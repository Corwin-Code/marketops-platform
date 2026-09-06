#!/usr/bin/env python3
"""Parameter adaptation of preserved original decoder; final source is supplied by actual capture."""
from pathlib import Path
import argparse,collections,datetime,hashlib,json,sys,time,zipfile,xml.etree.ElementTree as ET
from capture_ci import checked_zip
if not __debug__: raise RuntimeError('Optimized Python disables required evidence checks')
ap=argparse.ArgumentParser(description="Decode preserved exact final-head backend CI raw; performs no network or product execution.")
ap.add_argument("--capture-dir",type=Path,required=True)
a=ap.parse_args()
P=a.capture_dir.resolve()
decoderStartedAt=datetime.datetime.now(datetime.timezone.utc).isoformat();decoderClock=time.monotonic()
capture=json.loads((P/'CI-CAPTURE-RECEIPT.json').read_bytes())
assert capture['result']=='CAPTURE_COMPLETE_REQUIRES_BACKEND_AND_SECURITY_REVIEW' and capture['exitCode']==0
HEAD=capture['sourceHead'];TREE=capture['sourceTree'];MERGE=capture['testedMerge']
assert capture['allRequiredAndAggregateSucceeded'] and capture['testedMergeTree']==TREE and capture['testedMergeParents']==[capture['baseHead'],HEAD]
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p);return {'path':str(p.resolve()),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def load(n):return json.loads((P/n).read_bytes())
def write(n,x):
 p=P/n;assert not p.exists(),n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,indent=2)+'\n');return ref(p)
# Every decoder input remains tied to the actual capture/GET receipt before use.
attemptGroups=collections.defaultdict(list);attemptObservations=[]
for cr in capture['commandReceipts']:
 assert ref(cr['path'])==cr
 command=json.loads(Path(cr['path']).read_bytes())
 assert type(command['exitCode'])is int and ref(command['response']['path'])==command['response'] and ref(command['stderr']['path'])==command['stderr']
 assert command['argv'][:4]==['gh','api','--method','GET'] and command['argv'][4].startswith('repos/Corwin-Code/marketops-platform/')
 assert type(command['attempt'])is int and 1<=command['attempt']<=3
 suffix='.attempt-'+str(command['attempt']);responsePath=Path(command['response']['path']);assert responsePath.name.endswith(suffix)
 logicalTarget=str(responsePath.with_name(responsePath.name[:-len(suffix)]))
 attemptGroups[(tuple(command['argv']),logicalTarget)].append((cr,command))
for argv,group in attemptGroups.items():
 assert [v['attempt']for _,v in group]==list(range(1,len(group)+1))
 successes=[(r,c)for r,c in group if c['exitCode']==0];assert len(successes)==1 and successes[0]==group[-1]
 sr,sc=successes[0];response=Path(sc['response']['path']);suffix='.attempt-'+str(sc['attempt']);assert response.name.endswith(suffix)
 canonical=response.with_name(response.name[:-len(suffix)]);canonicalCommand=json.loads(canonical.with_name(canonical.name+'.command.json').read_bytes())
 assert canonicalCommand['selectedOriginalAttempt']==sr and canonicalCommand['canonicalResponse']==ref(canonical) and canonical.read_bytes()==response.read_bytes()
 for r,c in group:
  if c['exitCode']!=0:assert c['exitCode']==124 or b'EOF'in Path(c['stderr']['path']).read_bytes()
  attemptObservations.append({'attemptReceipt':r,'originalExitCode':c['exitCode'],'usedAsSuccessfulResponse':c['exitCode']==0,'successfulCanonicalResponse':ref(canonical),'laterExactGetSuccess':sr if c['exitCode']!=0 else None})
