#!/usr/bin/env python3
import collections,hashlib,json,re,subprocess
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
RAW=Path('/tmp/slice3-checkpoint-backend-ci-7e66cf8');OUT=Path(__file__).resolve().parent
SEC=Path('/tmp/slice3-checkpoint-ci-7e66cf8');LOCAL=Path('/tmp/slice3-final-execution-7e66cf8-r3/backend-full')
HEAD='7e66cf87afc15773d4f6e6e6717a86e7b8336ae5';PREFIX='backend/marketops-server/'
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):return {'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p.read_bytes())}
def write(name,d):(OUT/name).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
run=json.loads((RAW/'run.json').read_text());jobs={j['name']:j for j in json.loads((RAW/'jobs.json').read_text())['jobs']};arts=json.loads((RAW/'artifacts.json').read_text())['artifacts'];downloads=json.loads((RAW/'download-commands.json').read_text());security=json.loads((SEC/'SECURITY-RECEIPT.json').read_text());comparison=json.loads((SEC/'SOURCE-IDENTITY-COMPARISON.json').read_text());MERGE=security['testedMerge']
assert run['id']==34001582148 and run['head_sha']==HEAD and run['conclusion']=='success'
inv=ref(LOCAL/'source-before.json');saved={x['path']:x['sha256'] for x in json.loads((LOCAL/'source-before.json').read_text())['files']}
assert inv['sha256']==security['executionSourceInventory']['sha256']==comparison['inventorySha256']
assert sha((SEC/security['sourceInventoryComparison']['path']).read_bytes())==security['sourceInventoryComparison']['sha256']
assert comparison['testedMerge']==MERGE and comparison['testedMergeTree']==comparison['sourceTree']==security['sourceTree'] and comparison['testedMergeParents']==[run['pull_requests'][0]['base']['sha'],HEAD]
assert len(comparison['files'])==len(saved)==1272
for item in comparison['files']:assert saved[item['path']]==item['executedSha256'] and item['match'] is True
workflow=subprocess.check_output(['git','show',HEAD+':.github/workflows/backend.yml']);assert sha(workflow)==saved['.github/workflows/backend.yml'];(OUT/'backend-workflow-7e66cf8.yml').write_bytes(workflow)
allmembers=[];archive_refs={};metadata_refs=[]
for p in sorted(RAW.glob('*.zip')):
 data=p.read_bytes();rr=ref(p);archive_refs[p.name]=rr
 a=next((a for a in arts if a['name']+'.zip'==p.name),None)
 if a:assert a['digest']=='sha256:'+rr['sha256'] and a['size_in_bytes']==rr['bytes'] and a['workflow_run']['id']==run['id'] and a['workflow_run']['head_sha']==HEAD
 download=next(d for d in downloads if Path(d['stdout']).name==p.name);assert download['exitCode']==0 and download['sha256']==rr['sha256'] and download['bytes']==rr['bytes']
 with zipfile.ZipFile(p) as z:
  assert z.testzip() is None;names=[i.filename for i in z.infolist() if not i.is_dir()];assert len(names)==len(set(names))
  for i in z.infolist():
   if i.is_dir():continue
   b=z.read(i);assert len(b)==i.file_size
   allmembers.append({'archive':p.name,'artifactId':a['id'] if a else None,'member':i.filename,'bytes':len(b),'sha256':sha(b),'crc32':f'{i.CRC:08x}'})
membermap={(m['archive'],m['member']):m for m in allmembers}
def mref(archive,member):return dict(membermap[(archive,member)],archiveReference=archive_refs[archive])
checkouts=[];log_summaries={}
with zipfile.ZipFile(RAW/'run-logs.zip') as z:
 for job in jobs:
  member=job+'/2_Checkout repository.txt';text=z.read(member).decode();lines=text.splitlines()
  hashes=[re.sub(r'^\S+\s+','',l) for l in lines if re.fullmatch(r'\S+\s+[0-9a-f]{40}',l)];assert MERGE in hashes
  assert 'Merge '+HEAD+' into '+run['pull_requests'][0]['base']['sha'] in text
  checkouts.append({'jobId':jobs[job]['id'],'job':job,'testedMerge':MERGE,'sourceHead':HEAD,'evidence':mref('run-logs.zip',member),'exactMergeHashLineNumbers':[i+1 for i,l in enumerate(lines) if l.endswith(' '+MERGE)]})
  candidates=[n for n in z.namelist() if n.startswith(job+'/') and ('Run full backend verification' in n or 'Run the database and application integration tests' in n or 'Run the boundary rules' in n)]
  assert len(candidates)==1;c=candidates[0];body=z.read(c).decode(errors='replace')
  result=[]
  for ordinal,line in enumerate(body.splitlines()):
   match=re.search(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)(?:, Time elapsed: .*? -- in (.+))?',line)
   if match:result.append({'line':ordinal+1,'tests':int(match[1]),'failures':int(match[2]),'errors':int(match[3]),'skipped':int(match[4]),'suiteLabel':match[5]})
  log_summaries[job]={'evidence':mref('run-logs.zip',c),'actualMavenSummaryLines':result,'buildSuccessObserved':'BUILD SUCCESS' in body,'duplicateLogCopiesNotCounted':True}
job_reports=[];source_comparisons=[]
for aname,job in [('backend-test-reports','backend-build'),('backend-integration-reports','backend-integration')]:
 archive=aname+'.zip';artifact=next(a for a in arts if a['name']==aname);j=jobs[job];assert j['conclusion']=='success' and j['status']=='completed' and j['run_attempt']==1
 with zipfile.ZipFile(RAW/archive) as z:
  names=z.namelist();nodes=[];counts=collections.Counter();families=collections.defaultdict(collections.Counter)
  for member in names:
   if not member.endswith('.xml') or not any(x in member for x in ['/surefire-reports/','/failsafe-reports/']):continue
   tree=ET.fromstring(z.read(member));family='unit' if '/surefire-reports/' in member else 'integration'
   for ordinal,n in enumerate(tree.iter('testcase')):
    status=next((t for t in ['failure','error','skipped'] if n.find(t) is not None),'passed');status={'failure':'failures','error':'errors'}.get(status,status)
    counts[status]+=1;families[family][status]+=1;nodes.append({'class':n.get('classname'),'name':n.get('name'),'family':family,'status':status,'ordinal':ordinal,'evidence':mref(archive,member)})
  unique=collections.Counter((n['family'],n['class'],n['name']) for n in nodes)
  assert not [k for k,v in unique.items() if v>1]
  assert counts['failures']==counts['errors']==counts['skipped']==0
  write(job+'-RAW-NAMED-JUNIT.json',{'kind':'ACTUAL_CI_RAW_JUNIT_NODES','sourceHead':HEAD,'testedMerge':MERGE,'runId':run['id'],'jobId':j['id'],'runAttempt':1,'artifactId':artifact['id'],'nodes':nodes,'counts':dict(counts),'families':dict(families),'duplicateExactNodes':0})
  measurements=[]
  for kind in ['advertising-capacity','advertising-mixed-capacity']:
   path=PREFIX+'target/'+kind+'-receipt.json';r=json.loads(z.read(path));ids=r['identities'];publication=ids['publicationIdentity'];assert publication['sourceHeadSha']==HEAD and publication['testedMergeSha']==MERGE and publication['workflowJob']==job and publication['artifactName']==aname and publication['workflowRunId']==str(run['id']) and publication['workflowRunAttempt']=='1'
   assert ids['measuredLocalGitHead']==MERGE and ids['ciIdentity']['GITHUB_SHA']==MERGE
   dataset=PREFIX+ids['datasetPath'];inputs=PREFIX+ids['sourceInputsPath'];assert sha(z.read(dataset))==ids['datasetSha256'] and sha(z.read(inputs))==ids['sourceInputsSha256']
   sourceitems=json.loads(z.read(inputs));assert isinstance(sourceitems,list)
   checks=[{'path':PREFIX+x['path'],'recordedSha256':x['sha256'],'savedExecutedSha256':saved.get(PREFIX+x['path']),'matches':saved.get(PREFIX+x['path'])==x['sha256']} for x in sourceitems];assert all(c['matches'] for c in checks)
   localinput=LOCAL/'raw'/inputs;assert sha(localinput.read_bytes())==ids['sourceInputsSha256']
   source_comparisons.append({'jobId':j['id'],'job':job,'artifactId':artifact['id'],'kind':kind,'sourceInputs':mref(archive,inputs),'savedLocalSourceInputs':ref(localinput),'recordedSourceFileCount':len(checks),'allRowsMatchSavedLocalExecutionInventory':True,'comparisons':checks})
   res='build/slice3-runtime-resources.json';resources=json.loads(z.read(res));assert sha(z.read(res))==ids['runtimeResourceReceiptSha256'];assert resources['github']=={'runId':str(run['id']),'runAttempt':'1','job':job,'testedCheckout':MERGE}
   target=r['targeted'];thresholds={'criticalP95LE300000':target['criticalP95Millis']<=300000,'maximumLE900000':target['maximumMillis']<=900000,'sweepLT1800000':r['sweepWallMillis']<1800000,'zeroHardBreaches':target['hardBreachCount']==0,'zeroClockDefects':target['clockDefectCount']==0,'zeroPending':target['pendingRequests']==0,'zeroFailed':target['failedRequests']==0}
   assert all(thresholds.values()) and r['productionWriteEnabled'] is False and r['realProviderAccess'] is False
   measurements.append({'kind':kind,'receipt':mref(archive,path),'dataset':mref(archive,dataset),'sourceInputs':mref(archive,inputs),'datasetId':ids['datasetId'],'identities':ids,'actualDataset':r['dataset'],'actualRuntime':r['runtime'],'outerResources':resources,'outerResourceEvidence':mref(archive,res),'postgresContainerResources':r.get('postgresContainerResources'),'actualTargeted':target,'targetedWallMillis':r['targetedWallMillis'],'sweepWallMillis':r['sweepWallMillis'],'hourlyMarginMillis':r['hourlyMarginMillis'],'observedThresholdComparisons':thresholds,'stateAndControlCounts':{k:v for k,v in r.items() if k in ['setupOutcomeStages','afterTargetedOutcomeStages','afterTargetedRetainedVerdicts','criticalSalesGuardEvidenceAfterTargeted','afterSweepOutcomeStages','latestRetainedVerdicts','exceptionStates','expiredAuthorizations','expiredAuthorizationsAlreadyInvalidated','newAuthorizationExpiryJournals','currentCommandWriteGateReasons','historicalCommandWriteGateReasons','heldReservations','releasedHistoricalReservations','droppedLateCorrectionObjectsRecovered','sweepRepairedRequestRows','sweepRunId']},'scopeNotice':r.get('scopeNotice'),'productionWriteEnabled':False,'realProviderAccess':False})
  coverage=None
  jp=PREFIX+'target/site/jacoco/jacoco.xml'
  if jp in names:
   root=ET.fromstring(z.read(jp));rc={c.attrib['type']:{k:int(c.attrib[k]) for k in ['missed','covered']} for c in root.findall('counter')};cc=collections.defaultdict(lambda:{'missed':0,'covered':0});line={'missed':0,'covered':0}
   for c in root.findall('.//class/counter'):
    for k in ['missed','covered']:cc[c.attrib['type']][k]+=int(c.attrib[k])
   for c in root.findall('.//sourcefile/counter'):
    if c.attrib['type']=='LINE':
     for k in line:line[k]+=int(c.attrib[k])
   assert line==rc['LINE'];coverage={'evidence':mref(archive,jp),'rootCounters':rc,'sumOfClassCounters':dict(cc),'sourcefileLineTotals':line,'sourcefileLineTotalsMatchRoot':True}
  job_reports.append({'jobId':j['id'],'job':job,'runId':run['id'],'attempt':1,'artifactId':artifact['id'],'artifact':artifact,'rawJUnitCounts':dict(counts),'rawJUnitFamilies':dict(families),'rawJUnitUniqueExactNodes':len(unique),'rawJUnitEvidence':ref(OUT/(job+'-RAW-NAMED-JUNIT.json')),'unitXmlIncluded':job=='backend-build','unuploadedUnitEvidenceBoundary':'Integration job executes clean verify including units; its artifact uploads failsafe only. Actual per-class and aggregate Maven unit results are preserved in the single corresponding step log; no unit XML nodes are invented.' if job=='backend-integration' else None,'actualMavenStep':log_summaries[job],'capacityMeasurements':measurements,'coverage':coverage})
write('ARCHIVE-MEMBER-INDEX.json',{'kind':'OFFICIAL_ACTIONS_ARTIFACT_AND_LOG_MEMBER_INDEX','sourceHead':HEAD,'testedMerge':MERGE,'runId':run['id'],'archives':archive_refs,'members':allmembers,'zipCrcAndAllMembersRead':True,'membersHaveUniqueNamesWithinArchive':True,'rawBytesChanged':False})
write('CI-SOURCE-INPUT-COMPARISON.json',{'kind':'CI_INPUTS_VS_SAVED_EXACT_7E_EXECUTION_BYTES','sourceHead':HEAD,'sourceTree':security['sourceTree'],'testedMerge':MERGE,'testedMergeTree':security['testedMergeTree'],'testedMergeParents':security['testedMergeParents'],'savedExecutedInventory':inv,'reusedPriorRemoteTreeComparison':ref(SEC/'SOURCE-IDENTITY-COMPARISON.json'),'comparisons':source_comparisons,'currentDirtyWorktreeUsedAsExecutedSource':False,'allActualCapacitySourceRowsMatch':True})
write('BACKEND-CI-RECONCILIATION.json',{'kind':'EXACT_BACKEND_CI_CHECKPOINT_RAW_RECONCILIATION_NOT_FINAL_CLOSURE','sourceHead':HEAD,'sourceTree':security['sourceTree'],'testedMerge':MERGE,'testedMergeTree':security['testedMergeTree'],'testedMergeParents':security['testedMergeParents'],'run':run,'originalSecurityReceipt':ref(SEC/'SECURITY-RECEIPT.json'),'workflowSource':ref(OUT/'backend-workflow-7e66cf8.yml'),'actualCheckoutLogs':checkouts,'jobReports':job_reports,'architectureJobLogEvidence':log_summaries['architecture-boundary'],'sourceInputComparison':ref(OUT/'CI-SOURCE-INPUT-COMPARISON.json'),'archiveMemberIndex':ref(OUT/'ARCHIVE-MEMBER-INDEX.json'),'downloadAttempts':len(downloads),'failedDownloadAttempts':sum(d['exitCode']!=0 for d in downloads),'checkpointDisposition':'PRE_SECTION_6_24_REPAIR_COMPLETE_BACKEND_CI_CHECKPOINT_NOT_FINAL_CLOSURE','productionWriteEnabled':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'publicationScan':'PENDING_SEPARATE_RAW_SCAN'})
for j in job_reports:print(json.dumps({'job':j['job'],'counts':j['rawJUnitCounts'],'families':j['rawJUnitFamilies'],'capacity':[{'kind':m['kind'],'p95':m['actualTargeted']['criticalP95Millis'],'max':m['actualTargeted']['maximumMillis'],'sweep':m['sweepWallMillis']} for m in j['capacityMeasurements']]}))
print(json.dumps({'sourceComparisonSets':len(source_comparisons),'filesPerSet':[x['recordedSourceFileCount'] for x in source_comparisons],'archiveMembers':len(allmembers),'testedMerge':MERGE}))
