"""Independent pixel/reference analysis; unknowns and raw samples are preserved."""
import argparse
import json
import math
from pathlib import Path
import re


def parse_time(value):
    if not re.fullmatch(r'\d{2}:\d{2}:\d{2}:\d{3}',value):raise ValueError('clock_format')
    hour,minute,second,millis=map(int,value.split(':'))
    if hour>23 or minute>59 or second>59:raise ValueError('clock_range')
    return ((hour*60+minute)*60+second)*1000+millis


def nearest_epoch(value,expected,zone_offset):
    local=parse_time(value)
    day=math.floor((expected+zone_offset)/86400000)
    return min((local+(day+d)*86400000-zone_offset for d in [-1,0,1]),key=lambda x:abs(x-expected))


def quantiles(values):
    if not values:raise ValueError('no_valid_samples')
    v=sorted(values)
    return dict(n=len(v),min=v[0],p50=v[math.ceil(len(v)*.5)-1],p95=v[math.ceil(len(v)*.95)-1],
        p99=v[math.ceil(len(v)*.99)-1],max=v[-1])


def offset_interval(probes,at_ns,ppm=500):
    valid=[p for p in probes if not p.get('error') and all(k in p for k in ['sentNs','receivedNs','serverEpoch'])]
    if len(valid)!=len(probes) or not valid:raise ValueError('missing_or_failed_reference')
    intervals=[]
    for p in valid:
        start=p['sentNs']/1e6;end=p['receivedNs']/1e6
        if not 0<=end-start<=2000:raise ValueError('reference_rtt')
        # Sensitivity assumption, not calibrated oscillator accuracy or a JD SLA.
        extra=max(abs(at_ns/1e6-start),abs(at_ns/1e6-end))*ppm/1e6+1
        intervals.append((p['serverEpoch']-end-extra,p['serverEpoch']-start+extra))
    lo=max(i[0] for i in intervals);hi=min(i[1] for i in intervals)
    if lo>hi:raise ValueError('reference_intervals_conflict')
    return lo,hi


def classify(lo,hi,limit=50):
    if lo>hi:raise ValueError('inverted_interval')
    if lo>=-limit and hi<=limit:return 'within'
    if lo>limit or hi<-limit:return 'outside'
    return 'inconclusive'


def decode_image(path,geometry,templates,local=False):
    import numpy as np
    from PIL import Image
    w,h=geometry['width'],geometry['height']
    image=Image.open(path).convert('RGB')
    if not local:
        x,y=geometry['x'],geometry['y']
        if x<0 or y<0 or x+w>image.width or y+h>image.height:raise ValueError('clock_outside_image')
        image=image.crop((x,y,x+w,y+h))
    if image.size!=(w,h):raise ValueError('image_geometry')
    bg=np.asarray(Image.open(templates/'template-0.png').convert('RGB'))[0,0].max()/255
    def ink(p):return np.clip((np.asarray(p,dtype=float).max(axis=2)/255-bg)/(1-bg),0,1)
    actual=ink(image)
    model=[ink(Image.open(templates/f'template-{i}.png').convert('RGB')) for i in range(10)]
    cell=min(w,geometry['textWidth'])/12
    chars=[];details=[]
    for pos in range(12):
        left=math.floor(pos*cell);right=math.floor((pos+1)*cell)
        a=actual[:,left:right];scores=[]
        for digit,t in enumerate(model):
            b=t[:,left:right]
            distances=[]
            for dx in [-1,0,1]:
                for dy in [-1,0,1]:
                    padded=np.pad(b,((1,1),(1,1)))
                    shifted=padded[1+dy:1+dy+h,1+dx:1+dx+b.shape[1]]
                    distances.append(float(abs(a-shifted).sum()/max(np.maximum(a,shifted).sum(),1)))
            scores.append((min(distances),digit))
        scores.sort()
        if pos in [2,5,8]:
            if scores[0][0]>.22:raise ValueError('colon_unrecognized')
            chars.append(':');continue
        best,digit=scores[0];margin=scores[1][0]-best
        details.append(dict(position=pos,digit=digit,distance=best,margin=margin))
        if best>.22 or margin<.04:raise ValueError('digit_ambiguous_'+str(pos))
        chars.append(str(digit))
    value=''.join(chars);parse_time(value)
    return dict(text=value,digits=details)


