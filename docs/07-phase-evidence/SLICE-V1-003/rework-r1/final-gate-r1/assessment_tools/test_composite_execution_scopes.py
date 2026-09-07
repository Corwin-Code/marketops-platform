"""Synthetic wrapper/raw-admission checks; never executed product or future-D proof."""
import copy,json,subprocess,sys,unittest
from pathlib import Path
import propose_method_bindings as b
import test_derivation_source_identity as fixtures

TEST=fixtures.TOOLS+'/test_synthetic_scope.py'

class CompositeScopes(unittest.TestCase):
    def setUp(self):
        self.f=fixtures.DerivationIdentity();self.f.setUp();self.addCleanup(self.f.doCleanups);f=self.f
        # Same test file exists at both synthetic checkpoints; a matching SHA is
        # deliberately insufficient to move its old raw execution to the new run.
        f.git('checkout','--detach','-q',f.product);f.put(TEST,'def test_scope():\n    assert True\n');f.commit('synthetic product with excluded historical helper')
        f.product=f.git('rev-parse','HEAD');f.product_tree=f.git('rev-parse','HEAD^{tree}')
        for p in f.toolpaths:f.put(p,(f.here/Path(p).name).read_bytes())
        f.toolpaths.append(TEST);f.commit('synthetic derivation with unchanged test helper');f.rebind()
        self.identity={'sourceHead':f.product,'sourceTree':f.product_tree,'sourceInventorySha256':b.ref(f.ip)['sha256']}
        self.oldraw=f.write('old-product-tool22.json',{'records':[{'name':'test_scope','status':'PASSED','source':TEST}]})
        self.productraw=f.write('product-controls.json',{'records':[{'name':'scoped','status':'PASSED','source':fixtures.JAVA}]})
        self.raw=f.write('actual-new-tool-raw.json',{'records':[{'name':'test_scope','status':'PASSED','source':TEST}]})
        f.receipt['runId']='SYNTHETIC_D_ACTUAL_SHAPE';f.receipt['evidence'].append(b.ref(self.raw));f.sync_receipt()
        self.parent={'kind':'SYNTHETIC_PRODUCT_RECEIPT','runId':'SYNTHETIC_OLD_PRODUCT_SHAPE',**self.identity,'result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED','sourceStable':True,'exitCode':0,'finishedAt':'2026-01-01T00:00:01Z','evidence':[b.ref(self.oldraw),b.ref(self.productraw)]}
        self.pp=f.write('parent.json',self.parent);self.wrapper={'kind':b.COMPOSITE_KIND,'parent':b.ref(self.pp),'toolExecutions':[b.ref(f.rp)]};self.wp=f.write('composite.json',self.wrapper)
    def sync(self):
        f=self.f;f.sync_receipt();self.pp=f.write('parent.json',self.parent)
        self.wrapper.update(parent=b.ref(self.pp),toolExecutions=[b.ref(f.rp)]);self.wp=f.write('composite.json',self.wrapper)
    def context(self):
        self.sources=self.f.check();return b.composite_execution_context(b.ref(self.wp),self.identity,self.f.base,self.f.expected)
    def scope(self,evidence=None):
        context=self.context();return b.additional_node_execution(self.sources[TEST],b.ref(evidence or self.raw),context,self.f.expected,self.f.base)
    def test_actual_new_raw_preserves_separate_product_and_execution_identity(self):
        before=self.pp.read_bytes();scope=self.scope();self.assertEqual(scope['runId'],self.f.receipt['runId']);self.assertEqual(scope['productSourceIdentity'],self.identity);self.assertEqual(scope['executionSourceIdentity'],self.f.expected['derivationSourceIdentity']);self.assertEqual(scope['actualExecutionReceipt'],b.ref(self.f.rp));self.assertEqual(self.pp.read_bytes(),before)
    def test_identical_test_sha_cannot_move_old22_raw_to_new_tool_receipt(self):
        self.assertEqual(self.f.git('rev-parse',self.f.product+':'+TEST),self.f.git('rev-parse','HEAD:'+TEST))
        with self.assertRaisesRegex(b.Rejected,'RAW_NOT_IN_OWN_EXECUTION_RECEIPT'):self.scope(self.oldraw)
    def test_same_raw_bytes_at_another_path_are_not_execution_membership(self):
        twin=self.f.base/'copied-raw.json';twin.write_bytes(self.raw.read_bytes())
        with self.assertRaisesRegex(b.Rejected,'RAW_NOT_IN_OWN_EXECUTION_RECEIPT'):self.scope(twin)
    def test_changed_raw_bytes_are_rejected(self):
        self.raw.write_text('{"changed":true}')
        with self.assertRaisesRegex(b.Rejected,'FILE_SHA_MISMATCH'):self.scope()
    def test_wrapper_cannot_invent_aggregate_execution_fields(self):
        for field,value in [('runId','made-up'),('exitCode',0),('startedAt','2026-01-01T00:00:00Z')]:
            with self.subTest(field=field):
                self.wp=self.f.write('composite.json',{**self.wrapper,field:value})
                with self.assertRaisesRegex(b.Rejected,'WRAPPER_FIELDS_INVALID'):self.context()
        self.wp=self.f.write('composite.json',self.wrapper)
    def test_duplicate_tool_execution_registration_is_rejected(self):
        self.wrapper['toolExecutions']*=2;self.f.write('composite.json',self.wrapper)
        with self.assertRaisesRegex(b.Rejected,'UNREGISTERED_OR_DUPLICATE'):self.context()
    def test_tool_receipt_not_registered_by_an_additional_input_is_rejected(self):
        second=self.f.write('unregistered-receipt.json',self.f.receipt);self.wrapper['toolExecutions']=[b.ref(second)];self.f.write('composite.json',self.wrapper)
        with self.assertRaisesRegex(b.Rejected,'UNREGISTERED_OR_DUPLICATE'):self.context()
    def test_missing_tool_execution_refuses(self):
        self.wrapper['toolExecutions']=[];self.f.write('composite.json',self.wrapper)
        with self.assertRaisesRegex(b.Rejected,'TOOL_EXECUTIONS_ABSENT'):self.context()
    def test_failed_original_product_parent_is_not_laundered_by_tools(self):
        self.parent['exitCode']=1;self.sync()
        with self.assertRaisesRegex(b.Rejected,'PRODUCT_PARENT_NOT_TERMINAL_SUCCESS'):self.context()
    def test_wrong_product_parent_identity_is_rejected(self):
        self.parent['sourceHead']=self.f.expected['derivationSourceIdentity']['sourceHead'];self.sync()
        with self.assertRaisesRegex(b.Rejected,'PRODUCT_PARENT_IDENTITY_MISMATCH'):self.context()
    def test_duplicate_run_id_across_scopes_is_rejected(self):
        self.f.receipt['runId']=self.parent['runId'];self.sync()
        with self.assertRaisesRegex(b.Rejected,'EXECUTION_RUN_AMBIGUOUS'):self.context()
    def test_shared_raw_registration_between_product_and_derivation_is_ambiguous(self):
        self.f.receipt['evidence'].append(b.ref(self.oldraw));self.sync()
        with self.assertRaisesRegex(b.Rejected,'RAW_ARTIFACT_OWNERSHIP_AMBIGUOUS'):self.context()
    def test_duplicate_raw_registration_within_receipt_is_ambiguous(self):
        self.f.receipt['evidence'].append(b.ref(self.raw));self.sync()
        with self.assertRaisesRegex(b.Rejected,'DUPLICATE_EXECUTION_EVIDENCE_REFERENCE'):self.context()
    def test_source_pin_must_be_in_the_exact_own_receipt(self):
        context=self.context();source=dict(self.sources[TEST],sha256='f'*64)
        with self.assertRaisesRegex(b.Rejected,'SOURCE_NOT_IN_OWN_EXECUTION_RECEIPT'):b.additional_node_execution(source,b.ref(self.raw),context,self.f.expected,self.f.base)
    def test_additional_node_cannot_use_a_direct_non_composite_layer(self):
        self.context()
        with self.assertRaisesRegex(b.Rejected,'REQUIRES_EXPLICIT_COMPOSITE_SCOPE'):b.additional_node_execution(self.sources[TEST],b.ref(self.raw),None,self.f.expected,self.f.base)
    def make_catalog(self,old_adapter=False):
        f=self.f;self.expected=f.write('expected.json',f.expected);self.out=f.base/'catalog';self.out.mkdir(exist_ok=True)
        (self.out/'METHOD-LOCATORS.json').write_text('{"selectors":[]}');self.slots={**self.identity,**{k:[] for k in ('criteria','findings','clauses','verificationChecks')}};(self.out/'ASSESSMENT-SLOTS.json').write_text(json.dumps(self.slots))
        def adapter(p):return {'evidence':b.ref(p),'recordsPointer':'/records','statusPointer':'/status','namePointer':'/name','sourcePathPointer':'/source','passedValue':'PASSED'}
        self.config=f.write('config.json',{**self.identity,'layers':[{'id':'governance','receipt':str(self.wp),'jsonRecordAdapters':[adapter(self.productraw),adapter(self.oldraw if old_adapter else self.raw)]}]})
        argv=[sys.executable,'-B',str(f.root/f.toolpaths[0]),'catalog','--source-identity',str(self.expected),'--out',str(self.out),'--config',str(self.config)]
        run=subprocess.run(argv,cwd=f.root,capture_output=True,text=True)
        return run
    def bind(self,catalog):
        f=self.f;cp=self.out/'CURRENT-NODE-CATALOG.json';cp.write_text(json.dumps(catalog));source=next(x for x in f.expected['additionalExecutionInputs'] if x['path']==TEST)
        plan=f.write('plan.json',{**self.identity,'reviewer':'synthetic','rows':[{'id':'synthetic','rowKind':'criteria','proofSelections':[],'selectedMethods':[{'kind':'method','sourcePath':TEST,'candidateSourceSha256':source['sha256'],'method':'test_scope','layer':'governance','plannedRole':'supporting','scope':'synthetic ownership admission only','evidenceBoundary':'no product or future-D execution claim'}]}]})
        blockers=f.write('blockers.json',{**self.identity,'reviewer':'synthetic','reviewBoundary':'synthetic only','rows':[]});self.target=f.base/('bindings-'+str(len(list(f.base.glob('bindings-*')))))
        argv=[sys.executable,'-B',str(f.root/f.toolpaths[1]),'--expected-source',str(self.expected),'--catalog',str(cp),'--plan',str(plan),'--blockers',str(blockers),'--out',str(self.target)]
        return subprocess.run(argv,cwd=f.root,capture_output=True,text=True)
    def test_actual_catalog_and_binder_join_real_own_raw_and_keep_both_scopes(self):
        run=self.make_catalog();self.assertEqual(run.returncode,0,run.stderr);catalog=b.load(self.out/'CURRENT-NODE-CATALOG.json');layer=catalog['layers'][0];self.assertEqual(layer['parent'],b.ref(self.pp));self.assertEqual(layer['toolExecutions'],[b.ref(self.f.rp)])
        n=next(n for n in catalog['nodes'] if n['sourcePath']==TEST);self.assertEqual(n['runId'],self.f.receipt['runId']);self.assertNotEqual(n['runId'],layer['runId']);self.assertEqual(n['additionalExecutionReceipt'],b.ref(self.f.rp));run=self.bind(catalog);self.assertEqual(run.returncode,0,run.stderr);self.assertEqual(b.load(self.target/'PROPOSED-METHOD-BINDINGS.json')['summary']['proposedSelections'],1)
    def test_actual_catalog_rejects_the_old22_adapter_even_with_unchanged_test_sha(self):
        run=self.make_catalog(old_adapter=True);self.assertNotEqual(run.returncode,0);self.assertIn('RAW_NOT_IN_OWN_EXECUTION_RECEIPT',run.stderr)
    def test_actual_binder_independently_rejects_relabelled_old_raw(self):
        run=self.make_catalog();self.assertEqual(run.returncode,0,run.stderr);catalog=b.load(self.out/'CURRENT-NODE-CATALOG.json');n=next(n for n in catalog['nodes'] if n['sourcePath']==TEST);n['evidence']=b.ref(self.oldraw);run=self.bind(catalog);self.assertNotEqual(run.returncode,0);self.assertIn('RAW_NOT_IN_OWN_EXECUTION_RECEIPT',run.stderr)
    def test_actual_binder_rejects_wrong_run_identity_or_receipt_metadata(self):
        run=self.make_catalog();self.assertEqual(run.returncode,0,run.stderr);original=b.load(self.out/'CURRENT-NODE-CATALOG.json')
        for field,value in [('runId',self.parent['runId']),('executionSourceIdentity',self.identity),('productSourceIdentity',self.f.expected['derivationSourceIdentity']),('actualExecutionReceipt',b.ref(self.pp)),('additionalExecutionReceipt',b.ref(self.pp))]:
            with self.subTest(field=field):
                catalog=copy.deepcopy(original);next(n for n in catalog['nodes'] if n['sourcePath']==TEST)[field]=value;run=self.bind(catalog);self.assertNotEqual(run.returncode,0);self.assertIn('EXECUTION_SCOPE_DISAGREES',run.stderr)
    def test_actual_pending_assembly_preserves_execution_scope_and_receipt_publication(self):
        run=self.make_catalog();self.assertEqual(run.returncode,0,run.stderr);catalog=b.load(self.out/'CURRENT-NODE-CATALOG.json');n=next(n for n in catalog['nodes'] if n['sourcePath']==TEST)
        self.slots['criteria']=[{'id':'synthetic','engineeringReason':'synthetic proof preservation only','reviewedBy':'synthetic','proofSelections':[{'nodeId':n['nodeId'],'scope':'synthetic scope','role':'supporting'}]}];(self.out/'ASSESSMENT-SLOTS.json').write_text(json.dumps(self.slots));config=b.load(self.config);config['artifactDestinations']={str(p.resolve()):'docs/synthetic/'+p.name for p in [self.raw,self.f.rp]};self.config.write_text(json.dumps(config))
        argv=[sys.executable,'-B',str(self.f.root/self.f.toolpaths[0]),'assemble-pending','--source-identity',str(self.expected),'--out',str(self.out),'--config',str(self.config)];run=subprocess.run(argv,cwd=self.f.root,capture_output=True,text=True);self.assertEqual(run.returncode,0,run.stderr);m=b.load(self.out/'EXECUTION-MANIFEST-CANDIDATE.json');proof=m['criteria'][0]['proofs'][0]
        self.assertEqual(proof['runId'],self.f.receipt['runId']);self.assertEqual(proof['productSourceIdentity'],self.identity);self.assertEqual(proof['executionSourceIdentity'],self.f.expected['derivationSourceIdentity']);self.assertEqual(proof['actualExecutionReceipt'],{'path':'docs/synthetic/receipt.json','sha256':b.ref(self.f.rp)['sha256']});self.assertEqual(proof['additionalExecutionReceipt'],proof['actualExecutionReceipt']);self.assertEqual(m['status'],'PENDING');self.assertFalse(m['engineeringClosureClaimMade'])
        saved=self.f.base/'first-assembly';saved.mkdir();(saved/'manifest.json').write_text(json.dumps(m));damaged=copy.deepcopy(catalog);next(x for x in damaged['nodes'] if x['sourcePath']==TEST)['evidence']=b.ref(self.oldraw);(self.out/'CURRENT-NODE-CATALOG.json').write_text(json.dumps(damaged));run=subprocess.run(argv,cwd=self.f.root,capture_output=True,text=True);self.assertEqual(run.returncode,0,run.stderr);self.assertEqual(b.load(self.out/'EXECUTION-MANIFEST-CANDIDATE.json')['criteria'][0]['proofs'],[]);self.assertTrue(any('RAW_NOT_IN_OWN_EXECUTION_RECEIPT' in x['problem'] for x in b.load(self.out/'ASSEMBLY-BLOCKERS.json')['issues']))


