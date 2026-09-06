import collections,datetime,hashlib,json,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
OUT=Path(__file__).resolve().parent;BASE=Path('/tmp/slice3-final-execution-e278b1e-r6');REPO=Path('/Users/chzhengx/Code/personal/marketops-platform');HEAD='e278b1e3d8541aeb806e41d6cbef4deac8d16d06';TREE='178132dd32a92e59e320eb100774b5bb9f6fb248';INV='73cc1dc47aebd86cf93de3afbdfe4d905b6c5b4f8b1335488f28ca63fe963eda'
sha=lambda b:hashlib.sha256(b).hexdigest()
def ref(p):p=Path(p);return {'path':str(p),'sha256':sha(p.read_bytes()),'bytes':p.stat().st_size}
def read(p):return json.loads(Path(p).read_text())
def checked(e):p=Path(e['path']);assert sha(p.read_bytes())==e['sha256'];assert 'bytes'not in e or p.stat().st_size==e['bytes'];return p
inventory={r['path']:r for r in read(BASE/'backend-full/source-before.json')['files']}
oldgit={}
def gitsha(path):
 if path not in oldgit:oldgit[path]=sha(subprocess.check_output(['git','--no-replace-objects','show',HEAD+':'+path],cwd=REPO))
 return oldgit[path]
