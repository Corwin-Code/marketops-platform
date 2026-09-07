#!/usr/bin/env python3
"""Scan an explicit original file manifest using the previously reviewed recursive nine-rule scanner."""
import ast,argparse,base64,collections,gzip,hashlib,html,io,json,re,tarfile,time,zipfile
from pathlib import Path
ROOT=Path('/Users/chzhengx/Code/personal/marketops-platform')
p=argparse.ArgumentParser();p.add_argument('--manifest',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
OUT=a.out.resolve();assert OUT.is_relative_to(Path('/tmp').resolve()) and not OUT.exists();OUT.mkdir()
sha=lambda b:hashlib.sha256(b).hexdigest()
def write(name,value):(OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
manifest=json.loads(a.manifest.read_bytes());files=[Path(r['path']).resolve() for r in manifest['files']];assert len(set(files))==len(files)
assert all(p.is_file() and not p.is_symlink() for p in files)
before={str(p):{'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size} for p in files}
assert all(before[str(Path(r['path']).resolve())]['sha256']==r['sha256'] for r in manifest['files'])
VALIDATOR=ROOT/'scripts/validate_governance.py';assert sha(VALIDATOR.read_bytes())=='89f716bbada69c90db33869b7d15ba5e702b595f07d4ac690373ebbadff60b34'
rules=[]
for node in ast.parse(VALIDATOR.read_text()).body:
 if isinstance(node,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='SECRET_PATTERNS' for t in node.targets):rules=[ast.literal_eval(item.args[0]) for item in node.value.elts]
assert len(rules)==7
rules.append(r'\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{8,}\b')
rules.append(r'(?i)https?://[^\s\"<>]*(?:X-Amz-Signature|X-Goog-Signature|[?&]sig=|[?&]signature=|[?&]access_token=|[?&]token=)[^\s\"<>]*')
patterns=[re.compile(p) for p in rules]
write('SCAN-INPUT.json',{'kind':'EXPLICIT_ORIGINAL_FILE_MANIFEST_SCAN','scope':manifest['scope'],'files':before,'manifest':{'path':str(a.manifest.resolve()),'sha256':sha(a.manifest.read_bytes())},'scanner':{'path':str(Path(__file__).resolve()),'sha256':sha(Path(__file__).read_bytes())},'rulesSource':{'path':str(VALIDATOR),'sha256':sha(VALIDATOR.read_bytes())},'patterns':rules,'productExecutions':0,'closureClaimMade':False})
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
            item['redactedContext']='<Raw context omitted; exact original file, line, offset and match SHA identify private triage scope>'
            if number in (8,9):
                item.update(classification='TOKEN_SHAPED_OUTPUT_PRIVATE_UNTIL_CLASSIFIED',redactedContext='<Token or signed-URL-shaped material omitted>')
                try:
                    if number==9:raise ValueError('Signed URL has no JWT claims')
                    parts=value.split('.')
                    header=json.loads(base64.urlsafe_b64decode(parts[0]+'='*(-len(parts[0])%4)))
                    payload=json.loads(base64.urlsafe_b64decode(parts[1]+'='*(-len(parts[1])%4)))
                    item['jwtMetadata']={'headerJsonObject':isinstance(header,dict),'payloadJsonObject':isinstance(payload,dict)}
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
 for f in files:consume(f.read_bytes(),str(f))
except Exception:
 status='INVALID_SCAN_ERROR';raise
finally:
 member_output.close();hit_output.close()
 after={str(p):{'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size} for p in files}
 if before!=after:status='INVALID_ORIGINAL_BYTES_CHANGED'
 summary={'kind':'EXPLICIT_ORIGINAL_FILE_RECURSIVE_PATTERN_SCAN','status':status,'scope':manifest['scope'],'originalFiles':len(files),'originalBytesUnchanged':before==after,'scannedRecords':len(scanned),'archiveMembers':archive_members,'scannedBytesIncludingContainersAndDuplicateCopies':total,'elapsedSeconds':round(time.time()-started,3),'hitCount':len(hits),'ruleCounts':dict(collections.Counter(r['rule'] for r in hits)),'unreviewedHits':len(hits),'rawPublicationAuthorized':False,'productExecutions':0,'closureClaimMade':False,'limits':'Nine bounded patterns; UTF8/raw/entity/JSON strings and actual nested archive members. Images scanned as bytes, no OCR. All hits require exact context review; no generic no-PII claim.'}
 write('SCAN-SUMMARY.json',summary);print(json.dumps(summary))
