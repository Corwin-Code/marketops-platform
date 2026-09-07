import argparse,collections,datetime,hashlib,json
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('--catalog',required=True);ap.add_argument('--rules',required=True);ap.add_argument('--review',required=True);ap.add_argument('--out',required=True);a=ap.parse_args();out=Path(a.out).resolve();assert out.is_relative_to(Path('/tmp').resolve()) and not out.exists();out.mkdir(parents=True)
sha=lambda b:hashlib.sha256(b).hexdigest()
def read(p):return json.loads(Path(p).read_text())
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def eq(x,y):return json.dumps(x,sort_keys=True,separators=(',',':'))==json.dumps(y,sort_keys=True,separators=(',',':'))
def ptr(v,p):
 assert isinstance(p,str)and (p==''or p.startswith('/'))
 for t in p.split('/')[1:]:
  t=t.replace('~1','/').replace('~0','~');v=v[int(t)]if isinstance(v,list)else v[t]
 return v
cache={}
def raw(e):
 k=(str(Path(e['path']).resolve()),e['sha256'])
 if k not in cache:
  b=Path(k[0]).read_bytes();assert sha(b)==k[1];cache[k]=json.loads(b)
 return cache[k]
def refEq(x,y):return str(Path(x['path']).resolve())==str(Path(y['path']).resolve())and x['sha256']==y['sha256']
rules=read(a.rules);catalog=read(a.catalog);review=read(a.review);assert catalog['issues']==[] and review['reviewedPendingItemCount']==15
for k in ['sourceHead','sourceTree','sourceInventorySha256']:assert catalog[k]==rules[k]==review[k]
assert eq(catalog.get('derivationSourceIdentity'),rules.get('derivationSourceIdentity'));assert all(l['exactSourceIdentityMatches'] and l['terminalSuccessfulCommandObserved']for l in catalog['layers'])
index=collections.defaultdict(list)
for n in catalog['nodes']:index[(n['kind'],n['layer'],n['name'],n['sourcePath'])].append(n)
resolved=[];byDefinition=collections.defaultdict(list);problems=[]
for m in rules['matches']:
 e=m['exactCatalogMatch'];found=index[(e['kind'],e['layer'],e['name'],e['sourcePath'])];assert len(found)==1,('ambiguous/missing',e['name'],len(found));n=found[0]
 assert n['source']['sha256']==e['sourceSha256'] and refEq(n['evidence'],e['evidence']) and eq(n['assertions'],e['assertions']);assert n['runId']==e['runId']
 assert n['observedResult']=='PASSED' and n['terminalSuccessfulCommandObserved']is True and n['admissibleAfterIndependentScopeReview']is True
 if rules.get('derivationSourceIdentity')is not None:
  for key in ['productSourceIdentity','executionSourceIdentity']:assert eq(n[key],e[key])
  assert refEq(n['actualExecutionReceipt'],e['actualExecutionReceipt']);raw(n['actualExecutionReceipt'])
 else:
  # Same commit uses its actual registered parent, rather than fabricated D-shaped node metadata.
  layer=next(x for x in catalog['layers']if x['id']==n['layer']);assert refEq(layer['receipt'],m['requiredParent']) and refEq(m['requiredParent'],m['actualProductParent'])
  parent=raw(layer['receipt']);assert [parent[k]for k in ['sourceHead','sourceTree','sourceInventorySha256']]==[catalog[k]for k in ['sourceHead','sourceTree','sourceInventorySha256']]
  assert str(parent.get('runId',parent.get('run',{}).get('id')))==str(n['runId'])
  def actual_reference(v):
   if isinstance(v,dict):
    if isinstance(v.get('path'),str)and v.get('sha256')and refEq(v,n['evidence']):return True
    return any(actual_reference(x)for x in v.values())
   return isinstance(v,list)and any(actual_reference(x)for x in v)
  assert actual_reference(parent),'Exact structured raw member absent from actual registered parent'
  for key in ['productSourceIdentity','executionSourceIdentity']:
   if key in n or key in e:assert eq(n.get(key),e.get(key))
  if 'actualExecutionReceipt'in n or 'actualExecutionReceipt'in e:assert refEq(n['actualExecutionReceipt'],e['actualExecutionReceipt'])

 document=raw(n['evidence'])
 for assertion in n['assertions']:assert eq(ptr(document,assertion['pointer']),assertion['expected'])
 item={'planProofId':m['planProofId'],'catalogNodeId':n['nodeId'],'name':n['name'],'layer':n['layer'],'sourcePath':n['sourcePath'],'sourceSha256':n['source']['sha256'],'evidence':n['evidence'],'assertions':n['assertions'],'runId':n['runId'],'executionSourceIdentity':n.get('executionSourceIdentity',{'sourceHead':catalog['sourceHead'],'sourceTree':catalog['sourceTree'],'sourceInventorySha256':catalog['sourceInventorySha256']}),'actualExecutionReceipt':n.get('actualExecutionReceipt',m['actualProductParent']),'assertionRole':m['plannedRole'],'assertionScope':m['scope'],'requiredParent':m['requiredParent'],'actualProductParent':m['actualProductParent']};resolved.append(item);byDefinition[m['planProofId']].append(item)
