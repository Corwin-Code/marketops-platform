#!/usr/bin/env python3
"""Read-only 342-row evidence consistency audit. Never selects proofs or admits closure."""
import argparse
import collections
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path('/Users/chzhengx/Code/personal/marketops-platform')
HEAD = 'e278b1e3d8541aeb806e41d6cbef4deac8d16d06'
TREE = '178132dd32a92e59e320eb100774b5bb9f6fb248'
INVENTORY_SHA = '73cc1dc47aebd86cf93de3afbdfe4d905b6c5b4f8b1335488f28ca63fe963eda'
AUTHORITY = {
 'contract': ('docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md', '1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c'),
 'frozen': ('docs/07-phase-evidence/SLICE-V1-003/SLICE-V1-003-FROZEN-FINDING-SET-001.json', 'f4af74f5086772dc70c3ec3cc7aa8808e9441e96109d301b145e70c18f6131a0'),
 'frozenMarkdown': ('docs/07-phase-evidence/SLICE-V1-003/SLICE-V1-003-FROZEN-FINDING-SET-001.md', '15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1')}
LAYERS = {'backend_full','frontend_quality','browser','governance','infrastructure','migration','security','supply_chain','mixed_capacity'}
SECTIONS = ('criteria','findings','clauses','verificationChecks')
ROLES = {'positive','adverse','supporting'}

class Rejected(ValueError): pass

def require(ok, code):
    if not ok: raise Rejected(code)
