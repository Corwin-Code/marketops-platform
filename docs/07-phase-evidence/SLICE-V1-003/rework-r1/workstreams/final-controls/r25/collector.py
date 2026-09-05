import datetime, hashlib, json, os, pathlib, shutil, subprocess, time, xml.etree.ElementTree as ET
repo=pathlib.Path('/Users/chzhengx/Code/personal/marketops-platform');cwd=repo/'backend/marketops-server'
out=pathlib.Path('/tmp/slice3-r1-final-controls-r25');out.mkdir(exist_ok=False)
classes=['AdvertisingReservationIT','AdvertisingHumanWorkflowIT','AdvertisingOrchestrationCapacityIT','AdvertisingReconciliationWorkerTest','StaffedResponseClockTest']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def git(*a):return subprocess.check_output(['git',*a],cwd=repo,text=True).strip()
def sources():return {str(p.relative_to(repo)):sha(p) for p in sorted((cwd/'src').rglob('*')) if p.is_file()}
def write(n,v):(out/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
head=git('rev-parse','HEAD');before=sources();write('source-before.json',before)
argv=['./mvnw','-B','-ntp',f'-Dmarketops.build.gitCommit={head}','-Dtest='+','.join(classes),'test']
resource=pathlib.Path('/tmp/slice3-r1-runtime-resources.json');shutil.copyfile(resource,out/'runtime-resources.json')
r={'kind':'TARGETED_R25_F013_RAW_AND_SAME_CLASS_TIME_PRECISION_REPAIR','cwd':str(cwd),'command':argv,'baseHead':head,'baseTree':git('rev-parse','HEAD^{tree}'),'workingTreeStatusBefore':git('status','--porcelain=v1'),'sourceBinding':'Exact source manifests bind W7 plus the R24 repair checkpoint and explicit Raw content deduplication, correct projected Case oracle, Task SLO as-of boundary and Reconciliation exact Duration controls. Actual R24 failures remain preserved. All listed relevant classes run completely; full unselected verify and latest CI remain separately required.','sourceBeforeSha256':sha(out/'source-before.json'),'resourcesSha256':sha(resource),'boundary':'Synthetic isolated PostgreSQL only; no real Provider/shared/production. production_write_enabled=false.','startedAtUTC':now()}
reportdir=cwd/'target/surefire-reports'
for p in reportdir.glob('TEST-*.xml'):
 if any(p.name.endswith('.'+name+'.xml') or ('.'+name+'$') in p.name for name in classes):p.unlink()
for name in ['advertising-capacity-receipt.json','advertising-capacity-dataset.json','advertising-capacity-source-inputs.json']:
 p=cwd/'target'/name
 if p.is_file():p.unlink()
write('receipt.json',r);start=time.monotonic()
with (out/'maven.log').open('wb') as log:completed=subprocess.run(argv,cwd=cwd,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,SLICE3_RUNTIME_RESOURCE_RECEIPT=str(resource)))
r.update({'finishedAtUTC':now(),'elapsedSeconds':round(time.monotonic()-start,3),'exitCode':completed.returncode,'logSha256':sha(out/'maven.log')})
after=sources();write('source-after.json',after);r['sourceAfterSha256']=sha(out/'source-after.json');r['sourceStable']=before==after
reports=[];counts={'tests':0,'failures':0,'errors':0,'skipped':0}
for p in sorted(reportdir.glob('TEST-*.xml')):
 xml=ET.parse(p).getroot();name=xml.attrib.get('name','').split('.')[-1].split('$')[0]
 if name not in classes:continue
 dest=out/'reports'/p.name;dest.parent.mkdir(exist_ok=True);shutil.copyfile(p,dest)
 cases=xml.findall('testcase');observed={'tests':len(cases),'failures':sum(c.find('failure') is not None for c in cases),'errors':sum(c.find('error') is not None for c in cases),'skipped':sum(c.find('skipped') is not None for c in cases)}
 for k in counts:counts[k]+=observed[k]
 reports.append({'class':xml.attrib['name'],'declared':{k:int(xml.attrib.get(k,0)) for k in counts},'actual':observed,'path':str(dest),'sha256':sha(dest)})
capacity=[]
for name in ['advertising-capacity-receipt.json','advertising-capacity-dataset.json','advertising-capacity-source-inputs.json']:
 source=cwd/'target'/name
 if source.is_file():
  destination=out/'capacity'/name;destination.parent.mkdir(exist_ok=True);shutil.copyfile(source,destination);capacity.append({'path':str(destination),'sha256':sha(destination)})
r['capacityArtifacts']=capacity
r['reports']=reports;r['counts']=counts;r['expectedClasses']=classes;r['missingClasses']=[c for c in classes if not any(v['class'].endswith('.'+c) for v in reports)]
r['completeSelectedClassesPass']=completed.returncode==0 and len(capacity)==3 and r['sourceStable'] and not r['missingClasses'] and not any(counts[k] for k in ['failures','errors','skipped'])
r['limit']='Complete clean unselected full verify and latest PR security/CI remain required. Historical W6/W7 browser evidence retains its actual source identity.'
write('receipt.json',r);print(json.dumps({k:r[k] for k in ['exitCode','elapsedSeconds','counts','sourceStable','missingClasses','completeSelectedClassesPass']}));raise SystemExit(0 if r['completeSelectedClassesPass'] else 1)
