"""Bounded screenshot capture supervisor; the Android clock owns measurement timestamps."""
import argparse
import json
from pathlib import Path
import queue
import subprocess
import threading
import time
from transport_status import transport_errors


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--adb',required=True,type=Path)
    p.add_argument('--out',required=True,type=Path);p.add_argument('--id',required=True)
    p.add_argument('--duration',type=int,default=3600000);p.add_argument('--sample',type=int,default=30000)
    args=p.parse_args()
    if not 1000<=args.duration<=3600000 or not 3000<=args.sample<=60000:raise ValueError('Invalid bounded duration')
    args.out.mkdir(parents=True,exist_ok=False)
    adb=[str(args.adb),'-s','emulator-5554']
    command=adb+['shell','am','instrument','-w','-r','-e','id',args.id,'-e','durationMillis',str(args.duration),
        '-e','sampleMillis',str(args.sample),'com.ahao.clicker.acceptance.target/.AccuracyClockAcceptance']
    report=dict(command=command,supervisorClock=time.get_clock_info('perf_counter').implementation,stoppedReason=None)
    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,bufsize=0)
    lines=queue.Queue(maxsize=256)
    def read():
        with (args.out/'instrumentation.txt').open('wb') as output:
            for line in iter(process.stdout.readline,b''):
                output.write(line);output.flush()
                try:lines.put_nowait(line.decode(errors='replace').strip())
                except queue.Full:pass
    reader=threading.Thread(target=read,daemon=True);reader.start()
    started=time.perf_counter();last=started;seen=False
    try:
        while process.poll() is None:
            while not lines.empty():
                line=lines.get_nowait()
                if line.startswith('INSTRUMENTATION_STATUS: heartbeatNs='):last=time.perf_counter();seen=True
            now=time.perf_counter()
            if now-started>args.duration/1000+120 or now-last>(5 if seen else 35):
                report['stoppedReason']='outer_timeout' if now-started>args.duration/1000+120 else 'heartbeat_stale'
                for package in ['com.ahao.clicker','com.ahao.clicker.acceptance.target']:
                    subprocess.run(adb+['shell','am','force-stop',package],capture_output=True,timeout=15)
                break
            time.sleep(.5)
    finally:
        try:process.wait(timeout=5)
        except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=5)
        reader.join(timeout=3)
        if process.returncode!=0:
            for package in ['com.ahao.clicker','com.ahao.clicker.acceptance.target']:
                stopped=subprocess.run(adb+['shell','am','force-stop',package],capture_output=True,timeout=20)
                (args.out/('transport-stop-'+package+'.txt')).write_bytes(stopped.stdout+stopped.stderr)
        for tag,remote in [('target-session','/sdcard/Android/data/com.ahao.clicker.acceptance.target/files/accuracy/'),
                           ('app-session','/sdcard/Android/data/com.ahao.clicker/files/v3/')]:
            result=subprocess.run(adb+['pull',remote+args.id,str(args.out/tag)],capture_output=True,timeout=60)
            (args.out/(tag+'-pull.txt')).write_bytes(result.stdout+result.stderr)
            report[tag+'PullExitCode']=result.returncode
        report.update(exitCode=process.returncode,elapsedSeconds=time.perf_counter()-started)
        (args.out/'host-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(report))
    errors=transport_errors(report,'exitCode',['target-sessionPullExitCode','app-sessionPullExitCode'])
    if errors:raise RuntimeError('Capture transport incomplete; see preserved host-report.json: '+','.join(errors))


if __name__=='__main__':main()
