#!/usr/bin/env python3
"""Read-only final-evidence-commit GitHub raw capture. Never emits a gate PASS."""
import argparse,datetime,hashlib,json,re,subprocess,time,urllib.parse,zipfile
from pathlib import Path
if not __debug__: raise RuntimeError('Optimized Python disables required evidence checks')
REQUIRED=['backend-build','backend-integration','architecture-boundary','frontend-lint','frontend-typecheck','frontend-test','frontend-build','governance','infrastructure-validation','dependency-review','codeql-java','codeql-typescript']
ap=argparse.ArgumentParser();ap.add_argument('--repo',type=Path,required=True);ap.add_argument('--head',required=True);ap.add_argument('--tree',required=True);ap.add_argument('--base',required=True);ap.add_argument('--product-expected',type=Path,required=True);ap.add_argument('--inventory-sha256',required=True);ap.add_argument('--inventory-count',type=int,required=True);ap.add_argument('--baseline-head',required=True);ap.add_argument('--baseline-security-sha256',required=True);ap.add_argument('--baseline-security',type=Path,required=True);ap.add_argument('--out',type=Path,required=True);ap.add_argument('--phase',choices=['metadata','completed'],required=True);ap.add_argument('--run-id',type=int);ap.add_argument('--shared-capture',type=Path);a=ap.parse_args()
for value in [a.head,a.tree,a.base,a.baseline_head]:assert re.fullmatch('[0-9a-f]{40}',value)
assert re.fullmatch('[0-9a-f]{64}',a.inventory_sha256) and re.fullmatch('[0-9a-f]{64}',a.baseline_security_sha256) and a.inventory_count>0
a.repo=a.repo.resolve();a.out=a.out.resolve();assert a.out.is_relative_to(Path('/tmp').resolve()) and not a.out.exists();assert (a.repo/'bootstrap-manifest.json').is_file();a.out.mkdir(parents=True)
sha=lambda b:hashlib.sha256(b).hexdigest();now=lambda:datetime.datetime.now(datetime.timezone.utc).isoformat()
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def read(p):return json.loads(Path(p).read_text())
def write(name,d):(a.out/name).write_text(json.dumps(d,indent=2)+'\n')
def command(name,argv):
 out=a.out/name;err=a.out/(name+'.stderr');assert not out.exists();attempts=[]
 for attempt in range(1,4):
  raw=a.out/(name+'.attempt-'+str(attempt));stderr=a.out/(name+'.attempt-'+str(attempt)+'.stderr')
  assert not raw.exists() and not stderr.exists();start=now();t=time.monotonic();failure=None
  with raw.open('wb')as o,stderr.open('wb')as e:
   try:exitcode=subprocess.run(argv,stdout=o,stderr=e,cwd=a.repo,timeout=60).returncode
   except subprocess.TimeoutExpired:exitcode=124;failure='READ_ONLY_CAPTURE_TIMEOUT_RAW_PREFIX_RETAINED'
  record={'argv':argv,'startedAt':start,'finishedAt':now(),'elapsedSeconds':time.monotonic()-t,'exitCode':exitcode,'failure':failure,'artifact':ref(raw),'stderr':ref(stderr),'attempt':attempt,'boundary':'Original bytes of one read-only capture attempt; never overwritten.'}
  recordname=name+'.attempt-'+str(attempt)+'.command.json';write(recordname,record);attempts.append(ref(a.out/recordname))
  transient=exitcode!=0 and (failure is not None or b'EOF' in stderr.read_bytes())
  if exitcode==0 or not transient or attempt==3:
   out.write_bytes(raw.read_bytes());err.write_bytes(stderr.read_bytes())
   write(name+'.command.json',{**record,'artifact':ref(out),'stderr':ref(err),'originalResponse':ref(raw),'originalStderr':ref(stderr),'attemptReceipts':attempts,'canonicalResponseIsExactCopy':True})
   assert exitcode==0,(name,exitcode);return out
  time.sleep(attempt)
sharedResponses={};reused=[]
def api(name,endpoint,*,pages=False,accept=None,binary=False):
 if endpoint in sharedResponses and name not in ['security-run-after.json','pr30-after.json','source-check-runs-after.json'] and not pages and not accept and not binary:
  entry=sharedResponses[endpoint];original=Path(entry['response']['path']);assert ref(original)['sha256']==entry['response']['sha256'];p=a.out/name;p.write_bytes(original.read_bytes());reused.append({'output':ref(p),'original':entry['response'],'originalCommand':entry['originalCommand'],'parentCapture':entry['parentCapture'],'newApiExecutionClaimed':False});return read(p)
 argv=['gh','api',endpoint,'-X','GET'];argv+=['--paginate','--slurp']if pages else [];argv+=['-H','Accept: '+accept]if accept else [];p=command(name,argv);return p if binary else read(p)
