#!/usr/bin/env python3
"""Propose exact human-planned method/node bindings; never merge or claim closure."""
import argparse
import collections
import hashlib
import json
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

def read_xml(path):
    root = ET.parse(path).getroot()
    return [{'class': n.get('classname',''), 'name': n.get('name'), 'nodeOrdinal': i,
             'observedResult': next((t.upper() for t in ('failure','error','skipped') if n.find(t) is not None), 'PASSED')}
            for i,n in enumerate(root.iter('testcase'))]

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
    layers={}; layer_errors={}; layer_artifacts={}
    for layer in catalog['layers']:
        lid=layer['id']; require(lid not in layers,'AMBIGUOUS_PARENT_LAYER: '+lid); layers[lid]=layer
        errors=[]; registered=set()
        if not layer.get('receipt'): errors.append('NO_COMPLETED_PARENT_RECEIPT')
        else:
            rp=checked_file(layer['receipt'],catalog_path.parent); receipt=load(rp)
            registered.add((str(rp),layer['receipt']['sha256']))
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
                    if parent and n.get('runId')!=parent.get('runId'): problems.append('NODE_PARENT_RUN_MISMATCH')
                    ep=Path(n['evidence']['path']); ep=(ep if ep.is_absolute() else catalog_path.parent/ep).resolve()
                    ek=(str(ep),n['evidence']['sha256'])
                    if ek not in layer_artifacts.get(sel['layer'],set()): problems.append('NODE_EVIDENCE_NOT_REGISTERED_ON_PARENT')
                    if ek not in verified_files: verified_files[ek]=checked_file(n['evidence'],catalog_path.parent)
                    if n.get('kind')=='junit':
                        if ek not in xml_cache: xml_cache[ek]=read_xml(ep)
                        raw=xml_cache[ek]; ordinal=n.get('nodeOrdinal')
                        if type(ordinal) is not int or not 0<=ordinal<len(raw) or any(raw[ordinal][k]!=n.get(k) for k in ('class','name','observedResult')): problems.append('CATALOG_NODE_DIFFERS_FROM_RAW_JUNIT')
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
                        if ek not in xml_cache: xml_cache[ek]=read_xml(Path(epstr))
                        raw_seen.extend((ek,r) for r in xml_cache[ek] if r['class'] in classes and method_matches(sel,dict(r,kind='junit')))
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
