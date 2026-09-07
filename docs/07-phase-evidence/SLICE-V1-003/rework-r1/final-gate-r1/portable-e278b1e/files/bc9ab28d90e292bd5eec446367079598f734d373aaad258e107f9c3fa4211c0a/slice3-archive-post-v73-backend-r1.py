#!/usr/bin/env python3
import argparse,collections,datetime,hashlib,json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
ap=argparse.ArgumentParser();ap.add_argument('--runs',type=Path,required=True);ap.add_argument('--out',type=Path,required=True);ap.add_argument('--head',required=True);ap.add_argument('--inventory-sha',required=True);ap.add_argument('--passed',type=int,required=True);args=ap.parse_args()
OUT=args.out.resolve();assert OUT.is_relative_to(Path('/tmp').resolve()) and OUT!=Path('/tmp').resolve() and not OUT.exists();OUT.mkdir()
assert args.runs.resolve().is_relative_to(Path('/tmp').resolve())
assert args.passed>0 and len(args.head)==40 and len(args.inventory_sha)==64
sha=lambda b:hashlib.sha256(b).hexdigest()
def write(p,d):p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
for short in [args.head[:7]]:
 root=args.runs/'backend-full'
 layer=json.loads((root/'layer-candidate.json').read_text());assert layer.get('finishedAt') and layer['sourceHead']==args.head and layer['result']=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and layer['exitCode']==0 and layer['sourceStable'] is True
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
 archive=OUT/('slice3-backend-'+short+'-r6-immutable.zip')
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
 classification='COMPLETE_BACKEND_SOURCE_CHECKPOINT_NINE_LAYER_ADMISSION_PENDING'
 assert layer['sourceHead']==args.head and layer['sourceInventorySha256']==args.inventory_sha and layer['result']=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and layer['exitCode']==0
 assert normalized=={'passed':args.passed,'failures':0,'errors':0,'skipped':0}
 counter={'kind':'INDEPENDENT_RAW_JUNIT_COUNT_NOT_CLOSURE','sourceHead':layer['sourceHead'],'sourceTree':layer['sourceTree'],'sourceInventorySha256':layer['sourceInventorySha256'],'checkpointDisposition':classification,'actualRawTestcaseCounts':normalized,'families':dict(families),'rawJunitFiles':len(junit_files),'rawTestcaseNodes':rawnodes,'matchesCollectorCounts':True,'productTestsReexecuted':False}
 write(OUT/(short+'-RAW-JUNIT-RECONCILIATION.json'),counter)
 index={'kind':'IMMUTABLE_BACKEND_CHECKPOINT_ARCHIVE_INDEX','sourceHead':layer['sourceHead'],'sourceTree':layer['sourceTree'],'sourceInventorySha256':layer['sourceInventorySha256'],'runId':layer['runId'],'startedAt':layer['startedAt'],'finishedAt':layer['finishedAt'],'exitCode':layer['exitCode'],'sourceStable':layer['sourceStable'],'workingTreeDirty':layer['workingTreeDirty'],'checkpointDisposition':classification,'archive':{'path':str(archive),'bytes':archive.stat().st_size,'sha256':sha(archive.read_bytes()),'compression':'ZIP_DEFLATED_LEVEL_6','memberOrder':'lexicographic','memberTimestamp':'1980-01-01T00:00:00','memberMode':'100644'},'files':rows,'originalFileCount':len(files),'memberCount':len(rows),'uncompressedBytes':sum(r['bytes'] for r in rows),'allCollectorEvidenceReferencesVerified':len(layer['evidence']),'zipCrcAndEveryMemberShaVerified':True,'allOriginalBytesUnchanged':True,'actualRawTestcaseCounts':normalized,'families':dict(families),'rawPublicationAuthorized':False,'publicationShapeScan':'PENDING_SEPARATE_SCAN','productionWriteEnabled':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False}
 write(OUT/(short+'-INDEX.json'),index)
 print(json.dumps({'checkpoint':short,'archive':index['archive'],'counts':normalized,'families':dict(families),'members':len(rows),'originalsUnchanged':True}),flush=True)
