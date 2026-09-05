#!/usr/bin/env python3
"""Build a source-only finalization draft. Never promotes or edits active matrices."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = next(parent for parent in HERE.parents if (parent / "AGENTS.md").is_file())
BASE = HERE.parent
W10 = "3ff042df66d5d6924b587cac96fc652b93bf5e7a"
CONTRACT = ROOT / "docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md"
FROZEN = BASE.parent / "SLICE-V1-003-FROZEN-FINDING-SET-001.json"
APP = "backend/marketops-server/src/main/java/com/mimococo/marketops/advertisingefficiency/internal/"
TEST = "backend/marketops-server/src/test/java/com/mimococo/marketops/advertisingefficiency/internal/application/"
DB = "backend/marketops-server/src/main/resources/db/migration/"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read(path: Path):
    return json.loads(path.read_text())


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=ROOT)


def pin(path: str) -> dict:
    content = (ROOT / path).read_bytes()
    return {"path": path, "sha256": digest(content), "bytes": len(content)}


def historical(path: Path) -> dict:
    relative = path.relative_to(ROOT).as_posix()
    content = git("show", f"{W10}:{relative}")
    return {"sourceHead": W10, "path": relative, "sha256": digest(content), "bytes": len(content)}


def source_paths(value) -> set[str]:
    found = set()
    if isinstance(value, dict):
        path = value.get("path")
        if isinstance(path, str):
            found.add(path)
        for nested in value.values():
            found.update(source_paths(nested))
    elif isinstance(value, list):
        for nested in value:
            found.update(source_paths(nested))
    return found


def methods(path: str) -> list[dict]:
    result = []
    pending = None
    for number, line in enumerate((ROOT / path).read_text().splitlines(), 1):
        annotation = re.search(r"@(Test|ParameterizedTest)\b", line)
        if annotation:
            pending = annotation.group(1)
        method = re.search(r"\bvoid\s+(\w+)\s*\(", line)
        if pending and method:
            result.append({"method": method.group(1), "line": number, "annotation": pending,
                           "reference": path + "#" + method.group(1), "executionBinding": None})
            pending = None
    return result


def build() -> dict:
    assert digest(CONTRACT.read_bytes()) == "1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c"
    assert digest(FROZEN.read_bytes()) == "f4af74f5086772dc70c3ec3cc7aa8808e9441e96109d301b145e70c18f6131a0"
    report_path = HERE / "controller-package/VERIFICATION-RESULT.json"
    report = read(report_path)
    assert report["verdict"] == "NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED"
    frozen = {row["id"]: row for row in read(FROZEN)["findings"]}
    criteria = dict(re.findall(r"^- `(S3-AC-\d{3})` — (.+)$", CONTRACT.read_text(), re.M))
    assert len(frozen) == 22 and len(criteria) == 200
    prior_ac = {row["id"]: row for row in read(BASE / "workstreams/engineering-assessment-w9/criterion-engineering-assessment.json")["entries"]}
    prior_findings = {row["id"]: row for row in read(HERE / "historical-w10-central/FINDING-CLOSURE-MATRIX.json")["entries"]}
    changed = set(git("diff", "--name-only", W10).decode().splitlines())
    changed.update(git("ls-files", "--others", "--exclude-standard").decode().splitlines())
    checks = {row["id"]: row for row in report["verification_checks"]}
    residuals = {finding for row in checks.values() for finding in row["findings"]}
    assert residuals == {"S3-DR-004", "S3-DR-011", "S3-DR-015", "S3-DR-020", "S3-DR-022"}
    sources = {
        "CV-A": [APP+"application/"+name+".java" for name in ["AdvertisingOutcomeFreshness", "AdvertisingOutcomeEvidenceService", "AdvertisingOutcomePlanningService", "AdvertisingPurposeFreshness"]]
                + [APP+"infrastructure/jdbc/AdvertisingPolicyRepository.java", DB+"V0067__validate_frozen_outcome_input_profiles.sql"],
        "CV-B": [APP+"application/"+name+".java" for name in ["AdvertisingCaseCalculationService", "AdvertisingDecisionService", "AdvertisingProposalService", "AdvertisingEvidenceGatherer"]]
                + [APP+"domain/AdCaseCalculation.java", APP+"domain/AdActionDependencyPolicy.java", DB+"V0066__qualify_economic_cause_bound_protection.sql",
                   "backend/marketops-server/src/main/java/com/mimococo/marketops/analyticsdecision/MetricQuery.java",
                   "backend/marketops-server/src/main/java/com/mimococo/marketops/analyticsdecision/internal/application/AnalyticsQueryService.java",
                   "backend/marketops-server/src/main/java/com/mimococo/marketops/analyticsdecision/internal/infrastructure/jdbc/MetricRepository.java"],
        "CV-C": [APP+"application/"+name+".java" for name in ["AdvertisingProtectionWindow", "AdvertisingOutcomeAssessment", "AdvertisingOutcomeService", "AdvertisingOutcomeEvidenceService"]]
                + [APP+"infrastructure/jdbc/AdvertisingOutcomeRepository.java", APP+"infrastructure/jdbc/AdvertisingEvidenceRepository.java",
                   "backend/marketops-server/src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/AdvertisingOutcomeReviewService.java",
                   DB+"V0067__validate_frozen_outcome_input_profiles.sql", DB+"V0068__preserve_critical_sales_guard_case_evidence.sql", DB+"V0069__reopen_invalidated_protection_outcomes.sql"],
        "CV-D": [TEST+name+".java" for name in ["AdvertisingMixedCapacityEvidence", "AdvertisingMixedCapacityFixture", "AdvertisingMixedOrchestrationCapacityIT"]]
                + [APP+"application/"+name+".java" for name in ["AdvertisingTargetedWorker", "AdvertisingReconciliationWorker", "AdvertisingOrchestrationSloService", "AdvertisingOutcomeWorker"]],
        "CV-E": ["scripts/validation/finalize_slice3_rework_assessment.py", "scripts/validate_governance.py", "scripts/validate_production_readiness.py",
                  str((HERE/"reconcile_measurements.py").relative_to(ROOT))],
    }
    test_files = {
        "CV-A": ["AdvertisingOutcomePurposeFreshnessIT", "AdvertisingOutcomeRevisionFreshnessIT", "AdvertisingFrozenOutcomeIT", "AdvertisingPurposeFreshnessTest", "AdvertisingSegmentPurposeFreshnessTest"],
        "CV-B": ["AdvertisingEconomicCauseBoundIT", "AdvertisingPhysicalPurposeFreshnessTest", "AdvertisingSegmentPurposeFreshnessTest", "AdvertisingVerticalPathIT"],
        "CV-C": ["AdvertisingOutcomePurposeFreshnessIT", "AdvertisingOutcomeRevisionFreshnessIT", "AdvertisingFrozenOutcomeIT", "AdvertisingOutcomeServiceTest"],
        "CV-D": ["AdvertisingMixedOrchestrationCapacityIT"],
        "CV-E": [],
    }
    test_catalog = {TEST+name+".java": {**pin(TEST+name+".java"), "methods": methods(TEST+name+".java")}
                    for names in test_files.values() for name in names}
    cv_rows = []
    for identity, check in checks.items():
        direct = sorted(value for value in check["contract"] if re.fullmatch(r"S3-AC-\d{3}", value))
        indirect = sorted({ac for finding in check["findings"] for ac in frozen[finding]["criteria"]} - set(direct))
        cv_rows.append({"id": identity, "findings": check["findings"], "controllerTitle": check["title"],
            "controllerRequiredClosure": check["required_closure"], "directCriteria": direct,
            "frozenFindingTransitiveCriteria": indirect, "currentSourcePins": [pin(path) for path in sources[identity]],
            "testCatalogPaths": [TEST+name+".java" for name in test_files[identity]],
            "scopeBoundary": "Listed files/methods are proof obligations and inspection entry points, not a claim that each proves every linked AC.",
            "currentAssessment": "REWORK_EVIDENCE_PENDING", "currentExecutedProof": None})
    finding_rows = []
    for index, row in enumerate(report["findings"]):
        identity = row["id"]
        finding_rows.append({"id": identity, "title": frozen[identity]["title"], "severity": frozen[identity]["severity"],
            "frozenRequiredRework": frozen[identity]["required_rework"], "frozenVerification": frozen[identity]["verification"],
            "criteria": frozen[identity]["criteria"], "historicalControllerDisposition": row["controller_disposition"],
            "historicalControllerReference": {**pin(str(report_path.relative_to(ROOT))), "sourceHead": W10, "jsonPointer": f"/findings/{index}"},
            "residualChecks": row["verification_checks"],
            "currentStatus": "REWORK_EVIDENCE_PENDING" if identity in residuals else "HISTORICAL_SCOPE_ACCEPTED_CURRENT_REGRESSION_PENDING",
            "priorAssessmentReferencesChanged": sorted(source_paths(prior_findings[identity]) & changed),
            "currentExecutedProof": None, "independentControllerClosedCurrentHead": False})
    ac_rows = []
    for identity, text in criteria.items():
        direct = [row["id"] for row in cv_rows if identity in row["directCriteria"]]
        transitive = [row["id"] for row in cv_rows if identity in row["frozenFindingTransitiveCriteria"]]
        paths = sorted(source_paths(prior_ac[identity]) & changed)
        ac_rows.append({"id": identity, "acceptedExact": text, "directControllerChecks": direct,
            "frozenFindingTransitiveChecks": transitive, "priorSourceOrProofReferencesChanged": paths,
            "historicalAssessment": {"sourceHead": W10, "status": prior_ac[identity]["status"],
                 "path": str((BASE/"workstreams/engineering-assessment-w9/criterion-engineering-assessment.json").relative_to(ROOT))},
            "currentStatus": "INDEPENDENT_CONTROLLER_NOT_PASSED_NEW_HEAD_NOT_REVIEWED" if identity == "S3-AC-200" else
                "CURRENT_EVIDENCE_REASSESSMENT_REQUIRED" if direct or transitive or paths else "HISTORICAL_EVIDENCE_RETAINED_CURRENT_REGRESSION_PENDING",
            "currentExecutedProof": None})
    retained = [BASE/name for name in ["ENGINEERING_VERIFICATION.json", "S3-AC-REWORK-STATUS.json", "FINDING-CLOSURE-MATRIX.json"]]
    retained += [BASE/"workstreams/engineering-assessment-w9"/name for name in ["ui81-current.json", "criterion-engineering-assessment.json", "finding-engineering-assessment.json"]]
    return {"kind": "SLICE3_FINAL_GATE_FINALIZATION_SOURCE_MAP_DRAFT", "status": "SOURCE_MAPPING_ONLY_NO_VERIFICATION_OR_CLOSURE_CLAIM",
        "historicalControllerVerdict": report["verdict"], "historicalReviewedHead": W10,
        "historicalAcceptedFindings": 17, "historicalResidualFindings": 5,
        "currentSourceIdentity": {"baseHead": git("rev-parse", "HEAD").decode().strip(), "uncommittedSourcePins": True,
            "futurePublishedHead": None, "currentCIIdentity": None},
        "preserveHistoricalFinalizerOutputs": [historical(path) for path in retained],
        "verificationChecks": cv_rows, "findingCount": len(finding_rows), "findings": finding_rows,
        "criterionCount": len(ac_rows), "criteria": ac_rows,
        "directAffectedCriterionCount": sum(bool(row["directControllerChecks"]) for row in ac_rows),
        "transitiveOrSourceChangedCriterionCount": sum(bool(row["directControllerChecks"] or row["frozenFindingTransitiveChecks"] or row["priorSourceOrProofReferencesChanged"]) for row in ac_rows),
        "sourceTestCatalog": list(test_catalog.values()),
        "evidenceValidatorTestObligations": [{**pin(path), "scope": scope, "executionBinding": None} for path, scope in [
            ("tests/test_finalize_slice3_rework_assessment.py", "Replace blanket active closure/byte regeneration assertions with historical preservation and current measured-proof admission"),
            ("tests/test_validate_governance.py", "Slice 3 exact active state/history and no premature Controller/merge authority"),
            ("tests/test_validate_production_readiness.py", "Same active state/history tuple and unchanged deferred production/write boundaries"),
            ("tests/test_assemble_slice3_rework_evidence.py", "Source-only assembly must never promote a prior or current result to execution PASS")]],
        "productionWriteEnabled": False,
        "all24ReleaseObligationsRemainDeferred": True, "engineeringClosureClaimMade": False,
        "controllerApprovalClaimMade": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    output = HERE / "FINALIZATION-INPUT-MAP-DRAFT.json"
    data = build()
    content = (json.dumps(data, ensure_ascii=False, indent=2)+"\n").encode()
    if args.check:
        if output.read_bytes() != content:
            raise SystemExit("Draft source map is stale; regenerate after coordinated source edits.")
    else:
        output.write_bytes(content)
    print(json.dumps({"result": "SOURCE_MAP_VALIDATED_NOT_EXECUTION_PASS", "findings": 22, "criteria": 200,
        "directAffected": data["directAffectedCriterionCount"], "transitiveOrChanged": data["transitiveOrSourceChangedCriterionCount"],
        "testFiles": len(data["sourceTestCatalog"]), "sha256": digest(content)}))


if __name__ == "__main__":
    main()
