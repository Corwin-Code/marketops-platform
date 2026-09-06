#!/usr/bin/env python3
"""Bounded current-evidence preparation. Writes only /tmp; never emits COMPLETE/PASS layers."""
from pathlib import Path
import argparse,collections,hashlib,json,re,subprocess,sys,xml.etree.ElementTree as ET
if not __debug__:raise SystemExit('REFUSED: optimized Python disables required boundary checks')
SELF=Path(__file__).resolve()
def repository_root():
 # Prefer the caller's repository; a checked-in copy also works from another cwd.
 for start in [Path.cwd().resolve(),SELF.parent]:
  for candidate in [start,*start.parents]:
   if (candidate/'bootstrap-manifest.json').is_file():return candidate
 raise ValueError('Repository bootstrap-manifest.json not found from cwd or script parents')
ROOT=repository_root()
OUT=None
def temporary_output(path):
 out=path.resolve();temporary=Path('/tmp').resolve()
 assert out!=temporary and out.is_relative_to(temporary) and not out.is_relative_to(ROOT), 'Output must be a dedicated /tmp directory outside the repository'
 return out
GATE=ROOT/'docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1'
HEAD=TREE=INVENTORY=INVENTORY_SHA=SOURCE_IDENTITY=None
LAYERS=['backend_full','frontend_quality','browser','governance','infrastructure','migration','security','supply_chain','mixed_capacity']
sha=lambda raw:hashlib.sha256(raw).hexdigest()
load=lambda path:json.loads(Path(path).read_text())
def write(name,obj):
 p=OUT/name
 assert p.resolve().is_relative_to(temporary_output(OUT))
 p.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
def ref(p):
 p=Path(p);return {'path':str(p.relative_to(ROOT)) if p.is_relative_to(ROOT) else str(p),'sha256':sha(p.read_bytes())}
def resolve(refvalue,base=None):
 p=Path(refvalue['path'])
 if not p.is_absolute():p=ROOT/p if (ROOT/p).is_file() or base is None else base/p
 assert p.is_file() and sha(p.read_bytes())==refvalue['sha256'],('Evidence bytes absent/changed',str(p))
 return p.resolve()
def pointer(obj,path):
 if path=='':return obj
 if not isinstance(path,str) or not path.startswith('/') or re.search(r'~(?:[^01]|$)',path):raise ValueError('Invalid JSON pointer')
 for key in path[1:].split('/'):
  key=key.replace('~1','/').replace('~0','~')
  if isinstance(obj,list):
   if not re.fullmatch(r'0|[1-9][0-9]*',key):raise ValueError('Invalid JSON array index')
   obj=obj[int(key)]
  else:obj=obj[key]
 return obj
def json_equal(actual,expected):
 return json.dumps(actual,sort_keys=True)==json.dumps(expected,sort_keys=True)
def raw_proof_document(path):
 assert path.name not in ['EXECUTION-MANIFEST.json','EXECUTION-MANIFEST-CANDIDATE.json'], 'A generated closure manifest cannot prove itself'
 data=load(path)
 assert not (isinstance(data,dict) and data.get('kind')=='SLICE3_FINAL_GATE_EXECUTION_MANIFEST'), 'A generated closure manifest cannot prove itself'
 return data
def require_identity(document,with_inventory=False):
 assert document['sourceHead']==HEAD and document['sourceTree']==TREE, 'Prepared/config source identity differs from expected checkpoint'
 if with_inventory:
  digest=document.get('sourceInventorySha256') or document.get('sourceInventory',{}).get('sha256')
  assert digest==INVENTORY_SHA, 'Prepared source inventory differs from expected checkpoint'
def inv():
 assert sha(INVENTORY.read_bytes())==INVENTORY_SHA
 return {r['path']:r for r in load(INVENTORY)['files']}
def all_sources():
 result=inv()
 for row in SOURCE_IDENTITY.get('additionalExecutionInputs',[]):
  path=row['path'];assert row['sourceHead']==HEAD and row.get('scope')=='EVIDENCE_DERIVATION_ONLY'
  raw=subprocess.check_output(['git','show',HEAD+':'+path],cwd=ROOT)
  assert sha(raw)==row['sha256'],('Additional input differs from checkpoint Git bytes',path)
  assert path not in result,('Runtime input must use the runtime inventory',path)
  result[path]={**row,'additionalDerivationInput':True}
 return result

