#!/usr/bin/env python3
from pathlib import Path
import ast,collections,hashlib,json,re,subprocess,zipfile
ROOT=Path('/Users/chzhengx/Code/personal/marketops-platform')
OUT=Path(__file__).resolve().parent
HEAD='1381ef78594875a1e1304e4d9a636e49d0fab71f'
MERGE='ddec24ca37d71a61772b6682e633d7536da1cb3e'
sha=lambda raw:hashlib.sha256(raw).hexdigest()
load=lambda name:json.loads((OUT/name).read_text())
def write(name,obj):
 (OUT/name).write_text(json.dumps(obj,indent=2)+'\n')
def proof(path):
 return {'path':str(path.relative_to(ROOT) if path.is_relative_to(ROOT) else path.relative_to(OUT)), 'bytes':path.stat().st_size,'sha256':sha(path.read_bytes())}
oldpath=ROOT/'docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/security-w8/summary.json'
old={a['number']:a for a in json.loads(oldpath.read_text())['historicalDismissedSecurity']}
current={a['number']:a for page in load('dismissed-alerts-pages-r1.json') for a in page}
assert set(old)==set(current)=={66,73,74,75,76}
rows=[]
for number,a in sorted(current.items()):
 prior=old[number];loc=a['most_recent_instance']['location'];raw=subprocess.check_output(['git','show',HEAD+':'+loc['path']],cwd=ROOT)
 fields=['state','dismissed_at','dismissed_reason','dismissed_comment']
 row={'number':number,'ruleId':a['rule']['id'],'securitySeverity':a['rule']['security_severity_level'],
  'currentLocation':loc,'sourceHead':HEAD,'instanceCommit':a['most_recent_instance']['commit_sha'],
  'currentSourceSha256':sha(raw),'historicalSourceSha256':prior['w8SourceSha256'],
  'sourceByteIdentical':sha(raw)==prior['w8SourceSha256'],
  **{k:a[k] for k in fields},'allDismissalMetadataUnchanged':all(a[k]==prior[k] for k in fields),
  'assessment':'Historical false-positive disposition retained. Exact affected source bytes and dismissal metadata match W8. This HIGH remains present in raw SARIF; no new dismissal or zero-raw-HIGH claim.'}
 assert row['sourceByteIdentical'] and row['allDismissalMetadataUnchanged'] and row['instanceCommit']==MERGE
 rows.append(row)
write('HISTORICAL-DISMISSED-HIGH-RECONCILIATION.json',{'kind':'SEPARATE_HISTORICAL_DISMISSED_HIGH_RECONCILIATION','sourceHead':HEAD,'testedMerge':MERGE,'priorEvidence':proof(oldpath),'currentRawEvidence':proof(OUT/'dismissed-alerts-pages-r1.json'),'count':len(rows),'newDismissalsPerformed':False,'findings':rows})
# Scan original API responses and each original ZIP member. Never emit matched values.
validator=ROOT/'scripts/validate_governance.py'
validator_bytes=subprocess.check_output(['git','show',HEAD+':scripts/validate_governance.py'],cwd=ROOT)
assert sha(validator.read_bytes())==sha(validator_bytes), 'Do not scan under changed validator patterns'
node=next(n for n in ast.parse(validator_bytes).body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='SECRET_PATTERNS' for t in n.targets))
patterns=[re.compile(ast.literal_eval(v.args[0])) for v in node.value.elts]
extras=[re.compile(r'\beyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\b'),re.compile(r'https?://[^\s"<>]*(?:X-Amz-Signature|[?&]sig=|[?&]signature=)[^\s"<>]*',re.I)]
allpatterns=patterns+extras
known={'js/session-fixation':'1d7e2ebf8c59c4f94905ea79aa023c08b5dbae7a7dfbef9cafc706302b25956b','js/jwt-missing-verification':'2a7376043713c3885dcafd7c8d739ac8736c6add25040ef728cdb929f01ab884'}
priortriage=ROOT/'docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/security-w8/secret-shape-triage.json'
scan=[];triage=[]
def walk(obj,pointer=''):
 if isinstance(obj,dict):
  for k,v in obj.items():yield from walk(v,pointer+'/'+k.replace('~','~0').replace('/','~1'))
 elif isinstance(obj,list):
  for i,v in enumerate(obj):yield from walk(v,pointer+'/'+str(i))
 elif isinstance(obj,str):yield pointer,obj
