#!/usr/bin/env python3
"""Explicit execution-input checks and separate derivation receipts; never close a gate."""
import argparse,datetime,hashlib,importlib.util,json,os,pathlib,subprocess,sys,time
P=pathlib.Path
GATE='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1'
BRANCH='feat/SLICE-V1-003-advertising-traffic-efficiency'
def sha(p):return hashlib.sha256(P(p).read_bytes()).hexdigest()
def ref(p):p=P(p).resolve();return {'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size}
def write(p,x):p=P(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
def read(p):return json.loads(P(p).read_bytes())
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def require(ok,msg):
 if not ok:raise ValueError(msg)
def git(c,*args):return subprocess.check_output(['git','--no-replace-objects',*args],cwd=c['repo']).decode().strip()
def load_collector(c):
 p=P(c['repo'])/GATE/'collect_execution.py';s=importlib.util.spec_from_file_location('bound_collector',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

def exact_source(c,with_inventory=True):
 require(git(c,'rev-parse','HEAD')==c['head'],'Wrong HEAD')
 require(git(c,'rev-parse','HEAD^{tree}')==c['tree'],'Wrong tree')
 require(git(c,'branch','--show-current')==BRANCH,'Outside named branch')
 require(not git(c,'status','--porcelain=v1','--untracked-files=all'),'Dirty source refused')
 p=P(c['inventory']['path']);require(sha(p)==c['inventory']['sha256'],'Inventory bytes changed')
 if with_inventory:
  raw=(json.dumps(load_collector(c).inventory(),ensure_ascii=False,indent=2)+'\n').encode()
  require(hashlib.sha256(raw).hexdigest()==c['inventory']['sha256'],'Complete source inventory differs')
 return {'sourceHead':c['head'],'sourceTree':c['tree'],'sourceInventorySha256':c['inventory']['sha256'],'workingTreeDirty':False}

def inputs(c):
 index=read(P(c['driverDirectory'])/'EXECUTION-INPUTS.json')
 for row in index['files']:require(sha(row['path'])==row['sha256'],'Execution input changed: '+row['path'])
 return index

def backend(c):
 p=P(c['runs'])/'backend-full/layer-candidate.json';x=read(p)
 require(x.get('id')=='backend_full' and x.get('sourceHead')==c['head'] and x.get('sourceTree')==c['tree'] and x.get('sourceInventorySha256')==c['inventory']['sha256'],'Backend identity differs')
 require(x.get('result')=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and x.get('exitCode')==0 and x.get('sourceStable') is True and x.get('finishedAt'),'Backend command not successful/stable/completed')
 require(x.get('testcaseCounts',{}).get('passed',0)>0 and all(x.get('testcaseCounts',{}).get(k)==0 for k in ('failures','errors','skipped')),'Backend named tests are incomplete')
 return p,x

def registered_report(parent,row):
 matches=[r for r in parent['evidence'] if r.get('path')==row['path']]
 require(len(matches)==1 and matches[0].get('sha256')==row['sha256'],'Raw report is not an exact unique member of the parent execution receipt')

def products(c,destination):
 p,x=backend(c);rows=read(p.parent/'preserved-reports.json');selected=[]
 for row in rows:
  name=row['originalPath']
  if name.startswith('backend/marketops-server/target/') and (name.endswith('.jar') or name.endswith('-sbom.json') or name.endswith('/licenses/backend-license-inventory.txt') or name.endswith('/classes/META-INF/build-info.properties')):
   require(row.get('writtenDuringCommand') is True,'Preexisting backend output cannot supply a downstream layer')
   registered_report(x,row)
   actual=P(c['repo'])/name;require(sha(row['path'])==row['sha256'] and sha(actual)==row['sha256'],'Verified product changed or original raw changed: '+name)
   selected.append({'originalPath':name,'backendRaw':ref(row['path']),'currentFile':ref(actual)})
 require(sum(r['originalPath'].endswith('.jar') for r in selected)==1,'Exactly one same verified JAR required')
 require(any(r['originalPath'].endswith('-sbom.json') for r in selected),'Same full-run backend SBOM required')
 require(any(r['originalPath'].endswith('/licenses/backend-license-inventory.txt') for r in selected),'Same full-run license inventory required')
 value={'kind':'SAME_FULL_BACKEND_OUTPUT_BINDING','backendExecution':ref(p),**exact_source(c,False),'files':selected,'newBuildPerformed':False,'gateAssessment':'NOT_PERFORMED'};write(destination,value)


def binder(c):
 out=P(c['runs'])/c['binderOutputName'];out.mkdir(exist_ok=False);started=now();t=time.monotonic();code=None
 tool=P(c['repo'])/GATE/'assessment_tools';hook=P(c['binderObserverDirectory'])
 observer_before=[ref(hook/n) for n in ('sitecustomize.py','slice3_unittest_capture.py')]
 observer_binding=c['binderObserverSourceBinding']
 require(sha(observer_binding['copiedInput']['path'])==sha(observer_binding['repositoryInput']['path'])==observer_binding['gitBlobSha256'],'Unchanged unittest observer copied/current/committed bytes differ')
 before=[ref(row['repositoryInput']['path']) for row in c['binderSourceBindings']]
 for row in c['binderSourceBindings']:
  require(sha(row['copiedInput']['path'])==sha(row['repositoryInput']['path'])==row['gitBlobSha256'],'Binder copied/current/committed bytes differ')
 env=dict(os.environ,PYTHONDONTWRITEBYTECODE='1',PYTHONPATH=str(hook)+':'+str(tool),SLICE3_NAMED_REPOSITORY=str(tool),SLICE3_NAMED_UNITTEST_OUTPUT=str(out/'named-tests.json'))
 argv=[sys.executable,'-m','unittest','discover','-s',str(tool),'-p','test_*.py','-v']
 require(argv[1:]==c['binderObserverExpectedArgvSuffix'],'Binder observer must match the exact actual argv')
 with (out/'command.log').open('wb') as f:code=subprocess.run(argv,cwd=tool,env=env,stdout=f,stderr=subprocess.STDOUT).returncode
 after=[ref(row['repositoryInput']['path']) for row in c['binderSourceBindings']];n=read(out/'named-tests.json') if (out/'named-tests.json').is_file() else {}
 observer_after=[ref(hook/n) for n in ('sitecustomize.py','slice3_unittest_capture.py')]
 count=n.get('frameworkCounts',{});ok=code==0 and before==after and observer_before==observer_after and count=={'testsRun':106,'failures':0,'errors':0,'skipped':0,'expectedFailures':0,'unexpectedSuccesses':0} and n.get('frameworkWasSuccessful') is True and n.get('unexecutedIds')==[]
 receipt={'kind':'INDEPENDENT_ADDITIONAL_INPUT_SYNTHETIC_EXECUTION','id':'method_binder_synthetic','sourceHead':c['head'],'sourceTree':c['tree'],'sourceInventorySha256':c['inventory']['sha256'],'runId':out.name,'startedAt':started,'finishedAt':now(),'elapsedSeconds':round(time.monotonic()-t,6),'argv':argv,'exitCode':code,'sourceStable':before==after and observer_before==observer_after,'inputsBefore':before,'inputsAfter':after,'observerInputsBefore':observer_before,'observerInputsAfter':observer_after,'observerRepositoryGitBinding':observer_binding,'observerExpectedArgvSuffix':c['binderObserverExpectedArgvSuffix'],'repositoryGitBindings':c['binderSourceBindings'],'frameworkCounts':count,'result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED' if ok else 'COMMAND_FAILED_REVIEW_REQUIRED','evidence':[ref(p) for p in out.iterdir() if p.is_file()],'boundary':'106 evidence-tool synthetic boundary tests only, never product acceptance nodes or automatic proof admission.'};write(out/'receipt.json',receipt)
 require(ok,'Binder synthetic checks must all execute successfully; see independent receipt')


def mixed(c):
 p,x=backend(c);out=P(c['runs'])/'mixed-capacity';out.mkdir(exist_ok=False)
 rows=read(p.parent/'preserved-reports.json');wanted=[r for r in rows if '/advertising-mixed-capacity-' in r['originalPath'] and r['originalPath'].endswith('.json')]
 expected={'backend/marketops-server/target/advertising-mixed-capacity-'+suffix+'.json' for suffix in ('dataset','diagnostic','receipt','source-inputs')}
 require(len(wanted)==4 and {r['originalPath'] for r in wanted}==expected and all(r.get('writtenDuringCommand') is True for r in wanted),'Exact four freshly emitted mixed files required, including diagnostic; duplicate/missing/extra paths refused')
 for r in wanted:
  registered_report(x,r);require(sha(r['path'])==r['sha256'],'Mixed raw hash differs')
 node_ref=x['namedTestcaseNodes'];node_path=p.parent/'named-testcase-nodes.json'
 require(P(node_ref['path']).resolve()==node_path.resolve() and sha(node_path)==node_ref['sha256'],'Named testcase index differs from exact parent receipt reference')
 named=read(node_path);method='declaredPortfolioProcessesFreshMatureRevisionsAndRepairsDroppedCorrectionsWithExpiredControls';nodes=[n for n in named if n['name']==method or n['name'].startswith(method+'(') or n['name'].startswith(method+'[')]
 require(len(nodes)==1 and nodes[0]['status']=='PASSED','Unique successful actual mixed node required')
 report=nodes[0]['report'];registered_report(x,report);require(sha(report['path'])==report['sha256'],'Mixed JUnit raw changed')
 row=next(r for r in wanted if r['originalPath'].endswith('mixed-capacity-receipt.json'));v=read(row['path']);target=v['targeted'];ident=v['identities']
 require(ident['measuredLocalGitHead']==c['head'] and ident['publicationIdentity']['sourceHeadSha']==c['head'],'Mixed measured source identity differs')
 for suffix,key in [('mixed-capacity-dataset.json','datasetSha256'),('mixed-capacity-source-inputs.json','sourceInputsSha256')]:
  raw=next(r for r in wanted if r['originalPath'].endswith(suffix));require(raw['sha256']==ident[key],'Mixed linked input digest differs')
 resource=next((r for r in rows if r['sha256']==ident['runtimeResourceReceiptSha256'] and r['originalPath'].endswith('/backend/runtime-resources.json')),None)
 require(resource is not None and sha(resource['path'])==ident['runtimeResourceReceiptSha256'],'Original same-run resource receipt missing or changed')
 registered_report(x,resource)
 require(v['productionWriteEnabled'] is False and v['realProviderAccess'] is False,'Mixed declared environment boundary differs')
 # Preserve the established thresholds; no favorable substitution or second timed run.
 require(target['criticalP95Millis']<=300000 and target['maximumMillis']<=900000 and v['sweepWallMillis']<1800000,'Declared latency threshold breached')
 require(all(target[k]==0 for k in ('hardBreachCount','clockDefectCount','pendingRequests','failedRequests')),'Mixed queue/failure/clock boundary failed')
 write(out/'original-backend-run-reference.json',{'kind':'SAME_BACKEND_EXECUTION_SCOPE_REFERENCE_NOT_NEW_RUN','id':'mixed_capacity',**exact_source(c,False),'runId':x['runId'],'originalLayer':'backend_full','parentExecution':ref(p),'node':nodes[0],'mixedArtifacts':[{'originalPath':r['originalPath'],'raw':ref(r['path'])} for r in wanted],'evidence':[ref(r['path']) for r in wanted]+[report,ref(resource['path'])],'thresholdsMillis':{'criticalP95':300000,'maximum':900000,'sweep':1800000},'sweepComparator':'STRICTLY_LESS_THAN','timedRunRepeated':False,'assessment':'NOT_PERFORMED','boundary':'Mixed workload is a separately reviewed scope of the original full backend execution; raw workload/state/resource/source/dataset IDs still require root review. No invented test count, current admission or APPLY throughput.'})


def main():
 ap=argparse.ArgumentParser();ap.add_argument('--config',required=True,type=P);ap.add_argument('mode',choices=['preflight','finish','products','binder','mixed']);ap.add_argument('value',nargs='?');ap.add_argument('extra',nargs='?');a=ap.parse_args();c=read(a.config)
 if a.mode=='preflight':
  ident=exact_source(c);i=inputs(c);d=P(c['runs'])/'driver-executions'/a.value;d.mkdir(parents=True,exist_ok=False);write(d/'before.json',{'at':now(),**ident,'executionInputs':i,'config':ref(a.config)})
 elif a.mode=='finish':
  d=P(c['runs'])/'driver-executions'/a.value;before=read(d/'before.json');stable=False;error=None
  try:ident=exact_source(c,False);require(ref(a.config)==before['config'],'Driver CONFIG changed during execution');require(inputs(c)==before['executionInputs'],'Execution input index changed during execution');stable=True
  except Exception as e:error=str(e);ident={'sourceHead':c['head'],'sourceTree':c['tree'],'sourceInventorySha256':c['inventory']['sha256']}
  write(d/'receipt.json',{'kind':'DRIVER_ADDITIONAL_EXECUTION_INPUT_RECEIPT','id':a.value,**ident,'startedAt':before['at'],'finishedAt':now(),'exitCode':int(a.extra),'sourceStable':stable,'executionInputs':before['executionInputs'],'before':ref(d/'before.json'),'error':error,'result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED' if int(a.extra)==0 and stable else 'COMMAND_FAILED_REVIEW_REQUIRED','boundary':'Driver transport/inputs only. Layer collector or independently collected CI receipt remains the product execution authority; this does not assert layer PASS.'});require(stable,'Execution input drift')
 elif a.mode=='products':products(c,a.value)
 elif a.mode=='binder':binder(c)
 elif a.mode=='mixed':mixed(c)
if __name__=='__main__':main()
