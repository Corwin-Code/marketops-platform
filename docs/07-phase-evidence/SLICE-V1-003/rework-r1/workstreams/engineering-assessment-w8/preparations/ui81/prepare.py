from pathlib import Path
import json,hashlib,subprocess,re,copy,xml.etree.ElementTree as ET
from datetime import datetime,timezone
repo=Path('/Users/chzhengx/Code/personal/marketops-platform');out=Path('/tmp/slice3-ui81-w8-final-preparation');old=Path('/tmp/slice3-w6-assessment/ui-81-engineering-evaluated-w6-full-w7-frontend-ci.json')
W8='9b6e6195f779bd80b1e8ed9c78d6ad9daa1a68af';TREE='eb4bce1333c87e4de762f6f42bbd3bcd392fec38';W6='3ed3f4c87c336cb07188e470528f328358fb279f';W7='3e4039259c0a56d0f10319cdcae79cab66f81983';now=datetime.now(timezone.utc).isoformat()
def sha(b):return hashlib.sha256(b).hexdigest()
def ref(p):return {'path':str(p),'sha256':sha(Path(p).read_bytes())}
def read(p):return json.loads(Path(p).read_text())
def write(p,d):Path(p).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
d=read(old);ci=Path('/tmp/slice3-w8-frontend-ci');browsers=read(ci/'named-browser-results.json')['tests'];bytitle={b['title']:b for b in browsers};assert len(bytitle)==37
r25=repo/'docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/final-controls/r25';r25receipt=read(r25/'receipt.json');r25manifest=read(r25/'source-before.json');assert r25receipt['sourceStable'] and r25receipt['counts']=={'tests':72,'failures':0,'errors':0,'skipped':0}
cache={};manifest={}
def source(path):
 if path not in cache:
  raw=subprocess.check_output(['git','show',W8+':'+path],cwd=repo);current=(repo/path).read_bytes();assert raw==current,path
  try:prior=subprocess.check_output(['git','show',W6+':'+path],cwd=repo,stderr=subprocess.DEVNULL)
  except subprocess.CalledProcessError:prior=None
  cache[path]=raw;manifest[path]={'path':path,'w8Sha256':sha(raw),'w6Sha256':sha(prior) if prior is not None else None,'w6BytesEqualW8':raw==prior,'currentWorkingBytesEqualW8':True,'committedHead':W8,'committedTree':TREE}
 return cache[path]
def current_method(path,method,fqcn=None):
 text=source(path).decode();match=re.search(r'\b(?:void|boolean|[A-Za-z0-9_<>?,.\[\]]+)\s+'+re.escape(method)+r'\s*\(',text);assert match,(path,method)
 fqcn=fqcn or re.search(r'package\s+([\w.]+);',text).group(1)+'.'+Path(path).stem
 return {'path':path,'method':method,'declaringClass':fqcn,'currentSourceSha256':manifest[path]['w8Sha256'],'currentSourceLine':text.count('\n',0,match.start())+1,'sourceHead':W8,'sourceTree':TREE,'executionStatus':'PENDING_W8_FULL_ARCHIVED_XML_MATCH','expectedIdentityRule':'Exact declaring class and method, preserving any parameterized raw invocation names; match against frozen W8 full output only.'}
# Actual R25 reports are working-tree evidence, not silently rebound to the subsequent W8 commit.
r25cases={}
for report in r25receipt['reports']:
 p=r25/'reports'/Path(report['path']).name;assert sha(p.read_bytes())==report['sha256'];root=ET.parse(p).getroot();assert len(root.findall('testcase'))==report['actual']['tests']
 for i,t in enumerate(root.findall('testcase')):
  assert not any(t.find(x) is not None for x in ['failure','error','skipped'])
  r25cases[(t.attrib['classname'],t.attrib['name'])]={'class':t.attrib['classname'],'method':t.attrib['name'],'status':'PASSED','seconds':t.attrib['time'],'testcaseOrdinal':i,'xml':ref(p),'runReceipt':ref(r25/'receipt.json'),'measuredSourceManifest':ref(r25/'source-before.json'),'runtimeResources':ref(r25/'runtime-resources.json'),'measuredBaseHead':r25receipt['baseHead'],'measuredSourceState':'Exact W7 working tree plus listed repairs; later W8 bytes are compared separately, not declared the measured commit.'}
