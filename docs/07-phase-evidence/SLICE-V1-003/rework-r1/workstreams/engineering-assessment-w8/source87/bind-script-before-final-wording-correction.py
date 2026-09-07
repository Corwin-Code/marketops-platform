from pathlib import Path
import json,hashlib,datetime,copy,subprocess,xml.etree.ElementTree as ET,re
head='9b6e6195f779bd80b1e8ed9c78d6ad9daa1a68af';repo=Path('/tmp/slice3-r1-w7-isolated-publication/repository');run=Path('/tmp/slice3-r1-full-clean-w8-9b6e6195');prepdir=Path('/tmp/slice3-source87-w8-final-preparation');out=Path('/tmp/slice3-source87-w8-measured-final');out.mkdir(parents=True,exist_ok=False)
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):
 p=Path(p);b=p.read_bytes();return {'path':str(p),'sha256':sha(b),'bytes':len(b)}
def emit(n,v):
 b=(json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode();(out/n).write_bytes(b);return {'path':n,'sha256':sha(b),'bytes':len(b)}
receipt=json.loads((run/'run-receipt.json').read_bytes());assert receipt['completePass'] and receipt['head']==head and receipt['headAfter']==head and receipt['backendSourceStable'] and receipt['initialClean'] and receipt['cleanAfter'] and receipt['mavenExitCode']==0
forbidden=['-DskipTests','-Dmaven.test.skip','-Dit.test','-Dtest='];assert all(not any(t in arg for t in forbidden) for arg in receipt['command']);assert 'clean' in receipt['command'] and 'verify' in receipt['command']
prep=json.loads((prepdir/'source87-w8-prepared-review.json').read_bytes());assert prep['head']==head
joinpath=Path('/tmp/slice3-w8-individual-backend-join.json');join=json.loads(joinpath.read_bytes());assert join['measuredHead']==head
manifest=json.loads((run/'backend-source-before.json').read_bytes());assert manifest==json.loads((run/'backend-source-after.json').read_bytes());assert sha((run/'backend-source-before.json').read_bytes())==receipt['backendSourceBeforeSha256']
artifactmanifest=json.loads((run/'preserved-artifact-manifest.json').read_bytes());amap={str(Path(m['artifact']).resolve()):m for m in artifactmanifest}
refs={(r['path'],r['method']):r for r in join['references'].values()};xmlcache={};validated_nodes={};method_evidence={};source_evidence={}
def parsexml(path):
 path=Path(path).resolve();assert run.resolve() in path.parents
 if str(path) not in xmlcache:
  raw=path.read_bytes();assert str(path) in amap and sha(raw)==amap[str(path)]['sha256'];xmlcache[str(path)]=(ET.fromstring(raw),sha(raw))
 return xmlcache[str(path)]
def bindsource(s):
 path=s['path'];expected=s['w8SourceSha256']
 if path not in source_evidence:
  if path in manifest:
   assert expected==manifest[path]
   source_evidence[path]={'path':path,'w8Head':head,'sha256':expected,'completedLocalFullSourceManifestSha256':manifest[path],'identity':'EXACT_W8_BYTES_MATCH_COMPLETED_FULL_BEFORE_AND_AFTER_MANIFEST','sourceManifest':ref(run/'backend-source-before.json')}
  else:
   b=subprocess.run(['git','-c','protocol.allow=never','-C',str(repo),'cat-file','blob',head+':'+path],capture_output=True,check=True).stdout;assert sha(b)==expected
   source_evidence[path]={'path':path,'w8Head':head,'sha256':expected,'identity':'EXACT_W8_HISTORICAL_DOCUMENT_GIT_BYTES; NOT_EXECUTABLE_FULL_SOURCE_INPUT','limit':'This is a preserved historical receipt, not the current full-run proof.'}
 return source_evidence[path]
def bindmethod(r):
 key=(r['path'],r['method']);rid=sha(('\0'.join(key)).encode())[:24]
 if rid in method_evidence:return {'referenceId':rid,'path':key[0],'method':key[1]}
 assert manifest[key[0]]==r['w8SourceSha256']
 current=refs.get(key)
 if current:
  assert current['matchStatus']=='EXACT_JUNIT_CASES_MATCHED' and current['sourceIdentity']['measuredSha256']==manifest[key[0]]
  matches=current['matches']
 else:
  assert key[1]=='descriptiveListingFieldsDoNotInventNewMaterialityThresholds'
  fqcn=key[0].split('/src/test/java/')[1][:-5].replace('/','.')
  xp=run/'artifacts/failsafe-reports'/('TEST-'+fqcn+'.xml');root,dig=parsexml(xp);matches=[]
  for i,t in enumerate(root.iter('testcase'),1):
   if t.get('classname')==fqcn and re.fullmatch(re.escape(key[1])+r'\(String\)\[\d+\]',t.get('name','')):
    matches.append({'reportPath':str(xp),'reportSha256':dig,'phase':'failsafe','testcaseOrdinal':i,'rawName':t.get('name'),'rawClassname':t.get('classname'),'status':'PASSED','identityRule':'EXACT_SOURCE_DECLARATION_PLUS_FQCN_PARAMETERIZED_METHOD','seconds':t.get('time')})
  assert len(matches)==2
 cases=[]
 for m in matches:
  root,dig=parsexml(m['reportPath']);assert m['reportSha256']==dig
  ts=list(root.iter('testcase'));ordinal=m['testcaseOrdinal'] + (1 if current else 0);t=ts[ordinal-1]
  assert t.get('name')==m['rawName'] and t.get('classname')==m['rawClassname']
  assert t.find('failure') is None and t.find('error') is None and t.find('skipped') is None and m['status']=='PASSED'
  row={'reportPath':str(Path(m['reportPath']).resolve()),'reportSha256':dig,'phase':m['phase'],'testcaseOrdinal':ordinal,'rawName':m['rawName'],'rawClassname':m['rawClassname'],'seconds':t.get('time'),'failure':False,'error':False,'skipped':False,'actualStatus':'PASSED','identityRule':m['identityRule'],'archiveManifestMatched':True}
  cases.append(row);validated_nodes[(row['reportPath'],ordinal)]=row
 method_evidence[rid]={'referenceId':rid,'path':key[0],'method':key[1],'testSourceSha256':manifest[key[0]],'declarationLines':r.get('declarationOrReferenceLines',[]),'exactMeasuredHead':head,'actualCases':cases,'directFrozenXmlReinspection':'PASS','parentJoinContribution':'DIRECT_ADDITIONAL_PARAMETERIZED_BINDING' if current is None else 'EXACT_REFERENCE_FROM_PARENT_JOIN_INDEPENDENTLY_REINSPECTED','claimLimit':'Execution proves only assertions in this exact method and fixture; criterion-specific meaning remains in the individual review.'}
 return {'referenceId':rid,'path':key[0],'method':key[1],'actualInvocationCount':len(cases),'actualStatus':'PASSED'}
newcriteria=[]
for c in prep['criteria']:
 n=copy.deepcopy(c);n['status']='W8_MEASURED_SOURCE_CONTRIBUTION_FOR_INDIVIDUAL_REVIEW; WHOLE_ACCEPTANCE_PENDING'
 n['currentImplementationBasis']=[{**s,'measuredW8Binding':bindsource(s)}for s in c['currentImplementationBasis']]
 n['currentRequiredNamedAssertions']=[bindmethod(r)for r in c['currentRequiredNamedAssertions']]
 n['currentFinalExecutionEvidence']={'completedLocalFullReceipt':ref(run/'run-receipt.json'),'methodEvidenceFile':'method-execution-evidence.json','currentSourceFile':'source-identity-evidence.json','remoteExactBackendProvenance':ref('/tmp/slice3-w8-backend-ci-provenance-r3/review.json'),'remoteActualBackendResults':ref('/tmp/slice3-w8-backend-ci-final-review/summary.json'),'scope':'Local W8 full actual nodes and exact source manifests. Remote W8 full job source bytes independently match same commit/tree; separate remote artifact identities retained.'}
 n['individualEngineeringReview']['assessment']='EXACT_W8_BACKEND_ASSERTIONS_MEASURED_AND_INDIVIDUALLY_REASONED; NOT_CONTROLLER_ACCEPTANCE'
 n['individualEngineeringReview']['requiredBeforeClosure']='Parent must combine this exact criterion-specific backend reasoning with required controls/UI/operations evidence and current final publication/CI identity. W9 unresolved-profile UI wording is an identified open defect; no blanket 22/200 approval follows from test totals.'
 reason=n['w8SpecificEngineeringReason'].replace('+122ns persisted-origin discrepancy','a sub-microsecond persisted-origin discrepancy (the forced .123456789→.123457 fixture differs by 211ns)')
 if c['criterionId']=='S3-AC-069':
  reason+=' Completed W8 PostgreSQL tests now prove PROFILE_MISSING remains explicit, preserves responsibility and gives exact zero wall age only for the same representable origin; genuinely earlier reads remain absent. This does not close the separately observed UI defect: a missing-profile deadline can still be rendered as within/active. The UI correction and final W9 browser/CI evidence remain required.'
  n['status']='BACKEND_UNKNOWN_SEMANTICS_MEASURED; UI_ON_TIME_WORDING_DEFECT_OPEN'
 elif c['criterionId']=='S3-AC-070':
  reason+=' This is now bound to the complete local W8 full and both exact W8 remote CI artifacts, not merely R25. All three independently measured declared portfolios pass P95/hard/margin checks. The result remains limited to that dataset and does not establish mature-Outcome throughput.'
  n['status']='DECLARED_WORKLOAD_TIMING_MEASURED; CAPACITY_SCOPE_AND_WHOLE_GATE_LIMITS_RETAINED'
 elif c['criterionId']=='S3-AC-071':
  reason+=' Both remote jobs and completed local full also execute the dropped-trigger recovery and same-asOf replay assertions. The initial before-sweep INCIDENT record is kept rather than relabeled; subsequent sweep timing supplies positive hourly margin.'
 elif c['criterionId']=='S3-AC-199':
  reason='Traceability is an evidence-artifact and Git identity obligation, not a business Java assertion. The exact completed W8 full receipt, preserved XML/manifests/JAR, individually matched assertions and independently verified remote API/upload/ZIP/source/resource chain now provide the current backend binding. Earlier W6/W7/R24/R25 proofs and failures remain separate immutable inputs. Final canonical-document synchronization, W9 UI repair, exact new remote CI and Controller disposition remain parent-owned and pending.'
  n['status']='CURRENT_BACKEND_EVIDENCE_BOUND; FINAL_DOCS_PUBLICATION_AND_W9_PENDING'
 elif c['criterionId']=='S3-AC-195':
  reason+=' The completed W8 backend layers are now directly bound by exact named XML nodes. Browser presentation must be assessed separately; the known missing-profile within/active text defect is not excused by backend success.'
 n['w8SpecificEngineeringReason']=reason
 n['individualMeasuredEngineeringAssessment']={'engineeringReason':reason,'positiveScope':n['individualEngineeringReview']['positiveAssertions'],'adverseScope':n['individualEngineeringReview']['negativeAssertions'],'proofLimits':n['individualEngineeringReview']['proofLimits']+n['proofLayerAndCrossStreamLimits'],'observedAssertionRefs':n['currentRequiredNamedAssertions'],'acceptanceDecision':'NOT_ISSUED; SUBSTANTIVE_SOURCE_REVIEW_CONTRIBUTION_ONLY'}
 # Pending wording preserved inside historical preparation, not presented as current execution status.
 n['preparationHistory']={'input':ref(prepdir/'source87-w8-prepared-review.json'),'priorStatus':c['status'],'correction':'AC196 forced fixture is 211ns; W7 original production test discrepancy was 122ns.' if c['criterionId']=='S3-AC-196' else None}
 newcriteria.append(n)
contrib=[]
for c in prep['crossStreamContributions']:
 n=copy.deepcopy(c);n['w8RequiredNamedAssertions']=[bindmethod(r)for r in c['w8RequiredNamedAssertions']];n['status']='CURRENT_W8_BACKEND_SINK_ASSERTIONS_MEASURED; PRIMARY_CONTROLS_OWNER_ADJUDICATION_PENDING';n['currentFullReceipt']=ref(run/'run-receipt.json');contrib.append(n)
assert len(newcriteria)==87 and len(set(c['criterionId']for c in newcriteria))==87 and len(method_evidence)==183
localcap=json.loads((run/'artifacts/capacity/advertising-capacity-receipt.json').read_bytes())
capacity={'localW8':{'receipt':ref(run/'artifacts/capacity/advertising-capacity-receipt.json'),'dataset':ref(run/'artifacts/capacity/advertising-capacity-dataset.json'),'sourceInputs':ref(run/'artifacts/capacity/advertising-capacity-source-inputs.json'),'resources':ref(run/'runtime-resources.json'),'measurements':{k:v for k,v in localcap.items()if k not in ['identities','dataset','runtime']},'identities':localcap['identities'],'runtime':localcap['runtime']},'remoteW8':ref('/tmp/slice3-w8-backend-ci-final-review/summary.json'),'boundary':'1000 UNVERIFIED native objects, one organization/store/shared Variant+listing; 200 explicit containment fixtures; real Case/Task/projection/brief paths, 0 commands and no mature-Outcome throughput workload; no extrapolation.','initialIncidentPreserved':True}
summary={'kind':'SOURCE87_INDIVIDUAL_W8_MEASURED_ENGINEERING_REVIEW','generatedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'measuredHead':head,'measuredTree':receipt['tree'],'criteriaCount':87,'scope':'Substantive individual source/facts/economics/freshness/priority/Outcome engineering contribution, with explicit cross-stream and measured workload limits. This is not a Controller verdict or automatic acceptance.','inputs':{'preparation':ref(prepdir/'source87-w8-prepared-review.json'),'historicalW6':ref('/tmp/slice3-source87-measured-w6-review/source87-individual-measured-review.json'),'frozenW8Full':ref(run/'run-receipt.json'),'parentNamedJoin':ref(joinpath),'fullSourceBefore':ref(run/'backend-source-before.json'),'fullSourceAfter':ref(run/'backend-source-after.json'),'rawArchiveManifest':ref(run/'preserved-artifact-manifest.json'),'remoteProvenance':ref('/tmp/slice3-w8-backend-ci-provenance-r3/review.json'),'remoteMeasuredResults':ref('/tmp/slice3-w8-backend-ci-final-review/summary.json')},'criteria':newcriteria,'crossStreamContributions':contrib,'methodEvidenceFile':'method-execution-evidence.json','sourceEvidenceFile':'source-identity-evidence.json','capacityEvidenceFile':'capacity-boundary-and-measurements.json','validationSummary':{'requiredJavaMethods':len(method_evidence),'actualUniqueTestcaseNodes':len(validated_nodes),'consumedFrozenXmlReports':len(xmlcache),'allMatchedNodesPassed':True,'allComparedTestSourceBytesMatchCompletedFullManifest':True,'localFullAllActualCounts':receipt['actualTestcaseNodeCounts'],'localFullConsole':receipt['mavenConsoleSummaries'],'localFullExactComplete':True,'readLiveTarget':False,'parentJoinOmittedExtraReferenceBoundDirectly':'AdvertisingCrossDomainIsolationIT.descriptiveListingFieldsDoNotInventNewMaterialityThresholds: exact parameterized raw nodes 19/20 both PASS'},'knownOpenCurrentIssue':{'criterionIds':['S3-AC-069','S3-AC-195'],'findingContributions':['S3-DR-006','S3-DR-021'],'description':'W8 frontend renders a missing human SLO profile as within/active despite backend deadline/profile unresolved. Parent/UI have identified narrow W9 correction; this source review does not treat backend PASS as UI acceptance.','state':'OPEN_PARENT_UI_REPAIR_PENDING'},'historicalCorrection':{'criterionId':'S3-AC-196','preparedPhrase':'+122ns persisted-origin discrepancy','actualW8ForcedFixture':'211ns (.123456789 rounds to .123457); final wording says sub-microsecond; prior W7 observed failure was a separate 122ns instance.','originalPreparationUnchanged':True},'overallStatus':'EXACT_W8_SOURCE_ASSERTIONS_BOUND_AND_INDIVIDUALLY_REASONED; FINAL_W9_AND_WHOLE_ACCEPTANCE_PENDING','controllerVerdict':'NOT_ISSUED'}
emit('method-execution-evidence.json',list(method_evidence.values()));emit('source-identity-evidence.json',list(source_evidence.values()));emit('capacity-boundary-and-measurements.json',capacity);emit('actual-matched-case-index.json',list(validated_nodes.values()))
rr=emit('source87-individual-measured-review.json',summary)
emit('manifest.json',[ref(p)for p in sorted(out.rglob('*'))if p.is_file()]);print(json.dumps({'result':summary['overallStatus'],'report':rr,'methods':len(method_evidence),'actualNodes':len(validated_nodes),'xmlReports':len(xmlcache),'criteria':87}))
