"""Apply the preregistered discovery rule; never approve production retention."""
import argparse
import json
import math
from pathlib import Path

from repair_report import batch
from v3_analysis import distribution


def compare(runs):
    reasons=[]
    if [r.get('variant') for r in runs] != list('ABBABAAB'):
        reasons.append('incomplete_or_changed_order')
    boots={r.get('bootId') for r in runs}
    if len(boots)!=1 or not next(iter(boots),None):
        reasons.append('missing_or_changed_boot')
    for run in runs:
        report=run.get('report') or {}
        attempts=report.get('attempts',[])
        if report.get('qualityErrors')!=[]:reasons.append('quality_not_passed')
        if report.get('recordingMode')!='off':reasons.append('recording_mode_not_off')
        if not report.get('failureDiagnosticsPresent') or report.get('failureEvent')!=0:
            reasons.append('unexpected_or_missing_failure_state')
        if (not run.get('collected') or not report.get('collectorPassed') or
            report.get('requested')!=25 or report.get('warmupRequested')!=10 or
            len(attempts)!=35 or {a.get('attemptId') for a in attempts}!=set(range(1,36))):
            reasons.append('incomplete_batch')
        for a in attempts:
            if (a.get('warmup')!=(a.get('attemptId',0)<=10) or
                a.get('outcome')!='exactly_once_B' or a.get('phase')!='COMPLETED' or
                any(not isinstance(a.get(k),(int,float)) or not math.isfinite(a[k])
                    for k in ('downMs','clickMs','armLeadMs'))):
                reasons.append('invalid_or_unclosed_attempt')
    output=dict(eligible=not reasons, discoveryPassed=False, retentionApproved=False,
                reasons=sorted(set(reasons)), confirmation='independent_1000_per_variant_required')
    if reasons:return output
    variants={}
    for variant in 'AB':
        rows=[a for r in runs if r['variant']==variant for a in r['report']['attempts'] if not a['warmup']]
        variants[variant]={k:distribution([a[k] for a in rows],None if k=='armLeadMs' else 50)
                           for k in ('downMs','clickMs','armLeadMs')}
    output['variants']=variants
    # Early receipts in warmup also invalidate safety; warmups never enter percentiles.
    if any(a[k]<0 for r in runs for a in r['report']['attempts'] for k in ('downMs','clickMs')):
        reasons.append('early_receipt')
    for metric in ('downMs','clickMs'):
        for percentile in ('p50','p95','p99','max'):
            if variants['B'][metric][percentile]>variants['A'][metric][percentile]+1:
                reasons.append(metric+'_'+percentile+'_regression')
    pairs=[]
    for left,right in ((0,1),(3,2),(5,4),(6,7)):
        a,b=runs[left],runs[right]
        gains={}
        for metric in ('downMs','clickMs'):
            values=lambda run:[v[metric] for v in run['report']['attempts'] if not v['warmup']]
            gains[metric]=distribution(values(a))['p95']-distribution(values(b))['p95']
        pairs.append(dict(aBatch=left+1,bBatch=right+1,p95GainMs=gains,
                          consistentGain=all(value>=1 for value in gains.values())))
    output['pairs']=pairs
    if sum(p['consistentGain'] for p in pairs)<3:
        reasons.append('fewer_than_three_consistent_pairs')
    output.update(reasons=sorted(set(reasons)),discoveryPassed=not reasons)
    return output


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    args=parser.parse_args()
    manifest=json.loads((args.root/'discovery-matrix.json').read_text(encoding='utf-8'))
    runs=[]
    for entry in manifest['runs']:
        directory=args.root/entry['path']
        report=batch(directory,10) if (directory/'target-session/result.json').exists() else None
        runs.append(dict(entry,report=report))
    result=compare(runs)
    # Exact APK identity must remain fixed within each variant and across target installs.
    identity_errors=[]
    for key in ('targetApkSha256',):
        values={r.get(key) for r in runs}
        if len(values)!=1 or not next(iter(values),None):
            identity_errors.append('missing_or_changed_'+key)
    for variant in 'AB':
        values={r.get('apkSha256') for r in runs if r.get('variant')==variant}
        if len(values)!=1 or not next(iter(values),None):
            identity_errors.append('missing_or_changed_app_'+variant)
    if identity_errors:
        result['reasons']+=identity_errors
        result.update(eligible=False,discoveryPassed=False)
    path=args.root/'discovery-comparison.json'
    if path.exists():raise ValueError('Existing comparison is immutable')
    path.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result))


if __name__=='__main__':main()
