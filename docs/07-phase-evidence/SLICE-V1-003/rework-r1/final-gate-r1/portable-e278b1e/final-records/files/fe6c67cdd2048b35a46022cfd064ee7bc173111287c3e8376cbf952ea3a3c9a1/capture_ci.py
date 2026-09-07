#!/usr/bin/env python3
"""Bounded GET-only capture for the actual final PR30 head. No workflow dispatch or polling."""
import argparse,datetime,hashlib,json,pathlib,re,subprocess,sys,time,zipfile
REPOSITORY='Corwin-Code/marketops-platform'
REQUIRED=['backend-build','backend-integration','architecture-boundary','frontend-lint','frontend-typecheck','frontend-test','frontend-build','governance','infrastructure-validation','dependency-review','codeql-java','codeql-typescript']
WORKFLOWS={'backend':('Backend','.github/workflows/backend.yml',['backend-build','backend-integration','architecture-boundary']), 'frontend':('Frontend','.github/workflows/frontend.yml',['frontend-lint','frontend-typecheck','frontend-test','frontend-build']), 'governance':('Governance','.github/workflows/governance.yml',['governance']), 'infrastructure':('Infrastructure','.github/workflows/infrastructure.yml',['infrastructure-validation']), 'security':('Security','.github/workflows/security.yml',['dependency-review','codeql-java','codeql-typescript'])}
ARTIFACT_JOBS={'backend':{'backend-test-reports':'backend-build','backend-integration-reports':'backend-integration','backend-supply-chain':'backend-build'}, 'frontend':{'frontend-coverage':'frontend-test','advertising-browser-screenshots':'frontend-test','frontend-distribution':'frontend-build'}, 'governance':{},'infrastructure':{'infrastructure-verification':'infrastructure-validation'}}
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):
 p=pathlib.Path(p).resolve();b=p.read_bytes();return {'path':str(p),'sha256':sha(b),'bytes':len(b)}
def need(ok,message):
 if not ok:raise ValueError(message)
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def save(p,v):
 need(not p.exists(),'Preserve earlier output: '+str(p));p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,indent=2)+'\n');return ref(p)
