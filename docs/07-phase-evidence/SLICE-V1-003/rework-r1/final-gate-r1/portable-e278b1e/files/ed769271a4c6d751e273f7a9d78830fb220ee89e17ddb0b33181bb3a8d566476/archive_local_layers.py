#!/usr/bin/env python3
"""Archive exact already-scanned local layer inventories; never infer product PASS."""
import datetime,hashlib,json,shutil,sys,time,zipfile,zlib
from pathlib import Path
OUT=Path(__file__).resolve().parent
SCAN=Path('/tmp/slice3-local-layer-publication-scans-02e6172-r1')
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def write(p,d):p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
started=datetime.datetime.now(datetime.timezone.utc).isoformat();clock=time.monotonic()
originalScanIndex=SCAN/'INDEX.json';scanIndex=json.loads(originalScanIndex.read_text());published=[]
for e in scanIndex['files']+[ref(originalScanIndex)]:
 p=Path(e['path']);data=p.read_bytes();assert sha(data)==e['sha256'];assert len(data)==e.get('size',e.get('bytes'))
 relative=p.relative_to(SCAN);dest=OUT/'publication-scan'/relative;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
 assert dest.read_bytes()==data
 published.append({'originalPath':str(p),'packageRelativePath':dest.relative_to(OUT).as_posix(),'sha256':sha(data),'bytes':len(data)})
write(OUT/'PUBLICATION-SCAN-COPY-INDEX.json',{'kind':'UNCHANGED_SCAN_EVIDENCE_PORTABLE_COPY_INDEX','sourceIndex':ref(originalScanIndex),'files':published,'boundary':'Original scan output bytes are copied unchanged. Their absolute provenance paths remain original; packageRelativePath enables offline lookup. No raw or scan result is rewritten.'})
archives=[]
for name in ['frontend-quality','governance-r2','infrastructure','migration','supply-chain','security-npm-audit-r2','browser','browser-r2','browser-r3']:
 scan=SCAN/name;inventoryPath=scan/'ORIGINAL-FILE-SHA-INDEX.json';inv=json.loads(inventoryPath.read_text());scanReceiptPath=scan/'PUBLICATION-SCAN-RECEIPT.json';scanReceipt=json.loads(scanReceiptPath.read_text())
 assert scanReceipt['scanResult']=='COMPLETE_ZERO_SHAPE_HITS_ORIGINALS_STABLE' and scanReceipt['originalBytesAndFilesetUnchanged'] is True and scanReceipt['hitCount']==0
 assert ref(inventoryPath)['sha256']==scanReceipt['originalFileIndex']['sha256']
 root=Path(inv['originalRoot']);paths=sorted(p for p in root.rglob('*') if p.is_file());assert not any(p.is_symlink() for p in root.rglob('*'))
 assert [p.relative_to(root).as_posix() for p in paths]==[r['relativePath'] for r in inv['files']]
 rows=[]
 for r in inv['files']:
  p=Path(r['path']);assert p.resolve()==(root/r['relativePath']).resolve();data=p.read_bytes();assert sha(data)==r['sha256'] and len(data)==r['bytes']
  rows.append({'member':name+'/'+r['relativePath'],'originalPath':str(p),'relativeSourcePath':r['relativePath'],'bytes':len(data),'sha256':sha(data)})
 rows.sort(key=lambda r:r['member']);byPath={str(Path(r['originalPath']).resolve()):r for r in rows}
 receiptPath=Path(scanReceipt['layerReceipt']['path']);assert sha(receiptPath.read_bytes())==scanReceipt['layerReceipt']['sha256'];layer=json.loads(receiptPath.read_text())
 assert layer['sourceHead']==inv['sourceHead']==scanReceipt['sourceHead']=='02e617278793b4458dfb7cac74c2a937f1dfcb00'
 assert layer['sourceTree']==inv['sourceTree']==scanReceipt['sourceTree']=='8d8dd0b3429797a0e01b2ff91c1ba5c09de17dcc'
 assert layer['sourceInventorySha256']==inv['sourceInventorySha256']==scanReceipt['sourceInventorySha256']=='9706e844337851329c5e30954598162ac52144c82facaf736b04475c25f0c6ec'
 assert layer['result']==inv['originalCommandResult']==scanReceipt['originalCommandResult'] and layer['sourceStable'] is True and layer.get('finishedAt')
 for e in layer['evidence']:
  actual=byPath[str(Path(e['path']).resolve())];assert actual['sha256']==e['sha256'];assert 'bytes' not in e or actual['bytes']==e['bytes']
 archive=OUT/(name+'-02e6172-immutable.zip')
 with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for r in rows:
   data=Path(r['originalPath']).read_bytes();assert sha(data)==r['sha256'] and len(data)==r['bytes']
   zi=zipfile.ZipInfo(r['member'],date_time=(1980,1,1,0,0,0));zi.compress_type=zipfile.ZIP_DEFLATED;zi.create_system=3;zi.external_attr=0o100644<<16
   z.writestr(zi,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=6)
 with zipfile.ZipFile(archive) as z:
  assert z.testzip() is None and z.namelist()==[r['member'] for r in rows]
  for r,zi in zip(rows,z.infolist()):
   data=z.read(zi);assert len(data)==r['bytes'] and sha(data)==r['sha256'] and zlib.crc32(data)==zi.CRC
   assert zi.date_time==(1980,1,1,0,0,0) and zi.create_system==3 and zi.external_attr>>16==0o100644 and zi.compress_type==zipfile.ZIP_DEFLATED
   r.update(crc32=f'{zi.CRC:08x}',compressedBytes=zi.compress_size,compressionMethod=zi.compress_type,unixMode='100644',timestamp='1980-01-01T00:00:00',zipMemberReadShaVerified=True)
 after=sorted(p for p in root.rglob('*') if p.is_file());assert after==paths and not any(p.is_symlink() for p in root.rglob('*'))
 for r in rows:
  data=Path(r['originalPath']).read_bytes();assert len(data)==r['bytes'] and sha(data)==r['sha256']
 index={'kind':'IMMUTABLE_LOCAL_LAYER_ARCHIVE_INDEX','layer':name,'sourceHead':layer['sourceHead'],'sourceTree':layer['sourceTree'],'sourceInventorySha256':layer['sourceInventorySha256'],'runId':layer['runId'],'originalStartedAt':layer['startedAt'],'originalFinishedAt':layer['finishedAt'],'originalResult':layer['result'],'originalExitCode':layer['exitCode'],'originalSourceStable':layer['sourceStable'],'originalCollectorTestcaseCounts':layer.get('testcaseCounts'),'originalReceipt':ref(receiptPath),'originalReceiptMember':byPath[str(receiptPath.resolve())]['member'],'boundary':scanReceipt['boundary'],'archive':ref(archive)|{'packageRelativePath':archive.name,'compression':'ZIP_DEFLATED_LEVEL_6','memberOrder':'lexicographic','memberTimestamp':'1980-01-01T00:00:00','memberMode':'100644'},'originalInventory':ref(inventoryPath),'portableOriginalInventory':'publication-scan/'+name+'/ORIGINAL-FILE-SHA-INDEX.json','publicationScanReceipt':ref(scanReceiptPath),'portablePublicationScanReceipt':'publication-scan/'+name+'/PUBLICATION-SCAN-RECEIPT.json','memberCount':len(rows),'uncompressedBytes':sum(r['bytes'] for r in rows),'allOriginalEvidenceReferencesVerified':len(layer['evidence']),'allOriginalBytesAndFilesetUnchanged':True,'allZipCrcAndMemberShaVerified':True,'files':rows,'reexecutionPerformed':False,'productPassInferred':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'publicationAuthorized':False,'noOcrOrPixelReview':True}
 ip=OUT/(name+'-ZIP-MEMBER-INDEX.json');write(ip,index)
 archives.append({'layer':name,'archive':index['archive'],'memberIndex':ref(ip)|{'packageRelativePath':ip.name},'originalResult':layer['result'],'originalExitCode':layer['exitCode'],'members':len(rows),'originalsUnchanged':True,'zipCrcAndMemberShaVerified':True})
 print(json.dumps({'layer':name,'archiveSha256':index['archive']['sha256'],'archiveBytes':index['archive']['bytes'],'members':len(rows),'originalResult':layer['result']}),flush=True)
