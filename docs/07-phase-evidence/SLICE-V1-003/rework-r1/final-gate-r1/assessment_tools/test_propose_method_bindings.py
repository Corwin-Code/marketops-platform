import copy
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import propose_method_bindings as b

class Boundaries(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='slice3-binder-synthetic-');self.addCleanup(self.tmp.cleanup)
        self.d=Path(self.tmp.name);self.source='backend/marketops-server/src/test/java/example/ScopeIT.java'
        self.sh='a'*64;self.identity={'sourceHead':'b'*40,'sourceTree':'c'*40,'sourceInventorySha256':''}
        self.inventory=self.write('inventory.json',{'files':[{'path':self.source,'sha256':self.sh}]})
        self.identity['sourceInventorySha256']=b.ref(self.inventory)['sha256']
        self.expected=self.write('expected.json',{'sourceHead':self.identity['sourceHead'],'sourceTree':self.identity['sourceTree'],'inventory':b.ref(self.inventory),'disposition':'UNDER_VERIFICATION'})
        self.blockers=self.write('blockers.json',{**self.identity,'reviewer':'synthetic boundary only','reviewBoundary':'not product evidence','rows':[]})
        self.xml=self.d/'report.xml';self.xml.write_text('<testsuite><testcase classname="example.ScopeIT" name="scope[1]"/><testcase classname="example.ScopeIT" name="scope[2]"/></testsuite>')
        self.receipt={'sourceHead':self.identity['sourceHead'],'sourceTree':self.identity['sourceTree'],'sourceInventorySha256':self.identity['sourceInventorySha256'],'runId':'synthetic-run','sourceStable':True,'result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED','exitCode':0,'finishedAt':'synthetic-completed','evidence':[b.ref(self.xml)]}
        self.rp=self.write('receipt.json',self.receipt)
        self.catalog={'kind':'ACTUAL_EXECUTION_NODE_OBSERVATIONS_NOT_CLOSURE',**self.identity,'sourceDisposition':'UNDER_VERIFICATION','issues':[],
            'layers':[{'id':'backend_full','runId':'synthetic-run','receipt':b.ref(self.rp),'exactSourceIdentityMatches':True,'terminalSuccessfulCommandObserved':True}],
            'nodes':[{'nodeId':'node-'+str(i),'kind':'junit','class':'example.ScopeIT','name':f'scope[{i+1}]','nodeOrdinal':i,'sourcePath':self.source,'source':{'path':self.source,'sha256':self.sh},'sourceAuthorityScope':'RUNTIME_SOURCE_INVENTORY','evidence':b.ref(self.xml),'observedResult':'PASSED','layer':'backend_full','runId':'synthetic-run',**self.identity,'terminalSuccessfulCommandObserved':True,'admissibleAfterIndependentScopeReview':True} for i in range(2)]}
        self.plan={**self.identity,'reviewer':'human-plan-synthetic','rows':[{'id':'S3-AC-001','rowKind':'criteria','proofSelections':[],'selectedMethods':[{'kind':'method','layer':'backend_full','sourcePath':self.source,'candidateSourceSha256':self.sh,'method':'scope','plannedRole':'adverse','scope':'exact narrow synthetic scope','evidenceBoundary':'synthetic checks do not execute product'}]}]}
    def write(self,name,obj):
        p=self.d/name;p.write_text(json.dumps(obj));return p
    def run_bind(self):
        return b.execute(self.expected,self.write('catalog.json',self.catalog),[self.write('plan.json',self.plan)],self.blockers)
    def problems(self):return self.run_bind()['rows'][0]['methodResults'][0]['problems']
    def sync_receipt(self):
        self.write('receipt.json',self.receipt);self.catalog['layers'][0]['receipt']=b.ref(self.rp)
    def test_exact_java_all_parameters_proposed(self):
        r=self.run_bind();self.assertEqual(r['summary']['proposedSelections'],2);self.assertFalse(r['automaticMergePerformed']);self.assertEqual(r['rows'][0]['proposedProofSelections'][0]['scope'],'exact narrow synthetic scope')
    def test_wrong_plan_head_rejected(self):
        self.plan['sourceHead']='f'*40
        with self.assertRaisesRegex(b.Rejected,'PLAN_SOURCE'):self.run_bind()
    def test_wrong_catalog_tree_rejected(self):
        self.catalog['sourceTree']='f'*40
        with self.assertRaisesRegex(b.Rejected,'CATALOG_SOURCE'):self.run_bind()
    def test_nonmember_catalog_source_rejected_even_unselected(self):
        self.catalog['nodes'][0]['source']['sha256']='f'*64
        with self.assertRaisesRegex(b.Rejected,'INVENTORY_MEMBER'):self.run_bind()
    def test_selector_wrong_source_sha_unbound(self):
        self.plan['rows'][0]['selectedMethods'][0]['candidateSourceSha256']='f'*64
        self.assertIn('SELECTOR_SOURCE_NOT_EXACT_INVENTORY_MEMBER',self.problems())
    def test_failed_parent_layer_no_proposal(self):
        self.receipt['exitCode']=1;self.sync_receipt();self.assertIn('PARENT_LAYER_NOT_COMPLETED_SUCCESSFULLY',self.problems())
    def test_running_parent_layer_no_proposal(self):
        self.receipt['finishedAt']=None;self.sync_receipt();self.assertIn('PARENT_LAYER_NOT_COMPLETED_SUCCESSFULLY',self.problems())
    def test_wrong_parent_inventory_no_proposal(self):
        self.receipt['sourceInventorySha256']='f'*64;self.sync_receipt();self.assertIn('PARENT_RECEIPT_SOURCE_IDENTITY_MISMATCH',self.problems())
    def test_catalog_failed_instance_blocks_whole_method(self):
        self.catalog['nodes'][1]['observedResult']='FAILURE'
        self.assertIn('METHOD_HAS_FAILED_SKIPPED_OR_INCOMPLETE_INSTANCE',self.problems())
    def test_catalog_skipped_instance_blocks_whole_method(self):
        self.catalog['nodes'][1]['observedResult']='SKIPPED'
        self.assertIn('METHOD_HAS_FAILED_SKIPPED_OR_INCOMPLETE_INSTANCE',self.problems())
    def test_omitted_raw_parameter_blocks_whole_method(self):
        self.catalog['nodes'].pop();self.assertIn('RAW_METHOD_INSTANCE_MISSING_FROM_CATALOG',self.problems())
    def test_omitted_second_report_failure_blocks(self):
        p=self.d/'other.xml';p.write_text('<testsuite><testcase classname="example.ScopeIT" name="scope[3]"><failure/></testcase></testsuite>')
        self.receipt['evidence'].append(b.ref(p));self.sync_receipt()
        self.assertIn('REGISTERED_RAW_METHOD_HAS_FAILED_SKIPPED_OR_INCOMPLETE_INSTANCE',self.problems())
    def test_ambiguous_duplicate_instance_no_proposal(self):
        n=copy.deepcopy(self.catalog['nodes'][0]);n['nodeId']='duplicate';self.catalog['nodes'].append(n)
        self.assertIn('AMBIGUOUS_REPEATED_METHOD_INSTANCE',self.problems())
    def test_duplicate_node_id_rejected(self):
        self.catalog['nodes'][1]['nodeId']=self.catalog['nodes'][0]['nodeId']
        with self.assertRaisesRegex(b.Rejected,'DUPLICATE_OR_ABSENT_NODE'):self.run_bind()
    def test_raw_xml_sha_change_rejected(self):
        self.xml.write_text('<testsuite/>')
        with self.assertRaisesRegex(b.Rejected,'FILE_SHA'):self.run_bind()
    def test_no_historical_fallback(self):
        self.catalog['nodes']=[];self.assertIn('NO_EXACT_CURRENT_METHOD_NODES',self.problems())
    def test_python_and_browser_full_name_only(self):
        for path in ['tests/test_a.py','frontend/e2e/example.spec.ts']:
            selector={'sourcePath':path,'method':'exact name'}
            self.assertTrue(b.method_matches(selector,{'kind':'junit','name':'exact name'}))
            self.assertFalse(b.method_matches(selector,{'kind':'junit','name':'exact name[1]'}))
            self.assertFalse(b.method_matches(selector,{'kind':'junit','name':'exact name(argument)'}))
    def test_blocked_policy_rows_not_proposed(self):
        d=b.load(self.blockers);d['rows']=[{'rowKind':'criteria','id':'S3-AC-001','reason':'current source gap requires re-review'}];self.write('blockers.json',d)
        self.assertIn('EXPLICIT_ROW_REVIEW_BLOCK',self.problems())
    def test_failed_checkpoint_rejected(self):
        self.catalog['sourceDisposition']='FAILED_CHECKPOINT'
        with self.assertRaisesRegex(b.Rejected,'FAILED_CHECKPOINT'):self.run_bind()
    def test_existing_selections_cannot_be_silently_replaced(self):
        self.plan['rows'][0]['proofSelections']=[{'nodeId':'old'}]
        with self.assertRaisesRegex(b.Rejected,'ALREADY_HAS_PROOF'):self.run_bind()
    def test_raw_pointer_type_is_strict(self):
        self.assertFalse(b.json_equal(True,1));self.assertFalse(b.json_equal({'x':True},{'x':1}))
        with self.assertRaises(b.Rejected):b.pointer({'x':[1]},'/x/01')
    def test_unexecuted_additional_source_no_proposal(self):
        expected=b.load(self.expected)
        source=self.source
        self.inventory=self.write('inventory.json',{'files':[]});newsha=b.ref(self.inventory)['sha256']
        expected['inventory']=b.ref(self.inventory);expected['additionalExecutionInputs']=[{'path':source,'sha256':self.sh,'sourceHead':self.identity['sourceHead'],'scope':'EVIDENCE_DERIVATION_ONLY','executionReceipt':None}]
        self.write('expected.json',expected);self.identity['sourceInventorySha256']=newsha
        self.plan.update(self.identity);self.catalog.update(self.identity);self.receipt['sourceInventorySha256']=newsha;self.sync_receipt()
        self.write('blockers.json',{**self.identity,'reviewer':'synthetic','reviewBoundary':'synthetic','rows':[]})
        for n in self.catalog['nodes']:n.update(self.identity);n['sourceAuthorityScope']='ADDITIONAL_DERIVATION_INPUT'
        self.assertIn('ADDITIONAL_SOURCE_NOT_EXECUTED',self.problems())
if __name__=='__main__':unittest.main()
