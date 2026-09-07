from pathlib import Path
import argparse,ast,hashlib,json,re,zipfile,io,subprocess,datetime
ap=argparse.ArgumentParser();ap.add_argument('--capture',type=Path,required=True);ap.add_argument('--out',type=Path,required=True);ap.add_argument('--head',required=True);ap.add_argument('--tree',required=True);a=ap.parse_args()
R=Path('/Users/chzhengx/Code/personal/marketops-platform');C=a.capture.resolve();O=a.out.resolve();H=a.head
assert re.fullmatch('[0-9a-f]{40}',H) and re.fullmatch('[0-9a-f]{40}',a.tree)
assert C.is_relative_to(Path('/tmp').resolve()) and O.is_relative_to(Path('/tmp').resolve()) and O!=Path('/tmp').resolve() and not O.exists();O.mkdir()
cap=json.loads((C/'CAPTURE-RECEIPT.json').read_text());assert cap['head']==H and cap['tree']==a.tree

sha=lambda b:hashlib.sha256(b).hexdigest();ref=lambda p:{'path':str(p.resolve()),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
prior=Path('/tmp/slice3-security-sarif-publication-02e6172-r1/EXACT-SARIF-CLASSIFICATION.json');old=json.loads(prior.read_text());known={(x['ruleId'],x['format'],x['helpSha256']) for x in old['rawOccurrences']+old['decodedOccurrences']};assert {x[0] for x in known}=={'js/session-fixation','js/jwt-missing-verification'}
v=R/'scripts/validate_governance.py';vb=subprocess.check_output(['git','--no-replace-objects','show',H+':scripts/validate_governance.py'],cwd=R);assert vb==v.read_bytes();node=next(n.value for n in ast.parse(vb).body if isinstance(n,ast.Assign) and any(isinstance(x,ast.Name) and x.id=='SECRET_PATTERNS' for x in n.targets));patterns=[re.compile(ast.literal_eval(x.args[0])) for x in node.elts];patterns.extend([re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b'),re.compile(r'https?://[^\s"<>]*(?:X-Amz-Signature|[?&]sig=|[?&]signature=)[^\s"<>]*',re.I)]);assert len(patterns)==9
files=[];hits=[];decoder=json.JSONDecoder()
def inspect(name,b):
 if zipfile.is_zipfile(io.BytesIO(b)):
  with zipfile.ZipFile(io.BytesIO(b)) as z:
   assert z.testzip() is None and len(z.namelist())==len(set(z.namelist()))
   files.append({'path':name,'sha256':sha(b),'bytes':len(b),'kind':'ZIP','crcVerified':True,'members':len(z.infolist())})
   for n in z.namelist():
    if not n.endswith('/'):inspect(name+'!/'+n,z.read(n))
  return
 text=b.decode('utf-8','replace');spans=[];helpmap={};obj=None
 try:obj=json.loads(text)
 except (ValueError,TypeError):pass
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
 if obj is not None:
  assert skip(visit(0,''))==len(text)
  if isinstance(obj,dict):
   for ri,run in enumerate(obj.get('runs',[])):
    for ei,ext in enumerate(run.get('tool',{}).get('extensions',[])):
     for qi,rule in enumerate(ext.get('rules',[])):
      for fmt,value in rule.get('help',{}).items():
       if isinstance(value,str) and (rule['id'],fmt,sha(value.encode())) in known:
        helpmap[f'/runs/{ri}/tool/extensions/{ei}/rules/{qi}/help/{fmt}']={'ruleId':rule['id'],'format':fmt,'helpSha256':sha(value.encode()),'extensionVersion':ext.get('version'),'helpUri':rule.get('helpUri')}
 local=[]
 for pi,pat in enumerate(patterns,1):
  for m in pat.finditer(text):
   matches=[x for x in spans if x[0]<=m.start() and m.end()<=x[1]];pointer=matches[0][2] if len(matches)==1 else None
   local.append({'representation':'ORIGINAL_RAW_TEXT','patternIndex':pi,'characterOffset':m.start(),'characterEnd':m.end(),'matchSha256':sha(m.group().encode()),'jsonPointer':pointer,'classification':'EXACT_OFFICIAL_RULE_HELP_EXAMPLE' if pointer in helpmap else 'UNREVIEWED',**helpmap.get(pointer,{})})
  for lo,hi,pointer,value in spans:
   for m in pat.finditer(value):local.append({'representation':'DECODED_JSON_STRING','patternIndex':pi,'characterOffset':m.start(),'characterEnd':m.end(),'matchSha256':sha(m.group().encode()),'jsonPointer':pointer,'classification':'EXACT_OFFICIAL_RULE_HELP_EXAMPLE' if pointer in helpmap else 'UNREVIEWED',**helpmap.get(pointer,{})})
 files.append({'path':name,'sha256':sha(b),'bytes':len(b),'kind':'RAW','rawHits':sum(x['representation']=='ORIGINAL_RAW_TEXT' for x in local),'decodedHits':sum(x['representation']=='DECODED_JSON_STRING' for x in local)})
 if local:hits.append({'path':name,'sha256':sha(b),'occurrences':local})
index=C/'INDEX.json';items=json.loads(index.read_text())['files']+[ref(index)]
for x in items:
 p=Path(x['path']);assert p.parent==C and sha(p.read_bytes())==x['sha256'];inspect(str(p),p.read_bytes())
unreviewed=[{'path':r['path'],**x} for r in hits for x in r['occurrences'] if x['classification']=='UNREVIEWED'];report={'kind':'EXACT_CURRENT_RAW_AND_RECURSIVE_ZIP_PUBLICATION_PATTERN_REVIEW','sourceHead':H,'sourceTree':a.tree,'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'originalCaptureIndex':ref(index),'patternAuthority':ref(v),'patternCount':9,'priorExactHelpIdentity':ref(prior),'exactRuleHelpOnly':True,'items':files,'findings':hits,'unreviewedOccurrences':unreviewed,'originalBytesChanged':False,'valuesDisclosed':False,'safeWithinThisExactPatternReview':not unreviewed,'scope':'Original API/log/ZIP bytes and every decoded JSON string are scanned. Each help occurrence uses its actual lexical JSON pointer and exact previously reviewed official help bytes; no entire rule/file exclusion. This is a bounded pattern review, not a universal absence-of-PII claim.'};p=O/'RAW-PUBLICATION-SCAN.json';assert not p.exists();p.write_text(json.dumps(report,indent=2)+'\n');assert all(sha(Path(x['path']).read_bytes())==x['sha256'] for x in items);print(json.dumps({'report':ref(p),'originalFiles':len(items),'expandedFiles':len(files),'hitFiles':len(hits),'occurrences':sum(len(x['occurrences']) for x in hits),'unreviewed':len(unreviewed)}));assert not unreviewed