def require_measured_tool():
 assert SELF.is_relative_to(ROOT), 'Run the reviewed checked-in assembler, not an unbound external copy'
 path=SELF.relative_to(ROOT).as_posix();sources=all_sources()
 assert path in sources and sources[path]['sha256']==sha(SELF.read_bytes()), 'Assembler bytes must be in the measured inventory or exact-HEAD additionalExecutionInputs'

def checkpoint_data_references(paths):
 references=[]
 for path in paths:
  relative=path.relative_to(ROOT).as_posix()
  assert sha(path.read_bytes())==sha(subprocess.check_output(['git','show',HEAD+':'+relative],cwd=ROOT)), ('Authoring data differs from source checkpoint',relative)
  references.append(ref(path))
 return references

def key(path,method):return path+'#'+method

def prepare():
 if (OUT/'ASSESSMENT-SLOTS.json').exists():
  existing=load(OUT/'ASSESSMENT-SLOTS.json')
  assert existing['sourceHead']==HEAD, 'Use a new --out directory for a new checkpoint; retain the failed prior checkpoint.'
  assert not any(r.get('engineeringReason') or r.get('proofSelections') for section in ['criteria','findings','clauses','verificationChecks'] for r in existing[section]), 'Refusing to replace reviewed assessment slots.'
 draft=load(GATE/'CURRENT-ASSESSMENT-DRAFT.json');mapping=load(GATE/'FINALIZATION-INPUT-MAP-DRAFT.json')
 frozenpath=GATE.parent.parent/'SLICE-V1-003-FROZEN-FINDING-SET-001.json'
 frozen=load(frozenpath)['findings'];frozenby={r['id']:r for r in frozen}
 assert sha(frozenpath.read_bytes())=='f4af74f5086772dc70c3ec3cc7aa8808e9441e96109d301b145e70c18f6131a0'
 historicalpath=GATE.parent/'workstreams/engineering-assessment-w9/finding-engineering-assessment.json'
 historical={r['id']:r for r in load(historicalpath)['entries']}
 acpath=GATE.parent/'workstreams/engineering-assessment-w9/criterion-engineering-assessment.json'
 oldac={r['id']:r for r in load(acpath)['entries']}
 namedpath=GATE.parent/'workstreams/current-named-backend/current-named-backend-evidence.json'
 oldnamed=load(namedpath)['rows'];measured=all_sources();catalog={};aliases={}
 authoring_refs=checkpoint_data_references([frozenpath,historicalpath,acpath,namedpath,GATE/'CURRENT-ASSESSMENT-DRAFT.json',GATE/'FINALIZATION-INPUT-MAP-DRAFT.json',ROOT/'scripts/validation/finalize_slice3_rework_assessment.py'])
 def remember(path,method,alias,scope=None):
  if not path or not method:return None
  identity=key(path,method)
  row=catalog.setdefault(identity,{'selectorId':sha(identity.encode())[:24],'sourcePath':path,'method':method,'measuredSourceSha256':measured.get(path,{}).get('sha256'),'historicalAliases':[],'historicalScopes':[],'actualExecutionBindings':[],'status':'LOCATOR_ONLY_NOT_CURRENT_EXECUTION'})
  if alias and alias not in row['historicalAliases']:row['historicalAliases'].append(alias);aliases[alias]=identity
  if scope and scope not in row['historicalScopes']:row['historicalScopes'].append(scope)
  return identity
 for row in draft['historicalNamedProofCatalog']:remember(row['path'],row['method'],row['id'],row.get('historicalAssertionScope'))
 for row in oldnamed:
  remember(row['path'],row['method'],row['id'])
  for origin in row.get('originIndexes',[]):remember(row['path'],row['method'],origin['referenceId'])
 for f in historical.values():
  for group in f['tests'].values():
   if isinstance(group,list):
    for row in group:
     if isinstance(row,dict) and row.get('originalCentralClaim'):
      c=row['originalCentralClaim'];remember(c.get('path'),c.get('method'),row['proofId'],c.get('evidenceLimit'))
 for source in draft['currentSourceOnlyTestCatalog']:
  for row in source['methods']:remember(source['path'],row['method'],None)
 def selectors(ids):return sorted({catalog[aliases[i]]['selectorId'] for i in ids if i in aliases})
 cvs={r['id']:r for r in draft['verificationChecks']}
 plans=[]
 for row in draft['criteria']:
  old=oldac[row['id']];contributions=old['individualContributions'];changes=[]
  for contribution in contributions:
   for source in contribution.get('currentImplementationSources',[]):
    path=source['path'];current=measured.get(path,{}).get('sha256');prior=source.get('currentSha256')
    changes.append({'path':path,'historicalSha256':prior,'executedSourceSha256':current,'changed':prior!=current,'sourceInventoryMember':current is not None})
  checks=sorted(set(row['controllerChecks']+row['transitiveChecks']))
  review={'criterionSpecificHistoricalReasoning':row['historicalEngineeringReasons'],
   'historicalPositiveScopes':[s for c in contributions for s in c.get('positiveScope',[])],
   'historicalAdverseOrUnknownScopes':[s for c in contributions for s in c.get('adverseOrUnknownScope',[])],
   'historicalProofLimits':[s for c in contributions for s in c.get('proofLimits',[])],
   'currentResidualObligations':[{'id':c,'exactRequiredClosure':cvs[c]['requiredClosure']} for c in checks],
   'currentImplementationImpact':changes,
   'currentReviewQuestion':row['acceptedExact'],
   'assessmentBoundary':'Historical reason and method locators guide review. The reviewed current engineeringReason must explain this criterion using new exact-source execution and retain its source/UI/release limits.'}
  if row['id']=='S3-AC-200':review['independentControllerBoundary']='Only candidate prerequisites can be established by this run. Historical Controller NOT_PASS remains fixed; no unresolved BLOCKER/MAJOR conclusion requires a new independent exact-Head Controller verdict.'
  plans.append({'id':row['id'],'acceptedExact':row['acceptedExact'],'independentReasonReview':review,'candidateSelectors':selectors(row['candidateHistoricalProofIds']),'historicalProofIds':row['candidateHistoricalProofIds'],'engineeringReason':None,'reviewedBy':None,'proofSelections':[],'layers':[],'status':'CURRENT_REASON_AND_EXECUTION_BINDING_PENDING'})
 clauses=[];findings=[]
 for row in draft['findings']:
  f=frozenby[row['id']];old=historical[row['id']];oldclauses={c['clauseId']:c for c in old['clauses']};ids=[]
  for field,label in [('required_rework','REQUIRED_REWORK'),('verification','VERIFICATION')]:
   for i,text in enumerate(f[field],1):
    identity=f'{f["id"]}:{label}:{i}';prior=oldclauses[identity];assert prior['frozenExact']==text
    proofids=[p['proofId'] for p in prior['concreteNamedProofs']];ids.append(identity)
    clauses.append({'id':identity,'findingId':f['id'],'clauseKind':label,'ordinal':i,'frozenExact':text,'frozenJsonPointer':f'/findings/{frozen.index(f)}/{field}/{i-1}',
     'historicalReasonForRecheck':prior['engineeringReason'],'historicalProofLimits':prior.get('proofLimits'),'historicalAdditionalScopes':prior.get('additionalActualEvidenceScopes',[]),
     'historicalProofIds':proofids,'candidateSelectors':selectors(proofids),'unresolvedHistoricalLocators':[p for p in proofids if p not in aliases],
     'currentResidualObligations':[{'id':c,'exactRequiredClosure':cvs[c]['requiredClosure']} for c in row['residualChecks']],
     'engineeringReason':None,'proofLimits':None,'reviewedBy':None,'proofSelections':[],'status':'INDIVIDUAL_CLAUSE_REASON_AND_CURRENT_PROOF_REQUIRED'})
  findings.append({'id':f['id'],'title':f['title'],'criteria':f['criteria'],'clauseIds':ids,'historicalRootCause':row['historicalRootCause'],'historicalImplementedBehavior':row['historicalImplementedBehavior'],'historicalControllerDisposition':row['historicalControllerDisposition'],'residualChecks':row['residualChecks'],'candidateSelectors':selectors(row['candidateHistoricalProofIds']),'engineeringReason':None,'reviewedBy':None,'proofSelections':[],'layers':[],'status':'CURRENT_REASON_AND_EXECUTION_BINDING_PENDING'})
 assert len(plans)==200 and len(findings)==22 and len(clauses)==115 and len({r['id'] for r in clauses})==115
 cvslots=[{'id':c['id'],'requiredClosure':c['requiredClosure'],'findings':c['findings'],'directCriteria':c['directCriteria'],'candidateSourcePaths':c['candidateCurrentTestCatalogPaths'],'engineeringReason':None,'proofLimits':None,'reviewedBy':None,'proofSelections':[],'result':'PENDING'} for c in draft['verificationChecks']]
 write('ASSESSMENT-SLOTS.json',{'kind':'EXACT_AUTHORITY_INDIVIDUAL_CURRENT_ASSESSMENT_SLOTS','status':'PENDING','sourceHead':HEAD,'sourceTree':TREE,'sourceInventory':ref(INVENTORY),'sourceDisposition':SOURCE_IDENTITY.get('disposition'),'additionalExecutionInputs':SOURCE_IDENTITY.get('additionalExecutionInputs',[]),'authorityReferences':authoring_refs, 'criteria':plans,'findings':findings,'clauses':clauses,'verificationChecks':cvslots,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'productionWriteEnabled':False})
 write('METHOD-LOCATORS.json',{'kind':'HISTORICAL_AND_CURRENT_SOURCE_METHOD_LOCATORS_ONLY','sourceHead':HEAD,'selectors':list(catalog.values()),'boundary':'Historical IDs, claims and results are locators only. No historical result is copied into current proof.'})
 drifts=[]
 for c in draft['verificationChecks']:
  for p in c['currentSourcePins']:
   actual=measured.get(p['path'],{}).get('sha256')
   if p['sha256']!=actual:drifts.append({'check':c['id'],'path':p['path'],'draftSha256':p['sha256'],'executedSha256':actual,'comparisonKind':'CHANGED_SINCE_DRAFT' if actual else 'NOT_IN_RUNTIME_INVENTORY_OR_REGISTERED_ADDITIONAL_INPUTS'})
 write('UNMATCHED-REVIEW-QUEUE.json',{'counts':{'criteria':200,'findings':22,'clauses':115,'verificationChecks':5,'methodLocators':len(catalog)},'allCurrentReasonsPending':True,'allCurrentProofSelectionsEmpty':True,'historicalClauseLocatorsUnresolved':[{'clause':c['id'],'ids':c['unresolvedHistoricalLocators']} for c in clauses if c['unresolvedHistoricalLocators']],'staleDraftSourcePins':drifts,'sourceInventoryIsCurrentAuthority':True})
 if not (OUT/'EXECUTION-INPUTS.json').exists():
  write('EXECUTION-INPUTS.json',{'kind':'ROOT_REGISTERED_CURRENT_EXECUTION_INPUTS','sourceHead':HEAD,'sourceTree':TREE,'layers':[{'id':name,'adapter':'collector','receipt':None,'junitSourceByClass':{},'jsonRecordAdapters':[]} for name in LAYERS],'sourcePrefixToRepository':{},'artifactDestinations':{}})
 print(json.dumps({'preparedCriteria':200,'preparedFindings':22,'exactFrozenClauses':115,'CV':5,'methodLocators':len(catalog),'staleDraftPins':len(drifts),'unresolvedClauseLocators':sum(len(c['unresolvedHistoricalLocators']) for c in clauses)}))

