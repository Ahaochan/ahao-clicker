"""Fixed ABBA/BAAB public-UI discovery, 10 warmups +25 trials per batch.

Installs only the user-authorized test APK and reconnects this application's
existing accessibility service, preserving any other enabled services.
No trace/high-frequency observer; bounded failure-only scalar remains enabled.
Never retries a failed task or silently replaces a batch.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import re

from repair_report import batch


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    parser.add_argument('--adb',required=True,type=Path)
    parser.add_argument('--prefix',default='repair')
    args=parser.parse_args()
    adb=[str(args.adb),'-s','emulator-5554']
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,20}',args.prefix):raise ValueError('Invalid unique ID prefix')
    order=list('ABBABAAB')
    manifest=dict(order=order,warmupsPerBatch=10,measuredPerBatch=25,leadMillis=4000,setupBudgetMillis=15000,
                  observer='off',failureDiagnostics='failure-only bounded scalar, no successful-path events',
                  trace=False,runs=[],formalAcceptance=False,
                  decisionRule='receipt/engine/early nonregression; aggregate DOWN and click P50/P95/P99/max nonregression (1ms quantization tolerance); >=3/4 paired P95 gains >=1ms in both DOWN and click; independent confirmation required to retain')
    path=args.root/'discovery-matrix.json'
    if path.exists():raise ValueError('Existing matrix must remain immutable')
    boot=subprocess.run(adb+['shell','cat','/proc/sys/kernel/random/boot_id'],capture_output=True,timeout=20)
    if boot.returncode:raise RuntimeError('Cannot identify device boot')
    boot_id=boot.stdout.decode().strip()
    if not re.fullmatch(r'[0-9a-f-]{36}',boot_id):raise RuntimeError('Invalid boot identity')
    manifest['bootId']=boot_id
    path.write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    for index,variant in enumerate(order,1):
        name=f'discovery-{index:02}-{variant}'
        setup=args.root/(name+'-setup');setup.mkdir()
        def invoke(label,*command):
            result=subprocess.run(adb+list(command),capture_output=True,timeout=90)
            (setup/label).write_bytes(result.stdout+result.stderr)
            if result.returncode:raise RuntimeError('Setup failed: '+label)
            return result.stdout.decode().strip()
        current_boot=invoke('boot-id.txt','shell','cat','/proc/sys/kernel/random/boot_id')
        if current_boot!=boot_id:raise RuntimeError('Emulator restarted; cohort cannot resume')
        apk=args.root/('variant-'+variant)/'app.apk'
        sha=hashlib.sha256(apk.read_bytes()).hexdigest()
        invoke('install.txt','install','-r',str(apk))
        invoke('compile.txt','shell','cmd','package','compile','-f','-m','speed','com.ahao.clicker')
        target=invoke('target-path.txt','shell','pm','path','com.ahao.clicker.acceptance.target')
        if not target.startswith('package:/data/app/') or '\n' in target:raise RuntimeError('Unexpected target APK path')
        target_sha=invoke('target-sha.txt','shell','sha256sum',target[len('package:'):]).split()[0]
        expected_target=hashlib.sha256((args.root/'variant-A/target.apk').read_bytes()).hexdigest()
        if target_sha!=expected_target:raise RuntimeError('Target APK changed; do not compare these batches')
        services=invoke('services-before.txt','shell','settings','get','secure','enabled_accessibility_services')
        own='com.ahao.clicker/com.ahao.clicker.ClickService'
        other=':'.join(s for s in services.split(':') if s not in ('null',own,''))
        if other:invoke('disconnect.txt','shell','settings','put','secure','enabled_accessibility_services',other)
        else:invoke('disconnect.txt','shell','settings','delete','secure','enabled_accessibility_services')
        invoke('start.txt','shell','am','start','-n','com.ahao.clicker/.MainActivity')
        invoke('reconnect.txt','shell','settings','put','secure','enabled_accessibility_services',':'.join(filter(None,[other,own])))
        command=[sys.executable,'-B',str(Path(__file__).with_name('v3_host.py')),
                 '--adb',str(args.adb),'--out',str(args.root/name),'--id',args.prefix+'-'+name,
                 '--duration','0','--mode','off','--samples','35','--no-resources']
        with (args.root/(name+'-host.txt')).open('wb') as output:
            try:
                result=subprocess.run(command,stdout=output,stderr=subprocess.STDOUT,timeout=1250)
            except subprocess.TimeoutExpired:
                # The host normally stops earlier. If its own supervision stalls,
                # stop both owned packages before returning an invalid batch.
                invoke('outer-timeout-stop-app.txt','shell','am','force-stop','com.ahao.clicker')
                invoke('outer-timeout-stop-target.txt','shell','am','force-stop','com.ahao.clicker.acceptance.target')
                result=subprocess.CompletedProcess(command,124)
        output=args.root/name/'target-session/result.json'
        measured=batch(args.root/name,10) if output.exists() else None
        if measured:(args.root/name/'repair-report.json').write_text(json.dumps(measured,indent=2),encoding='utf-8')
        ok=result.returncode==0 and measured is not None and measured['collectorPassed']
        ending_boot=invoke('boot-id-after.txt','shell','cat','/proc/sys/kernel/random/boot_id')
        ok=ok and ending_boot==boot_id
        entry=dict(index=index,variant=variant,path=name,bootId=current_boot,
                   bootUnchanged=ending_boot==boot_id,apkSha256=sha,targetApkSha256=target_sha,collected=ok)
        manifest['runs'].append(entry)
        path.write_text(json.dumps(manifest,indent=2),encoding='utf-8')
        print(json.dumps(entry),flush=True)
        if not ok:raise RuntimeError('Discovery stopped; all failure data retained without retry')


if __name__=='__main__':main()
