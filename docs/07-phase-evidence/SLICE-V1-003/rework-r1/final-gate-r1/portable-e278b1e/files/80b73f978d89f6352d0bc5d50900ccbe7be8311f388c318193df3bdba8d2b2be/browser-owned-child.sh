#!/bin/bash
# Prepared execution input only; parent reviews and runs after releasing Maven/Docker slot.
set -euo pipefail
if [ "${1:-}" != --clean-child ]; then
  exec /usr/bin/env -i HOME=/Users/chzhengx USER=chzhengx LOGNAME=chzhengx \
    PATH='/Users/chzhengx/.sdkman/candidates/java/21.0.10-zulu/bin:/Users/chzhengx/Library/Application Support/fnm/node-versions/v24.19.0/installation/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin' \
    JAVA_HOME=/Users/chzhengx/.sdkman/candidates/java/21.0.10-zulu \
    LANG=C.UTF-8 TZ=UTC CI=true MAVEN_SKIP_RC=true \
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_SYSTEM=/dev/null GIT_CONFIG_GLOBAL=/dev/null GIT_TERMINAL_PROMPT=0 \
    /bin/bash --noprofile --norc "$0" --clean-child
fi
umask 077
export SLICE3_REPO=/Users/chzhengx/Code/personal/marketops-platform
export SLICE3_SOURCE_HEAD=e278b1e3d8541aeb806e41d6cbef4deac8d16d06
export SLICE3_SOURCE_TREE=178132dd32a92e59e320eb100774b5bb9f6fb248
export SLICE3_BASELINE=/private/tmp/slice3-final-drivers-e278b1e-r6/expected-source-inventory.json
export SLICE3_BASELINE_SHA=73cc1dc47aebd86cf93de3afbdfe4d905b6c5b4f8b1335488f28ca63fe963eda
export SLICE3_RUNS=/private/tmp/slice3-final-execution-e278b1e-r6
export SLICE3_PUBLIC="$SLICE3_REPO/build/final-gate-r6-e278b1e"
export SLICE3_BROWSER_PUBLIC="$SLICE3_PUBLIC/browser-r4"
export SLICE3_REAL_DOCKER=/usr/local/bin/docker
cd "$SLICE3_REPO"
test "$(git rev-parse HEAD)" = "$SLICE3_SOURCE_HEAD"
test "$(git rev-parse 'HEAD^{tree}')" = "$SLICE3_SOURCE_TREE"
test "$(git branch --show-current)" = feat/SLICE-V1-003-advertising-traffic-efficiency
test -z "$(git status --porcelain=v1 --untracked-files=all)"
test "$(node --version)" = v24.19.0
# A fresh layer directory is mandatory; an earlier failed run is never overwritten.
test ! -e "$SLICE3_BROWSER_PUBLIC"
# The outer collector owns/refuses reuse of its candidate directory.
mkdir -p "$SLICE3_BROWSER_PUBLIC"
export SLICE3_BROWSER_WORK="$(mktemp -d /tmp/slice3-final-browser-work.XXXXXX)"
export SLICE3_BROWSER_PRIVATE="$(mktemp -d /tmp/slice3-final-browser-evidence.XXXXXX)"
export SLICE3_BROWSER_SOURCE="$SLICE3_BROWSER_WORK/source"
export SLICE3_BROWSER_PROJECT="slice3-fg-legacy-$(basename "$SLICE3_BROWSER_WORK" | tr '[:upper:].' '[:lower:]-')"
export SLICE3_BROWSER_JOURNAL="$SLICE3_BROWSER_PUBLIC/owned-docker-events.jsonl"
export SLICE3_BROWSER_HELPER="$SLICE3_BROWSER_WORK/driver.py"
export SLICE3_BROWSER_ADAPTER=frontend/marketops-console/playwright.legacy-isolated.config.ts
export SLICE3_BROWSER_ORIGINAL_ADAPTER=docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/browser-w6/legacy-browser-r2/legacy-isolation-adapter.ts
export TMPDIR="$SLICE3_BROWSER_WORK/tmp/"
mkdir -p "$TMPDIR" "$SLICE3_BROWSER_WORK/bin" "$SLICE3_BROWSER_WORK/java-tmp"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$SLICE3_BROWSER_WORK/java-tmp"
printf '%s\n' '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0" />' > "$SLICE3_BROWSER_WORK/maven-settings.xml"
: > "$SLICE3_BROWSER_WORK/npm-user.rc"
: > "$SLICE3_BROWSER_WORK/npm-global.rc"
export npm_config_cache=/tmp/slice3-finalgate-npm-cache
export npm_config_userconfig="$SLICE3_BROWSER_WORK/npm-user.rc"
export npm_config_globalconfig="$SLICE3_BROWSER_WORK/npm-global.rc"
# This exact repository mvnw consumes MAVEN_CONFIG, not MAVEN_ARGS.
export MAVEN_CONFIG="-Dmarketops.build.gitCommit=$SLICE3_SOURCE_HEAD -s $SLICE3_BROWSER_WORK/maven-settings.xml -gs $SLICE3_BROWSER_WORK/maven-settings.xml"
export COMPOSE_PROJECT_NAME="$SLICE3_BROWSER_PROJECT"
export MARKETOPS_SOURCE_HEAD_SHA="$SLICE3_SOURCE_HEAD"
export PLAYWRIGHT_HTML_OPEN=never
cat > "$SLICE3_BROWSER_HELPER" <<'PY'
#!/usr/bin/python3
import errno,hashlib,json,os,re,shutil,socket,subprocess,sys,time,zipfile
from pathlib import Path
E=os.environ; root=Path(E['SLICE3_REPO']); src=Path(E['SLICE3_BROWSER_SOURCE']); public=Path(E['SLICE3_BROWSER_PUBLIC']); private=Path(E['SLICE3_BROWSER_PRIVATE']); docker=E['SLICE3_REAL_DOCKER']; journal=Path(E['SLICE3_BROWSER_JOURNAL'])
def sha(p): return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(p,x): Path(p).write_text(json.dumps(x,ensure_ascii=False,indent=2)+'\n')
def git(p,*a): return subprocess.check_output(['git',*a],cwd=p).decode().strip()
def dcall(*a,check=True): return subprocess.run([docker,*a],text=True,capture_output=True,check=check)
def event(x):
 x={'at':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),**x}
 with journal.open('a') as f: f.write(json.dumps(x)+'\n')
