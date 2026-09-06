"""Classify actual scanner hits only by lexical pointer and exact official help bytes."""
import json,hashlib,re,zipfile,io,datetime
from pathlib import Path
O=Path('/tmp/slice3-final-publication-scans-e278b1e-r1');P=Path('/tmp/slice3-security-sarif-publication-02e6172-r1/EXACT-SARIF-CLASSIFICATION.json');old=json.loads(P.read_text());known={(x['ruleId'],x['format'],x['helpSha256']) for x in old['rawOccurrences']+old['decodedOccurrences']};assert {x[0] for x in known}=={'js/session-fixation','js/jwt-missing-verification'}
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
decoder=json.JSONDecoder();cache={};reviews=[]
def parse(text):
 obj=json.loads(text);spans=[];helps={}
 def skip(i):
  while i<len(text) and text[i].isspace():i+=1
  return i
 def visit(i,p):
  i=skip(i)
  if text[i]=='{':
   i=skip(i+1)
   if text[i]=='}':return i+1
   while True:
    key,end=decoder.raw_decode(text,i);i=skip(end);assert text[i]==':'
    i=visit(i+1,p+'/'+key.replace('~','~0').replace('/','~1'));i=skip(i)
    if text[i]=='}':return i+1
    assert text[i]==',';i=skip(i+1)
  elif text[i]=='[':
   i=skip(i+1);k=0
   if text[i]==']':return i+1
   while True:
    i=skip(visit(i,p+'/'+str(k)));k+=1
    if text[i]==']':return i+1
    assert text[i]==',';i=skip(i+1)
  else:
   value,end=decoder.raw_decode(text,i)
   if isinstance(value,str):spans.append((i,end,p,value))
   return end
 assert skip(visit(0,''))==len(text)
 for ri,run in enumerate(obj.get('runs',[])):
  for ei,ext in enumerate(run.get('tool',{}).get('extensions',[])):
   for qi,rule in enumerate(ext.get('rules',[])):
    for fmt,value in rule.get('help',{}).items():
     if isinstance(value,str) and (rule['id'],fmt,sha(value.encode())) in known:helps[f'/runs/{ri}/tool/extensions/{ei}/rules/{qi}/help/{fmt}']={'ruleId':rule['id'],'format':fmt,'helpSha256':sha(value.encode()),'extensionName':ext.get('name')}
 return spans,helps
for batch in ['14']:
 S=O/('scan-'+batch);summary=json.loads((S/'SCAN-SUMMARY.json').read_text());sin=json.loads((S/'SCAN-INPUT.json').read_text());assert summary['status']=='COMPLETE' and summary['originalBytesUnchanged'] and len(sin['patterns'])==9
 hits=[json.loads(x) for x in (S/'HITS.jsonl').read_text().splitlines()];members={r['path']:r for r in (json.loads(x) for x in (S/'MEMBERS.jsonl').read_text().splitlines())}
 for hit in hits:
  assert hit['rule']==7 and hit['view'] in ['RAW_BYTES','JSON_STRING_DECODED']
  split=hit['path'].split('#/',1);base=split[0];explicit='/'+split[1] if len(split)==2 else None
  if base not in cache:
   parts=base.split('!');outer=Path(parts[0]);rr=ref(outer);assert sin['files'][str(outer)]=={'sha256':rr['sha256'],'bytes':rr['bytes']};data=outer.read_bytes();chain=[rr];position=str(outer)
   for part in parts[1:]:
    m=re.fullmatch(r'(.*)#member=(\d+)',part);assert m;name,ordinal=m[1],int(m[2]);position+='!'+part
    with zipfile.ZipFile(io.BytesIO(data)) as z:
     entries=[i for i in z.infolist() if not i.is_dir()];zi=entries[ordinal];assert zi.filename==name;data=z.read(zi);assert members[position]['sha256']==sha(data) and members[position]['bytes']==len(data);chain.append({'path':position,'sha256':sha(data),'bytes':len(data),'member':name,'ordinalAmongFiles':ordinal,'crc32':f'{zi.CRC:08x}'})
   assert members[base]['sha256']==sha(data);text=data.decode('utf-8',errors='replace');spans,helps=parse(text);cache[base]=(text,spans,helps,chain)
  text,spans,helps,chain=cache[base]
  if explicit is not None:
   ss=[s for s in spans if s[2]==explicit];assert len(ss)==1;value=ss[0][3];pointer=explicit
  else:value=text;pointer=None
  matches=[m for m in re.compile(sin['patterns'][6]).finditer(value) if m.start()==hit['characterOffset']];assert len(matches)==1;m=matches[0]
  assert len(m.group())==hit['matchedCharacters'] and sha(m.group().encode())==hit['matchedSha256'] and value.count('\n',0,m.start())+1==hit['line']
  if pointer is None:
   containing=[s for s in spans if s[0]<=m.start() and m.end()<=s[1]];assert len(containing)==1;pointer=containing[0][2]
  assert pointer in helps,'Hit lies outside exactly reviewed official rule-help bytes'
  reviews.append({'batch':batch,'actualHit':hit,'actualArchiveOrFileChain':chain,'actualLexicalJsonPointer':pointer,**helps[pointer],'originalExactHelpReview':ref(P),'classification':'EXACT_OFFICIAL_CODEQL_RULE_HELP_EXAMPLE','noRedactionRequiredForThisExactHit':True,'boundary':'Exact entire decoded rule-help bytes plus actual lexical JSON pointer and reproduced scanner match. No file, rule ID, path or future-output waiver.'})
assert len(reviews)==40
result={'kind':'EXACT_CURRENT_PUBLICATION_SARIF_HELP_MATCH_REVIEW','publicationProductHead':'e278b1e3d8541aeb806e41d6cbef4deac8d16d06','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'tool':ref(__file__),'priorExactHelpReview':ref(P),'originalScans':[ref(O/('scan-'+b)/n)for b in ['14']for n in ['SCAN-INPUT.json','SCAN-SUMMARY.json','HITS.jsonl','MEMBERS.jsonl']],'reviewedOccurrences':len(reviews),'unreviewedOccurrences':0,'occurrences':reviews,'originalBytesChanged':False,'valuesDisclosed':False,'scope':'Both historical and H official SARIF originals keep their original analysis/run identities. This report establishes only exact publication pattern disposition.','productionWriteEnabled':False}
p=O/'EXACT-ARCHIVED-SARIF-HELP-TRIAGE.json';assert not p.exists();p.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'review':ref(p),'exactReviewedOccurrences':len(reviews),'unreviewed':0}))
