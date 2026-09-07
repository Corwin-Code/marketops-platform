"""Publish reviewed official artifacts in byte-exact ZIP form; do not alter gates."""
import json,hashlib,zipfile,datetime,subprocess,shutil,copy
from pathlib import Path
R=Path('/Users/chzhengx/Code/personal/marketops-platform');G=R/'docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1';O=Path('/tmp/slice3-official-sarif-package-e278b1e-r2');assert not O.exists();O.mkdir();P=Path('/tmp/slice3-portable-publication-e278b1e-r2/PUBLICATION-PLAN.json');plan=json.loads(P.read_text());candidates=json.loads(Path('/tmp/slice3-official-sarif-packaging-candidates-e278b1e-r1.json').read_text());digests={r['sha256'] for r in candidates};files=[r for r in plan['directFiles'] if r['copyRequired'] and r['sha256'] in digests];assert len(files)==4
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def put(p,d):p=Path(p);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
manifest=json.loads((G/'EXECUTION-MANIFEST.json').read_text());clauses=json.loads((G/'FROZEN-CLAUSE-ASSESSMENT.json').read_text());selected={x['evidence']['path'] for row in manifest['criteria']+manifest['findings']+manifest['verificationChecks']+clauses['entries']for x in row['proofs']};assert not selected&{r['repositoryPath']for r in files}
zipout=O/'official-typescript-sarif-originals.zip';members=[]
with zipfile.ZipFile(zipout,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for r in files:
  p=Path(r['originalPath']);b=p.read_bytes();assert sha(b)==r['sha256'];name=r['sha256']+'/'+p.name;zi=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));zi.compress_type=zipfile.ZIP_DEFLATED;zi.external_attr=0o100644<<16;z.writestr(zi,b);members.append({'originalPath':str(p),'member':name,'sha256':sha(b),'bytes':len(b),'previousUntrackedDirectRepositoryPath':r['repositoryPath']})
with zipfile.ZipFile(zipout)as z:
 assert z.testzip()is None
 for r in members:assert sha(z.read(r['member']))==r['sha256']
index={'kind':'BYTE_EXACT_OFFICIAL_SARIF_PUBLICATION_ARCHIVE','archive':ref(zipout),'files':members,'memberCount':len(members),'zipCrcAndEveryMemberShaVerified':True,'originalBytesUnchanged':True,'recordedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'exactContextReview':ref(Path('/tmp/slice3-final-publication-scans-e278b1e-r1/EXACT-SARIF-HELP-TRIAGE.json')),'boundary':'Official CodeQL help examples remain byte-exact and explicitly recursively scanned/reviewed. Native artifact ZIP transport preserves the existing unmodified repository text-pattern gate. No credential or source assertion is redacted, suppressed or changed; original analysis identities remain historical/current as recorded. None of these four files is a direct selected finalizer proof.'};ip=O/'ARCHIVE-MEMBER-INDEX.json';put(ip,index)
prefix='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/portable-e278b1e/files/'
def destination(p):r=ref(p);return prefix+r['sha256']+'/'+Path(p).name
zd=destination(zipout);idst=destination(ip)
mapping=json.loads((P.parent/'RELOCATION-MAPPING.json').read_text());destinations=json.loads((P.parent/'ARTIFACT-DESTINATIONS.json').read_text())['artifactDestinations'];config=json.loads((P.parent/'EXECUTION-INPUTS-WITH-PORTABLE-DESTINATIONS.json').read_text())
removed={r['repositoryPath'] for r in files}
for row in members:
 found=[e for e in mapping['entries'] if e.get('repositoryPath')==row['previousUntrackedDirectRepositoryPath']];assert found
 for e in found:
  assert e['sha256']==row['sha256'];e.pop('repositoryPath');e.pop('copyRequired',None);e['archiveLocations'].append({'archiveRepositoryPath':zd,'archiveSha256':index['archive']['sha256'],'member':row['member'],'memberSha256':row['sha256'],'memberBytes':row['bytes'],'memberIndexRepositoryPath':idst});e['roles'].append('EXACT_OFFICIAL_ARTIFACT_ARCHIVE_TRANSPORT')
destinations={k:v for k,v in destinations.items() if v not in removed};config['artifactDestinations']=destinations
plan['directFiles']=[r for r in plan['directFiles'] if r['repositoryPath']not in removed]
for p in [zipout,ip]:
 r=ref(p);dest=destination(p);q=R/dest;assert not q.exists();q.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,q);assert ref(q)['sha256']==r['sha256'];plan['directFiles'].append({'originalPath':r['path'],'repositoryPath':dest,'sha256':r['sha256'],'bytes':r['bytes'],'copyRequired':True,'gitFileLimitExceeded':False,'gitFileWarning':False});destinations[r['path']]=dest;mapping['entries'].append({'canonicalOriginalPath':r['path'],'originalPaths':[r['path']],'sha256':r['sha256'],'bytes':r['bytes'],'roles':['EXACT_OFFICIAL_SARIF_PACKAGE'],'archiveLocations':[],'repositoryPath':dest,'copyRequired':True})
