"""Offline measurement-admission analysis. Standard library; never invent missing values."""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import struct


def distribution(values, limit=50):
    values = sorted(values)
    if not values:
        return None
    result=dict(n=len(values), min=values[0], max=values[-1],
                **{f'p{q}': values[math.ceil(q * len(values) / 100) - 1] for q in (50, 95, 99)})
    if limit is not None:result.update(over50=sum(v > limit for v in values),early=sum(v < 0 for v in values))
    return result


def decode_records(data):
    if len(data) % 64:
        raise ValueError('truncated binary journal')
    rows = []
    previous = 0
    for sequence, ns, tid, packed, a, b, c, d in struct.iter_unpack('<8q', data):
        if sequence != previous + 1:
            raise ValueError(f'sequence discontinuity {previous}->{sequence}')
        previous = sequence
        rows.append(dict(sequence=sequence, elapsedNs=ns, tid=tid, event=packed & 0xffffffff,
                         attemptId=packed >> 32, a=a, b=b, c=c, d=d))
    return rows


def parse_reference(status, values, age):
    if not 200 <= status < 300:
        raise ValueError('http_status')
    if age is not None and (not re.fullmatch(r'[0-9]+', age) or int(age) != 0):
        raise ValueError('cache_age')
    unique = {value.strip(' \t\r\n\v\f') for value in values}
    if len(unique) != 1:
        raise ValueError('missing_or_conflicting_header')
    match = re.fullmatch(r'[0-9]+-[0-9]+-([0-9]{13})', next(iter(unique)))
    if not match:
        raise ValueError('header_format')
    epoch = int(match[1])
    if not 946684800000 <= epoch < 4102444800000:
        raise ValueError('epoch_range')
    return epoch


def reference_interval(row, ns):
    if row.get('error'):
        raise ValueError('request_error: ' + row['error'])
    epoch = parse_reference(row['httpStatus'], row.get('rawRequestIdValues', []), row.get('age'))
    sent, received = row['sentNs'], row['receivedNs']
    if received < sent:
        raise ValueError('reversed_request')
    age = abs(ns - received) / 1e6
    if age > 5000:
        raise ValueError('reference_age')
    # Source timestamp may occur anywhere between request start and header receipt.
    # The full interval includes connect, plus conservative +/-1ms quantization.
    return dict(lower=epoch + (ns - received) / 1e6 - 1,
                upper=epoch + (ns - sent) / 1e6 + 1,
                estimate=epoch + (ns - (sent + received) / 2) / 1e6,
                ageMillis=age, rttMillis=(received-sent)/1e6, modelConditional=True)


def paired_interval(left, right):
    if max(left['lower'], right['lower']) > min(left['upper'], right['upper']):
        raise ValueError('reference_conflict')
    return dict(lower=min(left['lower'], right['lower']), upper=max(left['upper'], right['upper']),
                estimates=[left['estimate'], right['estimate']], modelConditional=True)


def classify_interval(lower, upper, allowed_lower, allowed_upper):
    if lower > upper:
        raise ValueError('reversed_interval')
    if lower >= allowed_lower and upper <= allowed_upper:
        return 'conditional_within'
    if upper < allowed_lower or lower > allowed_upper:
        return 'conditional_outside'
    return 'inconclusive'


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)+'\n', encoding='utf-8')


def save_rows(path, rows):
    path.write_text(''.join(json.dumps(row, ensure_ascii=False, allow_nan=False)+'\n' for row in rows), encoding='utf-8')