def aggregate(rows,key):
    values=[r[key] for r in rows if key in r and r[key] is not None and 'analysisError' not in r]
    if not values:return dict(n=0)
    return dict(signed=quantiles(values),absolute=quantiles([abs(x) for x in values]),
                pointEstimateOver50=sum(abs(x)>50 for x in values))


def clock(directory):
    raw=json.loads((directory/'result.json').read_text(encoding='utf-8'))
    quality_errors=[]
    recorder=raw.get('appRecorder',{})
    for key,expected in dict(mode='off',written=0,frames=0,dropped=0,observerFailures=0,error='').items():
        if recorder.get(key)!=expected:quality_errors.append('app_'+key)
    if raw.get('cleanupError'):quality_errors.append('cleanup_error')
    host=json.loads((directory.parent/'host-report.json').read_text(encoding='utf-8'))
    if host.get('stoppedReason') is not None or host.get('exitCode')!=0:quality_errors.append('host_failure')
    g=raw['geometry'];controls=[]
    for c in raw['decoderControls']:
        actual=decode_image(directory/c['file'],g,directory,True)
        if actual['text']!=c['expected']:raise ValueError('Decoder control failed')
        controls.append(dict(file=c['file'],expected=c['expected'],observed=actual['text']))
    rows=[]
    for item in raw['samples']:
        r=dict(slot=item['slot'],image=item.get('image'),rawError=item.get('error'))
        try:
            if item.get('error'):raise ValueError('collection_error')
            decoded=decode_image(directory/item['image'],g,directory);r.update(decoded)
            before,after=item['captureBeforeNs'],item['captureAfterNs'];mid=(before+after)/2
            if after<before:raise ValueError('capture_clock_reversed')
            r['captureDurationMs']=(after-before)/1e6
            probes=[item['oracleBefore'],item['oracleAfter']]
            lo,hi=offset_interval(probes,mid)
            expected=(lo+hi)/2+mid/1e6
            # Geometry is fixed; zoneOffset is recorded independently by Android.
            epoch=nearest_epoch(decoded['text'],expected,raw['zoneOffsetMillis'])
            r['screenErrorMs']=epoch-expected
            r['screenErrorBoundsMs']=[epoch-(hi+after/1e6),epoch-(lo+before/1e6)]
            r['screenClassification']=classify(*r['screenErrorBoundsMs'])
            state=item['appState'];state_ns=state['elapsed']*1e6
            lo2,hi2=offset_interval(probes,state_ns)
            offset=state['sourceEpoch']-state['elapsed']
            r['referenceErrorMs']=offset-(lo2+hi2)/2
            r['referenceErrorBoundsMs']=[offset-hi2,offset-lo2]
            r['referenceClassification']=classify(*r['referenceErrorBoundsMs'])
            r['anchorElapsed']=state['sourceAnchorElapsed'];r['syncAgeMs']=state['elapsed']-state['lastSuccessElapsed']
            r['fresh']=state['fresh'];r['legacyDrawCount']=state['draws']
            r['oracleRttMs']=[(p['receivedNs']-p['sentNs'])/1e6 for p in probes]
            if r['legacyDrawCount']!=0:raise ValueError('legacy_draw_observer_active')
        except Exception as error:r['analysisError']=str(error)
        rows.append(r)
    counts={k:sum(r.get('screenClassification')==k and 'analysisError'not in r for r in rows) for k in ['within','outside','inconclusive']}
    known=[r for r in rows if 'screenErrorMs'in r and 'analysisError'not in r]
    quarters={}
    if known:
        n=max(1,len(known)//4)
        quarters=dict(first=aggregate(known[:n],'screenErrorMs'),last=aggregate(known[-n:],'screenErrorMs'))
    return dict(collectionCompleted=raw.get('collectionCompleted'),qualityErrors=quality_errors,planned=raw['plannedSamples'],observed=len(rows),
        decoded=sum('text'in r for r in rows),valid=len(known),failed=sum('analysisError'in r for r in rows),
        screen=aggregate(rows,'screenErrorMs'),reference=aggregate(rows,'referenceErrorMs'),classification=counts,
        distinctAnchors=len({r['anchorElapsed'] for r in rows if 'anchorElapsed'in r}),
        captureDurationMs=quantiles([r['captureDurationMs'] for r in rows if 'captureDurationMs'in r]) if any('captureDurationMs'in r for r in rows) else dict(n=0),
        quarters=quarters,controls=controls,rows=rows,
        conditions='JD request timestamp during full request bracket; constant offset with +/-500ppm sensitivity; 1ms timestamp budget; composed pixels, not panel light',
        passed50ms=bool(not quality_errors and raw.get('collectionCompleted') and len(known)==raw['plannedSamples'] and counts['within']==len(known)))


def clicks(directory):
    from repair_report import batch
    quality=batch(directory,warmup=0)
    if not quality['collectorPassed']:quality['qualityErrors'].append('collector_not_completed')
    if quality['recordingMode']!='off':quality['qualityErrors'].append('not_off_mode')
    if quality['failureEvent']!=0:quality['qualityErrors'].append('failure_event')
    raw=json.loads((directory/'target-session/result.json').read_text(encoding='utf-8'))
    events=[json.loads(line) for line in (directory/'target-session/driver.jsonl').read_text(encoding='utf-8').splitlines() if line.strip()]
    rows=[]
    for attempt in quality['attempts']:
        record=dict(attemptId=attempt['attemptId'],localDownMs=attempt.get('downMs'),localUpMs=attempt.get('upMs'),
                    localClickMs=attempt.get('clickMs'))
        try:
            selected=[r for r in events if r.get('attemptId')==attempt['attemptId']]
            receipts=[r for r in selected if r['kind']=='receipt']
            if len(receipts)!=1:raise ValueError('missing_or_duplicate_receipt')
            receipt=receipts[0];down=receipt['downNs'];state=receipt['production']
            if receipt['downs']!=1 or receipt['ups']!=1 or receipt['clicks']!=1 or receipt['touchOpen'] or state['phase']!='COMPLETED':
                raise ValueError('invalid_actual_receipt')
            probes=[r for r in selected if r['kind']=='oracle']
            before=[r for r in probes if r.get('role')=='before'];after=[r for r in probes if r.get('role')=='after']
            if len(before)!=1 or len(after)!=1:raise ValueError('missing_or_duplicate_oracle_pair')
            if before[0].get('receivedNs',down)>=down or after[0].get('sentNs',0)<=down:raise ValueError('oracle_not_bracketing_down')
            lo,hi=offset_interval([before[0],after[0]],down)
            target=state['targetEpoch'];record['targetEpoch']=target
            record['externalDownErrorMs']=(lo+hi)/2+down/1e6-target
            record['externalDownErrorBoundsMs']=[lo+down/1e6-target,hi+down/1e6-target]
            record['classification']=classify(*record['externalDownErrorBoundsMs'])
            record['oracleRttMs']=[(p['receivedNs']-p['sentNs'])/1e6 for p in probes]
            record['independentReferenceOverlapsFinal750ms']=before[0]['receivedNs']/1e6>state['plannedElapsed']-750
        except Exception as e:record['analysisError']=str(e)
        rows.append(record)
    requested=raw['requestedSamples']
    counts={k:sum(r.get('classification')==k and 'analysisError'not in r for r in rows) for k in ['within','outside','inconclusive']}
    return dict(requested=requested,qualityErrors=quality['qualityErrors'],actualReceived=quality['actualReceived'],
        actualReceiveRate=quality['actualReceiveRate'],external=aggregate(rows,'externalDownErrorMs'),classification=counts,
        local=quality['metrics'],failedReference=sum('analysisError'in r for r in rows),rows=rows,
        conditions='JD request timestamp assumed during full request; +/-500ppm sensitivity; local deadline is millisecond-quantized',
        passed50ms=bool(not quality['qualityErrors'] and len(rows)==requested and quality['actualReceived']==requested and counts['within']==requested))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('directory',type=Path);parser.add_argument('--out',required=True,type=Path)
    parser.add_argument('--kind',choices=['clock','clicks'],default='clock')
    args=parser.parse_args()
    if args.out.exists():raise ValueError('Preserve previous analysis')
    result=(clock if args.kind=='clock' else clicks)(args.directory);args.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ['rows','controls','quarters']}))


if __name__=='__main__':main()
