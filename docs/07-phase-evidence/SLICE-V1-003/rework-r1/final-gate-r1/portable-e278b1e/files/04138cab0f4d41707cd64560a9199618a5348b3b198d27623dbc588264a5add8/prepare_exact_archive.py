#!/usr/bin/env python3
import ast,hashlib,json,re,time,zipfile,zlib
from pathlib import Path
ROOT=Path('/Users/chzhengx/Code/personal/marketops-platform')
SRC=Path('/tmp/slice3-checkpoint-ci-02e6172').resolve()
OUT=Path(__file__).resolve().parent
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):
 p=Path(p);b=p.read_bytes();return {'path':str(p),'sha256':sha(b),'bytes':len(b)}
def write(n,d): (OUT/n).write_text(json.dumps(d,indent=2)+'\n')
started=time.monotonic(); original=SRC/'typescript.sarif.json'; b=original.read_bytes();text=b.decode('utf-8');obj=json.loads(text)
assert sha(b)=='707ab60c4f7f5e42f854211a060a69e9c7f8e79403a0d475b39aaf47fab5ed34'
validator=ROOT/'scripts/validate_governance.py';vb=validator.read_bytes()
v=next(n.value for n in ast.parse(vb).body if isinstance(n,ast.Assign) and any(isinstance(x,ast.Name) and x.id=='SECRET_PATTERNS' for x in n.targets))
patterns=[re.compile(ast.literal_eval(n.args[0])) for n in v.elts]
patterns.extend([re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b'),re.compile(r'https?://[^\s"<>]*(?:X-Amz-Signature|[?&]sig=|[?&]signature=)[^\s"<>]*',re.I)])
assert len(patterns)==9
# Parse actual JSON lexical spans by pointer. Equal text/markdown strings do not alias pointers.
dec=json.JSONDecoder();spans=[]
def skip(i):
 while i<len(text) and text[i].isspace():i+=1
 return i
def visit(i,p):
 i=skip(i)
 if text[i]=='{':
  i=skip(i+1)
  if text[i]=='}':return i+1
  while True:
   key,end=dec.raw_decode(text,i);i=skip(end);assert text[i]==':'
   i=visit(i+1,p+'/'+key.replace('~','~0').replace('/','~1'));i=skip(i)
   if text[i]=='}':return i+1
   assert text[i]==',';i=skip(i+1)
 elif text[i]=='[':
  i=skip(i+1);k=0
  if text[i]==']':return i+1
  while True:
   i=visit(i,p+'/'+str(k));k+=1;i=skip(i)
   if text[i]==']':return i+1
   assert text[i]==',';i=skip(i+1)
 else:
  val,end=dec.raw_decode(text,i)
  if isinstance(val,str):spans.append((i,end,p,val))
  return end
assert skip(visit(0,''))==len(text)
helpmap={}
for ri,run in enumerate(obj['runs']):
 for ei,ext in enumerate(run.get('tool',{}).get('extensions',[])):
  for qi,rule in enumerate(ext.get('rules',[])):
   for fmt,val in rule.get('help',{}).items():
    if isinstance(val,str):
     helpmap[f'/runs/{ri}/tool/extensions/{ei}/rules/{qi}/help/{fmt}']={'ruleId':rule['id'],'helpUri':rule.get('helpUri'),'extensionName':ext.get('name'),'extensionVersion':ext.get('version'),'helpSha256':sha(val.encode()),'format':fmt}
def details(m,s):return {'characterOffset':m.start(),'characterEndExclusive':m.end(),'byteOffset':len(s[:m.start()].encode()),'byteEndExclusive':len(s[:m.end()].encode()),'matchBytes':len(m.group().encode()),'matchSha256':sha(m.group().encode())}
rawhits=[];decoded=[]
for pi,pat in enumerate(patterns,1):
 for m in pat.finditer(text):
  enclosing=[z for z in spans if z[0]<=m.start() and m.end()<=z[1]];assert len(enclosing)==1
  lo,hi,pointer,value=enclosing[0];assert pointer in helpmap
  rawhits.append({'patternIndexOneBased':pi,**details(m,text),'jsonPointer':pointer,'containingJsonStringCharacterSpan':[lo,hi],**helpmap[pointer],'classification':'OFFICIAL_CODEQL_RULE_HELP_EXAMPLE_ONLY_NOT_APPLICATION_CONFIGURATION'})
 for lo,hi,pointer,value in spans:
  for m in pat.finditer(value):
   assert pointer in helpmap
   prefix=value[:m.start()];fence=prefix.count('```')%2==1
   assignment=re.match(r'''(?is)(?:password|passwd|secret|token|api[_-]?key)\s*[:=]\s*(["'])(.*?)\1''',m.group())
   decoded.append({'patternIndexOneBased':pi,**details(m,value),'jsonPointer':pointer,**helpmap[pointer],'insideMarkdownCodeFence':fence,'firstQuotedAssignmentValueLength':len(assignment.group(2)) if assignment else None,'classification':'OFFICIAL_CODEQL_RULE_HELP_EXAMPLE_ONLY_NOT_APPLICATION_CONFIGURATION'})
assert len(rawhits)==4 and len(decoded)==8 and all(x['patternIndexOneBased']==7 for x in rawhits+decoded)
scan=json.loads((SRC/'RAW-PUBLICATION-SCAN.json').read_text());sr=next(x for x in scan['items'] if x['path']=='typescript.sarif.json')
assert sr['sha256']==sha(b) and sr['rawHitCount']==4 and sr['decodedHitCount']==8
catalogpath=Path('/tmp/slice3-final-assessment-9d962df-r1/CURRENT-NODE-CATALOG.json');cat=json.loads(catalogpath.read_text())
def refs(x):
 if isinstance(x,dict):
  for k,v in x.items():
   if k=='path' and isinstance(v,str):yield v
   yield from refs(v)
 elif isinstance(x,list):
  for v in x:yield from refs(v)
candidate_nodes=[n for n in cat['nodes'] if any(Path(x).resolve()==original for x in refs(n.get('evidence',{})))]
assert not candidate_nodes
classification={'kind':'FRESH_EXACT_SARIF_HELP_OCCURRENCE_CLASSIFICATION','sourceHead':'02e617278793b4458dfb7cac74c2a937f1dfcb00','sourceTree':'8d8dd0b3429797a0e01b2ff91c1ba5c09de17dcc','original':ref(original),'actualOfficialFetch':ref(SRC/'typescript.sarif.json.command.json'),'officialAnalysisId':1730818438,'patternAuthority':ref(validator),'patternCount':9,'rawOccurrences':rawhits,'decodedOccurrences':decoded,'outsideActualRuleHelpOccurrences':0,'jwtOrSignedUrlOccurrences':0,'originalNinePatternScan':ref(SRC/'RAW-PUBLICATION-SCAN.json'),'originalScanCountsMatchFreshNinePatternScan':True,'originalClassificationPreserved':ref(SRC/'SECRET-SHAPE-TRIAGE.json'),'originalClassificationPointerCorrection':'The original classifier associated equal markdown/text values with the first matching pointer; exact lexical parsing assigns raw offsets 651873 and 652609 to help/text. Original JSON is preserved unchanged.','priorWaiverUsed':False,'actualCatalog':ref(catalogpath),'catalogNodes':len(cat['nodes']),'nodesWithSarifEvidencePath':0,'dynamicSelectionBoundary':'Parent/E independently confirmed the 184 added dynamic nodes refer only to mixed receipt/dataset/resources. This report checks all 4081 catalog node evidence paths.','classificationScope':'All actual matches lie in official SARIF tool.extensions.rules.help fields for js/session-fixation or js/jwt-missing-verification. They are rule documentation example bytes; none occur in results, code locations, source configuration, logs, or API credentials. No blanket file/rule exclusion is created.','matchedValuesDisclosed':False,'originalBytesChanged':False,'publishableWithinThisPatternReview':True,'publicationAuthorizedByThisReport':False,'limits':['A pattern/field-scope review is not a universal no-secret or no-PII guarantee.','The original SARIF remains in the complete original Security archive. It is not rewritten, renamed, omitted from the raw audit trail, or substituted with a redacted report.','Direct-file repository secret scan still rejects the original SARIF shape; this classification does not modify that scanner or grant an allowlist entry.']}
write('EXACT-SARIF-CLASSIFICATION.json',classification)
idx=SRC/'ARTIFACT-INDEX.json';iv=json.loads(idx.read_text());inputs=iv['files']+[ref(idx)];assert len(inputs)==87
archive=OUT/'security-02e6172-original-87-files.zip';assert not archive.exists();members=[]
with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for r in sorted(inputs,key=lambda x:Path(x['path']).name):
  p=Path(r['path']).resolve();data=p.read_bytes();assert sha(data)==r['sha256'] and len(data)==r['bytes'];assert p.parent==SRC
  name='original-security-02e6172/'+p.name;info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,data)
  members.append({'originalPath':str(p),'member':name,'sha256':sha(data),'bytes':len(data),'crc32':format(zlib.crc32(data)&0xffffffff,'08x')})
with zipfile.ZipFile(archive) as z:
 assert len(z.infolist())==87 and z.testzip() is None
 for r in members:
  data=z.read(r['member']);assert len(data)==r['bytes'] and sha(data)==r['sha256'] and format(zlib.crc32(data)&0xffffffff,'08x')==r['crc32']
  assert sha(Path(r['originalPath']).read_bytes())==r['sha256']
write('ARCHIVE-MEMBER-INDEX.json',{'kind':'EXACT_ORIGINAL_SECURITY_87_FILE_ARCHIVE','sourceHead':iv['sourceHead'],'sourceDisposition':'EXACT_PRODUCT_CHECKPOINT_SECURITY_ONLY_NOT_WHOLE_CLOSURE','archive':ref(archive),'originalIndex':ref(idx),'members':members,'memberCount':87,'allCrcAndSha256Verified':True,'allOriginalBytesStable':True,'publicationScan':ref(SRC/'RAW-PUBLICATION-SCAN.json'),'freshSarifOccurrenceClassification':ref(OUT/'EXACT-SARIF-CLASSIFICATION.json'),'noTestReexecution':True,'repositoryModified':False,'publicationPerformed':False,'elapsedSeconds':round(time.monotonic()-started,6)})
sarifmember=next(r for r in members if r['originalPath']==str(original));baseline=Path('/tmp/slice3-portable-evidence-baseline-9d962df-r1/PUBLICATION-PLAN.json');bp=json.loads(baseline.read_text());direct=next(x for x in bp['directFiles'] if Path(x['originalPath']).resolve()==original)
prefix='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/portable-02e6172/files/'
archiveDest=prefix+ref(archive)['sha256']+'/'+archive.name
write('DIRECT-TO-ARCHIVE-PATCH-PLAN.json',{'kind':'PREPARATION_ONLY_EXACT_DIRECT_FILE_TO_ORIGINAL_ARCHIVE_RELOCATION','baselinePlan':ref(baseline),'predicate':{'originalPath':str(original),'sha256':sha(b),'mustHaveNoSelectedNodeEvidence':True},'removeDirectFiles':[direct],'removeUnneededArtifactDestinationsForNonselectedRaw':[str(original)],'addDirectFiles':[{'originalPath':str(p),'repositoryPath':prefix+ref(p)['sha256']+'/'+p.name,'sha256':ref(p)['sha256'],'bytes':ref(p)['bytes']} for p in [archive,OUT/'ARCHIVE-MEMBER-INDEX.json',OUT/'EXACT-SARIF-CLASSIFICATION.json']],'addArchive':{'originalPath':str(archive),'repositoryPath':archiveDest,'sha256':ref(archive)['sha256'],'memberIndex':ref(OUT/'ARCHIVE-MEMBER-INDEX.json')},'replaceRelocationForOriginalSarif':{'originalPath':str(original),'sha256':sha(b),'repositoryPath':archiveDest,'archiveMember':sarifmember['member'],'memberBytes':sarifmember['bytes'],'memberCrc32':sarifmember['crc32']},'keepOther86DirectOrExistingMappingsUnchanged':True,'retainOriginalReceiptAbsolutePathBytes':True,'requireFinalSelectionAndPlanRecheck':True,'applied':False,'repositoryModified':False,'scannerChanged':False,'extensionChanged':False,'closureClaimMade':False})
write('RUN-RECEIPT.json',{'kind':'ACTUAL_READ_ONLY_ARCHIVE_AND_CLASSIFICATION_EXECUTION','script':ref(__file__),'inputs':[ref(idx),ref(original),ref(catalogpath)],'outputs':[ref(OUT/n) for n in ['EXACT-SARIF-CLASSIFICATION.json','ARCHIVE-MEMBER-INDEX.json','DIRECT-TO-ARCHIVE-PATCH-PLAN.json']]+[ref(archive)],'elapsedSeconds':round(time.monotonic()-started,6),'originalFiles':87,'matchedValuesDisclosed':False,'productTestsExecuted':False,'repositoryModified':False,'publicationPerformed':False})
print(json.dumps({'archive':ref(archive),'classification':ref(OUT/'EXACT-SARIF-CLASSIFICATION.json'),'rawOccurrences':4,'decodedOccurrences':8,'outsideRuleHelpOccurrences':0,'members':87,'catalogSarifEvidenceNodes':len(candidate_nodes),'elapsedSeconds':round(time.monotonic()-started,6)}))
