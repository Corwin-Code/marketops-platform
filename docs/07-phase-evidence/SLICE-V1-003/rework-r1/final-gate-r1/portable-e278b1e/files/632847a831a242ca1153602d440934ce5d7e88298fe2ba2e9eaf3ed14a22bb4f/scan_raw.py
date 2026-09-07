#!/usr/bin/env python3
"""Read actual original bytes/archive members. Findings contain hashes and redacted context only."""
import ast
import base64
import collections
import gzip
import hashlib
import html
import io
import json
from pathlib import Path
import re
import tarfile
import time
import zipfile

ROOT=Path('/Users/chzhengx/Code/personal/marketops-platform')
INPUT=Path('/tmp/slice3-checkpoint-backend-ci-5fd53c0')
OUT=Path(__file__).resolve().parent
VALIDATOR=ROOT/'scripts/validate_governance.py'
sha=lambda b:hashlib.sha256(b).hexdigest()
def write(name,value):(OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
rules=[]
for node in ast.parse(VALIDATOR.read_text()).body:
    if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='SECRET_PATTERNS' for t in node.targets):
        rules=[ast.literal_eval(item.args[0]) for item in node.value.elts]
assert len(rules)==7
rules.append(r'\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\b')
patterns=[re.compile(p) for p in rules]
files=sorted(p for p in INPUT.rglob('*') if p.is_file())
before={str(p.relative_to(INPUT)):{'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in files}
original_manifest=json.loads((INPUT/'SHA256-MANIFEST.json').read_text())
assert all(before[row['path']]=={'bytes':row['bytes'],'sha256':row['sha256']} for row in original_manifest)
artifacts=json.loads((INPUT/'artifacts.json').read_text())['artifacts']
for artifact in artifacts:
    path=artifact['name']+'.zip'
    assert before[path]['sha256']==artifact['digest'].removeprefix('sha256:')
    assert before[path]['bytes']==artifact['size_in_bytes']
write('SCAN-INPUT.json',{'kind':'ORIGINAL_OFFICIAL_CI_RAW_SCAN_INPUT','files':before,
    'sourceHead':'5fd53c0959ad50d949ee92c7477f1fef9954f2a2','runId':33994962312,'runAttempt':1,
    'checkpointResult':'FAILED_NON_ADMISSIBLE_FOR_CURRENT_CLOSURE','originalManifestVerified':True,
    'officialArtifacts':[{'id':r['id'],'name':r['name'],'bytes':r['size_in_bytes'],'digest':r['digest']} for r in artifacts],
    'rulesSource':{'path':str(VALIDATOR.relative_to(ROOT)),'sha256':sha(VALIDATOR.read_bytes())},
    'patterns':[{'id':i+1,'pattern':p} for i,p in enumerate(rules)],'rawContentPrinted':False})
scanned=[];hits=[];seen_payloads=set();started=time.time();total=0;archive_members=0
member_output=(OUT/'MEMBERS.jsonl').open('w')
hit_output=(OUT/'HITS.jsonl').open('w')
def pause():
    if (OUT/'PAUSE').exists():raise InterruptedError('Root requested pause for measurement')

def scan_text(data,path,view='RAW_BYTES'):
    text=data.decode('utf-8',errors='replace')
    for number,pattern in enumerate(patterns,1):
        for match in pattern.finditer(text):
            value=match.group()
            key=(path,number,view,match.start(),sha(value.encode()))
            if key in seen_payloads:continue
            seen_payloads.add(key)
            item={'path':path,'rule':number,'view':view,'line':text.count('\n',0,match.start())+1,
                  'characterOffset':match.start(),'matchedCharacters':len(value),'matchedSha256':sha(value.encode()),
                  'classification':'REQUIRES_EXACT_CONTEXT_REVIEW','publishableRaw':False}
            # All shape values, not just this match, are redacted from the small review context.
            context=text[max(0,match.start()-100):min(len(text),match.end()+100)]
            for redactor in patterns:context=redactor.sub('<REDACTED_SHAPE>',context)
            item['redactedContext']=context
            if number==8:
                item.update(classification='TOKEN_SHAPED_OUTPUT_PRIVATE_UNTIL_CLASSIFIED',redactedContext='<JWT-shaped material omitted>')
                try:
                    parts=value.split('.')
                    header=json.loads(base64.urlsafe_b64decode(parts[0]+'='*(-len(parts[0])%4)))
                    payload=json.loads(base64.urlsafe_b64decode(parts[1]+'='*(-len(parts[1])%4)))
                    item['jwtMetadata']={'headerKeys':sorted(header),'algorithm':header.get('alg'),'payloadKeys':sorted(payload)}
                except (ValueError,TypeError):item['jwtMetadata']={'decodableJson':False}
            hits.append(item);hit_output.write(json.dumps(item,ensure_ascii=False)+'\n');hit_output.flush()
    if view=='RAW_BYTES':
        decoded=html.unescape(text)
        if decoded!=text:scan_text(decoded.encode(),path,'HTML_XML_ENTITY_DECODED')
        if re.sub(r'#member=\d+', '', path).lower().endswith('.json'):
            try:value=json.loads(text)
            except (ValueError,TypeError):return
            def strings(obj,pointer=''):
                if isinstance(obj,str):yield pointer,obj
                elif isinstance(obj,dict):
                    for k,v in obj.items():yield from strings(v,pointer+'/'+k.replace('~','~0').replace('/','~1'))
                elif isinstance(obj,list):
                    for index,v in enumerate(obj):yield from strings(v,pointer+'/'+str(index))
            for pointer,value in strings(value):
                if '\\' in value or any(p.search(value) for p in patterns):scan_text(value.encode(),path+'#'+pointer,'JSON_STRING_DECODED')

def consume(data,path,depth=0):
    global total,archive_members
    pause();assert depth<=8,('Archive nesting bound',path)
    assert len(data)<=256*1024*1024,('Member exceeds review bound',path)
    total+=len(data);assert total<=1024*1024*1024,'Scan byte bound'
    kind='ZIP' if zipfile.is_zipfile(io.BytesIO(data)) else 'GZIP' if data.startswith(b'\x1f\x8b') else 'TAR' if len(data)>262 and data[257:262]==b'ustar' else 'FILE'
    record={'path':path,'bytes':len(data),'sha256':sha(data),'type':kind,'depth':depth}
    if kind=='ZIP':
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            members=[r for r in archive.infolist() if not r.is_dir()]
            record['memberCount']=len(members)
            for ordinal,member in enumerate(members):
                pause();assert not member.flag_bits&1,('Encrypted archive member',path,member.filename)
                assert member.file_size<=256*1024*1024
                raw=archive.read(member) # CRC is checked by ZipFile; nothing is extracted to disk.
                assert len(raw)==member.file_size
                archive_members+=1;consume(raw,path+'!'+member.filename+'#member='+str(ordinal),depth+1)
    elif kind=='GZIP':
        archive_members+=1;consume(gzip.decompress(data),path+'!gunzip',depth+1)
    elif kind=='TAR':
        with tarfile.open(fileobj=io.BytesIO(data)) as archive:
            for ordinal,member in enumerate(archive.getmembers()):
                if member.isfile():
                    archive_members+=1;consume(archive.extractfile(member).read(),path+'!'+member.name+'#member='+str(ordinal),depth+1)
    else:scan_text(data,path)
    scanned.append(record);member_output.write(json.dumps(record)+'\n');member_output.flush()

status='COMPLETE'
try:
    for p in files:consume(p.read_bytes(),str(p.relative_to(INPUT)))
except InterruptedError:status='PAUSED_ROOT_MEASUREMENT'
except Exception:
    status='INVALID_SCAN_ERROR'
    raise
finally:
    member_output.close();hit_output.close()
    after={str(p.relative_to(INPUT)):{'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())} for p in files}
    write('SCAN-SUMMARY.json',{'kind':'RAW_PUBLICATION_SHAPE_SCAN','status':status,'scope':'All original files plus actual ZIP/GZIP/TAR nested members; raw UTF-8 bytes and decoded XML/HTML/JSON strings.',
      'originalBytesUnchanged':before==after,'originalFiles':len(files),'scannedRecords':len(scanned),'archiveMembers':archive_members,
      'scannedBytesIncludingArchiveContainersAndDuplicatePhysicalCopies':total,'elapsedSeconds':round(time.time()-started,3),
      'hitCount':len(hits),'ruleCounts':dict(collections.Counter(r['rule'] for r in hits)),
      'rawFilesWithHits':sorted(set(r['path'].split('!')[0].split('#')[0] for r in hits)),
      'unreviewedHits':len(hits),'rawPublicationAuthorized':False,'testOrClosurePassImplied':False,
      'limitations':'A bounded pattern scan is not a universal no-PII proof. Every shape hit needs exact classification; JWT/test-token bytes remain private. Original failed checkpoint remains historical.'})
    print(json.dumps({'status':status,'records':len(scanned),'members':archive_members,'bytes':total,'hits':len(hits),'originalBytesUnchanged':before==after}))