receipt={'kind':'INDEPENDENT_LOCAL_LAYER_ARCHIVING_RECEIPT','startedAt':started,'finishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsedSeconds':round(time.monotonic()-clock,3),'argv':sys.argv,'source':ref(Path(__file__)),'sourceHead':'02e617278793b4458dfb7cac74c2a937f1dfcb00','sourceTree':'8d8dd0b3429797a0e01b2ff91c1ba5c09de17dcc','archives':archives,'copiedScanEvidenceIndex':ref(OUT/'PUBLICATION-SCAN-COPY-INDEX.json'),'scopeBoundary':['Each layer remains independently attributed; no combined product test totals.','Original successful commands are not automatically final engineering PASS. Browser r1/r2/r3 remain whole-layer failures.','All archive members match exact previously scanned inventories; no source/evidence content was rewritten.','Backend/currentCI archives and any later browser r4 are outside this scope.','Copied scanner results retain original exact bytes and absolute provenance; portable relative lookup is explicit.','Archive packaging is not reexecution, Controller approval or publication.'],'originalsUnchanged':True,'archiveValidation':'EVERY_MEMBER_SHA_SIZE_CRC_ORDER_TIMESTAMP_AND_MODE_VERIFIED','productTestsRun':0,'repositoryModified':False,'publicationPerformed':False}
write(OUT/'ARCHIVING-RECEIPT.json',receipt)
