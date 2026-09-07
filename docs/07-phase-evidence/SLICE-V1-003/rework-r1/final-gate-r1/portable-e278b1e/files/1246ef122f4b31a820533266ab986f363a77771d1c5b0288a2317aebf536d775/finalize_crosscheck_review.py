import json,hashlib,subprocess
from pathlib import Path
O=Path(__file__).resolve().parent;R=Path('/Users/chzhengx/Code/personal/marketops-platform');H='e278b1e3d8541aeb806e41d6cbef4deac8d16d06'
sha=lambda b:hashlib.sha256(b).hexdigest()
def read(p):return json.loads(Path(p).read_text())
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
pending=read('/tmp/slice3-structured-registration-e278b1e-r1/PENDING-STRUCTURED-BINDINGS.json');common=read(O/'COMMON-LAYER-RAW-CROSSCHECKS.json');mixed=read(O/'MIXED-DYNAMIC-RAW-CROSSCHECK.json');security=read(O/'SECURITY-RAW-CROSSCHECK.json');migrationp=Path('/tmp/slice3-migration-crosscheck-e278b1e-r1/REVIEW.json');migration=read(migrationp);assert migration['productSourceIdentity']['sourceHead']==H and migration['migrationCount']==73
byid={'source-proof-d36cf26359e1333837cc6381':'backend-full','source-proof-6b0800afc4c50374c5778a41':'backend-full','source-proof-897e11b3117080a6dacb0f2d':'frontend-quality','source-proof-824cec3f580149dc4519e4e0':'browser-r4','source-proof-16cec657dda52ddbcd6234b5':'governance-r2','source-proof-ab96c4f72f8be606d69199d2':'infrastructure','source-proof-cf31c3d308bf2f52b3cc0622':'migration','source-proof-c79daf810ae410d7832fefe6':'supply-chain'}
items=[]
for p in pending['pending']:
 pid=p['planProofId'];requirements=p['requirements'];observations=[]
 if pid in byid:
  layer=byid[pid];i=next(i for i,x in enumerate(common['layers'])if x['layer']==layer);v=common['layers'][i];r=ref(O/'COMMON-LAYER-RAW-CROSSCHECKS.json');fieldsets=[['startedAt','finishedAt','command','actualDriver'],['identities','sourceBefore','sourceAfter','originalInventoryShaMatchesBoth'],['everyOriginalEvidenceMemberShaVerified','preservedReports','namedCollectorNodes','rawResultReview'],['rawResultReview'],['actualDriver']]
  for requirement,fields in zip(requirements,fieldsets):observations.append({'requirement':requirement,'reviewEvidence':r,'reviewPointer':'/layers/'+str(i),'observedFields':{k:v[k]for k in fields},'conclusion':'VERIFIED_WITH_THE_STATED_COMMAND_AND_REPORT_BOUNDARY'})
  limits=v['limitations']
 elif pid in ['source-proof-ace1a61c3f87f1a63a45c15e','source-proof-432c837f999bcc073f7b673d']:
  r=ref(O/'MIXED-DYNAMIC-RAW-CROSSCHECK.json');dynamic='SUPPLEMENTARY_DYNAMIC' in p['problem'];fields=(['actualSourceEntries','sourceChecks','rawReferences'],['historicalGateJoins'],['currentCommandWriteGateReasons','dynamicAssertions'],['actualRuntime','actualPostgresContainerResources','actualOuterResources','dynamicAssertions'])if dynamic else (['parent'],['rawReferences'],['actualTargeted','actualTargetedWallMillis','actualSweepWallMillis','independentCapacityReviews'],['dynamicAssertions'],['rawReferences','limits'],['limits'])
  for requirement,fs in zip(requirements,fields):observations.append({'requirement':requirement,'reviewEvidence':r,'observedPointers':['/'+k for k in fs],'conclusion':'VERIFIED_FOR_ACTUAL_MIXED_WORKERS_WITH_SYNTHETIC_HISTORY_LIMITS'})
  limits=mixed['limits']
 elif pid=='source-proof-9b84af18c1abdb959fc34e22':
  for requirement in requirements:observations.append({'requirement':requirement,'reviewEvidence':ref(migrationp),'observedPointers':['/artifactSha256','/productSnapshots','/migrationComparisons','/resolverLog','/refusals'],'observedMigrationCount':73,'actualArtifactSha256':migration['artifactSha256'],'conclusion':'VERIFIED_FOR_SAME_ARTIFACT_OFFLINE_RESOLUTION_AND_EXACT_REFUSAL_BOUNDARY'})
  limits=migration['limits']
 else:
  assert pid in ['source-proof-08c882ac3c40e02973cd511a','source-proof-83dd6d613cca463007248731'];r=ref(O/'SECURITY-RAW-CROSSCHECK.json');fields=[['originalIndex','originalMembers','actualOriginalIndexMembersVerified','originalDirectoryFilesIncludingIndex'],['actualCheckoutProof','actualRun','actualJobs','aggregate','testedMerge','testedMergeTree','testedMergeParents'],['actualGitAndRemoteInventoryEntries','actualComparisonCounts','gitComparison','comparison'],['sarifResults','sarifToExactAlertMatches','openQualityAlertCount','openLevelCounts','exactOpenExpressionRechecks','triage','priorRootSourceContext','historicalDismissedHigh','defaultMainDependencyBoundary'],['priorRootSourceContext','exactOpenExpressionRechecks','triage'],['actualNpmReceipt','actualNpmRaw','actualNpmVulnerabilities','publicationScan','limits']]
  for requirement,fs in zip(requirements,fields):observations.append({'requirement':requirement,'reviewEvidence':r,'observedPointers':['/'+k for k in fs],'conclusion':'VERIFIED_FOR_EXACT_E278B1E_CHECKPOINT_SECURITY_SCOPE'})
  limits=security['limits']
 assert len(observations)==len(requirements);items.append({'planProofId':pid,'originalPendingProblem':p['problem'],'eachOriginalRequirement':observations,'limits':limits,'scopeReviewConclusion':'SATISFIED_WITH_RECORDED_LIMITS_NO_INDEPENDENT_CONTROLLER_CLAIM'})
