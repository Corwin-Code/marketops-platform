from pathlib import Path
import collections,datetime,hashlib,json,zipfile,xml.etree.ElementTree as ET
P=Path(__file__).resolve().parent
HEAD='02e617278793b4458dfb7cac74c2a937f1dfcb00';TREE='8d8dd0b3429797a0e01b2ff91c1ba5c09de17dcc';MERGE='2b3e8e4b60f4d7a22afe1616f205e218d29a178c'
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p);return {'path':str(p.resolve()),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def load(n):return json.loads((P/n).read_bytes())
def write(n,x):
 p=P/n;assert not p.exists(),n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,indent=2)+'\n');return ref(p)
run=load('backend-run-r3.json');jobs=load('backend-jobs-r3.json')['jobs'];metadata=load('backend-artifacts-r2.json')['artifacts']
assert run['head_sha']==HEAD and run['status']=='completed' and run['conclusion']=='success' and run['run_attempt']==1
assert all(j['head_sha']==HEAD and j['run_id']==run['id'] and j['status']=='completed' and j['conclusion']=='success' for j in jobs)
inventory={r['path']:r['sha256'] for r in json.loads(Path('/tmp/slice3-source-inventory-02e6172.json').read_bytes())['files']}
summaries=[];indices=[];allnodes=[];sourcecomparisons=[]
for meta in metadata:
 filename=meta['name']+'.zip';path=P/filename
 assert sha(path.read_bytes())==meta['digest'].removeprefix('sha256:') and path.stat().st_size==meta['size_in_bytes']
 assert meta['workflow_run']['id']==run['id'] and meta['workflow_run']['head_sha']==HEAD
 rows=[];nodes=[];mismatch=[];capacity=[];coverage=None
 jobname={'backend-test-reports':'backend-build','backend-integration-reports':'backend-integration','backend-supply-chain':'backend-build'}[meta['name']]
 job=next(j for j in jobs if j['name']==jobname)
 with zipfile.ZipFile(path)as z:
  names=z.namelist();assert len(names)==len(set(names))
  for n in sorted(names):
   b=z.read(n);i=z.getinfo(n);member={'member':n,'bytes':len(b),'sha256':sha(b),'crc32':format(i.CRC,'08x')};rows.append(member)
   if '/TEST-' in n and ('/surefire-reports/' in n or '/failsafe-reports/' in n) and n.endswith('.xml'):
    xml=ET.fromstring(b);count=0
    for ix,t in enumerate(xml.iter('testcase')):
     status=next((k.upper() for k in ['failure','error','skipped'] if t.find(k)is not None),'PASSED')
     nodes.append({'archive':filename,'artifactId':meta['id'],'jobId':job['id'],'reportMember':n,'reportMemberSha256':sha(b),'nodeOrdinal':ix,
      'class':t.get('classname'),'name':t.get('name'),'status':status,'seconds':t.get('time'),'kind':'surefire' if '/surefire-reports/' in n else 'failsafe'})
     count+=1
    if xml.get('tests') and count!=int(xml.get('tests')):mismatch.append({'member':n,'declaredTests':int(xml.get('tests')),'actualTestcaseNodes':count,'boundary':'Actual leaves retained without silent correction or duplicate-name collapse.'})
   if n.endswith('/site/jacoco/jacoco.xml'):
    xml=ET.fromstring(b);coverage={'artifactMember':member,'aggregation':'JACOCO_REPORT_ROOT_COUNTERS','rootCounters':{c.get('type'):{'missed':int(c.get('missed')),'covered':int(c.get('covered'))} for c in xml.findall('counter')}}
   if '/target/advertising-' in n or n=='build/slice3-runtime-resources.json' or meta['name']=='backend-supply-chain':
    target=P/'backend-selected-originals'/meta['name']/n;assert not target.exists();target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(b)
  for prefix in ['advertising-capacity','advertising-mixed-capacity']:
   receiptname='backend/marketops-server/target/'+prefix+'-receipt.json'
   if receiptname not in names:continue
   r=json.loads(z.read(receiptname));identity=r['identities'];pi=identity['publicationIdentity']
   assert pi=={'sourceHeadSha':HEAD,'testedMergeSha':MERGE,'workflowRunId':str(run['id']),'workflowRunAttempt':'1','workflowJob':jobname,'artifactName':meta['name']}
   assert identity['measuredLocalGitHead']==MERGE and identity['ciIdentity']['GITHUB_SHA']==MERGE
   for field,suffix in [('dataset','dataset'),('sourceInputs','source-inputs')]:
    n='backend/marketops-server/target/'+prefix+'-'+suffix+'.json';assert sha(z.read(n))==identity[field+'Sha256']
   data=json.loads(z.read('backend/marketops-server/target/'+prefix+'-dataset.json'));assert data['datasetId']==identity['datasetId']
   src=json.loads(z.read('backend/marketops-server/target/'+prefix+'-source-inputs.json'));assert len(src)==len({v['path']for v in src})
   compare=[{'path':'backend/marketops-server/'+v['path'],'sha256':v['sha256'],'matchesExactInventory':inventory.get('backend/marketops-server/'+v['path'])==v['sha256']} for v in src];assert all(v['matchesExactInventory']for v in compare)
   sourcecomparisons.append({'artifactId':meta['id'],'jobId':job['id'],'kind':prefix,'sourceInputsSha256':identity['sourceInputsSha256'],'fileCount':len(compare),'files':compare})
   resources=json.loads(z.read('build/slice3-runtime-resources.json'));assert sha(z.read('build/slice3-runtime-resources.json'))==identity['runtimeResourceReceiptSha256']
   assert r['productionWriteEnabled'] is False and r['realProviderAccess'] is False
   t=r['targeted'];bounds=t['criticalP95Millis']<=300000 and t['maximumMillis']<=900000 and r['sweepWallMillis']<1800000 and all(t[k]==0 for k in ['hardBreachCount','clockDefectCount','pendingRequests','failedRequests']);assert bounds
   summary={k:r[k] for k in ['dataset','targeted','targetedWallMillis','sweepWallMillis','hourlyMarginMillis','runtime','productionWriteEnabled','realProviderAccess']}
   summary.update(kind=prefix,receipt=ref(P/'backend-selected-originals'/meta['name']/receiptname),identities=identity,resourceReceipt=resources,withinUnchangedThresholds=True)
   if 'mixed' in prefix:
    for key in ['setupOutcomeStages','afterTargetedOutcomeStages','afterTargetedRetainedVerdicts','criticalSalesGuardEvidenceAfterTargeted','afterSweepOutcomeStages','latestRetainedVerdicts','exceptionStates','expiredAuthorizations','expiredAuthorizationsAlreadyInvalidated','newAuthorizationExpiryJournals','heldReservations','releasedHistoricalReservations','droppedLateCorrectionObjectsRecovered','sweepRepairedRequestRows','postgresContainerResources']:summary[key]=r[key]
    assert len(r['historicalCommandWriteGateReasons'])==40 and all({'SEALED_AUTHORIZATION_MISSING_OR_EXPIRED','AUTHORITY_PERMANENTLY_INVALIDATED'}<=set(x)for x in r['historicalCommandWriteGateReasons'].values())
    assert 'GLOBAL_SWITCH_DISABLED'in r['currentCommandWriteGateReasons']
    summary['historicalGateRefusalEntriesVerified']=40;summary['currentGlobalSwitchRefusalVerified']=True
    summary['diagnosticUploaded']=any(x.endswith('/advertising-mixed-capacity-diagnostic.json')for x in names)
    summary['diagnosticBoundary']='Official workflow uploads receipt/dataset/source-inputs only. Do not claim the fourth local diagnostic member exists in CI or substitute these files for the local four-file layer.'
   capacity.append(summary)
 counts={kind:dict(collections.Counter(n['status']for n in nodes if n['kind']==kind))for kind in ['surefire','failsafe']}
 assert all(n['status']=='PASSED'for n in nodes)
 duplicates=[list(k)+[v]for k,v in collections.Counter((n['reportMember'],n['class'],n['name'])for n in nodes).items()if v>1]
 assert not duplicates
 indices.append({'officialMetadata':meta,'archive':ref(path),'allMemberShaAndCrcVerifiedByRead':True,'members':rows})
 summaries.append({'artifactId':meta['id'],'artifactName':meta['name'],'jobId':job['id'],'jobName':jobname,'sourceHead':HEAD,'testedMerge':MERGE,'countsActualUniqueWithinJob':counts,'declaredVsActualMismatches':mismatch,'duplicateExactNodes':duplicates,'capacityMeasurements':capacity,'coverage':coverage})
 allnodes+=nodes
