#!/usr/bin/env python3
"""Bounded adaptation of archive_backend.py for the exact already-scanned 439-file history manifest."""
import collections,hashlib,json,time,zipfile,zlib
from pathlib import Path
import xml.etree.ElementTree as ET
OUT=Path(__file__).resolve().parent;SCAN=Path('/tmp/slice3-historical-1381-raw-scan-r1');HEAD='1381ef78594875a1e1304e4d9a636e49d0fab71f';TREE='d366331b9b0ad7f8eb0f90c8536f46c0d2b9ce5e';start=time.monotonic()
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p).resolve();b=p.read_bytes();return {'path':str(p),'sha256':sha(b),'bytes':len(b)}
def read(p):return json.loads(Path(p).read_text())
def write(n,d):(OUT/n).write_text(json.dumps(d,indent=2)+'\n')
si=read(SCAN/'SCAN-INPUT.json');summary=read(SCAN/'SCAN-SUMMARY.json');triage=OUT/'triage-r2/EXACT-HIT-TRIAGE.json';tr=read(triage)
assert len(si['files'])==summary['originalFiles']==439 and summary['status']=='COMPLETE' and summary['unreviewedHits']==5 and tr['reviewedHitCount']==5 and tr['unresolvedHitCount']==0 and not tr['priorWaiversUsed']
assert tr['originalScan']['sha256']==ref(SCAN/'SCAN-SUMMARY.json')['sha256']
roots={'local-backend-failed-partial':Path('/tmp/slice3-final-execution-1381ef7-r4/backend-full').resolve(),'backend-ci-cancelled':Path('/tmp/slice3-checkpoint-backend-ci-1381ef7').resolve(),'frontend-ci-failed':Path('/tmp/slice3-checkpoint-frontend-ci-1381ef7').resolve()}
parentpath=roots['local-backend-failed-partial']/'layer-candidate.json';parent=read(parentpath)
assert parent['sourceHead']==HEAD and parent['sourceTree']==TREE and parent['exitCode']==143 and parent['sourceStable'] and parent['testcaseCounts']=={'passed':2040,'failures':1,'errors':0,'skipped':0}
rows=[]
for s,r in sorted(si['files'].items()):
 p=Path(s).resolve();b=p.read_bytes();assert sha(b)==r['sha256'] and len(b)==r['bytes']
 matches=[(k,root)for k,root in roots.items()if p.is_relative_to(root)];assert len(matches)==1
 scope,base=matches[0];member=scope+'/'+p.relative_to(base).as_posix();rows.append({'originalPath':str(p),'member':member,'scope':scope,'sha256':sha(b),'bytes':len(b),'crc32':f'{zlib.crc32(b)&0xffffffff:08x}'})
byoriginal={r['originalPath']:r for r in rows}
for e in parent['evidence']:
 r=byoriginal[str(Path(e['path']).resolve())];assert r['sha256']==e['sha256']
for s in ['source-before.json','source-after.json']:
 assert ref(roots['local-backend-failed-partial']/s)['sha256']==parent['sourceInventorySha256']
# Independently preserve local partial and each CI artifact's raw statuses without summing repeated workflows.
def classify(n):return next((x for x in ['failure','error','skipped']if n.find(x)is not None),'passed')
def normalize(c):return {'passed':c['passed'],'failures':c['failure'],'errors':c['error'],'skipped':c['skipped']}
local=collections.Counter();localnodes=[]
for r in rows:
 p=Path(r['originalPath'])
 if r['scope']!='local-backend-failed-partial' or not p.name.startswith('TEST-')or p.suffix!='.xml' or not any(x in p.parts for x in ['surefire-reports','failsafe-reports']):continue
 for i,n in enumerate(ET.fromstring(p.read_bytes()).iter('testcase')):
  state=classify(n);local[state]+=1;localnodes.append({'class':n.get('classname'),'name':n.get('name'),'ordinal':i,'result':state,'member':r['member'],'sha256':r['sha256']})
assert normalize(local)==parent['testcaseCounts']
ci=[]
for name in ['backend-test-reports.zip','backend-integration-reports.zip']:
 p=roots['backend-ci-cancelled']/name;counts=collections.Counter();reports=0;nodes=[]
 with zipfile.ZipFile(p)as z:
  for e in z.infolist():
   if not Path(e.filename).name.startswith('TEST-')or not e.filename.endswith('.xml'):continue
   b=z.read(e);tree=ET.fromstring(b);cases=list(tree.iter('testcase'))
   if not cases:continue
   reports+=1
   for i,n in enumerate(cases):
    state=classify(n);counts[state]+=1;nodes.append({'class':n.get('classname'),'name':n.get('name'),'ordinal':i,'result':state,'member':e.filename,'sha256':sha(b)})
 ci.append({'originalArchive':ref(p),'reports':reports,'actualRawTestcaseCounts':normalize(counts),'nodes':nodes,'boundary':'Actual artifact-specific subset from a cancelled workflow; no full workflow pass and no aggregation with repeated local/job nodes.'})
archive=OUT/'1381ef7-failed-and-cancelled-original-439-files.zip';assert not archive.exists();rows.sort(key=lambda r:r['member'])
with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6)as z:
 for r in rows:
  data=Path(r['originalPath']).read_bytes();assert sha(data)==r['sha256'];e=zipfile.ZipInfo(r['member'],(1980,1,1,0,0,0));e.create_system=3;e.external_attr=0o100644<<16;e.compress_type=zipfile.ZIP_DEFLATED;z.writestr(e,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=6)