def source_for_java(classname,inventory):
 outer=classname.split('$')[0].replace('.','/')+'.java'
 found=[p for p in inventory if p.endswith('/'+outer)]
 return found[0] if len(found)==1 else None

def catalog(configpath):
 config=load(configpath);inventory=all_sources();nodes=[];layers=[];issues=[]
 require_identity(config)
 require_identity(load(OUT/'ASSESSMENT-SLOTS.json'),True)
 for spec in config['layers']:
  if not spec.get('receipt'):layers.append({'id':spec['id'],'state':'NO_EXECUTION_RECEIPT_REGISTERED'});continue
  receiptpath=Path(spec['receipt']);receiptpath=receiptpath if receiptpath.is_absolute() else Path(configpath).resolve().parent/receiptpath
  receipt=load(receiptpath);security=spec.get('adapter')=='security'
  if security:
   same=receipt['sourceHead']==HEAD and receipt['sourceTree']==TREE and receipt['executionSourceInventory']['sha256']==INVENTORY_SHA
   terminal=same and all(receipt['criteria'].values()) and receipt['run']['status']=='completed' and receipt['run']['conclusion']=='success'
   state=receipt['securityLayerResult'];runid=str(receipt['run']['id']);artifacts=[ref(receiptpath)]
  else:
   same=receipt['sourceHead']==HEAD and receipt['sourceTree']==TREE and receipt['sourceInventorySha256']==INVENTORY_SHA
   terminal=same and receipt.get('result')=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and receipt.get('sourceStable') is True and receipt.get('exitCode')==0 and bool(receipt.get('finishedAt'))
   state=receipt['result'];runid=receipt['runId'];artifacts=[ref(resolve(e,receiptpath.parent)) for e in receipt.get('evidence',[])]
  layers.append({'id':spec['id'],'runId':runid,'receipt':ref(receiptpath),'state':state,'exactSourceIdentityMatches':same,'terminalSuccessfulCommandObserved':terminal,'engineeringLayerReview':'PENDING','overallPassClaimMade':False})
  if not same:issues.append({'layer':spec['id'],'problem':'SOURCE_IDENTITY_DIFFERS'});continue
  def add(row):
   canonical=json.dumps([spec['id'],runid,row.get('sourcePath'),row.get('class'),row.get('name'),row['evidence'],row.get('assertions')],sort_keys=True)
   row.update(nodeId=sha(canonical.encode())[:24],layer=spec['id'],runId=runid,sourceHead=HEAD,sourceTree=TREE,sourceInventorySha256=INVENTORY_SHA,terminalSuccessfulCommandObserved=terminal,admissibleAfterIndependentScopeReview=terminal and row['observedResult']=='PASSED' and SOURCE_IDENTITY.get('disposition')!='FAILED_CHECKPOINT')
   path=row.get('sourcePath');row['sourceAuthorityScope']='ADDITIONAL_DERIVATION_INPUT' if inventory.get(path,{}).get('additionalDerivationInput') else 'RUNTIME_SOURCE_INVENTORY';row['source']={'path':path,'sha256':inventory[path]['sha256']} if path in inventory else None
   if row['source'] is None:row['admissibleAfterIndependentScopeReview']=False
   if inventory.get(path,{}).get('additionalDerivationInput'):
    extra=inventory[path]
    if not extra.get('executionReceipt'):
     row['admissibleAfterIndependentScopeReview']=False;row['additionalSourceBoundary']='SOURCE_PIN_ONLY_NO_EXECUTION_RECEIPT'
    else:
     resolve(extra['executionReceipt']);row['additionalExecutionReceipt']=extra['executionReceipt']
   nodes.append(row)
  if security:
   for name,value in receipt['criteria'].items():
    add({'kind':'json','name':'security.'+name,'class':None,'sourcePath':'.github/workflows/security.yml','evidence':ref(receiptpath),'observedResult':'PASSED' if value is True else 'NOT_PASSED','assertions':[{'pointer':'/criteria/'+name,'expected':value}],'scope':None})
  for evidence in artifacts:
   path=resolve(evidence)
   if path.suffix!='.xml':continue
   try:tree=ET.parse(path)
   except ET.ParseError as error:issues.append({'layer':spec['id'],'path':str(path),'problem':str(error)});continue
   for ordinal,node in enumerate(tree.getroot().iter('testcase')):
    result=next((tag.upper() for tag in ['failure','error','skipped'] if node.find(tag) is not None),'PASSED')
    classname=node.get('classname','');source=source_for_java(classname,inventory) if spec['id']=='backend_full' else spec.get('junitSourceByClass',{}).get(classname)
    add({'kind':'junit','class':classname,'name':node.get('name'),'sourcePath':source,'evidence':{k:evidence[k] for k in ['path','sha256']},'nodeOrdinal':ordinal,'observedResult':result,'scope':None})
  # Explicit original-object receipts: only already registered execution artifacts qualify.
  # Nested provenance artifacts must be selected by an exact reference pointer on this receipt.
  registered=[ref(receiptpath)]+list(artifacts)
  for provenance in ['sourceInventory','executionSourceInventory']:
   if isinstance(receipt.get(provenance),dict):registered.append(receipt[provenance])
  for reference_pointer in spec.get('registeredArtifactPointers',[]):
   value=pointer(receipt,reference_pointer)
   assert isinstance(value,dict) and set(['path','sha256'])<=value.keys(), 'Receipt artifact pointer must identify an actual path and digest'
   registered.append(value)
  registered_keys=set()
  for evidence in registered:
   p=resolve(evidence,receiptpath.parent)
   registered_keys.add((str(p),evidence['sha256']))
  # These nodes still require a successful same-source parent layer and independently reviewed scope.
  for adapter in spec.get('structuredRecordAdapters',[]):
   path=resolve(adapter['evidence'],receiptpath.parent);evidence=ref(path)
   assert (str(path.resolve()),evidence['sha256']) in registered_keys, 'Structured proof is not a registered execution artifact'
   data=raw_proof_document(path)
   expected=adapter['assertions'];assert expected
   assertions=[{'pointer':item['pointer'],'expected':pointer(data,item['pointer'])} for item in expected]
   matched=all(json_equal(actual['expected'],want['expected'])
               for actual,want in zip(assertions,expected))
   add({'kind':'json','class':None,'name':adapter['name'],'sourcePath':adapter['sourcePath'],'evidence':evidence,
        'observedResult':'PASSED' if matched else 'EXPECTED_RAW_ASSERTION_NOT_MET','assertions':assertions,'scope':None})
  # Explicit adapters for actual Vitest/Python/Playwright JSON records; no fabricated assertion values.
  for adapter in spec.get('jsonRecordAdapters',[]):
   path=resolve(adapter['evidence'],receiptpath.parent);evidence=ref(path)
   assert (str(path),evidence['sha256']) in registered_keys, 'JSON record proof is not a registered execution artifact'
   data=raw_proof_document(path);records=pointer(data,adapter['recordsPointer']);assert isinstance(records,list)
   for index,record in enumerate(records):
    status=pointer(record,adapter['statusPointer']);identity=pointer(record,adapter['namePointer']);source=adapter.get('sourcePath')
    if adapter.get('sourcePathPointer'):source=pointer(record,adapter['sourcePathPointer'])
    for prefix,replacement in config.get('sourcePrefixToRepository',{}).items():
     if source and source.startswith(prefix):source=replacement+source[len(prefix):]
    add({'kind':'json','class':adapter.get('class'),'name':identity,'sourcePath':source,'evidence':evidence,'observedResult':'PASSED' if json_equal(status,adapter['passedValue']) else str(status),'assertions':[{'pointer':adapter['recordsPointer']+'/'+str(index)+adapter['statusPointer'],'expected':status}],'scope':None})
 duplicates=collections.Counter((n['layer'],n['runId'],n['evidence']['path'],n.get('class'),n['name']) for n in nodes if n['kind']=='junit')
 for n in nodes:
  if n['kind']=='junit' and duplicates[(n['layer'],n['runId'],n['evidence']['path'],n.get('class'),n['name'])]!=1:
   n['admissibleAfterIndependentScopeReview']=False;n['ambiguity']='DUPLICATE_EXACT_JUNIT_NODE'
 counts=collections.Counter((n['layer'],n['observedResult'],n['admissibleAfterIndependentScopeReview']) for n in nodes)
 write('CURRENT-NODE-CATALOG.json',{'kind':'ACTUAL_EXECUTION_NODE_OBSERVATIONS_NOT_CLOSURE','sourceHead':HEAD,'sourceTree':TREE,'sourceInventorySha256':INVENTORY_SHA,'sourceDisposition':SOURCE_IDENTITY.get('disposition'),'layers':layers,'nodes':nodes,'issues':issues,'observationCounts':[{'layer':k[0],'observedResult':k[1],'admissibleAfterScopeReview':k[2],'count':v} for k,v in counts.items()],'engineeringClosureClaimMade':False})
 locators=load(OUT/'METHOD-LOCATORS.json')['selectors'];matches=[]
 for selector in locators:
  method=selector['method'];hits=[n['nodeId'] for n in nodes if n['sourcePath']==selector['sourcePath'] and (n['name']==method or re.match(re.escape(method)+r'(?:\(|\[)',n['name'] or ''))]
  matches.append({'selectorId':selector['selectorId'],'sourcePath':selector['sourcePath'],'method':method,'exactOrExpandedCurrentNodeIds':hits,'semanticScopeReviewStillRequired':True})
 write('CURRENT-METHOD-MATCHES.json',{'matches':matches,'matchingRule':'Exact source path and exact method or Java parameterized expansion beginning method( or method[. No substring, aggregate counts, historical PASS or cross-run identity substitution.'})
 reviewermatches=[]
 slots=load(OUT/'ASSESSMENT-SLOTS.json')
 for section in ['criteria','findings','clauses','verificationChecks']:
  for row in slots[section]:
   for selector in row.get('addedSelectors',[]):
    candidates=[n for n in nodes if n['sourcePath']==selector['sourcePath'] and n['layer']==selector['layer']]
    if selector['kind']=='method':
     method=selector['method'];candidates=[n for n in candidates if n['name']==method or (n['kind']=='junit' and re.match(re.escape(method)+r'(?:\(|\[)',n['name'] or ''))]
    else:
     desired={a['pointer']:a.get('expected') for a in selector.get('assertions',[])}
     candidates=[n for n in candidates if n['kind']=='json' and desired and all(any(a['pointer']==pointer and json_equal(a['expected'],value) for a in n.get('assertions',[])) for pointer,value in desired.items())]
    reviewermatches.append({'section':section,'id':row['id'],'selector':selector,'currentNodeIds':[n['nodeId'] for n in candidates],'automaticSelection':False})
 write('CURRENT-REVIEWER-SELECTOR-MATCHES.json',{'matches':reviewermatches,'boundary':'Additional reviewer selectors and rejection reasons guide exact current-node choice; no locator becomes a proof automatically.'})
 print(json.dumps({'registeredLayers':len(layers),'observedNodes':len(nodes),'matchedLocators':sum(bool(r['exactOrExpandedCurrentNodeIds']) for r in matches),'issues':len(issues),'closureClaim':False}))

