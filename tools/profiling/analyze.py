"""Offline Perfetto evidence export and flamegraphs; Python standard library only."""
import argparse
from collections import Counter
import hashlib
import html
import json
import math
from pathlib import Path
import re
import statistics
import subprocess

SCOPE = "p.name IN ('com.ahao.clicker','com.ahao.clicker.acceptance.target')"
TABLES = {
    'bounds': ('start_ts,end_ts', 'SELECT * FROM trace_bounds'),
    'quality': ('name,value,severity,source', 'SELECT name,value,severity,source FROM stats WHERE value>0'),
    'threads': ('utid,tid,thread,pid,process', f'SELECT t.utid,t.tid,t.name thread,p.pid,p.name process FROM thread t JOIN process p USING(upid) WHERE {SCOPE}'),
    'cpu': ('process,thread,pid,tid,cpu_ms,switches', f'SELECT p.name process,t.name thread,p.pid,t.tid,sum(s.dur)/1e6 cpu_ms,count(*) switches FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid) WHERE {SCOPE} AND s.dur>0 GROUP BY s.utid'),
    'global_cpu': ('process,cpu_ms', 'SELECT p.name process,sum(s.dur)/1e6 cpu_ms FROM sched s JOIN thread t USING(utid) JOIN process p USING(upid) WHERE s.dur>0 GROUP BY p.upid ORDER BY cpu_ms DESC LIMIT 20'),
    'states': ('utid,ts,dur,state,waker_utid,blocked_function', f'SELECT s.utid,s.ts,s.dur,s.state,s.waker_utid,s.blocked_function FROM thread_state s JOIN thread t USING(utid) JOIN process p USING(upid) WHERE {SCOPE} AND s.dur>0'),
    'slices': ('utid,ts,dur,name,depth', f'SELECT tt.utid,s.ts,s.dur,s.name,s.depth FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid) JOIN process p USING(upid) WHERE {SCOPE} AND s.dur>=1000000'),
    'frames': ('pid,ts,dur,layer_name,jank_type,present_type,on_time_finish', f'SELECT p.pid,s.ts,s.dur,s.layer_name,s.jank_type,s.present_type,s.on_time_finish FROM actual_frame_timeline_slice s JOIN process p USING(upid) WHERE {SCOPE} AND s.dur>0'),
    'samples': ('id,ts,utid,callsite_id,unwind_error', f'SELECT s.id,s.ts,s.utid,s.callsite_id,s.unwind_error FROM perf_sample s JOIN thread t USING(utid) JOIN process p USING(upid) WHERE {SCOPE}'),
    'callsites': ('id,parent_id,frame_id,depth', 'SELECT id,parent_id,frame_id,depth FROM stack_profile_callsite'),
    'symbols': ('id,name,mapping', 'SELECT f.id,coalesce(DEMANGLE(f.name),f.name) name,m.name mapping FROM stack_profile_frame f JOIN stack_profile_mapping m ON f.mapping=m.id'),
}

def distribution(values):
    values = sorted(values)
    if not values:
        return None
    return {'count': len(values), 'min': values[0], 'p50': statistics.median(values),
            'p95': values[math.ceil(.95 * len(values)) - 1], 'max': values[-1]}

def arm_observation(report):
    snapshot=report.get('failureSnapshot')
    if not snapshot:
        return None
    state=snapshot.get('appState',{})
    control=snapshot.get('lastPlayControl',{})
    events=snapshot.get('targetEvents',[])
    phase=state.get('phase')
    if phase=='COMPLETED':
        classification='completed_before_observation' if len(events)==1 and events[0].get('point')=='B' else 'completed_without_expected_receipt'
    elif not control.get('actionAccepted'):
        classification='no_accepted_play_action'
    else:
        classification=(phase.lower()+'_at_observation') if phase else 'unknown_state'
    prepared=next((row.get('appState',{}) for row in reversed(report.get('stageHistory',[]))
                   if row.get('index')==snapshot.get('index') and row.get('stage')=='prepared'),{})
    deadline=prepared.get('initialPlannedElapsed')
    started=control.get('actionStartedElapsed')
    return {'classification':classification,'phase':phase,'stage':snapshot.get('stage'),
            'receivedEvents':len(events),'receivedTouches':len(snapshot.get('targetTouches',[])),
            'playActionAccepted':control.get('actionAccepted'),
            'initialPreparedDeadlineMillis':deadline,
            'playStartMinusInitialDeadlineMillis':started-deadline if started is not None and deadline is not None else None,
            'snapshotDurationMillis':snapshot.get('finishedElapsed',0)-snapshot.get('startedElapsed',0),
            'feedback':snapshot.get('diagnoseState',{}).get('lastFeedback'),
            'note':'Observed facts only; an initial prepared deadline is not proof the engine armed, and no snapshot means historical cause remains unknown.'}