def events(): return [json.loads(x) for x in journal.read_text().splitlines()] if journal.exists() else []
def attrs(name):
 raw=git(src,'check-attr','--cached','-z','text','eol','--',name).split('\0')
 return {raw[i+1]:raw[i+2] for i in range(0,len(raw)-2,3)}
DECLARED_CRLF_SOURCE_PATHS=frozenset({'backend/marketops-server/mvnw.cmd','scripts/bootstrap-repo.ps1'})
def source_check(stage):
 baseline=Path(E['SLICE3_BASELINE']); assert sha(baseline)==E['SLICE3_BASELINE_SHA']
 files=json.loads(baseline.read_text())['files']; assert len(files)==1280
 result=[]
 for row in files:
  name=row['path']; rp=root/name; cp=src/name
  assert rp.is_file() and rp.resolve().is_relative_to(root.resolve()) and sha(rp)==row['sha256'],('Root input changed',name)
  assert cp.is_file() and cp.resolve().is_relative_to(src.resolve()),('Clone input absent/escaped',name)
  rb=rp.read_bytes(); cb=cp.read_bytes(); equal=rb==cb; allowance=None
  if not equal:
   assert name in DECLARED_CRLF_SOURCE_PATHS,('Byte difference outside exact CRLF path allowance',name)
   a=attrs(name)
   assert a.get('eol')=='crlf' and a.get('text')!='unset' and rb.replace(b'\r\n',b'\n')==cb.replace(b'\r\n',b'\n'),('Undeclared byte difference',name)
   allowance='DECLARED_GIT_EOL_CRLF_ONLY'
  result.append({'path':name,'sourceSha256':row['sha256'],'cloneSha256':sha(cp),'cloneBytes':len(cb),'exactBytes':equal,'normalization':allowance})
 adapter=src/E['SLICE3_BROWSER_ADAPTER']; original=src/E['SLICE3_BROWSER_ORIGINAL_ADAPTER']
 if adapter.exists(): assert adapter.read_bytes()==original.read_bytes()
 status=git(src,'status','--porcelain=v1','--untracked-files=all').splitlines()
 assert all(s=='?? '+E['SLICE3_BROWSER_ADAPTER'] for s in status),('Unexpected clone change',status)
 assert git(src,'rev-parse','HEAD')==E['SLICE3_SOURCE_HEAD'] and git(src,'rev-parse','HEAD^{tree}')==E['SLICE3_SOURCE_TREE']
 if stage=='after':
  prior=json.loads((public/'clone-source-before.json').read_text())['files']; assert prior==result, 'Clone source bytes changed during execution'
 save(public/f'clone-source-{stage}.json',{'sourceHead':E['SLICE3_SOURCE_HEAD'],'sourceTree':E['SLICE3_SOURCE_TREE'],'baselineSha256':sha(baseline),'fileCount':len(files),'exactCount':sum(r['exactBytes'] for r in result),'declaredCrlfCount':sum(not r['exactBytes'] for r in result),'files':result,'additionalAdapter':{'path':E['SLICE3_BROWSER_ADAPTER'],'sha256':sha(adapter)} if adapter.exists() else None})