def task_chains(app, target):
    """The first terminal after ARM ends the engine task; later STOP is cleanup."""
    results=[]
    for arm in (event for event in app if event['event']==10):
        attempt=arm['attemptId']
        scoped=[e for e in app if e['attemptId']==attempt and e['elapsedNs']>=arm['elapsedNs']]
        terminal=next((e for e in scoped if e['event']==60),None)
        end=terminal['elapsedNs'] if terminal else math.inf
        engine=[e for e in scoped if e['elapsedNs']<=end]
        rebases=[e for e in engine if e['event']==12]
        deadline=rebases[-1]['b'] if rebases else arm['b']
        def first(code, events):
            return next((e for e in events if e['event']==code),None)
        dispatch=first(114,engine)
        receipts=[e for e in target if e['attemptId']==attempt and e['elapsedNs']>=arm['elapsedNs']]
        down,up,click=(first(code,receipts) for code in (400,401,420))
        counts={str(code):sum(e['event']==code for e in receipts) for code in (400,401,403,420)}
        outcome='zero_receipts' if not down and not click else 'unknown_or_unclosed'
        if counts['400']==counts['401']==counts['420']==1 and not counts['403']:
            outcome='exactly_once_B' if click['a']==66 else 'wrong_point'
        elif counts['420']>1 or counts['400']>1:
            outcome='duplicate_or_ambiguous'
        local={name:(e['elapsedNs']/1e6-deadline if e else None) for name,e in [('dispatch',dispatch),('down',down),('click',click)]}
        def elapsed(left,right):
            return (right['elapsedNs']-left['elapsedNs'])/1e6 if left and right else None
        entered=first(40,engine)
        results.append(dict(attemptId=attempt,targetEpoch=arm['a'],initialDeadlineMs=arm['b'],finalDeadlineMs=deadline,
                            actualLeadMillis=arm['b']-arm['elapsedNs']/1e6,rebases=rebases,engineTerminal=terminal,
                            receiptCounts=counts,outcome=outcome,localMillis=local,
                            stagesMillis=dict(deadlineCallback=elapsed(None,entered) if not entered else entered['elapsedNs']/1e6-deadline,
                                              callbackToDispatch=elapsed(entered,dispatch),dispatchToDown=elapsed(dispatch,down),
                                              downToUp=elapsed(down,up),upToClick=elapsed(up,click)),
                            local50msDiagnostic=outcome=='exactly_once_B' and all(v is not None and 0<=v<=50 for v in local.values()),
                            formalAcceptance=False,rawArm=arm,rawDispatch=dispatch,rawDown=down,rawUp=up,rawClick=click))
    return results


def clock_brackets(brackets, counters, snapshots):
    """Trace primary BOOTTIME is elapsedRealtimeNanos; marker call is an interval.

    A long bracket is scheduling noise, never a clock-offset estimate. Reject
    missing/duplicate counters and non-identity BOOTTIME snapshots explicitly.
    Uptime remains millisecond-quantized and is not used to fit elapsed time.
    """
    by_id=defaultdict(list)
    for row in counters:by_id[int(row['value'])].append(row)
    results=[]
    for row in brackets:
        markers=by_id[row['id']]
        valid=len(markers)==1 and row['beforeNs']<=markers[0]['ts']<=row['afterNs']
        width=row['afterNs']-row['beforeNs']
        results.append(dict(row,widthNs=width,contained=valid,narrow=valid and 0<=width<=500_000))
    boot=[r for r in snapshots if r['clockId']==6]
    grouped=defaultdict(dict)
    for row in snapshots:
        grouped[row.get('snapshotId',row['ts'])][row['clockId']]=row
    offsets=[group[6]['value']-group[3]['value'] for group in grouped.values() if 6 in group and 3 in group]
    # Snapshot clock reads are sequential, not atomic; retain their variation.
    spread=max(offsets)-min(offsets) if offsets else None
    return dict(boottimeIdentity=bool(boot) and all(r['ts']==r['value'] for r in boot),
                outsideBracket=sum(not r['contained'] for r in results),
                narrowCount=sum(r['narrow'] for r in results),brackets=results,
                clockSnapshotCount=len(boot),monotonicBootOffsetsNs=offsets,
                uptimeQuantizationNs=1_000_000,
                conversionUncertaintyNs=max((r['widthNs'] for r in results),default=0)+1_000_000+spread if spread is not None else None)