def checked_zip(p):
 z=zipfile.ZipFile(p);names=z.namelist();need(len(names)==len(set(names)),'Duplicate ZIP member: '+str(p))
 for n in names:
  q=pathlib.PurePosixPath(n);need(n and not q.is_absolute() and '..' not in q.parts and '\\' not in n,'Unsafe ZIP member: '+n)
 return z

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--source-head',required=True);ap.add_argument('--source-tree',required=True);ap.add_argument('--product-head',required=True);ap.add_argument('--product-tree',required=True);ap.add_argument('--inventory-sha256',required=True);ap.add_argument('--inventory-count',type=int,required=True);ap.add_argument('--inventory',type=pathlib.Path,required=True);ap.add_argument('--repo-path',type=pathlib.Path,required=True);ap.add_argument('--out',type=pathlib.Path,required=True);a=ap.parse_args()
 need(all(re.fullmatch('[0-9a-f]{40}',v) for v in (a.source_head,a.source_tree,a.product_head,a.product_tree)) and re.fullmatch('[0-9a-f]{64}',a.inventory_sha256) and a.inventory_count>0,'Explicit actual source/product/tree/inventory pins required');a.out=a.out.resolve();need(a.out.is_relative_to(pathlib.Path('/tmp').resolve()) and not a.out.exists(),'Use a fresh /tmp output directory');a.out.mkdir(parents=True)
 started=now();clock=time.monotonic();inputs={'captureTool':ref(__file__),'sourceInventory':ref(a.inventory)};commands=[];result={'kind':'EXACT_FINAL_CONTAINING_CI_READ_ONLY_CAPTURE','startedAt':started,'sourceHead':a.source_head,'productSourceHead':a.product_head,'productSourceTree':a.product_tree,'sourceInventoryFileCount':a.inventory_count,'checkpointScope':'PRODUCT_CHECKPOINT' if a.source_head==a.product_head else 'CONTAINING_EVIDENCE_COMMIT','sourceInventorySha256':a.inventory_sha256,'inputFiles':inputs,'repository':REPOSITORY,'prNumber':30,'securityScope':'Status/run/attempt/job metadata only. Security logs/artifacts/alerts require the separate Security evidence collector.','productionWriteEnabled':False,'providerAccessPerformed':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False}
 def git(*args):return subprocess.check_output(['git','--no-replace-objects','-C',str(a.repo_path),*args])
 def api(name,endpoint,binary=False):
  need(endpoint.startswith('repos/'+REPOSITORY+'/'),'GET endpoint outside exact repository')
  target=a.out/name;need(not target.exists(),'Preserve original API response');argv=['gh','api','--method','GET',endpoint]
  for attempt in range(1,4):
   p=a.out/(name+'.attempt-'+str(attempt));ep=a.out/(name+'.attempt-'+str(attempt)+'.stderr');need(not p.exists() and not ep.exists(),'Preserve every GET attempt');st=now();ct=time.monotonic()
   with p.open('wb') as output,ep.open('wb') as err:
    try:exitcode=subprocess.run(argv,stdout=output,stderr=err,timeout=60).returncode
    except subprocess.TimeoutExpired:exitcode=124
   record={'kind':'READ_ONLY_GITHUB_API_COMMAND','argv':argv,'attempt':attempt,'startedAt':st,'finishedAt':now(),'elapsedSeconds':round(time.monotonic()-ct,3),'exitCode':exitcode,'response':ref(p),'stderr':ref(ep)}
   attempt_ref=save(a.out/(name+'.attempt-'+str(attempt)+'.command.json'),record);commands.append(attempt_ref)
   if exitcode==0:
    target.write_bytes(p.read_bytes());need(ref(target)['sha256']==record['response']['sha256'],'Selected response copy differs')
    save(a.out/(name+'.command.json'),{**record,'selectedOriginalAttempt':attempt_ref,'canonicalResponse':ref(target)})
    return target if binary else json.loads(target.read_bytes())
   retryable=exitcode==124 or b'EOF' in ep.read_bytes()
   if not retryable or attempt==3:break
   time.sleep(attempt)
  need(False,'GET failed; every original raw attempt retained: '+name)

 try:
  need(inputs['sourceInventory']['sha256']==a.inventory_sha256,'Product inventory bytes differ');inv=json.loads(a.inventory.read_bytes())['files'];need(len(inv)==len({x['path'] for x in inv})==a.inventory_count,'Exact supplied runtime inventory required')
  need(git('rev-parse',a.product_head+'^{tree}').decode().strip()==a.product_tree and git('rev-parse',a.source_head+'^{tree}').decode().strip()==a.source_tree,'Explicit product or source tree differs');need(git('rev-parse','HEAD').decode().strip()==a.source_head,'Local actual HEAD differs');need(not git('status','--porcelain','--untracked-files=all').strip(),'Local worktree must be clean before final CI capture');git('merge-base','--is-ancestor',a.product_head,a.source_head)
  def entries(head):
   d={}
   for v in git('ls-tree','-rz','--full-tree',head).split(b'\0'):
    if v:
     m,p=v.split(b'\t',1);mode,typ,oid=m.decode().split();d[p.decode()]={'mode':mode,'type':typ,'oid':oid}
   return d
  old=entries(a.product_head);new=entries(a.source_head);inpaths={x['path'] for x in inv};changed=sorted(p for p in set(old)|set(new) if old.get(p)!=new.get(p));need(all(p not in inpaths and (p.startswith('docs/') or p=='README.md') for p in changed),'Containing commit changed runtime or undeclared non-doc paths')
  checks=[]
  # Check actual original Git bytes against measured inventory, and modes/OIDs at final head.
  cp=subprocess.run(['git','--no-replace-objects','-C',str(a.repo_path),'cat-file','--batch'],input=('\n'.join(a.product_head+':'+r['path'] for r in inv)+'\n').encode(),stdout=subprocess.PIPE,check=True);pos=0;data=cp.stdout
  for row in inv:
   p=row['path'];end=data.index(b'\n',pos);oid,typ,n=data[pos:end].decode().split();n=int(n);b=data[end+1:end+1+n];pos=end+n+2;need(old[p]==new[p] and oid==old[p]['oid'] and typ=='blob','Runtime Git entry mismatch: '+p)
   transformation=None
   if sha(b)!=row['sha256']:
    need(p in {'backend/marketops-server/mvnw.cmd','scripts/bootstrap-repo.ps1'} and sha(b.replace(b'\r\n',b'\n').replace(b'\n',b'\r\n'))==row['sha256'],'Undeclared source byte mismatch: '+p);transformation='GIT_LF_TO_WORKTREE_CRLF'
   checks.append({'path':p,'productGitEntry':old[p],'containingGitEntry':new[p],'sourceInventorySha256':row['sha256'],'lineEndingTransformation':transformation})
  need(pos==len(data),'Unexpected Git cat-file bytes');tree=git('rev-parse',a.source_head+'^{tree}').decode().strip();result['sourceTree']=tree
  localref=save(a.out/'LOCAL-CONTAINING-SOURCE-EQUIVALENCE.json',{'productHead':a.product_head,'sourceHead':a.source_head,'sourceTree':tree,'inventory':inputs['sourceInventory'],'runtimeRows':checks,'changedPaths':[{'path':p,'before':old.get(p),'after':new.get(p)} for p in changed],'boundary':'Exact product runtime Git equality only; final excluded docs/tool changes remain explicitly enumerated.'})
  pr=api('pr30-before.json','repos/'+REPOSITORY+'/pulls/30');need(pr['number']==30 and pr['state']=='open' and pr['draft'] is True and pr['head']['sha']==a.source_head,'PR30 must remain open Draft at exact final head');base=pr['base']['sha'];merge=pr['merge_commit_sha'];need(re.fullmatch('[0-9a-f]{40}',merge or ''),'Actual tested merge not available')
  headmeta=api('source-commit.json','repos/'+REPOSITORY+'/git/commits/'+a.source_head);mm=api('tested-merge-commit.json','repos/'+REPOSITORY+'/git/commits/'+merge);need(headmeta['sha']==a.source_head and headmeta['tree']['sha']==tree,'API source commit/tree mismatch');need(mm['sha']==merge and [p['sha'] for p in mm['parents']]==[base,a.source_head] and mm['tree']['sha']==tree,'Tested merge must have exact base/head parents and equivalent tree')
  result.update(baseHead=base,testedMerge=merge,testedMergeParents=[base,a.source_head],testedMergeTree=tree,sourceBinding=localref)
  checkresp=api('check-runs.json','repos/'+REPOSITORY+'/commits/'+a.source_head+'/check-runs?filter=latest&per_page=100');checkruns=checkresp['check_runs'];need(checkresp['total_count']==len(checkruns)==13 and {x['name'] for x in checkruns}==set(REQUIRED+['CodeQL']) and all(x['head_sha']==a.source_head for x in checkruns),'Expected exact 12 required contexts plus CodeQL aggregate');checkby={x['name']:x for x in checkruns};result['contexts']=[{k:c.get(k) for k in ['id','name','head_sha','status','conclusion','started_at','completed_at','details_url']} for c in checkruns]
  rs=api('workflow-runs.json','repos/'+REPOSITORY+'/actions/runs?head_sha='+a.source_head+'&event=pull_request&per_page=100');need(rs['total_count']==len(rs['workflow_runs'])<=100,'Workflow list incomplete');runs=rs['workflow_runs'];wfout=[];artifact_index=[];log_index=[]
  for key,(name,path,jobnames) in WORKFLOWS.items():
   matches=[r for r in runs if r['name']==name and r['path']==path and r['head_sha']==a.source_head];need(len(matches)==1,'Ambiguous/missing exact workflow run: '+name);run=matches[0];rid=run['id'];attempt=run['run_attempt'];need(run['event']=='pull_request' and any(p['number']==30 and p['head']['sha']==a.source_head and p['base']['sha']==base for p in run['pull_requests']),'Run not bound to exact PR/head/base')
   actual=api(key+'-run.json','repos/'+REPOSITORY+'/actions/runs/'+str(rid));need(actual['head_sha']==a.source_head and actual['run_attempt']==attempt,'Run changed during capture');jr=api(key+'-jobs.json','repos/'+REPOSITORY+'/actions/runs/'+str(rid)+'/attempts/'+str(attempt)+'/jobs?per_page=100');jobs=jr['jobs'];need(jr['total_count']==len(jobs)==len(jobnames) and {j['name'] for j in jobs}==set(jobnames),'Missing/ambiguous job set: '+name)
   for j in jobs:need(j['head_sha']==a.source_head and j['run_id']==rid and j['run_attempt']==attempt and j['id']==checkby[j['name']]['id'] and j['status']==checkby[j['name']]['status'] and j['conclusion']==checkby[j['name']]['conclusion'],'Check/job/run/attempt mismatch: '+j['name'])
   wf={'workflow':name,'key':key,'runId':rid,'runAttempt':attempt,'run':ref(a.out/(key+'-run.json')),'jobs':ref(a.out/(key+'-jobs.json')),'status':actual['status'],'conclusion':actual['conclusion'],'jobNames':jobnames};wfout.append(wf)
   if key=='security':continue
   if actual['status']!='completed':wf['rawCapture']='PENDING_TERMINAL_RUN';continue
   logs=api(key+'-logs.zip','repos/'+REPOSITORY+'/actions/runs/'+str(rid)+'/attempts/'+str(attempt)+'/logs',True);memberindex=[];texts={};checkout=[]
   with checked_zip(logs) as z:
    for n in sorted(z.namelist()):
     b=z.read(n);zi=z.getinfo(n);memberindex.append({'member':n,'bytes':len(b),'sha256':sha(b),'crc32':format(zi.CRC,'08x')});texts[n]=b.decode('utf-8','replace')
    for job in jobnames:
     options=[n for n in texts if n.startswith(job+'/') and 'Checkout' in n];matched=[]
     for n in options:
      lines=[{'line':i+1,'text':s} for i,s in enumerate(texts[n].splitlines()) if merge in s or ('HEAD is now at '+merge[:7]+' Merge '+a.source_head) in s]
      if lines:matched.append({'member':n,'memberSha256':sha(z.read(n)),'lines':lines})
     need(len(matched)==1,'Missing/ambiguous tested checkout log: '+job);checkout.append({'jobName':job,'testedMerge':merge,'evidence':matched[0]})
   log_index.append({'workflow':name,'runId':rid,'attempt':attempt,'archive':ref(logs),'members':memberindex,'actualCheckoutEvidence':checkout});wf['logs']=ref(logs)
   ars=api(key+'-artifacts.json','repos/'+REPOSITORY+'/actions/runs/'+str(rid)+'/artifacts?per_page=100');need(ars['total_count']==len(ars['artifacts'])<=100,'Artifact list incomplete');wf['artifactApi']=ref(a.out/(key+'-artifacts.json'))
   for aname,jobname in ARTIFACT_JOBS[key].items():
    upload=[]
    for n,t in texts.items():
     if not n.startswith(jobname+'/'):continue
     ids=re.findall(r'Artifact '+re.escape(aname)+r' successfully finalized\. Artifact ID (\d+)',t);digests=re.findall(r'SHA256 digest of uploaded artifact is ([0-9a-f]{64})',t)
     if ids:need(len(ids)==len(digests)==1,'Ambiguous artifact upload log');upload.append((n,int(ids[0]),digests[0]))
    need(len(upload)==1,'Missing exact current-attempt artifact upload: '+aname);n,aid,digest=upload[0];metas=[m for m in ars['artifacts'] if m['id']==aid];need(len(metas)==1,'Artifact ID absent in official metadata');meta=metas[0];need(meta['name']==aname and not meta['expired'] and meta['workflow_run']['id']==rid and meta['workflow_run']['head_sha']==a.source_head and meta['digest']=='sha256:'+digest,'Artifact metadata/source/log digest mismatch')
    archive=api(aname+'.zip','repos/'+REPOSITORY+'/actions/artifacts/'+str(aid)+'/zip',True);need(ref(archive)['sha256']==digest and archive.stat().st_size==meta['size_in_bytes'],'Official artifact size/digest mismatch');members=[]
    with checked_zip(archive) as z:
     for mn in sorted(z.namelist()):
      b=z.read(mn);zi=z.getinfo(mn);members.append({'member':mn,'bytes':len(b),'sha256':sha(b),'crc32':format(zi.CRC,'08x')})
    job=next(j for j in jobs if j['name']==jobname);artifact_index.append({'officialMetadata':meta,'workflow':name,'runId':rid,'attempt':attempt,'jobId':job['id'],'jobName':jobname,'archive':ref(archive),'uploadLog':{'archive':ref(logs),'member':n,'memberSha256':next(m['sha256'] for m in memberindex if m['member']==n),'artifactId':aid,'digest':digest},'members':members})
  for wf in wfout:
   current=api(wf['key']+'-run-after.json','repos/'+REPOSITORY+'/actions/runs/'+str(wf['runId']));need(current['head_sha']==a.source_head and current['run_attempt']==wf['runAttempt'] and current['status']==wf['status'] and current['conclusion']==wf['conclusion'],'Workflow/attempt changed during capture: '+wf['workflow'])
  result['workflows']=wfout;result['artifactIndex']=save(a.out/'OFFICIAL-ARTIFACT-MEMBER-INDEX.json',{'sourceHead':a.source_head,'testedMerge':merge,'artifacts':artifact_index});result['logIndex']=save(a.out/'RAW-LOG-MEMBER-INDEX.json',{'sourceHead':a.source_head,'testedMerge':merge,'archives':log_index})
  finalpr=api('pr30-after.json','repos/'+REPOSITORY+'/pulls/30');need((finalpr['head']['sha'],finalpr['base']['sha'],finalpr['merge_commit_sha'],finalpr['draft'],finalpr['state'])==(a.source_head,base,merge,True,'open'),'PR identity/state changed during capture')
  cr2=api('check-runs-after.json','repos/'+REPOSITORY+'/commits/'+a.source_head+'/check-runs?filter=latest&per_page=100');need({(x['id'],x['name'],x['status'],x['conclusion']) for x in cr2['check_runs']}=={(x['id'],x['name'],x['status'],x['conclusion']) for x in checkruns},'CI states/attempts changed; preserve this snapshot and use a new output directory')
  need(ref(__file__)==inputs['captureTool'] and ref(a.inventory)==inputs['sourceInventory'],'Capture inputs changed');need(git('rev-parse','HEAD').decode().strip()==a.source_head and not git('status','--porcelain','--untracked-files=all').strip(),'Local identity changed');result['allRequiredAndAggregateSucceeded']=all(c['status']=='completed' and c['conclusion']=='success' for c in checkruns);result['result']='CAPTURE_COMPLETE_REQUIRES_BACKEND_AND_SECURITY_REVIEW' if result['allRequiredAndAggregateSucceeded'] else 'CAPTURE_PENDING_OR_FAILED_NO_SUCCESS_CLAIM';exitcode=0 if result['allRequiredAndAggregateSucceeded'] else 3
 except Exception as e:
  result['result']='CAPTURE_REFUSED_RAW_RETAINED';result['error']=type(e).__name__+': '+str(e);exitcode=2
 result.update(finishedAt=now(),elapsedSeconds=round(time.monotonic()-clock,3),commandReceipts=commands,exitCode=exitcode,rawPublicationSafetyReview='NOT_PERFORMED',proofsCreated=0);receipt=save(a.out/'CI-CAPTURE-RECEIPT.json',result);print(json.dumps({'receipt':receipt,'result':result['result'],'exitCode':exitcode}));return exitcode
if __name__=='__main__':
 if not __debug__:raise RuntimeError('Optimized Python disables evidence guards')
 sys.exit(main())
