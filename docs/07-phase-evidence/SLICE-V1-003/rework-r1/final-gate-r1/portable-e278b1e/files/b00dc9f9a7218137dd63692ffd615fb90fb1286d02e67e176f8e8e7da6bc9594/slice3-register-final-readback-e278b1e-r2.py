"""Add typed current publication observations, preserving original executions."""
import json, hashlib, datetime, copy, subprocess
from pathlib import Path
R=Path('/Users/chzhengx/Code/personal/marketops-platform'); O=Path('/tmp/slice3-publication-observation-e278b1e-r2'); assert not O.exists(); O.mkdir()
H='e278b1e3d8541aeb806e41d6cbef4deac8d16d06'; T='178132dd32a92e59e320eb100774b5bb9f6fb248'; I='73cc1dc47aebd86cf93de3afbdfe4d905b6c5b4f8b1335488f28ca63fe963eda'; B='08ad7da7d9e75b4ddd1c387a22ac0affba9e1430'; M='196afde14f0d51f88c4b27ac2e7cff7d645b5a1a'
sha=lambda b:hashlib.sha256(b).hexdigest()
def read(p):return json.loads(Path(p).read_text())
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def put(n,d):p=O/n;p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n');return p
def ptr(d,p):
 for t in p.split('/')[1:]:t=t.replace('~1','/').replace('~0','~');d=d[int(t)] if isinstance(d,list) else d[t]
 return d
CI=Path('/tmp/slice3-checkpoint-ci-e278b1e-all-r1')
paths={'pr':CI/'pr30-after.json','checks':CI/'check-runs-after.json','capture':CI/'CI-CAPTURE-RECEIPT.json','sourceEquivalence':CI/'LOCAL-CONTAINING-SOURCE-EQUIVALENCE.json','officialArtifactIndex':CI/'OFFICIAL-ARTIFACT-MEMBER-INDEX.json','backendArchiveIndex':Path('/tmp/slice3-backend-checkpoint-archive-e278b1e-r6/e278b1e-INDEX.json'),'localArchiveIndex':Path('/tmp/slice3-local-layer-archives-e278b1e-r1/INDEX.json')}
d={k:read(p) for k,p in paths.items()}; c=d['capture']; pr=d['pr']; checks=d['checks']['check_runs']; eq=d['sourceEquivalence']; bi=d['backendArchiveIndex']
assert [c[k] for k in ['sourceHead','sourceTree','sourceInventorySha256','baseHead','testedMerge']]==[H,T,I,B,M]
assert c['exitCode']==0 and c['allRequiredAndAggregateSucceeded'] is True and c['finishedAt'] and len(c['contexts'])==13
assert pr['number']==30 and pr['state']=='open' and pr['draft'] is True and pr['head']['sha']==H and pr['head']['ref']=='feat/SLICE-V1-003-advertising-traffic-efficiency' and pr['base']['sha']==B and pr['merge_commit_sha']==M
assert len(checks)==13 and {x['id'] for x in checks}=={x['id'] for x in c['contexts']}
assert all(x['head_sha']==H and x['status']=='completed' and x['conclusion']=='success' for x in checks)
assert eq['productHead']==eq['sourceHead']==H and eq['sourceTree']==T and eq['inventory']['sha256']==I and len(eq['runtimeRows'])==1280 and eq['changedPaths']==[]
assert all(x['productGitEntry']==x['containingGitEntry'] for x in eq['runtimeRows'])
assert [bi[k] for k in ['sourceHead','sourceTree','sourceInventorySha256']]==[H,T,I]
assert bi['memberCount']==2406 and type(bi['allCollectorEvidenceReferencesVerified']) is int and bi['allCollectorEvidenceReferencesVerified']==2398 and bi['zipCrcAndEveryMemberShaVerified'] is True and bi['allOriginalBytesUnchanged'] is True and bi['actualRawTestcaseCounts']=={'passed':2769,'failures':0,'errors':0,'skipped':0}
assert ref(bi['archive']['path'])['sha256']==bi['archive']['sha256']
for f in d['localArchiveIndex']['files']:assert ref(f['path'])==f
assert len(d['officialArtifactIndex']['artifacts'])==7
for a in d['officialArtifactIndex']['artifacts']:
 assert ref(a['archive']['path'])==a['archive'];assert a['officialMetadata']['digest']=='sha256:'+a['archive']['sha256'];assert a['officialMetadata']['workflow_run']['head_sha']==H
