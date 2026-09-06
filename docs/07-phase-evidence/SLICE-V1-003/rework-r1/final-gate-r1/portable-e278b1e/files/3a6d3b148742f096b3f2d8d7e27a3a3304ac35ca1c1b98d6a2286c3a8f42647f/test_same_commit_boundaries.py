import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from test_auditor_boundaries import a


class SameCommitBoundaryTest(unittest.TestCase):
    def fixture(self, directory, extra=False):
        root = Path(directory)
        source = 'docs/tool.py' if extra else 'backend/Test.java'
        (root / source).parent.mkdir()
        (root / source).write_text('synthetic boundary fixture only\n')
        raw = root / 'original-parent-named.json'
        raw.write_text('{"synthetic": true}\n')
        parent = root / 'parent.json'
        identity = {'sourceHead': a.HEAD, 'sourceTree': a.TREE,
                    'sourceInventorySha256': a.INVENTORY_SHA}
        parent.write_text(json.dumps({**identity, 'runId': 'current-parent',
                                     'evidence': [a.reference(raw)]}))
        pin = {'path': source, 'sha256': a.sha(root / source)}
        audit = a.Audit.__new__(a.Audit)
        audit.derivation = None
        audit.sources = {source: pin}
        audit.extras = {}
        audit.layers = {'governance': {'runId': 'current-parent'}}
        audit.product_parent_refs = {'governance': a.reference(parent)}
        audit.registered = {'governance': {(str(raw.resolve()), a.sha(raw))}}
        audit.catalog_base = root
        audit.expected_base = root
        audit.cache = {}
        if extra:
            helper = root / 'helper.json'
            helper.write_text(json.dumps({
                **identity, 'kind': 'INDEPENDENT_ADDITIONAL_INPUT_SYNTHETIC_EXECUTION',
                'result': 'COMMAND_SUCCEEDED_REVIEW_REQUIRED', 'sourceStable': True,
                'exitCode': 0, 'evidence': [a.reference(raw)],
                'repositoryGitBindings': [{
                    'repositoryRelativePath': source, 'sourceHead': a.HEAD,
                    'gitBlobSha256': pin['sha256'], 'copiedInput': a.reference(root / source)}],
                'inputsBefore': [a.reference(root / source)],
                'inputsAfter': [a.reference(root / source)]}))
            pin['executionReceipt'] = a.reference(helper)
            audit.extras = {source: pin}
        node = {'sourcePath': source, 'source': {'path': source, 'sha256': pin['sha256']},
                'layer': 'governance', 'runId': 'current-parent', 'evidence': a.reference(raw),
                'actualExecutionReceipt': a.reference(parent),
                'productSourceIdentity': identity, 'executionSourceIdentity': identity}
        return root, audit, node

    def test_actual_current_parent_raw_is_accepted(self):
        for extra in (False, True):
            with self.subTest(extra=extra), tempfile.TemporaryDirectory(dir='/tmp') as d:
                root, audit, node = self.fixture(d, extra)
                with patch.object(a, 'ROOT', root):
                    audit.validate_source(node)

    def test_prior_raw_cannot_borrow_current_parent_or_same_source(self):
        with tempfile.TemporaryDirectory(dir='/tmp') as d:
            root, audit, node = self.fixture(d, True)
            old = root / 'prior-raw.json'
            old.write_text('{"prior":true}\n')
            node['evidence'] = a.reference(old)
            with patch.object(a, 'ROOT', root), self.assertRaisesRegex(a.Rejected, 'SAME_COMMIT_RAW_NOT_IN_ORIGINAL_PARENT'):
                audit.validate_source(node)

    def test_wrong_run_or_relabelled_execution_or_parent_is_rejected(self):
        for key, expected in [('runId', 'PARENT_RUN_MISMATCH'),
                              ('executionSourceIdentity', 'NODE_IDENTITY_RELABELED'),
                              ('actualExecutionReceipt', 'PARENT_CHANGED')]:
            with self.subTest(key=key), tempfile.TemporaryDirectory(dir='/tmp') as d:
                root, audit, node = self.fixture(d)
                if key == 'runId': node[key] = 'prior-run'
                elif key == 'executionSourceIdentity': node[key] = {**node[key], 'sourceHead': 'a' * 40}
                else:
                    p = root / 'other-parent.json'; p.write_text('{}\n'); node[key] = a.reference(p)
                with patch.object(a, 'ROOT', root), self.assertRaisesRegex(a.Rejected, expected):
                    audit.validate_source(node)

    def test_unexecuted_helper_or_changed_inputs_or_missing_raw_is_rejected(self):
        cases = [('failed', 'HELPER_NOT_EXECUTED'), ('boolean-exit', 'HELPER_NOT_EXECUTED'), ('input', 'HELPER_SOURCE_CHANGED'),
                 ('raw', 'HELPER_RAW_NOT_FROM_ACTUAL_EXECUTION'),
                 ('head', 'HELPER_EXECUTION_IDENTITY_MISMATCH')]
        for mutation, expected in cases:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory(dir='/tmp') as d:
                root, audit, node = self.fixture(d, True)
                pin = audit.extras[node['sourcePath']]
                p = Path(pin['executionReceipt']['path']); helper = json.loads(p.read_text())
                if mutation == 'failed': helper['exitCode'] = 1
                elif mutation == 'boolean-exit': helper['exitCode'] = False
                elif mutation == 'input': helper['inputsAfter'][0]['sha256'] = '0' * 64
                elif mutation == 'raw': helper['evidence'] = []
                else: helper['sourceHead'] = 'a' * 40
                p.write_text(json.dumps(helper)); pin['executionReceipt'] = a.reference(p)
                with patch.object(a, 'ROOT', root), self.assertRaisesRegex(a.Rejected, expected):
                    audit.validate_source(node)


if __name__ == '__main__':
    unittest.main(verbosity=2)
