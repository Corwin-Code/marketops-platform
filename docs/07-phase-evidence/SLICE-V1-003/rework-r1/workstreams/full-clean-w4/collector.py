"""Run only after root gives the exclusive final Maven window and a clean checkpoint."""
import datetime,hashlib,json,os,pathlib,platform,shutil,subprocess,sys,time,xml.etree.ElementTree as ET,zipfile
repo=pathlib.Path('/Users/chzhengx/Code/personal/marketops-platform');cwd=repo/'backend/marketops-server'
out=pathlib.Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=False)
shutil.copyfile(pathlib.Path(__file__),out/'collector.py')
shutil.copyfile('/tmp/reconcile-full-testcase-counts.py',out/'reconcile-full-testcase-counts.py')
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def cmd(args,where=repo):
 r=subprocess.run(args,cwd=where,text=True,capture_output=True);return {'argv':args,'exitCode':r.returncode,'stdout':r.stdout.strip(),'stderr':r.stderr.strip()}
def git(*args):
 r=cmd(['git',*args]);assert r['exitCode']==0,r;return r['stdout']
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def sources():
 raw=subprocess.check_output(['git','ls-files','-z','--','backend/marketops-server/src','backend/marketops-server/pom.xml','backend/marketops-server/mvnw','backend/marketops-server/.mvn'],cwd=repo)
 return {p:sha(repo/p) for p in raw.decode().split('\0') if p and (repo/p).is_file()}
def write(name,value):(out/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
status=git('status','--porcelain=v1','--untracked-files=all')
if status:raise SystemExit('Final run refuses dirty checkout; root must first finalize exact checkpoint. '+status)
head=git('rev-parse','HEAD');tree=git('rev-parse','HEAD^{tree}');before=sources();resource=pathlib.Path('/tmp/slice3-r1-runtime-resources.json');assert resource.is_file()
shutil.copyfile(resource,out/'runtime-resources.json')
write('backend-source-before.json',before)
fixtures={str(p.relative_to(repo)):sha(p) for p in (cwd/'src/test/resources').rglob('*') if p.is_file()}
fixtures.update({str(p.relative_to(repo)):sha(p) for p in (cwd/'src/test/java').rglob('*.java') if any(k in p.name for k in ['Fixture','Seed','VerticalPathIT'])})
write('fixture-datasets.json',fixtures)
postgres='postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3'
pgSources=[cwd/'src/test/java/com/mimococo/marketops/TestDatabase.java',cwd/'src/test/java/com/mimococo/marketops/database/PostgresContainerSupport.java',cwd/'src/test/java/com/mimococo/marketops/shared/internal/migration/ManagedProfileMigrationIT.java']
import re
pgImages=sorted({image for source in pgSources for image in re.findall(r'postgres:[^\s\"]+',source.read_text())})
pgInventory={'recordedAtUTC':now(),'scope':'Exact requested PostgreSQL images from the full-suite fixture source, with local image IDs and repository digests; all containers are isolated test fixtures.','sourceReferences':{str(p.relative_to(repo)):sha(p) for p in pgSources},'images':[dict(requestedImage=image,**cmd(['docker','image','inspect',image,'--format','{{json .Id}} {{json .RepoDigests}}'])) for image in pgImages]}
write('postgres-test-image-inventory.json',pgInventory)
argv=['./mvnw','-B','-ntp','clean',f'-Dmarketops.build.gitCommit={head}','verify']
receipt={'kind':'EXACT_CLEAN_FULL_VERIFY','cwd':str(cwd),'command':argv,'head':head,'tree':tree,'branch':git('branch','--show-current'),'initialGitStatus':status,'initialClean':True,'scope':'No test selectors, exclusions or skip flags. All configured unit/integration/architecture/coverage verification phases.','safeRuntimeInputs':{'SLICE3_RUNTIME_RESOURCE_RECEIPT':str(resource),'resourceReceiptSha256':sha(resource),'preservedResourceReceipt':str(out/'runtime-resources.json'),'measuredHostAndDockerResources':json.loads(resource.read_text()),'javaHome':os.environ.get('JAVA_HOME'),'platform':platform.platform(),'machine':platform.machine(),'cpuMemory':cmd(['sysctl','-n','hw.physicalcpu','hw.logicalcpu','hw.memsize']),'javaVersion':cmd(['java','-version']),'postgresImage':postgres,'postgresImageMetadata':cmd(['docker','image','inspect',postgres,'--format','{{json .RepoDigests}}'])},'backendSourceBeforeSha256':sha(out/'backend-source-before.json'),'fixtureDatasetManifestSha256':sha(out/'fixture-datasets.json'),'boundary':'All execution must remain isolated synthetic fixtures; production_write_enabled=false; no real Provider/shared/production/Ready/merge/force-push.'}
receipt['safeRuntimeInputs']['allPostgresImageInventorySha256']=sha(out/'postgres-test-image-inventory.json')
receipt['criterionIds']=[f'S3-AC-{i:03}' for i in range(1,200)]
receipt['findingIds']=[f'S3-DR-{i:03}' for i in range(1,23)]
receipt['applicableScopeDeclaration']='The full backend Maven reactor and every configured unit, database integration, protected/fresh migration, architecture, guardrail and JaCoCo gate are executed. Criterion IDs001-199 and all22 findings identify the engineering review context; this receipt attests only assertions actually executed in backend suites. It does not independently establish browser UX, remote CI/publication, all22-finding closure or Owner/Controller acceptance. AC200 independent review is excluded from self-verification.'
write('run-receipt.json',receipt);env=os.environ.copy();env['SLICE3_RUNTIME_RESOURCE_RECEIPT']=str(resource)
receipt['startedAtUTC']=now();started=time.monotonic();write('run-receipt.json',receipt)
with (out/'maven-clean-verify.log').open('wb') as log: completed=subprocess.run(argv,cwd=cwd,stdout=log,stderr=subprocess.STDOUT,env=env)
receipt['finishedAtUTC']=now();receipt['elapsedSeconds']=round(time.monotonic()-started,3);receipt['mavenExitCode']=completed.returncode;receipt['mavenLogSha256']=sha(out/'maven-clean-verify.log')
after=sources();write('backend-source-after.json',after);receipt['backendSourceAfterSha256']=sha(out/'backend-source-after.json');receipt['backendSourceStable']=before==after;receipt['headAfter']=git('rev-parse','HEAD');receipt['treeAfter']=git('rev-parse','HEAD^{tree}');receipt['gitStatusAfter']=git('status','--porcelain=v1','--untracked-files=all');receipt['cleanAfter']=not receipt['gitStatusAfter']
artifacts=[];missing=[]
def preserve(source,relative):
 if not source.is_file():missing.append(str(source));return None
 destination=out/'artifacts'/relative;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,destination)
 artifacts.append({'source':str(source),'artifact':str(destination),'sha256':sha(destination),'bytes':destination.stat().st_size})
 return destination
