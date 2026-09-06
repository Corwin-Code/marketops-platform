#!/usr/bin/env python3
"""Propose exact human-planned method/node bindings; never merge or claim closure."""
import argparse
import collections
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET

IDENTITY = ('sourceHead', 'sourceTree', 'sourceInventorySha256')
ROLES = {'positive', 'adverse', 'supporting'}
ROW_KINDS = {'criteria', 'findings', 'clauses', 'verificationChecks'}
class Rejected(ValueError): pass

def require(ok, message):
    if not ok: raise Rejected(message)

def digest(data): return hashlib.sha256(data).hexdigest()
def load(path): return json.loads(Path(path).read_text())
def ref(path):
    p = Path(path).resolve()
    return {'path': str(p), 'sha256': digest(p.read_bytes())}
def same(obj, identity): return all(obj.get(k) == identity[k] for k in IDENTITY)
def relative_source(path):
    return isinstance(path, str) and bool(path) and not PurePosixPath(path).is_absolute() and '..' not in PurePosixPath(path).parts and str(PurePosixPath(path)) == path

def checked_file(reference, base):
    require(isinstance(reference, dict) and set(('path', 'sha256')) <= set(reference), 'ABSENT_FILE_REFERENCE')
    p = Path(reference['path']); p = (p if p.is_absolute() else base / p).resolve()
    require(p.is_file(), 'ABSENT_FILE: ' + str(p))
    require(digest(p.read_bytes()) == reference['sha256'], 'FILE_SHA_MISMATCH: ' + str(p))
    return p

def pointer(data, value):
    require(isinstance(value, str) and (value == '' or value.startswith('/')), 'INVALID_JSON_POINTER')
    for raw in value[1:].split('/') if value else []:
        require(not re.search(r'~(?:[^01]|$)', raw), 'INVALID_POINTER_ESCAPE')
        key = raw.replace('~1', '/').replace('~0', '~')
        if isinstance(data, dict):
            require(key in data, 'ABSENT_JSON_POINTER'); data = data[key]
        elif isinstance(data, list):
            require(bool(re.fullmatch(r'0|[1-9][0-9]*', key)) and int(key) < len(data), 'ABSENT_ARRAY_POINTER'); data = data[int(key)]
        else: raise Rejected('ABSENT_JSON_POINTER')
    return data

def json_equal(a, b):
    return type(a) is type(b) and (all(json_equal(x,y) for x,y in zip(a,b)) and len(a)==len(b) if isinstance(a,list) else set(a)==set(b) and all(json_equal(a[k],b[k]) for k in a) if isinstance(a,dict) else a==b)

def method_matches(selector, node):
    name = node.get('name')
    if not isinstance(name, str): return False
    method = selector['method']
    if name == method: return True
    # Never apply Java expansion rules to Python, TypeScript or browser names.
    return selector['sourcePath'].endswith('.java') and node.get('kind') == 'junit' and (name.startswith(method+'(') or name.startswith(method+'['))

# Product execution identity and later evidence-tool execution identity are distinct.
LINE_ENDING_EQUIVALENCES = {
    'backend/marketops-server/mvnw.cmd': 'GIT_LF_TO_WORKTREE_CRLF',
    'scripts/bootstrap-repo.ps1': 'GIT_LF_TO_WORKTREE_CRLF',
}

def repository_root():
    for start in (Path.cwd().resolve(), Path(__file__).resolve().parent):
        for root in (start, *start.parents):
            if (root/'bootstrap-manifest.json').is_file(): return root
    raise Rejected('DERIVATION_REPOSITORY_ROOT_ABSENT')

def git_read(root, *args, input_bytes=None):
    # Read local objects only; no user/global config, replace refs, hooks or network.
    env={'PATH':os.defpath,'GIT_CONFIG_NOSYSTEM':'1','GIT_CONFIG_GLOBAL':os.devnull,'GIT_NO_REPLACE_OBJECTS':'1'}
    result=subprocess.run(['git','--no-pager','-c','core.hooksPath=/dev/null','-c','core.fsmonitor=false',*args],cwd=root,
        input=input_bytes,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env)
    require(result.returncode==0,'DERIVATION_GIT_CHECK_FAILED: '+' '.join(args[:2]))
    return result.stdout

def commit_listing(root, head):
    result={}
    for line in git_read(root,'ls-tree','-rz','--full-tree',head).split(b'\0'):
        if not line: continue
        metadata,path=line.split(b'\t',1);mode,kind,oid=metadata.decode('ascii').split();path=path.decode('utf-8')
        require(relative_source(path) and path not in result,'DERIVATION_INVALID_GIT_PATH')
        result[path]=(mode,kind,oid)
    return result

def git_blobs(root, oids):
    oids=sorted(set(oids))
    data=git_read(root,'cat-file','--batch',input_bytes=('\n'.join(oids)+'\n').encode('ascii'));offset=0;result={}
    for expected in oids:
        end=data.find(b'\n',offset);require(end>=0,'DERIVATION_BLOB_HEADER_ABSENT')
        parts=data[offset:end].decode('ascii').split();require(len(parts)==3 and parts[0]==expected and parts[1]=='blob','DERIVATION_BLOB_INVALID')
        size=int(parts[2]);offset=end+1;raw=data[offset:offset+size];offset+=size
        require(len(raw)==size and data[offset:offset+1]==b'\n','DERIVATION_BLOB_TRUNCATED');offset+=1;result[expected]=raw
    require(offset==len(data),'DERIVATION_BLOB_TRAILING_DATA')
    return result

