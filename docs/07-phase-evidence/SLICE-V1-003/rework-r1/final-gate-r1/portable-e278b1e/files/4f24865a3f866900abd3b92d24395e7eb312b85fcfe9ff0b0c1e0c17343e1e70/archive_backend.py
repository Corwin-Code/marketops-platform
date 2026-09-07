#!/usr/bin/env python3
import collections,datetime,hashlib,json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
OUT=Path(__file__).resolve().parent
sha=lambda b:hashlib.sha256(b).hexdigest()
def write(p,d):p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
for short in ['7e66cf8','98344bb']:
 root=Path('/tmp/slice3-final-execution-'+short+'-r3/backend-full')
 layer=json.loads((root/'layer-candidate.json').read_text())
 rows=[];files=sorted(p for p in root.rglob('*') if p.is_file())
 for p in files:
  assert not p.is_symlink()
  data=p.read_bytes();rows.append({'member':'backend-full/'+p.relative_to(root).as_posix(),'originalPath':str(p),'relativeSourcePath':p.relative_to(root).as_posix(),'bytes':len(data),'sha256':sha(data)})
 disposition=root.parent/'FAILED-PARTIAL-DISPOSITION.json'
 if disposition.exists():
  data=disposition.read_bytes();rows.append({'member':disposition.name,'originalPath':str(disposition),'relativeSourcePath':'../'+disposition.name,'bytes':len(data),'sha256':sha(data)})
 rows=sorted(rows,key=lambda r:r['member']);idx={str(Path(r['originalPath']).resolve()):r for r in rows}
 for e in layer['evidence']:
  actual=idx[str(Path(e['path']).resolve())];assert actual['sha256']==e['sha256'] and ('bytes' not in e or actual['bytes']==e['bytes'])
 assert layer['sourceInventorySha256']==sha((root/'source-before.json').read_bytes())==sha((root/'source-after.json').read_bytes())
 assert layer['sourceStable'] is True
 counts=collections.Counter();families=collections.defaultdict(collections.Counter);rawnodes=[];junit_files=[]
 for r in rows:
  p=Path(r['originalPath'])
  if p.suffix!='.xml' or not any(x in p.parts for x in ['surefire-reports','failsafe-reports']):continue
  tree=ET.fromstring(p.read_bytes());nodes=list(tree.iter('testcase'))
  if not nodes:continue
  family='unit' if 'surefire-reports' in p.parts else 'integration'
  for ordinal,n in enumerate(nodes):
   status=next((x for x in ['failure','error','skipped'] if n.find(x) is not None),'passed')
   status={'failure':'failures','error':'errors'}.get(status,status);counts[status]+=1;families[family][status]+=1
   rawnodes.append({'class':n.get('classname'),'name':n.get('name'),'status':status,'ordinal':ordinal,'evidenceMember':r['member'],'evidenceSha256':r['sha256']})
  junit_files.append(r['member'])
 normalized={k:counts[k] for k in ['passed','failures','errors','skipped']}
 assert normalized==layer['testcaseCounts'],(normalized,layer['testcaseCounts'])
 archive=OUT/('slice3-backend-'+short+'-r3-immutable.zip')
 with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for r in rows:
   data=Path(r['originalPath']).read_bytes();assert sha(data)==r['sha256']
   zi=zipfile.ZipInfo(r['member'],date_time=(1980,1,1,0,0,0));zi.compress_type=zipfile.ZIP_DEFLATED;zi.create_system=3;zi.external_attr=0o100644<<16
   z.writestr(zi,data,compress_type=zipfile.ZIP_DEFLATED,compresslevel=6)
 with zipfile.ZipFile(archive) as z:
  assert z.testzip() is None
  assert z.namelist()==[r['member'] for r in rows]
  for r in rows:
   data=z.read(r['member']);assert sha(data)==r['sha256'] and len(data)==r['bytes']
 after=sorted(p for p in root.rglob('*') if p.is_file());assert after==files
 assert all(sha(Path(r['originalPath']).read_bytes())==r['sha256'] for r in rows)
 classification='COMPLETE_BACKEND_PASS_CHECKPOINT_BEFORE_SECTION_6_24_REPAIR_NOT_FINAL_CLOSURE' if short=='7e66cf8' else 'FAILED_TERMINATED_PARTIAL_CHECKPOINT_NOT_COMPLETE_BACKEND_VERIFICATION'
 counter={'kind':'INDEPENDENT_RAW_JUNIT_COUNT_NOT_CLOSURE','sourceHead':layer['sourceHead'],'sourceTree':layer['sourceTree'],'sourceInventorySha256':layer['sourceInventorySha256'],'checkpointDisposition':classification,'actualRawTestcaseCounts':normalized,'families':dict(families),'rawJunitFiles':len(junit_files),'rawTestcaseNodes':rawnodes,'matchesCollectorCounts':True,'productTestsReexecuted':False}
 write(OUT/(short+'-RAW-JUNIT-RECONCILIATION.json'),counter)
 index={'kind':'IMMUTABLE_BACKEND_CHECKPOINT_ARCHIVE_INDEX','sourceHead':layer['sourceHead'],'sourceTree':layer['sourceTree'],'sourceInventorySha256':layer['sourceInventorySha256'],'runId':layer['runId'],'startedAt':layer['startedAt'],'finishedAt':layer['finishedAt'],'exitCode':layer['exitCode'],'sourceStable':layer['sourceStable'],'workingTreeDirty':layer['workingTreeDirty'],'checkpointDisposition':classification,'archive':{'path':str(archive),'bytes':archive.stat().st_size,'sha256':sha(archive.read_bytes()),'compression':'ZIP_DEFLATED_LEVEL_6','memberOrder':'lexicographic','memberTimestamp':'1980-01-01T00:00:00','memberMode':'100644'},'files':rows,'originalFileCount':len(files),'memberCount':len(rows),'uncompressedBytes':sum(r['bytes'] for r in rows),'allCollectorEvidenceReferencesVerified':len(layer['evidence']),'zipCrcAndEveryMemberShaVerified':True,'allOriginalBytesUnchanged':True,'actualRawTestcaseCounts':normalized,'families':dict(families),'rawPublicationAuthorized':False,'publicationShapeScan':'PENDING_SEPARATE_SCAN','productionWriteEnabled':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False}
 write(OUT/(short+'-INDEX.json'),index)
 print(json.dumps({'checkpoint':short,'archive':index['archive'],'counts':normalized,'families':dict(families),'members':len(rows),'originalsUnchanged':True}),flush=True)