def snapshot(name):
 # These selected fields exclude Config.Env and container command arguments.
 value={'containers':dcall('container','ls','-a','--no-trunc','--format','{{.ID}}\t{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}').stdout.splitlines(),'networks':dcall('network','ls','--no-trunc','--format','{{.ID}}\t{{.Name}}\t{{.Driver}}').stdout.splitlines(),'volumes':dcall('volume','ls','--format','{{.Name}}\t{{.Driver}}').stdout.splitlines()}
 save(public/f'docker-{name}.json',value); return value

def check_legacy_namespace():
 project=E['SLICE3_BROWSER_PROJECT']; found={}
 for typ,cmd in [('container',['container','ls','-aq']),('network',['network','ls','-q']),('volume',['volume','ls','-q'])]:
  found[typ]=dcall(*cmd,'--filter','label=com.docker.compose.project='+project).stdout.splitlines()
 assert not any(found.values()),'Refusing a pre-existing Compose project namespace'
 save(public/'legacy-empty-namespace.json',{'project':project,'preexistingResources':found,'emptyBeforeAnyComposeOperation':True})

def port_probe(port,timeout):
 # A live wildcard listener may coexist with a reuse-address loopback bind on
 # macOS. Require an explicit TCP refusal before testing the new listener.
 with socket.socket() as probe:
  probe.settimeout(timeout)
  status=probe.connect_ex(('127.0.0.1',port))
 if status==0: return {'available':False,'reason':'ACTIVE_TCP_LISTENER','connectErrno':status}
 if status!=errno.ECONNREFUSED: raise OSError(status,'Port preflight did not establish TCP refusal')
 try:
  with socket.socket() as probe:
   probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
   probe.bind(('127.0.0.1',port)); probe.listen(1)
 except OSError as error:
  if error.errno!=errno.EADDRINUSE: raise
  return {'available':False,'reason':'LISTENER_BIND_IN_USE','connectErrno':status,'bindErrno':error.errno}
 return {'available':True,'reason':'TCP_REFUSED_AND_NEW_LISTENER_BOUND','connectErrno':status}

def check_ports():
 # Observe release for at most ten seconds; never kill a process or claim a
 # reservation. Original reuseExistingServer=false/strictPort remain authoritative.
 started=time.monotonic(); deadline=started+10.0; observations=[]
 def receipt(outcome):
  with (public/'port-preflight-observations.jsonl').open('a') as output:
   output.write(json.dumps({'observedAtUnixSeconds':time.time(),'outcome':outcome,'elapsedSeconds':time.monotonic()-started,'attempts':observations,'ports':[8080,8082,4173],'reservationClaimMade':False})+'\n')
 while True:
  if time.monotonic()>=deadline:
   receipt('BOUNDED_TIMEOUT'); raise RuntimeError('Original browser ports not listenable within bounded 10-second observation')
  states={}
  for port in (8080,8082,4173):
   remaining=deadline-time.monotonic()
   if remaining<=0:
    receipt('BOUNDED_TIMEOUT'); raise RuntimeError('Original browser port observation reached its ten-second bound')
   try: states[str(port)]=port_probe(port,min(0.2,remaining))
   except OSError:
    receipt('UNEXPECTED_SOCKET_ERROR'); raise
  observations.append({'elapsedSeconds':time.monotonic()-started,'states':states})
  if all(row['available'] for row in states.values()) and time.monotonic()<deadline:
   receipt('ALL_PORTS_LISTENABLE'); return
  remaining=deadline-time.monotonic()
  if remaining>0: time.sleep(min(0.1,remaining))