# Each collection attempt is retained. Failures are not converted into successes.
attempts=[]
for f in c['commandReceipts']:
 assert ref(f['path'])==f; raw=read(f['path']);attempts.append({'reference':f,'receipt':raw})
assert len(attempts)==38
cfgp=Path('/tmp/slice3-final-configuration-e278b1e-r3/EXECUTION-INPUTS.json');cfg=read(cfgp);sec=next(x for x in cfg['layers'] if x['id']=='security');oldp=Path(sec['receipt']);old=read(oldp);new=copy.deepcopy(old)
boundary='These are separately collected, read-only GitHub and local archive observations associated with the same product Head. The Security run ID identifies the associated measured workflow, not the later GET/archiving command execution. Original Security criteria and execution timestamps remain unchanged. They add zero product tests. This establishes published product H; the subsequent evidence-only containing E still requires separate exact Head/tree/merge/all-13-CI/source-equivalence readback before handoff is complete.'
new['publicationObservationArtifacts']={k:ref(p) for k,p in paths.items()}
new['publicationObservationDerivation']={'kind':'ADDITIVE_ROOT_METADATA_REGISTRATION_NOT_JOB_EXECUTION','originalSecurityReceipt':ref(oldp),'actualCollectorReceipt':ref(paths['capture']),'collectorStartedAt':c['startedAt'],'collectorFinishedAt':c['finishedAt'],'collectorExitCode':c['exitCode'],'originalCommandAttemptCount':len(attempts),'associatedProductHead':H,'productTestExecutions':0,'builder':ref(__file__),'boundary':boundary}
newp=put('SECURITY-WITH-EXPLICIT-PUBLICATION-OBSERVATIONS.json',new);sec['receipt']=str(newp.resolve())
adapters=[]
def add(name,key,pointers):
 assertions=[{'pointer':p,'expected':ptr(d[key],p)} for p in pointers]
 item={'name':'publication-observation.'+name,'sourcePath':'.github/workflows/security.yml','evidence':ref(paths[key]),'assertions':assertions};adapters.append(item);sec['structuredRecordAdapters'].append(item)
add('exact-pr','pr',['/number','/state','/draft','/head/sha','/head/ref','/base/sha','/merge_commit_sha'])
add('exact-all-contexts','checks',['/total_count']+[f'/check_runs/{i}/{k}' for i in range(13) for k in ['id','name','head_sha','status','conclusion']])
add('capture-identity-and-original-attempts','capture',['/sourceHead','/sourceTree','/productSourceHead','/productSourceTree','/sourceInventorySha256','/sourceInventoryFileCount','/baseHead','/testedMerge','/testedMergeParents','/testedMergeTree','/startedAt','/finishedAt','/exitCode','/allRequiredAndAggregateSucceeded','/inputFiles','/commandReceipts','/workflows','/artifactIndex','/logIndex'])
add('measured-versus-containing-source','sourceEquivalence',['/productHead','/sourceHead','/sourceTree','/inventory','/changedPaths','/runtimeRows'])
add('official-artifact-digests','officialArtifactIndex',['/sourceHead','/testedMerge','/artifacts'])
add('complete-original-backend-index','backendArchiveIndex',['/sourceHead','/sourceTree','/sourceInventorySha256','/runId','/startedAt','/finishedAt','/exitCode','/sourceStable','/archive','/originalFileCount','/memberCount','/allCollectorEvidenceReferencesVerified','/zipCrcAndEveryMemberShaVerified','/allOriginalBytesUnchanged','/actualRawTestcaseCounts','/files'])
add('complete-seven-local-layer-archives','localArchiveIndex',['/files'])
for key in paths:sec['registeredArtifactPointers'].append('/publicationObservationArtifacts/'+key)
prefix='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/portable-e278b1e/files/'
for p in [newp,*paths.values()]:
 rr=ref(p);dest=prefix+rr['sha256']+'/'+Path(rr['path']).name;cfg['artifactDestinations'][str(p)]=dest;cfg['artifactDestinations'][rr['path']]=dest
