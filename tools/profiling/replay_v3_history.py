"""Reanalyze historical traces in a new directory, preserving all original evidence."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--processor',required=True,type=Path)
    parser.add_argument('--history',required=True,type=Path)
    parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args();args.out.mkdir(exist_ok=False,parents=True)
    results=[]
    analyzer=Path(__file__).with_name('analyze.py').resolve()
    for trace in sorted(args.history.glob('*/capture.perfetto-trace')):
        source=trace.parent;destination=args.out/source.name;destination.mkdir()
        row=dict(id=source.name,historical=True,traceSha256=hashlib.sha256(trace.read_bytes()).hexdigest())
        for name in ['capture.perfetto-trace','result.json','mapping.txt','measured.apk','target.apk']:
            if (source/name).exists():shutil.copyfile(source/name,destination/name)
        if not (destination/'result.json').exists() or not (destination/'mapping.txt').exists():
            row.update(replayed=False,reason='missing_historical_result_or_mapping');results.append(row);continue
        with (destination/'replay.log').open('wb') as log:
            completed=subprocess.run([sys.executable,'-B',str(analyzer),'--processor',str(args.processor.resolve()),str(destination.resolve())],stdout=log,stderr=subprocess.STDOUT)
        row.update(replayed=completed.returncode==0,exitCode=completed.returncode)
        summary=destination/'analysis/summary.json'
        if summary.exists():
            value=json.loads(summary.read_text(encoding='utf-8'))
            row.update(traceHashMatches=value['traceSha256']==row['traceSha256'],quality=value['qualityNonzero'],
                       samplingCoverage=value['samplingCoverage'],clickWindows=len(value['clickWindows']))
        results.append(row)
    (args.out/'replay-index.json').write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(traces=len(results),replayed=sum(r['replayed'] for r in results),failures=[r for r in results if not r['replayed']])))


if __name__=='__main__':
    main()