def digest(raw): return hashlib.sha256(raw).hexdigest()
def sha(path): return digest(Path(path).read_bytes())
def reference(path):
    p=Path(path).resolve();return {'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size}
def load(path): return json.loads(Path(path).read_bytes())
def checked(ref, base):
    require(isinstance(ref,dict) and isinstance(ref.get('path'),str) and re.fullmatch('[0-9a-f]{64}',ref.get('sha256','')), 'INVALID_REFERENCE')
    p=Path(ref['path']);p=(p if p.is_absolute() else base/p).resolve()
    require(p.is_file() and sha(p)==ref['sha256'],'REFERENCE_MISSING_OR_SHA_CHANGED')
    if 'bytes' in ref: require(type(ref['bytes']) is int and ref['bytes']==p.stat().st_size,'REFERENCE_SIZE_CHANGED')
    return p

def pointer(value, path):
    require(isinstance(path,str) and (path=='' or path.startswith('/')) and not re.search(r'~(?:[^01]|$)',path),'INVALID_JSON_POINTER')
    for token in path.split('/')[1:] if path else []:
        token=token.replace('~1','/').replace('~0','~')
        if isinstance(value,list):
            require(bool(re.fullmatch('0|[1-9][0-9]*',token)) and int(token)<len(value),'INVALID_ARRAY_POINTER')
            value=value[int(token)]
        else:
            require(isinstance(value,dict) and token in value,'MISSING_JSON_POINTER');value=value[token]
    return value

def same_json(a,b):
    return type(a) is type(b) and (all(k in b and same_json(v,b[k]) for k,v in a.items()) and len(a)==len(b) if isinstance(a,dict) else len(a)==len(b) and all(same_json(x,y) for x,y in zip(a,b)) if isinstance(a,list) else a==b)

def indexed(rows, expected, code):
    require(isinstance(rows,list) and len(rows)==len(expected),code+'_COUNT')
    ids=[r.get('id') for r in rows];require(len(set(ids))==len(ids) and set(ids)==set(expected),code+'_IDS')
    return {r['id']:r for r in rows}

def all_rows(document):
    if 'rows' in document:
        require(isinstance(document['rows'],list),'INVALID_ROW_LIST')
        result={}
        for r in document['rows']:
            k=(r.get('rowKind'),r.get('id'));require(k[0] in SECTIONS and k not in result,'DUPLICATE_OR_INVALID_ROW');result[k]=r
        return result
    result={}
    for kind in SECTIONS:
        for r in document.get(kind,[]):
            k=(kind,r['id']);require(k not in result,'DUPLICATE_OR_INVALID_ROW');result[k]=r
    return result

def selection_key(s): return (s.get('nodeId'),s.get('role'),s.get('scope'))
def match_method(selector,node):
    if node.get('sourcePath')!=selector['sourcePath'] or node.get('layer')!=selector['layer']:return False
    n=node.get('name') or '';m=selector['method']
    return n==m or selector['sourcePath'].endswith('.java') and node.get('kind')=='junit' and (n.startswith(m+'(') or n.startswith(m+'['))

def require_full_expansion(selected, proposed):
    actual={selection_key(s) for s in selected};wanted={selection_key(s) for s in proposed}
    require(wanted and wanted<=actual,'SELECTED_METHOD_PARAMETER_EXPANSION_OMITTED_OR_SCOPE_CHANGED')

def check_row_reason(row):
    require(isinstance(row.get('engineeringReason'),str) and row['engineeringReason'].strip(),'ROW_REASON_MISSING')
    require(isinstance(row.get('reviewedBy'),str) and row['reviewedBy'].strip(),'ROW_REVIEWER_MISSING')
    require(isinstance(row.get('proofLimits'),str) and row['proofLimits'].strip(),'ROW_PROOF_LIMITS_MISSING')

def check_clause(row, expected, assembled):
    for k,v in expected.items():require(row.get(k)==v,'FROZEN_CLAUSE_METADATA_OR_TEXT_CHANGED')
    require(assembled.get('frozenExact')==expected['frozenExact'] and assembled.get('findingId')==expected['findingId'],'ASSEMBLED_FROZEN_CLAUSE_CHANGED')
    require(assembled.get('proofLimits')==row['proofLimits'],'ASSEMBLED_CLAUSE_LIMITS_CHANGED')

def build_authorities(contract, frozen):
    ac=dict(re.findall(r'^- `(S3-AC-\d{3})` — (.+)$',contract,re.M))
    require(set(ac)=={f'S3-AC-{i:03}' for i in range(1,201)},'AC_AUTHORITY_SET')
    findings=indexed(frozen['findings'],{f'S3-DR-{i:03}' for i in range(1,23)},'FROZEN_FINDINGS')
    clauses={}
    for ordinal,f in enumerate(frozen['findings']):
        for field,label in [('required_rework','REQUIRED_REWORK'),('verification','VERIFICATION')]:
            for i,text in enumerate(f[field],1):
                clauses[f'{f["id"]}:{label}:{i}']={'frozenExact':text,'findingId':f['id'],'clauseKind':label,'ordinal':i,'frozenJsonPointer':f'/findings/{ordinal}/{field}/{i-1}'}
    require(len(clauses)==115,'FROZEN_CLAUSE_COUNT')
    return {'criteria':ac,'findings':findings,'clauses':clauses,'verificationChecks':{f'CV-{c}':None for c in 'ABCDE'}}

def git(*args):
    env={'PATH':os.defpath,'GIT_CONFIG_GLOBAL':os.devnull,'GIT_CONFIG_NOSYSTEM':'1','GIT_NO_REPLACE_OBJECTS':'1'}
    return subprocess.check_output(['git','--no-pager','-c','core.hooksPath=/dev/null','-c','core.fsmonitor=false',*args],cwd=ROOT,env=env)

class Audit:
    def __init__(self, input_path):
        self.input_path=input_path;self.config=load(input_path);self.files={};self.docs={};self.issues=[];self.row_reports=[];self.cache={};self.raw_checks={};self.unselected=[]
    def issue(self,code, row=None):self.issues.append({'code':code,**({'rowKind':row[0],'id':row[1]} if row else {})})
    def attempt(self, fn, row=None):
        try:return fn()
        except (Rejected,KeyError,TypeError,ValueError,OSError,ET.ParseError) as e:
            self.issue(str(e) if isinstance(e,Rejected) else type(e).__name__,row);return None
    def document(self,name):
        p=checked(self.config[name],self.input_path.parent);self.files[name]=reference(p);self.docs[name]=load(p);return self.docs[name]
    def product_identity(self,doc):
        require(doc.get('sourceHead')==HEAD and doc.get('sourceTree')==TREE,'PRODUCT_IDENTITY_MISMATCH')
        value=doc.get('sourceInventorySha256') or doc.get('sourceInventory',{}).get('sha256') or doc.get('inventory',{}).get('sha256')
        require(value==INVENTORY_SHA,'PRODUCT_INVENTORY_MISMATCH')
    def read_json(self,p):
        if str(p) not in self.cache:self.cache[str(p)]=load(p)
        return self.cache[str(p)]
    def raw_xml(self,p):
        k=('xml',str(p))
        if k not in self.cache:
            root=ET.parse(p).getroot();self.cache[k]=(root,[n for n in root.iter('testcase')])
        return self.cache[k]
    def raw_key(self,ref,base):
        p=checked(ref,base);return str(p),ref['sha256']
    def validate_source(self,node):
        source=node['source'];path=source['path'];require(path==node['sourcePath'],'NODE_SOURCE_PATH_MISMATCH')
        require(path in self.sources and source['sha256']==self.sources[path]['sha256'],'NODE_SOURCE_NOT_PINNED')
        require(not Path(path).is_absolute() and '..' not in Path(path).parts and sha(ROOT/path)==source['sha256'],'SOURCE_BYTES_CHANGED')
        if self.derivation is None:
            layer=node['layer'];require(node.get('runId')==self.layers[layer]['runId'],'SAME_COMMIT_PARENT_RUN_MISMATCH')
            product={'sourceHead':HEAD,'sourceTree':TREE,'sourceInventorySha256':INVENTORY_SHA}
            for key in ('productSourceIdentity','executionSourceIdentity'):
                if key in node:require(node[key]==product,'SAME_COMMIT_NODE_IDENTITY_RELABELED')
            if 'actualExecutionReceipt' in node:
                require(self.raw_key(node['actualExecutionReceipt'],self.catalog_base)==self.raw_key(self.product_parent_refs[layer],self.catalog_base),'SAME_COMMIT_PARENT_CHANGED')
            require(self.raw_key(node['evidence'],self.catalog_base) in self.registered[layer],'SAME_COMMIT_RAW_NOT_IN_ORIGINAL_PARENT')
            if path in self.extras:
                pin=self.extras[path];ep=checked(pin['executionReceipt'],self.expected_base);e=self.read_json(ep)
                require((e.get('sourceHead'),e.get('sourceTree'),e.get('sourceInventorySha256'))==(HEAD,TREE,INVENTORY_SHA),'SAME_COMMIT_HELPER_EXECUTION_IDENTITY_MISMATCH')
                require(e.get('result')=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and e.get('sourceStable') is True and type(e.get('exitCode')) is int and e['exitCode']==0,'SAME_COMMIT_HELPER_NOT_EXECUTED')
                raw=checked(node['evidence'],self.catalog_base)
                # The full governance collector copies these original named results byte-for-byte.
                require(any(sha(checked(x,ep.parent))==sha(raw) for x in e.get('evidence',[])),'SAME_COMMIT_HELPER_RAW_NOT_FROM_ACTUAL_EXECUTION')
                if e.get('kind')=='INDEPENDENT_ADDITIONAL_INPUT_SYNTHETIC_EXECUTION':
                    matches=[x for x in e['repositoryGitBindings'] if x['repositoryRelativePath']==path]
                    require(len(matches)==1 and matches[0]['sourceHead']==HEAD and matches[0]['gitBlobSha256']==pin['sha256'],'SAME_COMMIT_HELPER_SOURCE_NOT_BOUND')
                    binding=matches[0]
                    for key in ('inputsBefore','inputsAfter'):
                        require(any(x['path']==binding['copiedInput']['path'] and x['sha256']==pin['sha256'] for x in e[key]),'SAME_COMMIT_HELPER_SOURCE_CHANGED')
            return
        extra=path in self.extras
        if extra:
            pin=self.extras[path];rref=node.get('actualExecutionReceipt');require(rref==node.get('additionalExecutionReceipt'),'D_RECEIPT_POINTERS_DIFFER')
            rp=checked(rref,self.catalog_base);r=self.read_json(rp)
            require(self.raw_key(rref,self.catalog_base) in self.composite_receipts.get(node['layer'],set()),'D_RECEIPT_NOT_IN_EXPLICIT_GOVERNANCE_COMPOSITE')
            require(self.raw_key(rref,self.catalog_base)==self.raw_key(pin['executionReceipt'],self.expected_base),'D_RAW_USES_DIFFERENT_HELPER_RECEIPT')
            require(node.get('executionSourceIdentity')==self.derivation,'D_NODE_EXECUTION_IDENTITY_MISMATCH')
            require(node.get('productSourceIdentity')=={'sourceHead':HEAD,'sourceTree':TREE,'sourceInventorySha256':INVENTORY_SHA},'D_NODE_PRODUCT_IDENTITY_MISMATCH')
            require(r.get('sourceHead')==self.derivation['sourceHead'] and r.get('sourceTree')==self.derivation['sourceTree'],'D_RECEIPT_HEAD_MISMATCH')
            require(r.get('scope')=='EVIDENCE_DERIVATION_ONLY' and r.get('kind')=='EVIDENCE_DERIVATION_EXECUTION_RECEIPT','D_RECEIPT_SCOPE_MISMATCH')
            require(r.get('appliesToProductSourceHead')==HEAD and r.get('appliesToProductSourceTree')==TREE and r.get('sourceInventorySha256')==INVENTORY_SHA,'D_RECEIPT_PRODUCT_IDENTITY_MISMATCH')
            require(r.get('result')=='COMMAND_SUCCEEDED_REVIEW_REQUIRED' and r.get('sourceStable') is True and type(r.get('exitCode')) is int and r['exitCode']==0,'D_RECEIPT_NOT_SUCCESSFUL')
            require(r.get('runId')==node.get('runId'),'D_NODE_RUN_MISMATCH')
            pins={p['path']:p for p in r['executedInputs']};require(path in pins,'D_SOURCE_NOT_ACTUALLY_EXECUTED')
            require(all(pins[path].get(k)==pin.get(k) for k in ('sha256','sourceHead','sourceTree','gitBlobOid')),'D_EXECUTED_SOURCE_PIN_MISMATCH')
            allowed={self.raw_key(e,rp.parent) for e in r['evidence']}
            require(self.raw_key(node['evidence'],self.catalog_base) in allowed,'D_NODE_RAW_NOT_IN_ACTUAL_D_RECEIPT')
        else:
            layer=self.layers[node['layer']];require(node.get('runId')==layer.get('runId'),'PRODUCT_NODE_PARENT_RUN_MISMATCH')
            product={'sourceHead':HEAD,'sourceTree':TREE,'sourceInventorySha256':INVENTORY_SHA}
            require(node.get('productSourceIdentity')==product and node.get('executionSourceIdentity')==product,'PRODUCT_NODE_RELABELLED_AS_D')
            require(self.raw_key(node['actualExecutionReceipt'],self.catalog_base)==self.raw_key(self.product_parent_refs[node['layer']],self.catalog_base),'PRODUCT_NODE_ACTUAL_PARENT_CHANGED')
            if self.composite_receipts.get(node['layer']):
                require(self.raw_key(node['evidence'],self.catalog_base) in self.product_raw_members[node['layer']],'PRODUCT_NODE_RAW_NOT_IN_ORIGINAL_PARENT')
    def validate_node(self,node):
        self.product_identity(node);require(node.get('observedResult')=='PASSED' and node.get('terminalSuccessfulCommandObserved') is True and node.get('admissibleAfterIndependentScopeReview') is True and not node.get('ambiguity'),'SELECTED_NODE_NOT_UNAMBIGUOUS_PASSED')
        require(node['layer'] in self.layers,'NODE_LAYER_ABSENT');self.validate_source(node)
        path=checked(node['evidence'],self.catalog_base);rawkey=(str(path),node['evidence']['sha256'])
        require(rawkey in self.registered[node['layer']] or node['sourcePath'] in self.extras,'RAW_NOT_IN_LAYER_REGISTRATION')
        if node['kind']=='junit':
            _,nodes=self.raw_xml(path);i=node.get('nodeOrdinal');require(type(i) is int and 0<=i<len(nodes),'JUNIT_ORDINAL_INVALID');n=nodes[i]
            require(n.get('classname','')==node.get('class') and n.get('name')==node.get('name'),'JUNIT_IDENTITY_CHANGED')
            require(not any(n.find(k) is not None for k in ('failure','error','skipped')),'RAW_JUNIT_FAILED_OR_SKIPPED')
            require(sum(x.get('classname','')==node.get('class') and x.get('name')==node.get('name') for x in nodes)==1,'DUPLICATE_RAW_JUNIT_NAME')
        else:
            require(node['kind']=='json' and node.get('assertions'),'UNSUPPORTED_OR_EMPTY_RAW_ASSERTIONS');raw=self.read_json(path)
            require(raw.get('kind') not in ('SLICE3_FINAL_GATE_EXECUTION_MANIFEST','ACTUAL_EXECUTION_NODE_OBSERVATIONS_NOT_CLOSURE') if isinstance(raw,dict) else True,'GENERATED_ASSEMBLY_SELF_PROOF')
            for a in node['assertions']:require(same_json(pointer(raw,a['pointer']),a['expected']),'RAW_TYPED_ASSERTION_CHANGED')
            records=[r for r in self.json_records[node['layer']] if r['path']==str(path) and r['sha256']==node['evidence']['sha256'] and r['sourcePath']==node['sourcePath'] and r['name']==node['name'] and same_json(r['assertions'],node['assertions'])]
            structured=[r for r in self.adapters[node['layer']] if r['name']==node['name'] and r['sourcePath']==node['sourcePath'] and same_json(r['assertions'],node['assertions']) and self.raw_key(r['evidence'],self.receipt_bases[node['layer']])==rawkey]
            require(len(records)==1 or len(structured)==1 or node['layer']=='security' and node['name'].startswith('security.'),'RAW_JSON_NAMED_OR_STRUCTURED_IDENTITY_UNREGISTERED')
        self.raw_checks[node['nodeId']]={'raw':reference(path),'source':node['source'],'scopeIdentity':('D_TOOL_EXECUTION' if self.derivation is not None else 'CURRENT_COMMIT_EVIDENCE_TOOL_EXECUTION') if node['sourcePath'] in self.extras else 'PRODUCT_E278B1E_EXECUTION','runId':node['runId']}
    def raw_method_identities(self, selector):
        """Recount every registered original expansion; do not trust catalog cardinality."""
        layer=selector['layer'];source=selector['sourcePath'];method=selector['method'];found=set()
        if source.endswith('.java') or layer=='browser':
            for path,d in self.registered[layer]:
                if not path.endswith('.xml'):continue
                root,raw=self.raw_xml(Path(path));root_name=root.get('name') if root.tag=='testsuite' else None
                for ordinal,n in enumerate(raw):
                    name=n.get('name') or ''
                    if not (name==method or source.endswith('.java') and (name.startswith(method+'(') or name.startswith(method+'['))):continue
                    direct=n.get('classname','').split('$')[0];root_class=(root_name or '').split('$')[0]
                    direct_candidates=self.java_sources.get(direct,[]);root_candidates=self.java_sources.get(root_class,[])
                    if layer=='browser':bound=self.junit_sources.get(layer,{}).get(n.get('classname',''))
                    elif len(direct_candidates)==1 and (not root_name or root_candidates==direct_candidates):bound=direct_candidates[0]
                    elif len(root_candidates)==1 and not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+',n.get('classname','')):bound=root_candidates[0]
                    else:bound=None
                    if bound!=source:continue
                    require(not any(n.find(k) is not None for k in ('failure','error','skipped')),'RAW_METHOD_HAS_FAILED_OR_SKIPPED_EXPANSION')
                    found.add((path,d,n.get('classname',''),name,ordinal))
        else:
            for r in self.json_records[layer]:
                if r['sourcePath']==source and r['name']==method:
                    require(r['passed'],'RAW_JSON_METHOD_HAS_FAILED_OR_SKIPPED_EXPANSION')
                    found.add((r['path'],r['sha256'],json.dumps(r['assertions'],sort_keys=True)))
        return found
    def node_raw_identity(self,node):
        path,d=self.raw_key(node['evidence'],self.catalog_base)
        if node['kind']=='junit':return (path,d,node.get('class',''),node['name'],node['nodeOrdinal'])
        return (path,d,json.dumps(node['assertions'],sort_keys=True))
    def published(self,ref):
        p=checked(ref,self.catalog_base);dest=self.layer_config.get('artifactDestinations',{}).get(str(p))
        require(isinstance(dest,str) and dest and not Path(dest).is_absolute() and '..' not in Path(dest).parts,'EXPLICIT_PUBLICATION_DESTINATION_MISSING')
        return {'path':dest,'sha256':ref['sha256']}
    def expected_proof(self,node,selection):
        proof={k:node[k] for k in ('kind','layer','source')};proof.update(scope=selection['scope'],role=selection['role'],evidence=self.published(node['evidence']))
        if node['kind']=='junit':proof.update({k:node[k] for k in ('class','name')})
        else:proof['assertions']=node['assertions']
        for k in ('runId','productSourceIdentity','executionSourceIdentity','sourceAuthorityScope'):
            if k in node:proof[k]=node[k]
        for k in ('actualExecutionReceipt','additionalExecutionReceipt'):
            if k in node:proof[k]=self.published(node[k])
        return proof
    def setup(self):
        names=['expectedSource','slots','nodeCatalog','sourcePlan','methodProposals','layerConfig','rootSelections','manifestCandidate','clausesCandidate']
        for name in names:self.document(name)
        self.expected=self.docs['expectedSource'];self.expected_base=Path(self.files['expectedSource']['path']).parent
        self.catalog=self.docs['nodeCatalog'];self.catalog_base=Path(self.files['nodeCatalog']['path']).parent
        for name in ['expectedSource','slots','nodeCatalog','sourcePlan','methodProposals','rootSelections']:self.product_identity(self.docs[name])
        inventory_path=checked(self.expected['inventory'],self.expected_base);require(sha(inventory_path)==INVENTORY_SHA,'INVENTORY_SHA_MISMATCH')
        rows=load(inventory_path)['files'];require(len(rows)==1280 and len({r['path'] for r in rows})==1280,'INVENTORY_COUNT_OR_DUPLICATE')
        self.sources={r['path']:r for r in rows};self.extras={r['path']:r for r in self.expected.get('additionalExecutionInputs',[])}
        require(not set(self.extras)&set(self.sources),'D_AND_PRODUCT_SOURCE_OVERLAP');self.sources.update(self.extras)
        for path,row in self.sources.items():require(sha(ROOT/path)==row['sha256'],'CURRENT_SOURCE_BYTES_CHANGED')
        self.derivation=self.expected.get('derivationSourceIdentity')
        if self.derivation is None:
            require(git('rev-parse','HEAD').decode().strip()==HEAD and not git('status','--porcelain=v1','--untracked-files=all'),'CURRENT_SOURCE_NOT_CLEAN')
            require(git('rev-parse',HEAD+'^{tree}').decode().strip()==TREE,'CURRENT_SOURCE_TREE_INVALID')
            names=sorted(self.sources);oids=[git('rev-parse',HEAD+':'+path).decode().strip() for path in names]
            # Each current source, including documentation helpers, must match its committed blob.
            raw=subprocess.check_output(['git','--no-replace-objects','cat-file','--batch'],input=('\n'.join(oids)+'\n').encode(),cwd=ROOT);pos=0
            for path,oid in zip(names,oids):
                end=raw.index(b'\n',pos);actual,kind,size=raw[pos:end].decode().split();b=raw[end+1:end+1+int(size)];pos=end+2+int(size)
                require(actual==oid and kind=='blob' and raw[pos-1:pos]==b'\n','CURRENT_SOURCE_GIT_BLOB_INVALID')
                current=(ROOT/path).read_bytes()
                if b!=current:
                    require(path in {'backend/marketops-server/mvnw.cmd','scripts/bootstrap-repo.ps1'} and current.replace(b'\r\n',b'\n')==b.replace(b'\r\n',b'\n'),'CURRENT_SOURCE_GIT_BYTES_DIFFER')
                    require(b'eol\x00crlf\x00' in git('check-attr','--cached','-z','eol','--',path),'UNDECLARED_LINE_ENDING_EQUIVALENCE')
                if path in self.extras:
                    pin=self.extras[path];require(pin.get('sourceHead')==HEAD and pin.get('sourceTree')==TREE and pin.get('gitBlobOid')==oid and pin.get('scope')=='EVIDENCE_DERIVATION_ONLY','SAME_COMMIT_HELPER_GIT_IDENTITY_INVALID');checked(pin['executionReceipt'],self.expected_base)
            require(pos==len(raw),'CURRENT_SOURCE_GIT_BATCH_NOT_EXHAUSTED')
            require(self.catalog.get('derivationSourceIdentity') is None and self.docs['methodProposals'].get('derivationSourceIdentity') is None,'UNREQUESTED_D_IDENTITY')
        else:
            require(isinstance(self.derivation,dict),'ACTUAL_D_IDENTITY_NOT_REGISTERED')
            d=self.derivation;require(d['sourceHead']!=HEAD and d['appliesToProductSourceHead']==HEAD and d['appliesToProductSourceTree']==TREE and d['sourceInventorySha256']==INVENTORY_SHA,'D_IDENTITY_INVALID')
            require(git('rev-parse','HEAD').decode().strip()==d['sourceHead'] and not git('status','--porcelain=v1','--untracked-files=all'),'CURRENT_D_NOT_CLEAN')
            require(git('rev-parse',d['sourceHead']+'^{tree}').decode().strip()==d['sourceTree'],'D_TREE_INVALID');git('merge-base','--is-ancestor',HEAD,d['sourceHead'])
            for path,row in self.extras.items():
                require(row.get('sourceHead')==d['sourceHead'] and row.get('sourceTree')==d['sourceTree'] and row.get('scope')=='EVIDENCE_DERIVATION_ONLY','ADDITIONAL_SOURCE_WRONG_D_IDENTITY')
                require(git('rev-parse',d['sourceHead']+':'+path).decode().strip()==row.get('gitBlobOid') and digest(git('show',d['sourceHead']+':'+path))==row['sha256'],'ADDITIONAL_SOURCE_NOT_ACTUAL_D_GIT_BYTES')
            require(self.catalog.get('derivationSourceIdentity')==d and self.docs['methodProposals'].get('derivationSourceIdentity')==d,'CATALOG_OR_PROPOSAL_D_IDENTITY_MISMATCH')
            v=self.catalog.get('derivationSourceValidation',{})
            require(v.get('runtimeFileCount')==1280 and v.get('allRuntimeGitEntriesIdentical') is True and v.get('originalRuntimeGitEntriesSha256')==v.get('derivationRuntimeGitEntriesSha256') and v.get('derivationSourceHead')==d['sourceHead'],'FULL_RUNTIME_GIT_EQUIVALENCE_NOT_RECORDED')
        for path,expected in AUTHORITY.values():require(sha(ROOT/path)==expected,'IMMUTABLE_AUTHORITY_CHANGED')
        self.authorities=build_authorities((ROOT/AUTHORITY['contract'][0]).read_text(),load(ROOT/AUTHORITY['frozen'][0]))
        self.row_index={}
        for section in SECTIONS:self.row_index[section]=indexed(self.docs['slots'][section],self.authorities[section],'SLOTS_'+section)
        self.all_expected={(s,i) for s,v in self.authorities.items() for i in v}
        self.plans=all_rows(self.docs['sourcePlan']);self.proposals=all_rows(self.docs['methodProposals']);self.root_rows=all_rows(self.docs['rootSelections'])
        for rows in (self.plans,self.proposals,self.root_rows):require(set(rows)==self.all_expected,'ROW_SET_NOT_EXACT_342')
        self.assembled={}
        m=self.docs['manifestCandidate'];c=self.docs['clausesCandidate']
        require(m.get('status')=='PENDING' and c.get('status')=='PENDING','AUDIT_EXPECTS_PENDING_INPUTS')
        require(m.get('engineeringClosureClaimMade') is False and c.get('engineeringClosureClaimMade') is False and m.get('productionWriteEnabled') is False and m.get('controllerApprovalClaimMade') is False,'ASSEMBLY_PREMATURE_CLOSURE_OR_WRITE')
        for s in ('criteria','findings','verificationChecks'):self.assembled[s]=indexed(m[s],self.authorities[s],'MANIFEST_'+s)
        self.assembled['clauses']=indexed(c['entries'],self.authorities['clauses'],'CLAUSE_CANDIDATE');require(c['count']==115,'CLAUSE_DECLARED_COUNT')
        self.java_sources=collections.defaultdict(list)
        for source in self.sources:
            if '/src/test/java/' in source and source.endswith('.java'):self.java_sources[source.split('/src/test/java/')[-1][:-5].replace('/','.')].append(source)
        self.layers=indexed(self.catalog['layers'],LAYERS,'CATALOG_LAYERS');self.layer_config=self.docs['layerConfig'];require(self.layer_config['sourceHead']==HEAD and self.layer_config['sourceTree']==TREE,'CONFIG_SOURCE_MISMATCH')
        specs=indexed(self.layer_config['layers'],LAYERS,'CONFIG_LAYERS');self.registered={};self.adapters={};self.json_records={};self.receipt_bases={};self.composite_receipts={};self.product_parent_refs={};self.junit_sources={};self.product_raw_members={}
        for name,layer in self.layers.items():
            require(layer.get('exactSourceIdentityMatches') is True and layer.get('terminalSuccessfulCommandObserved') is True,'LAYER_NOT_TERMINAL_SOURCE_BOUND')
            spec=specs[name];self.junit_sources[name]=spec.get('junitSourceByClass',{})
            for classname,path in self.junit_sources[name].items():require(path in self.sources and (name!='browser' or classname.endswith('.spec.ts') and path.endswith('/'+classname)),'BROWSER_JUNIT_SOURCE_NOT_EXACT_INVENTORY_SUFFIX')
            rp=Path(spec['receipt']);rp=rp if rp.is_absolute() else Path(self.files['layerConfig']['path']).parent/rp;rp=rp.resolve();require(self.raw_key(layer['receipt'],self.catalog_base)==(str(rp),sha(rp)),'LAYER_RECEIPT_CONFIG_MISMATCH');r=self.read_json(rp)
            tools=[];self.composite_receipts[name]=set()
            if r.get('kind')=='EXPLICIT_COMPOSITE_PRODUCT_AND_DERIVATION_SCOPES':
                require(name=='governance','COMPOSITE_OUTSIDE_GOVERNANCE')
                wrapper=r;wp=rp
                require(self.raw_key(layer['parent'],self.catalog_base)==self.raw_key(wrapper['parent'],wp.parent),'COMPOSITE_PARENT_CHANGED')
                listed=[self.raw_key(x,self.catalog_base) for x in layer['toolExecutions']];actual=[self.raw_key(x,wp.parent) for x in wrapper['toolExecutions']]
                require(len(listed)==len(set(listed))==len(actual)==len(set(actual)) and set(listed)==set(actual),'COMPOSITE_TOOL_RECEIPTS_CHANGED')
                for tr in wrapper['toolExecutions']:
                    tp=checked(tr,wp.parent);tools.append((tp,self.read_json(tp)));self.composite_receipts[name].add((str(tp),tr['sha256']))
                rp=checked(wrapper['parent'],wp.parent);r=self.read_json(rp)
            self.receipt_bases[name]=rp.parent;self.product_parent_refs[name]=reference(rp)
            originals=[reference(rp),*r.get('evidence',[])]
            for key in ('sourceInventory','executionSourceInventory'):
                if isinstance(r.get(key),dict) and r[key].get('sha256')==INVENTORY_SHA:originals.append(r[key])
            self.product_raw_members[name]={self.raw_key(ref,rp.parent) for ref in originals}
            refs=[reference(rp),*r.get('evidence',[])]
            for tp,tr in tools:refs.extend(reference(checked(e,tp.parent)) for e in tr['evidence'])
            for key in ('sourceInventory','executionSourceInventory'):
                if isinstance(r.get(key),dict):refs.append(r[key])
            refs += [pointer(r,p) for p in spec.get('registeredArtifactPointers',[])]
            self.registered[name]={self.raw_key(ref,rp.parent) for ref in refs}
            self.adapters[name]=spec.get('structuredRecordAdapters',[]);self.json_records[name]=[]
            for adapter in spec.get('jsonRecordAdapters',[]):
                ep=checked(adapter['evidence'],rp.parent);raw=self.read_json(ep);records=pointer(raw,adapter['recordsPointer']);require(isinstance(records,list),'JSON_ADAPTER_RECORDS_NOT_ARRAY')
                require((str(ep),adapter['evidence']['sha256']) in self.registered[name],'JSON_ADAPTER_RAW_NOT_REGISTERED')
                for ordinal,record in enumerate(records):
                    path=pointer(record,adapter['sourcePathPointer']) if adapter.get('sourcePathPointer') else adapter.get('sourcePath')
                    for prefix,replacement in self.layer_config.get('sourcePrefixToRepository',{}).items():
                        if path and path.startswith(prefix):path=replacement+path[len(prefix):]
                    status=pointer(record,adapter['statusPointer'])
                    self.json_records[name].append({'path':str(ep),'sha256':adapter['evidence']['sha256'],'sourcePath':path,'name':pointer(record,adapter['namePointer']),'passed':same_json(status,adapter['passedValue']),'assertions':[{'pointer':adapter['recordsPointer']+'/'+str(ordinal)+adapter['statusPointer'],'expected':status}]})
        self.nodes={n['nodeId']:n for n in self.catalog['nodes']};require(len(self.nodes)==len(self.catalog['nodes']),'DUPLICATE_CATALOG_NODE_ID')
        ip=self.docs['methodProposals']['inputs'];require(self.raw_key(ip['catalog'],Path(self.files['methodProposals']['path']).parent)==(self.files['nodeCatalog']['path'],self.files['nodeCatalog']['sha256']),'PROPOSALS_WRONG_CATALOG')
    def row(self,section,id):
        key=(section,id);s=self.row_index[section][id];root=self.root_rows[key];a=self.assembled[section][id]
        check_row_reason(s);require(root.get('proofSelections')==s.get('proofSelections'),'ROOT_SELECTIONS_NOT_EXACT_SLOTS')
        require(a.get('engineeringReason')==s['engineeringReason'],'ASSEMBLED_REASON_CHANGED')
        if section=='criteria':require(s['acceptedExact']==self.authorities[section][id],'ACCEPTED_CRITERION_TEXT_CHANGED')
        if section=='clauses':
            exact=self.authorities[section][id]
            check_clause(s,exact,a)
        if section=='verificationChecks':require(a.get('proofLimits')==s['proofLimits'] and a.get('result')=='PENDING','CV_LIMITS_OR_PENDING_CHANGED')
        sels=s.get('proofSelections');require(isinstance(sels,list) and sels,'ROW_HAS_NO_ACTUAL_SELECTION')
        require(len({selection_key(x) for x in sels})==len(sels),'DUPLICATE_SELECTION')
        require(all(x.get('role') in ROLES and isinstance(x.get('scope'),str) and x['scope'].strip() for x in sels),'SELECTION_ROLE_OR_SCOPE_MISSING')
        if section=='verificationChecks':require({'positive','adverse'}<={x['role'] for x in sels},'CV_POSITIVE_ADVERSE_MISSING')
        proposal=self.proposals[key];require(not proposal.get('explicitReviewBlock'),'ROW_HAS_UNRESOLVED_REVIEW_BLOCK')
        methods=proposal['methodResults'];used=set();method_checks=[]
        for method in methods:
            wanted=method.get('proposedProofSelections',[]);touch={x['nodeId'] for x in sels}&set(method.get('matchedCurrentNodeIds',[]))
            if not touch:
                self.unselected.append({'rowKind':section,'id':id,'selectorIndex':method['selectorIndex'],'method':method['selector'],'problems':method.get('problems',[]),'mandatoryContractProofInferred':False});continue
            require(not method.get('problems'),'SELECTED_METHOD_PROPOSAL_HAS_PROBLEMS')
            si=method['selectorIndex'];planned=self.plans[key]['selectedMethods'];require(type(si) is int and 0<=si<len(planned) and same_json(planned[si],method['selector']),'METHOD_SELECTOR_DIFFERS_FROM_REVIEWED_SOURCE_PLAN')
            provenance=method['sourcePlanProvenance'];require(self.raw_key(provenance,Path(self.files['methodProposals']['path']).parent)==(self.files['sourcePlan']['path'],self.files['sourcePlan']['sha256']),'METHOD_PROPOSAL_SOURCE_PLAN_REFERENCE_MISMATCH')
            require(same_json(pointer(self.docs['sourcePlan'],provenance['pointer']),method['selector']),'METHOD_PROPOSAL_SOURCE_PLAN_POINTER_MISMATCH')
            require_full_expansion(sels,wanted)
            actual={n['nodeId'] for n in self.catalog['nodes'] if match_method(method['selector'],n)}
            require(actual==set(method['matchedCurrentNodeIds'])=={x['nodeId'] for x in wanted},'METHOD_PROPOSAL_OMITS_CATALOG_EXPANSION')
            require(all(self.nodes[n]['observedResult']=='PASSED' for n in actual),'METHOD_EXPANSION_CONTAINS_FAILURE')
            raw=self.raw_method_identities(method['selector'])
            node_raw={self.node_raw_identity(self.nodes[n]) for n in actual}
            # Structured browser leaf adapters are exact source/title assertions, not JSON-array adapters.
            structured_leaf=method['selector']['layer']=='browser' and not raw and all(self.nodes[n]['kind']=='json' for n in actual)
            require(raw==node_raw or structured_leaf,'METHOD_CATALOG_AND_PROPOSAL_OMIT_OR_ADD_RAW_EXPANSION')
            used.update(actual);method_checks.append({'selector':method['selector'],'expandedNodeIds':sorted(actual),'sourcePlanProvenance':method.get('sourcePlanProvenance')})
        expected_proofs=[]
        for selection in sels:
            require(selection['nodeId'] in self.nodes,'SELECTED_NODE_MISSING');node=self.nodes[selection['nodeId']];self.validate_node(node)
            if node['nodeId'] not in used:
                require(node['kind']=='json','JUNIT_PROOF_WITHOUT_EXACT_METHOD_PROPOSAL')
                adapters=[r for r in self.adapters[node['layer']] if r['name']==node['name'] and r['sourcePath']==node['sourcePath']]
                implicit_security=node['layer']=='security' and node['name'].startswith('security.')
                require(implicit_security or len(adapters)==1,'STRUCTURED_PROOF_NOT_EXPLICITLY_REGISTERED')
                if adapters:
                    require(self.raw_key(adapters[0]['evidence'],self.receipt_bases[node['layer']])==self.raw_key(node['evidence'],self.catalog_base) and same_json(adapters[0]['assertions'],node['assertions']),'STRUCTURED_REGISTRATION_CHANGED')
            expected_proofs.append(self.expected_proof(node,selection))
        require(same_json(a.get('proofs'),expected_proofs),'ASSEMBLED_PROOFS_DIFFER_FROM_EXACT_ROOT_SELECTIONS')
        require(a.get('layers')==sorted({p['layer'] for p in expected_proofs}),'ASSEMBLED_ROW_LAYERS_CHANGED')
        self.row_reports.append({'rowKind':section,'id':id,'exactAuthority':self.authorities[section][id] if section in ('criteria','clauses') else s.get('requiredClosure') or s.get('title'),'engineeringReason':s['engineeringReason'],'proofLimits':s['proofLimits'],'reviewedBy':s['reviewedBy'],'selections':sels,'methods':method_checks,'rawProofNodes':[self.raw_checks[x['nodeId']] for x in sels],'semanticSufficiency':'REQUIRES_INDEPENDENT_ROW_SCOPE_REVIEW','mechanicalConsistency':'CHECKED'})
    def execute(self):
        if self.attempt(self.setup) is None and self.issues:return
        for section,rows in self.row_index.items():
            for id in rows:self.attempt(lambda section=section,id=id:self.row(section,id),(section,id))


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--inputs',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    out=a.out.resolve();require(out!=Path('/tmp').resolve() and out.is_relative_to(Path('/tmp').resolve()) and not out.exists(),'DEDICATED_FRESH_TMP_OUTPUT_REQUIRED');out.mkdir()
    auditor_before=reference(__file__);config_before=reference(a.inputs)
    audit=Audit(a.inputs.resolve());started=datetime.datetime.now(datetime.timezone.utc).isoformat();audit.execute()
    checked_inputs=[*audit.files.values(),auditor_before,config_before,*[r['raw'] for r in audit.raw_checks.values()]]
    stable=all(Path(r['path']).is_file() and sha(r['path'])==r['sha256'] for r in checked_inputs)
    if not stable:audit.issue('AUDIT_INPUT_BYTES_CHANGED_DURING_READ')
    counts=collections.Counter(r['rowKind'] for r in audit.row_reports)
    result={'kind':'READ_ONLY_PENDING_ASSEMBLY_ROW_AUDIT','status':'AUDIT_OBSERVATIONS','sourceHead':HEAD,'sourceTree':TREE,'sourceInventorySha256':INVENTORY_SHA,'startedAtUTC':started,'finishedAtUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'auditor':auditor_before,'inputConfig':config_before,'inputs':audit.files,'inputsStable':stable,'mechanicallyCheckedRowCounts':dict(counts),'mechanicalIssues':audit.issues,'rows':audit.row_reports,'unselectedPlannedMethodsForHumanScopeReview':audit.unselected,'automaticProofSelectionPerformed':False,'semanticCompletenessClaimMade':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'productTestsExecuted':False,'productionWriteEnabled':False,'boundary':'Mechanical consistency only. 115 exact Frozen clauses are independently bound here because the product finalizer does not consume that file. Individual engineering reason/scope sufficiency, actual full-parent completeness and publication remain independent reviews. Unselected source-plan candidates are reported, never made mandatory by this tool. No COMPLETE output or proof mutation exists.'}
    (out/'ROW-AUDIT.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'audit':reference(out/'ROW-AUDIT.json'),'mechanicalIssueCount':len(audit.issues),'checkedRows':sum(counts.values()),'closureClaimMade':False}))
    return 1 if audit.issues else 0

if __name__=='__main__':raise SystemExit(main())
