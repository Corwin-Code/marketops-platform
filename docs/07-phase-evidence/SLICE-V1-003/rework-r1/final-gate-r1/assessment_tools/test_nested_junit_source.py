"""Synthetic evidence-adapter regression; never product or business verification."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
import propose_method_bindings as b
import test_propose_method_bindings as original

SOURCE='backend/marketops-server/src/test/java/example/ScopeIT.java'
OTHER='backend/marketops-server/src/test/java/other/DifferentIT.java'
DISPLAY='TC-AD-PRIV-103 every sanctioned route is a function, and only those'

class SourceResolution(unittest.TestCase):
    def resolve(self,classname=DISPLAY,suite='example.ScopeIT',extra=()):
        return b.java_source_resolution(classname,suite,{p:{} for p in [SOURCE,*extra]})
    def test_display_class_uses_original_root_suite_only(self):
        r=self.resolve();self.assertEqual(r['sourcePath'],SOURCE);self.assertEqual(r['basis'],'ROOT_TESTSUITE_NAME');self.assertEqual(r['testcaseClassname'],DISPLAY);self.assertIsNone(r['problem'])
    def test_conventional_class_with_or_without_root_stays_direct(self):
        for suite in (None,'example.ScopeIT'):
            with self.subTest(suite=suite):
                r=self.resolve('example.ScopeIT',suite);self.assertEqual(r['sourcePath'],SOURCE);self.assertEqual(r['basis'],'TESTCASE_CLASSNAME')
        self.assertEqual(self.resolve('example.ScopeIT$Inner')['sourcePath'],SOURCE)
    def test_root_and_conventional_class_conflict_refuses(self):
        r=self.resolve('example.ScopeIT','other.DifferentIT',(OTHER,));self.assertIsNone(r['sourcePath']);self.assertEqual(r['problem'],'ROOT_AND_TESTCASE_SOURCE_CONFLICT')
    def test_missing_or_non_java_root_cannot_bind_display(self):
        for suite in (None,'','Display-only suite','missing.UnknownIT'):
            with self.subTest(suite=suite):self.assertIsNone(self.resolve(suite=suite)['sourcePath'])
    def test_unknown_java_testcase_cannot_borrow_another_valid_root(self):
        r=self.resolve('missing.UnknownIT');self.assertIsNone(r['sourcePath']);self.assertEqual(r['problem'],'TESTCASE_JAVA_SOURCE_UNRESOLVED')
    def test_ambiguous_inventory_source_cannot_fallback(self):
        twin='different-module/src/test/java/example/ScopeIT.java'
        for classname in (DISPLAY,'example.ScopeIT'):
            with self.subTest(classname=classname):self.assertEqual(self.resolve(classname,extra=(twin,))['problem'],'AMBIGUOUS_JAVA_SOURCE')
    def test_raw_fields_ordinal_status_and_xml_bytes_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'raw.xml';xml=f'<testsuite name="example.ScopeIT"><testcase classname="{DISPLAY}" name="scope[1]"/><testcase classname="{DISPLAY}" name="scope[2]"><failure/></testcase></testsuite>';p.write_text(xml);before=p.read_bytes()
            rows=b.read_xml(p,{SOURCE:{}});self.assertEqual([(r['class'],r['name'],r['nodeOrdinal'],r['observedResult']) for r in rows],[(DISPLAY,'scope[1]',0,'PASSED'),(DISPLAY,'scope[2]',1,'FAILURE')]);self.assertTrue(all(r['sourcePath']==SOURCE for r in rows));self.assertEqual(p.read_bytes(),before)
    def test_nested_testsuites_container_is_not_an_invented_root_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'raw.xml';p.write_text(f'<testsuites><testsuite name="example.ScopeIT"><testcase classname="{DISPLAY}" name="scope"/></testsuite></testsuites>');self.assertIsNone(b.read_xml(p,{SOURCE:{}})[0]['sourcePath'])

class BinderRawFamily(unittest.TestCase):
    def setUp(self):
        self.f=original.Boundaries();self.f.setUp();self.addCleanup(self.f.doCleanups)
    def raw(self,classes=(DISPLAY,DISPLAY),root='example.ScopeIT',names=('scope[1]','scope[2]'),failed=()):
        top=ET.Element('testsuite',{} if root is None else {'name':root})
        for i,(classname,name) in enumerate(zip(classes,names)):
            node=ET.SubElement(top,'testcase',{'classname':classname,'name':name})
            if i in failed:ET.SubElement(node,'failure')
        self.f.xml.write_bytes(ET.tostring(top));self.f.receipt['evidence']=[b.ref(self.f.xml)];self.f.sync_receipt()
        raws=b.read_xml(self.f.xml,{SOURCE:{}})
        for i,n in enumerate(self.f.catalog['nodes']):
            n.update({k:raws[i][k] for k in ('class','name','nodeOrdinal','observedResult')});n['evidence']=b.ref(self.f.xml);n['sourceResolution']=raws[i]['sourceResolution']
        return raws
    def test_display_named_family_binds_each_original_parameter(self):
        self.raw();out=self.f.run_bind();self.assertEqual(out['summary']['proposedSelections'],2);self.assertFalse(out['automaticMergePerformed'])
    def test_forged_catalog_source_cannot_override_missing_root(self):
        self.raw(root=None);self.assertIn('RAW_JUNIT_SOURCE_AUTHORITY_MISMATCH',self.f.problems())
    def test_forged_catalog_root_resolution_metadata_is_rejected(self):
        self.raw();self.f.catalog['nodes'][0]['sourceResolution']['rootSuiteName']='other.DifferentIT';self.assertIn('CATALOG_SOURCE_RESOLUTION_DIFFERS_FROM_RAW_JUNIT',self.f.problems())
    def test_omitted_display_parameter_still_blocks_whole_family(self):
        self.raw();self.f.catalog['nodes'].pop();self.assertIn('RAW_METHOD_INSTANCE_MISSING_FROM_CATALOG',self.f.problems())
    def test_hidden_same_method_in_second_nested_class_is_ambiguous(self):
        self.raw(classes=(DISPLAY,'another nested display'),names=('scope','scope'));self.f.catalog['nodes'].pop();problems=self.f.problems();self.assertIn('AMBIGUOUS_RAW_METHOD_CLASSES',problems);self.assertIn('REGISTERED_RAW_METHOD_INSTANCE_MISSING_FROM_CATALOG',problems)
    def test_hidden_second_suite_report_failure_cannot_be_missed_by_display_class(self):
        self.raw();second=self.f.d/'second.xml';second.write_text('<testsuite name="example.ScopeIT"><testcase classname="different nested display" name="scope[3]"><failure/></testcase></testsuite>');self.f.receipt['evidence'].append(b.ref(second));self.f.sync_receipt();problems=self.f.problems();self.assertIn('REGISTERED_RAW_METHOD_HAS_FAILED_SKIPPED_OR_INCOMPLETE_INSTANCE',problems);self.assertIn('AMBIGUOUS_RAW_METHOD_CLASSES',problems)
    def test_duplicate_original_display_instance_stays_ambiguous(self):
        self.raw(names=('scope','scope'));self.assertIn('AMBIGUOUS_REPEATED_METHOD_INSTANCE',self.f.problems())
    def test_same_display_in_different_source_is_not_silently_merged(self):
        self.raw();second=self.f.d/'second.xml';second.write_text(f'<testsuite name="other.DifferentIT"><testcase classname="{DISPLAY}" name="scope[3]"/></testsuite>');self.f.receipt['evidence'].append(b.ref(second));self.f.sync_receipt();self.assertIn('REGISTERED_RAW_SOURCE_AUTHORITY_UNRESOLVED',self.f.problems())

class BrowserRawFamily(unittest.TestCase):
    def setUp(self):
        self.f=original.Boundaries();self.f.setUp();self.addCleanup(self.f.doCleanups)
        source='frontend/marketops-console/e2e/manual-workflow.spec.ts';name='OZON manual execution preserves the original Outcome history';classname='manual-workflow.spec.ts'
        self.f.source=source;self.f.write('inventory.json',{'files':[{'path':source,'sha256':self.f.sh}]})
        identity=dict(self.f.identity,sourceInventorySha256=b.ref(self.f.inventory)['sha256']);self.f.identity=identity
        self.f.write('expected.json',{'sourceHead':identity['sourceHead'],'sourceTree':identity['sourceTree'],'inventory':b.ref(self.f.inventory),'disposition':'UNDER_VERIFICATION'})
        self.f.write('blockers.json',{**identity,'reviewer':'synthetic','reviewBoundary':'not product proof','rows':[]})
        self.f.xml.write_text('<testsuites><testsuite name="manual-workflow.spec.ts"><testcase classname="'+classname+'" name="'+name+'"/></testsuite></testsuites>')
        self.f.receipt.update(identity,evidence=[b.ref(self.f.xml)]);self.f.catalog.update(identity);self.f.catalog['layers'][0]['id']='browser';self.f.sync_receipt()
        self.f.catalog['nodes']=self.f.catalog['nodes'][:1]
        self.f.catalog['nodes'][0].update(identity,layer='browser',sourcePath=source,source={'path':source,'sha256':self.f.sh},name=name,**{'class':classname},evidence=b.ref(self.f.xml))
        self.f.plan.update(identity);self.f.plan['rows'][0]['selectedMethods'][0].update(layer='browser',sourcePath=source,method=name)
    def test_explicit_browser_source_keeps_original_non_java_junit_identity(self):
        result=self.f.run_bind();self.assertEqual(result['summary']['proposedSelections'],1);self.assertFalse(result['automaticMergePerformed'])
    def test_forged_browser_ordinal_still_refuses(self):
        self.f.catalog['nodes'][0]['nodeOrdinal']=9;self.assertIn('CATALOG_NODE_DIFFERS_FROM_RAW_JUNIT',self.f.problems())

class AssemblerCatalog(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='slice3-nested-catalog-',dir='/tmp');self.addCleanup(self.tmp.cleanup);self.base=Path(self.tmp.name);self.repo=self.base/'repository';self.repo.mkdir();(self.repo/'bootstrap-manifest.json').write_text('{}')
        self.here=Path(__file__).resolve().parent;self.tools=self.repo/'assessment_tools';self.tools.mkdir()
        for name in ('assemble_assessment_with_structured.py','propose_method_bindings.py'):shutil.copyfile(self.here/name,self.tools/name)
        self.sources=[SOURCE];self.head='b'*40;self.tree='c'*40
    def write(self,path,value):path.write_text(json.dumps(value));return path
    def catalog(self,root='example.ScopeIT',classes=(DISPLAY,),names=('scope',),extra=(),omit_helper=False,bad_helper=False):
        paths=[SOURCE,*extra]
        for path in paths:
            p=self.repo/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text('/* explicit synthetic test-source identity only */')
        files=[{'path':path,'sha256':b.digest((self.repo/path).read_bytes())} for path in paths]
        files.append({'path':'assessment_tools/assemble_assessment_with_structured.py','sha256':b.digest((self.tools/'assemble_assessment_with_structured.py').read_bytes())})
        if not omit_helper:files.append({'path':'assessment_tools/propose_method_bindings.py','sha256':'f'*64 if bad_helper else b.digest((self.tools/'propose_method_bindings.py').read_bytes())})
        inv=self.write(self.base/'inventory.json',{'files':files});identity=self.write(self.base/'identity.json',{'sourceHead':self.head,'sourceTree':self.tree,'identityScope':'WORKTREE_WITH_EXACT_SOURCE_MANIFEST'});expected=self.write(self.base/'expected.json',{'sourceHead':self.head,'sourceTree':self.tree,'identityScope':'WORKTREE_WITH_EXACT_SOURCE_MANIFEST','identityEvidence':b.ref(identity),'inventory':b.ref(inv),'disposition':'SYNTHETIC_TOOL_CHECK_ONLY','additionalExecutionInputs':[]})
        raw=self.base/'raw.xml';top=ET.Element('testsuite',{} if root is None else {'name':root})
        for c,n in zip(classes,names):ET.SubElement(top,'testcase',{'classname':c,'name':n})
        raw.write_bytes(ET.tostring(top));before=raw.read_bytes()
        receipt=self.write(self.base/'receipt.json',{'id':'backend_full','sourceHead':self.head,'sourceTree':self.tree,'sourceInventorySha256':b.ref(inv)['sha256'],'runId':'SYNTHETIC_TOOL_ONLY','result':'COMMAND_SUCCEEDED_REVIEW_REQUIRED','sourceStable':True,'exitCode':0,'finishedAt':'synthetic','evidence':[b.ref(raw)]})
        config=self.write(self.base/'config.json',{'sourceHead':self.head,'sourceTree':self.tree,'layers':[{'id':'backend_full','receipt':str(receipt),'junitSourceByClass':{DISPLAY:SOURCE}}]})
        out=self.base/'out';out.mkdir();self.write(out/'METHOD-LOCATORS.json',{'selectors':[]});self.write(out/'ASSESSMENT-SLOTS.json',{'sourceHead':self.head,'sourceTree':self.tree,'sourceInventory':b.ref(inv),**{k:[] for k in ['criteria','findings','clauses','verificationChecks']}})
        run=subprocess.run([sys.executable,'-B',str(self.tools/'assemble_assessment_with_structured.py'),'catalog','--source-identity',str(expected),'--out',str(out),'--config',str(config)],cwd=self.repo,capture_output=True,text=True)
        self.assertEqual(raw.read_bytes(),before)
        result=json.loads((out/'CURRENT-NODE-CATALOG.json').read_text()) if (out/'CURRENT-NODE-CATALOG.json').exists() else None
        return run,result,raw
    def test_actual_catalog_preserves_raw_display_and_ordinal_with_bound_suite_source(self):
        run,result,raw=self.catalog(classes=(DISPLAY,DISPLAY),names=('scope[1]','scope[2]'));self.assertEqual(run.returncode,0,run.stderr);self.assertEqual(len(result['nodes']),2)
        for i,n in enumerate(result['nodes']):self.assertEqual(n['class'],DISPLAY);self.assertEqual(n['nodeOrdinal'],i);self.assertEqual(n['sourcePath'],SOURCE);self.assertEqual(n['evidence']['sha256'],b.ref(raw)['sha256']);self.assertTrue(n['admissibleAfterIndependentScopeReview'])
        self.assertFalse(result['engineeringClosureClaimMade'])
    def test_actual_catalog_root_class_conflict_stays_unbound(self):
        run,result,_=self.catalog(root='other.DifferentIT',classes=('example.ScopeIT',),extra=(OTHER,));self.assertEqual(run.returncode,0,run.stderr);self.assertIsNone(result['nodes'][0]['source']);self.assertFalse(result['nodes'][0]['admissibleAfterIndependentScopeReview'])
    def test_actual_catalog_missing_root_ignores_display_override_config(self):
        run,result,_=self.catalog(root=None);self.assertEqual(run.returncode,0,run.stderr);self.assertIsNone(result['nodes'][0]['source']);self.assertFalse(result['nodes'][0]['admissibleAfterIndependentScopeReview'])
    def test_actual_catalog_ambiguous_source_stays_unbound(self):
        run,result,_=self.catalog(extra=('second-module/src/test/java/example/ScopeIT.java',));self.assertEqual(run.returncode,0,run.stderr);self.assertFalse(result['nodes'][0]['admissibleAfterIndependentScopeReview'])
    def test_actual_catalog_duplicate_exact_node_stays_unbound(self):
        run,result,_=self.catalog(classes=(DISPLAY,DISPLAY),names=('scope','scope'));self.assertEqual(run.returncode,0,run.stderr);self.assertTrue(all(not n['admissibleAfterIndependentScopeReview'] for n in result['nodes']))
    def test_actual_catalog_repeated_nested_method_stays_unbound(self):
        run,result,_=self.catalog(classes=(DISPLAY,'different nested display'),names=('scope','scope'));self.assertEqual(run.returncode,0,run.stderr);self.assertTrue(all(n['ambiguity']=='AMBIGUOUS_NESTED_METHOD_CLASSES' and not n['admissibleAfterIndependentScopeReview'] for n in result['nodes']))
    def test_actual_catalog_conventional_without_root_is_unchanged(self):
        run,result,_=self.catalog(root=None,classes=('example.ScopeIT',));self.assertEqual(run.returncode,0,run.stderr);self.assertTrue(result['nodes'][0]['admissibleAfterIndependentScopeReview']);self.assertEqual(result['nodes'][0]['sourceResolution']['basis'],'TESTCASE_CLASSNAME')
    def test_actual_catalog_unmeasured_shared_resolver_is_refused(self):
        run,result,_=self.catalog(omit_helper=True);self.assertNotEqual(run.returncode,0);self.assertIsNone(result);self.assertIn('unmeasured or changed',run.stderr)
    def test_actual_catalog_changed_shared_resolver_is_refused(self):
        run,result,_=self.catalog(bad_helper=True);self.assertNotEqual(run.returncode,0);self.assertIsNone(result);self.assertIn('unmeasured or changed',run.stderr)

if __name__=='__main__':unittest.main()