def merge_reviews(paths):
 slots=load(OUT/'ASSESSMENT-SLOTS.json');require_identity(slots,True);measured=all_sources()
 allowed=['criteria','findings','clauses','verificationChecks'];indexes={section:{r['id']:r for r in slots[section]} for section in allowed}
 merged=[]
 for path in paths:
  patch=load(path);assert patch['sourceHead']==HEAD and patch['sourceInventorySha256']==INVENTORY_SHA, 'Review must be explicitly rebound/rechecked for this checkpoint.'
  assert patch.get('reviewer'), 'Named reviewer required'
  for section in allowed:
   for update in patch.get(section,[]):
    target=indexes[section][update['id']]
    if 'frozenExact' in update:assert update['frozenExact']==target.get('frozenExact'), 'Frozen clause cannot change'
    if 'acceptedExact' in update:assert update['acceptedExact']==target.get('acceptedExact'), 'Accepted criterion cannot change'
    for field in ['engineeringReason','proofLimits']:
     if field in update:
      assert update[field] is None or isinstance(update[field],str)
      assert not target.get(field) or target[field]==update[field] or target.get('reviewedBy')==patch['reviewer'], ('Conflicting reviewer text requires root resolution',section,update['id'],field)
      target[field]=update[field]
    for field in ['addedSelectors','rejectedOriginalSelectors','methodApplicabilityNotes']:
     if field in update:
      assert isinstance(update[field],list)
      for value in update[field]:
       if field=='addedSelectors':
        assert value['kind'] in ['method','structured'] and value['layer'] in LAYERS and value.get('sourcePath') and value.get('scope')
        assert value.get('method') if value['kind']=='method' else bool(value.get('assertions'))
        assert value['sourcePath'] in measured and value.get('candidateSourceSha256')==measured[value['sourcePath']]['sha256'], 'Reviewer selector source pin is stale or unmeasured'
        resolve({'path':value['sourcePath'],'sha256':value['candidateSourceSha256']})
       if field=='rejectedOriginalSelectors':assert value.get('selectorId') and value.get('reason')
       if value not in target.setdefault(field,[]):target[field].append(value)
    if 'proofSelections' in update:
     assert not target['proofSelections'] or target['proofSelections']==update['proofSelections'], 'Conflicting proof selection requires root resolution'
     target['proofSelections']=update['proofSelections']
    target['reviewedBy']=patch['reviewer'];target.setdefault('reviewPatchReferences',[]).append(ref(path));merged.append(section+':'+update['id'])
 write('ASSESSMENT-SLOTS.json',slots)
 print(json.dumps({'reviewRowsMerged':len(merged),'status':'PENDING','automaticProofSelection':False}))