def associate_frames(trace_rows, draws, window=None):
    """Use drawId counter containment, slice ancestry and exact surface/display tokens."""
    slices={r['id']:r for r in trace_rows if r['kind']=='slice'}
    counters=[r for r in trace_rows if r['kind']=='counter' and r['name']=='clock_draw_id']
    frame_by_id={r['frameId']:r for r in draws}
    surface=defaultdict(list)
    display=defaultdict(list)
    for row in trace_rows:
        if row['kind']!='frame':
            continue
        if row['process']=='com.ahao.clicker':
            surface[(row['pid'],row['surfaceToken'])].append(row)
        elif row['process']=='/system/bin/surfaceflinger':
            display[row['displayToken']].append(row)
    output=[]
    seen=set()
    for draw_slice in (r for r in slices.values() if r['name']=='clock_draw'):
        item=dict(drawSliceId=draw_slice['id'],presentedNs=None,associationMethod='drawId_containment_parent_surfaceToken_displayToken')
        ids=[c for c in counters if c['pid']==draw_slice['pid'] and draw_slice['ts']<=c['ts']<draw_slice['ts']+draw_slice['dur']]
        if len(ids)!=1:
            output.append(dict(item,reason='missing_or_ambiguous_draw_counter'));continue
        frame_id=int(ids[0]['value']);raw=frame_by_id.get(frame_id)
        if not raw or frame_id in seen:
            output.append(dict(item,frameId=frame_id,reason='missing_or_duplicate_binary_draw'));continue
        seen.add(frame_id)
        item.update(raw)
        item.update(markerCallDelayNs=ids[0]['ts']-raw['drawBeginNs'],drawTraceBeginNs=draw_slice['ts'])
        tokens=[]
        hwui_tokens=[]
        window_found=window is None
        node=draw_slice
        visited=set()
        while node and node['id'] not in visited:
            visited.add(node['id'])
            original=re.fullmatch(r'Choreographer#doFrame ([0-9]+)',node['name'])
            if original:hwui_tokens.append(int(original[1]))
            for pattern in (r'Choreographer#doFrame ([0-9]+)',r'Choreographer#doFrame - resynced to ([0-9]+)'):
                match=re.match(pattern,node['name'])
                if match:tokens.append(int(match[1]))
            if window is not None and node['name']=='draw-VRI['+window+']':window_found=True
            node=slices.get(node['parent'])
        item['ancestorTokens']=tokens
        if not window_found:
            output.append(dict(item,reason='missing_window_ancestry'));continue
        # AOSP Choreographer stores the original vsyncEventData in mFrameInfo for
        # HWUI; the nested "resynced to" describes the animation timeline. Never
        # select a future frame just because its token appears in that label.
        if len(hwui_tokens)!=1:
            output.append(dict(item,reason='missing_or_ambiguous_hwui_token'));continue
        effective=hwui_tokens[0]
        candidates={r['id']:r for r in surface[(draw_slice['pid'],effective)]
                    if window is None or re.fullmatch(r'TX - VRI-'+re.escape(window)+r'#\d+',r.get('layer') or '')}
        item['effectiveToken']=effective
        if len(candidates)!=1:
            output.append(dict(item,reason='ambiguous_surface_frame' if candidates else 'missing_surface_frame',candidateSurfaceIds=sorted(candidates)));continue
        app=next(iter(candidates.values()))
        item.update(surfaceFrameId=app['id'],surfaceToken=app['surfaceToken'],displayToken=app['displayToken'],layer=app['layer'])
        if app['dur']<0 or app.get('presentType')=='Dropped Frame':
            output.append(dict(item,reason='app_frame_dropped_or_incomplete'));continue
        if 'presentType' in app and app['presentType'] in (None,'Unknown','Unspecified','Unknown Present','Unspecified Present','unknown','unspecified','',0):
            output.append(dict(item,reason='unknown_surface_present_type'));continue
        if app.get('isBuffer') in (0,False,'No'):
            output.append(dict(item,reason='no_buffer'));continue
        sf=display[app['displayToken']]
        if len(sf)!=1:
            output.append(dict(item,reason='missing_or_ambiguous_display_frame'));continue
        actual=sf[0]
        if 'presentType' in actual and actual['presentType'] in (None,'Unknown','Unspecified','Unknown Present','Unspecified Present','unknown','unspecified','',0):
            output.append(dict(item,reason='unknown_display_present_type'));continue
        presented=actual['ts']+actual['dur']
        if actual['dur']<0 or presented<raw['drawEndNs']:
            output.append(dict(item,reason='incomplete_or_reversed_display'));continue
        item.update(displayFrameId=actual['id'],presentedNs=presented,reason=None,
                    drawToPresentMillis=(presented-raw['drawBeginNs'])/1e6)
        output.append(item)
    for raw in draws:
        if raw['frameId'] not in seen:
            output.append(dict(raw,presentedNs=None,reason='binary_draw_missing_from_trace'))
    return output


