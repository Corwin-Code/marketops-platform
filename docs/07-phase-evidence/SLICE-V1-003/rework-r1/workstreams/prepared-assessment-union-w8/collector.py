import json,hashlib,datetime
from pathlib import Path
repo=Path('/Users/chzhengx/Code/personal/marketops-platform');base=Path('docs/07-phase-evidence/SLICE-V1-003/rework-r1');out=Path('/tmp/slice3-final-assessment-preparation');out.mkdir(exist_ok=False)
read=lambda p:json.loads((repo/p).read_text());sha=lambda p:hashlib.sha256((repo/p).read_bytes()).hexdigest()
central=read(base/'S3-AC-REWORK-STATUS.json');rows={r['id']:{'id':r['id'],'acceptedCriterionExact':r['criterion'],'status':'PREPARED_INDIVIDUAL_REVIEW_ARGUMENT_UNION_FINAL_REPAIRED_SOURCE_AND_CI_PENDING','contributions':[],'findingIds':r['finding_ids'],'externalReleaseObligations':r['external_release_obligations']} for r in central['entries']}
items=[('root',base/'workstreams/measured-assessment-w6/root41/root41-individual-measured-review.json','criteria','id','acceptedCriterionExact'),('source',base/'workstreams/measured-assessment-w6/source87/source87-individual-measured-review.json','criteria','criterionId','acceptedExact'),('controls',base/'workstreams/measured-assessment-w6/control51/controls-51-engineering-assessed.json','rows','id','acceptedCriterion'),('ui',base/'workstreams/measured-assessment-w6/ui81/ui-81-engineering-evaluated-w6-full-w7-frontend-ci.json','acceptanceCriteria','id','acceptedExact')]
inputs=[]
for owner,path,array,idkey,textkey in items:
 d=read(path);inputs.append({'owner':owner,'path':str(path),'sha256':sha(path),'count':len(d[array])})
 for i,x in enumerate(d[array]):
  identifier=x[idkey];assert identifier in rows and x[textkey]==rows[identifier]['acceptedCriterionExact'],identifier
  if owner=='root':reason=x['finalAssessmentRationale'];scope=x['assessmentBoundary']
  elif owner=='source':
   a=x['individualMeasuredEngineeringAssessment'];reason=a.get('engineeringReasonAppliedToMeasuredSource')
   if reason is None:reason=a.get('engineeringReason')
   if reason is None:raise ValueError((identifier,list(a)))
   scope={k:v for k,v in a.items() if k in ['positiveOrNormalScopeActuallyReviewed','negativeOrAdverseScopeActuallyReviewed','proofLimits']}
  elif owner=='controls':reason=x['engineeringReason'];scope=x['proofScopeAndLimits']
  else:reason=x['engineeringAssessment']['reason'];scope={k:x['engineeringAssessment'][k] for k in ['positiveBoundary','refusalUnknownOrNonClaimBoundary']}
  assert isinstance(reason,str) and reason.strip(),identifier
  rows[identifier]['contributions'].append({'owner':owner,'reviewFile':str(path),'reviewFileSha256':sha(path),'jsonPointer':f'/{array}/{i}','recordedStatus':x['status'],'specificEngineeringReason':reason,'proofScope':scope,'evidenceIdentityBoundary':'The referenced review preserves original W6/W7 actual execution and source hashes. New repair source/full/CI evidence must be bound separately; no old report is restamped or made current by this union.'})
assert len(rows)==200 and all(v['contributions'] for v in rows.values())
count=sum(len(v['contributions']) for v in rows.values());assert count==260,count
packet={'kind':'UNION_OF_260_EXISTING_INDIVIDUAL_ENGINEERING_ARGUMENTS_NOT_AUTOMATIC_ACCEPTANCE','preparedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'contractSha256':central['contract_sha256'],'inputs':inputs,'criterionCount':200,'contributionCount':count,'overlappingCriterionCount':sum(len(x['contributions'])>1 for x in rows.values()),'criteria':[rows[k] for k in sorted(rows)],'remainingRequiredJudgment':'Root must combine each recorded argument with exact complete repaired-source named evidence, all115 Frozen clauses and actual latest CI/security/publication before engineering status. AC200 retains independent B/M closure judgment. All24 release obligations remain blocking.','automaticPassPromotion':False,'controllerVerdict':None,'production_write_enabled':False}
f=out/'per-criterion-reviewed-contributions.json';f.write_text(json.dumps(packet,ensure_ascii=False,indent=2)+'\n')
(out/'receipt.json').write_text(json.dumps({'file':str(f),'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'count':200,'contributions':260,'overlap':packet['overlappingCriterionCount'],'status':'PREPARED_FINAL_SOURCE_AND_CI_PENDING'},indent=2)+'\n');print((out/'receipt.json').read_text())