def assemble(configpath):
 config=load(configpath);slots=load(OUT/'ASSESSMENT-SLOTS.json');catalog=load(OUT/'CURRENT-NODE-CATALOG.json');nodes={n['nodeId']:n for n in catalog['nodes']};issues=[]
 require_identity(config);require_identity(slots,True);require_identity(catalog,True);measured=all_sources()
 destinations=config.get('artifactDestinations',{})
 def published(reference):
  p=resolve(reference);dest=destinations.get(str(p),destinations.get(str(p.resolve())))
  if not dest:raise ValueError('No explicit repository publication destination: '+str(p))
  q=Path(dest);assert not q.is_absolute() and '..' not in q.parts
  return {'path':dest,'sha256':reference['sha256']}
 def bind(row):
  if not row.get('engineeringReason') or not row.get('reviewedBy'):issues.append({'id':row['id'],'problem':'INDIVIDUAL_CURRENT_REASON_NOT_REVIEWED'})
  proofs=[]
  for selected in row['proofSelections']:
   node=nodes.get(selected['nodeId'])
   if not node or not node['admissibleAfterIndependentScopeReview'] or catalog.get('sourceDisposition')=='FAILED_CHECKPOINT':issues.append({'id':row['id'],'problem':'NODE_ABSENT_FAILED_RUNNING_OR_SOURCE_UNBOUND','selection':selected});continue
   if not selected.get('scope') or selected.get('role') not in ['positive','adverse','supporting']:issues.append({'id':row['id'],'problem':'ASSERTION_SCOPE_OR_ROLE_MISSING'});continue
   try:
    require_identity(node,True)
    assert node['source']['path'] in measured and node['source']['sha256']==measured[node['source']['path']]['sha256'], 'Selected proof source is outside expected measured inputs'
    resolve(node['source'])
    reference=published(node['evidence'])
   except (ValueError,AssertionError) as error:issues.append({'id':row['id'],'problem':str(error)});continue
   proof={k:node[k] for k in ['kind','layer','source']};proof.update(scope=selected['scope'],role=selected['role'],evidence=reference)
   if node['kind']=='junit':proof.update({k:node[k] for k in ['class','name']})
   else:proof['assertions']=node['assertions']
   proofs.append(proof)
  if not proofs:issues.append({'id':row['id'],'problem':'NO_REVIEWED_CURRENT_PROOF'})
  out={'id':row['id'],'engineeringReason':row.get('engineeringReason'),'layers':sorted({p['layer'] for p in proofs}),'proofs':proofs}
  if row['id'].startswith('CV-'):
   out.update(result='PENDING',proofLimits=row.get('proofLimits'))
   if not row.get('proofLimits') or not {'positive','adverse'}<={p['role'] for p in proofs}:issues.append({'id':row['id'],'problem':'CV_POSITIVE_ADVERSE_OR_LIMITS_MISSING'})
  return out
 criteria=[bind(r) for r in slots['criteria']];findings=[bind(r) for r in slots['findings']];clauses=[{**bind(r),'frozenExact':r['frozenExact'],'findingId':r['findingId'],'proofLimits':r.get('proofLimits')} for r in slots['clauses']];cv=[bind(r) for r in slots['verificationChecks']]
 manifest={'kind':'SLICE3_FINAL_GATE_EXECUTION_MANIFEST','status':'PENDING','engineeringClosureClaimMade':False,'productionWriteEnabled':False,'controllerApprovalClaimMade':False,'source':None,'layers':[],'verificationChecks':cv,'criteria':criteria,'findings':findings,'boundary':'Pending assembly only. Root must admit every required terminal layer, review every current proof and all 115 clauses, publish exact artifacts, then invoke the repository finalizer. This tool cannot emit COMPLETE or grant Controller acceptance.'}
 write('EXECUTION-MANIFEST-CANDIDATE.json',manifest);write('FROZEN-CLAUSE-ASSESSMENT-CANDIDATE.json',{'status':'PENDING','entries':clauses,'count':len(clauses),'engineeringClosureClaimMade':False});write('ASSEMBLY-BLOCKERS.json',{'issues':issues,'allRequiredCurrentReviewsAndProofsBound':not issues,'layerAdmissionStillRequired':True,'status':'PENDING'})
 print(json.dumps({'assemblyIssues':len(issues),'manifestStatus':'PENDING','clauses':len(clauses),'automaticPass':False}))