for row in files:
 q=R/row['repositoryPath'];assert q.is_file() and ref(q)['sha256']==row['sha256'] and q.is_relative_to(G/'portable-e278b1e/files');tracked=subprocess.run(['git','ls-files','--error-unmatch','--',row['repositoryPath']],cwd=R,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);assert tracked.returncode==1,'Do not remove tracked history';q.unlink()
mapping['artifactTransportRevision']={'priorRelocationMap':ref(P.parent/'RELOCATION-MAPPING.json'),'packageIndex':ref(ip),'rawJsonBytesChanged':False,'directFinalizerProofsChanged':False}
plan['archives'].append({'index':ref(ip),'archive':ref(zipout),'repositoryPath':zd,'members':4,'originalResult':'ORIGINAL_OFFICIAL_SARIF_ANALYSES_KEEP_THEIR_OWN_IDENTITIES','crcAndMemberHashesVerifiedByOriginalIndex':True,'freshUnzipPerformedByThisTool':True})
plan['artifactTransportRevision']={'priorPublicationPlan':ref(P),'governancePrecheck':ref(Path('/tmp/slice3-final-governance-precheck-e278b1e-r1.log')),'packageIndex':ref(ip),'builder':ref(__file__),'removedFreshUntrackedDirectCopies':len(files),'originalSourceFilesRemoved':0,'selectedProofsChanged':0,'controlSourceChanged':False}
plan['directFiles'].sort(key=lambda r:r['repositoryPath']);plan['sizeSummary'].update(directUniqueFiles=len(plan['directFiles']),newCopyBytes=sum(r['bytes']for r in plan['directFiles']if r['copyRequired']))
put(O/'RELOCATION-MAPPING.json',mapping);put(O/'ARTIFACT-DESTINATIONS.json',{'artifactDestinations':destinations});put(O/'EXECUTION-INPUTS-WITH-PORTABLE-DESTINATIONS.json',config)
plan.update(relocationMap=ref(O/'RELOCATION-MAPPING.json'),artifactDestinations=ref(O/'ARTIFACT-DESTINATIONS.json'),derivedRegistrationConfig=ref(O/'EXECUTION-INPUTS-WITH-PORTABLE-DESTINATIONS.json'));put(O/'PUBLICATION-PLAN.json',plan)
for name in ['PUBLICATION-PLAN.json','RELOCATION-MAPPING.json','ARTIFACT-DESTINATIONS.json','EXECUTION-INPUTS-WITH-PORTABLE-DESTINATIONS.json']:
 dest=G/'portable-e278b1e'/name;assert dest.read_bytes()==(P.parent/name).read_bytes();hist=G/'portable-e278b1e/original-pre-packaging'/name;hist.parent.mkdir(parents=True,exist_ok=True);assert not hist.exists();shutil.copyfile(dest,hist);shutil.copyfile(O/name,dest)
print(json.dumps({'archive':ref(zipout),'archiveIndex':ref(ip),'files':len(files),'sourceBytesChanged':0,'selectedProofsChanged':0,'currentPlan':ref(O/'PUBLICATION-PLAN.json')}))