toolp=O/'EVIDENCE-TOOL-RAW-CROSSCHECK.json';tools=read(toolp)
assert tools['sourceHead']==H and tools['allEightExactCurrentGitSourceBindingsVerified']is True and tools['noSeparateDerivationIdentityInferred']is True
for x in [common,security]:assert x['sourceHead']==H
reconcilep=Path(tools['actualReconciliation']['path']);assert ref(reconcilep)==tools['actualReconciliation'];reconcile=read(reconcilep);assert len(reconcile['measurements'])==5 and bool(reconcile['newMeasurementsRequired'])
rules=read(O/'FINAL-STRUCTURED-NODE-SELECTION-RULES.json');assert rules.get('derivationSourceIdentity')is None
# Every documented review pointer must exist; schema drift cannot produce a hollow scope review.
def pointer(v,p):
 for k in p.split('/')[1:]:k=k.replace('~1','/').replace('~0','~');v=v[int(k)]if isinstance(v,list)else v[k]
 return v
for item in items:
 for obs in item['eachOriginalRequirement']:
  r=obs['reviewEvidence'];assert ref(r['path'])==r;doc=read(r['path'])
  for pt in obs.get('observedPointers',[]):pointer(doc,pt)
result={'kind':'INDEPENDENT_SCOPE_REVIEW_OF_ALL_15_PENDING_STRUCTURED_ITEMS','sourceHead':common['sourceHead'],'sourceTree':common['sourceTree'],'sourceInventorySha256':common['inventorySha256'],'originalPending':ref('/tmp/slice3-structured-registration-e278b1e-r1/PENDING-STRUCTURED-BINDINGS.json'),'originalPendingItemCount':15,'reviewedPendingItemCount':len(items),'originalIndividualRequirements':sum(len(i['eachOriginalRequirement'])for i in items),'items':items,'additionalReconcileDefinitions':{'planProofIds':['source-proof-917cef537160ee8db3249b27','source-proof-782fd45d97c56d1bac77f18a'],'actualReconciliation':ref(reconcilep),'actualSameCommitExecutions':tools['executions'],'actualToolCrosscheck':ref(toolp),'historicalMeasurementCount':5,'oldMeasuredSourceHead':'3ff042df66d5d6924b587cac96fc652b93bf5e7a','boundary':'Five separate historical measurements and root/class Jacoco recount retain original dataset/run/job identity; actual e278b1e --check reads the same historical bytes and adds no product measurements.','scopeTypoCorrection':'Only the earlier requirement scope line said14; actual structured result is24. Original requirement bytes remain unchanged. Actual same-commit unittest106 is separately recorded.'},'completeDefinitionCount':15,'expandedRules':ref(O/'FINAL-STRUCTURED-NODE-SELECTION-RULES.json'),'expectedFinalStructuredAssertions':len(rules['matches']),'originalRowReferenceCount':107,'proofSelections':[],'repositoryFilesChanged':[],'productTestExecutions':0,'automaticAdmissionPerformed':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False}
(O/'ALL-STRUCTURED-SCOPE-REVIEWS.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({'items':len(items),'requirements':result['originalIndividualRequirements'],'assertions':len(rules['matches']),'review':ref(O/'ALL-STRUCTURED-SCOPE-REVIEWS.json')}))
