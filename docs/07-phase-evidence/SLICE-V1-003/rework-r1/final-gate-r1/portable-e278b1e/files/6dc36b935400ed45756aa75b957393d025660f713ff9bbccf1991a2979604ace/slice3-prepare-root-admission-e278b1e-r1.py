"""Prepare root-admitted current evidence, after exact independent raw row audit.
Does not copy files or invoke finalizer; publication and final containing CI remain separate.
"""
import json,hashlib,datetime,copy
from pathlib import Path
R=Path('/Users/chzhengx/Code/personal/marketops-platform');O=Path('/tmp/slice3-root-admission-e278b1e-r1');assert not O.exists();O.mkdir()
H='e278b1e3d8541aeb806e41d6cbef4deac8d16d06';T='178132dd32a92e59e320eb100774b5bb9f6fb248';I='73cc1dc47aebd86cf93de3afbdfe4d905b6c5b4f8b1335488f28ca63fe963eda'
def read(p):return json.loads(Path(p).read_text())
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
def put(n,d):p=O/n;p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n');return p
A=Path('/tmp/slice3-final-assessment-e278b1e-r2');cfgp=Path('/tmp/slice3-publication-observation-e278b1e-r2/EXECUTION-INPUTS.json');cfg=read(cfgp);expectedp=Path('/tmp/slice3-registered-inputs-e278b1e-r1/EXPECTED-SOURCE.json');expected=read(expectedp);ap=Path('/tmp/slice3-final-row-audit-e278b1e-r2/ROW-AUDIT.json');audit=read(ap);rootp=Path('/tmp/slice3-root-selected-evidence-e278b1e-r3/ROW-SCOPE-REVIEW.json');root=read(rootp);catalog=read(A/'CURRENT-NODE-CATALOG.json');m=read(A/'EXECUTION-MANIFEST-CANDIDATE.json');clauses=read(A/'FROZEN-CLAUSE-ASSESSMENT-CANDIDATE.json')
assert audit['mechanicalIssues']==[] and audit['inputsStable'] is True and audit['mechanicallyCheckedRowCounts']=={'criteria':200,'findings':22,'clauses':115,'verificationChecks':5}
for key,path in [('slots',A/'ASSESSMENT-SLOTS.json'),('nodeCatalog',A/'CURRENT-NODE-CATALOG.json'),('rootSelections',Path('/tmp/slice3-root-selected-evidence-e278b1e-r3/ROOT-PROOF-SELECTIONS.json')),('clausesCandidate',A/'FROZEN-CLAUSE-ASSESSMENT-CANDIDATE.json'),('manifestCandidate',A/'EXECUTION-MANIFEST-CANDIDATE.json'),('layerConfig',cfgp)]:assert audit['inputs'][key]==ref(path)
assert catalog['issues']==[] and read(A/'ASSEMBLY-BLOCKERS.json')['issues']==[]
for d in [audit,root,catalog]:assert [d[k] for k in ['sourceHead','sourceTree','sourceInventorySha256']]==[H,T,I]
assert len(root['rows'])==342 and len(clauses['entries'])==115
dest=cfg['artifactDestinations'];prefix='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/portable-e278b1e/files/';required={}
def publish(r):
 p=Path(r['path']);p=p if p.is_absolute() else R/p;p=p.resolve();actual=ref(p);assert actual['sha256']==r['sha256'];target=dest.get(str(p))
 if target is None:target=p.relative_to(R).as_posix() if p.is_relative_to(R) else prefix+actual['sha256']+'/'+p.name
 required[str(p)]={'original':actual,'repositoryPath':target};return {'path':target,'sha256':actual['sha256']}