with zipfile.ZipFile(archive)as z:
 assert z.testzip()is None and z.namelist()==[r['member']for r in rows]
 for r in rows:
  data=z.read(r['member']);assert sha(data)==r['sha256'] and len(data)==r['bytes'] and f'{zlib.crc32(data)&0xffffffff:08x}'==r['crc32']
assert all(ref(r['originalPath'])['sha256']==r['sha256'] for r in rows)
assert read(SCAN/'SCAN-SUMMARY.json')==summary and read(SCAN/'SCAN-INPUT.json')==si
write('RAW-TESTCASE-RECONCILIATION.json',{'kind':'HISTORICAL1381_PARTIAL_AND_CANCELLED_RAW_RECOUNT','sourceHead':HEAD,'sourceTree':TREE,'local':{'parent':ref(parentpath),'actualRawTestcaseCounts':normalize(local),'nodes':localnodes},'officialBackendArtifacts':ci,'noCrossRunNodeAddition':True,'fullCheckpointPassed':False,'productTestsExecuted':False})
backend=read(roots['backend-ci-cancelled']/'CI-CHECKPOINT-RECEIPT.json');frontend=read(roots['frontend-ci-failed']/'DIAGNOSIS.json')
assert backend['run']['head_sha']==frontend['run']['head_sha']==HEAD and backend['run']['conclusion']=='cancelled' and frontend['run']['conclusion']=='failure'
write('ARCHIVE-MEMBER-INDEX.json',{'kind':'IMMUTABLE_HISTORICAL1381_ORIGINAL_SCAN_INPUT_ARCHIVE','sourceHead':HEAD,'sourceTree':TREE,'disposition':'LOCAL_BACKEND_FAILED_PARTIAL_AND_BACKEND_CI_CANCELLED_AND_FRONTEND_CI_FAILED_NOT_CURRENT_PASS','archive':ref(archive),'memberCount':439,'members':rows,'uncompressedBytes':sum(r['bytes']for r in rows),'allCrcAndSha256AndSizesVerified':True,'originalScanInput':ref(SCAN/'SCAN-INPUT.json'),'originalScanSummary':ref(SCAN/'SCAN-SUMMARY.json'),'originalScanUnreviewedHitsRetained':5,'independentExactHitClassification':ref(triage),'allOriginalBytesUnchanged':True,'localCollectorEvidenceReferencesVerified':len(parent['evidence']),'localCollector':ref(parentpath),'backendCi':{'run':backend['run'],'receipt':ref(roots['backend-ci-cancelled']/'CI-CHECKPOINT-RECEIPT.json')},'frontendCi':{'run':frontend['run'],'receipt':ref(roots['frontend-ci-failed']/'DIAGNOSIS.json')},'rawReconciliation':ref(OUT/'RAW-TESTCASE-RECONCILIATION.json'),'publicationPerformed':False,'repositoryModified':False,'productTestsExecuted':False,'engineeringClosureClaimMade':False,'productionWriteEnabled':False,'elapsedSeconds':round(time.monotonic()-start,6)})
(OUT/'README.md').write_text('This immutable appendix preserves the exact 439 files already inspected by the separate nine-rule scan for failed or cancelled checkpoint 1381ef7. The local backend was interrupted after 2,040 passes and one failure (exit 143); its source inventory stayed stable. Backend CI run 34007484515 ended cancelled; Frontend CI run 34007484556 failed. These are historical records and do not establish current product PASS.\n\nEvery archive member retains original bytes and an explicit original-path/relative-member/SHA256/CRC mapping. The independent five-hit classification parses the actual nested dependency class constant pools: four public PEM format literals and one binary cross-record serializer-shape false positive. It uses no historical waiver. The original scan still says five unreviewed hits; its bytes and fields are not changed. The separate classification binds each exact occurrence.\n\nNo secrets or matched literal values are printed, no repository files are changed, and no product tests or Provider actions are executed. Bounded pattern scans are not universal no-PII proofs or publication authority. The raw recount keeps local and each CI artifact separate; repeated nodes are not added into a current total.\n')
files=[ref(p)for p in sorted(OUT.rglob('*'))if p.is_file() and p.name!='DELIVERY-INDEX.json']
external=[ref(p)for p in sorted(SCAN.iterdir())if p.is_file()]+[ref(si['manifest']['path']),ref(si['scanner']['path']),ref('/tmp/inspect_slice3_class_hits.py'),ref('/tmp/slice3-backend-checkpoint-archives-r1/archive_backend.py')]
write('DELIVERY-INDEX.json',{'kind':'HISTORICAL1381_ARCHIVE_AND_FRESH_EXACT_CLASSIFICATION_DELIVERY','sourceHead':HEAD,'sourceTree':TREE,'files':files,'originalScanAndReusableToolReferences':external,'preserveOriginalAbsolutePathJsonBytes':True,'archiveOriginalFiles':439,'rawOriginalScanUnreviewedHits':5,'independentlyClassifiedExactHits':5,'independentUnresolvedHits':0,'publicationPerformed':False,'repositoryModified':False,'productTestsExecuted':False,'engineeringClosureClaimMade':False})
print(json.dumps({'archive':ref(archive),'index':ref(OUT/'ARCHIVE-MEMBER-INDEX.json'),'delivery':ref(OUT/'DELIVERY-INDEX.json'),'originalFiles':439,'localCounts':normalize(local),'ciArtifactCounts':[{ 'archive':x['originalArchive']['path'],'counts':x['actualRawTestcaseCounts']}for x in ci],'elapsedSeconds':round(time.monotonic()-start,6)}))