def derivation_sources(expected, inventory, root, base, audit=None):
    """Validate an evidence-only descendant; never relabel original product results."""
    require(load(checked_file(expected['inventory'],base))==inventory,'DERIVATION_INVENTORY_ARGUMENT_DIFFERS_FROM_HASHED_FILE')
    captured=load(checked_file(expected.get('identityEvidence'),base))
    require(all(captured.get(k)==expected.get(k) for k in ('sourceHead','sourceTree','identityScope')),'DERIVATION_CAPTURED_PRODUCT_IDENTITY_MISMATCH')
    d=expected['derivationSourceIdentity'];product={k:expected[k] for k in ('sourceHead','sourceTree')}
    product['sourceInventorySha256']=expected['inventory']['sha256'];head=d.get('sourceHead');tree=d.get('sourceTree')
    require(expected.get('identityScope')=='CLEAN_COMMIT_TREE' and d.get('identityScope')=='EVIDENCE_DERIVATION_ONLY','DERIVATION_REQUIRES_CLEAN_COMMIT_IDENTITY')
    require(all(isinstance(x,str) and re.fullmatch('[0-9a-f]{40}',x) for x in [head,tree,*[product[k] for k in ('sourceHead','sourceTree')]]),'DERIVATION_INVALID_GIT_IDENTITY')
    require(d.get('appliesToProductSourceHead')==product['sourceHead'] and d.get('appliesToProductSourceTree')==product['sourceTree'] and d.get('sourceInventorySha256')==product['sourceInventorySha256'],'DERIVATION_PRODUCT_IDENTITY_MISMATCH')
    require(head!=product['sourceHead'],'DERIVATION_HEAD_MUST_BE_DISTINCT_DESCENDANT')
    for h,t in [(head,tree),(product['sourceHead'],product['sourceTree'])]:
        require(git_read(root,'rev-parse',h+'^{commit}').decode().strip()==h and git_read(root,'rev-parse',h+'^{tree}').decode().strip()==t,'DERIVATION_COMMIT_TREE_MISMATCH')
    git_read(root,'merge-base','--is-ancestor',product['sourceHead'],head)
    require(git_read(root,'rev-parse','HEAD').decode().strip()==head,'DERIVATION_WORKTREE_HEAD_MISMATCH')
    require(not git_read(root,'status','--porcelain=v1','--untracked-files=all'),'DERIVATION_WORKTREE_NOT_CLEAN')
    original=commit_listing(root,product['sourceHead']);current=commit_listing(root,head)
    sources={}
    require(bool(inventory.get('files')),'DERIVATION_RUNTIME_INVENTORY_EMPTY')
    for row in inventory['files']:
        p=row.get('path');require(relative_source(p) and p not in sources and re.fullmatch('[0-9a-f]{64}',row.get('sha256','')),'DERIVATION_INVALID_OR_DUPLICATE_RUNTIME_INPUT')
        require(p in original and original[p][1]=='blob' and original[p][0] in ('100644','100755'),'DERIVATION_RUNTIME_SOURCE_NOT_REGULAR_BLOB')
        require(current.get(p)==original[p],'DERIVATION_RUNTIME_GIT_INPUT_CHANGED: '+p)
        sources[p]=dict(row,sourceAuthorityScope='RUNTIME_SOURCE_INVENTORY')
    # A new runtime path cannot hide outside the old inventory. Evidence/docs may
    # change; any already-inventoried document is still frozen by the loop above.
    mandatory={p for p in original if p.startswith(('backend/','frontend/','infra/','scripts/','tests/','.github/','fixtures/'))}
    mandatory.update(p for p in ['.dockerignore','.editorconfig','.env.example','.gitattributes','.gitignore','AGENTS.md','Makefile','bootstrap-manifest.json','docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/collect_execution.py'] if p in original)
    require(mandatory<=set(sources),'DERIVATION_RUNTIME_INVENTORY_INCOMPLETE')
    changed={p for p in set(original)|set(current) if original.get(p)!=current.get(p)}
    require(all(p.startswith('docs/') and p not in sources for p in changed),'DERIVATION_NON_EVIDENCE_PATH_CHANGED')
    equivalences={}
    for rule in d.get('lineEndingEquivalences',[]):
        p=rule.get('path');require(p not in equivalences and p in LINE_ENDING_EQUIVALENCES and rule.get('transformation')==LINE_ENDING_EQUIVALENCES[p],'DERIVATION_LINE_ENDING_RULE_NOT_ALLOWED')
        equivalences[p]=rule['transformation']
    runtime_paths=sorted(sources);applied_equivalences=[]
    blobs=git_blobs(root,[original[p][2] for p in sources])
    for p,row in sources.items():
        raw=blobs[original[p][2]]
        if digest(raw)!=row['sha256']:
            require(p in equivalences and b'\r' not in raw and digest(raw.replace(b'\n',b'\r\n'))==row['sha256'],'DERIVATION_PRODUCT_INVENTORY_GIT_SHA_MISMATCH: '+p)
            applied_equivalences.append({'path':p,'transformation':equivalences[p]})
        local=root/p;require(local.is_file() and not local.is_symlink() and digest(local.read_bytes())==row['sha256'],'DERIVATION_LOCAL_RUNTIME_INPUT_CHANGED: '+p)
    extras=expected.get('additionalExecutionInputs',[]);require(bool(extras),'DERIVATION_EXECUTION_INPUTS_ABSENT')
    receipts={}
    for row in extras:
        p=row.get('path');require(relative_source(p) and p.startswith('docs/') and p not in sources,'DERIVATION_INVALID_OR_DUPLICATE_ADDITIONAL_INPUT')
        require(row.get('sourceHead')==head and row.get('sourceTree')==tree and row.get('scope')=='EVIDENCE_DERIVATION_ONLY','DERIVATION_ADDITIONAL_INPUT_IDENTITY_MISMATCH')
        entry=current.get(p);require(entry is not None and entry[0] in ('100644','100755') and entry[1]=='blob' and row.get('gitBlobOid')==entry[2],'DERIVATION_ADDITIONAL_GIT_BLOB_MISMATCH')
        raw=git_blobs(root,[entry[2]])[entry[2]];local=root/p
        require(digest(raw)==row.get('sha256') and local.is_file() and not local.is_symlink() and local.read_bytes()==raw,'DERIVATION_ADDITIONAL_INPUT_BYTES_CHANGED')
        rp=checked_file(row.get('executionReceipt'),base)
        if rp not in receipts:
            receipt=load(rp);receipts[rp]=receipt
            require(receipt.get('kind')=='EVIDENCE_DERIVATION_EXECUTION_RECEIPT' and receipt.get('scope')=='EVIDENCE_DERIVATION_ONLY','DERIVATION_RECEIPT_SCOPE_INVALID')
            require(receipt.get('sourceHead')==head and receipt.get('sourceTree')==tree and all(receipt.get(k)==d.get(k) for k in ('appliesToProductSourceHead','appliesToProductSourceTree','sourceInventorySha256')),'DERIVATION_RECEIPT_IDENTITY_MISMATCH')
            require(receipt.get('result')=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and receipt.get('sourceStable') is True and type(receipt.get('exitCode')) is int and receipt['exitCode']==0 and bool(receipt.get('runId')),'DERIVATION_RECEIPT_NOT_TERMINAL_SUCCESS')
            try:
                require(all(isinstance(receipt.get(k),str) for k in ('startedAt','finishedAt')),'DERIVATION_RECEIPT_TIME_INVALID')
                times=[datetime.fromisoformat(receipt[k].replace('Z','+00:00')) for k in ('startedAt','finishedAt')]
                require(all(t.tzinfo is not None for t in times) and times[0]<=times[1]<=datetime.now(timezone.utc),'DERIVATION_RECEIPT_TIME_INVALID')
            except (KeyError,TypeError,ValueError): raise Rejected('DERIVATION_RECEIPT_TIME_INVALID')
            require(isinstance(receipt.get('argv'),list) and bool(receipt['argv']) and all(isinstance(a,str) and a for a in receipt['argv']) and bool(receipt.get('evidence')),'DERIVATION_RECEIPT_COMMAND_OR_RAW_EVIDENCE_ABSENT')
            for reference in receipt['evidence']: checked_file(reference,rp.parent)
            pins={}
            for pin in receipt.get('executedInputs',[]):
                q=pin.get('path');require(relative_source(q) and q not in pins and current.get(q,(None,None,None))[2]==pin.get('gitBlobOid') and pin.get('sourceHead')==head and pin.get('sourceTree')==tree,'DERIVATION_RECEIPT_EXECUTED_INPUT_INVALID')
                require(current[q][1]=='blob' and digest(git_blobs(root,[pin['gitBlobOid']])[pin['gitBlobOid']])==pin.get('sha256'),'DERIVATION_RECEIPT_EXECUTED_INPUT_SHA_MISMATCH');pins[q]=pin
            receipt['_validatedInputs']=pins
        pin=receipts[rp]['_validatedInputs'].get(p)
        require(pin is not None and all(pin.get(k)==row.get(k) for k in ('sourceHead','sourceTree','gitBlobOid','sha256')),'DERIVATION_ADDITIONAL_INPUT_NOT_EXECUTED')
        sources[p]=dict(row,sourceAuthorityScope='ADDITIONAL_DERIVATION_INPUT',additionalDerivationInput=True)
    if audit is not None:
        listing_digest=lambda listing:digest(json.dumps([(p,*listing[p]) for p in runtime_paths],separators=(',',':')).encode())
        audit.update(kind='EXACT_EVIDENCE_DERIVATION_SOURCE_VALIDATION',productSourceHead=product['sourceHead'],productSourceTree=product['sourceTree'],sourceInventorySha256=product['sourceInventorySha256'],derivationSourceHead=head,derivationSourceTree=tree,
            runtimeFileCount=len(runtime_paths),originalRuntimeGitEntriesSha256=listing_digest(original),derivationRuntimeGitEntriesSha256=listing_digest(current),
            changedPaths=[{'path':p,'originalEntry':original.get(p),'derivationEntry':current.get(p)} for p in sorted(changed)],
            appliedLineEndingEquivalences=applied_equivalences,workingTreeClean=True,allRuntimeGitEntriesIdentical=True,productExecutionClaimMade=False)
    return sources


