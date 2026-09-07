import json,hashlib,subprocess,datetime
from pathlib import Path
O=Path(__file__).resolve().parent;R=Path('/Users/chzhengx/Code/personal/marketops-platform');B=Path('/tmp/slice3-final-execution-e278b1e-r6');G=B/'governance-r2';P=G/'raw/build/final-gate-r6-e278b1e/governance-r2';E=Path('/tmp/slice3-registered-inputs-e278b1e-r1/EXPECTED-SOURCE.json');H='e278b1e3d8541aeb806e41d6cbef4deac8d16d06';T='178132dd32a92e59e320eb100774b5bb9f6fb248';I='73cc1dc47aebd86cf93de3afbdfe4d905b6c5b4f8b1335488f28ca63fe963eda';PRE='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/'
sha=lambda b:hashlib.sha256(b).hexdigest()
def read(p):return json.loads(Path(p).read_text())
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def checked(e):p=Path(e['path']);assert ref(p)['sha256']==e['sha256'];assert 'bytes'not in e or p.stat().st_size==e['bytes'];return p
expected=read(E);extra={e['path']:e for e in expected['additionalExecutionInputs']};assert len(extra)==8
parents={}
for e in extra.values():
 assert e['sourceHead']==H and e['sourceTree']==T
 raw=subprocess.check_output(['git','--no-replace-objects','show',H+':'+e['path']],cwd=R);oid=subprocess.check_output(['git','rev-parse',H+':'+e['path']],cwd=R).decode().strip();assert sha(raw)==e['sha256'] and oid==e['gitBlobOid']
 path=checked(e['executionReceipt']);d=read(path);assert (d['sourceHead'],d['sourceTree'],d['sourceInventorySha256'])==(H,T,I) and type(d['exitCode'])is int and d['exitCode']==0 and d['sourceStable']is True
 assert datetime.datetime.fromisoformat(d['startedAt'])<datetime.datetime.fromisoformat(d['finishedAt'])
 for rr in d['evidence']:checked(rr)
 parents[str(path.resolve())]=d
assert len(parents)==2
bp=B/'binder-synthetic-r2/receipt.json';binder=read(bp);namedp=checked(next(e for e in binder['evidence']if e['path'].endswith('/named-tests.json')));named=read(namedp)
assert binder['inputsBefore']==binder['inputsAfter'] and binder['observerInputsBefore']==binder['observerInputsAfter']
for rr in binder['inputsBefore']+binder['observerInputsBefore']:checked(rr)
assert binder['frameworkCounts']==named['frameworkCounts']=={'testsRun':106,'failures':0,'errors':0,'skipped':0,'expectedFailures':0,'unexpectedSuccesses':0}
assert named['allRecordedSourcesStable']is True and named['frameworkWasSuccessful']is True and named['unexecutedIds']==[] and len(named['methodsAndFrameworkEvents'])==106
for n in named['methodsAndFrameworkEvents']:
 assert n['status']=='PASSED';assert extra[PRE+'assessment_tools/'+n['sourcePath']]['sha256']==n['sourceSha256']
parent=read(G/'layer-candidate.json');members={(str(Path(e['path']).resolve()),e['sha256'])for e in parent['evidence']}
def member(p):rr=ref(p);assert (rr['path'],rr['sha256'])in members;return rr
cp=P/'assessment-tools-checks.json';c=read(cp);member(cp);assert len(c['checks'])==24 and all(x['passed']is True for x in c['checks']) and c['productVerificationEvidence']is False and c['closureClaimMade']is False
assert c['toolSha256']==extra[PRE+'assessment_tools/assemble_assessment_with_structured.py']['sha256'] and c['checkSha256']==extra[PRE+'assessment_tools/check_structured_adapter.py']['sha256']
argv='\n'.join(parent['argv']);assert 'python3 '+PRE+'assessment_tools/check_structured_adapter.py --out 'in argv and 'python3 '+PRE+'reconcile_measurements.py --check 'in argv
rp=P/'historical-measurement-reconciliation.json';reconcile=read(rp);member(rp);canonical=R/PRE/'CV-E-MEASUREMENT-RECONCILIATION.json';assert rp.read_bytes()==subprocess.check_output(['git','--no-replace-objects','show',H+':'+canonical.relative_to(R).as_posix()],cwd=R)
assert len(reconcile['measurements'])==5 and reconcile['productionWriteEnabled']is False and bool(reconcile['newMeasurementsRequired'])
for p in [P/'historical-measurement-check.log',P/'assessment-tools-check.log']:member(p)
report={'kind':'ACTUAL_SAME_COMMIT_EVIDENCE_TOOL_EXECUTION_CROSSCHECK','sourceHead':H,'sourceTree':T,'sourceInventorySha256':I,'registeredExpectedSource':ref(E),'registeredSourceCount':8,'sources':list(extra.values()),'executions':[{'kind':'unittest','receipt':ref(bp),'raw':ref(namedp),'actualCount':106,'actualInputBindings':binder['repositoryGitBindings'],'argv':binder['argv']},{'kind':'structured','receipt':ref(G/'layer-candidate.json'),'raw':ref(cp),'actualCount':24,'argv':parent['argv']},{'kind':'reconcile','receipt':ref(G/'layer-candidate.json'),'raw':ref(rp),'actualHistoricalMeasurements':5,'argv':parent['argv']}],'actualReconciliation':ref(rp),'allActualRawMembersRehashed':True,'allEightExactCurrentGitSourceBindingsVerified':True,'noSeparateDerivationIdentityInferred':True,'limits':['Actual106 named tool tests and24 structured boundary checks add zero product tests.','Current --check executes the historical reconciliation validator against the same archived five measurements, not new performance measurements.','Current raw command success and explicit source input identity are required; old D receipts are not relabelled.'],'proofSelections':[],'engineeringClosureClaimMade':False}
(O/'EVIDENCE-TOOL-RAW-CROSSCHECK.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'review':ref(O/'EVIDENCE-TOOL-RAW-CROSSCHECK.json'),'currentSourceBindings':8,'toolTests':106,'structuredChecks':24,'historicalMeasurements':5}))