parser=argparse.ArgumentParser()
parser.add_argument('command',choices=['prepare','catalog','merge-reviews','assemble-pending'])
parser.add_argument('--source-identity',type=Path,required=True)
parser.add_argument('--out',type=Path,required=True)
parser.add_argument('--config',type=Path)
parser.add_argument('--reviews',type=Path,nargs='+',default=[])
args=parser.parse_args()
try:
 OUT=temporary_output(args.out)
 OUT.mkdir(parents=True,exist_ok=True)
 SOURCE_IDENTITY=load(args.source_identity)
 HEAD=SOURCE_IDENTITY['sourceHead'];TREE=SOURCE_IDENTITY['sourceTree'];INVENTORY=resolve(SOURCE_IDENTITY['inventory']);INVENTORY_SHA=SOURCE_IDENTITY['inventory']['sha256']
 assert re.fullmatch('[0-9a-f]{40}',HEAD) and re.fullmatch('[0-9a-f]{40}',TREE)
 assert SOURCE_IDENTITY['identityScope'] in ['CLEAN_COMMIT_TREE','WORKTREE_WITH_EXACT_SOURCE_MANIFEST']
 original_identity=load(resolve(SOURCE_IDENTITY['identityEvidence']))
 assert all(original_identity[k]==SOURCE_IDENTITY[k] for k in ['sourceHead','sourceTree','identityScope']), 'Expected identity must match the original captured identity evidence'
 require_measured_tool()
 config=args.config or OUT/'EXECUTION-INPUTS.json'
 if args.command=='prepare':prepare()
 elif args.command=='catalog':catalog(config)
 elif args.command=='merge-reviews':
  assert args.reviews, '--reviews paths required';merge_reviews(args.reviews)
 else:assemble(config)
 require_measured_tool()
except (AssertionError,ValueError,KeyError,IndexError,TypeError,FileNotFoundError) as error:
 print('REFUSED: '+str(error),file=sys.stderr);sys.exit(2)
