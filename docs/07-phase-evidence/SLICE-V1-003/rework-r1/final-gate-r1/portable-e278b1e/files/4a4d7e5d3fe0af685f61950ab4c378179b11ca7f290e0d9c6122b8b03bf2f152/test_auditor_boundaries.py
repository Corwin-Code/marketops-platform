import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('row_audit',HERE/'audit_pending_rows.py')
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)

class RowAuditBoundaryTest(unittest.TestCase):
    def test_fixed_authority_reconstructs_exact_200_22_115_5(self):
        r=a.build_authorities((a.ROOT/a.AUTHORITY['contract'][0]).read_text(),a.load(a.ROOT/a.AUTHORITY['frozen'][0]))
        self.assertEqual([len(r[s]) for s in a.SECTIONS],[200,22,115,5])
        self.assertEqual(r['clauses']['S3-DR-001:REQUIRED_REWORK:1']['frozenJsonPointer'],'/findings/0/required_rework/0')
    def test_duplicate_row_hidden_in_grouped_document_rejected(self):
        self.assertRaises(a.Rejected,a.all_rows,{'clauses':[{'id':'a'},{'id':'a'}]})
    def test_wrong_115_clause_set_rejected_even_equal_count(self):
        expected={f'c{i}' for i in range(115)};rows=[{'id':x} for x in expected];rows[0]['id']='outsider'
        self.assertRaises(a.Rejected,a.indexed,rows,expected,'CLAUSE')
    def test_count_114_rejected(self):
        self.assertRaises(a.Rejected,a.indexed,[{'id':str(i)} for i in range(114)],{str(i) for i in range(115)},'CLAUSE')
    def test_empty_or_unattributed_reason_rejected(self):
        for field in ('engineeringReason','reviewedBy','proofLimits'):
            row={k:'specific synthetic value' for k in ('engineeringReason','reviewedBy','proofLimits')};row[field]=' '
            self.assertRaises(a.Rejected,a.check_row_reason,row)
    def test_no_false_boolean_integer_equivalence(self):
        self.assertFalse(a.same_json({'x':[True]},{'x':[1]}))
    def test_json_pointer_decodes_exact_slash_and_tilde(self):
        self.assertEqual(a.pointer({'a/b':{'~':[42]}},'/a~1b/~0/0'),42)
    def test_invalid_negative_and_escape_pointer_rejected(self):
        for p in ('/-1','/~2','/00'):
            self.assertRaises(a.Rejected,a.pointer,[1],p)
    def test_partial_selected_parameter_expansion_rejected(self):
        proposed=[{'nodeId':str(i),'role':'positive','scope':'one exact method'} for i in range(2)]
        self.assertRaises(a.Rejected,a.require_full_expansion,proposed[:1],proposed)
    def test_full_expansion_retains_scope_and_role(self):
        proposed=[{'nodeId':str(i),'role':'positive','scope':'one exact method'} for i in range(2)]
        a.require_full_expansion(proposed,proposed)
        altered=copy.deepcopy(proposed);altered[1]['scope']='larger business conclusion'
        self.assertRaises(a.Rejected,a.require_full_expansion,altered,proposed)
    def test_method_prefix_never_matches_another_method(self):
        selector={'sourcePath':'a.java','layer':'backend_full','method':'scope'}
        self.assertFalse(a.match_method(selector,{'sourcePath':'a.java','layer':'backend_full','kind':'junit','name':'scopeOther[1]'}))
    def fixture_audit(self,directory,xmls):
        audit=a.Audit.__new__(a.Audit);audit.cache={};audit.java_sources={'example.CaseIT':['backend/src/test/java/example/CaseIT.java']};audit.json_records={'backend_full':[]};audit.registered={'backend_full':set()}
        for i,xml in enumerate(xmls):
            p=Path(directory)/f'{i}.xml';p.write_text(xml);audit.registered['backend_full'].add((str(p),a.sha(p)))
        selector={'sourcePath':'backend/src/test/java/example/CaseIT.java','layer':'backend_full','method':'scope'}
        return audit,selector
    def test_original_raw_failed_parameter_cannot_hide_from_catalog_and_proposal(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as d:
            audit,selector=self.fixture_audit(d,['<testsuite name="example.CaseIT"><testcase classname="example.CaseIT" name="scope[1]"/><testcase classname="example.CaseIT" name="scope[2]"><failure/></testcase></testsuite>'])
            self.assertRaises(a.Rejected,audit.raw_method_identities,selector)
    def test_second_registered_raw_report_failed_parameter_is_not_omitted(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as d:
            audit,selector=self.fixture_audit(d,['<testsuite name="example.CaseIT"><testcase classname="example.CaseIT" name="scope[1]"/></testsuite>','<testsuite name="example.CaseIT"><testcase classname="Nested display" name="scope[2]"><error/></testcase></testsuite>'])
            self.assertRaises(a.Rejected,audit.raw_method_identities,selector)
    def test_real_root_suite_resolves_nested_display_without_renaming_raw(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as d:
            audit,selector=self.fixture_audit(d,['<testsuite name="example.CaseIT"><testcase classname="Nested display" name="scope[1]"/><testcase classname="Nested display" name="scope[2]"/></testsuite>'])
            refs=list(audit.registered['backend_full']);before=Path(refs[0][0]).read_bytes();rows=audit.raw_method_identities(selector)
            self.assertEqual(len(rows),2);self.assertEqual({r[2] for r in rows},{'Nested display'});self.assertEqual(Path(refs[0][0]).read_bytes(),before)
    def test_unrelated_display_cannot_be_mapped_by_report_filename(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as d:
            audit,selector=self.fixture_audit(d,['<testsuites><testsuite name="example.CaseIT"><testcase classname="Nested display" name="scope[1]"/></testsuite></testsuites>'])
            self.assertEqual(audit.raw_method_identities(selector),set())
    def test_raw_json_failed_method_expansion_is_adverse_even_without_catalog_node(self):
        audit=a.Audit.__new__(a.Audit);audit.json_records={'governance':[{'sourcePath':'tests/a.py','name':'scope','passed':False}]}
        self.assertRaises(a.Rejected,audit.raw_method_identities,{'sourcePath':'tests/a.py','layer':'governance','method':'scope'})

    def test_one_character_frozen_clause_change_is_rejected(self):
        expected={'frozenExact':'Original criterion.', 'findingId':'S3-DR-001','clauseKind':'VERIFICATION','ordinal':1,'frozenJsonPointer':'/findings/0/verification/0'}
        row=dict(expected,proofLimits='Exact fixture boundary');assembled={'frozenExact':expected['frozenExact'],'findingId':expected['findingId'],'proofLimits':row['proofLimits']}
        a.check_clause(row,expected,assembled)
        row['frozenExact']+=' ';self.assertRaises(a.Rejected,a.check_clause,row,expected,assembled)
    def test_clause_candidate_cannot_substitute_finding_level_text(self):
        expected={'frozenExact':'Original criterion.', 'findingId':'S3-DR-001'};row=dict(expected,proofLimits='Exact fixture boundary')
        self.assertRaises(a.Rejected,a.check_clause,row,expected,dict(expected,frozenExact='Finding passed',proofLimits=row['proofLimits']))
    def d_fixture(self,directory):
        root=Path(directory);source='docs/synthetic_tool_test.py';(root/'docs').mkdir();(root/source).write_text('synthetic helper only\n')
        raw=root/'actual-D-named.json';raw.write_text('{"synthetic":true}\n')
        d={'sourceHead':'a'*40,'sourceTree':'b'*40,'identityScope':'EVIDENCE_DERIVATION_ONLY','appliesToProductSourceHead':a.HEAD,'appliesToProductSourceTree':a.TREE,'sourceInventorySha256':a.INVENTORY_SHA}
        pin={'path':source,'sha256':a.sha(root/source),'sourceHead':d['sourceHead'],'sourceTree':d['sourceTree'],'gitBlobOid':'c'*40,'scope':'EVIDENCE_DERIVATION_ONLY'}
        rp=root/'D-receipt.json';receipt={'kind':'EVIDENCE_DERIVATION_EXECUTION_RECEIPT','scope':'EVIDENCE_DERIVATION_ONLY',**d,'result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED','sourceStable':True,'exitCode':0,'runId':'synthetic-D','executedInputs':[pin.copy()],'evidence':[a.reference(raw)]};rp.write_text(json.dumps(receipt))
        pin['executionReceipt']=a.reference(rp)
        audit=a.Audit.__new__(a.Audit);audit.sources={source:pin};audit.extras={source:pin};audit.expected_base=root;audit.catalog_base=root;audit.derivation=d;audit.cache={};audit.composite_receipts={'governance':{(str(rp.resolve()),a.sha(rp))}}
        node={'source':{'path':source,'sha256':pin['sha256']},'sourcePath':source,'layer':'governance','runId':'synthetic-D','evidence':a.reference(raw),'actualExecutionReceipt':a.reference(rp),'additionalExecutionReceipt':a.reference(rp),'executionSourceIdentity':d,'productSourceIdentity':{'sourceHead':a.HEAD,'sourceTree':a.TREE,'sourceInventorySha256':a.INVENTORY_SHA}}
        return audit,node,root
    def test_D_raw_is_bound_to_its_own_actual_helper_receipt(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as directory:
            audit,node,root=self.d_fixture(directory);old=a.ROOT
            try:a.ROOT=root;audit.validate_source(node)
            finally:a.ROOT=old
    def test_old_22_raw_cannot_borrow_D_receipt_even_when_test_source_hash_matches(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as directory:
            audit,node,root=self.d_fixture(directory);old_raw=root/'old-02e-named.json';old_raw.write_text('{"syntheticOldRun":true}\n');node['evidence']=a.reference(old_raw);old=a.ROOT
            try:
                a.ROOT=root
                with self.assertRaisesRegex(a.Rejected,'D_NODE_RAW_NOT_IN_ACTUAL_D_RECEIPT'):audit.validate_source(node)
            finally:a.ROOT=old
    def test_D_node_cannot_invent_a_different_run_id(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as directory:
            audit,node,root=self.d_fixture(directory);node['runId']='old-product-run';old=a.ROOT
            try:
                a.ROOT=root
                with self.assertRaisesRegex(a.Rejected,'D_NODE_RUN_MISMATCH'):audit.validate_source(node)
            finally:a.ROOT=old

    def test_browser_original_exact_junit_leaf_is_recounted(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as d:
            audit,selector=self.fixture_audit(d,['<testsuite><testcase classname="e2e/a.spec.ts" name="exact leaf"/></testsuite>'])
            audit.registered['browser']=audit.registered.pop('backend_full');audit.junit_sources={'browser':{'e2e/a.spec.ts':'frontend/e2e/a.spec.ts'}}
            rows=audit.raw_method_identities({'layer':'browser','sourcePath':'frontend/e2e/a.spec.ts','method':'exact leaf'})
            self.assertEqual(len(rows),1);self.assertEqual(next(iter(rows))[3],'exact leaf')
    def test_browser_full_junit_name_is_never_rewritten_as_leaf(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as d:
            audit,selector=self.fixture_audit(d,['<testsuite><testcase classname="e2e/a.spec.ts" name="describe › exact leaf"/></testsuite>'])
            audit.registered['browser']=audit.registered.pop('backend_full');audit.junit_sources={'browser':{'e2e/a.spec.ts':'frontend/e2e/a.spec.ts'}}
            self.assertEqual(audit.raw_method_identities({'layer':'browser','sourcePath':'frontend/e2e/a.spec.ts','method':'exact leaf'}),set())

    def runtime_composite_fixture(self,directory):
        audit,node,root=self.d_fixture(directory);audit.extras={}
        raw=root/'original-product-raw.json';raw.write_text('{"originalProductFixture":true}\n')
        parent=root/'original-product-parent.json';parent.write_text(json.dumps({'sourceHead':a.HEAD,'sourceTree':a.TREE,'sourceInventorySha256':a.INVENTORY_SHA,'runId':'product-run','evidence':[a.reference(raw)]}))
        audit.layers={'governance':{'runId':'product-run'}};audit.product_parent_refs={'governance':a.reference(parent)};audit.product_raw_members={'governance':{(str(raw.resolve()),a.sha(raw)),(str(parent.resolve()),a.sha(parent))}}
        node['runId']='product-run';node['executionSourceIdentity']=node['productSourceIdentity'];node['actualExecutionReceipt']=a.reference(parent);node.pop('additionalExecutionReceipt')
        return audit,node,root,raw
    def test_composite_runtime_cannot_borrow_D_raw(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as directory:
            audit,node,root,raw=self.runtime_composite_fixture(directory);old=a.ROOT
            try:
                a.ROOT=root
                with self.assertRaisesRegex(a.Rejected,'PRODUCT_NODE_RAW_NOT_IN_ORIGINAL_PARENT'):audit.validate_source(node)
            finally:a.ROOT=old
    def test_composite_runtime_keeps_original_parent_raw(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as directory:
            audit,node,root,raw=self.runtime_composite_fixture(directory);node['evidence']=a.reference(raw);old=a.ROOT
            try:a.ROOT=root;audit.validate_source(node)
            finally:a.ROOT=old

if __name__=='__main__':unittest.main(verbosity=2)
