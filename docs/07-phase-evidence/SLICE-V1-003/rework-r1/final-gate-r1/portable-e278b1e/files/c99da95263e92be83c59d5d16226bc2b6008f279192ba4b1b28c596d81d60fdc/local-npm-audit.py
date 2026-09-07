#!/usr/bin/env python3
"""Prepared read-only npm audit collector. No install/build/fix; only /tmp outputs."""
import argparse,datetime,hashlib,json,os,pathlib,shutil,subprocess,sys,tempfile,time,traceback
CONFIG=json.loads(pathlib.Path(__file__).with_name('CONFIG.json').read_bytes())
ROOT=pathlib.Path(CONFIG['repo'])
FRONTEND=ROOT/'frontend/marketops-console'
NODEBIN=pathlib.Path(CONFIG['nodeBin'])
LOCK='frontend/marketops-console/package-lock.json'
PACKAGE='frontend/marketops-console/package.json'
BRANCH='feat/SLICE-V1-003-advertising-traffic-efficiency'

def digest(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def write(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def ref(p):return {'path':str(p.resolve()),'sha256':digest(p),'bytes':p.stat().st_size}
def main():
 ap=argparse.ArgumentParser(description=__doc__)
 ap.add_argument('--expected-source',type=pathlib.Path,required=True)
 ap.add_argument('--security-receipt',type=pathlib.Path,required=True)
 ap.add_argument('--output',type=pathlib.Path,required=True)
 args=ap.parse_args();out=args.output.resolve()
 assert out.is_relative_to(pathlib.Path('/private/tmp')) and out!=pathlib.Path('/private/tmp'),'Output must be a new owned /tmp directory'
 out.mkdir(mode=0o700,parents=False,exist_ok=False);os.umask(0o077)
 raw=out/'raw';raw.mkdir();private=pathlib.Path(tempfile.mkdtemp(prefix='slice3-npm-audit-private-',dir='/tmp'))
 (private/'npm-user.rc').write_text('');(private/'npm-global.rc').write_text('');(private/'tmp').mkdir()
 # Do not inherit user/proxy/registry credentials, Provider settings or an executable PATH.
 env={'PATH':str(NODEBIN)+':/usr/bin:/bin:/usr/sbin:/sbin','HOME':str(pathlib.Path.home()),'USER':pathlib.Path.home().name,'LOGNAME':pathlib.Path.home().name,'LANG':'C.UTF-8','TZ':'UTC','CI':'true','TMPDIR':str(private/'tmp')+'/',
      'NPM_CONFIG_USERCONFIG':str(private/'npm-user.rc'),'NPM_CONFIG_GLOBALCONFIG':str(private/'npm-global.rc'),'NPM_CONFIG_CACHE':str(private/'npm-cache'),'NPM_CONFIG_REGISTRY':'https://registry.npmjs.org/',
      'NPM_CONFIG_AUDIT':'true','NPM_CONFIG_IGNORE_SCRIPTS':'true','NPM_CONFIG_FUND':'false','NPM_CONFIG_COLOR':'false','NPM_CONFIG_UPDATE_NOTIFIER':'false','GIT_OPTIONAL_LOCKS':'0'}
 shutil.copyfile(pathlib.Path(__file__),out/'execution-input.py')
 commands=[];started=now();startedMono=time.monotonic();audit=None;before=None;after=None;expected=None;parentSha=None;error=None;failureKind=None;exitValue=2
 def run(argv,label,cwd=ROOT,timeout=90):
  stdout=raw/('npm-audit.json' if label=='npm-audit' else label+'.stdout');stderr=raw/(label+'.stderr');record={'argv':list(argv),'cwd':str(cwd),'startedAt':now(),'stdout':str(stdout),'stderr':str(stderr)};t=time.monotonic();commands.append(record)
  try:
   with stdout.open('wb') as so,stderr.open('wb') as se:
    r=subprocess.run(argv,cwd=cwd,env=env,stdout=so,stderr=se,timeout=timeout)
   record['exitCode']=r.returncode;record['completed']=True
  except subprocess.TimeoutExpired:
   record.update(exitCode=None,completed=False,timedOut=True)
  finally:
   record.update(finishedAt=now(),elapsedSeconds=round(time.monotonic()-t,6));write(out/'commands.json',commands)
  return record,stdout.read_bytes()
 def git(*argv):
  n=sum(1 for c in commands if c['argv'][0]=='/usr/bin/git');rec,b=run(['/usr/bin/git',*argv],f'git-{n:02d}')
  if rec['exitCode']!=0:raise ValueError('Read-only Git identity command failed; see raw command receipt')
  return b.decode().strip()
 def snapshot(stage):
  observedHead=git('rev-parse','HEAD');observedTree=git('rev-parse','HEAD^{tree}');branch=git('branch','--show-current');status=git('status','--porcelain=v1','--untracked-files=all')
  rows=[]
  for item in inventory['files']:
   p=ROOT/item['path'];safe=p.is_file() and p.resolve().is_relative_to(ROOT)
   actual=digest(p) if safe else None
   rows.append({'path':item['path'],'expectedSha256':item['sha256'],'actualSha256':actual,'bytes':p.stat().st_size if safe else None,'matches':safe and actual==item['sha256']})
  good=observedHead==expected['sourceHead'] and observedTree==expected['sourceTree'] and branch==BRANCH and not status and all(r['matches'] for r in rows)
  value={'at':now(),'sourceHead':observedHead,'sourceTree':observedTree,'branch':branch,'workingTreeClean':not status,'workingTreeStatus':status.splitlines(),'expectedInventorySha256':expected['inventory']['sha256'],'fileCount':len(rows),'allSourceInputsMatch':good,'files':rows,'lockSha256':digest(ROOT/LOCK),'packageSha256':digest(ROOT/PACKAGE)}
  installed=FRONTEND/'node_modules/.package-lock.json';value['installedTreeLock']=ref(installed) if installed.is_file() else None
  write(out/f'source-{stage}.json',value);return value
 try:
  expected=json.loads(args.expected_source.read_bytes());parent=json.loads(args.security_receipt.read_bytes());parentSha=digest(args.security_receipt)
  assert parent['sourceHead']==expected['sourceHead'] and parent['sourceTree']==expected['sourceTree'],'Security receipt belongs to another checkpoint'
  assert parent['executionSourceInventory']['sha256']==expected['inventory']['sha256'],'Security source inventory differs'
  inv=pathlib.Path(expected['inventory']['path']);assert digest(inv)==expected['inventory']['sha256'],'Expected inventory was altered'
  inventory=json.loads(inv.read_bytes());paths={r['path']:r for r in inventory['files']};assert LOCK in paths and PACKAGE in paths
  shutil.copyfile(inv,out/'executed-source-inventory.json');shutil.copyfile(args.expected_source,out/'expected-source.json')
  write(out/'parent-security-reference.json',{'kind':'IMMUTABLE_EXTERNAL_PARENT_REFERENCE','receipt':ref(args.security_receipt),'sourceHead':parent['sourceHead'],'sourceTree':parent['sourceTree'],'criteriaCopiedOrModified':False,'localAuditIsAdditionalEvidence':True})
  before=snapshot('before');assert before['allSourceInputsMatch'],'Exact clean source precondition failed; npm audit was not run'
  for tool,argsv,want in [('node',['--version'],'v24.19.0'),('npm',['--version'],None)]:
   rec,b=run([str(NODEBIN/tool),*argsv],tool+'-version');assert rec['exitCode']==0,'Pinned tool unavailable'
   if want:assert b.decode().strip()==want,'Unexpected Node runtime'
  write(out/'tool-identity.json',{'nodeExecutable':ref(NODEBIN/'node'),'npmEntrypoint':ref((NODEBIN/'npm').resolve()),'nodeVersionRaw':ref(raw/'node-version.stdout'),'npmVersionRaw':ref(raw/'npm-version.stdout'),'wrapper':ref(out/'execution-input.py')})
  audit,_=run([str(NODEBIN/'npm'),'audit','--json'],'npm-audit',cwd=FRONTEND,timeout=600)
  # Preserve bytes unchanged. Network and advisory errors are never converted to zero.
  after=snapshot('after')
  assert digest(args.security_receipt)==parentSha,'Immutable parent Security receipt changed during this command'
  sourceStable=before['allSourceInputsMatch'] and after['allSourceInputsMatch'] and before['files']==after['files'] and before['lockSha256']==after['lockSha256'] and before['packageSha256']==after['packageSha256'] and before['installedTreeLock']==after['installedTreeLock']
  doc=json.loads((raw/'npm-audit.json').read_bytes());counts=doc.get('metadata',{}).get('vulnerabilities');countsValid=isinstance(counts,dict) and all(type(counts.get(k)) is int and counts[k]>=0 for k in ('info','low','moderate','high','critical','total')) and counts['total']==sum(counts[k] for k in ('info','low','moderate','high','critical'))
  success=audit['completed'] and audit['exitCode']==0 and sourceStable and countsValid and not doc.get('error')
  exitValue=(0 if success else audit['exitCode'] if audit['exitCode'] not in (None,0) else 2)
  receipt={'id':'security_npm_audit','kind':'EXACT_SOURCE_LOCAL_NPM_AUDIT_SUPPLEMENT','runId':out.name,'sourceHead':expected['sourceHead'],'sourceTree':expected['sourceTree'],'sourceInventorySha256':expected['inventory']['sha256'],'startedAt':started,'finishedAt':now(),'elapsedSeconds':round(time.monotonic()-startedMono,6),'command':audit,'exitCode':audit['exitCode'],'sourceStable':sourceStable,'result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED' if success else 'COMMAND_FAILED_REVIEW_REQUIRED','npmAuditJsonValid':True,'npmAuditCountsValid':countsValid,'npmAuditVulnerabilities':counts,'npmReportedError':bool(doc.get('error')),'lockSha256':before['lockSha256'],'packageSha256':before['packageSha256'],'immutableSecurityParent':{'path':str(args.security_receipt.resolve()),'sha256':parentSha},'sourceIdentityBefore':ref(out/'source-before.json'),'sourceIdentityAfter':ref(out/'source-after.json'),'rawAuditJson':ref(raw/'npm-audit.json'),'rawAuditStderr':ref(raw/'npm-audit.stderr'),'productionWriteEnabled':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'limitations':['Point-in-time npm public-registry advisory query for this exact frontend lock and current installed-tree lock if present. No install, fix, build, lifecycle script, Provider or database command is executed.','Existing Security CI criteria and sealed receipt are not modified. CodeQL, Dependency Review, default-branch Dependabot and local npm audit remain separately scoped.','A successful query is not a final closure or zero-vulnerabilities assertion for other branches, ecosystem packages, other checkpoints or unknown advisories.','Raw audit metadata and nonzero exit are preserved. Publication review and exact source applicability review remain required.']}
 except Exception as e:
  error=type(e).__name__+': '+str(e);failureKind='AUDIT_OR_POSTCONDITION_FAILED' if audit else 'PRECONDITION_FAILED_AUDIT_NOT_RUN'
  if expected and before and after is None:
   try:after=snapshot('after')
   except Exception:pass
  # No synthetic vulnerability counts on a parsing/network/preflight failure.
  receipt={'id':'security_npm_audit','kind':'EXACT_SOURCE_LOCAL_NPM_AUDIT_SUPPLEMENT','runId':out.name,'sourceHead':expected.get('sourceHead') if expected else None,'sourceTree':expected.get('sourceTree') if expected else None,'sourceInventorySha256':expected.get('inventory',{}).get('sha256') if expected else None,'startedAt':started,'finishedAt':now(),'elapsedSeconds':round(time.monotonic()-startedMono,6),'command':audit,'exitCode':audit.get('exitCode') if audit else None,'sourceStable':False,'result':failureKind,'failure':error,'npmAuditVulnerabilities':None,'immutableSecurityParentSha256':parentSha,'productionWriteEnabled':False,'engineeringClosureClaimMade':False}
 finally:
  shutil.rmtree(private) # Only this mkdtemp-owned configuration/cache directory.
 receipt['evidence']=[ref(p) for p in sorted(out.rglob('*')) if p.is_file()]
 write(out/'receipt.json',receipt)
 write(out/'ARTIFACT-INDEX.json',{'kind':'LOCAL_AUDIT_RAW_AND_DERIVED_EVIDENCE_INDEX','files':[ref(p) for p in sorted(out.rglob('*')) if p.is_file()],'archiveCreated':False,'publicationScanPerformed':False})
 print(json.dumps({'receipt':str(out/'receipt.json'),'result':receipt['result'],'exitCode':receipt['exitCode'],'sourceStable':receipt['sourceStable']}))
 return exitValue
if __name__=='__main__':sys.exit(main())
