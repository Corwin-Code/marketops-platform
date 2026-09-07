#!/usr/bin/env python3
"""Classify exact immutable dependency class literals; never expose token values."""
import hashlib,io,json,re,struct,zipfile
from pathlib import Path
OUT=Path(__file__).resolve().parent
sha=lambda b:hashlib.sha256(b).hexdigest()
def utf8_constants(data):
 assert data[:4]==b'\xca\xfe\xba\xbe'
 count=struct.unpack_from('>H',data,8)[0];pos=10;i=1;values=[]
 while i<count:
  tag=data[pos];pos+=1
  if tag==1:
   length=struct.unpack_from('>H',data,pos)[0];pos+=2
   values.append((i,data[pos:pos+length].decode('utf-8',errors='replace')));pos+=length
  elif tag in (3,4):pos+=4
  elif tag in (5,6):pos+=8;i+=1
  elif tag in (7,8,16,19,20):pos+=2
  elif tag in (9,10,11,12,17,18):pos+=4
  elif tag==15:pos+=3
  else:raise ValueError('Unknown class constant tag '+str(tag))
  i+=1
 return values
for short in ['7e66cf8','98344bb']:
 scan=OUT/(short+'-publication-scan')
 if not (scan/'SCAN-SUMMARY.json').exists():continue
 summary=json.loads((scan/'SCAN-SUMMARY.json').read_text());assert summary['status']=='COMPLETE'
 index=json.loads((OUT/(short+'-INDEX.json')).read_text());root=Path('/tmp/slice3-final-execution-'+short+'-r3/backend-full')
 members={r['path']:r for r in (json.loads(x) for x in (scan/'MEMBERS.jsonl').read_text().splitlines())}
 patterns=[re.compile(r['pattern']) for r in json.loads((scan/'SCAN-INPUT.json').read_text())['patterns']]
 hits=[json.loads(x) for x in (scan/'HITS.jsonl').read_text().splitlines()];classified=[]
 for hit in hits:
  parts=hit['path'].split('!');data=(root/parts[0]).read_bytes()
  for component in parts[1:]:
   name=re.sub(r'#member=\d+$','',component)
   with zipfile.ZipFile(io.BytesIO(data)) as z:data=z.read(name)
  assert sha(data)==members[hit['path']]['sha256']
  constants=utf8_constants(data);rule=hit['rule'];cp_hits=[]
  for ordinal,text in constants:
   for m in patterns[rule-1].finditer(text):cp_hits.append({'constantPoolIndex':ordinal,'constantBytes':len(text.encode()),'matchSha256':sha(m.group().encode()),'matchedCharacters':len(m.group())})
  allowed=False;reason='UNCLASSIFIED_PRIVATE';kind='UNREVIEWED'
  if rule==1 and any(hit['path'].endswith(s) for s in ['RsaKeyConverters.class#member=275','OpenSSLContext.class#member=1542']):
   # Exact match is the 27-byte standard delimiter only. Check all UTF8 strings
   # in this immutable class for complete PEM blocks with a base64 payload.
   no_pem_payload=not any(re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\s]{32,}-----END',text) for _,text in constants)
   allowed=hit['matchedCharacters']==27 and hit['matchedSha256']=='3021d90eb9437b2d8f30e8363695c4418b5e5f1870801b5c317e9398ee0f572d' and no_pem_payload
   kind='DEPENDENCY_PEM_DELIMITER_OR_FORMAT_ERROR_LITERAL'
   reason='The exact dependency class constant is a standard PEM delimiter or parser format-error message; all constant-pool strings contain no complete private-key block. This exception binds only this member SHA, occurrence and matched delimiter SHA.'
  elif rule==7 and 'org/apache/catalina/users/MemoryUserDatabase.class' in hit['path']:
   allowed=not cp_hits
   kind='BINARY_REGEX_SPANS_DISTINCT_CLASS_CONSTANTS'
   reason='The raw-byte regex spans multiple Java constant-pool entries used to serialize XML. Parsing actual class constants finds no single matching password/token assignment. No credential value is present in this matched sequence; exception binds this exact class SHA and occurrence only.'
  classified.append({'path':hit['path'],'memberSha256':members[hit['path']]['sha256'],'rule':rule,'view':hit['view'],'characterOffset':hit['characterOffset'],'matchSha256':hit['matchedSha256'],'classification':kind,'reason':reason,'constantPoolParseVerified':True,'matchingConstantMetadata':cp_hits,'exactOccurrenceAllowlisted':allowed,'rawTokenValuePrinted':False,'publishableWithinThisPatternScan':allowed})
 report={'kind':'EXACT_RAW_HIT_CLASSIFICATION_NOT_GENERAL_PUBLICATION_AUTHORITY','sourceHead':index['sourceHead'],'sourceTree':index['sourceTree'],'sourceInventorySha256':index['sourceInventorySha256'],'checkpointDisposition':index['checkpointDisposition'],'scanSummary':{'path':str(scan/'SCAN-SUMMARY.json'),'sha256':sha((scan/'SCAN-SUMMARY.json').read_bytes())},'hitRecords':classified,'totalHits':len(hits),'exactNonSecretOccurrencesAllowlisted':sum(r['exactOccurrenceAllowlisted'] for r in classified),'unreviewedOrCredentialShapedPrivateHits':sum(not r['exactOccurrenceAllowlisted'] for r in classified),'jwtShapedHits':sum(h['rule']==8 for h in hits),'testTokensPermitted':False,'originalBytesChanged':False,'rawPublicationAuthorized':False,'engineeringClosureClaimMade':False,'boundary':'No blanket file or rule exclusion. Exact immutable third-party parser/serializer constants only. Any JWT/test token or new unknown hit remains private. Pattern scan is not universal PII or credential assurance; Owner publication scope still applies.'}
 (scan/'HIT-CLASSIFICATION.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({'checkpoint':short,'hits':len(hits),'allowlistedNonSecret':report['exactNonSecretOccurrencesAllowlisted'],'private':report['unreviewedOrCredentialShapedPrivateHits'],'jwt':report['jwtShapedHits']}))
