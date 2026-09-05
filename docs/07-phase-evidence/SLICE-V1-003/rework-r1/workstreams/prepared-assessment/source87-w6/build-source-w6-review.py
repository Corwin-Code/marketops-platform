from pathlib import Path
import json,subprocess,hashlib,datetime,re,copy
repo=Path('/Users/chzhengx/Code/personal/marketops-platform');out=Path('/tmp/slice3-source-w6-review');out.mkdir(exist_ok=True);head='3ed3f4c87c336cb07188e470528f328358fb279f';previous='247ea5ced6cd0ac110314db9fa606d8995c85cac';shardPath='docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/facts-outcome-traceability.json';sha=lambda b:hashlib.sha256(b).hexdigest();now=datetime.datetime.now(datetime.timezone.utc).isoformat();cache={}
def blob(commit,path):
 key=(commit,path)
 if key not in cache:
  r=subprocess.run(['/usr/bin/git','show',commit+':'+path],cwd=repo,capture_output=True)
  cache[key]=r.stdout if r.returncode==0 else None
 return cache[key]
def bind(path):
 current=blob(head,path);prior=blob(previous,path)
 return {'path':path,'w6SourceExists':current is not None,'w6SourceSha256':sha(current) if current is not None else None,'w5SourceSha256':sha(prior) if prior is not None else None,'w5ToW6BytesUnchanged':current==prior,'sourceIdentityMethod':'Read exact immutable Git blobs; no working-tree or live test-result reads.'}
shardBytes=blob(head,shardPath);s=json.loads(shardBytes);assert len(s['acceptanceCriteria'])==87
oldDraft=Path('/tmp/slice3-source-ac-engineering-review-individual.json');oldBytes=oldDraft.read_bytes();old=json.loads(oldBytes);olderCurrent=Path('/tmp/slice3-source-ac-engineering-review-current.json');olderHash=sha(olderCurrent.read_bytes());oldBy={c['criterionId']:c for c in old['criteria']}
reviewed={
'AdvertisingDisclosureIT.java':'Remove redundant role/grant fixture inserts already supplied by canonical shared graph. Scope, disclosure and revoke assertions remain; no production authority or predicate is weakened.',
'AdvertisingHumanWorkflowIT.java':'Replace concatenated synthetic issuer ALTER ROLE with TestDatabase.enableSyntheticIdentityIssuer, which binds the password into PostgreSQL format(%L). Real human service and permission assertions are unchanged.',
'AdvertisingManualWorkflowIT.java':'Use the same trusted PostgreSQL-quoted synthetic issuer helper; Manual Planner/selection/verification/Outcome assertions are unchanged.',
'AdvertisingVerticalPathIT.java':'Use the trusted PostgreSQL-quoted synthetic issuer helper at fixture initialization. Actual canonical facts→Metric→calculator→human→command→fixture transport→Outcome journey assertions are unchanged.',
'TestDatabase.java':'New fixed-role helper binds synthetic password as SQL parameter then receives server-quoted single-literal DDL; companion TestDatabaseIssuerIT covers quote/injection and privilege refusal. No real credential is provisioned.',
'AdvertisingOperationsConsoleController.java':'Read Spring Security Authentication and require authenticated AuthenticatedActor principal, avoiding servlet request/model binding as identity authority; existing Store/Product/domain disclosure checks remain.',
'AdvertisingCapacityEvidence.java':'Test evidence collector names /usr/bin/git explicitly. Arguments, five-second deadline, failure state and HEAD validation are unchanged; the full capacity run, not a simple executable probe, remains required.'}

def method_definition(data,name):
 if data is None or not name:return None
 text=data.decode();pattern=r'\b(?:(?:public|protected|private|static|final|synchronized)\s+)*(?:void|boolean|int|long|String|UUID|(?:Stream|List|Set|Map)<[^;\n]+>)\s+'+re.escape(name)+r'\s*\('
 matches=list(re.finditer(pattern,text));return [{'line':text.count('\n',0,m.start())+1,'declaration':m.group().strip()} for m in matches]
criteria=[];paths=set();methodMissing=[]
for c in s['acceptanceCriteria']:
 cid=c['id'];previousReview=oldBy[cid];assert c['acceptedText']==previousReview['acceptedExact'];impl=[];tests=[]
 for r in c['sourceReferences']:
  if not r.get('path'):continue
  paths.add(r['path']);x=copy.deepcopy(r);x['w6Identity']=bind(r['path']);impl.append(x)
 for r in c['testReferences']:
  if not r.get('path'):continue
  paths.add(r['path']);x=copy.deepcopy(r);x['w6Identity']=bind(r['path']);x['w6VerificationStatus']='PENDING_COMPLETED_EXACT_W6_RUN_AND_NAMED_ASSERTION_BINDING';name=r.get('method');defs=method_definition(blob(head,r['path']),name);x['w6MethodDefinition']=defs
  if name and not defs:methodMissing.append({'criterionId':cid,'path':r['path'],'method':name})
  x['historicalEvidenceLimit']='Original run, namedTestcaseEvidence and verification fields remain historical only. File identity or an unchanged method does not promote a result to the currently running W6 full suite.'
  tests.append(x)
 changed=sorted({x['w6Identity']['path'] for x in impl+tests if not x['w6Identity']['w5ToW6BytesUnchanged']})
 item={'criterionId':cid,'acceptedExact':c['acceptedText'],'acceptedContractLine':c['contractLine'],'status':'ENGINEERING_REVIEW_DRAFT_PENDING_W6_FULL_CI_AND_CONTROLLER','implementationBasis':impl,'specificNamedAssertionEvidence':tests,'sameClassAndTransitiveAssessment':c['sameClassScan'],'individualEngineeringReview':copy.deepcopy(c['engineeringReview']),'historicalEvidenceStatus':c['evidenceStatus'],'historicalEvidenceLimits':c['evidenceLimits'],'historicalTargetedReceipt':c.get('currentTargetedReceipt'),'w5ToW6SourceDelta':{'changedMappedPaths':changed,'reviewedImpact':[{'path':f,'reason':reviewed.get(Path(f).name,'Source changed; no automatic claim of equivalent behavior. Reconcile the owning workstream actual assertions.')} for f in changed],'unchangedMappedPathCount':len({x['path'] for x in impl+tests})-len(changed),'judgmentBoundary':'Criterion-specific reasoning is preserved from the committed, manually corrected source shard. Exact hashes and source delta support review; final acceptance is not inferred.'}}
 criteria.append(item)