stringtokens=re.compile(r'"(?:\\.|[^"\\])*"',re.S)
def inspect(name,raw):
 text=raw.decode('utf-8',errors='replace');allowed={};obj=None
 try:obj=json.loads(text)
 except (ValueError,TypeError):pass
 if isinstance(obj,dict) and 'runs' in obj:
  for ri,run in enumerate(obj['runs']):
   for ei,extension in enumerate(run.get('tool',{}).get('extensions',[])):
    for qi,rule in enumerate(extension.get('rules',[])):
     for fmt,value in rule.get('help',{}).items():
      if isinstance(value,str) and sha(value.encode())==known.get(rule.get('id')):
       pointer=f'/runs/{ri}/tool/extensions/{ei}/rules/{qi}/help/{fmt}'
       allowed[pointer]={'ruleId':rule['id'],'decodedHelpValueSha256':sha(value.encode()),'value':value}
 tokens=[]
 if obj is not None:
  for match in stringtokens.finditer(text):
   try: value=json.loads(match.group())
   except ValueError: continue
   safe=[(p,a) for p,a in allowed.items() if value==a['value']]
   if safe:tokens.append((match.start(),match.end(),safe[0]))
 rawhits=[]
 for index,pattern in enumerate(allpatterns):
  for match in pattern.finditer(text):
   safe=next((safe for start,end,safe in tokens if start<=match.start() and match.end()<=end),None)
   hit={'patternIndex':index,'offset':match.start(),'length':match.end()-match.start(),'line':text.count('\n',0,match.start())+1,'classification':'UNREVIEWED_SECRET_SHAPE'}
   if safe:hit.update(classification='STATIC_CODEQL_RULE_HELP_EXAMPLE_NOT_APPLICATION_SECRET',jsonPointer=safe[0],**{k:v for k,v in safe[1].items() if k!='value'})
   rawhits.append(hit)
 decodedhits=[]
 if obj is not None:
  for pointer,value in walk(obj):
   for index,pattern in enumerate(allpatterns):
    count=len(list(pattern.finditer(value)))
    if count:
     hit={'jsonPointer':pointer,'patternIndex':index,'count':count,'classification':'UNREVIEWED_SECRET_SHAPE'}
     if pointer in allowed:hit.update(classification='STATIC_CODEQL_RULE_HELP_EXAMPLE_NOT_APPLICATION_SECRET',**{k:v for k,v in allowed[pointer].items() if k!='value'})
     decodedhits.append(hit)
 scan.append({'path':name,'bytes':len(raw),'sha256':sha(raw),'rawHitCount':len(rawhits),'decodedHitCount':sum(x['count'] for x in decodedhits)})
 if rawhits or decodedhits:triage.append({'path':name,'rawSha256':sha(raw),'rawHits':rawhits,'decodedHits':decodedhits})
rawfiles=[]
for command in sorted(OUT.glob('*.command.json')):
 record=json.loads(command.read_text());rawfiles.extend([OUT/record['artifact']['path'],OUT/record['stderr']['path'],command])
for p in sorted(set(rawfiles)):
 raw=p.read_bytes()
 if p.suffix=='.zip':
  with zipfile.ZipFile(p) as archive:
   for member in sorted(archive.namelist()):inspect(p.name+'!/'+member,archive.read(member))
 else:inspect(p.name,raw)
unreviewed=sum(h['classification']=='UNREVIEWED_SECRET_SHAPE' for row in triage for kind in ['rawHits','decodedHits'] for h in row[kind])
write('RAW-PUBLICATION-SCAN.json',{'kind':'UNCHANGED_RAW_RESPONSE_AND_EXPANDED_LOG_SECRET_SHAPE_SCAN','patternAuthority':proof(validator),'additionalPatterns':['JWT token shape','signed URL parameter shape'],'items':scan,'passWithoutTriage':not triage,'matchedValuesDisclosed':False})
write('SECRET-SHAPE-TRIAGE.json',{'kind':'EXACT_STATIC_CODEQL_RULE_HELP_ONLY_TRIAGE','originalScan':proof(OUT/'RAW-PUBLICATION-SCAN.json'),'priorHelpReview':proof(priortriage),'findings':triage,'unreviewedOccurrences':unreviewed,'safeToPublishOriginalPayloads':unreviewed==0,'method':'Raw and decoded-string hits are allowed only inside exact SHA-256-matched historical CodeQL rule-help strings at their actual SARIF rule/pointer. No result, code excerpt, raw API response or log bytes are altered. All other hits remain unreviewed.','matchedValuesDisclosed':False})
print(json.dumps({'dismissedHighExactSourceAndMetadataMatches':len(rows),'scannedItems':len(scan),'rawHits':sum(x['rawHitCount'] for x in scan),'decodedHits':sum(x['decodedHitCount'] for x in scan),'unreviewedOccurrences':unreviewed,'hitFiles':[x['path'] for x in triage]}))
