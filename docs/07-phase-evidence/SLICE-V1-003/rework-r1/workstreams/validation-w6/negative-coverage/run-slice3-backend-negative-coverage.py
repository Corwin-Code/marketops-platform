from pathlib import Path
import argparse, datetime, hashlib, json, subprocess, time
p=argparse.ArgumentParser();p.add_argument('--out',required=True);a=p.parse_args()
root=Path('/Users/chzhengx/Code/personal/marketops-platform');backend=root/'backend/marketops-server';out=Path(a.out);out.mkdir(exist_ok=False)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def git(*args):return subprocess.check_output(['git',*args],cwd=root,text=True).strip()
def utc():return datetime.datetime.now(datetime.timezone.utc).isoformat()
if git('status','--porcelain'):raise SystemExit('Refuse: clean measured source required')
head=git('rev-parse','HEAD');tree=git('rev-parse','HEAD^{tree}')
files=[backend/'target/marketops-server-0.1.0-SNAPSHOT.jar',backend/'target/jacoco.exec',backend/'target/site/jacoco/jacoco.xml',backend/'pom.xml',root/'scripts/verify_coverage_thresholds.sh']
if not all(f.is_file() for f in files):raise SystemExit('Refuse: complete measured coverage and packaged JAR required')
before={str(f.relative_to(root)):sha(f) for f in files}
commands=[(['./mvnw','-B','-ntp','-Djacoco.line.coverage=1.00','-Djacoco.branch.coverage=1.00','jacoco:check@verify-coverage'],backend,'forced-threshold.log'),(['bash','scripts/verify_coverage_thresholds.sh','backend'],root,'repository-enforcement-script.log')]
records=[]
for argv,cwd,name in commands:
 start=utc();tick=time.monotonic()
 with (out/name).open('wb') as log:r=subprocess.run(argv,cwd=cwd,stdout=log,stderr=subprocess.STDOUT)
 records.append({'argv':argv,'cwd':str(cwd),'startedAtUTC':start,'finishedAtUTC':utc(),'elapsedSeconds':round(time.monotonic()-tick,3),'exitCode':r.returncode,'log':name,'logSha256':sha(out/name)})
body=(out/'forced-threshold.log').read_text(errors='replace').lower()
reason='coverage checks have not been met' in body or 'rule violated' in body
after={str(f.relative_to(root)):sha(f) for f in files}
sourceStable=git('rev-parse','HEAD')==head and git('status','--porcelain')==''
receipt={'kind':'ACTUAL_BACKEND_COVERAGE_THRESHOLD_REFUSAL','head':head,'tree':tree,'commands':records,'expectedFailureObserved':records[0]['exitCode']!=0,'expectedCoverageReasonObserved':reason,'repositoryScriptPass':records[1]['exitCode']==0,'beforeSha256':before,'afterSha256':after,'artifactBytesUnchanged':before==after,'sourceStable':sourceStable,'scope':'Only existing complete execution data is checked. No recompilation, package, test selection, migration, Provider or application run. 100% is a deliberately impossible proof threshold; accepted thresholds are unchanged.','collectorSha256':sha(Path(__file__))}
receipt['pass']=all([receipt['expectedFailureObserved'],reason,receipt['repositoryScriptPass'],before==after,sourceStable])
(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');(out/Path(__file__).name).write_bytes(Path(__file__).read_bytes())
print(json.dumps({'pass':receipt['pass'],'receipt':str(out/'receipt.json')}))
raise SystemExit(0 if receipt['pass'] else 1)