COMPOSITE_KIND = 'EXPLICIT_COMPOSITE_PRODUCT_AND_DERIVATION_SCOPES'

def evidence_keys(receipt, receipt_path):
    keys=set()
    for item in receipt.get('evidence',[]):
        key=(str(checked_file(item,receipt_path.parent)),item['sha256'])
        require(key not in keys,'DUPLICATE_EXECUTION_EVIDENCE_REFERENCE')
        keys.add(key)
    return keys

def composite_execution_context(reference, identity, base, expected):
    """Unwrap explicit scopes; never manufacture one aggregate command/run."""
    wp=checked_file(reference,base);wrapper=load(wp)
    require(set(wrapper)=={'kind','parent','toolExecutions'} and wrapper['kind']==COMPOSITE_KIND,'COMPOSITE_SCOPE_WRAPPER_FIELDS_INVALID')
    d=expected.get('derivationSourceIdentity');require(isinstance(d,dict),'COMPOSITE_REQUIRES_DERIVATION_IDENTITY')
    pp=checked_file(wrapper['parent'],wp.parent);parent=load(pp)
    require(parent.get('kind')!=COMPOSITE_KIND and same(parent,identity),'COMPOSITE_PRODUCT_PARENT_IDENTITY_MISMATCH')
    require(parent.get('result')=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and parent.get('sourceStable') is True and type(parent.get('exitCode')) is int and parent['exitCode']==0 and bool(parent.get('finishedAt')) and bool(parent.get('runId')),'COMPOSITE_PRODUCT_PARENT_NOT_TERMINAL_SUCCESS')
    registered={}
    for source in expected.get('additionalExecutionInputs',[]):
        rr=source.get('executionReceipt')
        if rr: registered[(str(checked_file(rr,base)),rr['sha256'])]=rr
    product_keys=evidence_keys(parent,pp);product_keys.add((str(pp),digest(pp.read_bytes())))
    for field in ['sourceInventory','executionSourceInventory']:
        inventory_ref=parent.get(field)
        if isinstance(inventory_ref,dict) and inventory_ref.get('sha256')==expected['inventory']['sha256']:
            product_keys.add((str(checked_file(inventory_ref,pp.parent)),inventory_ref['sha256']))
    seen=set(product_keys);receipts={};runs={parent['runId']}
    require(isinstance(wrapper['toolExecutions'],list) and bool(wrapper['toolExecutions']),'COMPOSITE_TOOL_EXECUTIONS_ABSENT')
    for rr in wrapper['toolExecutions']:
        rp=checked_file(rr,wp.parent);key=(str(rp),rr['sha256'])
        require(key in registered and key not in receipts,'COMPOSITE_TOOL_RECEIPT_UNREGISTERED_OR_DUPLICATE')
        receipt=load(rp)
        require(receipt.get('kind')=='EVIDENCE_DERIVATION_EXECUTION_RECEIPT' and receipt.get('scope')=='EVIDENCE_DERIVATION_ONLY','COMPOSITE_TOOL_RECEIPT_SCOPE_INVALID')
        require(all(receipt.get(k)==d.get(k) for k in ['sourceHead','sourceTree','appliesToProductSourceHead','appliesToProductSourceTree','sourceInventorySha256']),'COMPOSITE_TOOL_RECEIPT_IDENTITY_MISMATCH')
        require(receipt.get('result')=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and receipt.get('sourceStable') is True and type(receipt.get('exitCode')) is int and receipt['exitCode']==0 and bool(receipt.get('finishedAt')) and bool(receipt.get('runId')),'COMPOSITE_TOOL_RECEIPT_NOT_TERMINAL_SUCCESS')
        require(receipt['runId'] not in runs,'COMPOSITE_EXECUTION_RUN_AMBIGUOUS');runs.add(receipt['runId'])
        keys=evidence_keys(receipt,rp);require(bool(keys) and not (seen&keys),'COMPOSITE_RAW_ARTIFACT_OWNERSHIP_AMBIGUOUS');seen.update(keys)
        receipts[key]={'path':rp,'receipt':receipt,'evidenceKeys':keys,'reference':ref(rp)}
    return {'wrapper':ref(wp),'parent':parent,'parentPath':pp,'parentReference':ref(pp),'productEvidenceKeys':product_keys,'toolExecutions':[v['reference'] for v in receipts.values()],'toolReceipts':receipts}