logrows=[];checkouts=[]
with zipfile.ZipFile(P/'backend-logs.zip')as z:
 for n in sorted(z.namelist()):
  b=z.read(n);i=z.getinfo(n);logrows.append({'member':n,'bytes':len(b),'sha256':sha(b),'crc32':format(i.CRC,'08x')})
  if '/2_Checkout' in n:
   lines=[{'line':ix+1,'text':v}for ix,v in enumerate(b.decode().splitlines())if MERGE in v or 'HEAD is now at '+MERGE[:7]+' Merge '+HEAD in v];assert lines
   checkouts.append({'member':n,'sha256':sha(b),'lines':lines,'testedMerge':MERGE})
assert len(checkouts)==3
nodesref=write('BACKEND-NAMED-TESTCASE-NODES.json',{'sourceHead':HEAD,'testedMerge':MERGE,'nodes':allnodes,'scope':'Exact original actual XML leaves counted separately per job; cross-job executions are not one unique product test total.'})
sourceref=write('BACKEND-SOURCE-INPUT-COMPARISON.json',{'sourceHead':HEAD,'sourceTree':TREE,'inventorySha256':'9706e844337851329c5e30954598162ac52144c82facaf736b04475c25f0c6ec','fourInventories':sourcecomparisons,'allExactInventorySourceBytesMatch':True})
indexref=write('BACKEND-ARTIFACT-MEMBER-INDEX.json',{'sourceHead':HEAD,'testedMerge':MERGE,'artifacts':indices,'rawLogs':{'archive':ref(P/'backend-logs.zip'),'allMemberShaAndCrcVerifiedByRead':True,'members':logrows}})
receipt={'kind':'EXACT_COMPLETED_CHECKPOINT_BACKEND_CI_RAW_VERIFICATION','sourceHead':HEAD,'sourceTree':TREE,'testedMerge':MERGE,'run':{k:run[k]for k in ['id','head_sha','status','conclusion','run_attempt','html_url']},'jobs':[{k:j[k]for k in ['id','name','status','conclusion','started_at','completed_at','html_url']}for j in jobs],
 'rawRun':ref(P/'backend-run-r3.json'),'rawJobs':ref(P/'backend-jobs-r3.json'),'rawArtifactApi':ref(P/'backend-artifacts-r2.json'),'rawIndex':indexref,'actualCheckouts':checkouts,'jobResults':summaries,'namedTestcaseNodes':nodesref,'sourceInputComparison':sourceref,
 'sourceBinding':ref(Path('/tmp/slice3-checkpoint-ci-02e6172/SOURCE-IDENTITY-COMPARISON.json')),'securityReceipt':ref(Path('/tmp/slice3-checkpoint-ci-02e6172/SECURITY-RECEIPT.json')),
 'publicationReview':'NOT_YET_SCANNED_NEW_BACKEND_RAW; no reuse of Security scan','productExecutionPerformedLocallyByThisDecoder':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'productionWriteEnabled':False,
 'limits':['This is exact checkpoint02e remote CI, separate from still running local final verification and future evidence-commit CI.', 'No CI diagnostic file or executable JAR was uploaded in these three artifacts; supply artifact contains SBOM/dependency inventory. Actual packaged verification remains in successful raw job logs.', 'Counts use actual testcase leaves with class and original ordinal. Different nested classes sharing a method name are distinct. Preserve any raw suite declared-count discrepancy.']}
receiptref=write('BACKEND-CI-CHECKPOINT-RECEIPT.json',receipt)
print(json.dumps({'receipt':receiptref,'jobs':[{'job':s['jobName'],'counts':s['countsActualUniqueWithinJob'],'mismatches':s['declaredVsActualMismatches'],'capacity':[(c['kind'],c['targeted']['criticalP95Millis'],c['targeted']['maximumMillis'],c['sweepWallMillis'])for c in s['capacityMeasurements']]}for s in summaries]}))