suites=[];parseErrors=[]
for phase in ['surefire','failsafe']:
 for path in sorted((cwd/'target'/f'{phase}-reports').glob('TEST-*.xml')):
  try:
   saved=preserve(path,pathlib.Path(f'{phase}-reports')/path.name)
   xml=ET.parse(saved).getroot();suites.append({'phase':phase,'name':xml.attrib.get('name'),'tests':int(xml.attrib.get('tests',0)),'failures':int(xml.attrib.get('failures',0)),'errors':int(xml.attrib.get('errors',0)),'skipped':int(xml.attrib.get('skipped',0)),'seconds':xml.attrib.get('time'),'path':str(saved),'originalTargetPath':str(path),'sha256':sha(saved)})
  except Exception as error:parseErrors.append({'path':str(path),'error':str(error)})
for name in ['advertising-capacity-receipt.json','advertising-capacity-dataset.json','advertising-capacity-source-inputs.json']:
 preserve(cwd/'target'/name,pathlib.Path('capacity')/name)
jacoco=cwd/'target/site/jacoco'
if not jacoco.is_dir():missing.append(str(jacoco))
else:
 for path in sorted(jacoco.rglob('*')):
  if path.is_file():preserve(path,pathlib.Path('jacoco-report')/path.relative_to(jacoco))
preserve(cwd/'target/jacoco.exec',pathlib.Path('jacoco.exec'))
# Keep the exact verified executable artifact and its embedded source stamp outside target.
jarIdentity=[]
for jar in sorted((cwd/'target').glob('*.jar')):
 saved=preserve(jar,pathlib.Path('jars')/jar.name)
 entry={'source':str(jar),'preservedJar':str(saved),'sha256':sha(saved),'bytes':saved.stat().st_size,'head':head,'tree':tree,'embeddedBuildInfo':[]}
 with zipfile.ZipFile(saved) as archive:
  for name in archive.namelist():
   if name.endswith('META-INF/build-info.properties') or name=='META-INF/MANIFEST.MF':
    data=archive.read(name);destination=out/'artifacts/jar-metadata'/jar.name/name;destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(data)
    artifacts.append({'source':str(jar)+'!/'+name,'artifact':str(destination),'sha256':sha(destination),'bytes':len(data)})
    if name.endswith('build-info.properties'):
     props=dict(line.split('=',1) for line in data.decode().splitlines() if '=' in line and not line.startswith('#'))
     entry['embeddedBuildInfo'].append({'path':name,'sha256':sha(destination),'properties':props})
 entry['embeddedSourceStampMatchesHead']=any(info['properties'].get('build.gitCommit')==head for info in entry['embeddedBuildInfo'])
 jarIdentity.append(entry)
