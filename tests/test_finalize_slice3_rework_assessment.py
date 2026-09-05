"""The final Slice 3 assessment must remain derived, exact and bounded."""

import importlib.util
import json
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "slice3_final_assessment",
    REPOSITORY / "scripts/validation/finalize_slice3_rework_assessment.py",
)
assessment = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(assessment)


def decoded(outputs, relative):
    return json.loads(outputs[relative])


class FinalSlice3AssessmentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.outputs = assessment.build_outputs()

    def test_exact_authorities_and_deferred_register_are_read_only_inputs(self):
        before = {
            path: assessment.sha256(REPOSITORY / path)
            for path in (assessment.CONTRACT, assessment.FROZEN_MD,
                         assessment.FROZEN_JSON, assessment.DEFERRED)
        }
        assessment.build_outputs()
        after = {path: assessment.sha256(REPOSITORY / path) for path in before}
        self.assertEqual(before, after)
        self.assertEqual(assessment.CONTRACT_SHA256, before[assessment.CONTRACT])
        self.assertEqual(assessment.FROZEN_MD_SHA256, before[assessment.FROZEN_MD])
        self.assertEqual(assessment.FROZEN_JSON_SHA256, before[assessment.FROZEN_JSON])

    def test_all_individual_criteria_have_the_exact_engineering_disposition(self):
        detailed = decoded(self.outputs, assessment.AC_OUTPUT)
        central = decoded(self.outputs, assessment.AC_MATRIX_OUTPUT)
        self.assertEqual(260, detailed["contributionCount"])
        self.assertEqual(200, detailed["criterionCount"])
        self.assertEqual(200, len(central["entries"]))
        self.assertEqual(
            {f"S3-AC-{number:03}" for number in range(1, 201)},
            {row["id"] for row in central["entries"]},
        )
        self.assertTrue(all(row["engineering_evidence"] for row in central["entries"]))
        self.assertTrue(all(row["status"] == "VERIFIED" for row in central["entries"][:-1]))
        self.assertEqual(
            "CANDIDATE_PREREQUISITES_PASS_CONTROLLER_PENDING",
            central["entries"][-1]["status"],
        )
        self.assertTrue(central["engineering_closure_claim_made"])
        self.assertFalse(central["closure_claim_made"])
        self.assertFalse(central["production_write_enabled"])

    def test_all_frozen_clauses_are_engineering_verified_without_controller_self_approval(self):
        detailed = decoded(self.outputs, assessment.FINDING_OUTPUT)
        central = decoded(self.outputs, assessment.FINDING_MATRIX_OUTPUT)
        self.assertEqual(22, detailed["findingCount"])
        self.assertEqual(115, detailed["clauseCount"])
        self.assertEqual(115, sum(len(row["clauses"]) for row in detailed["entries"]))
        self.assertTrue(all(row["status"] == "CLOSED_WITH_EVIDENCE" for row in central["entries"]))
        self.assertTrue(all(row["closed_by_codex_engineering_assessment"] for row in central["entries"]))
        self.assertTrue(all(row["controller_verdict"] == "PENDING_INDEPENDENT_REVIEW"
                            for row in central["entries"]))
        self.assertFalse(central["closure_claim_made"])
        self.assertFalse(central["production_write_enabled"])

    def test_minimum_expiry_and_capacity_claims_use_the_measured_boundaries(self):
        detailed = decoded(self.outputs, assessment.FINDING_OUTPUT)
        findings = {row["id"]: row for row in detailed["entries"]}
        expiry = findings["S3-DR-009"]
        current_claims = json.dumps(
            [expiry["implementedBehavior"], expiry["engineeringReason"], expiry["transitiveImpact"]]
        )
        self.assertNotIn("time-travel", current_claims)
        self.assertIn("900-second", expiry["implementedBehavior"])
        self.assertIn("885 seconds earlier", expiry["implementedBehavior"])
        self.assertIn("PostgreSQL-clock waiting", expiry["implementedBehavior"])
        capacity = " ".join(findings["S3-DR-020"]["remainingLimitations"])
        for boundary in ("1,000 UNVERIFIED", "200 critical", "1,200 Tasks",
                         "zero commands", "no mature Outcomes", "multi-store"):
            self.assertIn(boundary, capacity)

    def test_ui_repair_has_19_named_cases_and_both_actual_browser_runs(self):
        ui = decoded(self.outputs, assessment.UI_OUTPUT)
        self.assertEqual(81, ui["criterionCount"])
        self.assertEqual(19, len(ui["newSloDisplayAssertions"]))
        self.assertEqual({"local": 12, "remote": 12}, ui["actualAdvertisingBrowser"])
        self.assertTrue(all(name.startswith(
            "advertising response evidence stays distinct from staffed-clock evaluability"
        ) for name in ui["newSloDisplayAssertions"]))

    def test_checked_in_assessment_is_a_deterministic_derivation(self):
        self.assertEqual(self.outputs, assessment.build_outputs())
        assessment.finalize(check=True)


if __name__ == "__main__":
    unittest.main()
