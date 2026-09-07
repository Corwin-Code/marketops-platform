from pathlib import Path
import collections,datetime,hashlib,json,re,zipfile
OUT=Path(__file__).resolve().parent;SEC=Path('/tmp/slice3-checkpoint-ci-02e6172')
HEAD='02e617278793b4458dfb7cac74c2a937f1dfcb00';TREE='8d8dd0b3429797a0e01b2ff91c1ba5c09de17dcc';MERGE='2b3e8e4b60f4d7a22afe1616f205e218d29a178c';BASE='08ad7da7d9e75b4ddd1c387a22ac0affba9e1430'
sha=lambda b:hashlib.sha256(b).hexdigest()
def load(n):return json.loads((OUT/n).read_bytes())
def ref(n):p=Path(n);p=p if p.is_absolute()else OUT/p;return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def write(n,o):(OUT/n).write_text(json.dumps(o,indent=2)+'\n')
security=json.loads((SEC/'SECURITY-RECEIPT.json').read_bytes());assert security['sourceHead']==HEAD and security['sourceTree']==TREE and security['testedMerge']==MERGE and security['testedMergeParents']==[BASE,HEAD]
checks=load('check-runs-r1.json')['check_runs'];runs=load('workflow-runs-r1.json')['workflow_runs'];pr=load('pr30-r1.json');assert pr['head']['sha']==HEAD and pr['base']['sha']==BASE and pr['merge_commit_sha']==MERGE and pr['draft']
required=['backend-build','backend-integration','architecture-boundary','frontend-lint','frontend-typecheck','frontend-test','frontend-build','governance','infrastructure-validation','dependency-review','codeql-java','codeql-typescript']
assert {x['name']for x in checks}==set(required+['CodeQL']) and len(checks)==13 and all(x['head_sha']==HEAD for x in checks)
workflow_meta=[];job_map={}
for key,name in [('frontend','Frontend'),('governance','Governance'),('infrastructure','Infrastructure'),('backend','Backend')]:
 run=next(x for x in runs if x['name']==name);assert run['head_sha']==HEAD and run['run_attempt']==1
 jobs=load(key+'-jobs-r1.json')['jobs'];assert all(x['head_sha']==HEAD and x['run_id']==run['id'] and x['run_attempt']==1 for x in jobs)
 for job in jobs:
  check=next(x for x in checks if x['id']==job['id']);assert check['name']==job['name'];job_map[job['id']]={'runId':run['id'],'attempt':1,'workflow':name}
 workflow_meta.append({'workflow':name,'run':{k:run[k]for k in ['id','run_attempt','run_number','head_sha','status','conclusion','html_url','created_at','updated_at']},'jobs':[{k:j[k]for k in ['id','name','run_id','run_attempt','head_sha','status','conclusion','started_at','completed_at','html_url']}for j in jobs],'rawJobs':ref(key+'-jobs-r1.json'),'rawArtifacts':ref(key+'-artifacts-r1.json'),'rawLogs':ref(key+'-logs.zip')if key!='backend'else None})
for job in security['jobs']:job_map[job['id']]={'runId':security['run']['id'],'attempt':1,'workflow':'Security'}
ansi=re.compile(r'\x1b\[[0-9;]*m');logindexes=[];checkout=[];summaries={};rawlines={}
for key in ['frontend','governance','infrastructure']:
 with zipfile.ZipFile(OUT/(key+'-logs.zip'))as z:
  entries=[]
  for name in sorted(z.namelist()):
   raw=z.read(name);i=z.getinfo(name);entries.append({'member':name,'bytes':len(raw),'sha256':sha(raw),'crc32':format(i.CRC,'08x')})
   if '/2_Checkout' in name:
    lines=raw.decode().splitlines();proof=[{'line':n+1,'text':l}for n,l in enumerate(lines)if MERGE in l or 'HEAD is now at '+MERGE[:7]+' Merge '+HEAD in l];assert proof;checkout.append({'jobName':name.split('/')[0],'actualCheckout':MERGE,'sourceHead':HEAD,'testedTree':TREE,'archive':ref(key+'-logs.zip'),'member':name,'memberSha256':sha(raw),'lines':proof})
   if '/'in name:rawlines[name]=[ansi.sub('',l)for l in raw.decode('utf-8','replace').splitlines()]
  logindexes.append({'archive':ref(key+'-logs.zip'),'allMemberShaAndCrcVerifiedByRead':True,'memberCount':len(entries),'members':entries})