class ProductRawScopeAndLegacyAdditional(unittest.TestCase):
    def setUp(self):
        self.c=CompositeScopes();self.c.setUp();self.addCleanup(self.c.doCleanups)
    def catalog(self):
        run=self.c.make_catalog();self.assertEqual(run.returncode,0,run.stderr);return b.load(self.c.out/'CURRENT-NODE-CATALOG.json')
    def rerun_catalog(self):
        c=self.c;argv=[sys.executable,'-B',str(c.f.root/c.f.toolpaths[0]),'catalog','--source-identity',str(c.expected),'--out',str(c.out),'--config',str(c.config)]
        return subprocess.run(argv,cwd=c.f.root,capture_output=True,text=True)
    def runtime_adapter(self,path):
        return {'evidence':b.ref(path),'sourcePath':fixtures.JAVA,'name':'explicit_runtime_structure','assertions':[{'pointer':'/records/0/status','expected':'PASSED'}]}
    def test_original_parent_receipt_itself_is_a_product_reference(self):
        c=self.c;scope=b.product_node_execution(b.ref(c.pp),c.context(),c.f.expected,c.f.base);self.assertEqual(scope['runId'],c.parent['runId'])
        c.f.receipt['evidence'].append(b.ref(c.pp));c.sync()
        with self.assertRaisesRegex(b.Rejected,'RAW_ARTIFACT_OWNERSHIP_AMBIGUOUS'):c.context()
    def test_exact_measured_inventory_reference_is_a_product_reference(self):
        c=self.c;c.parent['sourceInventory']=b.ref(c.f.ip);c.sync();context=c.context();scope=b.product_node_execution(b.ref(c.f.ip),context,c.f.expected,c.f.base);self.assertEqual(scope['executionSourceIdentity'],c.identity);self.assertIn((str(c.f.ip.resolve()),b.ref(c.f.ip)['sha256']),context['productEvidenceKeys'])
        c.f.receipt['evidence'].append(b.ref(c.f.ip));c.sync()
        with self.assertRaisesRegex(b.Rejected,'RAW_ARTIFACT_OWNERSHIP_AMBIGUOUS'):c.context()
    def test_wrong_inventory_reference_cannot_authorize_a_tool_raw_as_product(self):
        c=self.c;c.parent['sourceInventory']=b.ref(c.raw);c.sync()
        with self.assertRaisesRegex(b.Rejected,'PRODUCT_NODE_RAW_NOT_IN_ORIGINAL_PARENT'):b.product_node_execution(b.ref(c.raw),c.context(),c.f.expected,c.f.base)
    def test_original_parent_runtime_structured_raw_is_admitted(self):
        catalog=self.catalog();c=self.c;cfg=b.load(c.config);cfg['layers'][0]['structuredRecordAdapters']=[self.runtime_adapter(c.productraw)];c.config.write_text(json.dumps(cfg));run=self.rerun_catalog();self.assertEqual(run.returncode,0,run.stderr)
        n=next(n for n in b.load(c.out/'CURRENT-NODE-CATALOG.json')['nodes'] if n['name']=='explicit_runtime_structure');self.assertEqual(n['runId'],c.parent['runId']);self.assertEqual(n['executionSourceIdentity'],c.identity);self.assertTrue(n['admissibleAfterIndependentScopeReview'])
    def test_catalog_refuses_derivation_raw_masquerading_as_runtime_structure(self):
        self.catalog();c=self.c;cfg=b.load(c.config);cfg['layers'][0]['structuredRecordAdapters']=[self.runtime_adapter(c.raw)];c.config.write_text(json.dumps(cfg));run=self.rerun_catalog();self.assertNotEqual(run.returncode,0);self.assertIn('PRODUCT_NODE_RAW_NOT_IN_ORIGINAL_PARENT',run.stderr)
    def disguised_catalog(self):
        catalog=self.catalog();c=self.c;n=next(n for n in catalog['nodes'] if n['sourcePath']==TEST);source=next(x for x in c.f.inventory['files'] if x['path']==fixtures.JAVA)
        n.update(sourcePath=fixtures.JAVA,source={'path':fixtures.JAVA,'sha256':source['sha256']},sourceAuthorityScope='RUNTIME_SOURCE_INVENTORY',runId=c.parent['runId'],productSourceIdentity=c.identity,executionSourceIdentity=c.identity,actualExecutionReceipt=b.ref(c.pp));n.pop('additionalExecutionReceipt',None);return catalog,n
    def test_binder_repeats_runtime_raw_membership_even_for_unselected_nodes(self):
        catalog,node=self.disguised_catalog();run=self.c.bind(catalog);self.assertNotEqual(run.returncode,0);self.assertIn('PRODUCT_NODE_RAW_NOT_IN_ORIGINAL_PARENT',run.stderr)
    def assemble(self,catalog,node):
        c=self.c;(c.out/'CURRENT-NODE-CATALOG.json').write_text(json.dumps(catalog));c.slots['criteria']=[{'id':'synthetic','engineeringReason':'exact receipt ownership only','reviewedBy':'synthetic','proofSelections':[{'nodeId':node['nodeId'],'scope':'synthetic scope','role':'supporting'}]}];(c.out/'ASSESSMENT-SLOTS.json').write_text(json.dumps(c.slots));cfg=b.load(c.config);cfg['artifactDestinations']={str(p.resolve()):'docs/synthetic/'+p.name for p in ([c.raw,c.productraw,c.f.rp,c.pp] if c.f.expected.get('derivationSourceIdentity') is not None else [c.raw,c.productraw])};c.config.write_text(json.dumps(cfg))
        argv=[sys.executable,'-B',str(c.f.root/c.f.toolpaths[0]),'assemble-pending','--source-identity',str(c.expected),'--out',str(c.out),'--config',str(c.config)];run=subprocess.run(argv,cwd=c.f.root,capture_output=True,text=True);self.assertEqual(run.returncode,0,run.stderr);return b.load(c.out/'EXECUTION-MANIFEST-CANDIDATE.json'),b.load(c.out/'ASSEMBLY-BLOCKERS.json')
    def test_assembly_independently_refuses_derivation_raw_disguised_as_runtime(self):
        catalog,node=self.disguised_catalog();manifest,blockers=self.assemble(catalog,node);self.assertEqual(manifest['criteria'][0]['proofs'],[]);self.assertTrue(any('PRODUCT_NODE_RAW_NOT_IN_ORIGINAL_PARENT' in x['problem'] for x in blockers['issues']))
    def test_assembly_keeps_legitimate_original_parent_runtime_scope(self):
        catalog=self.catalog();node=next(x for x in catalog['nodes'] if x['sourcePath']==fixtures.JAVA);manifest,blockers=self.assemble(catalog,node);proof=manifest['criteria'][0]['proofs'][0];self.assertEqual(proof['runId'],self.c.parent['runId']);self.assertEqual(proof['executionSourceIdentity'],self.c.identity);self.assertFalse(blockers['issues'])
    def same_head(self):
        c=self.c;f=c.f;head=f.git('rev-parse','HEAD');tree=f.git('rev-parse','HEAD^{tree}');c.identity={'sourceHead':head,'sourceTree':tree,'sourceInventorySha256':b.ref(f.ip)['sha256']};f.expected.update(sourceHead=head,sourceTree=tree);f.expected.pop('derivationSourceIdentity');f.expected['identityEvidence']=b.ref(f.write('same-head-identity.json',{k:f.expected[k] for k in ('sourceHead','sourceTree','identityScope')}));c.parent.update(c.identity,evidence=[b.ref(c.oldraw),b.ref(c.productraw),b.ref(c.raw)]);c.pp=f.write('parent.json',c.parent);c.wp=c.pp
        for extra in f.expected['additionalExecutionInputs']:extra['executionReceipt']=b.ref(c.pp)
    def test_same_head_executed_additional_source_uses_original_parent_without_D_wrapper(self):
        self.same_head();catalog=self.catalog();c=self.c;n=next(x for x in catalog['nodes'] if x['sourcePath']==TEST);self.assertEqual(n['runId'],c.parent['runId']);self.assertNotIn('actualExecutionReceipt',n);self.assertNotIn('executionSourceIdentity',n);run=c.bind(catalog);self.assertEqual(run.returncode,0,run.stderr);self.assertEqual(b.load(c.target/'PROPOSED-METHOD-BINDINGS.json')['summary']['proposedSelections'],1)
        # No publication destination for the parent receipt is necessary in the
        # unchanged same-Head protocol; only the actual selected raw is published.
        manifest,blockers=self.assemble(catalog,n);proof=manifest['criteria'][0]['proofs'][0];self.assertNotIn('actualExecutionReceipt',proof);self.assertNotIn('additionalExecutionReceipt',proof);self.assertFalse(blockers['issues'])
    def test_same_head_additional_source_still_refuses_wrong_parent_run(self):
        self.same_head();catalog=self.catalog();next(x for x in catalog['nodes'] if x['sourcePath']==TEST)['runId']='wrong-original-run';run=self.c.bind(catalog);self.assertEqual(run.returncode,0,run.stderr);proposal=b.load(self.c.target/'PROPOSED-METHOD-BINDINGS.json');self.assertEqual(proposal['summary']['proposedSelections'],0);self.assertIn('NODE_PARENT_RUN_MISMATCH',proposal['summary']['problems'])
    def test_same_head_additional_unregistered_raw_fails_binder_and_direct_assembly(self):
        self.same_head();catalog=self.catalog();c=self.c;twin=c.f.base/'unregistered-copy.json';twin.write_bytes(c.raw.read_bytes());n=next(x for x in catalog['nodes'] if x['sourcePath']==TEST);n['evidence']=b.ref(twin);run=c.bind(catalog);self.assertEqual(run.returncode,0,run.stderr);proposal=b.load(c.target/'PROPOSED-METHOD-BINDINGS.json');self.assertEqual(proposal['summary']['proposedSelections'],0);self.assertIn('NODE_EVIDENCE_NOT_REGISTERED_ON_PARENT',proposal['summary']['problems']);manifest,blockers=self.assemble(catalog,n);self.assertEqual(manifest['criteria'][0]['proofs'],[]);self.assertTrue(any('not an original parent artifact' in x['problem'] for x in blockers['issues']))

if __name__=='__main__':unittest.main()