review_paths={
'commonRaw':'/tmp/slice3-structured-crosschecks-e278b1e-r1/COMMON-LAYER-RAW-CROSSCHECKS.json',
'evidenceTools':'/tmp/slice3-structured-crosschecks-e278b1e-r1/EVIDENCE-TOOL-RAW-CROSSCHECK.json',
'securityRaw':'/tmp/slice3-structured-crosschecks-e278b1e-r1/SECURITY-RAW-CROSSCHECK.json',
'mixedRaw':'/tmp/slice3-structured-crosschecks-e278b1e-r1/MIXED-DYNAMIC-RAW-CROSSCHECK.json',
'allTypedScopes':'/tmp/slice3-structured-crosschecks-e278b1e-r1/ALL-STRUCTURED-SCOPE-REVIEWS.json',
'capacity':'/tmp/slice3-capacity-review-e278b1e-r6/CAPACITY-REVIEW.json',
'migration':'/tmp/slice3-migration-crosscheck-e278b1e-r1/REVIEW.json',
'backendCI':'/tmp/slice3-checkpoint-ci-e278b1e-all-r1/BACKEND-CI-CONTAINING-RECEIPT.json',
'otherCI':'/tmp/slice3-checkpoint-ci-e278b1e-all-r1/NONSECURITY-CI-CONTAINING-RECEIPT.json',
'publicationObservations':'/tmp/slice3-publication-observation-e278b1e-r2/ROOT-PUBLICATION-OBSERVATION-REVIEW.json'}
reviews={k:publish(ref(p)) for k,p in review_paths.items()}
scope={
'backend_full':'FULL_RELEVANT_VERIFICATION',
'frontend_quality':'COMPLETE_FRONTEND_LINT_FORMAT_TYPE_UNIT_COVERAGE_POSITIVE_NEGATIVE_BUNDLE_BUILD',
'browser':'COMPLETE_37_REAL_APPLICATION_BROWSER_TESTS_25_LEGACY_12_ADVERTISING',
'governance':'COMPLETE_421_GOVERNANCE_TESTS_AND_CURRENT_106_TOOL_TESTS_24_STRUCTURED_CHECKS_SEPARATELY_COUNTED',
'infrastructure':'COMPLETE_MOCK_ONLY_INFRASTRUCTURE_VALIDATION',
'migration':'DISPOSABLE_ISOLATED_LOCAL_SAME_BACKEND_JAR_73_MIGRATIONS_POSITIVE_AND_REFUSAL_PATHS',
'supply_chain':'SAME_COMPLETED_BACKEND_JAR_SBOM_LICENSE_NPM_AUDIT_AND_BUILD_METADATA',
'mixed_capacity':'REPRESENTATIVE_MIXED_CAPACITY_SCOPE_OF_COMPLETED_BACKEND_NO_EXTRA_TEST_COUNT',
'security':'EXACT_CURRENT_SECURITY_RUN_RAW_SARIF_AND_EXPLICIT_SEPARATE_GITHUB_READBACK_OBSERVATIONS'}
observations={
'backend_full':{'passed':2769,'unitPassed':1630,'integrationPassed':1139,'elapsedSeconds':2573.881},
'frontend_quality':{'passed':358,'testFiles':22},
'browser':{'passed':37,'legacyPassed':25,'advertisingPassed':12},
'governance':{'governancePassed':421,'separateEvidenceToolTestsPassed':106,'separateStructuredChecksPassed':24,'countsMustNotBeSummedWithProductTests':True},
'infrastructure':{'mockGroupsPassed':[9,13,7]},
'migration':{'migrationFiles':73,'sameActualBackendJar':True,'disposableLocalOnly':True},
'supply_chain':{'actualBackendJarPreserved':True,'npmVulnerabilities':0},
'mixed_capacity':{'backendTestAlreadyCounted':1,'additionalTests':0,'objects':1000,'criticalSamples':1040,'p95Ms':226605,'maximumMs':263105,'sweepMs':116776},
'security':{'workflowRunId':34025462624,'jobs':3,'aggregateCodeQL':True,'rawSarifResults':100,'openQualityAlerts':95,'historicalDismissedHigh':5,'newHighCritical':0,'alertsChanged':False}}
allrows=m['criteria']+m['findings']+m['verificationChecks']+clauses['entries'];layers=[]
for spec in cfg['layers']:
 name=spec['id'];rp=Path(spec['receipt']);raw=read(rp);cl=next(l for l in catalog['layers'] if l['id']==name);assert cl['terminalSuccessfulCommandObserved'] is True and cl['exactSourceIdentityMatches'] is True
 security=name=='security';assert [raw[k] for k in ['sourceHead','sourceTree','sourceInventorySha256']]==[H,T,I]
 if security:
  assert all(raw['criteria'].values()) and raw['run']['status']=='completed' and raw['run']['conclusion']=='success'
  command='GitHub Actions Security workflow at exact H; original read-only collection and root SARIF/alert/source review receipts. Additional publication observations retain their separate collector argv/timestamps.';runid=str(raw['run']['id']);start=raw['run']['run_started_at'];end=raw['run']['updated_at']
 else:
  assert raw['result']=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and raw['sourceStable'] is True and type(raw['exitCode']) is int and raw['exitCode']==0 and raw['finishedAt'];command=raw['command'];runid=raw['runId'];start=raw['startedAt'];end=raw['finishedAt']
 refs=[publish(ref(rp))];refs+=[p['evidence'] for row in allrows for p in row['proofs'] if p['layer']==name]
 # Complete original full JUnit, not merely selected passing methods.
 for r in raw.get('evidence',[]):
  pp=Path(r['path'])
  if pp.suffix=='.xml' or pp.name in ['command.log','runtime-resources.json','jacoco.xml']:refs.append(publish(r))
 for key in ['sourceInventory','executionSourceInventory']:
  if isinstance(raw.get(key),dict):refs.append(publish(raw[key]))
 refs=list({(r['path'],r['sha256']):r for r in refs}.values())
 layers.append({'id':name,'result':'PASS','command':command,'runId':runid,'scope':scope[name],'sourceHead':H,'sourceTree':T,'sourceInventorySha256':I,'startedAt':start,'finishedAt':end,'failures':0,'errors':0,'skipped':0,'originalReceipt':publish(ref(rp)),'evidence':refs,'measuredObservations':observations[name],'rootReview':'Original terminal scope and selected proof semantics reviewed by Codex /root; historical checkpoints and separate repeated CI integration nodes are not added to current local counts.'})