assert ref(capture['sourceBinding']['path'])==capture['sourceBinding']
assert ref(capture['artifactIndex']['path'])==capture['artifactIndex']
assert ref(capture['logIndex']['path'])==capture['logIndex']
run=load('backend-run.json');jobs=load('backend-jobs.json')['jobs']
artifact_index=load('OFFICIAL-ARTIFACT-MEMBER-INDEX.json')['artifacts']
metadata=[r['officialMetadata'] for r in artifact_index if r['workflow']=='Backend']
assert len(metadata)==3 and {r['name'] for r in metadata}=={'backend-test-reports','backend-integration-reports','backend-supply-chain'}
assert run['head_sha']==HEAD and run['status']=='completed' and run['conclusion']=='success' and isinstance(run['run_attempt'],int) and run['run_attempt']>=1
assert all(j['head_sha']==HEAD and j['run_id']==run['id'] and j['run_attempt']==run['run_attempt'] and j['status']=='completed' and j['conclusion']=='success' for j in jobs)
inventory_ref=capture['inputFiles']['sourceInventory'];assert ref(inventory_ref['path'])==inventory_ref
inventory={r['path']:r['sha256'] for r in json.loads(Path(inventory_ref['path']).read_bytes())['files']}
assert len(inventory)==capture['sourceInventoryFileCount'] and inventory_ref['sha256']==capture['sourceInventorySha256']
summaries=[];indices=[];allnodes=[];sourcecomparisons=[]
decoderInputs={'decoder':ref(__file__),'captureHelper':ref(Path(__file__).with_name('capture_ci.py')),'actualCapture':ref(P/'CI-CAPTURE-RECEIPT.json')}
for meta in metadata:
 filename=meta['name']+'.zip';path=P/filename
 assert sha(path.read_bytes())==meta['digest'].removeprefix('sha256:') and path.stat().st_size==meta['size_in_bytes']
 assert meta['workflow_run']['id']==run['id'] and meta['workflow_run']['head_sha']==HEAD
 rows=[];nodes=[];mismatch=[];capacity=[];coverage=None
 jobname={'backend-test-reports':'backend-build','backend-integration-reports':'backend-integration','backend-supply-chain':'backend-build'}[meta['name']]
 job=next(j for j in jobs if j['name']==jobname)
 binding=next(r for r in artifact_index if r['officialMetadata']['id']==meta['id']);assert binding['jobId']==job['id'] and binding['attempt']==run['run_attempt'] and binding['runId']==run['id'] and binding['archive']==ref(path)
 with checked_zip(path)as z:
  names=z.namelist();assert len(names)==len(set(names))
  for n in sorted(names):
   b=z.read(n);i=z.getinfo(n);member={'member':n,'bytes':len(b),'sha256':sha(b),'crc32':format(i.CRC,'08x')};rows.append(member)
   if '/TEST-' in n and ('/surefire-reports/' in n or '/failsafe-reports/' in n) and n.endswith('.xml'):
    xml=ET.fromstring(b);count=0
    for ix,t in enumerate(xml.iter('testcase')):
     status=next((k.upper() for k in ['failure','error','skipped'] if t.find(k)is not None),'PASSED')
     nodes.append({'archive':filename,'artifactId':meta['id'],'jobId':job['id'],'reportMember':n,'reportMemberSha256':sha(b),'nodeOrdinal':ix,
      'suite':xml.get('name'),'class':t.get('classname'),'name':t.get('name'),'status':status,'seconds':t.get('time'),'kind':'surefire' if '/surefire-reports/' in n else 'failsafe'})
     count+=1
    if xml.get('tests') and count!=int(xml.get('tests')):mismatch.append({'member':n,'declaredTests':int(xml.get('tests')),'actualTestcaseNodes':count,'boundary':'Actual leaves retained without silent correction or duplicate-name collapse.'})
   if n.endswith('/site/jacoco/jacoco.xml'):
    assert coverage is None,'Multiple root JaCoCo reports are ambiguous'
    xml=ET.fromstring(b);coverage={'artifactMember':member,'aggregation':'JACOCO_REPORT_ROOT_COUNTERS','rootCounters':{c.get('type'):{'missed':int(c.get('missed')),'covered':int(c.get('covered'))} for c in xml.findall('counter')}}
   if '/target/advertising-' in n or n=='build/slice3-runtime-resources.json' or meta['name']=='backend-supply-chain':
    target=P/'backend-selected-originals'/meta['name']/n;assert not target.exists();target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(b)
  for prefix in ['advertising-capacity','advertising-mixed-capacity']:
   receiptname='backend/marketops-server/target/'+prefix+'-receipt.json'
   if receiptname not in names:continue
   r=json.loads(z.read(receiptname));identity=r['identities'];pi=identity['publicationIdentity']
   assert pi=={'sourceHeadSha':HEAD,'testedMergeSha':MERGE,'workflowRunId':str(run['id']),'workflowRunAttempt':str(run['run_attempt']),'workflowJob':jobname,'artifactName':meta['name']}
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
with checked_zip(P/'backend-logs.zip')as z:
 for n in sorted(z.namelist()):
  b=z.read(n);i=z.getinfo(n);logrows.append({'member':n,'bytes':len(b),'sha256':sha(b),'crc32':format(i.CRC,'08x')})
  if '/2_Checkout' in n:
   lines=[{'line':ix+1,'text':v}for ix,v in enumerate(b.decode().splitlines())if MERGE in v or 'HEAD is now at '+MERGE[:7]+' Merge '+HEAD in v];assert lines
   checkouts.append({'member':n,'sha256':sha(b),'lines':lines,'testedMerge':MERGE})
