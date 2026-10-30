"""Standalone scientific figures from archived data; no device access."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import matplotlib
matplotlib.use('Agg')
from matplotlib import pyplot as plt
from matplotlib import font_manager
import numpy as np


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run',type=Path)
    parser.add_argument('--history',required=True,type=Path)
    args=parser.parse_args();out=args.run/'offline/figures';out.mkdir(exist_ok=True)
    font=Path('C:/Windows/Fonts/msyh.ttc')
    if font.exists():
        font_manager.fontManager.addfont(str(font));plt.rcParams['font.family']=font_manager.FontProperties(fname=str(font)).get_name()
    plt.rcParams.update({'axes.unicode_minus':False,'svg.fonttype':'path','axes.spines.top':False,
                         'axes.spines.right':False,'font.size':10,'axes.titlesize':11})
    provenance=[]
    def load(path,lines=False):
        content=path.read_bytes();provenance.append(dict(path=str(path),sha256=hashlib.sha256(content).hexdigest()))
        text=content.decode('utf-8-sig')
        return [json.loads(line) for line in text.splitlines()] if lines else json.loads(text)
    def save(figure,name):
        figure.savefig(out/(name+'.png'),dpi=150,bbox_inches='tight')
        figure.savefig(out/(name+'.svg'),bbox_inches='tight')
        plt.close(figure)
    def ecdf(ax,values,label,**options):
        values=np.sort([v for v in values if v is not None]);ax.step(values,np.arange(1,len(values)+1)/len(values),where='post',label=label,**options)
    def threshold(ax):
        ax.axvline(50,color='#b93832',linestyle='--',linewidth=1,label='50 ms')
    fig,axes=plt.subplots(2,3,figsize=(17,10),layout='constrained')
    reference=load(args.run/'pilot-source-01/offline/reference-screening.json')
    ax=axes[0,0];pairs=reference['pairs']
    ax.plot([p['attemptId'] for p in pairs],[p.get('widthMillis',np.nan) for p in pairs],marker='o',color='#16657a')
    ax.axhline(50,color='#b93832',linestyle='--',label='50 ms 判定窗口')
    ax.set(title='独立参考包络：筛查，非点击延迟',xlabel='配对 ID（n=20）',ylabel='包络宽度 / ms');ax.legend(fontsize=8)
    observations={name:load(args.run/name/'offline/clock-local-observations.jsonl',True) for name in ['pilot-frames-02','pilot-frames-03']}
    ax=axes[0,1]
    for name,color in [('pilot-frames-02','#b27639'),('pilot-frames-03','#16657a')]:
        rows=observations[name];ecdf(ax,[r['presentedReadAgeMillis'] for r in rows],name+' 已关联点',color=color)
    threshold(ax);ax.set(title='系统呈现画面年龄 ECDF（质量未准入）',xlabel='字符读取→观察点 / ms',ylabel='累计比例');ax.legend(fontsize=8)
    history=load(args.history/'opt-control4-clicks/result.json')
    ax=axes[0,2]
    for name,key,color in [('派发','dispatchedElapsed','#97a7aa'),('DOWN','downReceivedElapsed','#16657a'),('onClick','clickReceivedElapsed','#b27639')]:
        ecdf(ax,[r[key]-r['plannedElapsed'] for r in history['samples']],name,color=color)
    threshold(ax);ax.set(title='历史单批：APK 8f4ab849…，n=30',xlabel='本地迟到 / ms',ylabel='累计比例');ax.legend(fontsize=8)
    rows=observations['pilot-frames-03'];origin=rows[0]['observedMs']
    ax=axes[1,0];seconds=[(r['observedMs']-origin)/1000 for r in rows]
    ax.plot(seconds,[r['drawAgeMillis'] for r in rows],label='绘制结束年龄',color='#97a7aa')
    ax.plot(seconds,[r['presentedReadAgeMillis'] if r['presentedReadAgeMillis'] is not None else np.nan for r in rows],label='系统呈现的字符年龄',color='#16657a')
    ax.axhline(50,color='#b93832',linestyle='--');ax.set(title='03 局部时间线：不含独立绝对钟差',xlabel='实际观察时间 / s',ylabel='年龄 / ms');ax.legend(fontsize=8)
    ax=axes[1,1];sync=[]
    for name in observations:
        entries=load(args.run/name/'offline/sync-boundaries.jsonl',True)
        sync.extend((name[-2:]+'-'+str(i+1),e) for i,e in enumerate(entries))
    x=np.arange(len(sync));ax.bar(x-.18,[e['connectWallMillis'] for _,e in sync],.36,label='连接阶段 wall time',color='#b27639')
    ax.bar(x+.18,[e['productRttMillis'] for _,e in sync],.36,label='产品后续 RTT',color='#16657a')
    ax.set_xticks(x,[label for label,_ in sync]);ax.set_yscale('log');ax.set(title='授时边界：不是实际钟差（对数轴）',xlabel='批次−请求序号',ylabel='阶段 / ms');ax.legend(fontsize=8)
    ax=axes[1,2]
    for package,label,color in [('com.ahao.clicker','点击器','#16657a'),('com.ahao.clicker.acceptance.target','目标/驱动','#b27639')]:
        memory=load(args.run/'pilot-frames-03/offline/memory-samples.jsonl',True)
        points=[(int(re.search(r'meminfo-([0-9]+)-',r['path'])[1]),r['pssKiB']/1024) for r in memory if r['path'].endswith('-'+package+'.txt') and r['pssKiB'] is not None]
        points.sort();ax.plot([n*30 for n,_ in points],[pss for _,pss in points],marker='o',label=label,color=color)
    ax.set(title='PSS：含启动，未做配对基线',xlabel='资源采样槽 / s',ylabel='PSS / MiB');ax.legend(fontsize=8)
    fig.suptitle('V3 局部分析：正式矩阵未启动；两轮帧质量均未通过',fontsize=16)
    save(fig,'pilot-overview')
    chains=load(args.run/'pilot-ui-03/offline/task-chains.json')
    task=chains[0];timing=task['localMillis'];stages=task['stagesMillis']
    fig,ax=plt.subplots(figsize=(10,3.6),layout='constrained')
    bars=[('截止回调→派发',stages['deadlineCallback'],stages['callbackToDispatch']),
          ('派发→DOWN',timing['dispatch'],stages['dispatchToDown']),
          ('DOWN→UP',timing['down'],stages['downToUp']),
          ('UP→onClick',timing['down']+stages['downToUp'],stages['upToClick'])]
    for i,(label,left,width) in enumerate(bars):
        ax.broken_barh([(left,width)],(i-.3,.6),facecolors=['#16657a','#6c969d','#b27639','#738b62'][i])
        ax.text(left+width+.4,i,f'{width:.3f} ms',va='center')
    ax.set_yticks(range(len(bars)),[r[0] for r in bars]);ax.invert_yaxis();ax.axvline(0,color='#333333');ax.axvline(50,color='#b93832',linestyle='--')
    ax.set(xlim=(-1,60),xlabel='相对最终本地截止 D / ms',title='pilot-ui-03：一次真实目标 B 接收；不是分布或正式验收')
    save(fig,'single-click-stages')
    old=load(args.history/'clock-hour/result.json');rows=old['rows'];origin=rows[0]['elapsed']
    fig,ax=plt.subplots(figsize=(12,4),layout='constrained')
    ax.plot([(r['elapsed']-origin)/60000 for r in rows],[r['displayErrorMillis'] for r in rows],marker='.',label='历史绘制对照估计',color='#16657a')
    ax.axhline(50,color='#b93832',linestyle='--');ax.axhline(-50,color='#b93832',linestyle='--')
    ax.set(xlabel='实际观察时间 / min',ylabel='历史估计误差 / ms',title='历史一小时：121 点；非新 V3、非系统呈现、非 UTC 认证');ax.legend()
    save(fig,'historical-hour')
    (out/'figure-inputs.json').write_text(json.dumps(dict(inputs=provenance,plotter='matplotlib',version=matplotlib.__version__,
        warning='All pilot frame quantities are diagnostic only; no paired overhead or complete clock mapping admission.'),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(directory=str(out),figures=3,formats=['png','svg'],matplotlib=matplotlib.__version__)))


if __name__=='__main__':
    main()
