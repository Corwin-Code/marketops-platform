from pathlib import Path
import subprocess,hashlib,json,tarfile,io,shutil,sys,os,datetime,time,tempfile
repo=Path('/Users/chzhengx/Code/personal/marketops-platform');head='60638b1fc1a227b50f4b3ede1ba0bb983407bfdc'
assert subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()==head
owned=Path(tempfile.mkdtemp(prefix='marketops-s3-r1-infra-w1-',dir='/tmp'));checkout=owned/'source';checkout.mkdir();(owned/'tmp').mkdir()
archive=subprocess.check_output(['git','archive',head,'infra/yandex','scripts','tests'],cwd=repo)
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
 for member in tar.getmembers():
  assert not Path(member.name).is_absolute() and '..' not in Path(member.name).parts
 tar.extractall(checkout)
source_provider=repo/'infra/yandex/bootstrap/.terraform/providers/registry.terraform.io/yandex-cloud/yandex/0.220.0/darwin_arm64'
mirror=owned/'provider-mirror';destination=mirror/'registry.terraform.io/yandex-cloud/yandex/0.220.0/darwin_arm64'
assert source_provider.is_dir();shutil.copytree(source_provider,destination)
cache=Path('/tmp/marketops-terraform-1.14.9');sums={line.split()[1]:line.split()[0] for line in (cache/'terraform_1.14.9_SHA256SUMS').read_text().splitlines()}
assert sums['terraform_1.14.9_linux_amd64.zip']=='2e5cffc20a0b48a67a76268723bd5a10b8666f69b2aa4f04906e206726bedd63'
assert hashlib.sha256((cache/'terraform_1.14.9_darwin_arm64.zip').read_bytes()).hexdigest()==sums['terraform_1.14.9_darwin_arm64.zip']
terraform=owned/'terraform';shutil.copy2(cache/'terraform',terraform);terraform.chmod(0o755)
safe={'PATH':'/usr/bin:/bin:/usr/sbin:/sbin','LANG':'C.UTF-8','TMPDIR':str(owned/'tmp')+'/', 'PYTHONDONTWRITEBYTECODE':'1'}
report={'schemaVersion':'1.0','codeHead':head,'sourceArchiveSha256':hashlib.sha256(archive).hexdigest(),'ownedDirectory':str(owned),'scope':'EXACT_W1_SOURCE_COPY_LOCAL_MOCK_PLANS','terraform':{'version':'1.14.9','platform':'darwin_arm64','archiveSha256':sums['terraform_1.14.9_darwin_arm64.zip'],'binarySha256':hashlib.sha256(terraform.read_bytes()).hexdigest(),'integrity':'Cached ZIP matches preserved SHA256SUMS; its pinned linux_amd64 entry equals infrastructure.yml; extracted binary identity was compared to ZIP bytes.'},'provider':{'name':'yandex-cloud/yandex','version':'0.220.0','installation':'LOCAL_FILESYSTEM_MIRROR_WITH_REPOSITORY_LOCKFILE_READONLY','files':[{'name':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(destination.iterdir()) if p.is_file()]},'environment':{'retainedNames':list(safe),'HOME':'NOT_PASSED','cloudCredentialVariables':'NOT_PASSED','proxyVariables':'NOT_PASSED','userTerraformCliConfig':'NOT_PASSED; verifier writes explicit CLI config','localRepositoryTerraformState':'NOT_READ_OR_REUSED'},'steps':[],'boundary':{'realProviderApiCalls':False,'realAccount':False,'apply':False,'deployment':False,'sharedOrProductionDatabase':False,'maven':False,'implementationModified':False},'sourceFiles':[]}
for directory in ['infra/yandex','scripts','tests']:
 for p in sorted((checkout/directory).rglob('*')):
  if p.is_file():report['sourceFiles'].append({'path':str(p.relative_to(checkout)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
commands=[('terraform-mock-verification',[sys.executable,'scripts/verify_terraform.py','--terraform',str(terraform),'--provider-mirror',str(mirror)]),('terraform-plan-unit',[sys.executable,'-m','unittest','discover','-s','tests','-p','test_*terraform*.py']),('runtime-unit',[sys.executable,'-m','unittest','discover','-s','tests','-p','test_yandex_runtime.py']),('telemetry-unit',[sys.executable,'-m','unittest','discover','-s','tests','-p','test_yandex_telemetry.py'])]
Path('/tmp/r1-infrastructure-w1-location.txt').write_text(str(owned))
for name,command in commands:
 before=datetime.datetime.now(datetime.timezone.utc).isoformat();start=time.monotonic();log=owned/(name+'.log')
 with log.open('w') as stream:r=subprocess.run(command,cwd=checkout,env=safe,stdout=stream,stderr=subprocess.STDOUT,timeout=1200)
 step={'name':name,'argv':command,'cwd':str(checkout),'startedAt':before,'finishedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'durationSeconds':round(time.monotonic()-start,3),'exitCode':r.returncode,'log':str(log),'logSha256':hashlib.sha256(log.read_bytes()).hexdigest()};report['steps'].append(step)
 (owned/'receipt.json').write_text(json.dumps(report,indent=2)+'\n');print(name,'PASS' if r.returncode==0 else 'FAILED',str(log),flush=True)
 if r.returncode!=0:print(log.read_text()[-5000:],flush=True)
report['result']='PASS' if all(s['exitCode']==0 for s in report['steps']) else 'PARTIAL_FAILURE'
report['terraformEvidence']=str(checkout/'build/terraform-evidence');(owned/'receipt.json').write_text(json.dumps(report,indent=2)+'\n')
print('RECEIPT',owned/'receipt.json',flush=True)