H='backend/marketops-server/src/test/java/com/mimococo/marketops/AdvertisingHumanWorkflowIT.java';HS='backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/AdvertisingTaskSloService.java'
C='backend/marketops-server/src/test/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingOrchestrationCapacityIT.java';CS='backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingCaseCalculationService.java';CT='backend/marketops-server/src/test/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingCaseCalculationServiceTest.java'
R='backend/marketops-server/src/test/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingReconciliationWorkerTest.java';RS='backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/application/AdvertisingReconciliationWorker.java'
human=['staffedTaskAgeUsesTheExactPersistedMicrosecondOrigin','unresolvedProfileTaskAgeUsesTheExactPersistedMicrosecondOrigin'];worker=['oneNanosecondClockReversalIsUnknownAndBreachedInsteadOfZeroLatency','exactZeroLatencyIsMeasuredAndDoesNotBreach','exactFifteenMinuteLatencyDoesNotBreachTheInclusiveBound','oneNanosecondBeyondFifteenMinutesBreachesBeforeMillisecondConversion'];age=['aPersistedMicrosecondOriginCannotGiveTheSameCalculationNegativeAge','microsecondRoundingAcrossASecondBoundaryStillRepresentsAgeZero','aGenuinelyFutureCaseOriginRemainsUnknownInsteadOfZero','anExistingCaseKeepsItsPositiveElapsedAge']
newby={};allrefs={};criteria={};r25used={}
def add(path,method,criterion,fqcn=None):
 key=path+'#'+method
 if key not in allrefs:allrefs[key]=current_method(path,method,fqcn)
 allrefs[key].setdefault('criterionIds',[])
 if criterion not in allrefs[key]['criterionIds']:allrefs[key]['criterionIds'].append(criterion)
 actual=r25cases.get((allrefs[key]['declaringClass'],method))
 if actual:
  assert manifest[path]['w8Sha256']==r25manifest[path],path
  copyactual=copy.deepcopy(actual);copyactual['w8SourceEqualsMeasuredR25']=True;copyactual['measuredTestSourceSha256']=r25manifest[path];allrefs[key]['supportingR25Execution']=copyactual;r25used[key]=copyactual
 return key
nums_h=[60,61,62,64,65,67,68,69,72,74,181,194,195,196,199,200]
nums_w=[23,70,71,194,195,196,197,199,200]
nums_age=[56,59,60,62,70,71,194,195,196,197,199,200]
for n in nums_h:
 for m in human:newby.setdefault(n,[]).append((H,m))
for n in nums_w:
 for m in worker:newby.setdefault(n,[]).append((R,m))
for n in nums_age:
 for m in age:newby.setdefault(n,[]).append((CT,m))
