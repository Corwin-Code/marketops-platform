#!/usr/bin/env python3
"""Exact current class-file constant-pool triage; no class loading or literal secret output."""
import ast,hashlib,io,json,pathlib,re,struct,time,zipfile,zlib
P=pathlib.Path;scan=P('/tmp/slice3-historical-1381-raw-scan-r1');orig=P('/tmp/slice3-final-execution-1381ef7-r4/backend-full');out=P('/tmp/slice3-historical-1381-publication-package-r1/triage-r2');out.mkdir(exist_ok=False)
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=P(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def read(p):return json.loads(P(p).read_bytes())
# Reuse only the independently read class-file parser function, never the prior inspection loop.
parser=ast.parse(P('/tmp/inspect_slice3_class_hits.py').read_text());only=next(n for n in parser.body if isinstance(n,ast.FunctionDef) and n.name=='cp_parse');exec(compile(ast.fix_missing_locations(ast.Module(body=[only],type_ignores=[])),'constant_pool_parser','exec'))
summary=read(scan/'SCAN-SUMMARY.json');scanInput=read(scan/'SCAN-INPUT.json');parent=read(orig/'layer-candidate.json');hits=[json.loads(s)for s in (scan/'HITS.jsonl').read_text().splitlines()];members={r['path']:r for r in map(json.loads,(scan/'MEMBERS.jsonl').read_text().splitlines())};patterns={i:re.compile(x)for i,x in enumerate(scanInput['patterns'],1)}
assert len(hits)==summary['hitCount']==5 and summary['status']=='COMPLETE' and summary['originalBytesUnchanged'] is True
assert parent['sourceHead']=='1381ef78594875a1e1304e4d9a636e49d0fab71f' and parent['sourceTree']=='d366331b9b0ad7f8eb0f90c8536f46c0d2b9ce5e'
assert sha((orig/'layer-candidate.json').read_bytes())==scanInput['files'][str((orig/'layer-candidate.json').resolve())]['sha256']
allRows=[];classes={};originalShas={};t=time.monotonic()
for ordinal,h in enumerate(hits,1):
 chunks=h['path'].split('!');outer=orig/chunks[0];data=outer.read_bytes();originalShas[str(outer)]=sha(data)
 parentMember=next(e for e in parent['evidence']if P(e['path']).resolve()==outer.resolve());assert sha(data)==parentMember['sha256']==scanInput['files'][chunks[0]]['sha256']
 chain=[{'path':chunks[0],'bytes':len(data),'sha256':sha(data)}];current=chunks[0]
 for chunk in chunks[1:]:
  name,num=chunk.rsplit('#member=',1)
  with zipfile.ZipFile(io.BytesIO(data))as z:
   entries=[e for e in z.infolist()if not e.is_dir()];entry=entries[int(num)];assert entry.filename==name;data=z.read(entry);assert zlib.crc32(data)&0xffffffff==entry.CRC
  current+='!'+chunk;assert members[current]['sha256']==sha(data)and members[current]['bytes']==len(data)
  chain.append({'path':current,'ordinalAmongFiles':int(num),'bytes':len(data),'sha256':sha(data),'crc32':f'{entry.CRC:08x}'})
 cp,end=cp_parse(data);text=data.decode('utf8',errors='replace');matches=[m for m in patterns[h['rule']].finditer(text)if m.start()==h['characterOffset']];assert len(matches)==1;match=matches[0]
 assert sha(match.group().encode())==h['matchedSha256'] and len(match.group())==h['matchedCharacters'] and text.count('\n',0,match.start())+1==h['line']
 overlap=[]
 for i,x in cp.items():
  a=len(data[:x['start']].decode('utf8',errors='replace'));b=len(data[:x['end']].decode('utf8',errors='replace'))
  if a<match.end()and b>match.start():
   d={'constantPoolIndex':i,'tag':x['tag'],'byteStart':x['start'],'byteEnd':x['end']}
   if x['tag']==1:d.update(utf8Length=len(x['raw']),utf8Sha256=sha(x['raw']))
   else:d['references']=x.get('indices',x.get('index'))
   overlap.append(d)
 def val(i):return cp[i]['value']
 stringRefs=lambda i:[n for n,x in cp.items()if x['tag']==8 and x['index']==i]
 if ordinal==1:
  assert [x['constantPoolIndex']for x in overlap]==[90]and len(cp[90]['raw'])==27 and patterns[1].fullmatch(val(90))
  context={'role':'PEM header comparator literal','constantPoolIndex':90,'stringReferenceIndices':stringRefs(90),'entireConstantIsHeaderOnly':True,'embeddedKeyBodyInConstant':False};reason='The complete 27-byte UTF8 constant is only the standard private-key format header, referenced as a Java string. No encoded key payload is contained in this constant.'
 elif ordinal==2:
  assert [x['constantPoolIndex']for x in overlap]==[141]and val(141).startswith('Key is not in PEM-encoded PKCS#8 format, please check that the header begins with ')and len(cp[141]['raw'])==109
  context={'role':'PKCS8 format-validation error message','constantPoolIndex':141,'stringReferenceIndices':stringRefs(141),'headerIsDiagnosticSuffix':True,'embeddedKeyBodyInConstant':False};reason='The matched header occurs at the end of a fixed 109-byte PKCS8 validation error message. The constant describes required input format; it contains no key body.'
 elif ordinal==3:
  assert [x['constantPoolIndex']for x in overlap]==list(range(404,413))and val(404)=='" password="'and val(407)=='getPassword'and val(410)=='getFullName'and val(412)==' fullName="'
  interface=val(cp[170]['index']);assert interface=='org/apache/catalina/User'and cp[405]['tag']==cp[408]['tag']==11
  context={'role':'XML user-serialization syntax plus class-file reference records','constantPoolIndices':list(range(404,413)),'fixedPasswordAttributeFragmentIndex':404,'fixedFullNameAttributeFragmentIndex':412,'interface':'org/apache/catalina/User','accessorSymbols':['getPassword','getFullName'],'matchedSpanCrossesNineConstantPoolEntries':True,'literalPasswordValueAssigned':False};reason='The 75-character regex match crosses nine constant-pool records: an empty password attribute syntax fragment, binary InterfaceMethodref/NameAndType records for runtime User accessors, and the next fullName attribute fragment. Those structural bytes and method symbols are not a serialized password or secret literal.'
 elif ordinal==4:
  assert [x['constantPoolIndex']for x in overlap]==[752]and len(cp[752]['raw'])==28 and val(752).endswith('\n')and patterns[1].fullmatch(val(752)[:-1])
  context={'role':'PEM header plus newline constant','constantPoolIndex':752,'stringReferenceIndices':stringRefs(752),'headerAndOneNewlineOnly':True,'embeddedKeyBodyInConstant':False};reason='The complete 28-byte constant contains the standard header and one newline only. It is formatting syntax, with no encoded key payload.'
 else:
  assert [x['constantPoolIndex']for x in overlap]==[842]and len(cp[842]['raw'])==30 and val(842).count('\x01')==2 and patterns[1].fullmatch(val(842).split('\n')[0])
  remainder=patterns[1].sub('',val(842));assert remainder=='\n\x01\x01'
  context={'role':'StringConcatFactory format recipe','constantPoolIndex':842,'stringReferenceIndices':stringRefs(842),'concatDynamicArgumentMarkers':2,'fixedContentOnlyHeaderAndNewline':True,'embeddedKeyBodyInConstant':False};reason='The 30-byte concat recipe contains the header, newline, and two U+0001 dynamic argument markers. Runtime data is substituted by Java concatenation; this recipe does not embed key bytes.'
 noKeyBlocks=all(not re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]+-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',x['value'])for x in cp.values()if x['tag']==1)
 assert noKeyBlocks
 allRows.append({'hitId':'HISTORICAL1381-HIT-'+str(ordinal).zfill(2),'originalHit':{k:h[k]for k in ('path','rule','view','line','characterOffset','matchedCharacters','matchedSha256')},'archiveChain':chain,'constantPoolOverlap':overlap,'parsedContext':context,'classification':'FIXED_LIBRARY_FORMAT_LITERAL_NOT_SECRET'if ordinal!=3 else 'BINARY_CONSTANT_POOL_CROSS_RECORD_REGEX_FALSE_POSITIVE','reason':reason,'memberSha256':sha(data),'exactHitRequiresRedaction':False,'publishableRawForThisExactHit':True,'publicationDisposition':'NO_SECRET_REDACTION_NEEDED_FOR_THIS_EXACT_MEMBER_SHA_AND_MATCH','limitations':'Classification is bound to these exact current bytes and offset. It is not a reusable library/path waiver, a vulnerability assessment, or proof that arbitrary future content has no credentials/PII.'})
 classes[h['path']]={'path':h['path'],'sha256':sha(data),'bytes':len(data),'parsedConstantPoolEntries':len(cp),'classFileMajor':struct.unpack_from('>H',data,6)[0],'allHeaderMatchesClassified':True,'completeEmbeddedPemKeyBlockFoundInUtf8Constants':False}
assert all(sha(P(p).read_bytes())==v for p,v in originalShas.items())
result={'kind':'HISTORICAL1381_EXACT_PUBLICATION_SHAPE_HIT_TRIAGE','sourceHead':parent['sourceHead'],'sourceTree':parent['sourceTree'],'sourceInventorySha256':parent['sourceInventorySha256'],'originalScan':ref(scan/'SCAN-SUMMARY.json'),'originalHits':ref(scan/'HITS.jsonl'),'originalMemberInventory':ref(scan/'MEMBERS.jsonl'),'originalParent':ref(orig/'layer-candidate.json'),'reviewScript':ref(__file__),'parserSource':ref('/tmp/inspect_slice3_class_hits.py'),'elapsedSeconds':round(time.monotonic()-t,6),'hits':allRows,'classes':list(classes.values()),'reviewedHitCount':5,'exactShapeFalsePositiveCount':5,'unresolvedHitCount':0,'literalSecretValuesPrinted':False,'rawOrScanFilesChanged':False,'originalOuterJarStillMatchesScanAndParent':True,'priorWaiversUsed':False,'disposition':'ALL_FIVE_EXACT_HISTORICAL1381_HITS_REQUIRE_NO_SECRET_REDACTION','checkpointBoundary':'FAILED_OR_INTERRUPTED_LOCAL_BACKEND_AND_FAILED_OR_CANCELLED_CI_ONLY_NOT_CURRENT_PASS','publicationLimits':['This triage clears only the five exact current scan hits under their complete nested-member SHA identities; no historical exception or filename-only exemption is carried forward.','The original scanner covered 439 files and 25322 recursively expanded members; its bounded patterns are not a universal no-PII/no-secret theorem.','No private-key body, token value, user password, class execution or product test was emitted or executed by this review.','This is a publication-content determination for the exact hits. It neither changes the original raw scan publishableRaw=false fields nor grants new external transport authority, nine-layer closure, Controller approval or production enablement.'],'productTestsRun':0,'engineeringClosureClaimMade':False,'productionWriteEnabled':False}
(out/'EXACT-HIT-TRIAGE.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');(out/'INDEX.json').write_text(json.dumps({'files':[ref(out/'EXACT-HIT-TRIAGE.json'),ref(__file__),ref('/tmp/inspect_slice3_class_hits.py')]},indent=2)+'\n');print(json.dumps({'report':ref(out/'EXACT-HIT-TRIAGE.json'),'clearedExactHits':5,'unresolved':0,'secretTextPrinted':False}))