contributions=copy.deepcopy(s.get('crossStreamContributions',[]))
for contribution in contributions:
 contribution['historicalStatus']=contribution.get('status');contribution['status']='ENGINEERING_CONTRIBUTION_PENDING_W6_FULL_CI_AND_PRIMARY_OWNER_ADJUDICATION'
 for key in ['sourceReferences','testReferences']:
  for r in contribution.get(key,[]):
   if r.get('path'):paths.add(r['path']);r['w6Identity']=bind(r['path']);r['w6Validation']='PENDING: historical R21 sink proof is preserved and is not the currently running W6 result.'
 contribution['w6ContributionBoundary']='AC131 isolation and F012 worker contributions preserve the exact actual-sink assertions and historical R20/R21 causality. Current Git-object hashes are appended; primary ownership and final verdict are unchanged.'
transitive=['backend/marketops-server/src/test/java/com/mimococo/marketops/TestDatabase.java','backend/marketops-server/src/test/java/com/mimococo/marketops/TestDatabaseIssuerIT.java','backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/web/AdvertisingOperationsConsoleController.java','backend/marketops-server/src/test/java/com/mimococo/marketops/AdvertisingOperationsPrincipalIT.java','backend/marketops-server/src/test/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingCapacityEvidence.java']
transitiveRows=[]
for f in transitive:
 paths.add(f);transitiveRows.append({'sourceIdentity':bind(f),'reason':reviewed.get(Path(f).name,'New actual regression test for the separately owned security root-cause repair; exact assertions are controlled by its workstream.'),'executionBoundary':'No live XML inspected. R23 and fresh security scan are separate completed receipts; final W6 full verification remains in progress.'})
sourceInventory=[bind(f) for f in sorted(paths)]
body={'kind':'SOURCE_87_CRITERION_ENGINEERING_REVIEW_W6_DRAFT','generatedAtUTC':now,'scope':'The existing 87 facts/outcome criteria plus already-owned AC131/F012 cross-stream contributions; no new finding or audit scope. Read immutable W5/W6 Git source and existing committed evidence only.','headSha':head,'treeSha':'4e73aa0e7c30fe470528ecd5287b55a9c55e5ff1','previousReviewedHead':previous,'baseSha':'08ad7da7d9e75b4ddd1c387a22ac0affba9e1430','sourceShard':{'path':shardPath,'exactW6Sha256':sha(shardBytes)},'priorDraftsPreserved':[{'path':str(oldDraft),'sha256':sha(oldBytes)},{'path':str(olderCurrent),'sha256':olderHash}],'criteriaCount':len(criteria),'criteria':criteria,'crossStreamContributions':contributions,'reviewedTransitiveW6Changes':transitiveRows,'methodDefinitionLookupLimit':'Read-only Java declaration navigation, not Java parsing or execution evidence; unresolved lookups are listed rather than silently accepted.','unresolvedMethodDefinitionLookups':methodMissing,'sourceInventoryFile':'source-inventory-w5-w6.json','currentRunBoundary':{'fullClean':'Parent reports exact W6 clean verify session14952 in progress; no live XML read and no result inferred.','frontendCI':'Parent reports W6 frontend-test FAILURE under UI investigation; no all-CI-green claim.','security':'Exact W6 security run33962619117 and aggregate101297138061 SUCCESS; fixes12, retained87 quality alerts and5 historical dismissed SARIF findings transparently preserved in /tmp/slice3-security-w6/summary.json. Security alone does not promote these87 criteria.','controllerVerdict':None},'status':'DRAFT_NOT_AUTOMATICALLY_PROMOTED','requiredBeforeClosure':'Bind the completed exact W6 full report to each named assertion and preserved proof boundary, resolve the actual frontend CI failure with its exact source/run identity, then combine primary-owner engineering assessment and independent Controller verdict.'}
(out/'source-inventory-w5-w6.json').write_text(json.dumps(sourceInventory,indent=2)+'\n');(out/'source-87-engineering-review-w6.json').write_text(json.dumps(body,indent=2)+'\n');(out/'reviewed-source-delta.patch').write_bytes(subprocess.check_output(['/usr/bin/git','diff',previous,head,'--',*sorted(paths)],cwd=repo));(out/'committed-facts-outcome-shard-w6.json').write_bytes(shardBytes)
assert oldDraft.read_bytes()==oldBytes and sha(olderCurrent.read_bytes())==olderHash
print('criterionCount',len(criteria),'pathCount',len(paths),'unresolvedCount',len(methodMissing));print('draftSHA',sha((out/'source-87-engineering-review-w6.json').read_bytes()));print('inventorySHA',sha((out/'source-inventory-w5-w6.json').read_bytes()))