def git(*args):return subprocess.check_output(['git','--no-replace-objects',*args],cwd=a.repo)
inputs=[Path(__file__).resolve(),a.product_expected.resolve(),a.baseline_security.resolve()];before=[ref(p)for p in inputs];state={'kind':'READ_ONLY_FINAL_EVIDENCE_COMMIT_SECURITY_CAPTURE','startedAt':now(),'phase':a.phase,'head':a.head,'tree':a.tree,'base':a.base,'automaticPassClaimMade':False,'controllerApprovalClaimMade':False,'productionWriteEnabled':False,'mutationsPerformed':False};prefix='repos/Corwin-Code/marketops-platform';failure=None
try:
 assert git('rev-parse',a.head+'^{tree}').decode().strip()==a.tree
 if a.shared_capture:
  shared=a.shared_capture.resolve()/'CI-CAPTURE-RECEIPT.json';sd=read(shared);assert sd['sourceHead']==a.head and sd['sourceTree']==a.tree and sd['baseHead']==a.base and sd['exitCode']==0
  inputs.append(shared);before.append(ref(shared))
  for cr in sd['commandReceipts']:
   cp=Path(cr['path']);assert ref(cp)['sha256']==cr['sha256'];cd=read(cp);argv=cd['argv'];assert cd['exitCode']==0 and argv[:4]==['gh','api','--method','GET'];endpoint=argv[4]
   if Path(cd['response']['path']).name in ['pr30-before.json','source-commit.json','tested-merge-commit.json','check-runs.json','workflow-runs.json','security-run.json','security-jobs.json']:sharedResponses[endpoint]={**cd,'originalCommand':cr,'parentCapture':ref(shared)}
 expected=read(a.product_expected);invref=expected['inventory'];ip=Path(invref['path']);assert ref(ip)['sha256']==invref['sha256'];inv=read(ip);assert ref(ip)['sha256']==a.inventory_sha256 and len(inv['files'])==len({x['path']for x in inv['files']})==a.inventory_count;product=expected['sourceHead'];inputs.append(ip);before.append(ref(ip))
 baseline=read(a.baseline_security)
 assert re.fullmatch('[0-9a-f]{40}',product) and git('rev-parse',product+'^{tree}').decode().strip()==expected['sourceTree']
 assert ref(a.baseline_security)['sha256']==a.baseline_security_sha256 and baseline['sourceHead']==a.baseline_head
 assert a.baseline_head=='02e617278793b4458dfb7cac74c2a937f1dfcb00', 'This reviewed kit accepts only the explicit immutable historical 02e security baseline'
 assert baseline['sourceHead']!=product and baseline['kind']=='EXACT_SOURCE_CHECKPOINT_SECURITY_EXECUTION_RECEIPT'
 assert baseline['securityLayerResult']=='PASS_FOR_THIS_EXACT_SOURCE_CHECKPOINT' and baseline['criteria'] and all(v is True for v in baseline['criteria'].values())
 assert git('rev-parse',a.baseline_head+'^{tree}').decode().strip()==baseline['sourceTree']
 assert baseline['run']['head_sha']==a.baseline_head and baseline['run']['status']=='completed' and baseline['run']['conclusion']=='success'
 assert len(baseline['jobs'])==3 and {j['name'] for j in baseline['jobs']}=={'dependency-review','codeql-java','codeql-typescript'}
 assert all(j['head_sha']==a.baseline_head and j['run_id']==baseline['run']['id'] and j['run_attempt']==baseline['run']['run_attempt'] and j['status']=='completed' and j['conclusion']=='success' for j in baseline['jobs'])
 assert baseline['aggregateCodeQL']['head_sha']==a.baseline_head and baseline['aggregateCodeQL']['status']=='completed' and baseline['aggregateCodeQL']['conclusion']=='success'
 ancestry=command('historical-baseline-product-ancestry.txt',['git','--no-replace-objects','merge-base','--is-ancestor',a.baseline_head,product])
 containing=command('product-containing-ancestry.txt',['git','--no-replace-objects','merge-base','--is-ancestor',product,a.head])
 baseline_refs=[baseline['openAlerts']['triage'],baseline['historicalDismissedHigh']['reconciliation'],baseline['sourceInventoryComparison'],baseline['actualSourceBefore'],baseline['rawLogs']]
 for e in baseline_refs:
  path=Path(e['path']);assert ref(path)['sha256']==e['sha256'];inputs.append(path);before.append(ref(path))
 write('HISTORICAL-BASELINE-BINDING.json',{'kind':'HISTORICAL_ANCESTOR_SECURITY_COMPARISON_ONLY','baselineReceipt':ref(a.baseline_security),'baselineSourceHead':a.baseline_head,'baselineSourceTree':baseline['sourceTree'],'productSourceHead':product,'productSourceTree':expected['sourceTree'],'currentCiHead':a.head,'baselineAncestorCommand':ref(ancestry.with_name(ancestry.name+'.command.json')),'productAncestorCommand':ref(containing.with_name(containing.name+'.command.json')),'baselineReferences':baseline_refs,'historicalResultPromoted':False,'currentSourceSecurityReviewRequired':True})
 state['baselineBinding']=ref(a.out/'HISTORICAL-BASELINE-BINDING.json');state['productInventoryFileCount']=a.inventory_count;state['checkpointScope']='PRODUCT_CHECKPOINT' if a.head==product else 'CONTAINING_EVIDENCE_COMMIT'
 state['baselineSecurity']=ref(a.baseline_security);state['productExpected']=ref(a.product_expected);state['productInventory']=ref(ip);state['productSourceHead']=product;state['derivationSourceIdentity']=expected.get('derivationSourceIdentity')
 pr=api('pr30.json',prefix+'/pulls/30');assert pr['number']==30 and pr['draft']is True and pr['state']=='open' and pr['head']['sha']==a.head and pr['base']['sha']==a.base
 merge=pr['merge_commit_sha'];assert re.fullmatch('[0-9a-f]{40}',merge);state['testedMerge']=merge
 source=api('source-commit.json',prefix+'/git/commits/'+a.head);merged=api('tested-merge-commit.json',prefix+'/git/commits/'+merge)
 assert source['sha']==a.head and source['tree']['sha']==a.tree and merged['sha']==merge and merged['tree']['sha']==a.tree and [p['sha']for p in merged['parents']]==[a.base,a.head]
 # Compare every product runtime path/mode/blob at product and evidence commits, then official tested merge.
 remote=api('tested-merge-tree-recursive.json',prefix+'/git/trees/'+merged['tree']['sha']+'?recursive=1');assert not remote['truncated'];byremote={x['path']:x for x in remote['tree']}
 def tree_entries(head):
  rows={}
  for raw in git('ls-tree','-r','-z',head).split(b'\0'):
   if raw:meta,path=raw.split(b'\t',1);mode,kind,oid=meta.decode().split();rows[path.decode()]={'mode':mode,'type':kind,'sha':oid}
  return rows
 pt=tree_entries(product);et=tree_entries(a.head);sourcechecks=[]
 inpaths={f['path'] for f in inv['files']};changed=sorted(p for p in set(pt)|set(et) if pt.get(p)!=et.get(p));assert all(p not in inpaths and (p.startswith('docs/') or p=='README.md') for p in changed), 'Containing commit changed runtime or undeclared paths'
 state['productToContainingChangedPaths']=[{'path':p,'before':pt.get(p),'after':et.get(p)} for p in changed]
 request='\n'.join(et[f['path']]['sha']for f in inv['files'])+'\n';raw=subprocess.run(['git','--no-replace-objects','cat-file','--batch'],input=request.encode(),cwd=a.repo,capture_output=True,check=True,timeout=60).stdout;pos=0
 for f in inv['files']:
  path=f['path'];assert pt[path]==et[path];assert all(byremote[path][k]==et[path][k]for k in ['mode','type','sha']);end=raw.index(b'\n',pos);oid,kind,size=raw[pos:end].decode().split();pos=end+1;b=raw[pos:pos+int(size)];pos+=int(size)+1;assert kind=='blob' and oid==et[path]['sha'];conversion=None
  if sha(b)!=f['sha256']:
   assert path in ['backend/marketops-server/mvnw.cmd','scripts/bootstrap-repo.ps1'] and sha(b.replace(b'\r\n',b'\n').replace(b'\n',b'\r\n'))==f['sha256'];conversion='DECLARED_GIT_LF_TO_WORKTREE_CRLF'
  sourcechecks.append({'path':path,'gitMode':et[path]['mode'],'gitBlob':oid,'gitSha256':sha(b),'inventorySha256':f['sha256'],'conversion':conversion})
 assert pos==len(raw);write('PRODUCT-TO-FINAL-CI-SOURCE-COMPARISON.json',{'productSourceHead':product,'evidenceSourceHead':a.head,'evidenceTree':a.tree,'testedMerge':merge,'testedMergeTree':merged['tree']['sha'],'testedMergeParents':[a.base,a.head],'inventory':ref(ip),'members':sourcechecks,'scope':'Exact product runtime bytes at original product, final evidence commit and official tested merge. Evidence-only changes are distinct, not a relabeled original full run.'})
 extras=[]
 for e in expected.get('additionalExecutionInputs',[]):
  path=e['path'];b=git('show',a.head+':'+path);assert sha(b)==e['sha256'];assert et[path]['sha']==byremote[path]['sha'];extras.append({'path':path,'sha256':sha(b),'gitBlob':et[path]['sha'],'scope':'Present exact bytes only; prior D execution is separately bound and not claimed rerun by CI.'})
 state['evidenceHelperComparisons']=extras
 runs=api('security-runs.json',prefix+'/actions/runs?'+urllib.parse.urlencode({'head_sha':a.head,'event':'pull_request','per_page':100}));assert runs['total_count']==len(runs['workflow_runs'])<=100;candidates=[r for r in runs['workflow_runs']if r['head_sha']==a.head and r['path']=='.github/workflows/security.yml']
 if a.run_id:candidates=[r for r in candidates if r['id']==a.run_id]
 assert len(candidates)==1,('Need unambiguous exact run id',[(r['id'],r['status'])for r in candidates]);run=api('security-run.json',prefix+'/actions/runs/'+str(candidates[0]['id']));state['run']={k:run[k]for k in ['id','run_attempt','head_sha','status','conclusion','run_started_at','updated_at']};assert run['head_sha']==a.head and run['event']=='pull_request' and run['run_attempt']==candidates[0]['run_attempt'] and any(p['number']==30 and p['head']['sha']==a.head and p['base']['sha']==a.base for p in run['pull_requests'])
 checks=api('source-check-runs.json',prefix+'/commits/'+a.head+'/check-runs?filter=latest&per_page=100');assert checks['total_count']==len(checks['check_runs'])==13 and {c['name']for c in checks['check_runs']}==set(REQUIRED+['CodeQL']) and all(c['head_sha']==a.head for c in checks['check_runs']);checkby={c['name']:c for c in checks['check_runs']};state['checkContexts']=[{k:c[k]for k in ['id','name','head_sha','status','conclusion']}for c in checks['check_runs']]
 jobs=api('security-jobs.json',prefix+'/actions/runs/'+str(run['id'])+'/attempts/'+str(run['run_attempt'])+'/jobs?per_page=100');art=api('security-artifacts.json',prefix+'/actions/runs/'+str(run['id'])+'/artifacts?per_page=100');state['jobs']=[{k:j[k]for k in ['id','name','run_id','run_attempt','head_sha','status','conclusion','started_at','completed_at']}for j in jobs['jobs']];state['artifactIds']=[x['id']for x in art['artifacts']]
 assert jobs['total_count']==len(jobs['jobs'])==3 and {j['name']for j in jobs['jobs']}=={'dependency-review','codeql-java','codeql-typescript'}
 for j in jobs['jobs']:assert j['head_sha']==a.head and j['run_id']==run['id'] and j['run_attempt']==run['run_attempt'] and j['id']==checkby[j['name']]['id'] and j['status']==checkby[j['name']]['status'] and j['conclusion']==checkby[j['name']]['conclusion']
 assert art['total_count']==len(art['artifacts']);state['actionsArtifactBoundary']='The exact source Security workflow has no Actions artifact upload. SARIF is bound by Code Scanning analysis/sarif ID.'
 if a.phase=='completed':
  assert art['total_count']==0,'Unexpected Actions artifacts in the no-upload Security workflow; retain raw metadata and review exact attempt before reuse.'
  assert run['status']=='completed' and all(j['status']=='completed'for j in jobs['jobs']);assert {j['name']for j in jobs['jobs']}=={'dependency-review','codeql-java','codeql-typescript'}
  zpath=api('security-run-logs.zip',prefix+'/actions/runs/'+str(run['id'])+'/attempts/'+str(run['run_attempt'])+'/logs',binary=True);zi=[]
  with zipfile.ZipFile(zpath)as z:
   assert z.testzip()is None and len(z.namelist())==len(set(z.namelist()))
   for n in sorted(z.namelist()):b=z.read(n);zi.append({'member':n,'sha256':sha(b),'bytes':len(b),'crc32':format(z.getinfo(n).CRC,'08x')})
  write('RAW-LOG-MEMBER-INDEX.json',{'archive':ref(zpath),'members':zi,'zipCrcVerified':True})
  for artifact in art['artifacts']:
   p=api('artifact-'+str(artifact['id'])+'.zip',prefix+'/actions/artifacts/'+str(artifact['id'])+'/zip',binary=True)
   with zipfile.ZipFile(p)as z:assert z.testzip()is None
  pullref='refs/pull/30/merge';analyses=api('analyses-pages.json',prefix+'/code-scanning/analyses?'+urllib.parse.urlencode({'ref':pullref,'per_page':100}),pages=True);found=[]
  for jobname,category,name in [('codeql-java','/language:java-kotlin','java.sarif.json'),('codeql-typescript','/language:javascript-typescript','typescript.sarif.json')]:
   job=next(j for j in jobs['jobs']if j['name']==jobname);selected=[x for page in analyses for x in page if x['commit_sha']==merge and x['analysis_key']=='.github/workflows/security.yml:'+jobname and x['category']==category and job['started_at']<=x['created_at']<=job['completed_at']]
   if job['conclusion']=='success':assert len(selected)==1 and not selected[0]['error'] and not selected[0]['warning'],('Missing/ambiguous/error actual successful analysis',jobname)
   if len(selected)==1:
    analysis=selected[0];api(name,prefix+'/code-scanning/analyses/'+str(analysis['id']),accept='application/sarif+json');found.append(analysis)
  state['selectedAnalyses']=found
  for alertstate in ['open','dismissed','fixed']:api(alertstate+'-alerts-pages.json',prefix+'/code-scanning/alerts?'+urllib.parse.urlencode({'state':alertstate,'ref':pullref,'per_page':100}),pages=True)
  for agg in [c for c in checks['check_runs']if c['name']=='CodeQL']:api('aggregate-'+str(agg['id'])+'-annotations-pages.json',prefix+'/check-runs/'+str(agg['id'])+'/annotations?per_page=100',pages=True)
  api('dependency-diff.json',prefix+'/dependency-graph/compare/'+a.base+'...'+a.head);api('default-main-dependabot-pages.json',prefix+'/dependabot/alerts?state=open&per_page=100',pages=True)
  post=api('security-run-after.json',prefix+'/actions/runs/'+str(run['id']));postpr=api('pr30-after.json',prefix+'/pulls/30');assert post['run_attempt']==run['run_attempt'] and post['head_sha']==a.head and post['status']==run['status'] and post['conclusion']==run['conclusion'];assert postpr['head']['sha']==a.head and postpr['base']['sha']==a.base and postpr['merge_commit_sha']==merge and postpr['draft']is True and postpr['state']=='open'
  afterchecks=api('source-check-runs-after.json',prefix+'/commits/'+a.head+'/check-runs?filter=latest&per_page=100');assert {(c['id'],c['name'],c['head_sha'],c['status'],c['conclusion'])for c in afterchecks['check_runs']}=={(c['id'],c['name'],c['head_sha'],c['status'],c['conclusion'])for c in checks['check_runs']}
  state['captureDisposition']='RAW_TERMINAL_SECURITY_CAPTURE_REQUIRES_INDEPENDENT_ALERT_SOURCE_LOG_AND_PUBLICATION_REVIEW'
 else:state['captureDisposition']='METADATA_SNAPSHOT_ONLY_NO_TERMINAL_INFERENCE'
except Exception as e:
 failure=type(e).__name__+': '+str(e);state['captureDisposition']='INCOMPLETE_RAW_CAPTURE_RETAINED'
finally:
 state.update(reusedOriginalMetadata=reused,finishedAt=now(),failure=failure,inputsBefore=before,inputsAfter=[ref(p)for p in inputs]);state['inputsStable']=state['inputsBefore']==state['inputsAfter'];write('CAPTURE-RECEIPT.json',state)
 write('INDEX.json',{'kind':'EXACT_RAW_CAPTURE_FILE_INDEX','files':[ref(p)for p in sorted(a.out.iterdir())if p.is_file()and p.name!='INDEX.json'],'sourceHead':a.head,'scope':'No source-equivalent historical PASS is silently promoted; actual final run/job/merge and open/dismissed/default-main scopes require independent review.'})
 print(json.dumps({'capture':ref(a.out/'CAPTURE-RECEIPT.json'),'failure':failure,'disposition':state['captureDisposition']}))
 if failure:raise SystemExit(1)
