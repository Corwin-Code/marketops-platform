import json,hashlib,datetime
from pathlib import Path
O=Path('/tmp/slice3-security-schema-e278b1e-r1');assert not O.exists();O.mkdir()
A=Path('/tmp/slice3-security-admission-e278b1e-r1/SECURITY-RECEIPT.json');C=Path('/tmp/slice3-security-ci-e278b1e-r1');H='e278b1e3d8541aeb806e41d6cbef4deac8d16d06'
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def read(p):return json.loads(Path(p).read_text())
d=read(A);assert d['sourceHead']==H and all(v is True for v in d['criteria'].values());index=read(C/'INDEX.json');original={(str(Path(e['path']).resolve()),e['sha256'])for e in index['files']};rows=[]
for e in d['evidence']:
 r=ref(e['path']);assert r['sha256']==e['sha256'];r['origin']='ORIGINAL_OFFICIAL_CAPTURE'if (r['path'],r['sha256'])in original else 'EXPLICIT_ROOT_REVIEW_OUTPUT';rows.append(r)
assert len({(r['path'],r['sha256'])for r in rows})==len(rows)
ip=O/'EXPLICIT-RAW-AND-ROOT-OUTPUT-INDEX.json';ip.write_text(json.dumps({'kind':'EXPLICIT_CURRENT_RAW_AND_ROOT_OUTPUT_REGISTRATION_INDEX','sourceHead':H,'originalCaptureIndex':ref(C/'INDEX.json'),'originalRootReceipt':ref(A),'files':rows,'originalRawBytesChanged':False,'scope':'Original official capture and explicit root-review outputs retain distinct origin fields; this index does not assert that root outputs are official API files.'},indent=2)+'\n')
cap=read(C/'CAPTURE-RECEIPT.json');d['executionSourceIdentity']=d['actualCleanIdentityBefore'];d['expectedSource']=cap['productExpected'];d['schemaAdapter']={'kind':'DERIVED_FIELD_ALIAS_AND_EXPLICIT_ROOT_OUTPUT_REGISTRATION_NOT_NEW_EXECUTION','recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'originalReceipt':ref(A),'originalImmutableIndex':ref(ip),'officialCaptureIndex':ref(C/'INDEX.json'),'fieldMappings':[{'targetPointer':'/executionSourceIdentity','originalPointer':'/actualCleanIdentityBefore','operation':'EXACT_REFERENCE_COPY_NO_VALUE_CHANGE'},{'targetPointer':'/expectedSource','originalReceipt':ref(C/'CAPTURE-RECEIPT.json'),'originalPointer':'/productExpected','operation':'EXACT_REFERENCE_COPY_NO_VALUE_CHANGE'}],'allOriginalReceiptFieldsUnchanged':True,'newCiRunOrPassCreated':False,'scope':'Compatibility aliases and explicit exact evidence index only; actual source/run/results and raw bytes remain unchanged. Root triage remains root-authored.'}
for k,v in read(A).items():assert d[k]==v
p=O/'SCHEMA-ADAPTER-RECEIPT.json';p.write_text(json.dumps(d,indent=2)+'\n');print(json.dumps({'adapter':ref(p),'registeredFiles':len(rows),'officialCaptureMembers':len(original)}))
