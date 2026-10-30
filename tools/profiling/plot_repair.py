"""Optional matplotlib figures from the admitted, fixed discovery dataset only."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root',type=Path)
    parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args()
    decision=json.loads((args.root/'discovery-comparison.json').read_text(encoding='utf-8'))
    if not decision['eligible']:raise ValueError('Do not plot a non-admitted cohort as performance evidence')
    matrix=json.loads((args.root/'discovery-matrix.json').read_text(encoding='utf-8'))
    values={v:{k:[] for k in ('downMs','clickMs')} for v in 'AB'}
    sources={}
    for run in matrix['runs']:
        path=args.root/run['path']/'repair-report-quality-v2.json'
        sources[str(path.relative_to(args.root))]=hashlib.sha256(path.read_bytes()).hexdigest()
        report=json.loads(path.read_text(encoding='utf-8'))
        if report['qualityErrors']:raise ValueError('Recorder quality failure')
        for row in report['attempts']:
            if not row['warmup']:
                for metric in values[run['variant']]:values[run['variant']][metric].append(row[metric])
    if any(len(samples)!=100 for stage in values.values() for samples in stage.values()):
        raise ValueError('Expected exactly 100 measured attempts per variant')
    names=['measurement-repair-cdf-20261029','measurement-repair-pairs-20261029']
    args.out.mkdir(parents=True,exist_ok=True)
    targets=[args.out/(name+extension) for name in names for extension in ('.svg','.png')]
    if any(p.exists() for p in targets):raise ValueError('Preserve existing figures; choose a new output directory')
    inputs=args.root/'plot-input.json'
    if inputs.exists():raise ValueError('Preserve existing plot inputs')
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    plt.rcParams.update({'font.size':10,'axes.spines.top':False,'axes.spines.right':False})
    colors={'A':'#2166ac','B':'#d95f02'}
    fig,axes=plt.subplots(1,2,figsize=(10,4),layout='constrained')
    for ax,metric,label in zip(axes,('downMs','clickMs'),('DOWN callback','onClick callback')):
        largest=max(max(values[v][metric]) for v in 'AB')
        smallest=min(0,min(min(values[v][metric]) for v in 'AB'))
        limit=max(51,largest*1.05)
        for variant in 'AB':
            samples=sorted(values[variant][metric]);n=len(samples)
            ax.step([smallest]+samples+[limit],[0]+[(i+1)/n for i in range(n)]+[1],where='post',
                    color=colors[variant],label=variant+(' baseline' if variant=='A' else ' candidate'))
        ax.axvline(50,color='#777777',linestyle='--',linewidth=1,label='50 ms reference')
        ax.set(title=label,xlabel='Latency vs local deadline (ms)',ylabel='Empirical cumulative probability',xlim=(smallest,limit),ylim=(0,1.02))
        ax.grid(alpha=.2);ax.legend(fontsize=8)
    fig.suptitle('Fixed ABBABAAB exploration: 100 measured attempts per variant')
    fig.supxlabel('Same boot and target APK; warmups excluded. Relative local deadline, not absolute-source accuracy.',fontsize=8)
    fig.savefig(args.out/(names[0]+'.svg'));fig.savefig(args.out/(names[0]+'.png'),dpi=160);plt.close(fig)
    fig,ax=plt.subplots(figsize=(9,4),layout='constrained')
    pairs=decision['pairs'];width=.32
    for shift,metric,label,color in [(-width/2,'downMs','DOWN',colors['A']),(width/2,'clickMs','onClick',colors['B'])]:
        ax.bar([i+shift for i in range(4)],[p['p95GainMs'][metric] for p in pairs],width,label=label,color=color)
    ax.axhline(0,color='#444444',linewidth=.8);ax.axhline(1,color='#777777',linestyle='--',label='1 ms gain requirement')
    ax.set_xticks(range(4),[f"A{p['aBatch']}/B{p['bBatch']}" for p in pairs])
    ax.set(ylabel='P95 gain in ms (A - B; positive favors B)',xlabel='Preregistered independent batch pairs',
           title=f"Paired gains: {sum(p['consistentGain'] for p in pairs)}/4 pairs qualify; 3/4 required")
    ax.grid(axis='y',alpha=.2);ax.legend(fontsize=9)
    fig.savefig(args.out/(names[1]+'.svg'));fig.savefig(args.out/(names[1]+'.png'),dpi=160);plt.close(fig)
    # Matplotlib emits trailing spaces inside XML path attributes; normalize
    # line ends without changing geometry, so generated assets pass diff checks.
    for target in targets:
        if target.suffix=='.svg':
            target.write_text('\n'.join(line.rstrip() for line in target.read_text(encoding='utf-8').splitlines())+'\n',encoding='utf-8')
    inputs.write_text(json.dumps(dict(values=values,pairs=pairs,sources=sources,
        scope='Same boot/target; 40 warmups per variant excluded; no absolute-source accuracy claim'),indent=2),encoding='utf-8')
    print(json.dumps(dict(figures=[str(p) for p in targets],matplotlib=matplotlib.__version__)))


if __name__=='__main__':main()