def unique(root,name):rows=list(root.rglob(name));assert len(rows)==1,(name,len(rows));return rows[0]
observations=[]
for name,driver in [('backend-full','backend_full'),('frontend-quality','frontend_quality'),('browser-r4','browser-r4'),('governance-r2','governance-r2'),('infrastructure','infrastructure'),('migration','migration'),('supply-chain','supply_chain')]:
 root=BASE/name;parentPath=root/'layer-candidate.json';d=read(parentPath);assert [d['sourceHead'],d['sourceTree'],d['sourceInventorySha256']]==[HEAD,TREE,INV]
 times=[datetime.datetime.fromisoformat(d[k])for k in ['startedAt','finishedAt']];assert all(t.tzinfo for t in times)and times[0]<times[1]<=datetime.datetime.now(datetime.timezone.utc)
 assert type(d['exitCode'])is int and d['exitCode']==0 and d['result']=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and d['sourceStable'] is True and d['workingTreeDirty'] is False and d['sourceChangedPaths']==[] and d['stalePreexistingReports']==[] and d['xmlParseErrors']==[]
 identities=[]
 for file in ['identity-before.json','identity-after.json']:
  p=root/file;i=read(p);assert i['sourceHead']==HEAD and i['sourceTree']==TREE and i['workingTreeDirty'] is False and i['workingTreeStatus']==[];identities.append(ref(p))
 for file in ['source-before.json','source-after.json']:assert sha((root/file).read_bytes())==INV
 members={str(checked(e).resolve()):e for e in d['evidence']};assert len(members)==len(d['evidence'])
 preserved=read(root/'preserved-reports.json');named=read(root/'named-testcase-nodes.json')
 driverReceipt=BASE/'driver-executions'/driver/'receipt.json';dr=read(driverReceipt);assert dr['sourceHead']==HEAD and dr['sourceTree']==TREE and dr['sourceInventorySha256']==INV and dr['exitCode']==0 and dr['sourceStable'] is True and dr['result']=='COMMAND_SUCCEEDED_REVIEW_REQUIRED'
 preflight=read(checked(dr['before']));ii=dr['executionInputs'];inputchecks=[];inputIndex=None
 for e in ii['files']:
  p=Path(e['path']);kind='EXACT_CURRENT_EXTERNAL_FILE'
  if p.is_relative_to(REPO):actual=gitsha(p.relative_to(REPO).as_posix());kind='EXACT_ORIGINAL_PRODUCT_COMMIT_BLOB'
  else:actual=sha(p.read_bytes())
  assert actual==e['sha256'],(name,str(p));inputchecks.append({'path':str(p),'sha256':actual,'verification':kind})
 assert preflight['executionInputs']==dr['executionInputs'];configref=preflight['config'];config=read(checked(configref));assert config.get('head',config.get('sourceHead'))==HEAD
 xcounts=collections.Counter();families=collections.defaultdict(collections.Counter);xmlrefs=[];declaredDifferences=[];nodekeys=[]
 for e in d['evidence']:
  p=Path(e['path'])
  if p.suffix!='.xml':continue
  try:tree=ET.fromstring(p.read_bytes())
  except ET.ParseError:continue
  nodes=list(tree.iter('testcase'))
  if not nodes:continue
  fam='unit'if 'surefire-reports'in p.parts else 'integration'if 'failsafe-reports'in p.parts else 'browser'
  if tree.get('tests')is not None and int(tree.get('tests'))!=len(nodes):declaredDifferences.append({'evidence':e,'rootDeclaredTests':int(tree.get('tests')),'actualTestcaseLeaves':len(nodes)})
  for ordinal,n in enumerate(nodes):
   status=next((tag for tag in ['failure','error','skipped']if n.find(tag)is not None),'passed');xcounts[status]+=1;families[fam][status]+=1;nodekeys.append((str(p.resolve()),ordinal))
  xmlrefs.append(e)
 assert len(nodekeys)==len(set(nodekeys)) and not any(xcounts[k]for k in ['failure','error','skipped'])
 original_xml={'passed':xcounts['passed'],'failures':xcounts['failure'],'errors':xcounts['error'],'skipped':xcounts['skipped']}
 if d['testcaseCounts']is not None:assert d['testcaseCounts']==original_xml
 actual={'xmlTestcaseLeaves':original_xml,'xmlFamilies':dict(families),'xmlReportRefs':xmlrefs,'rawDeclaredCountDifferences':declaredDifferences,'uniquePhysicalNodeOrdinals':len(nodekeys),'countBoundary':'Actual testcase leaves retained; suite declared tests never substitute for leaves. No count from another layer or CI job is added.'}
 if name=='frontend-quality':
  p=unique(root,'tests.json');t=read(p);assert str(p.resolve())in members;nodes=[a for f in t['testResults']for a in f['assertionResults']];assert len(nodes)==358 and all(n['status']=='passed'and not n.get('failureMessages')for n in nodes)and t['success'] is True
  sourcechecks=[]
  for f in t['testResults']:
   rel=Path(f['name']).relative_to(REPO).as_posix();assert rel in inventory and gitsha(rel)==inventory[rel]['sha256'];sourcechecks.append({'path':rel,'sha256':inventory[rel]['sha256']})
  actual['namedFrontend']={'evidence':ref(p),'assertions':len(nodes),'physicalTestFiles':len(t['testResults']),'reportedSuiteGroups':t['numTotalTestSuites'],'allPassed':True,'sourceChecks':sourcechecks,'nameBoundary':'Actual assertionResults are counted independently; numTotalTestSuites may include nested groups and is not physical test-file count.'}
 if name=='governance-r2':
  p=unique(root,'named-unittest-results.json');g=read(p);assert str(p.resolve())in members;events=g['methodsAndFrameworkEvents'];assert len(events)==421 and all(e['status']=='PASSED'for e in events)and g['frameworkWasSuccessful']is True and g['unexecutedIds']==[] and g['allRecordedSourcesStable']is True
  for e in events:assert e['sourcePath']in inventory and e['sourceSha256']==inventory[e['sourcePath']]['sha256']
  actual['namedGovernance']={'evidence':ref(p),'frameworkCounts':g['frameworkCounts'],'eventCount':len(events),'methodSourceInventoryMatches':True,'scope':'Current e278b1e runtime validator 421; current evidence-tool 106 and structured 24 checks are separately source-bound and add zero product tests.'}
 if name=='browser-r4':
  br=[]
  for suite,count in [('legacy',25),('advertising',12)]:
   p=unique(root,suite+'-receipt.json');b=read(p);assert str(p.resolve())in members;assert b['exitCode']==0 and b['sourceHead']==HEAD and b['sourceTree']==TREE and b['actualTestCount']==b['expectedTestCount']==count and b['actualSpecFiles']==b['expectedSpecFiles'];assert len(b['testcases'])==count and all(t['results']==['passed']for t in b['testcases']);assert b['backendStampMatches']is True and b['frontendBundleContainsExactStamp']is True
   br.append({'evidence':ref(p),'suite':suite,'nodes':count,'backendStampMatches':True,'frontendBundleContainsExactStamp':True,'exactSpecFiles':b['actualSpecFiles']})
  cleanup=unique(root,'cleanup-receipt.json');exitfile=unique(root,'driver-exit.json');cl=read(cleanup);ex=read(exitfile);assert cl['ownedResourcesRemoved']is True and cl['problems']==[] and all(ex[k]==0 for k in ['commandExit','composeCleanupExit','ownershipCleanupExit','sourceCheckExit'])
  actual['browser']={'suites':br,'cleanup':{'evidence':ref(cleanup),'value':cl},'driverExit':{'evidence':ref(exitfile),'value':ex},'boundary':'Actual current e278b1e 37 nodes ran against owned fixtures. Historical F/G browser failures remain separate. Legacy synthesized historical display rows have their stated narrower scopes.'}
 cmdlog=root/'command.log';assert str(cmdlog.resolve())in members and d['argv']
 observations.append({'layer':name,'collector':ref(parentPath),'runId':d['runId'],'startedAt':d['startedAt'],'finishedAt':d['finishedAt'],'originalSuccessObserved':True,'identities':identities,'sourceBefore':ref(root/'source-before.json'),'sourceAfter':ref(root/'source-after.json'),'originalInventoryShaMatchesBoth':True,'everyOriginalEvidenceMemberShaVerified':len(members),'preservedReports':ref(root/'preserved-reports.json'),'namedCollectorNodes':ref(root/'named-testcase-nodes.json'),'command':{'argv':d['argv'],'rawLog':ref(cmdlog)},'actualDriver':{'receipt':ref(driverReceipt),'preflight':dr['before'],'actualInputIndex':dr['executionInputs'],'config':configref,'declaredInputChecks':inputchecks,'boundary':'Actual preflight/finish input receipts bind driver/helper/config bytes; every registered repository input is checked against immutable current e278b1e Git bytes.'},'rawResultReview':actual,'conclusion':'VERIFIED_FOR_THIS_ORIGINAL_COMMAND_AND_NAMED_REPORT_SCOPE','limitations':['Terminal result and raw count do not prove every criterion semantics or independent Controller approval.','Actual source-bound wrappers use env-i and explicit settings, with fixed tool paths; this is not a claim that every possible filesystem configuration was isolated or inspected.','No CI identity is inferred from local publicationIdentity NOT_PROVIDED fields.']})
(OUT/'COMMON-LAYER-RAW-CROSSCHECKS.json').write_text(json.dumps({'kind':'INDEPENDENT_ORIGINAL_LAYER_RAW_CROSSCHECKS','sourceHead':HEAD,'sourceTree':TREE,'inventorySha256':INV,'layers':observations,'proofSelections':[],'productTestsRun':0},indent=2)+'\n')
print(json.dumps({'layers':len(observations),'namedResults':{x['layer']:{'xml':x['rawResultReview']['xmlTestcaseLeaves'],'frontend':x['rawResultReview'].get('namedFrontend',{}).get('assertions'),'governance':x['rawResultReview'].get('namedGovernance',{}).get('eventCount')}for x in observations}}))