def docker_proxy(args):
 project=E['SLICE3_BROWSER_PROJECT']; op=args[0] if args else ''; created=events()
 ownedcontainers={x['name'] for x in created if x['kind']=='AD_CONTAINER_CREATED'}
 ownednets={x['name'] for x in created if x['kind']=='AD_NETWORK_CREATED'}
 def opt(k):
  assert args.count(k)==1,('Missing/duplicate Docker option',k)
  return args[args.index(k)+1]
 if op=='compose':
  proof=json.loads((public/'legacy-empty-namespace.json').read_text())
  assert proof['project']==project and proof['emptyBeforeAnyComposeOperation'] is True
  assert not any(proof['preexistingResources'].values()),'Compose ownership preflight absent'
  assert opt('--project-name')==project
  owned_root=src.resolve()
  env_file=Path(opt('--env-file')).resolve(); expected_env=(src/'.env.local').resolve()
  compose_file=Path(opt('-f')).resolve(); expected_compose=(src/'infra/compose/docker-compose.yml').resolve()
  assert expected_env.is_relative_to(owned_root) and env_file.is_relative_to(owned_root) and env_file==expected_env
  assert expected_compose.is_relative_to(owned_root) and compose_file.is_relative_to(owned_root) and compose_file==expected_compose
  # Health regression can only stop/up the explicitly owned project.
  assert any(x in args for x in ('up','down','stop','ps'))
  event({'kind':'LEGACY_COMPOSE','project':project,'operation':next(x for x in args if x in ('up','down','stop','ps'))})
 elif args[:2]==['network','create']:
  name=args[-1]; assert re.fullmatch(r'marketops-ad-browser-[a-z0-9-]+-net',name)
  assert dcall('network','inspect',name,check=False).returncode!=0,'Refusing existing advertising network'
 elif op=='run':
  name=opt('--name'); assert re.fullmatch(r'marketops-ad-browser-[a-z0-9-]+',name)
  assert opt('--network') in ownednets and opt('--publish')=='127.0.0.1::5432'
  assert args[-1]=='postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3'
  assert dcall('container','inspect',name,check=False).returncode!=0,'Refusing existing advertising container'
 elif op in ('exec','port','rm'):
  matches=ownedcontainers.intersection(args)
  if not matches:
   # The original EXIT handler may run after setup failed before container creation.
   assert op=='rm' and re.fullmatch(r'marketops-ad-browser-[a-z0-9-]+',args[-1])
   assert dcall('container','inspect',args[-1],check=False).returncode!=0
   return 0
  assert len(matches)==1
 elif args[:2]==['network','rm']:
  if args[-1] not in ownednets:
   assert dcall('network','inspect',args[-1],check=False).returncode!=0
   return 0
 else: raise RuntimeError('Unapproved Docker operation in browser child: '+op)
 # Never log args: the preserved ad script carries disposable passwords in --env.
 r=subprocess.run([docker,*args],stdout=subprocess.PIPE)
 sys.stdout.buffer.write(r.stdout); sys.stdout.buffer.flush()
 if r.returncode==0 and args[:2]==['network','create']:
  event({'kind':'AD_NETWORK_CREATED','name':args[-1],'id':r.stdout.decode().strip()})
 if r.returncode==0 and op=='run':
  name=opt('--name')
  event({'kind':'AD_CONTAINER_CREATED','name':name,'id':r.stdout.decode().strip(),'network':opt('--network')})
  ports=json.loads(dcall('container','inspect','--format','{{json .NetworkSettings.Ports}}',name).stdout)
  event({'kind':'AD_CONTAINER_PUBLISHED','name':name,'id':r.stdout.decode().strip(),'ports':ports})
  binding=ports['5432/tcp']; assert len(binding)==1 and binding[0]['HostIp']=='127.0.0.1' and binding[0]['HostPort'] not in ('5432','55436')
 return r.returncode

