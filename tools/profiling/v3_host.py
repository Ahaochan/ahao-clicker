"""Bounded emulator pilot supervision. Not a complete V3 formal matrix runner."""
import argparse
import ctypes
from ctypes import wintypes
import hashlib
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
from transport_status import transport_errors


class HeartbeatWatchdog:
    def __init__(self, now):
        self.last=now

    def observe(self, now):
        if now < self.last:
            raise ValueError('backwards host monotonic time')
        self.last=now

    def expired(self, now):
        return now-self.last > 5


def host_sample():
    idle,kernel,user=(wintypes.FILETIME() for _ in range(3))
    if not ctypes.windll.kernel32.GetSystemTimes(ctypes.byref(idle),ctypes.byref(kernel),ctypes.byref(user)):
        return {'reason':'GetSystemTimes_failed'}
    def ticks(value):
        return (value.dwHighDateTime<<32)|value.dwLowDateTime
    return dict(hostMonotonicNs=time.monotonic_ns(),hostEpochNs=time.time_ns(),
                idle100ns=ticks(idle),kernel100ns=ticks(kernel),user100ns=ticks(user),
                gpuReason='no_host_gpu_counter_in_this_pilot',emulatorProcessReason='not_collected_in_this_pilot')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True,type=Path)
    parser.add_argument('--serial',default='emulator-5554')
    parser.add_argument('--out',required=True,type=Path)
    parser.add_argument('--id',required=True)
    parser.add_argument('--duration',type=int,default=120000)
    parser.add_argument('--trace-config',type=Path)
    parser.add_argument('--mode',choices=['off','light','frame'],default='frame')
    parser.add_argument('--samples',type=int,default=1)
    parser.add_argument('--calibrate',action='store_true')
    parser.add_argument('--no-resources',action='store_true')
    parser.add_argument('--setup-budget',type=int,default=15000)
    parser.add_argument('--oracle',action='store_true',help='Independent JD request before/after each task; never writes app time')
    parser.add_argument('--lead',type=int,default=4000)
    controls=parser.add_mutually_exclusive_group()
    controls.add_argument('--late-prepare-control',action='store_true')
    controls.add_argument('--cancel-stall-probe',action='store_true')
    args=parser.parse_args()
    if not (args.duration==0 or 1000<=args.duration<=120000):
        raise ValueError('This pilot runner is limited to 120 seconds')
    if not 500<=args.lead<=3600000:raise ValueError('Bounded task lead required')
    if args.late_prepare_control or args.cancel_stall_probe:
        if (args.mode!='off' or args.samples!=1 or args.duration!=0 or args.setup_budget!=15000 or
            args.trace_config or args.calibrate or args.oracle or args.lead!=4000 or not args.no_resources or not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}',args.id)):
            raise ValueError('Controls require one off task, no trace/resources/calibration, fixed setup and short unique id')
    args.out.mkdir(exist_ok=False,parents=True)
    adb=[str(args.adb),'-s',args.serial]
    report={'id':args.id,'phase':'partial_capability_pilot','notFormal':True,'startedHostNs':time.monotonic_ns(),
            'hostSupervisionPeriodMillis':500,'heartbeatLimitMillis':5000,'resourcePeriodMillis':30000,
            'missingCapabilities':['paired_overhead_calibration','host_emulator_process_counters','full_clock_mapping']}
    def run(name,*command,timeout=20):
        started=time.monotonic_ns()
        completed=subprocess.run(adb+list(command),capture_output=True,timeout=timeout)
        (args.out/name).write_bytes(completed.stdout+completed.stderr)
        return dict(exitCode=completed.returncode,startedHostNs=started,finishedHostNs=time.monotonic_ns())
    trace_pid=None
    remote_trace='/data/misc/perfetto-traces/'+args.id+'.perfetto-trace'
    if args.trace_config:
        config=args.trace_config.read_bytes()
        (args.out/'config.pbtxt').write_bytes(config)
        report['traceConfigSha256']=hashlib.sha256(config).hexdigest()
        remote_config='/data/misc/perfetto-configs/'+args.id+'.pbtxt'
        run('trace-push.txt','push',str(args.trace_config),remote_config)
        started=subprocess.run(adb+['shell','perfetto','--txt','-c',remote_config,'-o',remote_trace,'--background-wait'],capture_output=True,timeout=20)
        (args.out/'trace-start.txt').write_bytes(started.stdout+started.stderr)
        value=started.stdout.decode(errors='replace').strip()
        if started.returncode or not value.isdecimal():
            raise RuntimeError('Trace failed to start; raw error preserved')
        trace_pid=value
    command=adb+['shell','am','instrument','-w','-r','-e','id',args.id,'-e','durationMillis',str(args.duration),
                 '-e','traceFrames',str(bool(args.trace_config)).lower(),'-e','mode',args.mode,
                 '-e','samples',str(args.samples),'-e','preservePickerMillis','true',
                 '-e','setupBudgetMillis',str(args.setup_budget),
                 '-e','calibrate',str(args.calibrate).lower(),'-e','oracle',str(args.oracle).lower(),'-e','leadMillis',str(args.lead)]
    if args.late_prepare_control:command+=['-e','latePrepareControl','true']
    if args.cancel_stall_probe:command+=['-e','cancelStallProbe','true']
    command+=['com.ahao.clicker.acceptance.target/.V3Acceptance']
    report['command']=command
    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,bufsize=0)
    incoming=queue.Queue(maxsize=2048)
    reader_overflow=threading.Event()
    def read_lines():
        with (args.out/'instrumentation.txt').open('wb') as output:
            for line in iter(process.stdout.readline,b''):
                output.write(line);output.flush()
                try:incoming.put_nowait((time.monotonic(),line.decode(errors='replace').strip()))
                except queue.Full:reader_overflow.set()
    reader=threading.Thread(target=read_lines,daemon=True);reader.start()
    watch=HeartbeatWatchdog(time.monotonic())
    critical=False
    stop=threading.Event()
    resource_log=args.out/'resources.jsonl'
    def resources():
        slot=0
        while not stop.is_set():
            started=time.monotonic_ns()
            if critical:
                entry=dict(slot=slot,plannedHostNs=started,reason='deferred_critical_region')
            else:
                paths={}
                for package in ['com.ahao.clicker','com.ahao.clicker.acceptance.target','traced','traced_probes']:
                    filename=f'meminfo-{slot}-{package}.txt'
                    paths[package]=dict(path=filename,**run(filename,'shell','dumpsys','meminfo',package,timeout=10))
                entry=dict(slot=slot,plannedHostNs=started,finishedHostNs=time.monotonic_ns(),packages=paths)
            with resource_log.open('a',encoding='utf-8') as output:
                output.write(json.dumps(entry)+'\n')
            slot+=1
            stop.wait(30)
    sampler=threading.Thread(target=resources,daemon=True)
    if not args.no_resources:sampler.start()
    next_host=time.monotonic()
    outer=time.monotonic()+args.duration/1000+args.samples*(args.setup_budget/1000+args.lead/1000+15)+65
    reason=None
    try:
        with (args.out/'host.jsonl').open('w',encoding='utf-8') as host, (args.out/'heartbeats.jsonl').open('w',encoding='utf-8') as pulses:
            while process.poll() is None:
                now=time.monotonic()
                while not incoming.empty():
                    received,line=incoming.get_nowait()
                    if line.startswith('INSTRUMENTATION_STATUS: heartbeatNs='):
                        watch.observe(received)
                        pulses.write(json.dumps(dict(receivedHostNs=int(received*1e9),deviceElapsedNs=int(line.split('=',1)[1])))+'\n');pulses.flush()
                    if line.startswith('INSTRUMENTATION_STATUS: critical='):
                        critical=line.endswith('true')
                if now>=next_host:
                    host.write(json.dumps(host_sample())+'\n');host.flush();next_host=now+1
                    if args.duration and not args.no_resources:
                        # CPU ticks and PID are kept together; PID changes invalidate a pair.
                        run('proc-'+str(time.monotonic_ns())+'.txt','shell',
                            'cat /proc/uptime; for p in $(pidof com.ahao.clicker com.ahao.clicker.acceptance.target traced traced_probes); do cat /proc/$p/stat; done')
                if watch.expired(now) or reader_overflow.is_set() or now>outer:
                    reason='heartbeat_stale' if watch.expired(now) else 'reader_overflow' if reader_overflow.is_set() else 'outer_timeout'
                    run('emergency-stop-app.txt','shell','am','force-stop','com.ahao.clicker')
                    run('emergency-stop-target.txt','shell','am','force-stop','com.ahao.clicker.acceptance.target')
                    break
                time.sleep(.5)
    finally:
        stop.set()
        if sampler.is_alive():sampler.join(timeout=22)
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=5)
        reader.join(timeout=3)
        if process.returncode!=0:
            run('transport-stop-app.txt','shell','am','force-stop','com.ahao.clicker')
            run('transport-stop-target.txt','shell','am','force-stop','com.ahao.clicker.acceptance.target')
        if trace_pid:
            run('trace-stop.txt','shell','kill','-INT',trace_pid)
            time.sleep(2)
            run('trace-pull.txt','pull',remote_trace,str(args.out/'capture.perfetto-trace'),timeout=60)
        report['targetPullExitCode']=run('pull-target.txt','pull','/sdcard/Android/data/com.ahao.clicker.acceptance.target/files/v3/'+args.id,str(args.out/'target-session'))['exitCode']
        app_id=args.id
        result_path=args.out/'target-session/result.json'
        if result_path.exists():
            app_id=json.loads(result_path.read_text(encoding='utf-8')).get('appSessionId',args.id)
        if app_id!=args.id:
            if not args.cancel_stall_probe or app_id!=args.id+'_restart':raise ValueError('Unexpected application session identity')
            run('pull-app-initial.txt','pull','/sdcard/Android/data/com.ahao.clicker/files/v3/'+args.id,str(args.out/'app-session-initial'))
        report['appPullExitCode']=run('pull-app.txt','pull','/sdcard/Android/data/com.ahao.clicker/files/v3/'+app_id,str(args.out/'app-session'))['exitCode']
        report.update(stoppedReason=reason,instrumentExitCode=process.returncode,finishedHostNs=time.monotonic_ns())
        (args.out/'host-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(report,ensure_ascii=False))
    errors=transport_errors(report,'instrumentExitCode',['targetPullExitCode','appPullExitCode'])
    if errors:raise RuntimeError('Capture transport incomplete; see preserved host-report.json: '+','.join(errors))


if __name__=='__main__':
    main()