def export(processor, directory, reuse=False):
    parts = []
    for name, (columns, select) in TABLES.items():
        obj = ','.join(f"'{key}',{key}" for key in columns.split(','))
        parts.append(f"SELECT '{name}' section,json_group_array(json_object({obj})) payload FROM ({select})")
    sql = '\nUNION ALL\n'.join(parts) + ';\n'
    out = directory / 'analysis'
    out.mkdir(exist_ok=True)
    if reuse and (out/'export.csv').exists() and (out/'export.sql').read_text(encoding='utf-8') == sql:
        raw = (out/'export.csv').read_bytes()
    else:
        (out / 'export.sql').write_text(sql, encoding='utf-8')
        result = subprocess.run([str(processor), 'query', '-f', str(out / 'export.sql'), str(directory / 'capture.perfetto-trace')], capture_output=True)
        (out / 'processor.log').write_bytes(result.stderr)
        result.check_returncode()
        raw = result.stdout
        (out / 'export.csv').write_bytes(raw)
    tables = {}
    for line in raw.decode('utf-8').splitlines():
        if not line.startswith('"') or line.startswith('"section"'):
            continue
        section, value = line.split(',', 1)
        section = section.strip('"')
        value = value[1:-1] if value.startswith('"') else value
        try:
            tables[section] = json.loads(value)
        except json.JSONDecodeError:
            tables[section] = json.loads(value.replace('""', '"'))
    if set(tables) != set(TABLES):
        raise RuntimeError('Incomplete SQL export')
    return out, tables

def clipped_states(rows, utids, start, end):
    total = Counter()
    longest = {}
    for row in rows:
        if row['utid'] not in utids:
            continue
        overlap = max(0, min(end, row['ts'] + row['dur']) - max(start, row['ts'])) / 1e6
        if overlap:
            key = row['state']
            total[key] += overlap
            longest[key] = max(longest.get(key, 0), overlap)
    return {'totalMillis': dict(total), 'longestSegmentMillis': longest}

def target_wait_chains(data,start,end):
    threads={r['utid']:r for r in data['threads']}
    main={r['utid'] for r in data['threads'] if r['process']=='com.ahao.clicker.acceptance.target' and r['tid']==r['pid']}
    sites={r['id']:r for r in data['callsites']}
    symbols={r['id']:r for r in data['symbols']}
    waits=sorted([r for r in data['slices'] if r['utid'] in main and r['name']=='postAndWait'
                  and start<=r['ts'] and r['ts']+r['dur']<=end],key=lambda r:-r['dur'])[:10]
    result=[]
    for wait in waits:
        left,right=wait['ts'],wait['ts']+wait['dur']
        sleeps=[r for r in data['states'] if r['utid']==wait['utid'] and r['state']=='S' and r['ts']<right and r['ts']+r['dur']>left]
        sleep=max(sleeps,key=lambda r:min(right,r['ts']+r['dur'])-max(left,r['ts']),default=None)
        wake_at=sleep['ts']+sleep['dur'] if sleep else None
        wake=next((r for r in data['states'] if r['utid']==wait['utid'] and r['state'] in ('R','R+')
                   and wake_at is not None and left<=wake_at<=right and abs(r['ts']-wake_at)<1000),None)
        waker=threads.get(wake.get('waker_utid')) if wake else None
        functions=Counter(); count=0
        if waker:
            for sample in data['samples']:
                if sample['utid']!=waker['utid'] or not left<=sample['ts']<=right or sample['callsite_id'] is None:
                    continue
                count+=1;site_id=sample['callsite_id'];names=set()
                while site_id is not None:
                    site=sites[site_id];names.add(symbols[site['frame_id']]['name'] or '[unresolved]');site_id=site['parent_id']
                functions.update(names)
        result.append({'ts':left,'dur':wait['dur'],'waitMillis':wait['dur']/1e6,'mainUtid':wait['utid'],
                       'mainStates':clipped_states(data['states'],{wait['utid']},left,right),
                       'waker':waker,'wakeRunnableMillis':wake['dur']/1e6 if wake else None,
                       'wakerStates':clipped_states(data['states'],{waker['utid']},left,right) if waker else None,
                       'wakerSamplesWithStack':count,'wakerInclusiveFunctions':functions.most_common(30)})
    return result

