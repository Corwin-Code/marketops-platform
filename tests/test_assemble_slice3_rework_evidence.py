"""Evidence mappings cannot silently change authority or manufacture PASS."""

import importlib.util
import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

REPOSITORY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "slice3_assembly", REPOSITORY / "scripts/validation/assemble_slice3_rework_evidence.py")
assembly = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(assembly)


class EvidenceAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.addCleanup(patch.stopall)
        patch.object(assembly, "ROOT", self.root).start()
        for relative in (assembly.CONTRACT, assembly.FROZEN,
                         assembly.BASE / "S3-AC-REWORK-STATUS.json",
                         assembly.BASE / "FINDING-CLOSURE-MATRIX.json"):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((REPOSITORY / relative).read_bytes())
        (self.root / "test_boundary.py").write_text("def proves_boundary():\n    return True\n")
        criteria = dict(re.findall(r"^- `(S3-AC-\d{3})` — (.+)$",
                                  (self.root / assembly.CONTRACT).read_text(), re.M))
        self.rows = [{"id": key, "criterion": text,
                      "sources": [{"path": "test_boundary.py"}],
                      "tests": [{"path": "test_boundary.py", "method": "proves_boundary"}],
                      "status": "VERIFIED"} for key, text in criteria.items()]
        for index, name in enumerate(assembly.SHARDS):
            path = self.root / assembly.BASE / "workstreams" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({"criteria": self.rows if index == 0 else []}))

    def first_shard(self, rows):
        path = self.root / assembly.BASE / "workstreams" / assembly.SHARDS[0]
        path.write_text(json.dumps({"criteria": rows}))

    def test_complete_mapping_preserves_authority_and_never_inherits_pass(self):
        contract_before = assembly.digest(self.root / assembly.CONTRACT)
        frozen_before = assembly.digest(self.root / assembly.FROZEN)
        # A later mapping preparation must revoke any earlier engineering claim.
        for filename in ("S3-AC-REWORK-STATUS.json", "FINDING-CLOSURE-MATRIX.json"):
            path = self.root / assembly.BASE / filename
            previous = json.loads(path.read_text())
            previous["engineering_closure_claim_made"] = True
            if filename == "FINDING-CLOSURE-MATRIX.json":
                for row in previous["entries"]:
                    row["closed_by_codex_engineering_assessment"] = True
            path.write_text(json.dumps(previous))
        assembly.assemble()
        result = assembly.read(assembly.BASE / "S3-AC-REWORK-STATUS.json")
        self.assertEqual(200, len(result["entries"]))
        self.assertFalse(result["closure_claim_made"])
        self.assertFalse(result["engineering_closure_claim_made"])
        self.assertFalse(result["production_write_enabled"])
        self.assertTrue(all(row["status"] == "REWORK_EVIDENCE_ASSEMBLED_VERIFICATION_PENDING"
                            for row in result["entries"]))
        self.assertEqual(contract_before, assembly.digest(self.root / assembly.CONTRACT))
        self.assertEqual(frozen_before, assembly.digest(self.root / assembly.FROZEN))
        findings = assembly.read(assembly.BASE / "FINDING-CLOSURE-MATRIX.json")
        self.assertFalse(findings["engineering_closure_claim_made"])
        self.assertTrue(all(not row["closed_by_codex_engineering_assessment"]
                            for row in findings["entries"]))

    def test_changed_contract_is_rejected_before_writing_matrices(self):
        path = self.root / assembly.CONTRACT
        path.write_bytes(path.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "Accepted Contract identity changed"):
            assembly.assemble()

    def test_changed_frozen_findings_are_rejected_before_writing_matrices(self):
        path = self.root / assembly.FROZEN
        path.write_bytes(path.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "Frozen Finding Set identity changed"):
            assembly.assemble()

    def test_modified_criterion_is_not_treated_as_the_accepted_criterion(self):
        self.rows[0]["criterion"] += " weakened"
        self.first_shard(self.rows)
        with self.assertRaisesRegex(ValueError, "Altered accepted criterion"):
            assembly.assemble()

    def test_a_missing_criterion_prevents_a_complete_matrix(self):
        self.first_shard(self.rows[1:])
        with self.assertRaisesRegex(ValueError, "Unmapped accepted criteria"):
            assembly.assemble()

    def test_a_nonexistent_test_method_is_not_accepted_as_evidence(self):
        with self.assertRaisesRegex(ValueError, "Missing test method"):
            assembly.references({"tests": [{"path": "test_boundary.py", "method": "missing"}]}, ("tests",))

    def test_references_cannot_escape_the_repository(self):
        with self.assertRaisesRegex(ValueError, "Invalid evidence path"):
            assembly.references({"tests": [{"path": "../unrelated.py"}]}, ("tests",))

    def test_javascript_test_titles_require_the_actual_literal_title(self):
        (self.root / "boundary.test.ts").write_text("test('denies incomplete scope', () => {});")
        actual = {"tests": [{"path": "boundary.test.ts", "method": "denies incomplete scope"}]}
        self.assertEqual(1, len(assembly.references(actual, ("tests",))))
        actual["tests"][0]["method"] = "denies all scope"
        with self.assertRaisesRegex(ValueError, "Missing test method"):
            assembly.references(actual, ("tests",))
