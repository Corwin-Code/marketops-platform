#!/usr/bin/env python3
"""Run unmodified make governance once after the agreed source freeze, with observer results."""
import argparse,datetime,hashlib,json,os,shutil,subprocess,sys,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,default=Path('/Users/chzhengx/Code/personal/marketops-platform'));p.add_argument('--out',type=Path,required=True)
p.add_argument('--expect-head',required=True);p.add_argument('--require-clean',action='store_true');p.add_argument('--execute-frozen-governance',action='store_true');a=p.parse_args()
if not a.execute_frozen_governance:p.error('Explicit --execute-frozen-governance is required; preparation alone does not execute tests')
root=a.repo.resolve();out=a.out.resolve();hook=Path('/tmp/slice3-named-governance-capture')
if out.is_relative_to(root):raise SystemExit('Evidence output must be outside repository')
if out.exists():raise SystemExit('Use a new output directory; do not overwrite run evidence')
sha=lambda b:hashlib.sha256(b).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],cwd=root)
def identity():return {'head':git('rev-parse','HEAD').decode().strip(),'tree':git('rev-parse','HEAD^{tree}').decode().strip(),'status':git('status','--porcelain=v1','--untracked-files=all').decode().splitlines(),'trackedDiffSha256':sha(git('diff','HEAD','--binary'))}
before=identity()
if before['head']!=a.expect_head:raise SystemExit('Expected source Head mismatch')
if a.require_clean and before['status']:raise SystemExit('A clean source is required')
makefile=(root/'Makefile').read_text()
expected="@python3 -m unittest discover -s tests -p 'test_*.py'"
if expected not in makefile:raise SystemExit('Makefile unittest invocation changed; review the observer before running')
out.mkdir();env=dict(os.environ);env['PYTHONDONTWRITEBYTECODE']='1';env['PYTHONPATH']=str(hook)+(os.pathsep+env['PYTHONPATH'] if env.get('PYTHONPATH') else '')
env['SLICE3_NAMED_UNITTEST_OUTPUT']=str(out/'named-unittest-results.json');env['SLICE3_NAMED_REPOSITORY']=str(root)
started=datetime.datetime.now(datetime.timezone.utc).isoformat();clock=time.monotonic()
with (out/'make-governance.log').open('wb') as log:run=subprocess.run(['make','governance'],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
after=identity();named=out/'named-unittest-results.json';data=json.loads(named.read_text()) if named.is_file() else None
record={'kind':'MAKE_GOVERNANCE_WITH_OBSERVATIONAL_NAMED_UNITTEST_EVIDENCE','command':['make','governance'],'cwd':str(root),'startedAtUTC':started,'finishedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsedSeconds':time.monotonic()-clock,'exitCode':run.returncode,'sourceBefore':before,'sourceAfter':after,'sourceBoundaryStable':before==after,'sourceIdentityClass':'COMMIT_COMPLETE_WHEN_CLEAN_OTHERWISE_EXPLICIT_WORKTREE','makefileSha256':sha((root/'Makefile').read_bytes()),'observerSources':[{'path':str(f),'sha256':sha(f.read_bytes())} for f in [Path(__file__),hook/'sitecustomize.py',hook/'slice3_unittest_capture.py']],'logSha256':sha((out/'make-governance.log').read_bytes()),'namedResultsSha256':sha(named.read_bytes()) if named.is_file() else None,'namedEvidenceCaptured':data is not None,'allNamedSourcesStable':data['allRecordedSourcesStable'] if data else False,'testContentsChanged':False,'discoveryAndAssertionsChanged':False,'frameworkCounts':data['frameworkCounts'] if data else None,'criterionAssessment':'NOT_PERFORMED','controllerVerdict':'NOT_ISSUED'}
record['result']='PASSED_WITH_NAMED_EVIDENCE' if run.returncode==0 and data and data['frameworkWasSuccessful'] and data['allRecordedSourcesStable'] and not data['unexecutedIds'] and before==after else 'FAILED_OR_EVIDENCE_INCOMPLETE'
(out/'receipt.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({'out':str(out),'result':record['result'],'exitCode':run.returncode,'frameworkCounts':record['frameworkCounts']}));raise SystemExit(0 if record['result']=='PASSED_WITH_NAMED_EVIDENCE' else run.returncode or 2)