for p in [HS,CS,RS]:source(p);assert manifest[p]['w8Sha256']==r25manifest[p]
# Substantive W8 review of the changed authority clocks; no blanket clamp or future-read widening.
time_review={'productionSource':[manifest[HS],manifest[CS],manifest[RS]],'taskSlo':'Both statusForCase and status(task,asOf) apply first_raised_at<=asOf and recorded_at<=asOf in SQL. The same original Java asOf rounded by PostgreSQL to its microsecond origin gets zero exposure age only when the persisted origin equals that precise rounded instant; a genuinely future origin is excluded. Missing profile/calendar still returns PROFILE_OR_CALENDAR_MISSING with null deadlines, not an on-time/ready claim.','actualPgOracle':'The two Human tests pass .123456789 through PostgreSQL and assert persisted .123457, both Case and Task entry points agree at zero age, a one-second-earlier query is absent/null, and 129600 seconds later retains exact elapsed exposure. One test uses a staffed profile; the other clears the synthetic profile and asserts unknown coverage/null acknowledgement deadline. This is actual production-reader behavior with a migration-role synthetic origin oracle, not proof that caller actors can mutate responsibility history.','caseAge':'Same-asOf capacity deliberately forces PostgreSQL round-up, retains full calculation equality, requires all projected CASE_AGE factors be zero, and verifies truly earlier rank-context reads exclude newly projected Cases. Replay timestamps come from actual PG clock precision. The four pure Case age tests additionally cover second rollover, positive elapsed age and truly future unknown state.','reconciliation':'The actual worker mock-port tests preserve exact Duration semantics: negative1ns => unknown/null+breached; exact0 and exact15min => not breached;15min+1ns => breached even while stored/display milliseconds are900000. This is deterministic worker logic, not PG sub-microsecond persistence or capacity proof.'}
caplimit={'declaredDataset':'1000 native objects, one organization/Store/shared Product/listing, one complete affected member per object,200 explicitly synthetic Regression/containment objects.','actualIncludedWork':'Actual targeted workers, scoped outcome-worker dispatch, canonical calculation/projection, responsibility Tasks, ledger late-correction trigger and deliberately dropped-request repair by full sweep, one Daily Brief publication and explicit unresolved write-decision gate.','actualZeroCommandBoundary':'The measured fixture asserts ops.ad_bid_command count is zero and semantic profile UNVERIFIED. It measures no Provider command creation/transmission throughput.','outcomeThroughputLimit':'Calling the real scoped outcome worker for each object does not establish matured Completed/Retained/Settled evaluation throughput at 1000 objects: this workload has no governed command/packet outcome population. Actual mature Outcome behavior is proved by separate source-owned canonical PG/vertical tests, not by this timing receipt.','scalingLimit':'No claim of arbitrary multi-organization or large affected-member economics scale; the separate diverse-scope trigger test proves inclusion/exclusion semantics only.','latestFull':'W8 full measurement is still running; no W6/R25 timing is relabeled as W8 full.'}
# Pull source identities from real prior references, preserving their measured-run fields in place.
rows=[];unmatched_browser=[]
for prior in d['acceptanceCriteria']:
 row=copy.deepcopy(prior);n=int(row['id'][-3:]);cid=row['id'];keys=[];components=[]
 for section in ['ownedAndSupplementaryJava','otherStreamJavaRetained']:
  for a in row['evaluatedEvidence'][section]:
   fq=a['actualCases'][0]['declaringBinaryClass'];keys.append(add(a['sourcePath'],a['method'],cid,fq))
 for p,m in newby.get(n,[]):keys.append(add(p,m,cid))
 for a in row['sourceComponentReferences']:
  p=a.get('path')
  if p and p.startswith(('backend/','frontend/','scripts/','.github/')) and (repo/p).is_file():source(p);components.append(manifest[p])
 for a in row['namedAssertionSources']:
  p=a.get('path')
  if p and p.startswith(('backend/','frontend/')) and (repo/p).is_file():source(p)
 selected=[]
 for e in row['supportingExecutionEvidence']:
  if e.get('layer') in ['ACTUAL_ADVERTISING_BROWSER','ACTUAL_LEGACY_BROWSER']:
   title=e['title'];b=bytitle.get(title)
   if b is None:unmatched_browser.append({'criterion':cid,'title':title});continue
   source(b['sourcePath']);assert manifest[b['sourcePath']]['w8Sha256']==b['sourceSha256'];selected.append(copy.deepcopy(b))
 selected={b['fullDisplayTitle']:b for b in selected};criteria[cid]=list(dict.fromkeys(keys))
 row['historicalW6EngineeringAssessment']=copy.deepcopy(row['engineeringAssessment'])
 rationale=row['engineeringAssessment']['reason']
 if n in nums_h:rationale+=' W8 repair review: both SLO entry points now enforce first_raised_at and recorded_at historical cutoffs; actual R25 staffed and missing-profile PG origin tests prove precise microsecond zero-age, genuinely future refusal and unchanged unknown-profile semantics.'
 if n in nums_age:rationale+=' W8 changes force, rather than remove, the PG round-up counterexample while retaining full same-asOf equality and exact future-case exclusion; new named age controls are queued for the frozen W8 XML join.'
 if n in nums_w:rationale+=' R25 additionally proves reconciliation detects -1ns and15min+1ns before millisecond conversion; inclusive zero/15min positive boundaries remain intact.'
 if selected:rationale+=f' The same {len(selected)} specifically associated browser journey/journeys now also have actual W8 CI named log evidence on tested merge954ff361; this verifies the current backend runtime rather than inheriting W7 behavior.'
 if n in [70,71,194,195,196,197,199,200]:rationale+=' Capacity is explicitly an orchestration/no-write workload: zero API commands and no bulk matured Outcome population. Separate source-owned Outcome tests supply behavior proof, not large-scale Outcome throughput.'
 row['status']='ENGINEERING_REVIEW_PREPARED_FOR_W8_FULL_NAMED_BINDING'
 row['w8EngineeringAssessment']={'reviewedAtUTC':now,'result':'SUBSTANTIVE_CURRENT_SOURCE_REVIEW_AND_W8_FRONTEND_PROOF_COMPLETE; W8_FULL_BACKEND_AND_PUBLICATION_GATES_PENDING','reason':rationale,'positiveBoundary':row['engineeringAssessment']['positiveBoundary'],'negativeAndUnknownBoundary':row['engineeringAssessment']['refusalUnknownOrNonClaimBoundary'],'requiredW8NamedAssertions':list(dict.fromkeys(keys)),'supportingR25Methods':[{'key':key,'evidence':allrefs[key]['supportingR25Execution']} for key in dict.fromkeys(keys) if 'supportingR25Execution' in allrefs[key]],'w8BrowserNamedEvidence':list(selected.values()),'w8FrontendUnitAggregate':{'receipt':ref(ci/'receipt.json'),'testsPassed':308,'filesPassed':22,'identityLimit':'Actual W8 CI aggregate only. W6 individual-title JSON remains separate historical named evidence with current-source equality, not W8 per-title execution.'},'sourceComponents':components,'wholeCriterionPassIssuedByThisDraft':False,'controllerVerdict':None,'sharedContributionPolicy':'Additive exact criterion ID join. Preserve other-stream authority, arithmetic, source and REL contributions; never replace them with browser or aggregate results.'}
 if n in nums_h or n in nums_age or n in nums_w:row['w8EngineeringAssessment']['precisionRepairReview']=time_review
 if n in [23,70,71,194,195,196,197,199,200]:row['w8EngineeringAssessment']['capacityProofBoundary']=caplimit
 row['remainingSpecificProof']=['Frozen W8 complete unselected backend XML/source/coverage/capacity binding, then latest required CI/security/Git and independent Controller/REL review.']
 row['finalRunTypesRequired']=['Join this exact named method set to frozen W8 full reports when Root completes collection; do not start another local run.']
 rows.append(row)