if not jarIdentity:missing.append('target/*.jar executable build artifact')
receipt['packagedJarSourceIdentityVerified']=bool(jarIdentity) and all(jar['embeddedSourceStampMatchesHead'] for jar in jarIdentity)
write('verified-jar-identity.json',{'head':head,'tree':tree,'backendSourceManifestSha256':receipt['backendSourceAfterSha256'],'jars':jarIdentity,'sourceStable':receipt['backendSourceStable']})
receipt['verifiedJarIdentitySha256']=sha(out/'verified-jar-identity.json')
preserve(cwd/'target/classes/META-INF/build-info.properties',pathlib.Path('build-info.properties'))
write('preserved-artifact-manifest.json',artifacts)
receipt['preservedArtifactManifestSha256']=sha(out/'preserved-artifact-manifest.json')
receipt['missingRequiredArtifacts']=missing
receipt['capacitySuite']=[row for row in suites if row['name'].endswith('.AdvertisingOrchestrationCapacityIT')]
receipt['capacityThreeTestsPassed']=sum(row['tests'] for row in receipt['capacitySuite'])==3 and all(row[key]==0 for row in receipt['capacitySuite'] for key in ['failures','errors','skipped'])
receipt['phaseCounts']={phase:{key:sum(row[key] for row in suites if row['phase']==phase) for key in ['tests','failures','errors','skipped']} for phase in ['surefire','failsafe']}
coverageFile=out/'artifacts/jacoco-report/jacoco.xml'
if coverageFile.is_file():
 coverage=ET.parse(coverageFile).getroot();receipt['jacocoCounters']={entry.attrib['type']:{'missed':int(entry.attrib['missed']),'covered':int(entry.attrib['covered'])} for entry in coverage.findall('counter')}
receipt['safeRuntimeInputs']['postgresImageMetadataAfter']=cmd(['docker','image','inspect',postgres,'--format','{{json .RepoDigests}}'])
write('test-report-inventory.json',suites);receipt['xmlParseErrors']=parseErrors;receipt['reportInventorySha256']=sha(out/'test-report-inventory.json');receipt['counts']={k:sum(s[k] for s in suites) for k in ['tests','failures','errors','skipped']};receipt['counts']['suites']=len(suites)
receipt['completePass']=completed.returncode==0 and receipt['backendSourceStable'] and receipt['headAfter']==head and receipt['cleanAfter'] and not parseErrors and not missing and receipt['capacityThreeTestsPassed'] and receipt['packagedJarSourceIdentityVerified'] and all(v['tests']>0 for v in receipt['phaseCounts'].values()) and bool(suites) and all(receipt['counts'][k]==0 for k in ['failures','errors','skipped'])
# Preserve both the XML declaration and every actual testcase node. Surefire's
# legacy nested display-name collision can report one fewer suite test than it
# actually emits; the console total and raw node total remain independently visible.
reconciled=subprocess.run([sys.executable,str(out/'reconcile-full-testcase-counts.py'),str(out)],capture_output=True,text=True)
(out/'testcase-count-reconciliation.log').write_text(reconciled.stdout+reconciled.stderr)
receipt['testcaseReconciliationExitCode']=reconciled.returncode
receipt['collectorSha256']=sha(out/'collector.py')
receipt['testcaseReconcilerSha256']=sha(out/'reconcile-full-testcase-counts.py')
if reconciled.returncode==0:
 actual=json.loads((out/'testcase-count-reconciliation.json').read_text())
 receipt['suiteDeclaredCounts']=receipt['counts']
 receipt['actualTestcaseNodeCounts']=actual['summary']
 receipt['mavenConsoleSummaries']=actual['mavenConsoleSummaries']
 receipt['suiteDeclarationMismatches']=actual['mismatchedSuites']
 receipt['testcaseReconciliationSha256']=sha(out/'testcase-count-reconciliation.json')
 receipt['everyActualTestcaseNodePassed']=actual['everyActualTestcaseNodePassed']
 receipt['mavenConsoleMatchesActualNodes']=len(actual['mavenConsoleSummaries'])==2 and all(
   summary['counts']==actual['phaseCounts'][summary['phase']]['actualTestcaseNodeCounts']
   for summary in actual['mavenConsoleSummaries'])
 receipt['completePass']=receipt['completePass'] and actual['everyActualTestcaseNodePassed'] and receipt['mavenConsoleMatchesActualNodes']
else:receipt['completePass']=False
write('run-receipt.json',receipt);print(json.dumps({'out':str(out),'head':head,'exitCode':completed.returncode,'completePass':receipt['completePass'],'counts':receipt['counts'],'elapsedSeconds':receipt['elapsedSeconds']}))
sys.exit(0 if receipt['completePass'] else 1)
