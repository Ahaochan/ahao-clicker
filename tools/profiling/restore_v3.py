"""Restore the exact backed-up APK/preferences after this emulator-only V3 pilot."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def is_installed(packages,package):
    return 'package:'+package in {line.strip() for line in packages.splitlines()}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True,type=Path)
    parser.add_argument('run',type=Path)
    args=parser.parse_args()
    backup=args.run/'device-backup'
    for name in ['original-app.apk','preferences.xml','services.txt','stay-on.txt','screen-timeout.txt']:
        if not (backup/name).is_file():raise RuntimeError('Missing backup: '+name)
    out=args.run/'restoration';out.mkdir(exist_ok=False)
    adb=[str(args.adb),'-s','emulator-5554']
    operations=[]
    def invoke(name,*command):
        result=subprocess.run(adb+list(command),capture_output=True,timeout=90)
        (out/name).write_bytes(result.stdout+result.stderr)
        operations.append(dict(log=name,exitCode=result.returncode))
        if result.returncode:raise RuntimeError('Restore operation failed: '+name)
        return result.stdout
    try:
        query=invoke('trace-state-before.txt','shell','perfetto','--query').decode(errors='replace')
        if 'Tracing sessions: 0 (started: 0)' not in query:raise RuntimeError('Unexpected active trace; do not kill unrelated sessions')
        artifacts=args.run/'measurement-artifacts';artifacts.mkdir(exist_ok=False)
        for package,filename in [('com.ahao.clicker','measured.apk'),('com.ahao.clicker.acceptance.target','target.apk')]:
            value=invoke(filename+'-package-path.txt','shell','pm','path',package).decode().strip()
            if not value.startswith('package:') or '\n' in value:raise RuntimeError('Expected one measured base APK')
            invoke(filename+'-pull.txt','pull',value[len('package:'):],str(artifacts/filename))
            invoke(filename+'-package-state.txt','shell','dumpsys','package',package)
        invoke('stop-measured-app.txt','shell','am','force-stop','com.ahao.clicker')
        invoke('restore-install.txt','install','-r',str(backup/'original-app.apk'))
        invoke('stop-before-prefs.txt','shell','am','force-stop','com.ahao.clicker')
        remote='/data/local/tmp/ahao-v3-restore-preferences.xml'
        invoke('prefs-push.txt','push',str(backup/'preferences.xml'),remote)
        invoke('prefs-mode.txt','shell','chmod','644',remote)
        invoke('prefs-restore.txt','shell','run-as','com.ahao.clicker','cp',remote,'shared_prefs/clicker.xml')
        invoke('prefs-temp-remove.txt','shell','rm','-f',remote)
        prefs=invoke('prefs-restored.xml','exec-out','run-as','com.ahao.clicker','cat','shared_prefs/clicker.xml')
        if prefs!=(backup/'preferences.xml').read_bytes():raise RuntimeError('Restored preferences differ from backup')
        original_services=(backup/'services.txt').read_text(encoding='utf-8-sig').strip()
        remaining=':'.join(s for s in original_services.split(':') if s!='com.ahao.clicker/com.ahao.clicker.ClickService')
        if remaining:invoke('service-disconnect.txt','shell','settings','put','secure','enabled_accessibility_services',remaining)
        else:invoke('service-disconnect.txt','shell','settings','delete','secure','enabled_accessibility_services')
        invoke('start-ordinary.txt','shell','am','start','-n','com.ahao.clicker/.MainActivity')
        invoke('services-restore.txt','shell','settings','put','secure','enabled_accessibility_services',original_services)
        for file,scope,key in [('stay-on.txt','global','stay_on_while_plugged_in'),('screen-timeout.txt','system','screen_off_timeout')]:
            value=(backup/file).read_text(encoding='utf-8-sig').strip()
            if value=='null':invoke(key+'-restore.txt','shell','settings','delete',scope,key)
            else:invoke(key+'-restore.txt','shell','settings','put',scope,key,value)
            restored=invoke(key+'-verify.txt','shell','settings','get',scope,key).decode().strip()
            if restored!=value:raise RuntimeError(key+' not restored')
        initial=(args.run/'device-readonly/installed-test-packages.txt').read_text(encoding='utf-8-sig')
        if is_installed(initial,'com.ahao.clicker.acceptance.target'):raise RuntimeError('Target existed before pilot; manual original-package restoration required')
        invoke('uninstall-target.txt','uninstall','com.ahao.clicker.acceptance.target')
        # A full functional regression may have installed the separate test APK.
        # Remove it only when it did not exist in the original package inventory.
        test_packages=invoke('test-package-before-cleanup.txt','shell','pm','list','packages','com.ahao.clicker.test').decode()
        test_present=is_installed(test_packages,'com.ahao.clicker.test')
        if not is_installed(initial,'com.ahao.clicker.test') and test_present:
            invoke('uninstall-instrumentation.txt','uninstall','com.ahao.clicker.test')
        path=invoke('ordinary-package-path.txt','shell','pm','path','com.ahao.clicker').decode().strip()[len('package:'):]
        invoke('ordinary-package-pull.txt','pull',path,str(out/'ordinary-restored.apk'))
        installed=(out/'ordinary-restored.apk').read_bytes()
        expected=(backup/'original-app.apk').read_bytes()
        if hashlib.sha256(installed).digest()!=hashlib.sha256(expected).digest():raise RuntimeError('Restored APK differs from original backup')
        invoke('accessibility-state.txt','shell','dumpsys','accessibility')
        trace_after=invoke('trace-state-after.txt','shell','perfetto','--query').decode(errors='replace')
        if 'Tracing sessions: 0 (started: 0)' not in trace_after:raise RuntimeError('Unexpected active trace after restoration')
        packages=invoke('packages-after.txt','shell','pm','list','packages','com.ahao.clicker').decode().splitlines()
        if 'package:com.ahao.clicker.acceptance.target' in packages:raise RuntimeError('Target still installed')
        if 'package:com.ahao.clicker.test' not in initial and 'package:com.ahao.clicker.test' in packages:
            raise RuntimeError('New instrumentation still installed')
        services=invoke('services-verify.txt','shell','settings','get','secure','enabled_accessibility_services').decode().strip()
        if services!=original_services:raise RuntimeError('Accessibility services differ from original')
        prefs=invoke('prefs-after-startup.xml','exec-out','run-as','com.ahao.clicker','cat','shared_prefs/clicker.xml')
        if prefs!=(backup/'preferences.xml').read_bytes():raise RuntimeError('Preferences changed during restored startup')
        invoke('devices-after.txt','get-state')
        report=dict(passed=True,originalApkSha256=hashlib.sha256(expected).hexdigest(),preferencesExact=True,
                    preferencesVerifiedAfterStartup=True,traceSessionsZero=True,
                    servicesRestored=original_services,testTargetRemoved=True,
                    newlyInstalledInstrumentationRemoved=not is_installed(initial,'com.ahao.clicker.test') and test_present,
                    operations=operations)
    except Exception as error:
        report=dict(passed=False,error=str(error),operations=operations)
        (out/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        raise
    (out/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='operations'}))


if __name__=='__main__':
    main()