cfg['registrationInputs'].append(ref(cfgp));cfg['publicationObservationScope']=boundary
config=put('EXECUTION-INPUTS.json',cfg)
# These original 621 assertions still bind the same original raw, with only the
# registered Security parent reference updated to the explicit additive wrapper.
rp=Path('/tmp/slice3-structured-crosschecks-e278b1e-r1/FINAL-STRUCTURED-NODE-SELECTION-RULES.json');rules=read(rp);changed=0
for item in rules['matches']:
 if item['exactCatalogMatch']['layer']=='security':
  for key in ['requiredParent','actualProductParent']:assert item[key]['sha256']==ref(oldp)['sha256'];item[key]=ref(newp)
  changed+=1
rules['publicationObservationParentRebinding']={'originalRules':ref(rp),'originalParent':ref(oldp),'newParent':ref(newp),'changedParentReferencesForAssertions':changed,'assertionBytesChanged':False,'boundary':boundary}
rulep=put('ORIGINAL-621-STRUCTURED-RULES.json',rules)
sp=Path('/tmp/slice3-source-review-e278b1e-r3/SOURCE-METHOD-REVIEW-PLAN.json');plan=read(sp)
messages={
'S3-DR-001:REQUIRED_REWORK:3':'The completed local nine layers and actual remote workflows are bound to measured product H e278b1e, tree 178132d, and the full 1280-file inventory. The extra evidence tools were actually executed on the same H; the older 02e/D distinction remains historical. The current source-equivalence readback compares H with H. A later evidence-only containing E must be separately checked against H and retain its own exact remote CI and tested merge.',
'S3-DR-001:VERIFICATION:2':'Actual read-only GitHub API responses show the authorized branch, open Draft PR 30, exact H/base/tested merge and all 13 completed successful check contexts. Original commands, attempts and canonical response hashes remain available. This is an observed H publication event; a subsequent E publication will receive its own final readback.',
'S3-DR-001:VERIFICATION:3':'The original full backend and seven local archive indices, explicit Security receipt and official seven-artifact CI index bind the exact current H and original bytes. Every selected proof is independently resolved by path and SHA. Final assembled manifest validation and the containing-E handoff inventory are separately executed before completion; neither a generated closure manifest nor its self-assertion is primary proof.'}
for row in plan['rows']:
 if row['id'] in messages:
  row['historicalPublicationSourceReview']={k:row[k] for k in ['engineeringReason','proofLimits','remainingEvidence']};row['engineeringReason']=messages[row['id']];row['proofLimits']=boundary+' Hash equality establishes byte identity; the completed parent/scope reviews establish test meaning. Final containing E and independent Controller acceptance are separate.';row['remainingEvidence']=['Final assembled-manifest and portable mapping validation.','Exact subsequent containing-E publication and all CI readback before final handoff.'];row['publicationObservationRegistration']=ref(config)
plan['publicationObservationRevision']={'priorPlan':ref(sp),'changedRowIds':list(messages),'methodSelectorsChanged':0,'scope':boundary}
planp=put('SOURCE-METHOD-REVIEW-PLAN.json',plan)
bp=Path('/tmp/slice3-root-method-blocker-review-e278b1e-r2.json');block=read(bp)
def rebind(v):
 if isinstance(v,dict):
  if v.get('sha256')==ref(sp)['sha256'] and v.get('path'):v.update(ref(planp))
  for x in v.values():rebind(x)
 elif isinstance(v,list):
  for x in v:rebind(x)
rebind(block);block['priorBlockerReview']=ref(bp);blockp=put('METHOD-BLOCKER-REVIEW.json',block)
review=put('ROOT-PUBLICATION-OBSERVATION-REVIEW.json',{'kind':'ROOT_REVIEWED_CURRENT_PUBLICATION_RAW_SCOPES','sourceHead':H,'sourceTree':T,'sourceInventorySha256':I,'reviewedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'inputs':{k:ref(p) for k,p in paths.items()},'originalSecurityReceipt':ref(oldp),'registeredSecurityReceipt':ref(newp),'actualCollectorStartedAt':c['startedAt'],'actualCollectorFinishedAt':c['finishedAt'],'actualCollectorAttempts':len(attempts),'all13ContextIds':[x['id'] for x in checks],'exactTypedAdapters':adapters,'sourcePlan':ref(planp),'config':ref(config),'rowScopes':messages,'boundary':boundary,'finalContainingReadbackStillRequired':True,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'productionWriteEnabled':False})
print(json.dumps({'config':ref(config),'rules':ref(rulep),'plan':ref(planp),'blockers':ref(blockp),'review':ref(review),'adapters':len(adapters),'originalSecurityAssertionsReparented':changed}))