def flamegraph(directory, tables, process_name, start, end):
    threads = {r['utid']: r for r in tables['threads'] if r['process'] == process_name}
    symbols = {r['id']: r for r in tables['symbols']}
    sites = {r['id']: r for r in tables['callsites']}
    root = {'name': process_name, 'value': 0, 'children': {}}
    leaves, modules, inclusive, inclusive_modules, thread_samples = Counter(), Counter(), Counter(), Counter(), Counter()
    mapping = {}
    for line in (directory / 'mapping.txt').read_text(encoding='utf-8').splitlines():
        match = re.match(r'([^ #].*) -> ([^ ]+):$', line)
        if match:
            mapping[match[2]] = match[1]
    def label(symbol):
        name = symbol['name'] or '[unresolved]'
        for short, full in mapping.items():
            if name.startswith(short + '.'):
                return full + name[len(short):] + ' [class restored; method may be obfuscated]'
        return name
    for sample in tables['samples']:
        if sample['utid'] not in threads or sample['callsite_id'] is None or not start <= sample['ts'] <= end:
            continue
        stack = []
        stack_modules = set()
        site_id = sample['callsite_id']
        while site_id is not None:
            site = sites[site_id]
            symbol = symbols[site['frame_id']]
            stack.append(label(symbol))
            stack_modules.add(symbol['mapping'])
            if len(stack) == 1:
                leaves[label(symbol)] += 1
                modules[symbol['mapping']] += 1
            site_id = site['parent_id']
        inclusive.update(set(stack))
        inclusive_modules.update(stack_modules)
        thread_samples[threads[sample['utid']]['thread']] += 1
        node = root
        node['value'] += 1
        for name in [threads[sample['utid']]['thread']] + list(reversed(stack)):
            node = node['children'].setdefault(name, {'name': name, 'value': 0, 'children': {}})
            node['value'] += 1
    if root['value'] == 0:
        return {'samplesWithStack': 0}
    def depth(node):
        return 1 + max((depth(c) for c in node['children'].values()), default=0)
    height = depth(root) * 20 + 90
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="{height}" viewBox="0 0 1600 {height}">',
           '<rect width="100%" height="100%" fill="#fffaf2"/>',
           f'<text x="12" y="23" font-size="17">CPU samples: {html.escape(process_name)} ({root["value"]})</text>',
           '<text x="12" y="46" font-size="13">Width = sampled CPU share; not elapsed latency. Hover for stack/sample count.</text>']
    def draw(node, x, width, level, parents):
        y = height - 25 - level * 20
        digest = hashlib.sha256(node['name'].encode()).digest()
        color = f'rgb({215+digest[0]%35},{100+digest[1]%100},{60+digest[2]%80})'
        title = html.escape(' → '.join(parents + [node['name']]) + f' | {node["value"]} samples ({100*node["value"]/root["value"]:.2f}%)')
        svg.append(f'<g><title>{title}</title><rect x="{x:.3f}" y="{y}" width="{max(width-.4,0):.3f}" height="19" fill="{color}"/>')
        if width > 25:
            text = html.escape(node['name'][:max(1, int(width/7)-1)])
            svg.append(f'<text x="{x+3:.3f}" y="{y+14}" font-size="12" font-family="monospace">{text}</text>')
        svg.append('</g>')
        at = x
        for child in sorted(node['children'].values(), key=lambda n: (-n['value'], n['name'])):
            size = width * child['value'] / node['value']
            draw(child, at, size, level+1, parents + [node['name']])
            at += size
    draw(root, 5, 1590, 0, [])
    svg.append('</svg>')
    filename = 'target-flamegraph.svg' if process_name.endswith('.target') else 'app-flamegraph.svg'
    (directory / 'analysis' / filename).write_text('\n'.join(svg), encoding='utf-8')
    return {'samplesWithStack': root['value'], 'leafFunctions': leaves.most_common(25), 'leafModules': modules.most_common(15),
            'inclusiveFunctions':inclusive.most_common(40), 'inclusiveModules':inclusive_modules.most_common(25),
            'threadSamples':dict(thread_samples), 'file': filename}