def product_node_execution(evidence, context, expected, base):
    require(context is not None,'PRODUCT_NODE_REQUIRES_ORIGINAL_COMPOSITE_PARENT')
    ep=checked_file(evidence,base);key=(str(ep),evidence['sha256'])
    original=context['productEvidenceKeys'];parent_ref=context['parentReference']
    require(key in original,'PRODUCT_NODE_RAW_NOT_IN_ORIGINAL_PARENT')
    product={k:expected[k] for k in ['sourceHead','sourceTree']};product['sourceInventorySha256']=expected['inventory']['sha256']
    return {'runId':context['parent']['runId'],'productSourceIdentity':product,'executionSourceIdentity':product,'actualExecutionReceipt':parent_ref}

def execution_scope_matches(node, scope, base):
    for key,value in scope.items():
        if key in ['actualExecutionReceipt','additionalExecutionReceipt']:
            actual=node.get(key)
            if checked_file(actual,base)!=checked_file(value,base) or actual['sha256']!=value['sha256']: return False
        elif node.get(key)!=value: return False
    return True

def additional_node_execution(source, evidence, context, expected, base):
    require(context is not None,'ADDITIONAL_NODE_REQUIRES_EXPLICIT_COMPOSITE_SCOPE')
    rr=source.get('executionReceipt');rp=checked_file(rr,base);key=(str(rp),rr['sha256'])
    execution=context['toolReceipts'].get(key);require(execution is not None,'ADDITIONAL_EXECUTION_RECEIPT_NOT_REGISTERED_ON_COMPOSITE')
    ep=checked_file(evidence,base);require((str(ep),evidence['sha256']) in execution['evidenceKeys'],'ADDITIONAL_NODE_RAW_NOT_IN_OWN_EXECUTION_RECEIPT')
    receipt=execution['receipt'];pins=[p for p in receipt.get('executedInputs',[]) if p.get('path')==source.get('path')]
    require(len(pins)==1 and all(pins[0].get(k)==source.get(k) for k in ['sourceHead','sourceTree','gitBlobOid','sha256']),'ADDITIONAL_NODE_SOURCE_NOT_IN_OWN_EXECUTION_RECEIPT')
    d=expected['derivationSourceIdentity'];require(source.get('sourceHead')==d['sourceHead'] and source.get('sourceTree')==d['sourceTree'],'ADDITIONAL_NODE_EXECUTION_SOURCE_IDENTITY_MISMATCH')
    product={k:expected[k] for k in ['sourceHead','sourceTree']};product['sourceInventorySha256']=expected['inventory']['sha256']
    return {'runId':receipt['runId'],'productSourceIdentity':product,'executionSourceIdentity':d,'actualExecutionReceipt':execution['reference'],'additionalExecutionReceipt':execution['reference']}

