"""Read-only Perfetto FrameTimeline wire audit, matching Trace Processor add693d8.

No rewriting traces or removing parser statistics. Invalid optional enum metadata
is distinguished from missing structural/timing fields, as in the upstream parser.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import zlib


def _varint(data,offset):
    value=0
    for i in range(10):
        if offset>=len(data):raise ValueError('truncated varint')
        byte=data[offset];offset+=1
        if i==9 and byte>1:raise ValueError('overflow varint')
        value|=(byte&127)<<(7*i)
        if byte<128:return value,offset
    raise ValueError('overflow varint')


def fields(data):
    offset=0
    while offset<len(data):
        key,offset=_varint(data,offset);number=key>>3;wire=key&7
        if not number:raise ValueError('zero field number')
        if wire==0:value,offset=_varint(data,offset)
        elif wire in (1,2,5):
            if wire==2:size,offset=_varint(data,offset)
            else:size=8 if wire==1 else 4
            if offset+size>len(data):raise ValueError('truncated field')
            value=data[offset:offset+size];offset+=size
        else:raise ValueError('unsupported wire type '+str(wire))
        yield number,value


def signed(value):return value-(1<<64) if value>=(1<<63) else value


def trace_records(trace):
    packet_number=0
    for number,packet in fields(trace):
        if number!=1:continue
        packet_number+=1
        content=dict(fields(packet))
        if 133 in content:raise ValueError('unsupported zstd compressed packet; cannot silently skip')
        if 50 in content:
            for row in trace_records(zlib.decompress(content[50])):
                row['compressedParentPacket']=packet_number;yield row
        if 76 not in content:continue
        ts=signed(content.get(8,0))
        event=dict(fields(content[76]))
        kinds=[k for k in (1,2,3,4,5) if k in event]
        kind=kinds[0] if kinds else 0
        values=dict(fields(event[kind])) if kind else {}
        yield dict(packet=packet_number,ts=ts,clockId=content.get(58,6),kind=kind,fields=values)


def audit_event(event):
    values=event['fields'];kind=event['kind'];ts=event['ts']
    if ts<0 or abs(ts)>=(1<<63)-1-10_000_000_000:
        return [dict(reason='invalid_timestamp',severity='structure',value=ts)]
    required={1:(1,2,3),2:(1,2,3),3:(1,2,3,4),4:(1,2,3,4),5:(1,)}
    if kind not in required:return [dict(reason='unknown_event',severity='structure',value=kind)]
    missing=[n for n in required[kind] if n not in values]
    if missing:return [dict(reason='missing_required_fields',severity='structure',value=missing)]
    enum_fields={2:((4,'present_type',5),(13,'present_type_experimental',5),(8,'prediction_type',3)),
                 4:((6,'present_type',5),(17,'present_type_experimental',5),(10,'prediction_type',3))}
    return [dict(reason=name,severity='metadata',value=values[n]) for n,name,maximum in enum_fields.get(kind,()) if n in values and not 1<=values[n]<=maximum]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('trace',type=Path);parser.add_argument('--out',required=True,type=Path)
    args=parser.parse_args();args.out.mkdir(exist_ok=False,parents=True)
    counts=Counter();issues=[];pids=Counter();events=0;clocks=Counter()
    # Capture limited to <=128MiB in this task. No decoded ftrace objects retained.
    with (args.out/'raw-frame-events.jsonl').open('w',encoding='utf-8') as output:
        for event in trace_records(args.trace.read_bytes()):
            events+=1;values=event['fields'];kind=event['kind']
            problems=audit_event(event);clock=event['clockId'];clocks[clock]+=1
            pid=values.get(3 if kind in (1,2) else 4) if kind!=5 else None
            entry=dict(event,pid=pid,issues=problems)
            # Preserve all numeric values; names are only for diagnostic identity.
            entry['fields']={str(k):({'hex':v.hex()} if isinstance(v,bytes) else v) for k,v in values.items()}
            output.write(json.dumps(entry,ensure_ascii=True)+'\n')
            for problem in problems:
                counts[(problem['severity'],problem['reason'],str(problem['value']))]+=1
                issues.append(dict(packet=event['packet'],ts=event['ts'],kind=kind,pid=pid,**problem))
                pids[pid]+=1
    summary=dict(events=events,clockIds=dict(clocks),parserErrorsExplained=sum(counts.values()),
        categories=[dict(severity=k[0],reason=k[1],value=k[2],count=v) for k,v in counts.items()],
        issuePidCounts=[dict(pid=k,count=v) for k,v in pids.items()],
        structuralErrors=sum(v for k,v in counts.items() if k[0]=='structure'),
        metadataErrors=sum(v for k,v in counts.items() if k[0]=='metadata'),
        officialParserRevision='add693d8b338ba9599dbcbc3e300b1ab8c000897',traceUnmodified=True)
    (args.out/'parser-issues.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in issues),encoding='utf-8')
    (args.out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False))


if __name__=='__main__':main()
