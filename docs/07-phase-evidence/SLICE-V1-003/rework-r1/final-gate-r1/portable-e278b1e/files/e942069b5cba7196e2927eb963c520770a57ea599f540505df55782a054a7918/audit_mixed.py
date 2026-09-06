import json,hashlib,subprocess,copy
from pathlib import Path
O=Path(__file__).resolve().parent;R=Path('/Users/chzhengx/Code/personal/marketops-platform');B=Path('/tmp/slice3-final-execution-e278b1e-r6/backend-full');T=B/'raw/backend/marketops-server/target';H='e278b1e3d8541aeb806e41d6cbef4deac8d16d06'
sha=lambda b:hashlib.sha256(b).hexdigest()
def read(p):return json.loads(Path(p).read_text())
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes())}
def ptr(v,p):
 for s in p.split('/')[1:]:s=s.replace('~1','/').replace('~0','~');v=v[int(s)] if isinstance(v,list) else v[s]
 return v
rp=T/'advertising-mixed-capacity-receipt.json';dp=T/'advertising-mixed-capacity-dataset.json';sp=T/'advertising-mixed-capacity-source-inputs.json';gp=T/'advertising-mixed-capacity-diagnostic.json';m=read(rp);d=read(dp);ss=read(sp)
parent=read(B/'layer-candidate.json');members={(str(Path(e['path']).resolve()),e['sha256'])for e in parent['evidence']}
for p in [rp,dp,sp,gp]:assert tuple(ref(p).values()) in members
assert m['identities']['datasetSha256']==ref(dp)['sha256'] and m['identities']['sourceInputsSha256']==ref(sp)['sha256'] and m['identities']['datasetId']==d['datasetId']
inventory={e['path']:e['sha256']for e in read(B/'source-before.json')['files']};sourceChecks=[]
for e in ss:
 rel='backend/marketops-server/'+e['path'];actual=sha(subprocess.check_output(['git','--no-replace-objects','show',H+':'+rel],cwd=R));assert actual==e['sha256']==inventory[rel];sourceChecks.append({'sourceInput':e,'repositoryPath':rel,'actualGitSha256':actual,'inventorySha256':inventory[rel]})
cohorts=d['historicalCohorts'];gates=m['historicalCommandWriteGateReasons'];assert len(cohorts)==len(gates)==40 and set(c['command']for c in cohorts)==set(gates)
joins=[];dynamic=[]
def add(p,pointer,role,scope):dynamic.append({'evidence':ref(p),'pointer':pointer,'actual':ptr(read(p),pointer),'role':role,'scope':scope})
for i,c in enumerate(cohorts):
 command=c['command'];value=gates[command];assert isinstance(value,list) and all(isinstance(x,str)for x in value);assert {'SEALED_AUTHORIZATION_MISSING_OR_EXPIRED','AUTHORITY_PERMANENTLY_INVALIDATED'}<=set(value)
 a='/historicalCohorts/'+str(i)+'/command';b='/historicalCommandWriteGateReasons/'+command
 joins.append({'command':command,'datasetPointer':a,'datasetEvidence':ref(dp),'gatePointer':b,'gateEvidence':ref(rp),'actualReasons':value})
 add(dp,a,'supporting','Exact constrained historical input command UUID; this row is setup, not an executed admission.')
 add(rp,b,'adverse','Actual app-role write gate reasons for this exact historical command; matched to the explicit dataset cohort UUID.')