def oracle_analysis(rows):
    grouped = defaultdict(dict)
    requests = []
    for row in rows:
        if row['kind'] != 'oracle':
            continue
        entry = dict(row)
        try:
            entry['intervalAtReceipt'] = reference_interval(row, row['receivedNs'])
            entry['valid'] = True
        except (ValueError, KeyError) as error:
            entry.update(valid=False, reason=str(error))
        requests.append(entry)
        grouped[row['attemptId']][row['role']] = row
    pairs = []
    for attempt, pair in grouped.items():
        result = dict(attemptId=attempt)
        try:
            before, after = pair['before'], pair['after']
            at = (before['receivedNs'] + after['sentNs']) // 2
            left, right = reference_interval(before, at), reference_interval(after, at)
            result.update(before=left, after=right)
            hull = paired_interval(left, right)
            result.update(hull=hull, widthMillis=hull['upper']-hull['lower'], valid=True)
        except (ValueError, KeyError) as error:
            result.update(valid=False, reason=str(error))
        pairs.append(result)
    return dict(plannedPairs=20, recordedPairs=len(pairs), recordedRequests=len(requests),
                validRequests=sum(r['valid'] for r in requests), validPairs=sum(p['valid'] for p in pairs),
                narrowPairs=sum(p.get('widthMillis', math.inf) <= 50 for p in pairs),
                fullRttMillis=distribution([r['intervalAtReceipt']['rttMillis'] for r in requests if r['valid']]),
                pairWidthMillis=distribution([p['widthMillis'] for p in pairs if p['valid']]),
                requests=requests, pairs=pairs,
                purpose='reference_screening_only; paired requests without public click tasks are not P admission',
                unknownSourceSemantics=True)