assert len(checkout)==6
# Assertions below are bounded log summaries, not synthetic JUnit or new test nodes.
def proof(name,pattern):
 lines=rawlines[name];got=[{'line':n+1,'text':l}for n,l in enumerate(lines)if re.search(pattern,l)];assert got,(name,pattern);return {'member':name,'lines':got}
frontend={'unitSourceFiles':22,'unitTests':337,'unitEvidence':[proof('frontend-test/6_Run the tests with coverage.txt',r'Test Files\s+22 passed'),proof('frontend-test/6_Run the tests with coverage.txt',r'Tests\s+337 passed')],'coverageNegative':proof('frontend-test/7_Prove the frontend coverage gate rejects an unmet threshold.txt',r'coverage-negative: frontend threshold enforcement PASS'),'bundleIsolation':proof('frontend-build/5_Prove that only prefixed values reached the bundle.txt',r'bundle isolation PASS'),'browserLegacyTests':25,'browserGovernedTests':12,'browserTotalTests':37,'browserEvidence':[proof('frontend-test/11_Exercise authenticated business flow and dependency recovery in Chromium.txt',r'25 passed'),proof('frontend-test/13_Exercise governed advertising roles in a fresh isolated database.txt',r'12 passed')],'countsScope':'Actual distinct completed workflow steps; whole-job rollup logs are retained but not counted again. No named JUnit is synthesized from log totals.'}
governance={'validatorTests':421,'evidence':proof("governance/6_Run the validator's own tests.txt",r'Ran 421 tests|\bOK$'),'scope':'Official governance workflow validator suite only; local extra22 binder/14 assembler and other derivation invocations are separate.'}
infra={'networkAndSecretRefusalTests':9,'ephemeralSecretDeliveryTests':13,'evidence':[proof('infrastructure-validation/5_Test network and secret-delivery refusal controls.txt',r'Ran 9 tests|\bOK$'),proof('infrastructure-validation/6_Test ephemeral runtime secret delivery.txt',r'Ran 13 tests|\bOK$')]}
artifacts=[]
for key in ['frontend','infrastructure']:
 run=next(x for x in workflow_meta if x['workflow'].lower()==key)['run']
 for item in load(key+'-artifacts-r1.json')['artifacts']:
  filename=item['name']+'-r2.zip';path=OUT/filename;assert sha(path.read_bytes())==item['digest'].removeprefix('sha256:') and path.stat().st_size==item['size_in_bytes'];assert item['workflow_run']['id']==run['id'] and item['workflow_run']['head_sha']==HEAD
  entries=[]
  with zipfile.ZipFile(path)as z:
   for name in sorted(z.namelist()):
    raw=z.read(name);zi=z.getinfo(name);entries.append({'member':name,'bytes':len(raw),'sha256':sha(raw),'crc32':format(zi.CRC,'08x')})
    if filename=='infrastructure-verification-r2.zip'and name=='summary.json':infra['actualMockPlanSummary']=json.loads(raw)
  artifacts.append({'id':item['id'],'name':item['name'],'officialDigest':item['digest'],'officialBytes':item['size_in_bytes'],'createdAt':item['created_at'],'expiresAt':item['expires_at'],'workflowRun':item['workflow_run'],'archive':ref(path),'officialDigestAndSizeMatch':True,'memberShaAndCrcVerifiedByRead':True,'memberCount':len(entries),'members':entries})
assert infra['actualMockPlanSummary']['real_provider_api_calls']=='NONE_MOCK_PROVIDER' and all(p['apply']=='NOT_EXECUTED'and p['external_verification']=='NOT_PERFORMED'for p in infra['actualMockPlanSummary']['plans'])
finished_failures=[x for x in checks if x['status']=='completed'and x['conclusion']!='success'];assert finished_failures==[]
checkrows=[]
for check in checks:
 row={k:check.get(k)for k in ['id','name','head_sha','status','conclusion','started_at','completed_at','details_url']};row['requiredContext']=check['name']in required;row['aggregateExtra']=check['name']=='CodeQL';row.update(job_map.get(check['id'],{'workflow':'CodeQL aggregate','runId':security['run']['id'],'attempt':security['run']['run_attempt']}));checkrows.append(row)
