import json,hashlib,copy
from pathlib import Path
O=Path(__file__).resolve().parent;P=Path('/tmp/slice3-final-execution-e278b1e-r6/migration/raw/build/final-gate-r6-e278b1e/migration')
sha=lambda b:hashlib.sha256(b).hexdigest()
def read(p):return json.loads(Path(p).read_text())
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes())}
rules=read(O/'EXPANDED-STRUCTURED-NODE-SELECTION-RULES.json');pid='source-proof-9b84af18c1abdb959fc34e22';t=next(x for x in rules['matches']if x['planProofId']==pid);adapters=[]
review=Path('/tmp/slice3-migration-crosscheck-e278b1e-r1/REVIEW.json');rv=read(review);assert rv['productSourceIdentity']['sourceHead']=='e278b1e3d8541aeb806e41d6cbef4deac8d16d06' and rv['migrationCount']==73
parent=read('/tmp/slice3-final-execution-e278b1e-r6/migration/layer-candidate.json');members={(str(Path(e['path']).resolve()),e['sha256'])for e in parent['evidence']}
for fname,pointer,scope in [('summary.json','/artifactSha256','Exact same full-backend packaged JAR SHA; independently rehashed against the actual backend artifact, not merely an asserted label.'),('summary.json','/migrationInventory','Actual packaged inventory of 73 migrations; independent review compares each summary/resolver/JAR/Git/inventory entry.'),('backend-products-before.json','','Actual captured backend output binding before migration verification, including JAR, SBOM, licenses/buildinfo. Scope is captured fields and file hashes.'),('backend-products-after.json','','Actual captured backend output binding after migration verification; every captured file SHA matches before and original backend raw. No rebuilt substitute.')]:
 p=P/fname;e=ref(p);assert tuple(e.values())in members;doc=read(p);value=doc if pointer=='' else doc[pointer[1:]]
 if pointer=='/artifactSha256':assert value==rv['artifactSha256']
 if pointer=='/migrationInventory':assert len(value)==rv['migrationCount']==73
 name='structured.'+pid+'.dynamic.'+sha((e['path']+'#'+pointer).encode())[:12];a={'name':name,'sourcePath':t['exactCatalogMatch']['sourcePath'],'evidence':e,'assertions':[{'pointer':pointer,'expected':value}]};adapters.append(a)
 m=copy.deepcopy(t);m.update(name=name,plannedRole='supporting',scope=scope,catalogNodeId=None,boundReview={'operator':'EXACT_JSON_EQUAL','requiredBound':None},independentRawCrosscheck=ref(review));m['exactCatalogMatch'].update(name=name,evidence=e,assertions=a['assertions']);rules['matches'].append(m)
(O/'ADDITIVE-MIGRATION-DYNAMIC-PATCH.json').write_text(json.dumps({'kind':'ADDITIVE_REQUIRED_MIGRATION_DYNAMIC_PATCH_ONLY','sourceHead':rules['sourceHead'],'sourceTree':rules['sourceTree'],'sourceInventorySha256':rules['sourceInventorySha256'],'layers':[{'id':'migration','registeredArtifactPointers':[],'structuredRecordAdapters':adapters}],'proofSelections':[],'independentRawCrosscheck':ref(review)},indent=2)+'\n')
(O/'FINAL-STRUCTURED-NODE-SELECTION-RULES.json').write_text(json.dumps(rules,indent=2)+'\n');print(json.dumps({'newAdapters':4,'allMatches':len(rules['matches']),'patch':ref(O/'ADDITIVE-MIGRATION-DYNAMIC-PATCH.json'),'rules':ref(O/'FINAL-STRUCTURED-NODE-SELECTION-RULES.json')}))
