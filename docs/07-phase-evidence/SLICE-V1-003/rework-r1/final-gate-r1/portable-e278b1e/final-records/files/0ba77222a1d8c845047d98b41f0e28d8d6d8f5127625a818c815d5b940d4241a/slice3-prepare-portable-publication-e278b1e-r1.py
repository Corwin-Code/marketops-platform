#!/usr/bin/env python3
"""Prepare exact-file publication/relocation metadata only; never copy or publish evidence."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re

ROOT=Path('/Users/chzhengx/Code/personal/marketops-platform').resolve()
HEAD='e278b1e3d8541aeb806e41d6cbef4deac8d16d06'
TREE='178132dd32a92e59e320eb100774b5bb9f6fb248'
PREFIX='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/portable-e278b1e'
LAYERS={'backend_full','frontend_quality','browser','governance','infrastructure','migration','security','supply_chain','mixed_capacity'}
HARD=100*1024*1024;WARNING=50*1024*1024
BACKEND=Path('/tmp/slice3-backend-checkpoint-archive-02e6172-r5')
LOCAL=Path('/tmp/slice3-local-layer-archives-02e6172-r1')
BROWSER=Path('/tmp/slice3-browser-r4-archives-02e6172-r1')
CI=Path('/tmp/slice3-checkpoint-ci-02e6172-all')
SECURITY=Path('/tmp/slice3-checkpoint-ci-02e6172')

def need(ok,code):
    if not ok:raise ValueError(code)
def sha(p):
    h=hashlib.sha256()
    with Path(p).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()
def read(p):return json.loads(Path(p).read_bytes())
def ref(p):
    p=Path(p).resolve();return {'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size}
def key(p,d):return str(Path(p).resolve()),d
def checked(r,base):
    need(isinstance(r,dict) and isinstance(r.get('path'),str) and re.fullmatch('[0-9a-f]{64}',r.get('sha256','')),'INVALID_EXACT_REFERENCE')
    p=Path(r['path']);p=(p if p.is_absolute() else base/p).resolve();need(p.is_file() and sha(p)==r['sha256'],'REFERENCE_MISSING_OR_SHA_CHANGED: '+str(p));return p

def rows(doc):
    if 'rows' in doc:return doc['rows']
    return [r for kind in ('criteria','findings','clauses','verificationChecks') for r in doc.get(kind,[])]

class Plan:
    def __init__(self):self.entries={};self.direct={};self.archives=[];self.selected=[];self.input_refs=[];self.original_config=None;self.destinations={};self.missing=[];self.receipts_seen=set();self.derivation=None
    def entry(self,original,digest,size):
        k=key(original,digest);e=self.entries.setdefault(k,{'canonicalOriginalPath':k[0],'originalPaths':[],'sha256':digest,'bytes':size,'roles':[],'archiveLocations':[]})
        need(e['bytes']==size,'SAME_PATH_SHA_SIZE_CONFLICT')
        for alias in (str(original),k[0]):
            if alias not in e['originalPaths']:e['originalPaths'].append(alias)
        return e
    def add(self,path,role,expected=None):
        original=str(path);p=Path(path).resolve();need(p.is_file(),'REQUIRED_FILE_MISSING: '+str(p));d=sha(p)
        if expected is not None:need(d==expected,'REQUIRED_FILE_CHANGED: '+str(p))
        e=self.entry(original,d,p.stat().st_size)
        if role not in e['roles']:e['roles'].append(role)
        if p.is_relative_to(ROOT):dest=p.relative_to(ROOT).as_posix();copy=False
        else:
            safe=re.sub('[^A-Za-z0-9._-]+','_',p.name)
            dest=self.destinations.get(str(p),f'{PREFIX}/files/{d}/{safe}');copy=True
        q=Path(dest);need(not q.is_absolute() and '..' not in q.parts,'UNSAFE_REPOSITORY_DESTINATION')
        if copy:need(dest.startswith('docs/07-phase-evidence/SLICE-V1-003/rework-r1/'),'NEW_EVIDENCE_DESTINATION_OUTSIDE_REWORK')
        existing=ROOT/q
        need(not existing.exists() or existing.is_file() and sha(existing)==d,'DESTINATION_EXISTS_WITH_DIFFERENT_BYTES')
        e.update(repositoryPath=dest,copyRequired=copy)
        prior=self.direct.get(dest);need(prior is None or prior['sha256']==d,'DESTINATION_COLLISION')
        self.direct[dest]={'originalPath':str(p),'repositoryPath':dest,'sha256':d,'bytes':p.stat().st_size,'copyRequired':copy,'gitFileLimitExceeded':p.stat().st_size>HARD,'gitFileWarning':p.stat().st_size>WARNING}
        self.destinations[str(p)]=dest
        self.destinations[original]=dest
        return e
    def archive_index(self,index_path):
        index_path=Path(index_path);x=read(index_path);self.add(index_path,'EXACT_ARCHIVE_MEMBER_INDEX')
        ap=checked(x['archive'],index_path.parent);ae=self.add(ap,'FULL_ORIGINAL_RUN_ARCHIVE',x['archive']['sha256'])
        self.archives.append({'index':ref(index_path),'archive':ref(ap),'repositoryPath':ae['repositoryPath'],'members':len(x['files']),'originalResult':x.get('originalResult') or x.get('checkpointDisposition'),'crcAndMemberHashesVerifiedByOriginalIndex':x.get('allZipCrcAndMemberShaVerified',x.get('zipCrcAndEveryMemberShaVerified',False)),'freshUnzipPerformedByThisTool':False})
        for f in x['files']:
            name=f['member'];need(not Path(name).is_absolute() and '..' not in Path(name).parts,'UNSAFE_ARCHIVE_MEMBER')
            e=self.entry(f['originalPath'],f['sha256'],f['bytes']);e['archiveLocations'].append({'archiveRepositoryPath':ae['repositoryPath'],'archiveSha256':x['archive']['sha256'],'member':name,'memberSha256':f['sha256'],'memberBytes':f['bytes'],'memberIndexRepositoryPath':self.destinations[str(index_path.resolve())]})
        return x
    def package_index(self,path,role):
        p=Path(path);x=read(p);self.add(p,role+'_INDEX')
        for r in x['files']:
            if not isinstance(r,dict) or 'path' not in r or 'sha256' not in r:continue
            target=checked(r,p.parent);self.add(target,role,r['sha256'])
    def original_receipt(self,path,force_all=False):
        p=Path(path).resolve()
        if str(p) in self.receipts_seen:return
        self.receipts_seen.add(str(p));x=read(p);self.add(p,'ACTUAL_EXECUTION_RECEIPT')
        if x.get('kind')=='EXPLICIT_COMPOSITE_PRODUCT_AND_DERIVATION_SCOPES':
            self.original_receipt(checked(x['parent'],p.parent))
            for r in x['toolExecutions']:self.original_receipt(checked(r,p.parent),True)
            return
        force_all=force_all or x.get('kind')=='EVIDENCE_DERIVATION_EXECUTION_RECEIPT'
        for r in x.get('evidence',[]):
            ep=Path(r['path']);ep=(ep if ep.is_absolute() else p.parent/ep).resolve();k=key(ep,r['sha256'])
            if force_all or k not in self.entries:self.add(checked(r,p.parent),'D_TOOL_OR_UNARCHIVED_PARENT_RAW',r['sha256'])
            else:
                self.entries[k]['roles'].append('ORIGINAL_PARENT_EVIDENCE_ARCHIVED')
                for alias in (r['path'],str(ep)):
                    if alias not in self.entries[k]['originalPaths']:self.entries[k]['originalPaths'].append(alias)
        if x.get('kind')=='EVIDENCE_DERIVATION_EXECUTION_RECEIPT':
            for pin in x.get('executedInputs',[]):self.add(ROOT/pin['path'],'ACTUAL_D_EXECUTED_DEPENDENCY',pin['sha256'])
        for name,r in x.items():
            if isinstance(r,dict) and 'path' in r and 'sha256' in r:
                target=checked(r,p.parent);self.add(target,'EXECUTION_METADATA_'+name,r['sha256'])
                if name in ('parentExecution','actualExecutionReceipt','additionalExecutionReceipt'):self.original_receipt(target)
    def directory(self,path,role):
        p=Path(path);need(p.is_dir(),'EXPLICIT_DIRECTORY_MISSING: '+str(p))
        for f in sorted(p.rglob('*')):
            if f.is_file() and '__pycache__' not in f.parts and f.suffix not in ['.pyc']:self.add(f,role)
    def baseline(self,registration):
        p=Path(registration).resolve();config=read(p);need(config['sourceHead']==HEAD and config['sourceTree']==TREE,'WRONG_PRODUCT_REGISTRATION')
        need(len(config['layers'])==9 and {r['id'] for r in config['layers']}==LAYERS,'NINE_EXACT_LAYERS_REQUIRED')
        self.original_config=config;self.destinations.update(config.get('artifactDestinations',{}));self.add(p,'ORIGINAL_NINE_LAYER_REGISTRATION');self.input_refs.append(ref(p))
        self.archive_index(Path('/tmp/slice3-backend-checkpoint-archive-e278b1e-r6/e278b1e-INDEX.json'))
        local=Path('/tmp/slice3-local-layer-archives-e278b1e-r1')
        for n in ['frontend-quality','governance-r2','infrastructure','migration','supply-chain','security-npm-audit','browser-r4']:self.archive_index(local/(n+'-ZIP-MEMBER-INDEX.json'))
        for path in [local/'INDEX.json',Path('/tmp/slice3-current-publication-scan-inputs-e278b1e-r1/ci.json'),Path('/tmp/slice3-security-ci-e278b1e-r1/INDEX.json'),Path('/tmp/slice3-security-schema-e278b1e-r1/EXPLICIT-RAW-AND-ROOT-OUTPUT-INDEX.json')]:self.package_index(path,'CURRENT_EXACT_RAW_OR_ARCHIVE_PACKAGE')
        for dirname in [
            'slice3-capacity-review-e278b1e-r6','slice3-migration-crosscheck-e278b1e-r1',
            'slice3-backend-publication-scan-e278b1e-r6','slice3-backend-publication-triage-e278b1e-r6',
            'slice3-local-publication-scan-e278b1e-r1','slice3-all-ci-publication-scan-e278b1e-r1',
            'slice3-security-review-e278b1e-r1','slice3-security-admission-e278b1e-r1','slice3-security-schema-e278b1e-r1',
            'slice3-publication-observation-e278b1e-r2','slice3-structured-crosschecks-e278b1e-r1',
            'slice3-structured-registration-e278b1e-r1','slice3-structured-binding-requirements-e278b1e-r2',
            'slice3-root-selected-evidence-e278b1e-r3','slice3-structured-proposals-e278b1e-r4',
            'slice3-final-row-audit-e278b1e-r1','slice3-registered-inputs-e278b1e-r1',
            'slice3-final-drivers-e278b1e-r6','slice3-completed-registration-e278b1e-r1']:
            self.directory(Path('/tmp')/dirname,'EXPLICIT_CURRENT_SCOPE_OR_DERIVATION_METADATA')
        for f in ['slice3-audit-capacity-e278b1e-r6.py','slice3-archive-post-v73-backend-r1.py','slice3-archive-current-local-layers-r1.py','slice3-register-final-readback-e278b1e-r1.py','slice3-register-final-readback-e278b1e-r2.py','slice3-bind-root-selected-evidence-e278b1e-r1.py','slice3-bind-root-selected-evidence-e278b1e-r2.py','slice3-bind-root-selected-evidence-e278b1e-r3.py','slice3-scan-explicit-publication-manifest-r1.py','slice3-triage-exact-library-hits-r1.py']:
            self.add(Path('/tmp')/f,'ACTUAL_DERIVATION_HELPER_OR_PRESERVED_REFUSED_PREDECESSOR')
        self.add('/tmp/slice3-backend-checkpoint-archive-e278b1e-r6/e278b1e-RAW-JUNIT-RECONCILIATION.json','ORIGINAL_FULL_RAW_NODE_RECOUNT')
        for spec in config['layers']:
            rp=Path(spec['receipt']);self.original_receipt(rp if rp.is_absolute() else p.parent/rp)
            for r in spec.get('structuredRecordAdapters',[])+spec.get('jsonRecordAdapters',[]):self.add(checked(r['evidence'],p.parent),'REGISTERED_NAMED_OR_STRUCTURED_RAW',r['evidence']['sha256'])
    def histories(self):
        hp=Path('/tmp/slice3-historical-publication-appendix-r1/HISTORICAL-PUBLICATION-PLAN.json');h=read(hp);self.add(hp,'HISTORICAL_APPENDIX_ORIGINAL_PLAN')
        for r in h['publicationCandidateFiles']:self.add(checked(r,hp.parent),'EXACT_RECORDED_HISTORICAL_CANDIDATE',r['sha256'])
        # Original indices are independently mapped; their historical failure and
        # cancellation dispositions remain unchanged and are never current proofs.
        for p in ['/tmp/slice3-backend-checkpoint-archives-r1/7e66cf8-INDEX.json','/tmp/slice3-backend-checkpoint-archives-r1/983e5cc-INDEX.json','/tmp/slice3-backend-checkpoint-archive-02e6172-r5/02e6172-INDEX.json','/tmp/slice3-backend-checkpoint-archive-da559bd-r6/da559bd-INDEX.json']:
            self.archive_index(Path(p))
        for dirname in ['slice3-local-layer-archives-02e6172-r1','slice3-browser-r4-archives-02e6172-r1']:
            for p in sorted((Path('/tmp')/dirname).glob('*-ZIP-MEMBER-INDEX.json')):self.archive_index(p)
            self.package_index(Path('/tmp')/dirname/'INDEX.json','HISTORICAL_LOCAL_ARCHIVE_PACKAGE')
        for p in ['/tmp/slice3-checkpoint-ci-02e6172-all/RAW-FILE-INDEX-r2.json','/tmp/slice3-historical-1381-publication-package-r1/DELIVERY-INDEX.json','/tmp/slice3-frontend-failure-da559bd-r1/INDEX.json','/tmp/slice3-frontend-failure-95902cf-r1/INDEX.json']:self.package_index(Path(p),'HISTORICAL_EXACT_FAILURE_OR_CHECKPOINT_PACKAGE')
        for dirname in ['slice3-security-sarif-publication-02e6172-r1','slice3-backend-publication-scan-da559bd-r6','slice3-backend-publication-triage-da559bd-r6','slice3-frontend-failure-publication-scan-da559bd-r1','slice3-frontend-failure-publication-scan-95902cf-r1','slice3-interrupted-backend-95902cf-r2']:
            self.directory(Path('/tmp')/dirname,'PRESERVED_ORIGINAL_HISTORICAL_DISPOSITION')
        self.add('/tmp/slice3-backend-checkpoint-archive-da559bd-r6/da559bd-RAW-JUNIT-RECONCILIATION.json','HISTORICAL_F_COMPLETE_BACKEND_RECOUNT')
        for f in ['slice3-manual-select-locator-diagnostic-r2.cjs','slice3-datetime-canonical-diagnostic-r1.cjs']:self.add(Path('/tmp')/f,'HISTORICAL_BROWSER_REPAIR_DIAGNOSTIC_SOURCE')
    def expected_source(self,path):
        p=Path(path).resolve();x=read(p);need(x['sourceHead']==HEAD and x['sourceTree']==TREE,'EXPECTED_PRODUCT_IDENTITY_CHANGED');self.add(p,'ACTUAL_EXPECTED_PRODUCT_AND_EVIDENCE_TOOL_SOURCE');self.input_refs.append(ref(p));self.derivation=x.get('derivationSourceIdentity')
        self.add(checked(x['inventory'],p.parent),'FULL_1280_RUNTIME_SOURCE_INVENTORY',x['inventory']['sha256'])
        if isinstance(x.get('identityEvidence'),dict):self.add(checked(x['identityEvidence'],p.parent),'ORIGINAL_PRODUCT_IDENTITY_EVIDENCE')
        for r in x.get('additionalExecutionInputs',[]):self.original_receipt(checked(r['executionReceipt'],p.parent),True)
    def selected_proofs(self,catalog_path,selections_path):
        cp=Path(catalog_path).resolve();sp=Path(selections_path).resolve();catalog=read(cp);selections=read(sp)
        need(catalog.get('sourceHead')==HEAD and catalog.get('sourceTree')==TREE and selections.get('sourceHead')==HEAD and selections.get('sourceTree')==TREE,'SELECTED_PRODUCT_IDENTITY_CHANGED')
        self.add(cp,'ACTUAL_NODE_CATALOG');self.add(sp,'ROOT_ACTUAL_PROOF_SELECTIONS');self.input_refs.extend([ref(cp),ref(sp)])
        nodes={n['nodeId']:n for n in catalog['nodes']};need(len(nodes)==len(catalog['nodes']),'DUPLICATE_CATALOG_NODE');catalog_base=cp.parent
        for row in rows(selections):
            for selection in row.get('proofSelections',[]):
                n=nodes[selection['nodeId']];need(n.get('observedResult')=='PASSED' and n.get('admissibleAfterIndependentScopeReview') is True,'UNADMITTED_OR_FAILED_NODE_SELECTED')
                ep=checked(n['evidence'],catalog_base);self.add(ep,'DIRECT_SELECTED_RAW_PROOF',n['evidence']['sha256'])
                for field in ('actualExecutionReceipt','additionalExecutionReceipt'):
                    if field in n:self.original_receipt(checked(n[field],catalog_base),field=='additionalExecutionReceipt')
                source=n['source'];sp=Path(source['path']);sp=sp if sp.is_absolute() else ROOT/sp;self.add(sp,'EXISTING_PRODUCT_OR_ACTUAL_D_PROOF_SOURCE',source['sha256'])
                self.selected.append({'rowId':row['id'],'nodeId':n['nodeId'],'originalEvidence':n['evidence'],'scope':selection['scope'],'role':selection['role'],'actualExecutionReceipt':n.get('actualExecutionReceipt')})
    def assembly(self,path,row_audit):
        p=Path(path).resolve();inputs=read(p);self.add(p,'ACTUAL_ROW_AUDIT_INPUT_CONFIG');docs={}
        for name,r in inputs.items():
            fp=checked(r,p.parent);self.add(fp,'ACTUAL_ASSEMBLY_'+name,r['sha256']);docs[name]=read(fp)
        need(all(name in docs for name in ['slots','nodeCatalog','rootSelections','clausesCandidate','manifestCandidate','expectedSource','sourcePlan','methodProposals','layerConfig']),'ACTUAL_COMPLETE_ASSEMBLY_INPUTS_REQUIRED')
        current_config=checked(inputs['layerConfig'],p.parent);need({r['id'] for r in docs['layerConfig']['layers']}==LAYERS,'ACTUAL_ASSEMBLY_LAYER_SET')
        for spec in docs['layerConfig']['layers']:
            rp=Path(spec['receipt']);self.original_receipt(rp if rp.is_absolute() else current_config.parent/rp)
        self.original_config=docs['layerConfig']
        self.selected_proofs(checked(inputs['nodeCatalog'],p.parent),checked(inputs['rootSelections'],p.parent))
        expected=docs['expectedSource'];expected_base=checked(inputs['expectedSource'],p.parent).parent
        for r in expected.get('additionalExecutionInputs',[]):self.original_receipt(checked(r['executionReceipt'],expected_base),True)
        if row_audit:
            ar=Path(row_audit).resolve();j=read(ar);need(j.get('kind')=='READ_ONLY_PENDING_ASSEMBLY_ROW_AUDIT' and j.get('sourceHead')==HEAD,'WRONG_ROW_AUDIT');need(j.get('mechanicallyCheckedRowCounts',{}).get('clauses')==115 and not j.get('mechanicalIssues'),'CLAUSE_AUDIT_NOT_MECHANICALLY_BOUND');self.add(ar,'MANDATORY_115_CLAUSE_INDEPENDENT_AUDIT');self.add(checked(j['auditor'],ar.parent),'ACTUAL_115_CLAUSE_AUDITOR_SOURCE')
        else:self.missing.append('Actual 115-clause ROW-AUDIT.json and its exact auditor source/receipt')
    def output(self,out):
        files=sorted(self.direct.values(),key=lambda r:r['repositoryPath']);oversized=[r for r in files if r['gitFileLimitExceeded']]
        mapping={'kind':'BYTE_EXACT_ORIGINAL_PATH_RELOCATION_MAP','sourceHead':HEAD,'sourceTree':TREE,'entries':sorted(self.entries.values(),key=lambda r:(r['canonicalOriginalPath'],r['sha256'])),'originalJsonBytesRewritten':False,'archiveMembersExtracted':False,'boundary':'Resolve each unchanged original path together with its SHA via this separate map. A standalone proof must use repositoryPath. Other parent members remain addressable by exact archive/member/SHA. Do not edit embedded absolute paths in original receipts.'}
        (out/'RELOCATION-MAPPING.json').write_text(json.dumps(mapping,ensure_ascii=False,indent=2)+'\n')
        (out/'ARTIFACT-DESTINATIONS.json').write_text(json.dumps({'artifactDestinations':self.destinations},ensure_ascii=False,indent=2)+'\n')
        config=dict(self.original_config,artifactDestinations=self.destinations)
        (out/'EXECUTION-INPUTS-WITH-PORTABLE-DESTINATIONS.json').write_text(json.dumps(config,ensure_ascii=False,indent=2)+'\n')
        plan={'kind':'PORTABLE_EVIDENCE_PUBLICATION_PREPARATION_ONLY','status':'PLAN_ONLY','sourceHead':HEAD,'sourceTree':TREE,'derivationSourceIdentity':self.derivation,'builder':ref(__file__),'inputs':self.input_refs,'directFiles':files,'archives':self.archives,'selectedProofOccurrences':self.selected,'selectedNodes':len({x['nodeId'] for x in self.selected}),'selectionBound':bool(self.selected),'stillRequired':self.missing,'sizeSummary':{'directUniqueFiles':len(files),'newCopyBytes':sum(r['bytes'] for r in files if r['copyRequired']),'largestFileBytes':max(r['bytes'] for r in files),'over100MiB':oversized,'over50MiB':[r for r in files if r['gitFileWarning']]},'sizeAuthority':{'url':'https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github','checkedOn':'2026-09-06','gitWarningBytesExclusive':WARNING,'gitBlockedBytesExclusive':HARD,'browserUploadLimitBytes':25*1024*1024},'relocationMap':ref(out/'RELOCATION-MAPPING.json'),'artifactDestinations':ref(out/'ARTIFACT-DESTINATIONS.json'),'derivedRegistrationConfig':ref(out/'EXECUTION-INPUTS-WITH-PORTABLE-DESTINATIONS.json'),'copiedFiles':0,'repositoryModified':False,'publicationPerformed':False,'publicationAuthorizedByThisTool':False,'engineeringClosureClaimMade':False,'controllerApprovalClaimMade':False,'productionWriteEnabled':False,'limits':['Archive CRC/member validation is the original exact index evidence; this preparation rehashes archive bytes but does not re-unzip or manufacture a new CRC pass.','The scan artifacts and exact-hit triage are preserved; adding new assembly/audit outputs needs the final publication scan of those actual bytes.','Complete original historical layers and failed browser histories keep their original status. No failed history becomes current proof.','Original absolute-path JSON is retained byte-exact; relocation is a separate mapping, never an in-place path rewrite.','The finalizer output, its actual command receipt, final per-row Controller boundary and final index must be added after authorized Root admission; this plan cannot create them.']}
        (out/'PUBLICATION-PLAN.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({'plan':ref(out/'PUBLICATION-PLAN.json'),'directFiles':len(files),'mappedOriginals':len(self.entries),'selectedNodes':plan['selectedNodes'],'over100MiB':len(oversized),'filesCopied':0}))
        return 1 if oversized else 0

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--registration',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--assembly-inputs',type=Path);p.add_argument('--row-audit',type=Path);p.add_argument('--expected-source',type=Path);p.add_argument('--catalog',type=Path);p.add_argument('--selections',type=Path);a=p.parse_args();out=a.out.resolve();need(out!=Path('/tmp').resolve() and out.is_relative_to(Path('/tmp').resolve()) and not out.exists(),'NEW_OWNED_TMP_OUTPUT_REQUIRED');out.mkdir()
    plan=Plan();plan.baseline(a.registration)
    if a.expected_source:plan.expected_source(a.expected_source)
    need(bool(a.catalog)==bool(a.selections),'CATALOG_AND_REVIEWED_SELECTIONS_MUST_BE_PAIRED')
    if a.catalog:plan.selected_proofs(a.catalog,a.selections)
    if a.assembly_inputs:plan.assembly(a.assembly_inputs,a.row_audit)
    else:plan.missing += ['Root-reviewed final 342-row selections and actual source-plan/method-proposal/structured scope reconciliation','Actual FROZEN-CLAUSE-ASSESSMENT-CANDIDATE.json with all 115 entries','Actual independent 115-clause ROW-AUDIT.json','Final pending manifest/assembly inputs and separate subsequent finalizer execution/outputs']
    if not a.expected_source and not a.assembly_inputs:plan.missing.append('Actual D expected-source registration and successful tool execution receipts')
    plan.histories()
    plan.add(__file__,'ACTUAL_PORTABLE_PLAN_BUILDER')
    return plan.output(out)

if __name__=='__main__':raise SystemExit(main())