receipt={'kind':'EXACT_CURRENT_CHECKPOINT_ALL_CI_STATUS_SNAPSHOT','capturedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceHead':HEAD,'sourceTree':TREE,'baseHead':BASE,'testedMerge':MERGE,'testedMergeParents':[BASE,HEAD],'testedMergeTree':TREE,'pr':{'number':30,'draft':True,'raw':ref('pr30-r1.json')},'sourceBinding':ref(SEC/'SOURCE-IDENTITY-COMPARISON.json'),'sourceInventorySha256':security['sourceInventorySha256'],'securityReceipt':ref(SEC/'SECURITY-RECEIPT.json'),'rawWorkflowRuns':ref('workflow-runs-r1.json'),'rawCheckRuns':ref('check-runs-r1.json'),'requiredContextCount':12,'extraAggregateCodeQLCount':1,'contexts':checkrows,'requiredSucceeded':sum(x['requiredContext']and x['conclusion']=='success'for x in checkrows),'requiredPending':[x['name']for x in checkrows if x['requiredContext']and x['status']!='completed'],'requiredFailed':[x['name']for x in checkrows if x['requiredContext']and x['status']=='completed'and x['conclusion']!='success'],'allRequiredAndAggregateSuccess':all(x['status']=='completed'and x['conclusion']=='success'for x in checkrows),'workflowEvidence':workflow_meta,'actualCompletedCheckoutProofs':checkout,'completedScopeSummaries':{'frontend':frontend,'governance':governance,'infrastructure':infra},'officialDownloadedArtifactCount':len(artifacts),'artifactIndex':'OFFICIAL-ARTIFACT-MEMBER-INDEX.json','backend':{'statusAtCapture':'in_progress','runId':34008254541,'buildJobId':101419354463,'integrationJobId':101419354593,'architectureJobId':101419354623,'officialArtifactCountAtCapture':0,'fullResultClaimed':False,'rawJobs':ref('backend-jobs-r1.json'),'rawArtifacts':ref('backend-artifacts-r1.json')},'publicationReview':'NOT_PERFORMED_FOR_THESE_NEW_RAW_LOG_AND_ARTIFACT_PAYLOADS; original Security payload has its own separate completed scan. No publication safety claim for screenshots or infra raw plans.','transportFailuresPreserved':[x+'.zip'for x in ['frontend-coverage','advertising-browser-screenshots','frontend-distribution','infrastructure-verification']],'productionWriteEnabled':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'limits':['Backend build/integration remain ongoing; no completed full backend or capacity result is assigned.','All other13-context statuses are actual source-qualified records. Successful CI does not replace local9-layer verification or independent Controller.','Browser counts25+12 and frontend337 derive actual distinct log steps; original official artifacts contain no new fabricated JUnit.','Infrastructure is synthetic mock planning, no account/provider access or apply/deployment.','Four initial artifact downloads returned EOF; original zero-byte files and command/stderr remain unchanged, successful r2 digests match official metadata.','No alerts, repository source or GitHub state were changed.']}
write('OFFICIAL-ARTIFACT-MEMBER-INDEX.json',{'sourceHead':HEAD,'testedMerge':MERGE,'artifacts':artifacts});write('RAW-LOG-MEMBER-INDEX.json',{'sourceHead':HEAD,'testedMerge':MERGE,'archives':logindexes});write('CI-CHECKPOINT-SNAPSHOT-r1.json',receipt)
print(json.dumps({'requiredSucceeded':receipt['requiredSucceeded'],'requiredPending':receipt['requiredPending'],'aggregateCodeQL':'success','frontendUnitTests':337,'browserTests':37,'governanceTests':421,'officialArtifacts':len(artifacts),'allFullCiSucceeded':receipt['allRequiredAndAggregateSuccess'],'receipt':ref('CI-CHECKPOINT-SNAPSHOT-r1.json')}))
