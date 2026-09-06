"""Byte-exact original local layer packaging. Publication scan is separately required."""
import argparse,datetime,hashlib,json,zipfile,zlib,time
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--runs',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--head',required=True);p.add_argument('--tree',required=True);p.add_argument('--inventory-sha256',required=True);a=p.parse_args();O=a.out.resolve();assert O.is_relative_to(Path('/tmp').resolve())and not O.exists();O.mkdir();B=a.runs.resolve();start=datetime.datetime.now(datetime.timezone.utc).isoformat();clock=time.monotonic()
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def write(p,d):assert not p.exists();p.write_text(json.dumps(d,indent=2)+'\n')
archives=[]
for name in ['frontend-quality','governance-r2','infrastructure','migration','supply-chain','browser-r4','security-npm-audit']:
 root=B/name;receipt=root/('receipt.json'if name=='security-npm-audit'else 'layer-candidate.json');d=json.loads(receipt.read_text());assert (d['sourceHead'],d['sourceTree'],d['sourceInventorySha256'])==(a.head,a.tree,a.inventory_sha256)
 assert type(d['exitCode'])is int and d['exitCode']==0 and d['sourceStable']is True and d.get('finishedAt');assert datetime.datetime.fromisoformat(d['startedAt'])<datetime.datetime.fromisoformat(d['finishedAt'])
 paths=sorted(p for p in root.rglob('*')if p.is_file());assert not any(p.is_symlink()for p in root.rglob('*'));rows=[{'member':name+'/'+p.relative_to(root).as_posix(),'originalPath':str(p),'relativeSourcePath':p.relative_to(root).as_posix(),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}for p in paths];by={r['originalPath']:r for r in rows}
 for e in d['evidence']:
  r=by[str(Path(e['path']).resolve())];assert r['sha256']==e['sha256']and ('bytes'not in e or r['bytes']==e['bytes'])
 archive=O/(name+'-'+a.head[:7]+'-immutable.zip')
 with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6)as z:
  for r in rows:
   b=Path(r['originalPath']).read_bytes();assert sha(b)==r['sha256'];zi=zipfile.ZipInfo(r['member'],(1980,1,1,0,0,0));zi.create_system=3;zi.external_attr=0o100644<<16;z.writestr(zi,b,compress_type=zipfile.ZIP_DEFLATED,compresslevel=6)
 with zipfile.ZipFile(archive)as z:
  assert z.testzip()is None and z.namelist()==[r['member']for r in rows]
  for r,zi in zip(rows,z.infolist()):
   b=z.read(zi);assert sha(b)==r['sha256'] and len(b)==r['bytes'] and zlib.crc32(b)==zi.CRC;r.update(crc32=f'{zi.CRC:08x}',compressedBytes=zi.compress_size,zipMemberReadShaVerified=True)
 assert sorted(p for p in root.rglob('*')if p.is_file())==paths
 for r in rows:assert sha(Path(r['originalPath']).read_bytes())==r['sha256']
 index={'kind':'IMMUTABLE_LOCAL_LAYER_ARCHIVE_INDEX','layer':name,'sourceHead':a.head,'sourceTree':a.tree,'sourceInventorySha256':a.inventory_sha256,'runId':d.get('runId'),'originalStartedAt':d['startedAt'],'originalFinishedAt':d['finishedAt'],'originalResult':d['result'],'originalExitCode':d['exitCode'],'originalSourceStable':d['sourceStable'],'originalCollectorTestcaseCounts':d.get('testcaseCounts'),'originalReceipt':ref(receipt),'originalReceiptMember':by[str(receipt.resolve())]['member'],'archive':ref(archive),'memberCount':len(rows),'uncompressedBytes':sum(r['bytes']for r in rows),'allOriginalEvidenceReferencesVerified':len(d['evidence']),'allOriginalBytesAndFilesetUnchanged':True,'allZipCrcAndMemberShaVerified':True,'files':rows,'reexecutionPerformed':False,'publicationScan':'PENDING_SCAN_OF_EXACT_ARCHIVE_AND_METADATA_BYTES','productPassInferred':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False}
 ip=O/(name+'-ZIP-MEMBER-INDEX.json');write(ip,index);archives.append({'layer':name,'archive':ref(archive),'index':ref(ip),'members':len(rows)});print(json.dumps(archives[-1]),flush=True)
write(O/'ARCHIVING-RECEIPT.json',{'kind':'BYTE_EXACT_CURRENT_LOCAL_LAYER_ARCHIVING','sourceHead':a.head,'sourceTree':a.tree,'sourceInventorySha256':a.inventory_sha256,'startedAt':start,'finishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsedSeconds':time.monotonic()-clock,'builder':ref(__file__),'archives':archives,'rawPublicationAuthorized':False,'originalsUnchanged':True,'productTestsRun':0})
write(O/'INDEX.json',{'kind':'ARCHIVING_PACKAGE_INDEX','files':[ref(p)for p in sorted(O.iterdir())if p.is_file()]})
