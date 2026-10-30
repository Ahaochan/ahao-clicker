"""Reverse recorded measurement patches in memory; compare with pre-session source hashes."""
import argparse
import hashlib
import json
from pathlib import Path


def updates(patch):
    lines=patch.splitlines()
    files=[]
    index=0
    while index<len(lines):
        line=lines[index]
        if not line.startswith('*** Update File: '):index+=1;continue
        path=Path(line.removeprefix('*** Update File: '))
        index+=1;hunks=[];current=[]
        while index<len(lines) and not lines[index].startswith('*** '):
            part=lines[index]
            if part.startswith('@@'):
                if current:hunks.append(current)
                current=[]
            else:current.append(part)
            index+=1
        if current:hunks.append(current)
        files.append((path,hunks))
    return files


def reverse_hunk(text,lines):
    before=[];after=[]
    for line in lines:
        if not line:before.append('');after.append('');continue
        if line[0]==' ':before.append(line[1:]);after.append(line[1:])
        elif line[0]=='-':before.append(line[1:])
        elif line[0]=='+':after.append(line[1:])
        else:raise ValueError('Unsupported patch line')
    old,new='\n'.join(before),'\n'.join(after)
    count=text.count(new)
    if count!=1:raise ValueError(f'Reverse hunk matched {count} times')
    return text.replace(new,old,1)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run',type=Path)
    args=parser.parse_args()
    baseline=json.loads((args.run/'code-baseline.json').read_text(encoding='utf-8-sig'))
    patches=json.loads((args.run/'measurement-patches.json').read_text(encoding='utf-8'))
    changed=[e for e in baseline if Path(e['path']).exists() and hashlib.sha256(Path(e['path']).read_bytes()).hexdigest()!=e['sha256']]
    output=[]
    for entry in changed:
        path=Path(entry['path']).resolve()
        text=path.read_text(encoding='utf-8-sig')
        edits=0
        try:
            for patch in reversed(patches):
                for candidate,hunks in reversed(updates(patch['patch'])):
                    if candidate.resolve()!=path:continue
                    for hunk in reversed(hunks):text=reverse_hunk(text,hunk)
                    edits+=1
            matches=[]
            for newline in ('\n','\r\n'):
                for bom in ('','\ufeff'):
                    data=(bom+text.replace('\n',newline)).encode('utf-8')
                    if hashlib.sha256(data).hexdigest()==entry['sha256']:matches.append(dict(newline=repr(newline),bom=bool(bom)))
            row=dict(path=entry['path'],measurementPatchCount=edits,exactOriginalReconstructed=bool(matches),matches=matches)
        except ValueError as error:row=dict(path=entry['path'],error=str(error),exactOriginalReconstructed=False)
        output.append(row)
    result=dict(existingChangedFiles=len(changed),files=output,allExact=all(r['exactOriginalReconstructed'] for r in output),
                interpretation='Exact pre-session file hashes after reversing only recorded measurement patches. Does not certify runtime observer overhead.')
    (args.run/'measurement-scope-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))


if __name__=='__main__':
    main()
