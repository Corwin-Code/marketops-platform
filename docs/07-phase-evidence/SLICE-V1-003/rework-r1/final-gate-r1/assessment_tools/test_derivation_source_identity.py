"""Small isolated Git/JSON fixtures for tool identity, never product execution."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import propose_method_bindings as b

TOOLS='docs/07-phase-evidence/example/assessment_tools'
JAVA='backend/marketops-server/src/test/java/example/ScopeIT.java'
COLLECTOR='docs/07-phase-evidence/SLICE-V1-003/rework-r1/final-gate-r1/collect_execution.py'
class DerivationIdentity(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='slice3-derivation-identity-',dir='/tmp');self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name);self.root=self.base/'repository';self.root.mkdir();self.here=Path(__file__).resolve().parent
        self.put('bootstrap-manifest.json','{}\n');self.put('.gitattributes','*.cmd text eol=crlf\n*.ps1 text eol=crlf\n')
        self.put(JAVA,'package example; class ScopeIT {}\n');self.put(COLLECTOR,'# synthetic original runtime collector\n')
        self.put('backend/marketops-server/mvnw.cmd',b'@echo off\r\n');self.put('scripts/bootstrap-repo.ps1',b'Write-Output synthetic\r\n')
        self.git('init','-q');self.commit('synthetic product checkpoint');self.product=self.git('rev-parse','HEAD');self.product_tree=self.git('rev-parse','HEAD^{tree}')
        paths=['bootstrap-manifest.json','.gitattributes',JAVA,COLLECTOR,*b.LINE_ENDING_EQUIVALENCES]
        self.inventory={'files':[{'path':p,'sha256':b.digest((self.root/p).read_bytes())} for p in paths]};self.ip=self.write('inventory.json',self.inventory)
        self.toolpaths=[TOOLS+'/'+n for n in ('assemble_assessment_with_structured.py','propose_method_bindings.py')]
        for p in self.toolpaths:self.put(p,(self.here/Path(p).name).read_bytes())
        self.commit('synthetic evidence-only tool checkpoint');self.expected={};self.rebind()
    def put(self,path,data):
        p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data.encode() if isinstance(data,str) else data);return p
    def write(self,name,data):p=self.base/name;p.write_text(json.dumps(data));return p
    def git(self,*args):return b.git_read(self.root,*args).decode().strip()
    def commit(self,message):
        self.git('add','.');self.git('-c','user.name=Synthetic Tool Check','-c','user.email=tool@example.invalid','-c','commit.gpgsign=false','commit','-qm',message)
    def rebind(self):
        head=self.git('rev-parse','HEAD');tree=self.git('rev-parse','HEAD^{tree}')
        self.expected={'sourceHead':self.product,'sourceTree':self.product_tree,'identityScope':'CLEAN_COMMIT_TREE','inventory':b.ref(self.ip),'disposition':'SYNTHETIC_TOOL_ONLY'}
        identity=self.write('identity.json',{k:self.expected[k] for k in ('sourceHead','sourceTree','identityScope')});self.expected['identityEvidence']=b.ref(identity)
        d={'sourceHead':head,'sourceTree':tree,'identityScope':'EVIDENCE_DERIVATION_ONLY','appliesToProductSourceHead':self.product,'appliesToProductSourceTree':self.product_tree,'sourceInventorySha256':b.ref(self.ip)['sha256'],
            'lineEndingEquivalences':[{'path':p,'transformation':t} for p,t in b.LINE_ENDING_EQUIVALENCES.items()]};self.expected['derivationSourceIdentity']=d
        pins=[{'path':p,'sourceHead':head,'sourceTree':tree,'gitBlobOid':self.git('rev-parse',head+':'+p),'sha256':b.digest((self.root/p).read_bytes())} for p in self.toolpaths]
        log=self.base/'synthetic-record.log';log.write_text('Synthetic receipt fixture; no product test or actual tool-run claim.\n')
        self.receipt={'kind':'EVIDENCE_DERIVATION_EXECUTION_RECEIPT','scope':'EVIDENCE_DERIVATION_ONLY',**{k:d[k] for k in ['sourceHead','sourceTree','appliesToProductSourceHead','appliesToProductSourceTree','sourceInventorySha256']},'runId':'SYNTHETIC_ONLY',
            'result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED','sourceStable':True,'exitCode':0,'startedAt':'2026-01-01T00:00:00Z','finishedAt':'2026-01-01T00:00:01Z','argv':['synthetic-fixture-no-product-command'],'executedInputs':pins,'evidence':[b.ref(log)]}
        self.rp=self.write('receipt.json',self.receipt)
        self.expected['additionalExecutionInputs']=[dict(pin,scope='EVIDENCE_DERIVATION_ONLY',executionReceipt=b.ref(self.rp)) for pin in pins]
    def sync_receipt(self):
        self.write('receipt.json',self.receipt)
        for r in self.expected['additionalExecutionInputs']:r['executionReceipt']=b.ref(self.rp)
    def check(self):return b.derivation_sources(self.expected,self.inventory,self.root,self.base)
    def test_evidence_only_descendant_preserves_product_identity_and_all_git_runtime_blobs(self):
        original=copy.deepcopy(self.expected);audit={};sources=b.derivation_sources(self.expected,self.inventory,self.root,self.base,audit);self.assertEqual(self.expected,original);self.assertEqual(audit['runtimeFileCount'],len(self.inventory['files']));self.assertEqual(audit['originalRuntimeGitEntriesSha256'],audit['derivationRuntimeGitEntriesSha256']);self.assertEqual({r['path'] for r in audit['changedPaths']},set(self.toolpaths));self.assertFalse(audit['productExecutionClaimMade'])
        self.assertEqual(sources[JAVA]['sourceAuthorityScope'],'RUNTIME_SOURCE_INVENTORY');self.assertEqual(sources[self.toolpaths[0]]['sourceHead'],self.git('rev-parse','HEAD'));self.assertNotEqual(sources[self.toolpaths[0]]['sourceHead'],self.product)
    def test_only_two_declared_crlf_checkout_paths_are_allowed(self):
        self.check();self.expected['derivationSourceIdentity']['lineEndingEquivalences']=[]
        with self.assertRaisesRegex(b.Rejected,'PRODUCT_INVENTORY_GIT_SHA'):self.check()
    def test_arbitrary_crlf_exception_is_rejected(self):
        self.expected['derivationSourceIdentity']['lineEndingEquivalences'].append({'path':JAVA,'transformation':'GIT_LF_TO_WORKTREE_CRLF'})
        with self.assertRaisesRegex(b.Rejected,'LINE_ENDING_RULE_NOT_ALLOWED'):self.check()
    def test_duplicate_crlf_exception_is_rejected(self):
        self.expected['derivationSourceIdentity']['lineEndingEquivalences']*=2
        with self.assertRaisesRegex(b.Rejected,'LINE_ENDING_RULE_NOT_ALLOWED'):self.check()
    def test_changed_existing_runtime_code_in_descendant_is_rejected(self):
        self.put(JAVA,'package example; class ScopeIT { int changed; }\n');self.commit('bad runtime descendant');self.rebind()
        with self.assertRaisesRegex(b.Rejected,'RUNTIME_GIT_INPUT_CHANGED'):self.check()
    def test_changed_inventoried_document_collector_is_not_an_evidence_exception(self):
        self.put(COLLECTOR,'# changed runtime collector\n');self.commit('bad inventoried tool');self.rebind()
        with self.assertRaisesRegex(b.Rejected,'RUNTIME_GIT_INPUT_CHANGED'):self.check()
    def test_new_runtime_file_outside_original_inventory_is_rejected(self):
        self.put('backend/marketops-server/src/test/java/example/NewIT.java','class NewIT {}\n');self.commit('bad new runtime path');self.rebind()
        with self.assertRaisesRegex(b.Rejected,'NON_EVIDENCE_PATH_CHANGED'):self.check()
    def test_new_top_level_executable_is_rejected(self):
        self.put('new-runtime.sh','exit 0\n');self.commit('bad new root path');self.rebind()
        with self.assertRaisesRegex(b.Rejected,'NON_EVIDENCE_PATH_CHANGED'):self.check()
    def test_runtime_file_mode_change_is_rejected(self):
        (self.root/JAVA).chmod(0o755);self.commit('bad mode');self.rebind()
        with self.assertRaisesRegex(b.Rejected,'RUNTIME_GIT_INPUT_CHANGED'):self.check()
    def test_incomplete_runtime_inventory_is_rejected(self):
        self.inventory['files']=[r for r in self.inventory['files'] if r['path']!=JAVA];self.write('inventory.json',self.inventory);self.rebind()
        with self.assertRaisesRegex(b.Rejected,'RUNTIME_INVENTORY_INCOMPLETE'):self.check()
    def test_duplicate_inventory_path_is_rejected(self):
        self.inventory['files'].append(self.inventory['files'][0]);self.write('inventory.json',self.inventory);self.rebind()
        with self.assertRaisesRegex(b.Rejected,'INVALID_OR_DUPLICATE_RUNTIME_INPUT'):self.check()
    def test_wrong_product_inventory_hash_is_rejected(self):
        self.expected['inventory']['sha256']='f'*64
        with self.assertRaisesRegex(b.Rejected,'FILE_SHA_MISMATCH'):self.check()
    def test_captured_original_product_identity_cannot_be_swapped(self):
        p=self.write('identity.json',{'sourceHead':'f'*40,'sourceTree':self.product_tree,'identityScope':'CLEAN_COMMIT_TREE'});self.expected['identityEvidence']=b.ref(p)
        with self.assertRaisesRegex(b.Rejected,'CAPTURED_PRODUCT_IDENTITY_MISMATCH'):self.check()
    def test_wrong_derivation_tree_is_rejected(self):
        self.expected['derivationSourceIdentity']['sourceTree']=self.product_tree
        with self.assertRaisesRegex(b.Rejected,'COMMIT_TREE_MISMATCH'):self.check()
    def test_non_descendant_tool_commit_is_rejected(self):
        tree=self.git('rev-parse','HEAD^{tree}');head=self.git('-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit-tree',tree,'-m','synthetic unrelated root');self.git('checkout','--detach','-q',head);self.rebind()
        with self.assertRaisesRegex(b.Rejected,'GIT_CHECK_FAILED: merge-base'):self.check()
    def test_product_head_cannot_be_relabelled_as_new_tool_commit(self):
        self.expected['derivationSourceIdentity']['sourceHead']=self.product
        with self.assertRaisesRegex(b.Rejected,'MUST_BE_DISTINCT_DESCENDANT'):self.check()
    def test_dirty_runtime_or_helper_cannot_use_clean_commit_receipt(self):
        self.put(self.toolpaths[0],(self.root/self.toolpaths[0]).read_bytes()+b'\n# dirty\n')
        with self.assertRaisesRegex(b.Rejected,'WORKTREE_NOT_CLEAN'):self.check()
    def test_helper_cannot_claim_old_product_head(self):
        self.expected['additionalExecutionInputs'][0]['sourceHead']=self.product
        with self.assertRaisesRegex(b.Rejected,'ADDITIONAL_INPUT_IDENTITY_MISMATCH'):self.check()
    def test_wrong_helper_git_blob_is_rejected(self):
        self.expected['additionalExecutionInputs'][0]['gitBlobOid']='0'*40
        with self.assertRaisesRegex(b.Rejected,'ADDITIONAL_GIT_BLOB_MISMATCH'):self.check()
    def test_wrong_helper_sha_is_rejected(self):
        self.expected['additionalExecutionInputs'][0]['sha256']='f'*64
        with self.assertRaisesRegex(b.Rejected,'ADDITIONAL_INPUT_BYTES_CHANGED'):self.check()
    def test_unexecuted_helper_and_wrong_executed_source_are_rejected(self):
        self.receipt['executedInputs'].pop(0);self.sync_receipt()
        with self.assertRaisesRegex(b.Rejected,'ADDITIONAL_INPUT_NOT_EXECUTED'):self.check()
    def test_missing_receipt_is_rejected(self):
        self.expected['additionalExecutionInputs'][0]['executionReceipt']=None
        with self.assertRaisesRegex(b.Rejected,'ABSENT_FILE_REFERENCE'):self.check()
    def test_receipt_cannot_be_a_product_run(self):
        self.receipt['sourceHead']=self.product;self.sync_receipt()
        with self.assertRaisesRegex(b.Rejected,'RECEIPT_IDENTITY_MISMATCH'):self.check()
    def test_failed_incomplete_future_or_changed_log_receipt_is_rejected(self):
        for change,message in [({'exitCode':1},'NOT_TERMINAL_SUCCESS'),({'finishedAt':None},'TIME_INVALID'),({'finishedAt':'2999-01-01T00:00:00Z'},'TIME_INVALID'),({'evidence':[]},'RAW_EVIDENCE_ABSENT')]:
            with self.subTest(change=change):
                original=copy.deepcopy(self.receipt);self.receipt.update(change);self.sync_receipt()
                with self.assertRaisesRegex(b.Rejected,message):self.check()
                self.receipt=original;self.sync_receipt()
        (self.base/'synthetic-record.log').write_text('changed')
        with self.assertRaisesRegex(b.Rejected,'FILE_SHA_MISMATCH'):self.check()
    def test_same_head_legacy_additional_pin_is_not_silently_moved_to_derivation_identity(self):
        self.expected['additionalExecutionInputs'][0]['scope']='RUNTIME_SOURCE_INVENTORY'
        with self.assertRaisesRegex(b.Rejected,'ADDITIONAL_INPUT_IDENTITY_MISMATCH'):self.check()
    def test_actual_assembler_and_binder_keep_product_nodes_and_independent_tool_identity(self):
        expected=self.write('expected.json',self.expected);raw=self.base/'junit.xml';raw.write_text('<testsuite name="example.ScopeIT"><testcase classname="nested display name" name="scoped"/></testsuite>')
        identity={'sourceHead':self.product,'sourceTree':self.product_tree,'sourceInventorySha256':b.ref(self.ip)['sha256']}
        layer=self.write('product-layer.json',{**identity,'runId':'SYNTHETIC_PRODUCT_SHAPE_ONLY','result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED','sourceStable':True,'exitCode':0,'finishedAt':'synthetic','evidence':[b.ref(raw)]})
        config=self.write('config.json',{**identity,'layers':[{'id':'backend_full','receipt':str(layer)}]});out=self.base/'catalog';out.mkdir()
        (out/'METHOD-LOCATORS.json').write_text('{"selectors":[]}');(out/'ASSESSMENT-SLOTS.json').write_text(json.dumps({**identity,**{k:[] for k in ('criteria','findings','clauses','verificationChecks')}}))
        argv=[sys.executable,'-B',str(self.root/self.toolpaths[0]),'catalog','--source-identity',str(expected),'--out',str(out),'--config',str(config)]
        run=subprocess.run(argv,cwd=self.root,capture_output=True,text=True);self.assertEqual(run.returncode,0,run.stderr)
        catalog=json.loads((out/'CURRENT-NODE-CATALOG.json').read_text());self.assertEqual(catalog['sourceHead'],self.product);self.assertEqual(catalog['derivationSourceIdentity'],self.expected['derivationSourceIdentity']);self.assertEqual(catalog['nodes'][0]['class'],'nested display name');self.assertEqual(catalog['nodes'][0]['sourceHead'],self.product);self.assertFalse(catalog['engineeringClosureClaimMade']);self.assertEqual(catalog['derivationSourceValidation']['runtimeFileCount'],len(self.inventory['files']))
        plan=self.write('plan.json',{**identity,'reviewer':'synthetic','rows':[{'id':'synthetic','rowKind':'criteria','proofSelections':[],'selectedMethods':[{'kind':'method','sourcePath':JAVA,'candidateSourceSha256':next(r['sha256'] for r in self.inventory['files'] if r['path']==JAVA),'method':'scoped','layer':'backend_full','plannedRole':'supporting','scope':'synthetic only','evidenceBoundary':'no actual product claim'}]}]})
        blockers=self.write('blockers.json',{**identity,'reviewer':'synthetic','reviewBoundary':'synthetic only','rows':[]})
        target=self.base/'bindings';argv=[sys.executable,'-B',str(self.root/self.toolpaths[1]),'--expected-source',str(expected),'--catalog',str(out/'CURRENT-NODE-CATALOG.json'),'--plan',str(plan),'--blockers',str(blockers),'--out',str(target)]
        run=subprocess.run(argv,cwd=self.root,capture_output=True,text=True);self.assertEqual(run.returncode,0,run.stderr);proposal=json.loads((target/'PROPOSED-METHOD-BINDINGS.json').read_text());self.assertEqual(proposal['sourceHead'],self.product);self.assertEqual(proposal['summary']['proposedSelections'],1);self.assertFalse(proposal['productTestsExecuted']);self.assertEqual(proposal['derivationSourceIdentity'],self.expected['derivationSourceIdentity'])
        external=list(argv);external[2]=str(self.here/'propose_method_bindings.py');external[-1]=str(self.base/'external-binding');run=subprocess.run(external,cwd=self.root,capture_output=True,text=True);self.assertNotEqual(run.returncode,0);self.assertIn('BINDER_DERIVATION_TOOL_NOT_BOUND',run.stderr)
        catalog.pop('derivationSourceIdentity');(out/'CURRENT-NODE-CATALOG.json').write_text(json.dumps(catalog));argv[-1]=str(self.base/'rejected-binding');run=subprocess.run(argv,cwd=self.root,capture_output=True,text=True);self.assertNotEqual(run.returncode,0);self.assertIn('CATALOG_DERIVATION_IDENTITY_MISMATCH',run.stderr)

if __name__=='__main__':unittest.main()