def analyze_pilot(directory):
    output = directory / 'offline'
    output.mkdir(exist_ok=True)
    result_file = directory / 'target-session/result.json'
    result = read_json(result_file) if result_file.exists() else None
    driver_file = directory / 'target-session/driver.jsonl'
    if driver_file.exists():
        with driver_file.open(encoding='utf-8') as journal:rows=[json.loads(line) for line in journal]
    else:rows=[]
    summary = dict(id=directory.name, phase='pilot_not_formal', result=result,
                   collectorCompleted=bool(result and result.get('passed')), qualityErrors=[],
                   performanceAdmission='not_passed', presentation='not_measured')
    journals = {}
    for name, relative in [('app','app-session/events.bin'), ('target','target-session/receipts.bin')]:
        path = directory / relative
        if not path.exists():
            continue
        try:
            journals[name] = decode_records(path.read_bytes())
            save_rows(output / (name+'-events.jsonl'), journals[name])
        except ValueError as error:
            summary['qualityErrors'].append(name+': '+str(error))
    summary['journalCounts'] = {name:len(events) for name, events in journals.items()}
    for process, recorder in [('app',(result or {}).get('appRecorder',{})),('target',result or {})]:
        for field in (('dropped','observerFailures') if process=='app' else ('targetDropped',)):
            if recorder.get(field,0):
                summary['qualityErrors'].append(f'{process}:{field}={recorder[field]}')
    chains=task_chains(journals.get('app',[]),journals.get('target',[]))
    save_json(output/'task-chains.json',chains)
    summary['taskChains']=chains
    frames = []
    reference = None
    frame = None
    for event in journals.get('app', []):
        code = event['event']
        if code == 130:
            reference = dict(epoch=event['a'], elapsedMs=event['b'], synced=event['c'], observedNs=event['elapsedNs'])
        elif code == 200:
            frame = dict(frameId=event['b'], drawBeginNs=event['elapsedNs'], attemptId=event['attemptId'])
        elif code == 201 and frame is not None:
            frame.update(epoch=event['a'], readObservedNs=event['elapsedNs'], reference=reference)
        elif code == 202 and frame is not None:
            frame.update(drawEndNs=event['elapsedNs'], presentedNs=None, presentationReason='no_display_frame_association')
            frames.append(frame)
            frame = None
    save_rows(output/'draw-frames.jsonl', frames)
    summary['completeDrawFrames'] = len(frames)
    summary['incompleteDrawFrame'] = frame
    trace_export=directory/'frame-export.csv'
    if trace_export.exists():
        with trace_export.open(encoding='utf-8-sig') as export:
            trace_rows=[json.loads(line.rstrip('\r\n')[1:-1]) for line in export if line.startswith('"{')]
        linked=associate_frames(trace_rows,frames,window=(result or {}).get('windowTitle'))
        save_rows(output/'presentation-links.jsonl',linked)
        good=[f for f in linked if f.get('presentedNs') is not None]
        reasons=defaultdict(int)
        for f in linked:
            reasons[f.get('reason') or 'linked']+=1
        quality=[r for r in trace_rows if r['kind']=='quality' and (r['severity']!='info' or any(k in r['name'] for k in ('lost','discard','overrun','skipped','error')))]
        summary['traceQuality']=quality
        summary['clockAlignment']=clock_brackets(
            [dict(id=e['a'],beforeNs=e['b'],afterNs=e['c'],uptimeMs=e['d'])
             for e in journals.get('app',[]) if e['event']==210],
            [r for r in trace_rows if r['kind']=='counter' and r['name']=='clock_bracket'],
            [r for r in trace_rows if r['kind']=='clock'])
        summary['presentation']='token_associated_diagnostics_not_admitted'
        summary['qualityErrors'] += ['trace:'+r['name']+'='+str(r['value']) for r in quality]
        summary['frameAssociation']=dict(reasons=reasons,linked=len(good),binaryDraws=len(frames),
            markerCallDelayAbove1ms=sum(abs(f['markerCallDelayNs'])>1e6 for f in linked if 'markerCallDelayNs' in f),
            markerCallDelayMillis=distribution([abs(f['markerCallDelayNs'])/1e6 for f in linked if 'markerCallDelayNs' in f]),
            drawToPresentMillis=distribution([f['drawToPresentMillis'] for f in good]))
        observations=[]
        refs=[e for e in journals.get('app',[]) if e['event']==130]
        for tick in (e for e in journals.get('target',[]) if e['event']==500):
            q=tick['b']*1_000_000
            previous=[f for f in good if f['presentedNs']<=q]
            drawn=[f for f in frames if f['drawEndNs']<=q]
            reference=next((r for r in reversed(refs) if r['elapsedNs']<=q),None)
            presented=max(previous,key=lambda f:f['presentedNs']) if previous else None
            gap=bool(presented and any(f.get('presentedNs') is None and presented['drawBeginNs']<f.get('drawBeginNs',-1)<=q for f in linked))
            if gap:presented=None
            last_draw=max(drawn,key=lambda f:f['drawEndNs']) if drawn else None
            current=reference['a']+q/1e6-reference['b'] if reference else None
            observations.append(dict(plannedMs=tick['a'],observedMs=tick['b'],elapsedNs=q,
                schedulingLateMillis=tick['b']-tick['a'],drawFrameId=last_draw['frameId'] if last_draw else None,
                presentedFrameId=presented['frameId'] if presented else None,
                drawAgeMillis=(q-last_draw['drawEndNs'])/1e6 if last_draw else None,
                presentedReadAgeMillis=(q-presented['readObservedNs'])/1e6 if presented else None,
                displayedLagToLastObservedAppReferenceMillis=current-presented['epoch'] if current is not None and presented else None,
                externalError=None,externalReason='no_independent_oracle_in_this_capability_pilot',
                presentationReason='intervening_unassociated_draw' if gap else None,
                formalQuality=False))
        save_rows(output/'clock-local-observations.jsonl',observations)
        summary['clockObservations']=dict(recorded=len(observations),
            drawAgeMillis=distribution([o['drawAgeMillis'] for o in observations if o['drawAgeMillis'] is not None]),
            presentedReadAgeMillis=distribution([o['presentedReadAgeMillis'] for o in observations if o['presentedReadAgeMillis'] is not None]),
            schedulingLateMillis=distribution([o['schedulingLateMillis'] for o in observations]))
    sync=[]
    pending={}
    for event in journals.get('app',[]):
        tid=event['tid'];code=event['event']
        if code==150:
            pending[tid]=dict(sourceOrdinal=event['a'],token=event['b'],begin=event)
        elif tid in pending:
            current=pending[tid]
            if code==151:current['connectBegin']=event
            elif code==152:current['connectEnd']=event
            elif code==153:current['response']=event
            elif code==154 and event['d']==current['token']:
                current['accept']=event
                if all(k in current for k in ('connectBegin','connectEnd','response')):
                    response=current['response'];begin=current['connectBegin'];end=current['connectEnd']
                    current.update(connectWallMillis=(end['elapsedNs']-begin['elapsedNs'])/1e6,
                        fullToResponseObservedMillis=(response['elapsedNs']-begin['elapsedNs'])/1e6,
                        productRttMillis=response['b']-response['a'],
                        midpointBoundaryDifferenceMillis=(response['a']-begin['elapsedNs']/1e6)/2,
                        interpretation='Boundary discrepancy and wall time only; not certified clock error or network-only delay.')
                else:current['qualityReason']='missing_boundary'
                sync.append(current);del pending[tid]
    save_rows(output/'sync-boundaries.jsonl',sync)
    summary['syncBoundaryObservations']=[{k:v for k,v in entry.items() if k not in ('begin','connectBegin','connectEnd','response','accept')} for entry in sync]
    summary['rawSyncErrors']=[e for e in journals.get('app',[]) if e['event']==155]
    mem=[]
    for path in sorted(directory.glob('meminfo-*.txt')):
        match=re.search(r'TOTAL PSS:\s*([0-9]+)',path.read_text(encoding='utf-8',errors='replace'))
        mem.append(dict(path=path.name,pssKiB=int(match[1]) if match else None,reason=None if match else 'unparsed_meminfo'))
    if mem:
        save_rows(output/'memory-samples.jsonl',mem)
        summary['memoryPssKiB']={name:distribution([r['pssKiB'] for r in mem if name in r['path'] and r['pssKiB'] is not None],limit=None)
                                 for name in ('com.ahao.clicker.txt','com.ahao.clicker.acceptance.target.txt')}
    heartbeat=directory/'heartbeats.jsonl'
    if heartbeat.exists():
        pulses=[json.loads(line) for line in heartbeat.read_text(encoding='utf-8').splitlines()]
        summary['heartbeats']=dict(n=len(pulses),maxReceivedGapMillis=max([(b['receivedHostNs']-a['receivedHostNs'])/1e6 for a,b in zip(pulses,pulses[1:])] or [0]))
    if any(row['kind']=='oracle' for row in rows):
        reference_report = oracle_analysis(rows)
        save_json(output/'reference-screening.json', reference_report)
        summary['referenceScreening'] = {k:v for k,v in reference_report.items() if k not in ('requests','pairs')}
    save_json(output/'summary.json', summary)
    return {k:v for k,v in summary.items() if k != 'result'}