assert not unmatched_browser
# source manifest is one observed W8 Git identity, not a rewritten W6 receipt.
write(out/'w8-current-source-manifest.json',{'head':W8,'tree':TREE,'createdAtUTC':now,'files':list(manifest.values()),'sourceChangesFromW6':[v for v in manifest.values() if not v['w6BytesEqualW8']],'limit':'Current source equality to exact committed W8 was read, not a test execution.'})
write(out/'required-w8-named-backend-assertions.json',{'kind':'CONCRETE_METHOD_SET_FOR_FUTURE_FROZEN_W8_XML_JOIN_NO_EXECUTION_PROMOTION','head':W8,'tree':TREE,'createdAtUTC':now,'criteria':criteria,'references':list(allrefs.values()),'uniqueReferenceCount':len(allrefs),'status':'PENDING_W8_FULL_XML_BINDING','sourceManifest':ref(out/'w8-current-source-manifest.json'),'r25ActualMethods':len(r25used),'notAnAcceptanceVerdict':True})
write(out/'r25-actual-supporting-methods.json',{'kind':'ACTUAL_R25_NAMED_SUPPORT_WITH_SEPARATE_W8_BYTE_COMPARISON','receipt':ref(r25/'receipt.json'),'sourceManifest':ref(r25/'source-before.json'),'resources':ref(r25/'runtime-resources.json'),'methods':r25used,'humanSuiteActualCases':18,'wholeR25ActualCases':72,'limit':'Actual selected working-tree R25 run; full W8 and independent Controller remain pending.'})
result=copy.deepcopy(d);result['kind']='UI81_W8_CURRENT_SOURCE_SUBSTANTIVE_PREPARATION_WITH_ACTUAL_FRONTEND_AND_R25_PROOF';result['createdAtUTC']=now;result['status']='PENDING_FROZEN_W8_FULL_BACKEND_AND_FINAL_PUBLICATION_GATES';result['priorAssessment']=ref(old);result['acceptanceCriteria']=rows
result['currentReviewedHead']=W8;result['currentReviewedTree']=TREE;result['priorBaseIdentityNotice']='baseCommit/baseTree and existing W6/W7 supporting fields are intentionally historical; current W8 is identified only by currentReviewedHead/tree and w8 fields.'
result['w8SourceManifest']=ref(out/'w8-current-source-manifest.json');result['w8RequiredNamedBackendAssertions']=ref(out/'required-w8-named-backend-assertions.json');result['r25SupportingRepairEvidence']=ref(out/'r25-actual-supporting-methods.json')
result['w8FrontendEvidence']={'receipt':ref(ci/'receipt.json'),'namedBrowser':ref(ci/'named-browser-results.json'),'backendRuntimeProvenance':ref(ci/'backend-runtime-provenance.json'),'visualReview':ref(ci/'visual-review.json'),'publicationScan':ref(ci/'publication-scan.json'),'actualCounts':{'unitAggregate':308,'legacyNamedBrowser':25,'advertisingNamedBrowser':12,'pngOriginals':26},'fullNamedBrowserCatalog':browsers,'noInheritedExecution':'Actual W8 CI run33967874665 attempt1,testedmerge954ff3617402c3ff22fa8eb86d9aa8abaea76941; not a source-equality claim used in place of running W8.'}
result['w8FullBackendBinding']={'status':'PENDING_ROOT_FULL_RUN_COMPLETION_AND_FROZEN_ARTIFACTS','expectedRunDirectory':'/tmp/slice3-r1-full-clean-w8-9b6e6195','expectedHead':W8,'requiredNamedSet':ref(out/'required-w8-named-backend-assertions.json'),'runtimeNotInspectedWhileRunning':True,'noResultInvented':True}
result['precisionSameClassRepairReview']=time_review;result['f020CapacityBoundary']=caplimit
result['validationOfThisW8Preparation']={'acceptedExact81Unchanged':all(a['acceptedExact']==b['acceptedExact'] for a,b in zip(d['acceptanceCriteria'],rows)),'criterionCount':len(rows),'ownBrowserRefsUnmatched':unmatched_browser,'currentSourceFiles':len(manifest),'concreteJavaMethodsForFutureJoin':len(allrefs),'r25NamedMethodsWithSourceEquality':len(r25used),'repositoryWrites':False,'runtimeExecuted':False,'doesNotPromoteCriterionFromTotals':True}
write(out/'ui-81-w8-final-prepared.json',result)
summary={'assessment':ref(out/'ui-81-w8-final-prepared.json'),'requiredNamedBackendAssertions':ref(out/'required-w8-named-backend-assertions.json'),'sourceManifest':ref(out/'w8-current-source-manifest.json'),'r25NamedSupport':ref(out/'r25-actual-supporting-methods.json'),'validation':result['validationOfThisW8Preparation'],'fullBackend':'PENDING_FROZEN_W8_FULL','allPublicationGates':'PENDING','controllerVerdict':None};write(out/'summary.json',summary);print(json.dumps(summary,indent=2))