def capture(suite,status):
 dest=private/suite; dest.mkdir(exist_ok=True)
 console=src/'frontend/marketops-console'
 output=console/'test-results'/('legacy-isolated' if suite=='legacy' else 'advertising')
 if output.exists(): shutil.copytree(output,dest/'test-results',dirs_exist_ok=True)
 build=src/'backend/marketops-server/target/classes/META-INF/build-info.properties'
 if build.exists(): shutil.copy2(build,dest/'backend-build-info.properties')
 dist=console/'dist'
 if dist.exists(): shutil.copytree(dist,dest/'frontend-dist',dirs_exist_ok=True)
 info=build.read_text() if build.exists() else ''
 stamp_ok=('build.gitCommit='+E['SLICE3_SOURCE_HEAD']) in info
 bundle_ok=dist.exists() and any(E['SLICE3_SOURCE_HEAD'].encode() in p.read_bytes() for p in dist.rglob('*.js'))
 report=dest/'report.json'; declared=[]; actual=[]; all_expected=True
 if report.exists():
  data=json.loads(report.read_text())
  def walk(s):
   nonlocal all_expected
   for spec in s.get('specs',[]):
    for t in spec.get('tests',[]):
     actual.append({'file':spec.get('file',s.get('file')),'title':spec['title'],'project':t.get('projectName'),'expectedStatus':t.get('expectedStatus'),'status':t.get('status'),'results':[x.get('status') for x in t.get('results',[])]})
     all_expected &= t.get('expectedStatus')=='passed' and t.get('status')=='expected' and bool(t.get('results')) and all(x.get('status')=='passed' for x in t.get('results',[]))
   for child in s.get('suites',[]): walk(child)
  for item in data.get('suites',[]): walk(item)
  all_expected &= not data.get('errors')
 expected_dir=console/'tests'/('browser' if suite=='legacy' else 'advertising-browser')
 declared=sorted(p.name for p in expected_dir.glob('*.spec.ts'))
 discovered=sorted({Path(x['file']).name for x in actual if x['file']})
 expected_count=25 if suite=='legacy' else 12
 good=int(status)==0 and all_expected and len(actual)==expected_count and discovered==declared and stamp_ok and bundle_ok
 # Raw evidence persists privately. Publish only after content screening, including ZIP traces.
 sensitive=[]; pattern=re.compile(rb'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}')
 for p in dest.rglob('*'):
  if not p.is_file(): continue
  if pattern.search(p.read_bytes()): sensitive.append(p.relative_to(dest).as_posix())
  if zipfile.is_zipfile(p):
   with zipfile.ZipFile(p) as z:
    if any(pattern.search(z.read(n)) for n in z.namelist() if not n.endswith('/')): sensitive.append(p.relative_to(dest).as_posix()+'::zip-entry')
 receipt={'suite':suite,'exitCode':int(status),'sourceHead':E['SLICE3_SOURCE_HEAD'],'sourceTree':E['SLICE3_SOURCE_TREE'],'expectedSpecFiles':declared,'actualSpecFiles':discovered,'expectedTestCount':expected_count,'actualTestCount':len(actual),'testcases':actual,'backendStampMatches':stamp_ok,'frontendBundleContainsExactStamp':bundle_ok,'rawPrivateDirectory':str(dest),'signedTokenArtifactPaths':sensitive,'completeExecutionAndIdentity':good,'signedJwtScreenPassed':not sensitive,'publicationReview':'Root must apply the full governance secret-pattern review before publication; this driver only screens signed JWT shapes.','boundary':'Browser/HTTP fixture proof only; not Provider or canonical calculation proof.'}
 save(public/f'{suite}-receipt.json',receipt)
 manifest=[{'path':p.relative_to(dest).as_posix(),'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(dest.rglob('*')) if p.is_file()]
 save(public/f'{suite}-raw-artifacts.json',{'privateRoot':str(dest),'files':manifest})
 if not sensitive: shutil.copytree(dest,public/suite)
 return 0 if good and not sensitive else 1

def cleanup_owned():
 problems=[]
 # Only exact IDs registered after successful creation can be removed here.
 for row in reversed(events()):
  if row['kind'] not in ('AD_CONTAINER_CREATED','AD_NETWORK_CREATED'): continue
  typ='container' if row['kind']=='AD_CONTAINER_CREATED' else 'network'
  exists=dcall(typ,'inspect',row['id'],check=False).returncode==0
  if exists:
   r=dcall('rm','-f',row['id'],check=False) if typ=='container' else dcall('network','rm',row['id'],check=False)
   if r.returncode: problems.append({'kind':typ,'id':row['id'],'cleanupExit':r.returncode})
  if dcall(typ,'inspect',row['id'],check=False).returncode==0: problems.append({'kind':typ,'id':row['id'],'stillExists':True})
 # Compose uses its unique label; require container, network and volume disappearance.
 project=E['SLICE3_BROWSER_PROJECT']
 for typ,cmd in [('container',['container','ls','-aq']),('network',['network','ls','-q']),('volume',['volume','ls','-q'])]:
  ids=dcall(*cmd,'--filter','label=com.docker.compose.project='+project).stdout.splitlines()
  if ids: problems.append({'kind':typ,'project':project,'remaining':ids})
 after=snapshot('after'); before=json.loads((public/'docker-before.json').read_text())
 for typ in before:
  def identities(rows): return {tuple(x.split('\t')[:2]) for x in rows}
  missing=identities(before[typ])-identities(after[typ])
  if missing: problems.append({'preexistingResourceMissingOrRenamed':typ,'ids':sorted(missing)})
 save(public/'cleanup-receipt.json',{'ownedResourcesRemoved':not problems,'problems':problems,'privateEvidencePreserved':str(private),'privateConfigArchived':False})
 return 0 if not problems else 1
mode=sys.argv[1]
if mode=='source': source_check(sys.argv[2])
elif mode=='snapshot': snapshot(sys.argv[2])
elif mode=='ports': check_ports()
elif mode=='namespace': check_legacy_namespace()
elif mode=='docker': sys.exit(docker_proxy(sys.argv[2:]))
elif mode=='capture': sys.exit(capture(sys.argv[2],sys.argv[3]))
elif mode=='cleanup': sys.exit(cleanup_owned())
elif mode=='legacy':
 ids=dcall('container','ls','-aq','--no-trunc','--filter','label=com.docker.compose.project='+E['SLICE3_BROWSER_PROJECT']).stdout.splitlines(); assert len(ids)==1
 value={'project':E['SLICE3_BROWSER_PROJECT'],'containerId':ids[0],'ports':json.loads(dcall('container','inspect','--format','{{json .NetworkSettings.Ports}}',ids[0]).stdout),'imageReference':dcall('container','inspect','--format','{{.Config.Image}}',ids[0]).stdout.strip(),'imageId':dcall('container','inspect','--format','{{.Image}}',ids[0]).stdout.strip(),'networks':dcall('network','ls','--no-trunc','--format','{{.ID}}\t{{.Name}}','--filter','label=com.docker.compose.project='+E['SLICE3_BROWSER_PROJECT']).stdout.splitlines(),'volumes':dcall('volume','ls','--format','{{.Name}}','--filter','label=com.docker.compose.project='+E['SLICE3_BROWSER_PROJECT']).stdout.splitlines()}
 expected=json.loads((public/'legacy-port.json').read_text())['port']; binding=value['ports']['5432/tcp']
 assert len(binding)==1 and binding[0]['HostIp']=='127.0.0.1' and int(binding[0]['HostPort'])==expected and expected not in (5432,55436)
 assert value['imageReference']=='postgres:18.4'
 save(public/'legacy-ownership.json',value)
elif mode=='port':
 with socket.socket() as s: s.bind(('127.0.0.1',0)); port=s.getsockname()[1]
 assert port not in (5432,55436,8080,8082,4173)
 p=src/'.env.local'; b=p.read_text(); assert b.count('MARKETOPS_DB_PORT=5432')==1
 p.write_text(b.replace('MARKETOPS_DB_PORT=5432','MARKETOPS_DB_PORT='+str(port)))
 save(public/'legacy-port.json',{'host':'127.0.0.1','port':port,'ownedComposeProject':E['SLICE3_BROWSER_PROJECT'],'selection':'ephemeral free-port preflight; Compose must bind successfully'})
else: raise RuntimeError('Unknown helper mode')
PY
cat > "$SLICE3_BROWSER_WORK/bin/docker" <<'WRAPPER'
#!/bin/bash
exec /usr/bin/python3 "$SLICE3_BROWSER_HELPER" docker "$@"
WRAPPER
chmod 700 "$SLICE3_BROWSER_WORK/bin/docker"
export PATH="$SLICE3_BROWSER_WORK/bin:$PATH"
# Only a local Unix-socket daemon is admitted. No inherited Docker context/host survives env -i.
export DOCKER_HOST="$("$SLICE3_REAL_DOCKER" context inspect --format '{{.Endpoints.docker.Host}}')"
case "$DOCKER_HOST" in unix://*) ;; *) echo 'Nonlocal Docker endpoint refused' >&2; exit 2;; esac
# Endpoint selection above is read-only. All subsequent Docker operations use
# a fresh client configuration with a pinned plugin path and no auth/helpers.
export DOCKER_CONFIG="$SLICE3_BROWSER_WORK/docker-config"
mkdir -p "$DOCKER_CONFIG"
/usr/bin/python3 - <<'PY'
import hashlib,json,os
from pathlib import Path
plugin=Path('/Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose')
expected='ac24c9061e7a7ed97c5446463a0963b5dd6bda30dc6b54f55427ff4c8a09fb0c'
assert plugin.is_file() and plugin.resolve()==plugin and hashlib.sha256(plugin.read_bytes()).hexdigest()==expected,'Reviewed Compose plugin changed'
config=Path(os.environ['DOCKER_CONFIG'])/'config.json'
config.write_text(json.dumps({'cliPluginsExtraDirs':[str(plugin.parent)]})+'\n')
receipt={'configSha256':hashlib.sha256(config.read_bytes()).hexdigest(),'configContainsAuthOrCredentialHelpers':False,'composePluginPath':str(plugin),'composePluginSha256':expected,'endpointKind':'LOCAL_UNIX_SOCKET'}
(Path(os.environ['SLICE3_BROWSER_PUBLIC'])/'docker-client-configuration.json').write_text(json.dumps(receipt,indent=2)+'\n')
PY
compose=(docker compose --project-name "$SLICE3_BROWSER_PROJECT" --env-file "$SLICE3_BROWSER_SOURCE/.env.local" -f "$SLICE3_BROWSER_SOURCE/infra/compose/docker-compose.yml")
cleanup() {
  status=$?; trap - EXIT INT TERM; set +e
  compose_status=0
  if [ -f "$SLICE3_BROWSER_PUBLIC/legacy-empty-namespace.json" ]; then
    "${compose[@]}" down --volumes --remove-orphans > "$SLICE3_BROWSER_PUBLIC/legacy-cleanup.log" 2>&1
    compose_status=$?
  fi
  /usr/bin/python3 "$SLICE3_BROWSER_HELPER" cleanup; owned_status=$?
  if [ -d "$SLICE3_BROWSER_SOURCE/.git" ]; then /usr/bin/python3 "$SLICE3_BROWSER_HELPER" source after; source_status=$?; else source_status=1; fi
  printf '{"commandExit":%s,"composeCleanupExit":%s,"ownershipCleanupExit":%s,"sourceCheckExit":%s,"privateConfigArchived":false}\n' "$status" "$compose_status" "$owned_status" "$source_status" > "$SLICE3_BROWSER_PUBLIC/driver-exit.json"
  case "$SLICE3_BROWSER_WORK" in /tmp/slice3-final-browser-work.*) rm -rf -- "$SLICE3_BROWSER_WORK" ;; *) exit 2;; esac
  if [ "$status" -eq 0 ] && [ "$compose_status" -eq 0 ] && [ "$owned_status" -eq 0 ] && [ "$source_status" -eq 0 ]; then exit 0; fi
  [ "$status" -ne 0 ] && exit "$status"
  exit 2
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" snapshot before
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" namespace
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" ports
{ node --version; npm --version; java -version; } > "$SLICE3_BROWSER_PUBLIC/tool-versions.log" 2>&1
cp "$0" "$SLICE3_BROWSER_PUBLIC/execution-driver.sh"
cp "$SLICE3_BROWSER_HELPER" "$SLICE3_BROWSER_PUBLIC/execution-helper.py"
cp "$SLICE3_BROWSER_WORK/bin/docker" "$SLICE3_BROWSER_PUBLIC/owned-docker-wrapper.sh"
shasum -a 256 "$0" "$SLICE3_BROWSER_HELPER" "$SLICE3_BROWSER_WORK/bin/docker" > "$SLICE3_BROWSER_PUBLIC/execution-input-sha256.txt"
# No original env files or build outputs enter this clone.
git clone --no-local --depth 1 --single-branch --branch feat/SLICE-V1-003-advertising-traffic-efficiency "file://$SLICE3_REPO" "$SLICE3_BROWSER_SOURCE"
cd "$SLICE3_BROWSER_SOURCE"
cp "$SLICE3_BROWSER_ORIGINAL_ADAPTER" "$SLICE3_BROWSER_ADAPTER"
shasum -a 256 "$SLICE3_BROWSER_ORIGINAL_ADAPTER" "$SLICE3_BROWSER_ADAPTER" > "$SLICE3_BROWSER_PUBLIC/adapter-sha256.txt"
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" source before
python3 scripts/init_local_env.py --target all > "$SLICE3_BROWSER_PUBLIC/env-init-metadata.log"
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" port
"${compose[@]}" up -d --wait postgres
"${compose[@]}" ps --format json > "$SLICE3_BROWSER_PUBLIC/legacy-owned-services.json"
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" legacy
cd frontend/marketops-console
npm ci --include=dev --include=optional
npx playwright install chromium
mkdir -p "$SLICE3_BROWSER_PRIVATE/legacy" "$SLICE3_BROWSER_PRIVATE/advertising"
set +e
PLAYWRIGHT_HTML_OUTPUT_DIR="$SLICE3_BROWSER_PRIVATE/legacy/html" \
PLAYWRIGHT_JSON_OUTPUT_FILE="$SLICE3_BROWSER_PRIVATE/legacy/report.json" \
PLAYWRIGHT_JUNIT_OUTPUT_FILE="$SLICE3_BROWSER_PRIVATE/legacy/report.xml" \
npx playwright test --config=playwright.legacy-isolated.config.ts --reporter=list,json,junit,html > "$SLICE3_BROWSER_PRIVATE/legacy/command.log" 2>&1
legacy_status=$?
printf 'Legacy suite exit code: %s; raw log retained privately.\n' "$legacy_status"
set -e
set +e
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" capture legacy "$legacy_status"
legacy_evidence_status=$?
set -e
cd "$SLICE3_BROWSER_SOURCE"
# A test assertion failure does not hide the second suite; unsafe teardown does stop it.
"${compose[@]}" down --volumes --remove-orphans
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" ports
set +e
PLAYWRIGHT_HTML_OUTPUT_DIR="$SLICE3_BROWSER_PRIVATE/advertising/html" \
PLAYWRIGHT_JSON_OUTPUT_FILE="$SLICE3_BROWSER_PRIVATE/advertising/report.json" \
PLAYWRIGHT_JUNIT_OUTPUT_FILE="$SLICE3_BROWSER_PRIVATE/advertising/report.xml" \
bash scripts/validation/advertising_browser_isolated.sh --reporter=list,json,junit,html > "$SLICE3_BROWSER_PRIVATE/advertising/command.log" 2>&1
advertising_status=$?
printf 'Advertising suite exit code: %s; raw log retained privately.\n' "$advertising_status"
set -e
set +e
/usr/bin/python3 "$SLICE3_BROWSER_HELPER" capture advertising "$advertising_status"
advertising_evidence_status=$?
set -e
[ "$legacy_status" -eq 0 ] && [ "$legacy_evidence_status" -eq 0 ] && [ "$advertising_status" -eq 0 ] && [ "$advertising_evidence_status" -eq 0 ]