def historical_analysis(root, out):
    batches, anomalies = [], []
    for path in sorted(root.glob('*/result.json')):
        report = read_json(path)
        directory = path.parent
        apk = directory/'measured.apk'
        samples = report.get('samples', [])
        clock = report.get('rows', [])
        entry = dict(id=directory.name, historical=True, apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest() if apk.exists() else None,
                     rawReportedPassed=report.get('passed'), recordedClickRows=len(samples), recordedClockRows=len(clock),
                     requested=report.get('requestedSamples'), leadMillis=report.get('leadMillis'),
                     rawError=report.get('error'), trace=(directory/'capture.perfetto-trace').exists(),
                     clockMethod='historical_draw_observation; no actual presentation; may omit connect from reference',
                     outcomeUnknown=not samples and bool(report.get('error')))
        stages = {'dispatch':'dispatchedElapsed', 'down':'downReceivedElapsed', 'click':'clickReceivedElapsed'}
        entry['localMillis'] = {}
        for name, field in stages.items():
            values=[]
            for sample in samples:
                if field not in sample or 'plannedElapsed' not in sample:
                    continue
                value=sample[field]-sample['plannedElapsed']
                values.append(value)
                if value>50 or value<0:
                    anomalies.append(dict(batch=directory.name,index=sample.get('index'),stage=name,localMillis=value,
                                          rawRelativePath=str(path),historical=True))
            entry['localMillis'][name]=distribution(values)
        entry['stageMillis'] = {name:distribution([s[b]-s[a] for s in samples if a in s and b in s]) for name,a,b in [
            ('dispatchToDown','dispatchedElapsed','downReceivedElapsed'),('downToUp','downReceivedElapsed','upReceivedElapsed'),
            ('upToClick','upReceivedElapsed','clickReceivedElapsed')]}
        entry['clockMillis'] = {name:distribution([abs(row[field]) if absolute else row[field] for row in clock if field in row])
            for name,field,absolute in [('estimatedAbsError','displayErrorMillis',True),('drawAge','drawAgeMillis',False),
                                       ('sourceRtt','sourceRttMillis',False),('oracleRtt','oracleRttMillis',False)]}
        trace_summary=directory/'analysis/summary.json'
        if trace_summary.exists():
            saved=read_json(trace_summary)
            entry['historicalTraceSummary']={k:saved.get(k) for k in ['traceSha256','qualityNonzero','samplingCoverage','targetWaitChains']}
        batches.append(entry)
    save_json(out/'historical-batches.json', batches)
    save_rows(out/'historical-anomalies.jsonl', anomalies)
    return dict(batches=len(batches), clickRows=sum(b['recordedClickRows'] for b in batches),
                clockRows=sum(b['recordedClockRows'] for b in batches),
                apkRevisions=len({b['apkSha256'] for b in batches if b['apkSha256']}),
                analysisPolicy='No pooled percentiles: distinct APKs, modes, lead times and failed attempts retained separately.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run',type=Path)
    parser.add_argument('--history',type=Path)
    args=parser.parse_args()
    out=args.run/'offline';out.mkdir(exist_ok=True)
    summary=dict(pilots=[analyze_pilot(p) for p in sorted(args.run.glob('pilot-*')) if p.is_dir()])
    if args.history:
        summary['historical']=historical_analysis(args.history,out)
    save_json(out/'index.json',summary)
    print(json.dumps(summary,ensure_ascii=False))


if __name__=='__main__':
    main()