rows=[];referenceCount=0;occurrences=0
for r in rules['rowRequirements']:
 proposed=[];refs=[]
 for s in r['selectedStructuredItems']:
  pid=s['planProofId'];selected=byDefinition[pid];assert selected;referenceCount+=1
  ids=[]
  for node in selected:
   ids.append(node['catalogNodeId']);proposed.append({'nodeId':node['catalogNodeId'],'role':s['plannedRole'],'scope':node['assertionScope'],'rowUseScope':s['scope'],'evidenceBoundary':s.get('evidenceBoundary'),'planProofId':pid,'assertionRole':node['assertionRole'],'plannedSourceSha256':s['sourceSha256']});occurrences+=1
  refs.append({'planProofId':pid,'definitionPointer':s['definitionPointer'],'name':s['name'],'plannedRole':s['plannedRole'],'scope':s['scope'],'evidenceBoundary':s.get('evidenceBoundary'),'exactCatalogNodeIds':ids,'nodeCount':len(ids)})
 rows.append({'id':r['id'],'rowKind':r['rowKind'],'originalStructuredReferences':refs,'proposedProofSelections':proposed,'admission':'ROOT_REVIEW_REQUIRED','independentCrosscheck':ref(a.review)})
assert len(rows)==119 and referenceCount==107 and len(resolved)==review['expectedFinalStructuredAssertions'] and len(byDefinition)==15 and len({x['catalogNodeId']for x in resolved})==len(resolved)
result={'kind':'EXACT_STRUCTURED_ROOT_REVIEW_PROPOSAL_ONLY','recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceHead':rules['sourceHead'],'sourceTree':rules['sourceTree'],'sourceInventorySha256':rules['sourceInventorySha256'],'derivationSourceIdentity':rules.get('derivationSourceIdentity'),'catalog':ref(a.catalog),'rules':ref(a.rules),'independentScopeReview':ref(a.review),'rows':rows,'resolvedDefinitionNodes':resolved,'counts':{'reviewRows':len(rows),'originalRowReferences':referenceCount,'definitions':len(byDefinition),'uniqueActualNodes':len(resolved),'proposedOccurrences':occurrences,'missing':0,'ambiguous':0,'failedOrSourceMismatched':0},'semantics':'Only explicitly source-planned definitions are mapped. Full actual typed assertions/raw SHA/source/execution identity were rechecked; row role and scope are preserved beside the narrower scalar/object assertion scope. No slot/manifest was edited.','repositoryFilesChanged':[],'productTestExecutions':0,'automaticAdmissionPerformed':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False}
p=out/'PROPOSED-STRUCTURED-BINDINGS.json';p.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'proposal':ref(p),'counts':result['counts']}))
