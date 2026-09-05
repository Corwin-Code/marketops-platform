"""Current closure requires measured evidence; W10 remains immutable history."""
import copy
import re
import shutil
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
from scripts.validation import finalize_slice3_rework_assessment as assessment
from scripts.validate_governance import validate_v1_current_state_text
from scripts.validate_production_readiness import completion_state_violations


class FinalSlice3AssessmentTests(unittest.TestCase):
    def test_pending_current_views_preserve_all_original_text_without_replaying_w10_pass(self):
        outputs = assessment.build_outputs()
        ac = json.loads(outputs[assessment.AC_MATRIX_OUTPUT])
        findings = json.loads(outputs[assessment.FINDING_MATRIX_OUTPUT])
        criteria, frozen, report = assessment.authorities()
        self.assertEqual(criteria, {row['id']: row['criterion'] for row in ac['entries']})
        self.assertEqual(22, len(findings['entries']))
        self.assertEqual(115, sum(len(row['required_rework'])+len(row['required_verification']) for row in findings['entries']))
        for row in findings['entries']:
            self.assertEqual(frozen[row['id']]['required_rework'], row['required_rework'])
            self.assertEqual(frozen[row['id']]['verification'], row['required_verification'])
        if assessment.read(assessment.MANIFEST)['status'] == 'PENDING':
            self.assertFalse(ac['engineering_closure_claim_made'])
            self.assertEqual(5, sum(row['status']=='REWORK_EVIDENCE_PENDING' for row in findings['entries']))
            self.assertFalse(any(row['status']=='VERIFIED' for row in ac['entries']))
            self.assertIn('NOT_PASSED', ac['entries'][-1]['status'])
        self.assertFalse(ac['closure_claim_made'])
        self.assertFalse(ac['production_write_enabled'])
        self.assertEqual(report['verdict'], ac['historical_controller']['verdict'])

    def test_historical_and_normative_bytes_are_read_only(self):
        paths = [assessment.CONTRACT, assessment.FROZEN_MD, assessment.FROZEN_JSON, assessment.DEFERRED]
        paths += [assessment.HISTORY/name for name in assessment.HISTORICAL_HASHES]
        before = {path: assessment.sha256(ROOT/path) for path in paths}
        assessment.build_outputs()
        self.assertEqual(before, {path: assessment.sha256(ROOT/path) for path in paths})
        for name, expected in assessment.HISTORICAL_HASHES.items():
            self.assertEqual(expected, before[assessment.HISTORY/name])
        old = assessment.read(assessment.HISTORY/'ENGINEERING_VERIFICATION.json')
        self.assertEqual(30789, old['capacityBoundary']['criticalP95Milliseconds'])
        self.assertEqual(109169, old['capacityBoundary']['fullSweepMilliseconds'])

    def test_pending_evidence_cannot_claim_closure_production_or_controller_approval(self):
        base = assessment.read(assessment.MANIFEST)
        base.update(status='PENDING',engineeringClosureClaimMade=False,productionWriteEnabled=False,controllerApprovalClaimMade=False)
        for key in ['engineeringClosureClaimMade','productionWriteEnabled','controllerApprovalClaimMade']:
            mutated = copy.deepcopy(base);mutated[key]=True
            with self.subTest(key=key), self.assertRaises(ValueError):
                assessment.validate_execution(mutated, {}, {})

    def test_complete_label_without_required_current_execution_is_rejected(self):
        manifest = {'status':'COMPLETE','productionWriteEnabled':False,'controllerApprovalClaimMade':False,
                    'source':{'sourceHead':'a'*40,'sourceTree':'b'*40,'identityScope':'CLEAN_COMMIT_TREE','inventory':{'path':'unmeasured','sha256':'c'*64}},'layers':[]}
        with patch.object(assessment,'source_inventory',return_value={}):
            with self.assertRaisesRegex(ValueError,'Required verification layers'):
                assessment.validate_execution(manifest, {}, {})

    def test_named_raw_proof_rejects_failure_skip_duplicates_wrong_inventory_and_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'Test.java';source.write_text('class Test {}')
            report=root/'result.xml'
            with patch.object(assessment,'ROOT',root):
                def check(xml, malformed=None):
                    report.write_text(xml)
                    evidence=assessment.reference(Path('result.xml'))
                    proof={'kind':'junit','layer':'backend_full','scope':'exact adverse assertion','class':'Fixture','name':'case',
                           'source':assessment.reference(Path('Test.java')),'evidence':evidence}
                    layers={'backend_full':{'sourceInventorySha256':'d'*64,'evidence':[evidence]}}
                    if malformed:malformed(proof,layers)
                    return assessment.validate_proof(proof,layers,'d'*64)
                check('<testsuite><testcase classname="Fixture" name="case"/></testsuite>')
                for child in ['<failure/>','<error/>','<skipped/>']:
                    with self.subTest(child=child),self.assertRaises(ValueError):
                        check('<testsuite><testcase classname="Fixture" name="case">'+child+'</testcase></testsuite>')
                with self.assertRaises(ValueError):
                    check('<testsuite><testcase classname="Fixture" name="case"/><testcase classname="Fixture" name="case"/></testsuite>')
                with self.assertRaises(ValueError):
                    check('<testsuite><testcase classname="Fixture" name="case"/></testsuite>',lambda p,l:l['backend_full'].update(evidence=[]))
                with self.assertRaises(ValueError):
                    check('<testsuite><testcase classname="Fixture" name="case"/></testsuite>',lambda p,l:source.write_text('changed after test'))

    def complete_phase_fixture(self, directory):
        """Synthetic measurements live only in an isolated temporary repository."""
        root = Path(directory)
        authorities = [assessment.CONTRACT, assessment.FROZEN_MD, assessment.FROZEN_JSON,
                       assessment.DEFERRED, assessment.REPORT,
                       assessment.GATE/'CV-E-MEASUREMENT-RECONCILIATION.json']
        authorities += [assessment.HISTORY/name for name in assessment.HISTORICAL_HASHES]
        for relative in authorities:
            target = root/relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT/relative, target)
        files = ['tests/MeasuredFixture.java', 'backend/marketops-server/pom.xml',
                 'frontend/marketops-console/package.json', 'frontend/marketops-console/package-lock.json']
        for relative in files:
            target = root/relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text('synthetic phase-admission fixture\n')
        inventory = assessment.GATE/'synthetic-source.json'
        (root/inventory).write_bytes(assessment.json_bytes({'files': [assessment.reference(Path(name)) for name in files]}))
        raw = assessment.GATE/'synthetic-result.xml'
        (root/raw).write_text('<testsuite><testcase classname="Synthetic" name="positive"/>'
                             '<testcase classname="Synthetic" name="adverse"/></testsuite>')
        evidence = assessment.reference(raw)
        source = {'sourceHead':'a'*40, 'sourceTree':'b'*40, 'identityScope':'WORKTREE_WITH_EXACT_SOURCE_MANIFEST',
                  'inventory':assessment.reference(inventory)}
        layers = [{'id':name, 'result':'PASS', 'command':'synthetic isolated fixture', 'runId':'synthetic',
                   'scope':'FULL_RELEVANT_VERIFICATION', 'failures':0, 'errors':0, 'skipped':0,
                   'sourceHead':source['sourceHead'], 'sourceTree':source['sourceTree'],
                   'sourceInventorySha256':source['inventory']['sha256'], 'evidence':[evidence]}
                  for name in sorted(assessment.REQUIRED_LAYERS)]
        proofs = [{'kind':'junit', 'layer':'backend_full', 'role':role, 'scope':'Synthetic '+role+' fixture assertion',
                   'source':assessment.reference(Path(files[0])), 'evidence':evidence,
                   'class':'Synthetic', 'name':role} for role in ['positive','adverse']]
        criteria, frozen, _ = assessment.authorities()
        manifest = {'status':'COMPLETE', 'engineeringClosureClaimMade':True, 'productionWriteEnabled':False,
                    'controllerApprovalClaimMade':False, 'source':source, 'layers':layers,
                    'verificationChecks':[{'id':'CV-'+letter,'result':'PASS','engineeringReason':'Synthetic '+letter,
                        'proofLimits':'Unit test fixture only; no project execution claim.', 'proofs':proofs} for letter in 'ABCDE'],
                    'criteria':[{'id':identity,'engineeringReason':'Synthetic '+identity,
                        'layers':['backend_full'],'proofs':proofs} for identity in criteria],
                    'findings':[{'id':identity,'engineeringReason':'Synthetic '+identity,
                        'layers':['backend_full'],'proofs':proofs} for identity in frozen]}
        (root/assessment.MANIFEST).write_bytes(assessment.json_bytes(manifest))
        for path, data in assessment.build_outputs().items():
            (root/path).write_bytes(data)
        current = (ROOT/'docs/00-governance/CURRENT_STATE.md').read_text()
        for field, value in assessment.CURRENT_PHASE_STATES['COMPLETE'].items():
            current = re.sub(rf'(?m)^{field}: [^\n]*$', field+': '+value, current)
        return manifest, current, raw

    def phase_errors(self, current):
        governance = []
        validate_v1_current_state_text(governance, current,
            (ROOT/'docs/00-governance/PROJECT_CHARTER.md').read_text())
        return governance, completion_state_violations(current)

    def test_complete_phase_admits_measured_evidence_but_preserves_historical_owner_and_write_boundaries(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(assessment, 'ROOT', Path(directory)):
            _, current, _ = self.complete_phase_fixture(directory)
            self.assertEqual(assessment.CURRENT_PHASE_STATES['COMPLETE'], assessment.validated_current_phase())
            self.assertEqual(([], []), self.phase_errors(current))
            for field, invalid in [('slice_v1_003_historical_controller_verdict', 'PASS'),
                                   ('slice_v1_003_historical_controller_reviewed_head', '0'*40),
                                   ('slice_v1_003_historical_controller_report_sha256', '0'*64),
                                   ('slice_v1_003_rework_authorization', 'EXPANDED_AUTHORITY'),
                                   ('production_write_enabled', 'true'),
                                   ('slice_v1_003_controller_verdict', 'APPROVE_FOR_HUMAN_MERGE')]:
                with self.subTest(field=field):
                    mutated = re.sub(rf'(?m)^{field}: [^\n]*$', field+': '+invalid, current)
                    for errors in self.phase_errors(mutated):
                        self.assertTrue(any(field in error for error in errors), errors)

    def test_complete_label_stale_views_and_changed_raw_evidence_cannot_admit_a_phase(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(assessment, 'ROOT', Path(directory)):
            manifest, current, raw = self.complete_phase_fixture(directory)
            root = Path(directory)
            manifest_path = root/assessment.MANIFEST
            original_manifest = manifest_path.read_bytes()
            forged = copy.deepcopy(manifest)
            forged.update(source=None, layers=[], verificationChecks=[], criteria=[], findings=[])
            manifest_path.write_bytes(assessment.json_bytes(forged))
            for errors in self.phase_errors(current):
                self.assertTrue(any('phase evidence is invalid' in error for error in errors))
            manifest_path.write_bytes(original_manifest)
            view = root/assessment.VERIFICATION_OUTPUT
            original_view = view.read_bytes()
            view.write_bytes(original_view+b'\n')
            for errors in self.phase_errors(current):
                self.assertTrue(any('Current assessment is stale' in error for error in errors))
            view.write_bytes(original_view)
            (root/raw).write_text('<testsuite><testcase classname="Synthetic" name="positive"><failure/></testcase></testsuite>')
            for errors in self.phase_errors(current):
                self.assertTrue(any('digest mismatch' in error for error in errors))

    def test_checked_in_views_are_deterministic_and_check_does_not_write(self):
        before={path:(ROOT/path).read_bytes() for path in assessment.build_outputs()}
        assessment.finalize(check=True)
        self.assertEqual(before,{path:(ROOT/path).read_bytes() for path in before})
        self.assertEqual(assessment.build_outputs(),assessment.build_outputs())


if __name__ == '__main__':
    unittest.main()
