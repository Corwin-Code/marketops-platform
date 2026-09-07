"""Conjoin equivalent same-source/raw/role JSON assertions, preserving every atom."""
import json,hashlib,collections,datetime,time,importlib.util
from pathlib import Path
R=Path('/Users/chzhengx/Code/personal/marketops-platform');O=Path('/tmp/slice3-conjunction-admission-e278b1e-r1');assert not O.exists();O.mkdir();P=Path('/tmp/slice3-root-admission-e278b1e-r1/EXECUTION-MANIFEST.json');original=json.loads(P.read_text());m=json.loads(P.read_text());audit=[]
canonical=lambda x:json.dumps(x,sort_keys=True,separators=(',',':'),ensure_ascii=False)
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size}
source_count=0;output_count=0
for section in ['criteria','findings','verificationChecks']:
 for row in m[section]:
  groups=collections.OrderedDict();source_count+=len(row['proofs'])
  for i,p in enumerate(row['proofs']):
   # JUnit proofs remain exactly unchanged, including their method/role/scope.
   key='JUNIT:'+str(i) if p['kind']=='junit' else canonical({k:v for k,v in p.items()if k not in ['name','scope','assertions']})
   groups.setdefault(key,[]).append((i,p))
  proofs=[];rowaudit=[]
  for items in groups.values():
   i,p=items[0]
   if len(items)==1:q=p
   else:
    assert p['kind']=='json';assertions=collections.OrderedDict();pointer_values={};scopes=[]
    for _,atom in items:
     assert atom['kind']=='json' and all(atom[k]==p[k]for k in p if k not in ['name','scope','assertions'])
     scopes.append({'name':atom['name'],'scope':atom['scope'],'assertions':atom['assertions']})
     for a in atom['assertions']:
      value=canonical(a['expected']);prior=pointer_values.setdefault(a['pointer'],value);assert prior==value,'Conflicting assertions cannot be consolidated';assertions.setdefault(canonical(a),a)
    q={k:v for k,v in p.items()if k not in ['name','scope','assertions']};q.update(name='Conjunction of '+str(len(items))+' original structured proof uses',scope='All of the following original scopes apply conjunctively; no scope or assertion is discarded.\n'+'\n'.join('['+str(n+1)+'] '+s['name']+': '+s['scope'] for n,s in enumerate(scopes)),assertions=list(assertions.values()),originalAtomicScopes=scopes)
    # Exact expansion equality includes typed JSON values, role, run identity,
    # original names/scopes and original per-atom assertion association.
    expanded=[]
    for a in q['originalAtomicScopes']:
     e={k:v for k,v in q.items()if k not in ['name','scope','assertions','originalAtomicScopes']};e.update(a);expanded.append(e)
    assert canonical(expanded)==canonical([x for _,x in items])
    assert {canonical(a)for a in q['assertions']}=={canonical(a)for _,p0 in items for a in p0['assertions']}
   proofs.append(q);rowaudit.append({'finalProofIndex':len(proofs)-1,'originalAtomicIndices':[i for i,_ in items],'originalAtomicSha256':hashlib.sha256(canonical([p for _,p in items]).encode()).hexdigest(),'conjunctiveTypedAssertions':len(q.get('assertions',[])),'fullAtomicExpansionEqual':True})
  assert set(range(len(row['proofs'])))=={i for a in rowaudit for i in a['originalAtomicIndices']};row['proofs']=proofs;output_count+=len(proofs);audit.append({'section':section,'id':row['id'],'groups':rowaudit})
assert len(audit)==227 and source_count==5118
original_dest=R/'docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/portable-e278b1e/files'/ref(P)['sha256']/'EXECUTION-MANIFEST.json';assert original_dest.read_bytes()==P.read_bytes()
m['equivalentAssertionConjunction']={'kind':'EXACT_CONJUNCTIVE_REGISTRATION_OF_ALL_ORIGINAL_ATOMS','originalAtomicManifest':{'path':original_dest.relative_to(R).as_posix(),'sha256':ref(P)['sha256']},'originalProofUses':source_count,'executableProofGroups':output_count,'junitProofsChanged':0,'typedAssertionsRemoved':0,'originalAtomicNamesScopesAndAssertionsPreserved':True,'boundary':'The source-plan/method/342-row audits refer to the retained original atomic manifestation. Each current JSON proof is the conjunction of exactly its original same-source/raw/run/role atoms; originalAtomicScopes provides exact expansion. No historical execution, source identity, underlying assertion or product count changes.'}
out=O/'EXECUTION-MANIFEST.json';out.write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n')
tool=R/'scripts/validation/finalize_slice3_rework_assessment.py';spec=importlib.util.spec_from_file_location('current_finalizer',tool);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);criteria,frozen,_=module.authorities();start=time.monotonic();module.validate_execution(m,criteria,frozen);elapsed=time.monotonic()-start
report={'kind':'EXACT_ATOMIC_EXPANSION_AND_UNMODIFIED_VALIDATOR_REVIEW','at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'builder':ref(__file__),'originalAtomicManifest':ref(P),'conjunctiveManifest':ref(out),'validator':ref(tool),'originalUses':source_count,'executableGroups':output_count,'rowCount':len(audit),'allTypedAtomsAndScopesPreserved':True,'junitProofsChanged':0,'rows':audit,'actualUnmodifiedValidateExecutionResult':'PASS','actualValidationElapsedSeconds':elapsed,'repositoryFilesWritten':0,'sourceHead':'e278b1e3d8541aeb806e41d6cbef4deac8d16d06','productTestsExecuted':0,'controllerApprovalClaimMade':False};rp=O/'EXACT-CONJUNCTION-REVIEW.json';rp.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'manifest':ref(out),'review':ref(rp),'originalUses':source_count,'groups':output_count,'seconds':elapsed}))
