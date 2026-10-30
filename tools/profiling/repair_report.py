"""Repair-run reports: all denominators, signed entry latencies, no invented failures."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re

from v3_analysis import distribution


def quality(directory,result):
    errors=[]
    app=result.get('appRecorder',{})
    if not isinstance(app,dict):app={};errors.append('invalid_app_metadata')
    def expected(values,entries,prefix):
        for key,value in entries.items():
            if key not in values:errors.append('missing_'+prefix+key)
            elif values[key]!=value:errors.append(prefix+key)
    expected(app,dict(dropped=0,observerFailures=0,error=''),'app_')
    expected(result,dict(targetDropped=0,collectorFailure=''),'target_')
    if 'mode' not in app:errors.append('missing_app_mode')
    elif app['mode'] not in ('off','light','frame'):errors.append('invalid_app_mode')
    for key in ('written','frames'):
        if type(app.get(key)) is not int or app[key]<0:errors.append('invalid_app_'+key)
    if app.get('mode')=='off':
        expected(app,dict(written=0,frames=0),'off_app_')
        for name,relative in [('app','app-session/events.bin'),('target','target-session/receipts.bin')]:
            path=directory/relative
            if not path.is_file():errors.append('missing_off_'+name+'_journal')
            elif path.stat().st_size!=0:errors.append('off_'+name+'_journal_nonempty')
    try:
        host=json.loads((directory/'host-report.json').read_text(encoding='utf-8'))
        if not isinstance(host,dict):raise ValueError('host report is not an object')
        expected(host,dict(stoppedReason=None,instrumentExitCode=0),'host_')
    except (OSError,ValueError):errors.append('missing_or_invalid_host_report')
    return sorted(set(errors))


def batch(directory, warmup=0):
    result=json.loads((directory/'target-session/result.json').read_text(encoding='utf-8'))
    with (directory/'target-session/driver.jsonl').open(encoding='utf-8') as journal:
        rows=[json.loads(line) for line in journal]
    receipts=[r for r in rows if r['kind']=='receipt']
    requested=result.get('requestedSamples',0)
    output=[]
    by_id=defaultdict(list)
    for r in receipts:by_id[r['attemptId']].append(r)
    for attempt in range(1,requested+1):
        candidates=by_id[attempt]
        item=dict(attemptId=attempt,warmup=attempt<=warmup,outcome='not_observed')
        if len(candidates)==1:
            r=candidates[0];p=r.get('production',{});deadline=p.get('plannedElapsed')
            good=r['downs']==r['ups']==r['clicks']==1 and r['point']==66 and not r['touchOpen']
            item.update(raw=r,outcome='exactly_once_B' if good else 'invalid_receipt',deadlineMs=deadline,
                        phase=p.get('phase'),armLeadMs=deadline-p['armedElapsed'] if deadline and p.get('armedElapsed') else None)
            for name,key in [('down','downNs'),('up','upNs'),('click','clickNs')]:
                item[name+'Ms']=r[key]/1e6-deadline if deadline and r.get(key,0)>0 else None
            item['dispatchRecordedMs']=p['dispatchedElapsed']-deadline if deadline and p.get('dispatchedElapsed') else None
        elif len(candidates)>1:item['outcome']='duplicate_receipt_rows'
        output.append(item)
    measured=[r for r in output if not r['warmup']]
    actual=sum(r['outcome']=='exactly_once_B' for r in measured)
    errors=quality(directory,result)
    recorder=result.get('appRecorder') if isinstance(result.get('appRecorder'),dict) else {}
    return dict(requested=len(measured),warmupRequested=min(warmup,requested),observed=len(receipts),
                actualReceived=actual,actualReceiveRate=actual/len(measured) if measured else None,
                missingOrInvalid=len(measured)-actual,collectorPassed=result.get('passed',False),
                qualityErrors=errors,qualityPassed=not errors,recordingMode=recorder.get('mode'),
                failureDiagnosticsPresent=type(recorder.get('failureEvent')) is int,
                failureEvent=recorder.get('failureEvent'),
                metrics={key:distribution([r[key] for r in measured if r.get(key) is not None],
                          None if key=='armLeadMs' else 50)
                         for key in ['dispatchRecordedMs','downMs','upMs','clickMs','armLeadMs']},
                attempts=output,clockUncertainty='deadline quantized to ms; target elapsedRealtimeNanos entry; no absolute source claim')


def cpu_samples(directory, stable_start, duration):
    grouped=defaultdict(list)
    for path in directory.glob('proc-*.txt'):
        lines=path.read_text(encoding='utf-8',errors='replace').splitlines()
        if not lines:continue
        try:stamp=float(lines[0].split()[0])
        except ValueError:continue
        if not stable_start/1000+2<=stamp<=(stable_start+duration)/1000-2:continue
        for line in lines[1:]:
            match=re.fullmatch(r'(\d+) \(([^)]+)\) (.*)',line)
            if not match:continue
            values=match[3].split()
            grouped[(int(match[1]),match[2])].append((stamp,int(values[11])+int(values[12])))
    result=[]
    for (pid,name),samples in grouped.items():
        samples.sort();first,last=samples[0],samples[-1]
        seconds=last[0]-first[0]
        result.append(dict(pid=pid,name=name,seconds=seconds,points=len(samples),
                           cpuOneCorePercent=(last[1]-first[1])/seconds if seconds>0 else None,
                           userHz=100,uptimeQuantizationMillis=10))
    return result


def resources(directory):
    raw=json.loads((directory/'target-session/result.json').read_text(encoding='utf-8'))
    pss=defaultdict(list)
    # 0 is startup; 30s and 60s samples fall inside the stable 60s window.
    for path in directory.glob('meminfo-*.txt'):
        match=re.fullmatch(r'meminfo-(\d+)-(.+)\.txt',path.name)
        if not match or int(match[1])==0:continue
        found=re.search(r'TOTAL PSS:\s*([0-9]+)',path.read_text(encoding='utf-8',errors='replace'))
        if found:pss[match[2]].append(dict(path=path.name,kib=int(found[1])))
    return dict(cpu=cpu_samples(directory,raw['stableStartMs'],raw['durationMillis']),pss=dict(pss))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory',type=Path);parser.add_argument('--warmup',type=int,default=0)
    parser.add_argument('--resources',action='store_true')
    args=parser.parse_args()
    result=resources(args.directory) if args.resources else batch(args.directory,args.warmup)
    (args.directory/'repair-report.json').write_text(json.dumps(result,ensure_ascii=True,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='attempts'},ensure_ascii=True))


if __name__=='__main__':main()