def click_timeline(out, data, sample):
    planned = sample['plannedElapsed']
    end = sample['clickReceivedElapsed'] - planned + 30
    begin = -30
    rows = [r for r in data['threads'] if r['tid']==r['pid'] or r['thread']=='RenderThread']
    def x(at):
        return 250 + (at-begin)/(end-begin)*1300
    def ms(ts):
        return ts/1e6-planned
    palette = {'Running':'#26865b','R':'#eea23b','R+':'#dc7739','S':'#a9b6be','D':'#c74545'}
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="{130+len(rows)*45}">',
           '<rect width="100%" height="100%" fill="#fffaf2"/>',
           '<text x="12" y="24" font-size="18">Slowest click: real scheduler states, relative to planned deadline (ms)</text>',
           '<text x="12" y="49" font-size="13">Green: on CPU | Orange: runnable, waiting for CPU | Grey: sleeping | Red: uninterruptible wait</text>']
    for index, thread in enumerate(rows):
        y = 80+index*45
        label = ('clicker' if thread['process']=='com.ahao.clicker' else 'target')+' / '+thread['thread']
        svg.append(f'<text x="8" y="{y+16}" font-size="13">{html.escape(label)}</text>')
        for state in data['states']:
            if state['utid'] != thread['utid']:
                continue
            left, right = max(begin,ms(state['ts'])),min(end,ms(state['ts']+state['dur']))
            if right>left:
                svg.append(f'<rect x="{x(left):.3f}" y="{y}" width="{max(.1,x(right)-x(left)):.3f}" height="22" fill="{palette.get(state["state"],"#999")}"><title>{html.escape(label)} {state["state"]}: {left:.3f} to {right:.3f} ms</title></rect>')
    for key,label,color in [('plannedElapsed','plan','#202020'),('dispatchedElapsed','send','#364fc7'),('downReceivedElapsed','DOWN','#136f63'),('upReceivedElapsed','UP','#8555a3'),('clickReceivedElapsed','onClick','#bb3333')]:
        at = sample[key]-planned
        xx = x(at)
        bottom = 80+len(rows)*45
        svg.append(f'<line x1="{xx}" y1="68" x2="{xx}" y2="{bottom}" stroke="{color}" stroke-width="1.5"/><text x="{xx+3}" y="{bottom+16}" font-size="12" fill="{color}">{label}: +{at}</text>')
    svg.append('</svg>')
    (out/'slowest-click-timeline.svg').write_text('\n'.join(svg),encoding='utf-8')