def java_source_resolution(classname, suite_name, inventory):
    """Use original XML Java identities only; a display label never becomes a class."""
    fqcn = r'[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+'
    def candidates(name):
        if not isinstance(name,str) or not re.fullmatch(fqcn,name): return []
        suffix = '/' + name.split('$')[0].replace('.','/') + '.java'
        return [p for p in inventory if relative_source(p) and p.endswith(suffix)]
    direct, suite = candidates(classname), candidates(suite_name)
    answer = {'basis': None, 'rootSuiteName': suite_name,
              'testcaseClassname': classname, 'sourcePath': None, 'problem': None}
    if len(direct)>1 or len(suite)>1:
        answer['problem']='AMBIGUOUS_JAVA_SOURCE'
    elif suite_name and len(suite)!=1:
        answer['problem']='ROOT_SUITE_SOURCE_UNRESOLVED'
    elif len(direct)==1:
        if suite and direct[0]!=suite[0]: answer['problem']='ROOT_AND_TESTCASE_SOURCE_CONFLICT'
        else: answer.update(basis='TESTCASE_CLASSNAME',sourcePath=direct[0])
    elif isinstance(classname,str) and re.fullmatch(fqcn,classname):
        answer['problem']='TESTCASE_JAVA_SOURCE_UNRESOLVED'
    elif len(suite)==1:
        answer.update(basis='ROOT_TESTSUITE_NAME',sourcePath=suite[0])
    else:
        answer['problem']='JAVA_SOURCE_UNRESOLVED'
    return answer


def read_xml(path, inventory=None):
    root = ET.parse(path).getroot()
    # Do not infer a source from a filename, testcase display label or another suite.
    suite_name = root.get('name') if root.tag=='testsuite' else None
    rows = []
    for i,n in enumerate(root.iter('testcase')):
        row = {'class': n.get('classname',''), 'name': n.get('name'), 'nodeOrdinal': i,
               'observedResult': next((t.upper() for t in ('failure','error','skipped') if n.find(t) is not None), 'PASSED')}
        if inventory is not None:
            row['sourceResolution'] = java_source_resolution(row['class'],suite_name,inventory)
            row['sourcePath'] = row['sourceResolution']['sourcePath']
        rows.append(row)
    return rows