assert 'GLOBAL_SWITCH_DISABLED'in m['currentCommandWriteGateReasons'];add(rp,'/currentCommandWriteGateReasons','adverse','Actual full typed reason array for the single current untransmitted command; refusal is not actual Provider access.')
resource=B/'raw/build/final-gate-r6-e278b1e/backend/runtime-resources.json';assert tuple(ref(resource).values())in members and ref(resource)['sha256']==m['identities']['runtimeResourceReceiptSha256']
for pointer in ['/identities/datasetId','/identities/datasetSha256','/identities/sourceInputsSha256','/identities/runtimeResourceReceiptSha256','/identities/measuredLocalGitHead','/identities/publicationIdentity','/identities/ciIdentity','/runtime','/postgresContainerResources']:add(rp,pointer,'supporting','Exact local receipt identity/resource object. JVM, owned PostgreSQL limits, and outer host/Docker receipt retain separate meanings; NOT_PROVIDED is not a CI identity.')
add(dp,'/datasetId','supporting','Exact dataset identity paired with receipt identity and independently verified raw SHA.')
# Outer object must remain a real parent evidence member. Its root object is an RFC6901 empty pointer.
add(resource,'','supporting','Actual separately captured host and Docker VM resource receipt; zero owned container limits mean no additional per-container cap.')
assert m['targeted']['criticalP95Millis']<=300000 and m['targeted']['maximumMillis']<=900000 and m['sweepWallMillis']<1800000
assert all(m['targeted'][k]==0 for k in ['hardBreachCount','clockDefectCount','pendingRequests','failedRequests']);assert m['targeted']['sampleCount']==m['targeted']['criticalSampleCount']==1040
assert m['productionWriteEnabled'] is False and m['realProviderAccess'] is False
capacityp=Path('/tmp/slice3-capacity-review-e278b1e-r6/CAPACITY-REVIEW.json');capacity=read(capacityp)
assert capacity['sourceHead']==H and capacity['sourceInventorySha256']==parent['sourceInventorySha256']
assert capacity['parentExecution']['sha256']==ref(B/'layer-candidate.json')['sha256']
assert capacity['allReviewedChecksPassed']is True and len(capacity['checks'])==87 and all(x['passed']is True for x in capacity['checks'])
assert len(ss)==1063
pb=capacity['postSweepPendingBoundary'];assert pb['originalDroppedCount']==pb['newDistinctPendingCount']==40 and pb['allAcceptedAfterFrozenAsOf']is True and pb['allOriginalDroppedIDsDistinctFromNewPendingIDs']is True
reviews=[ref(capacityp)]
review={'kind':'EXACT_MIXED_DYNAMIC_RAW_CROSSCHECK','rawReferences':[ref(p)for p in [rp,dp,sp,gp,resource]],'parent':ref(B/'layer-candidate.json'),'actualSourceEntries':len(ss),'sourceChecks':sourceChecks,'historicalGateJoins':joins,'currentCommandWriteGateReasons':m['currentCommandWriteGateReasons'],'dynamicAssertions':dynamic,'actualTargeted':m['targeted'],'actualSweepWallMillis':m['sweepWallMillis'],'actualTargetedWallMillis':m['targetedWallMillis'],'actualRuntime':m['runtime'],'actualPostgresContainerResources':m['postgresContainerResources'],'actualOuterResources':read(resource),'independentCapacityReviews':reviews,'postSweepPendingBoundary':pb,'conclusion':'VERIFIED_WITH_EXPLICIT_SCOPE_LIMITS','limits':['This is the same backend execution; the mixed scope adds zero product test counts.','Four exact files include the diagnostic; setup dataset was captured before measured workers and is not final post-sweep state.','Forty historical actions/admission/readback are constrained synthetic inputs. Measured workers advance stage/revision/control state; no APPLY throughput or real 30-day wait claim.','40 pre-invalidated expired authorizations produce zero new expiry journals.','Original 40 dropped requests were completed. A distinct 40 later requests accepted after the sweep cutoff remain pending; no next-sweep latency or final empty queue assertion.','Post-targeted aggregate INCIDENT/HOURLY_RECONCILIATION_NOT_CURRENT is retained, not relabeled healthy.','Local publication CI fields are NOT_PROVIDED; host8CPU/16GiB, Docker4CPU/6210576384B and JVM8/4GiB are distinct.'],'proofSelections':[]}
(O/'MIXED-DYNAMIC-RAW-CROSSCHECK.json').write_text(json.dumps(review,indent=2)+'\n')
rules=read('/tmp/slice3-structured-registration-e278b1e-r1/STRUCTURED-NODE-SELECTION-RULES.json');additional=[];newmatches=[]
for pid in ['source-proof-ace1a61c3f87f1a63a45c15e','source-proof-432c837f999bcc073f7b673d']:
 template=next(x for x in rules['matches']if x['planProofId']==pid)
 for x in dynamic:
  name='structured.'+pid+'.dynamic.'+sha((x['evidence']['path']+'#'+x['pointer']).encode())[:12]
  ad={'name':name,'sourcePath':template['exactCatalogMatch']['sourcePath'],'evidence':x['evidence'],'assertions':[{'pointer':x['pointer'],'expected':x['actual']}]};additional.append(ad)
  match=copy.deepcopy(template);match.update(name=name,plannedRole=x['role'],scope=x['scope'],catalogNodeId=None,boundReview={'operator':'EXACT_JSON_EQUAL','requiredBound':None});match['exactCatalogMatch'].update(name=name,evidence=x['evidence'],assertions=ad['assertions']);match['independentRawCrosscheck']=ref(O/'MIXED-DYNAMIC-RAW-CROSSCHECK.json');newmatches.append(match)
patch={'kind':'ADDITIVE_DYNAMIC_STRUCTURED_PATCH_ONLY','sourceHead':rules['sourceHead'],'sourceTree':rules['sourceTree'],'sourceInventorySha256':rules['sourceInventorySha256'],'original433Rules':ref('/tmp/slice3-structured-registration-e278b1e-r1/STRUCTURED-NODE-SELECTION-RULES.json'),'layers':[{'id':'mixed_capacity','registeredArtifactPointers':[],'structuredRecordAdapters':additional}],'proofSelections':[],'countAddedToProductTests':0,'independentRawCrosscheck':ref(O/'MIXED-DYNAMIC-RAW-CROSSCHECK.json')}
(O/'ADDITIVE-DYNAMIC-CONFIG-PATCH.json').write_text(json.dumps(patch,indent=2)+'\n')
rules['matches'].extend(newmatches);rules['dynamicRawCrosscheck']=ref(O/'MIXED-DYNAMIC-RAW-CROSSCHECK.json');rules['originalRules']=patch['original433Rules'];(O/'EXPANDED-STRUCTURED-NODE-SELECTION-RULES.json').write_text(json.dumps(rules,indent=2)+'\n')
print(json.dumps({'sourceEntries':len(ss),'joins':len(joins),'dynamicPerDefinition':len(dynamic),'adaptersAdded':len(additional),'allMatches':len(rules['matches']),'review':ref(O/'MIXED-DYNAMIC-RAW-CROSSCHECK.json'),'patch':ref(O/'ADDITIVE-DYNAMIC-CONFIG-PATCH.json')}))
