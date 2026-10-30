"""Predeclared three balanced blocks, no retries or performance pass inferred."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    parser.add_argument('--adb',required=True,type=Path)
    parser.add_argument('--trace-config',required=True,type=Path)
    args=parser.parse_args()
    order=[['off','light','frame'],['frame','off','light'],['light','frame','off']]
    manifest=dict(order=order,durationMillis=60000,runs=[],performancePassed=False)
    path=args.root/'overhead-matrix.json'
    if path.exists():raise ValueError('Never overwrite an existing matrix')
    path.write_text(json.dumps(manifest,indent=2),encoding='utf-8')
    for block,modes in enumerate(order,1):
        for mode in modes:
            name=f'overhead-{block}-{mode}'
            command=[sys.executable,'-B',str(Path(__file__).with_name('v3_host.py')),
                     '--adb',str(args.adb),'--out',str(args.root/name),'--id','repair-'+name,
                     '--duration','60000','--mode',mode]
            if mode=='frame':command+=['--trace-config',str(args.trace_config)]
            with (args.root/(name+'-host.txt')).open('wb') as log:
                result=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,timeout=190)
            report=args.root/name/'target-session/result.json'
            collected=json.loads(report.read_text(encoding='utf-8')) if report.exists() else {}
            ok=result.returncode==0 and collected.get('passed',False)
            manifest['runs'].append(dict(block=block,mode=mode,path=name,collected=ok))
            path.write_text(json.dumps(manifest,indent=2),encoding='utf-8')
            print(json.dumps(manifest['runs'][-1]),flush=True)
            if not ok:raise RuntimeError('Matrix stopped; failed run preserved, no automatic retry')


if __name__=='__main__':main()
