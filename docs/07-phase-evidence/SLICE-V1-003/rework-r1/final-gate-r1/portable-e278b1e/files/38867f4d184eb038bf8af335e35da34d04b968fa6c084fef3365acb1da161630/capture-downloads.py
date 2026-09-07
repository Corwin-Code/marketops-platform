import pathlib,json,subprocess,datetime,time,hashlib
out=pathlib.Path(__file__).resolve().parent
manifest=json.loads((out/'artifacts.json').read_text())
allowed={'backend-integration-reports','backend-test-reports','backend-supply-chain'}
assert {x['name'] for x in manifest['artifacts']}==allowed
commands=[]
def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def run(name,endpoint,expected=None):
 target=out/name;assert not target.exists()
 stderr=out/(name+'.stderr');argv=['gh','api',endpoint];t=time.monotonic();record={'argv':argv,'startedAt':now(),'stdout':str(target),'stderr':str(stderr)};commands.append(record)
 try:
  with target.open('wb') as so,stderr.open('wb') as se:
   p=subprocess.run(argv,stdout=so,stderr=se,timeout=300)
  record['exitCode']=p.returncode;record['sha256']=hashlib.sha256(target.read_bytes()).hexdigest();record['bytes']=target.stat().st_size
  if expected:record['expectedGithubDigest']=expected;record['githubDigestMatches']='sha256:'+record['sha256']==expected
  assert p.returncode==0 and (not expected or record['githubDigestMatches']),name
 finally:
  record['finishedAt']=now();record['elapsedSeconds']=time.monotonic()-t
  (out/'download-commands.json').write_text(json.dumps(commands,indent=2)+'\n')
for a in manifest['artifacts']:
 run(a['name']+'.zip',f"repos/Corwin-Code/marketops-platform/actions/artifacts/{a['id']}/zip",a['digest'])
run('run-logs.zip','repos/Corwin-Code/marketops-platform/actions/runs/34001582148/logs')
print(json.dumps({'downloads':len(commands),'allExitZero':all(c['exitCode']==0 for c in commands),'artifactGithubDigestsMatched':all(c.get('githubDigestMatches',True) for c in commands),'sourceCheckpoint':'7e66cf87afc15773d4f6e6e6717a86e7b8336ae5','finalClosureClaimMade':False}))