assert len(checkouts)==3
assert len(sourcecomparisons)==4 and len({(x['jobId'],x['kind']) for x in sourcecomparisons})==4
for jobname in ['backend-build','backend-integration']:
 js=next(s for s in summaries if s['jobName']==jobname and s['artifactName']!='backend-supply-chain')
 assert {m['kind'] for m in js['capacityMeasurements']}=={'advertising-capacity','advertising-mixed-capacity'}
coverage_summary=next(s for s in summaries if s['artifactName']=='backend-test-reports')['coverage']
assert coverage_summary is not None
for kind,minimum in [('LINE',.80),('BRANCH',.70)]:
 c=coverage_summary['rootCounters'][kind];assert c['covered']+c['missed']>0 and c['covered']/(c['covered']+c['missed'])>=minimum
coverage_summary['unchangedPomThresholds']={'LINE':.80,'BRANCH':.70}
nodesref=write('BACKEND-NAMED-TESTCASE-NODES.json',{'sourceHead':HEAD,'testedMerge':MERGE,'nodes':allnodes,'scope':'Exact original actual XML leaves counted separately per job; cross-job executions are not one unique product test total.'})
sourceref=write('BACKEND-SOURCE-INPUT-COMPARISON.json',{'sourceHead':HEAD,'sourceTree':TREE,'inventorySha256':capture['sourceInventorySha256'],'fourInventories':sourcecomparisons,'allExactInventorySourceBytesMatch':True})
indexref=write('BACKEND-ARTIFACT-MEMBER-INDEX.json',{'sourceHead':HEAD,'testedMerge':MERGE,'artifacts':indices,'rawLogs':{'archive':ref(P/'backend-logs.zip'),'allMemberShaAndCrcVerifiedByRead':True,'members':logrows}})
receipt={'kind':'EXACT_FINAL_CONTAINING_BACKEND_CI_RAW_VERIFICATION','sourceHead':HEAD,'sourceTree':TREE,'testedMerge':MERGE,'run':{k:run[k]for k in ['id','head_sha','status','conclusion','run_attempt','html_url']},'jobs':[{k:j[k]for k in ['id','name','status','conclusion','started_at','completed_at','html_url']}for j in jobs],
 'rawRun':ref(P/'backend-run.json'),'rawJobs':ref(P/'backend-jobs.json'),'rawArtifactApi':ref(P/'backend-artifacts.json'),'rawIndex':indexref,'actualCheckouts':checkouts,'jobResults':summaries,'namedTestcaseNodes':nodesref,'sourceInputComparison':sourceref,
 'originalGetAttemptObservations':attemptObservations,'sourceBinding':capture['sourceBinding'],'captureReceipt':ref(P/'CI-CAPTURE-RECEIPT.json'),'securityReceipt':None,'securityScope':'Separate exact-head Security review still required; the capture only records its status/run/attempt/job metadata.',
 'publicationReview':'NOT_YET_SCANNED_NEW_BACKEND_RAW; no reuse of Security scan','productExecutionPerformedLocallyByThisDecoder':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'productionWriteEnabled':False,'decoderInputs':decoderInputs,'proofsCreated':0,'argv':[sys.executable,*sys.argv],'startedAt':decoderStartedAt,'finishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsedSeconds':round(time.monotonic()-decoderClock,3),'exitCode':0,
 'limits':['This is exact actual containing-head remote CI. The explicitly bound product local runs retain their original identity and are not rerun or reassigned by this decoder.', 'No CI diagnostic file or executable JAR was uploaded in these three artifacts; supply artifact contains SBOM/dependency inventory. Actual packaged verification remains in successful raw job logs.', 'Counts use actual testcase leaves with class and original ordinal. Different nested classes sharing a method name are distinct. Preserve any raw suite declared-count discrepancy.']}
receiptref=write('BACKEND-CI-CONTAINING-RECEIPT.json',receipt)
print(json.dumps({'receipt':receiptref,'jobs':[{'job':s['jobName'],'counts':s['countsActualUniqueWithinJob'],'mismatches':s['declaredVsActualMismatches'],'capacity':[(c['kind'],c['targeted']['criticalP95Millis'],c['targeted']['maximumMillis'],c['sweepWallMillis'])for c in s['capacityMeasurements']]}for s in summaries]}))