def analyze(processor, directory, reuse=False):
    out, data = export(processor, directory, reuse)
    bounds = data['bounds'][0]
    start, end = bounds['start_ts'], bounds['end_ts']
    result = json.loads((directory / 'result.json').read_text(encoding='utf-8-sig'))
    clock_rows = [r for r in result.get('rows', []) if 'error' not in r]
    if clock_rows:
        measure_start = max(start, clock_rows[0]['elapsed'] * 1_000_000)
        measure_end = min(end, clock_rows[-1]['elapsed'] * 1_000_000)
    elif result.get('samples'):
        measure_start = max(start, (result['samples'][0]['plannedElapsed'] - 1800) * 1_000_000)
        measure_end = min(end, (result['samples'][-1]['clickReceivedElapsed'] + 500) * 1_000_000)
    else:
        measure_start, measure_end = start, end
    threads = data['threads']
    app_main = {r['utid'] for r in threads if r['process'] == 'com.ahao.clicker' and r['tid'] == r['pid']}
    target_main = {r['utid'] for r in threads if r['process'].endswith('.target') and r['tid'] == r['pid']}
    windows = []
    samples = result.get('samples', [])
    if result.get('failureState', {}).get('plannedElapsed'):
        samples = samples + [dict(result['failureState'], failure=True)]
    observation=arm_observation(result)
    if observation and not result.get('failureState',{}).get('plannedElapsed'):
        state=result['failureSnapshot'].get('appState',{})
        deadline=state.get('plannedElapsed') or observation.get('initialPreparedDeadlineMillis')
        if deadline and state.get('elapsed'):
            samples=samples+[dict(state,plannedElapsed=deadline,failure=True,index=result['failureSnapshot'].get('index',len(samples)),
                                 deadlineSource='armed_deadline' if state.get('plannedElapsed') else 'initial_prepared_deadline')]
    for i, sample in enumerate(samples):
        planned = sample['plannedElapsed'] * 1_000_000
        stop = sample.get('dispatchedElapsed', sample.get('elapsed')) * 1_000_000
        covered = start <= planned <= stop <= end
        window = {'index': sample.get('index',i), 'plannedElapsedMillis': sample['plannedElapsed'], 'failure': sample.get('failure', False),
                  'plannedToDispatchOrFailureMillis': (stop-planned)/1e6, 'traceFullyCoversWindow': covered}
        window['deadlineSource']=sample.get('deadlineSource','armed_deadline')
        if sample.get('failure'):
            window['windowMeaning']='Deadline to failure observation, not a dispatch-lateness or actual expiry-time measurement.'
        if covered:
            window['appMain'] = clipped_states(data['states'], app_main, planned, stop)
            window['targetMain'] = clipped_states(data['states'], target_main, planned, stop)
            window['appMainFirst250MillisAfterDeadline'] = clipped_states(data['states'],app_main,planned,min(end,planned+250_000_000))
            overlaps = [dict(r, overlapMillis=max(0,min(stop,r['ts']+r['dur'])-max(planned,r['ts']))/1e6)
                        for r in data['slices'] if r['utid'] in app_main and r['ts']<stop and r['ts']+r['dur']>planned]
            window['appMainOverlappingSlices'] = sorted(overlaps, key=lambda r:-r['overlapMillis'])[:8]
        if 'downReceivedElapsed' in sample:
            down = sample['downReceivedElapsed'] * 1_000_000
            window['dispatchToDownMillis'] = (down-stop)/1e6
            window['targetMainDispatchToDown'] = clipped_states(data['states'], target_main, stop, down)
            for label,a,b in [('downToUp','downReceivedElapsed','upReceivedElapsed'),('upToClick','upReceivedElapsed','clickReceivedElapsed')]:
                a,b=sample[a]*1_000_000,sample[b]*1_000_000
                window[label+'Millis']=(b-a)/1e6
                window[label+'TargetMain']=clipped_states(data['states'],target_main,a,b)
                overlaps=[dict(r,overlapMillis=max(0,min(b,r['ts']+r['dur'])-max(a,r['ts']))/1e6) for r in data['slices'] if r['utid'] in target_main and r['ts']<b and r['ts']+r['dur']>a]
                window[label+'TargetSlices']=sorted(overlaps,key=lambda r:-r['overlapMillis'])[:6]
        windows.append(window)
    measured_frames = [r for r in data['frames'] if measure_start <= r['ts'] and r['ts']+r['dur'] <= measure_end]
    measured_cpu = []
    for thread in threads:
        running = clipped_states(data['states'], {thread['utid']}, measure_start, measure_end)['totalMillis'].get('Running',0)
        measured_cpu.append(dict(thread, runningMillis=running, singleCorePercent=100*running/((measure_end-measure_start)/1e6)))
    clock_exceeded = []
    for i, row in enumerate(clock_rows):
        if abs(row['displayErrorMillis']) > 50:
            point = row['elapsed']*1_000_000
            clock_exceeded.append(dict(index=i, **{k:row.get(k) for k in ['elapsed','displayErrorMillis','referenceErrorMillis','referenceToDisplayLagMillis','drawAgeMillis','sourceRttMillis','oracleRttMillis']},
                mainPrevious50Millis=clipped_states(data['states'], app_main, max(start,point-50_000_000),min(end,point))))
    frame_groups = {}
    for row in measured_frames:
        key = f"{row['pid']}:{row['layer_name']}"
        frame_groups.setdefault(key, []).append(row)
    waits=target_wait_chains(data,measure_start,measure_end)
    for wait in waits:
        overlaps=[]
        for sample in result.get('samples',[]):
            for phase,a,b in [('dispatch_to_down','dispatchedElapsed','downReceivedElapsed'),('down_to_up','downReceivedElapsed','upReceivedElapsed'),('up_to_click','upReceivedElapsed','clickReceivedElapsed')]:
                overlap=max(0,min(wait['ts']+wait['dur'],sample[b]*1_000_000)-max(wait['ts'],sample[a]*1_000_000))/1e6
                if overlap: overlaps.append({'index':sample['index'],'phase':phase,'overlapMillis':overlap})
        wait['clickPhaseOverlaps']=overlaps
    summary = {
        'traceSha256': hashlib.sha256((directory/'capture.perfetto-trace').read_bytes()).hexdigest(),
        'traceBytes': (directory/'capture.perfetto-trace').stat().st_size,
        'boundsNanos': bounds, 'durationMillis': (end-start)/1e6,
        'measurementBoundsNanos': {'start': measure_start, 'end': measure_end},
        'qualityNonzero': [r for r in data['quality'] if r['severity']!='info' or any(k in r['name'] for k in ['lost','overrun','skipped','unwind'])],
        'appCpuByThread': sorted(data['cpu'], key=lambda r:-r['cpu_ms']), 'globalCpuTop20': data['global_cpu'],
        'mainThreadStates': clipped_states(data['states'], app_main, start, end),
        'measurementMainThreadStates': clipped_states(data['states'], app_main, measure_start, measure_end),
        'measurementCpuByThread': sorted(measured_cpu,key=lambda r:-r['runningMillis']),
        'frameDurationMillis': distribution([r['dur']/1e6 for r in data['frames']]),
        'frameJank': dict(Counter(r['jank_type'] for r in data['frames'])),
        'frameLayers': dict(Counter(r['layer_name'] for r in data['frames'])),
        'measurementFramesByLayer': {name:{'durationMillis':distribution([r['dur']/1e6 for r in rows]),'jank':dict(Counter(r['jank_type'] for r in rows))} for name,rows in frame_groups.items()},
        'flamegraphs': {name: flamegraph(directory, data, name, measure_start, measure_end) for name in ['com.ahao.clicker', 'com.ahao.clicker.acceptance.target']},
        'unwindErrors': dict(Counter(r['unwind_error'] for r in data['samples'] if r['unwind_error'])),
        'samplingCoverage': {name: {'samples':sum(r['utid'] in {t['utid'] for t in threads if t['process']==name} for r in data['samples']),
            'samplesWithCallsite':sum(r['utid'] in {t['utid'] for t in threads if t['process']==name} and r['callsite_id'] is not None for r in data['samples'])}
            for name in ['com.ahao.clicker','com.ahao.clicker.acceptance.target']},
        'clickWindows': windows,
        'failureObservation':observation,
        'targetWaitChains':waits,
        'clockExceeded50Millis': clock_exceeded,
        'clockSamplesOutsideTrace': sum(not(start<=r['elapsed']*1_000_000<=end) for r in result.get('rows',[]) if 'elapsed' in r),
        'limitations': ['Trace timestamps normalized by Trace Processor; app elapsedRealtime millisecond boundaries carry sub-ms uncertainty.',
                       'CPU flamegraph is sampled on-CPU work, not a wall-clock latency attribution.',
                       'R8 class names restored where mapping matches; method names may remain obfuscated.',
                       'Frame timeline includes startup/transitions and target; use layer/process filtering for steady overlay conclusions.'],
    }
    (out/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n',encoding='utf-8')
    if result.get('samples'):
        click_timeline(out,data,max(result['samples'],key=lambda r:r['clickReceivedElapsed']-r['plannedElapsed']))
    print(json.dumps({'directory': str(directory), 'durationMillis': summary['durationMillis'],
                      'quality': summary['qualityNonzero'], 'appStackSamples': summary['flamegraphs']['com.ahao.clicker']['samplesWithStack'],
                      'clickWindows': len(windows)}, ensure_ascii=True))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--processor', type=Path, required=True)
    parser.add_argument('--reuse-export', action='store_true', help='Reuse identical saved SQL output for local analysis iteration.')
    parser.add_argument('directories', nargs='+', type=Path)
    args = parser.parse_args()
    for directory in args.directories:
        analyze(args.processor.resolve(), directory.resolve(), args.reuse_export)
