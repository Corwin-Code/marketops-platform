import json,hashlib,subprocess,zipfile,collections
from pathlib import Path
O=Path(__file__).resolve().parent;C=Path('/tmp/slice3-security-ci-e278b1e-r1');A=Path('/tmp/slice3-security-admission-e278b1e-r1');R=Path('/Users/chzhengx/Code/personal/marketops-platform');B=Path('/tmp/slice3-final-execution-e278b1e-r6')
H='e278b1e3d8541aeb806e41d6cbef4deac8d16d06';T='178132dd32a92e59e320eb100774b5bb9f6fb248';I='73cc1dc47aebd86cf93de3afbdfe4d905b6c5b4f8b1335488f28ca63fe963eda'
sha=lambda b:hashlib.sha256(b).hexdigest()
def read(p):return json.loads(Path(p).read_text())
def ref(p):p=Path(p).resolve();return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def checked(e):p=Path(e['path']);assert ref(p)['sha256']==e['sha256'];assert 'bytes'not in e or p.stat().st_size==e['bytes'];return p
ad=read(A/'SECURITY-RECEIPT.json');assert (ad['sourceHead'],ad['sourceTree'],ad['sourceInventorySha256'])==(H,T,I) and all(x is True for x in ad['criteria'].values())
assert ad['alertsModifiedOrDismissed']is False and ad['productionWriteEnabled']is False
for e in ad['evidence']:checked(e)
v=read(checked(ad['rawReview']));idx=read(C/'INDEX.json');verified=[ref(checked(e))for e in idx['files']];assert len(verified)==148
zi=read(checked(ad['rawLogMemberIndex']));z=zipfile.ZipFile(checked(ad['rawLogs']));assert z.testzip() is None
for e in zi['members']:
 raw=z.read(e['member']);assert sha(raw)==e['sha256'] and len(raw)==e['bytes'] and format(z.getinfo(e['member']).CRC,'08x')==e['crc32']
for proof in v['actualCheckoutProof']:
 raw=z.read(proof['member']['member']);assert sha(raw)==proof['member']['sha256'];lines=raw.decode().splitlines()
 for line in proof['actualLines']:assert lines[line['line']-1]==line['text']
 assert ad['testedMerge']in raw.decode() and ('Merge '+H+' into '+ad['testedMergeParents'][0])in raw.decode()
c=read(checked(ad['sourceInventoryComparison']));co=read(checked(c['perFileGitComparison']));remote={x['path']:x for x in read(checked(c['remoteTree']))['tree']};inv={x['path']:x for x in read(checked(co['inventory']))['files']};assert len(co['members'])==len(inv)==1280
merge=read(checked(c['testedMergeCommit']));assert merge['sha']==ad['testedMerge'] and merge['tree']['sha']==T and [p['sha']for p in merge['parents']]==ad['testedMergeParents']
proc=subprocess.run(['git','--no-replace-objects','cat-file','--batch'],input=('\n'.join(x['gitBlob']for x in co['members'])+'\n').encode(),cwd=R,capture_output=True,check=True);data=proc.stdout;pos=0;sources={};counts=collections.Counter()
for e in co['members']:
 end=data.index(b'\n',pos);oid,typ,size=data[pos:end].decode().split();pos=end+1;raw=data[pos:pos+int(size)];pos+=int(size)+1
 assert typ=='blob' and oid==e['gitBlob']==remote[e['path']]['sha'] and sha(raw)==e['gitSha256'];assert e['inventorySha256']==inv[e['path']]['sha256']
 if e['conversion']is None:assert sha(raw)==e['inventorySha256'];counts['EXACT_GIT_BLOB_BYTES']+=1
 else:
  assert e['path']in ['backend/marketops-server/mvnw.cmd','scripts/bootstrap-repo.ps1'];assert sha(raw.replace(b'\r\n',b'\n').replace(b'\n',b'\r\n'))==e['inventorySha256'];counts['DECLARED_CRLF_EQUIVALENCE']+=1
 sources[e['path']]=raw
assert pos==len(data) and counts=={'EXACT_GIT_BLOB_BYTES':1278,'DECLARED_CRLF_EQUIVALENCE':2}
for row in [ad['run'],*ad['jobs'],ad['aggregateCodeQL']]:assert row['head_sha']==H and row['status']=='completed' and row['conclusion']=='success'
assert ad['run']['id']==34025462624 and ad['run']['run_attempt']==1
alerts={a['number']:a for name in ['open-alerts-pages.json','dismissed-alerts-pages.json']for page in read(C/name)for a in page};assert len(alerts)==100
actual=[]
for sarif in ad['sarifAnalyses']:
 rows=[r for run in read(checked(sarif['file']))['runs']for r in run.get('results',[])];assert len(rows)==sarif['actualResults']
 for r in rows:
  a=alerts[r['properties']['github/alertNumber']];loc=r['locations'][0]['physicalLocation'];al=a['most_recent_instance']['location'];assert loc['artifactLocation']['uri']==al['path'] and loc['region']['startLine']==al['start_line'] and r['ruleId']==a['rule']['id'];assert a['most_recent_instance']['commit_sha']==ad['testedMerge'];actual.append(a['number'])