def execute(expected_path, catalog_path, plan_paths, blockers_path):
    expected_path=Path(expected_path).resolve(); catalog_path=Path(catalog_path).resolve()
    expected=load(expected_path); catalog=load(catalog_path)
    inventory_path=checked_file(expected['inventory'], expected_path.parent)
    identity={k:expected[k] for k in IDENTITY if k != 'sourceInventorySha256'}
    identity['sourceInventorySha256']=expected['inventory']['sha256']
    require(same(catalog,identity), 'CATALOG_SOURCE_IDENTITY_MISMATCH')
    require(expected.get('disposition') != 'FAILED_CHECKPOINT' and catalog.get('sourceDisposition') != 'FAILED_CHECKPOINT', 'FAILED_CHECKPOINT_NOT_ADMISSIBLE')
    identity_ref=expected.get('identityEvidence')
    if identity_ref: checked_file(identity_ref,expected_path.parent)
    inventory=load(inventory_path); sources={}
    for item in inventory['files']:
        require(relative_source(item.get('path')) and item['path'] not in sources, 'INVALID_OR_DUPLICATE_INVENTORY_SOURCE')
        sources[item['path']]=dict(item, sourceAuthorityScope='RUNTIME_SOURCE_INVENTORY')
    derivation_audit={}
    if expected.get('derivationSourceIdentity') is not None:
        root=repository_root();sources=derivation_sources(expected,inventory,root,expected_path.parent,derivation_audit)
        running=Path(__file__).resolve()
        require(running.is_relative_to(root) and running.relative_to(root).as_posix() in sources and sources[running.relative_to(root).as_posix()]['sha256']==digest(running.read_bytes()), 'BINDER_DERIVATION_TOOL_NOT_BOUND')
        require(catalog.get('derivationSourceIdentity')==expected['derivationSourceIdentity'] and catalog.get('additionalExecutionInputs')==expected.get('additionalExecutionInputs'), 'CATALOG_DERIVATION_IDENTITY_MISMATCH')
    else:
        for item in expected.get('additionalExecutionInputs',[]):
            require(item.get('sourceHead')==identity['sourceHead'] and relative_source(item.get('path')) and item['path'] not in sources, 'INVALID_ADDITIONAL_INPUT_IDENTITY')
            sources[item['path']]=dict(item, sourceAuthorityScope='ADDITIONAL_DERIVATION_INPUT')
    blockers=load(blockers_path)
    require(same(blockers,identity), 'BLOCKER_REVIEW_SOURCE_IDENTITY_MISMATCH')
    require(bool(blockers.get('reviewer')) and bool(blockers.get('reviewBoundary')), 'BLOCKER_REVIEW_UNATTRIBUTED')
    blocked={(b['rowKind'],b['id']): b for b in blockers['rows']}
    plans=[]; rowkeys=set()
    for plan_path in plan_paths:
        p=Path(plan_path).resolve(); plan=load(p)
        require(same(plan,identity),'PLAN_SOURCE_IDENTITY_MISMATCH: '+str(p))
        for index,row in enumerate(plan['rows']):
            key=(row['rowKind'],row['id'])
            require(row['rowKind'] in ROW_KINDS and key not in rowkeys,'DUPLICATE_OR_INVALID_ROW: '+str(key))
            require(not row.get('proofSelections'), 'PLAN_ALREADY_HAS_PROOF_SELECTIONS: '+row['id'])
            rowkeys.add(key)
        plans.append((p,plan))
    layers={}; layer_errors={}; layer_artifacts={}; composite_contexts={}; product_receipts={}
    for layer in catalog['layers']:
        lid=layer['id']; require(lid not in layers,'AMBIGUOUS_PARENT_LAYER: '+lid); layers[lid]=layer
        errors=[]; registered=set()
        if not layer.get('receipt'): errors.append('NO_COMPLETED_PARENT_RECEIPT')
        else:
            rp=checked_file(layer['receipt'],catalog_path.parent); receipt=load(rp)
            if receipt.get('kind')==COMPOSITE_KIND:
                require(lid=='governance','COMPOSITE_SCOPE_ONLY_ALLOWED_FOR_GOVERNANCE')
                context=composite_execution_context(layer['receipt'],identity,expected_path.parent,expected);composite_contexts[lid]=context
                require(layer.get('kind')==COMPOSITE_KIND and layer.get('parent')==context['parentReference'] and layer.get('toolExecutions')==context['toolExecutions'],'CATALOG_COMPOSITE_SCOPE_METADATA_DISAGREES')
                rp=context['parentPath'];receipt=context['parent']
            else:
                require(layer.get('kind')!=COMPOSITE_KIND,'CATALOG_COMPOSITE_SCOPE_METADATA_DISAGREES')
            product_receipts[lid]=ref(rp)
            registered.add((str(rp),digest(rp.read_bytes())))
            ri={k:receipt.get(k) for k in IDENTITY}
            if lid=='security':
                ri['sourceInventorySha256']=receipt.get('executionSourceInventory',{}).get('sha256')
                criteria=receipt.get('criteria',{})
                terminal=bool(criteria) and all(v is True for v in criteria.values()) and receipt.get('run',{}).get('status')=='completed' and receipt.get('run',{}).get('conclusion')=='success'
                runid=str(receipt.get('run',{}).get('id'))
            else:
                terminal=receipt.get('result')=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and receipt.get('sourceStable') is True and type(receipt.get('exitCode')) is int and receipt['exitCode']==0 and bool(receipt.get('finishedAt'))
                runid=receipt.get('runId')
            if not same(ri,identity): errors.append('PARENT_RECEIPT_SOURCE_IDENTITY_MISMATCH')
            if not terminal: errors.append('PARENT_LAYER_NOT_COMPLETED_SUCCESSFULLY')
            if layer.get('exactSourceIdentityMatches') is not True or layer.get('terminalSuccessfulCommandObserved') is not True or layer.get('runId') != runid: errors.append('CATALOG_PARENT_RECEIPT_DISAGREES')
            # Register receipt evidence refs without scanning unrelated payloads. Selected files are SHA verified below.
            for e in receipt.get('evidence',[]):
                ep=Path(e['path']); ep=(ep if ep.is_absolute() else rp.parent/ep).resolve()
                registered.add((str(ep),e['sha256']))
            if lid in composite_contexts: registered.update(composite_contexts[lid]['productEvidenceKeys'])
            layer_artifacts[lid]=registered
        errors.extend('CATALOG_ISSUE: '+str(issue.get('problem')) for issue in catalog.get('issues',[]) if issue.get('layer') in (None,lid))
        layer_errors[lid]=errors
    ids=set(); nodes=catalog['nodes']
    for n in nodes:
        require(n.get('nodeId') not in ids and bool(n.get('nodeId')), 'DUPLICATE_OR_ABSENT_NODE_ID')
        ids.add(n['nodeId'])
        require(same(n,identity),'NODE_SOURCE_IDENTITY_MISMATCH: '+n['nodeId'])
        require(n.get('layer') in layers, 'NODE_WITHOUT_PARENT_LAYER')
        if n.get('source') is not None:
            source=n['source']; path=source.get('path'); actual=sources.get(path)
            require(path==n.get('sourcePath') and actual is not None and source.get('sha256')==actual['sha256'], 'CATALOG_SOURCE_NOT_EXACT_INVENTORY_MEMBER: '+n['nodeId'])
            require(n.get('sourceAuthorityScope')==actual['sourceAuthorityScope'], 'CATALOG_SOURCE_AUTHORITY_SCOPE_MISMATCH')
            if expected.get('derivationSourceIdentity') is not None and actual.get('sourceAuthorityScope')=='ADDITIONAL_DERIVATION_INPUT' and actual.get('executionReceipt'):
                scope=additional_node_execution(actual,n['evidence'],composite_contexts.get(n['layer']),expected,expected_path.parent)
                require(execution_scope_matches(n,scope,expected_path.parent),'CATALOG_ADDITIONAL_EXECUTION_SCOPE_DISAGREES')
            elif expected.get('derivationSourceIdentity') is not None and actual.get('sourceAuthorityScope')=='RUNTIME_SOURCE_INVENTORY':
                require(execution_scope_matches(n,{'productSourceIdentity':identity,'executionSourceIdentity':identity,'actualExecutionReceipt':product_receipts.get(n['layer'])},catalog_path.parent),'CATALOG_PRODUCT_EXECUTION_SCOPE_DISAGREES')
                if n['layer'] in composite_contexts:
                    scope=product_node_execution(n['evidence'],composite_contexts[n['layer']],expected,expected_path.parent)
                    require(execution_scope_matches(n,scope,catalog_path.parent),'CATALOG_PRODUCT_EXECUTION_SCOPE_DISAGREES')
    xml_cache={}; json_cache={}; verified_files={}; rows=[]
    for plan_path,plan in plans:
        provenance=ref(plan_path)
        for row_index,row in enumerate(plan['rows']):
            row_provenance=dict(provenance, pointer='/rows/'+str(row_index), reviewer=plan.get('reviewer'))
            results=[]; proposed=[]
            rowblock=blocked.get((row['rowKind'],row['id']))
            for si,sel in enumerate(row.get('selectedMethods',[])):
                require(sel.get('kind')=='method' and relative_source(sel.get('sourcePath')) and bool(sel.get('method')) and sel.get('plannedRole') in ROLES and bool(sel.get('scope')) and bool(sel.get('evidenceBoundary')), 'INVALID_EXPLICIT_METHOD_SELECTOR: '+row['id'])
                problems=[]; actual=sources.get(sel['sourcePath'])
                if not actual or actual['sha256']!=sel.get('candidateSourceSha256'): problems.append('SELECTOR_SOURCE_NOT_EXACT_INVENTORY_MEMBER')
                if actual and actual.get('sourceAuthorityScope')=='ADDITIONAL_DERIVATION_INPUT':
                    if not actual.get('executionReceipt'): problems.append('ADDITIONAL_SOURCE_NOT_EXECUTED')
                    else: checked_file(actual['executionReceipt'],expected_path.parent)
                parent=layers.get(sel['layer'])
                problems.extend(layer_errors.get(sel['layer'],['PARENT_LAYER_ABSENT']))
                matches=[n for n in nodes if n.get('sourcePath')==sel['sourcePath'] and n.get('layer')==sel['layer'] and method_matches(sel,n)]
                if not matches: problems.append('NO_EXACT_CURRENT_METHOD_NODES')
                if len({n.get('class') for n in matches})>1: problems.append('AMBIGUOUS_METHOD_CLASSES')
                keys=collections.Counter((n.get('class'),n.get('name')) for n in matches)
                if any(v>1 for v in keys.values()): problems.append('AMBIGUOUS_REPEATED_METHOD_INSTANCE')
                for n in matches:
                    if not n.get('source') or n['source'].get('sha256')!=sel['candidateSourceSha256']: problems.append('NODE_SOURCE_SHA_MISMATCH')
                    if n.get('observedResult')!='PASSED' or n.get('admissibleAfterIndependentScopeReview') is not True or n.get('terminalSuccessfulCommandObserved') is not True: problems.append('METHOD_HAS_FAILED_SKIPPED_OR_INCOMPLETE_INSTANCE')
                    if n.get('ambiguity'): problems.append('NODE_AMBIGUITY: '+str(n['ambiguity']))
                    additional=actual and actual.get('sourceAuthorityScope')=='ADDITIONAL_DERIVATION_INPUT'
                    additional_derivation=expected.get('derivationSourceIdentity') is not None and additional
                    if additional_derivation and actual.get('executionReceipt'):
                        scope=additional_node_execution(actual,n['evidence'],composite_contexts.get(sel['layer']),expected,expected_path.parent)
                        if not execution_scope_matches(n,scope,expected_path.parent): problems.append('CATALOG_ADDITIONAL_EXECUTION_SCOPE_DISAGREES')
                    elif parent and n.get('runId')!=parent.get('runId'): problems.append('NODE_PARENT_RUN_MISMATCH')
                    ep=Path(n['evidence']['path']); ep=(ep if ep.is_absolute() else catalog_path.parent/ep).resolve()
                    ek=(str(ep),n['evidence']['sha256'])
                    if not additional_derivation and ek not in layer_artifacts.get(sel['layer'],set()): problems.append('NODE_EVIDENCE_NOT_REGISTERED_ON_PARENT')
                    if ek not in verified_files: verified_files[ek]=checked_file(n['evidence'],catalog_path.parent)
                    if n.get('kind')=='junit':
                        if ek not in xml_cache: xml_cache[ek]=read_xml(ep,sources)
                        raw=xml_cache[ek]; ordinal=n.get('nodeOrdinal')
                        if type(ordinal) is not int or not 0<=ordinal<len(raw) or any(raw[ordinal][k]!=n.get(k) for k in ('class','name','observedResult')): problems.append('CATALOG_NODE_DIFFERS_FROM_RAW_JUNIT')
                        if sel['sourcePath'].endswith('.java') and type(ordinal) is int and 0<=ordinal<len(raw):
                            original=raw[ordinal]
                            if original['sourceResolution']['problem'] or original['sourcePath']!=sel['sourcePath']:
                                problems.append('RAW_JUNIT_SOURCE_AUTHORITY_MISMATCH')
                            if n.get('sourceResolution') is not None and n['sourceResolution']!=original['sourceResolution']:
                                problems.append('CATALOG_SOURCE_RESOLUTION_DIFFERS_FROM_RAW_JUNIT')
                        # Include every raw expansion, even a failed instance omitted from catalog.
                        rawfamily=[r for r in raw if r['class']==n.get('class') and method_matches(sel,dict(r,kind='junit'))]
                        catalogfamily={(x['evidence']['sha256'],x.get('nodeOrdinal')) for x in matches}
                        if any((ek[1],r['nodeOrdinal']) not in catalogfamily for r in rawfamily): problems.append('RAW_METHOD_INSTANCE_MISSING_FROM_CATALOG')
                        if any(r['observedResult']!='PASSED' for r in rawfamily): problems.append('RAW_METHOD_HAS_FAILED_SKIPPED_OR_INCOMPLETE_INSTANCE')
                    elif n.get('kind')=='json':
                        if ek not in json_cache: json_cache[ek]=load(ep)
                        if not n.get('assertions'): problems.append('JSON_NODE_HAS_NO_RAW_ASSERTION')
                        for a in n.get('assertions',[]):
                            if not json_equal(pointer(json_cache[ek],a['pointer']),a['expected']): problems.append('CATALOG_ASSERTION_DIFFERS_FROM_RAW_JSON')
                    else: problems.append('UNSUPPORTED_METHOD_NODE_KIND')
                # Check every registered JUnit report for this Java class/method family,
                # including a second report wholly omitted by a damaged catalog.
                if sel['sourcePath'].endswith('.java') and matches:
                    expected_class=sel['sourcePath'].split('/src/test/java/')[-1][:-5].replace('/','.')
                    classes={n.get('class') for n in matches}|{expected_class}
                    raw_seen=[]
                    for epstr,esh in layer_artifacts.get(sel['layer'],set()):
                        if Path(epstr).suffix!='.xml': continue
                        ek=(epstr,esh)
                        if ek not in verified_files: verified_files[ek]=checked_file({'path':epstr,'sha256':esh},catalog_path.parent)
                        if ek not in xml_cache: xml_cache[ek]=read_xml(Path(epstr),sources)
                        raw_seen.extend((ek,r) for r in xml_cache[ek]
                            if (r['sourcePath']==sel['sourcePath'] or r['class'] in classes
                                or r['sourceResolution']['rootSuiteName']==expected_class)
                            and method_matches(sel,dict(r,kind='junit')))
                    if any(r['sourceResolution']['problem'] or r['sourcePath']!=sel['sourcePath'] for _,r in raw_seen):
                        problems.append('REGISTERED_RAW_SOURCE_AUTHORITY_UNRESOLVED')
                    if len({r['class'] for _,r in raw_seen})>1:
                        problems.append('AMBIGUOUS_RAW_METHOD_CLASSES')
                    catalog_seen={(str((Path(n['evidence']['path']) if Path(n['evidence']['path']).is_absolute() else catalog_path.parent/Path(n['evidence']['path'])).resolve()),n['evidence']['sha256'],n.get('nodeOrdinal')) for n in matches}
                    if any((ek[0],ek[1],r['nodeOrdinal']) not in catalog_seen for ek,r in raw_seen): problems.append('REGISTERED_RAW_METHOD_INSTANCE_MISSING_FROM_CATALOG')
                    if any(r['observedResult']!='PASSED' for _,r in raw_seen): problems.append('REGISTERED_RAW_METHOD_HAS_FAILED_SKIPPED_OR_INCOMPLETE_INSTANCE')
                if rowblock: problems.append('EXPLICIT_ROW_REVIEW_BLOCK')
                selected=[]
                if not problems:
                    selected=[{'nodeId':n['nodeId'],'scope':sel['scope'],'role':sel['plannedRole']} for n in matches]
                    proposed.extend(selected)
                results.append({'selectorIndex':si,'selector':sel,'sourcePlanProvenance':dict(row_provenance,pointer=f'/rows/{row_index}/selectedMethods/{si}'),
                                'matchedCurrentNodeIds':[n['nodeId'] for n in matches], 'problems':sorted(set(problems)),
                                'proposedProofSelections':selected,'status':'PROPOSED_REQUIRES_ROOT_SCOPE_REVIEW' if selected else 'UNBOUND'})
            rows.append({'id':row['id'],'rowKind':row['rowKind'],'sourcePlanProvenance':row_provenance,
                         'status':'PROPOSAL_ONLY_REQUIRES_ROOT_REVIEW','proposedProofSelections':proposed,
                         'methodResults':results,'explicitReviewBlock':rowblock,'remainingEvidence':row.get('remainingEvidence',[]),
                         'structuredProofPlanIds':row.get('structuredProofPlanIds',[]),'rowCompletenessClaimMade':False})
    counts=collections.Counter(p for r in rows for m in r['methodResults'] for p in m['problems'])
    return {'kind':'PROPOSED_HUMAN_PLANNED_METHOD_BINDINGS_NOT_REVIEW_OR_CLOSURE',**identity,
            **({'derivationSourceIdentity':expected['derivationSourceIdentity'],'additionalExecutionInputs':expected['additionalExecutionInputs'],'derivationSourceValidation':derivation_audit} if expected.get('derivationSourceIdentity') is not None else {}),
            'inputs':{'expectedSource':ref(expected_path),'catalog':ref(catalog_path),'sourcePlans':[ref(p) for p,_ in plans],'blockerReview':ref(blockers_path)},
            'status':'PROPOSAL_ONLY','rows':rows,'catalogSourceRefsChecked':sum(n.get('source') is not None for n in nodes),
            'summary':{'rows':len(rows),'selectors':sum(len(r['methodResults']) for r in rows),'proposedSelections':sum(len(r['proposedProofSelections']) for r in rows),
                       'uniqueProposedNodeIds':len({s['nodeId'] for r in rows for s in r['proposedProofSelections']}),'unboundSelectors':sum(bool(m['problems']) for r in rows for m in r['methodResults']),
                       'explicitlyBlockedRows':sum(bool(r['explicitReviewBlock']) for r in rows),'rowsWithoutMethodSelectors':sum(not r['methodResults'] for r in rows),'problems':dict(counts)},
            'evidenceBoundary':'Only explicit source-plan methods are joined. Structural proofs remain separate. Raw JUnit siblings in each registered matched report are checked; source plans do not prescribe or invent parameter counts. Root must review the completeness of the registered parent execution and individual scope before any merge.',
            'automaticMergePerformed':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'productTestsExecuted':False}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--expected-source',required=True);p.add_argument('--catalog',required=True)
    p.add_argument('--plan',action='append',required=True);p.add_argument('--blockers',required=True);p.add_argument('--out',required=True)
    args=p.parse_args();out=Path(args.out).resolve()
    require(out.is_relative_to(Path('/tmp').resolve()),'OUTPUT_MUST_BE_UNDER_TMP')
    require(not out.exists(),'OUTPUT_ALREADY_EXISTS')
    out.mkdir(parents=True)
    try:
        report=execute(args.expected_source,args.catalog,args.plan,args.blockers)
        (out/'PROPOSED-METHOD-BINDINGS.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(report['summary']));return 0
    except (Rejected,KeyError,ValueError,ET.ParseError) as e:
        (out/'REJECTED.json').write_text(json.dumps({'status':'REJECTED_NO_PROPOSALS','reason':str(e),'engineeringClosureClaimMade':False},indent=2)+'\n')
        print(str(e),file=sys.stderr);return 2
if __name__=='__main__':sys.exit(main())
