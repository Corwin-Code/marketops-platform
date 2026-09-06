"""Materialize the root's reviewed current scopes; no layer closure or Controller approval."""
import json,hashlib,datetime,collections
from pathlib import Path
O=Path('/tmp/slice3-root-selected-evidence-e278b1e-r1');assert not O.exists();O.mkdir()
paths={'sourcePlan':Path('/tmp/slice3-source-review-e278b1e-r3/SOURCE-METHOD-REVIEW-PLAN.json'),'methods':Path('/tmp/slice3-method-proposals-e278b1e-r1/PROPOSED-METHOD-BINDINGS.json'),'structured':Path('/tmp/slice3-structured-proposals-e278b1e-r2/PROPOSED-STRUCTURED-BINDINGS.json'),'catalog':Path('/tmp/slice3-final-assessment-e278b1e-r1/CURRENT-NODE-CATALOG.json'),'slots':Path('/tmp/slice3-final-assessment-e278b1e-r1/ASSESSMENT-SLOTS.json'),'scopeReviews':Path('/tmp/slice3-structured-crosschecks-e278b1e-r1/ALL-STRUCTURED-SCOPE-REVIEWS.json')}
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def read(p):return json.loads(Path(p).read_text())
x={k:read(v)for k,v in paths.items()};plan=x['sourcePlan'];head=plan['sourceHead'];tree=plan['sourceTree'];inv=plan['sourceInventorySha256'];assert head=='e278b1e3d8541aeb806e41d6cbef4deac8d16d06'
for k in ['methods','structured','catalog','scopeReviews']:assert [x[k][t]for t in ['sourceHead','sourceTree','sourceInventorySha256']]==[head,tree,inv]
assert x['methods']['summary']['unboundSelectors']==x['methods']['summary']['explicitlyBlockedRows']==0 and x['methods']['summary']['selectors']==2297
assert x['structured']['counts']['missing']==x['structured']['counts']['ambiguous']==x['structured']['counts']['failedOrSourceMismatched']==0 and x['structured']['counts']['uniqueActualNodes']==621
assert x['scopeReviews']['reviewedPendingItemCount']==15 and x['scopeReviews']['originalIndividualRequirements']==73
assert x['catalog']['issues']==[] and len(x['catalog']['layers'])==9 and all(l['terminalSuccessfulCommandObserved']is True and l['exactSourceIdentityMatches']is True for l in x['catalog']['layers'])
nodes={n['nodeId']:n for n in x['catalog']['nodes']};sources={(r['rowKind'],r['id']):r for r in plan['rows']};methods={(r['rowKind'],r['id']):r for r in x['methods']['rows']};structured={(r['rowKind'],r['id']):r for r in x['structured']['rows']};result={'kind':'ROOT_REVIEWED_EXACT_CURRENT_ROW_PROOF_SELECTIONS','sourceHead':head,'sourceTree':tree,'sourceInventorySha256':inv,'reviewer':'Codex /root','reviewedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'inputs':{k:ref(v)for k,v in paths.items()},'reviewScope':'The current source review and named expansions are accepted only for their stated row-specific scopes. All existing method boundaries are part of canonical scope; structured row use, typed assertion and original boundary are concatenated. No independent Controller verdict, Ready, merge or production enablement is implied.','engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'productionWriteEnabled':False};audit=[]
for section,count in [('criteria',200),('findings',22),('clauses',115),('verificationChecks',5)]:
 assert len(x['slots'][section])==count;result[section]=[]
 for slot in x['slots'][section]:
  key=(section,slot['id']);s=sources[key];m=methods[key];sr=structured.get(key,{});selections=[]
  for sel in m['proposedProofSelections']:selections.append(dict(sel,origin='EXACT_CURRENT_METHOD_EXPANSION'))
  for sel in sr.get('proposedProofSelections',[]):
   role=sel['assertionRole'];assert role in ['positive','adverse','supporting'];scope='Row use: '+sel['rowUseScope']+' Assertion scope: '+sel['scope']
   if sel.get('evidenceBoundary'):scope+=' Evidence boundary: '+sel['evidenceBoundary']
   selections.append({'nodeId':sel['nodeId'],'role':role,'scope':scope,'origin':'EXACT_CURRENT_STRUCTURED_RAW_ASSERTION','planProofId':sel['planProofId'],'originalRowPlannedRole':sel['role']})
  unique={}
  for sel in selections:
   n=nodes[sel['nodeId']];assert n['observedResult']=='PASSED'and n['admissibleAfterIndependentScopeReview']is True and n['terminalSuccessfulCommandObserved']is True
   unique.setdefault((sel['nodeId'],sel['role'],sel['scope']),sel)
  sels=list(unique.values());assert sels,slot['id'];roles={p['role']for p in sels}
  if section=='verificationChecks':assert {'positive','adverse'}<=roles,slot['id']
  reason=s['engineeringReason']+' Current execution review: the stated source requirement is bound below to original completed e278b1e execution and every selected method parameter instance; source-review statements awaiting execution describe that earlier review stage. Historical checkpoints and counts remain historical. The independently reviewed raw layers and explicit typed crosschecks supply the current measured boundary.'
  limits='Current admission is limited to each selected proof scope, including its explicit evidence boundary. Full source identity, successful parent and all selected parameter expansions were verified. Historical source-review limits are retained verbatim below; old commit/count or pending-execution wording denotes its original point in time, not a reassigned current result. Independent Controller approval, real Provider capability and all 24 production-blocking release obligations remain outside this engineering evidence. Historical source-review limits: '+s['proofLimits']
  row={k:v for k,v in slot.items()if k in ['id','acceptedExact','frozenExact','findingId','clauseKind','ordinal','frozenJsonPointer','requiredClosure','title']};row.update(rowKind=section,engineeringReason=reason,proofLimits=limits,reviewedBy='Codex /root',proofSelections=sels,originalSourceReason=s['engineeringReason'],sourcePlanRow={'path':str(paths['sourcePlan'].resolve()),'sha256':sha(paths['sourcePlan'].read_bytes()),'id':s['id']});result[section].append(row)
  audit.append({'id':slot['id'],'rowKind':section,'methodSelections':len(m['proposedProofSelections']),'structuredSelections':len(sr.get('proposedProofSelections',[])),'finalSelections':len(sels),'roles':sorted(roles),'layers':sorted({nodes[v['nodeId']]['layer']for v in sels}),'selectedAmbiguousNodes':0,'scopeDisposition':'ROOT_REVIEWED_WITH_EVERY_STATED_SOURCE_AND_RAW_BOUNDARY'})
assert len(audit)==342
p=O/'ROOT-PROOF-SELECTIONS.json';p.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');q=O/'ROW-SCOPE-REVIEW.json';q.write_text(json.dumps({'kind':'ROOT_CURRENT_ROW_SCOPE_REVIEW_RECORD','sourceHead':head,'sourceTree':tree,'sourceInventorySha256':inv,'rootSelections':ref(p),'rootReviewInputs':result['inputs'],'rows':audit,'counts':{k:len(result[k])for k in ['criteria','findings','clauses','verificationChecks']},'mechanical115ClauseAudit':'STILL_REQUIRED_AFTER_PENDING_ASSEMBLY','controllerApprovalClaimMade':False,'engineeringClosureClaimMade':False,'productionWriteEnabled':False},indent=2)+'\n');print(json.dumps({'selections':ref(p),'scopeReview':ref(q),'rows':342,'selectedOccurrences':sum(r['finalSelections']for r in audit),'uniqueSelectedNodes':len({s['nodeId']for k in ['criteria','findings','clauses','verificationChecks']for r in result[k]for s in r['proofSelections']})}))