assert len(actual)==len(set(actual))==100
triage=read(checked(ad['openAlerts']['triage']));expression=[]
for f in triage['findings']:
 l=f['currentLocation'];raw=sources[l['path']];assert sha(raw)==f['currentSourceSha256'];lines=raw.decode().splitlines();seg=lines[l['start_line']-1:l['end_line']];seg[0]=seg[0][l['start_column']-1:];seg[-1]=seg[-1][:l['end_column']-(l['start_column']if len(seg)==1 else 1)];assert sha('\n'.join(seg).encode())==f['currentFlaggedExpressionSha256'];expression.append({'number':f['number'],'sourceSha256':sha(raw),'expressionSha256':f['currentFlaggedExpressionSha256'],'location':l})
assert len(expression)==95 and triage['levelCounts']=={'note':83,'warning':12} and triage['newSecuritySeverityFindings']==0
hist=read(checked(ad['historicalDismissedHigh']['reconciliation']));assert hist['count']==5 and hist['newDismissalsPerformed']is False
for f in hist['findings']:assert f['state']=='dismissed' and f['allDismissalMetadataUnchanged']is True and sha(sources[f['currentLocation']['path']])==f['currentSourceSha256']
scan=read(checked(ad['publicationScan']));assert scan['patternCount']==9 and scan['unreviewedOccurrences']==[] and scan['safeWithinThisExactPatternReview']is True and scan['originalCaptureIndex']==ref(C/'INDEX.json')
np=B/'security-npm-audit/receipt.json';npm=read(np);assert npm['sourceHead']==H and npm['sourceTree']==T and npm['sourceStable']is True and type(npm['exitCode'])is int and npm['exitCode']==0 and npm['command']['completed']is True and npm['command']['exitCode']==0
for e in npm['evidence']:checked(e)
na=read(checked(npm['rawAuditJson']));assert na['metadata']['vulnerabilities']==npm['npmAuditVulnerabilities'] and all(type(v)is int and v==0 for v in npm['npmAuditVulnerabilities'].values()) and npm['lockSha256']==inv['frontend/marketops-console/package-lock.json']['sha256']
out={'kind':'INDEPENDENT_CURRENT_CHECKPOINT_SECURITY_RAW_CROSSCHECK','sourceHead':H,'sourceTree':T,'sourceInventorySha256':I,'testedMerge':ad['testedMerge'],'testedMergeTree':T,'testedMergeParents':ad['testedMergeParents'],'originalIndex':ref(C/'INDEX.json'),'originalMembers':verified,'actualOriginalIndexMembersVerified':len(verified),'originalDirectoryFilesIncludingIndex':149,'actualZipMembersVerified':len(zi['members']),'rawLogIndex':ad['rawLogMemberIndex'],'actualCheckoutProof':v['actualCheckoutProof'],'actualRun':ad['run'],'actualJobs':ad['jobs'],'aggregate':ad['aggregateCodeQL'],'comparison':ad['sourceInventoryComparison'],'gitComparison':c['perFileGitComparison'],'actualGitAndRemoteInventoryEntries':1280,'actualComparisonCounts':dict(counts),'sarifResults':ad['sarifAnalyses'],'sarifToExactAlertMatches':v['sarifAlertLocationMatches'],'openQualityAlertCount':95,'openLevelCounts':triage['levelCounts'],'exactOpenExpressionRechecks':expression,'triage':ad['openAlerts']['triage'],'priorRootSourceContext':ad['priorRootSourceContext'],'historicalDismissedHigh':ad['historicalDismissedHigh']['reconciliation'],'defaultMainDependencyBoundary':ad['defaultMainDependencyAlerts'],'actualNpmReceipt':ref(np),'actualNpmRaw':npm['rawAuditJson'],'actualNpmVulnerabilities':npm['npmAuditVulnerabilities'],'publicationScan':ad['publicationScan'],'rootSecurityAdmission':ref(A/'SECURITY-RECEIPT.json'),'conclusion':'VERIFIED_FOR_EXACT_E278B1E_SECURITY_SCOPE_WITH_OPEN_QUALITY_AND_HISTORICAL_BOUNDARIES','limits':['Current checkpoint Security only; final evidence-containing commit requires its own exact CI capture.','100 raw SARIF findings comprise95 open quality findings and5 historically dismissed HIGH. No alert was changed or newly dismissed.','Four default-main HIGH Dependabot alerts remain a distinct scope; actual current npm audit and PR dependency delta both have zero vulnerability records.','Nine publication patterns include decoded JSON and recursive ZIP. Every official-help occurrence has an exact per-hit scope; there is no whole-file exclusion.','Current e278b1e Git and original raw sources are checked. Prior F root source assessment applies only to the95 byte-identical flagged whole files and expressions, not its execution results.'],'proofSelections':[],'productTestsRun':0}
(O/'SECURITY-RAW-CROSSCHECK.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps({'review':ref(O/'SECURITY-RAW-CROSSCHECK.json'),'rawFiles':149,'sourceMembers':1280,'sarif':100,'openQuality':95,'historicalDismissedHigh':5,'npmAllZero':True}))