assert len(layers)==9
m.update(status='COMPLETE',engineeringClosureClaimMade=True,source={'sourceHead':H,'sourceTree':T,'identityScope':'CLEAN_COMMIT_TREE','inventory':publish(expected['inventory']),'identityEvidence':publish(expected['identityEvidence']),'expectedSourceRegistration':publish(ref(expectedp))},layers=layers)
for row in m['verificationChecks']:assert row['proofLimits'] and {'positive','adverse'}<={p['role'] for p in row['proofs']};row['result']='PASS'
m['rootAdmission']={'reviewer':'Codex /root','reviewedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'rowScopeReview':publish(ref(rootp)),'mandatory115ClauseAudit':publish(ref(ap)),'individualRowCounts':{'criteria':200,'findings':22,'clauses':115,'verificationChecks':5},'rawScopeReviews':reviews,'publicationContainingCommitReadback':'REQUIRED_SEPARATE_APPEND_ONLY_EXTERNAL_READBACK','independentControllerVerdict':'PENDING_INDEPENDENT_REVIEW'}
m['boundary']='Engineering admission of measured product H and actual nine completed local/remote scopes, with 342 reviewed row bindings and zero independent mechanical issues. The subsequent evidence-only containing commit must receive its own exact remote CI/source-equivalence readback. No independent Controller approval, Human Owner closure, Ready, merge or production enablement is claimed.'
clauses.update(status='ENGINEERING_REVIEWED_CONTROLLER_PENDING',engineeringClosureClaimMade=True,sourceHead=H,sourceTree=T,sourceInventorySha256=I,rootReview=publish(ref(rootp)),independentMechanicalAudit=publish(ref(ap)),controllerApprovalClaimMade=False,productionWriteEnabled=False)
for row in clauses['entries']:row['result']='ENGINEERING_REVIEWED_CONTROLLER_PENDING'
mp=put('EXECUTION-MANIFEST.json',m);cp=put('FROZEN-CLAUSE-ASSESSMENT.json',clauses)
put('REQUIRED-DIRECT-FILES.json',{'kind':'EXACT_FINALIZER_INPUT_DESTINATIONS','sourceHead':H,'files':list(required.values()),'finalManifest':ref(mp),'finalClauses':ref(cp),'copiedFiles':0})
print(json.dumps({'manifest':ref(mp),'clauses':ref(cp),'requiredDirectFiles':len(required),'layers':len(layers),'productTestsRerun':0,'repositoryFilesWritten':0,'finalContainingCIStillRequired':True}))
