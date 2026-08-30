from __future__ import annotations

import csv
import hashlib
import io
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts.validate_governance import (
    CANONICAL_DESIGN_RELATIVE_PATH,
    DR0003_R1_ARTIFACT_HASHES,
    DR0003_R1_REVIEW_RELATIVE_PATH,
    DR0003_R2_ARTIFACT_HASHES,
    DR0003_REQUIRED_FILES,
    DR0004_ARTIFACT_HASHES,
    DR0004_CURRENT_STATE,
    DR0004_PROTECTED_CONTRACT_HASHES,
    DR0004_R1_ARTIFACT_HASHES,
    DR0004_REQUIRED_FILES,
    HISTORICAL_EVIDENCE_TREE_HASHES,
    HISTORIC_CONTRACT_BEGIN,
    HISTORIC_CONTRACT_END,
    REQUIRED_FILES,
    V1_ACTIVE_SLICE_CONTRACT_SHA256,
    SLICE_REWORK_ARTIFACT_HASHES,
    WP_P0_002_ID,
    WP_P0_002_DESIGN_RELATIVE_PATH,
    WP_P0_002_RELATIVE_PATH,
    WP_P0_003_PREIMPLEMENTATION_EMPTY_TRACEABILITY_IDS,
    WP_P0_003_RELATIVE_PATH,
    directory_tree_sha256,
    git_scan_paths,
    java_test_identity_inventory,
    validate_active_work_package_record_text,
    validate_backlog_state_text,
    validate_approved_design_state_text,
    validate_authorization_state_text,
    validate_completion_state_text,
    validate_codeql_disposition_artifacts,
    validate_controller_review_standard_text,
    validate_decision_log_v1_text,
    validate_dr0003_controller_review_text,
    validate_dr0003_hash_binding_text,
    validate_dr0003_r1_artifacts,
    validate_dr0003_r1_whitespace_exception,
    validate_dr0003_r2_artifacts,
    validate_dr0004_artifact_hashes,
    validate_dr0004_protocol_texts,
    validate_ai_execution_boundary_text,
    validate_ai_operating_model_v1_text,
    validate_backlog_v1_text,
    validate_capability_matrix_v1_text,
    validate_delivery_slices_v1_text,
    validate_finding_vocabulary_texts,
    validate_gate_ev_contract_texts,
    validate_lifecycle_state_text,
    validate_open_questions_v1_text,
    validate_owner_decisions_v1_text,
    validate_owner_control_state_text,
    validate_parallel_current_state_paths,
    validate_prior_closed_transition_text,
    validate_readme_runtime_state_text,
    validate_required_file_set,
    validate_slice_v1_001_text,
    validate_slice_rework_evidence_text,
    validate_v1_current_state_text,
    validate_v1_authority_effect_texts,
    validate_v1_product_contract_text,
    validate_v1_traceability_text,
    validate_wp_p0_002_completion_text,
    validate_wp_p0_002_test_identity_contract,
    validate_wp_p0_002_traceability_text,
    validate_wp_p0_003_activation_text,
    validate_wp_p0_003_post_merge_closure_text,
    validate_wp_p0_003_record_paths,
    validate_wp_p0_003_traceability_text,
    validate_wp_p0_003_work_package_text,
    wp_p0_002_acceptance_rows,
)


class SecretScanScopeTests(unittest.TestCase):
    def test_ignored_dependency_tree_is_not_a_scan_candidate(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".gitignore").write_text("node_modules/\n", encoding="utf-8")
            (root / "tracked.ts").write_text("export const value = 1;\n", encoding="utf-8")
            dependency = root / "node_modules" / "dependency.ts"
            dependency.parent.mkdir()
            dependency.write_text("third party source\n", encoding="utf-8")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(
                ["git", "add", ".gitignore", "tracked.ts"], cwd=root, check=True
            )

            relative = {path.relative_to(root) for path in git_scan_paths(root)}

            self.assertEqual({Path(".gitignore"), Path("tracked.ts")}, relative)


class ParallelCurrentStateTests(unittest.TestCase):
    def test_no_parallel_state_source_is_valid(self) -> None:
        errors: list[str] = []
        validate_parallel_current_state_paths(errors, set())
        self.assertEqual([], errors)

    def test_proposal_state_source_is_rejected(self) -> None:
        errors: list[str] = []
        validate_parallel_current_state_paths(
            errors,
            {"docs/00-governance/CURRENT_STATE_PROPOSAL_WP-P0-001.md"},
        )
        self.assertTrue(any("parallel Current State" in error for error in errors))


def current_state(
    guidance: str,
    delegation: str = "ACTIVE",
    delegate: str = "CODEX",
    scope: str = "PR_READY_AND_MERGE_AFTER_ALL_GATES",
) -> str:
    return f"""
owner_git_workflow_guidance: {guidance}
owner_git_workflow_guidance_exit: HUMAN_OWNER_EXPLICIT_CONFIRMATION
owner_git_execution_delegation: {delegation}
owner_git_execution_delegate: {delegate}
owner_git_execution_delegation_scope: {scope}
owner_git_execution_delegation_exit: HUMAN_OWNER_EXPLICIT_REVOCATION
"""


def authorization_current_state(
    authorization: str | None,
    active_work_package: str | None = "WP-P0-001",
) -> str:
    field = f"authorization: {authorization}\n" if authorization is not None else ""
    active = (
        f"active_work_package: {active_work_package}\n"
        if active_work_package is not None
        else ""
    )
    gate = (
        "active_gate: READY_FOR_DESIGN\n"
        if active_work_package not in {None, "NONE"}
        else "active_gate: CONTROLLER_PHASE_0_PLANNING\n"
    )
    return f"""# Current State

```yaml
{active}{gate}{field}production_write_enabled: false
```
"""


def authorization_work_package(
    authorization: str | None,
    *,
    status: str = "READY_FOR_DESIGN",
    explicit_field: bool = False,
    historic_verdict: str | None = None,
    implementation_result: str | None = None,
) -> str:
    field = "Current execution authorization" if explicit_field else "Authorization"
    authorization_row = f"| {field} | {authorization} |\n" if authorization is not None else ""
    historic_row = (
        f"| Historic design verdict | {historic_verdict} |\n"
        if historic_verdict is not None
        else ""
    )
    result_row = (
        f"| Implementation result | {implementation_result} |\n"
        if implementation_result is not None
        else ""
    )
    return f"""# WP-P0-001

## 1. Metadata

| Field | Value |
| --- | --- |
| Status | {status} |
{historic_row}{authorization_row}{result_row}

## 2. Outcome

Current functional contract.

## 10. Controller Gate

Current functional Gate.
"""


def lifecycle_current_state(*states: str) -> str:
    rows = "".join(f"lifecycle_state: {state}\n" for state in states)
    return f"""# Current State

```yaml
{rows}production_write_enabled: false
```
"""


def lifecycle_project_charter(*statuses: str) -> str:
    rows = "".join(f"| Status | {status} |\n" for status in statuses)
    return f"""# Project Charter — MarketOps Russia

## 1. Identity

| Field | Value |
| --- | --- |
{rows}
## 2. Mission

Current mission.
"""


def approved_design_current_state(
    authorization: str = "APPROVED_FOR_IMPLEMENTATION",
    paths: tuple[str, ...] = (CANONICAL_DESIGN_RELATIVE_PATH,),
) -> str:
    path_rows = "".join(f"Canonical design: {path}\n" for path in paths)
    design_block = (
        f"""
## Approved design of record

```text
{path_rows}```
"""
        if paths
        else ""
    )
    return f"""# Current State

```yaml
authorization: {authorization}
production_write_enabled: false
```
{design_block}"""


def approved_design_work_package(
    authorization: str = "APPROVED_FOR_IMPLEMENTATION",
    paths: tuple[str, ...] = (CANONICAL_DESIGN_RELATIVE_PATH,),
) -> str:
    path_contract = "".join(
        f"The approved canonical design at\n`{path}` defines:\n" for path in paths
    )
    authorization_row = (
        f"| Historic design verdict | {authorization} |"
        if authorization == "APPROVED_FOR_IMPLEMENTATION"
        else f"| Authorization | {authorization} |"
    )
    return f"""# WP-P0-001

## 1. Metadata

| Field | Value |
| --- | --- |
{authorization_row}

## 2. Outcome

Current outcome.

## 6. Design Deliverables

{path_contract}
## 10. Controller Gate

Current verdict:

```text
APPROVED_FOR_IMPLEMENTATION
```
"""


def canonical_design(
    overrides: dict[str, str] | None = None,
    duplicate_field: str | None = None,
) -> str:
    metadata = {
        "document_type": "module foundation design",
        "status": "APPROVED_FOR_IMPLEMENTATION",
        "work_package": "WP-P0-001",
        "product": "MarketOps Russia",
        "repository": "marketops-platform",
    }
    metadata.update(overrides or {})
    rows = "".join(f"{field}: {value}\n" for field, value in metadata.items())
    if duplicate_field is not None:
        rows += f"{duplicate_field}: {metadata[duplicate_field]}\n"
    return f"""# MarketOps Russia — Repository, Governance & CI Foundation Design

```yaml
{rows}```

Current functional design.
"""


class OwnerControlStateTests(unittest.TestCase):
    def assert_valid(self, text: str) -> None:
        errors: list[str] = []
        validate_owner_control_state_text(errors, text)
        self.assertEqual([], errors)

    def test_required_guidance_state_is_valid(self) -> None:
        self.assert_valid(current_state("REQUIRED"))

    def test_explicit_disabled_guidance_state_is_valid(self) -> None:
        self.assert_valid(current_state("DISABLED"))

    def test_unknown_guidance_state_is_rejected(self) -> None:
        errors: list[str] = []
        validate_owner_control_state_text(errors, current_state("PAUSED"))
        self.assertTrue(any("must be exactly one of" in error for error in errors))

    def test_inactive_delegation_requires_no_delegate(self) -> None:
        self.assert_valid(
            current_state(
                "REQUIRED",
                delegation="INACTIVE",
                delegate="NONE",
                scope="NONE",
            )
        )

    def test_active_delegation_requires_named_delegate(self) -> None:
        errors: list[str] = []
        validate_owner_control_state_text(
            errors,
            current_state("REQUIRED", delegation="ACTIVE", delegate="NONE"),
        )
        self.assertTrue(any("requires a named delegate" in error for error in errors))


class PriorClosedTransitionTests(unittest.TestCase):
    def active_state(self) -> str:
        return authorization_current_state("DESIGN_ONLY", WP_P0_002_ID) + """

## Prior closed planning transition — historical provenance

active_work_package: NONE
active_gate: CONTROLLER_PHASE_0_PLANNING
authorization: PLANNING_ONLY
superseded as live runtime state by the leading YAML
must not be interpreted as current authorization or a parallel state source
"""

    def test_classified_prior_transition_is_valid(self) -> None:
        errors: list[str] = []
        validate_prior_closed_transition_text(errors, self.active_state())
        self.assertEqual([], errors)

    def test_unclassified_old_state_tokens_are_rejected(self) -> None:
        errors: list[str] = []
        validate_prior_closed_transition_text(
            errors,
            authorization_current_state("DESIGN_ONLY", WP_P0_002_ID),
        )
        self.assertTrue(any("classified" in error for error in errors))

    def test_parallel_state_disclaimer_is_required(self) -> None:
        errors: list[str] = []
        validate_prior_closed_transition_text(
            errors,
            self.active_state().replace(
                "must not be interpreted as current authorization or a parallel state source",
                "",
            ),
        )
        self.assertTrue(any("parallel state source" in error for error in errors))

    def test_closed_state_needs_no_historical_duplicate(self) -> None:
        errors: list[str] = []
        validate_prior_closed_transition_text(
            errors,
            authorization_current_state("PLANNING_ONLY", "NONE"),
        )
        self.assertEqual([], errors)


class LifecycleStateTests(unittest.TestCase):
    def assert_valid(self, state: str) -> None:
        errors: list[str] = []
        validate_lifecycle_state_text(
            errors,
            lifecycle_current_state(state),
            lifecycle_project_charter(state),
        )
        self.assertEqual([], errors)

    def test_initiating_pair_is_valid(self) -> None:
        self.assert_valid("INITIATING")

    def test_executing_phase_zero_pair_is_valid(self) -> None:
        self.assert_valid("EXECUTING_PHASE_0")

    def test_missing_current_state_lifecycle_ignores_historical_prose(self) -> None:
        errors: list[str] = []
        historical = "\nlifecycle_state: EXECUTING_PHASE_0\n"
        validate_lifecycle_state_text(
            errors,
            lifecycle_current_state() + historical,
            lifecycle_project_charter("EXECUTING_PHASE_0"),
        )
        self.assertTrue(
            any("CURRENT_STATE lifecycle_state metadata is missing" in e for e in errors)
        )

    def test_missing_charter_status_ignores_historical_prose(self) -> None:
        errors: list[str] = []
        historical = "\nStatus: EXECUTING_PHASE_0\n"
        validate_lifecycle_state_text(
            errors,
            lifecycle_current_state("EXECUTING_PHASE_0"),
            lifecycle_project_charter() + historical,
        )
        self.assertTrue(
            any("PROJECT_CHARTER Status metadata is missing" in e for e in errors)
        )

    def test_unknown_current_state_lifecycle_is_rejected(self) -> None:
        errors: list[str] = []
        validate_lifecycle_state_text(
            errors,
            lifecycle_current_state("PAUSED"),
            lifecycle_project_charter("INITIATING"),
        )
        self.assertTrue(any("CURRENT_STATE lifecycle_state must" in e for e in errors))

    def test_unknown_charter_status_is_rejected(self) -> None:
        errors: list[str] = []
        validate_lifecycle_state_text(
            errors,
            lifecycle_current_state("INITIATING"),
            lifecycle_project_charter("PAUSED"),
        )
        self.assertTrue(any("PROJECT_CHARTER Status must" in e for e in errors))

    def test_duplicate_current_state_lifecycle_is_rejected(self) -> None:
        errors: list[str] = []
        validate_lifecycle_state_text(
            errors,
            lifecycle_current_state("INITIATING", "EXECUTING_PHASE_0"),
            lifecycle_project_charter("EXECUTING_PHASE_0"),
        )
        self.assertTrue(
            any("CURRENT_STATE lifecycle_state metadata is missing" in e for e in errors)
        )

    def test_duplicate_charter_status_is_rejected(self) -> None:
        errors: list[str] = []
        validate_lifecycle_state_text(
            errors,
            lifecycle_current_state("EXECUTING_PHASE_0"),
            lifecycle_project_charter("INITIATING", "EXECUTING_PHASE_0"),
        )
        self.assertTrue(
            any("PROJECT_CHARTER Status metadata is missing" in e for e in errors)
        )

    def test_lifecycle_mismatch_is_rejected(self) -> None:
        errors: list[str] = []
        validate_lifecycle_state_text(
            errors,
            lifecycle_current_state("EXECUTING_PHASE_0"),
            lifecycle_project_charter("INITIATING"),
        )
        self.assertTrue(any("lifecycle mismatch" in e for e in errors))


class AuthorizationStateTests(unittest.TestCase):
    def assert_valid(self, current_authorization: str, wp_authorization: str) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state(current_authorization),
            authorization_work_package(wp_authorization),
        )
        self.assertEqual([], errors)

    def test_design_only_pair_is_valid(self) -> None:
        self.assert_valid("DESIGN_ONLY", "DESIGN_ONLY")

    def test_approved_for_implementation_pair_is_valid(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("APPROVED_FOR_IMPLEMENTATION").replace(
                "active_gate: READY_FOR_DESIGN", "active_gate: IMPLEMENTING"
            ),
            authorization_work_package(
                "APPROVED_FOR_IMPLEMENTATION", status="IMPLEMENTING"
            ),
        )
        self.assertEqual([], errors)

    def test_implementation_stage_requires_the_implementing_gate(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("APPROVED_FOR_IMPLEMENTATION"),
            authorization_work_package(
                "APPROVED_FOR_IMPLEMENTATION", status="IMPLEMENTING"
            ),
        )
        self.assertTrue(any("active_gate: IMPLEMENTING" in error for error in errors))

    def test_implementation_stage_requires_the_implementing_status(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("APPROVED_FOR_IMPLEMENTATION").replace(
                "active_gate: READY_FOR_DESIGN", "active_gate: IMPLEMENTING"
            ),
            authorization_work_package("APPROVED_FOR_IMPLEMENTATION"),
        )
        self.assertTrue(
            any("Status must be: IMPLEMENTING" in error for error in errors)
        )

    def test_mismatched_pair_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("DESIGN_ONLY"),
            authorization_work_package("APPROVED_FOR_IMPLEMENTATION"),
        )
        self.assertTrue(any("authorization mismatch" in error for error in errors))

    def test_unknown_current_state_authorization_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("ANYTHING_GOES"),
            authorization_work_package("DESIGN_ONLY"),
        )
        self.assertTrue(
            any("CURRENT_STATE authorization must" in error for error in errors)
        )

    def test_unknown_work_package_authorization_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("DESIGN_ONLY"),
            authorization_work_package("ANYTHING_GOES"),
        )
        self.assertTrue(
            any("current execution authorization must" in error for error in errors)
        )

    def test_missing_current_state_authorization_is_rejected(self) -> None:
        errors: list[str] = []
        historical_prose = "\nauthorization: DESIGN_ONLY\n"
        validate_authorization_state_text(
            errors,
            authorization_current_state(None) + historical_prose,
            authorization_work_package("DESIGN_ONLY"),
        )
        self.assertTrue(
            any(
                "CURRENT_STATE authorization metadata is missing" in error
                for error in errors
            )
        )

    def test_missing_current_state_metadata_block_is_rejected(self) -> None:
        errors: list[str] = []
        historical_prose = "authorization: DESIGN_ONLY\n"
        validate_authorization_state_text(
            errors,
            historical_prose,
            authorization_work_package("DESIGN_ONLY"),
        )
        self.assertTrue(
            any(
                "CURRENT_STATE authorization metadata is missing" in error
                for error in errors
            )
        )

    def test_missing_work_package_authorization_is_rejected(self) -> None:
        errors: list[str] = []
        historical_prose = "\nHistorical: | Authorization | DESIGN_ONLY |\n"
        validate_authorization_state_text(
            errors,
            authorization_current_state("DESIGN_ONLY"),
            authorization_work_package(None) + historical_prose,
        )
        self.assertTrue(
            any(
                "current execution authorization is missing" in error
                for error in errors
            )
        )

    def test_planning_only_with_active_work_package_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("PLANNING_ONLY"),
            authorization_work_package("DESIGN_ONLY"),
        )
        self.assertTrue(any("active Work Package requires" in error for error in errors))

    def test_none_with_approved_for_implementation_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state(
                "APPROVED_FOR_IMPLEMENTATION", active_work_package="NONE"
            ),
            authorization_work_package("APPROVED_FOR_IMPLEMENTATION"),
        )
        self.assertTrue(any("NONE requires authorization PLANNING_ONLY" in error for error in errors))

    def test_none_with_design_only_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("DESIGN_ONLY", active_work_package="NONE"),
            authorization_work_package("DESIGN_ONLY"),
        )
        self.assertTrue(any("NONE requires authorization PLANNING_ONLY" in error for error in errors))

    def test_missing_active_work_package_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("DESIGN_ONLY", active_work_package=None),
            authorization_work_package("DESIGN_ONLY"),
        )
        self.assertTrue(any("active_work_package metadata is missing" in error for error in errors))


class ActiveDesignAuthorizationTests(unittest.TestCase):
    def wp_p0_002(
        self,
        authorization: str = "DESIGN_ONLY",
        status: str = "READY_FOR_DESIGN",
        work_package_id: str = WP_P0_002_ID,
    ) -> str:
        return f"""# WP-P0-002

## 1. Metadata

| Field | Value |
| --- | --- |
| ID | {work_package_id} |
| Status | {status} |
| Authorization | {authorization} |
"""

    def validate(
        self,
        *,
        current_authorization: str = "DESIGN_ONLY",
        gate: str = "READY_FOR_DESIGN",
        wp_authorization: str = "DESIGN_ONLY",
        wp_status: str = "READY_FOR_DESIGN",
        validated_id: str = WP_P0_002_ID,
    ) -> list[str]:
        current = authorization_current_state(
            current_authorization,
            active_work_package=WP_P0_002_ID,
        ).replace("active_gate: READY_FOR_DESIGN", f"active_gate: {gate}")
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            current,
            self.wp_p0_002(wp_authorization, wp_status),
            validated_id,
        )
        return errors

    def test_active_wp_p0_002_design_state_is_valid(self) -> None:
        self.assertEqual([], self.validate())

    def test_active_work_package_with_planning_only_is_rejected(self) -> None:
        errors = self.validate(current_authorization="PLANNING_ONLY")
        self.assertTrue(any("active Work Package requires" in error for error in errors))

    def test_active_work_package_with_closed_authorization_is_rejected(self) -> None:
        errors = self.validate(wp_authorization="CLOSED")
        self.assertTrue(any("cannot have CLOSED" in error for error in errors))

    def test_completed_work_package_cannot_be_active(self) -> None:
        errors = self.validate(wp_status="COMPLETED")
        self.assertTrue(any("COMPLETED Work Package" in error for error in errors))

    def test_design_state_requires_ready_for_design_gate(self) -> None:
        errors = self.validate(gate="PULL_REQUEST_GATE")
        self.assertTrue(any("active_gate" in error for error in errors))

    def test_design_state_requires_ready_for_design_status(self) -> None:
        errors = self.validate(wp_status="DRAFT")
        self.assertTrue(any("Status must be" in error for error in errors))

    def test_active_record_id_mismatch_is_rejected(self) -> None:
        errors = self.validate(validated_id="WP-P0-003")
        self.assertTrue(any("record mismatch" in error for error in errors))


class ActiveWorkPackageRecordTests(unittest.TestCase):
    def current(self) -> str:
        return authorization_current_state("DESIGN_ONLY", WP_P0_002_ID)

    def record(self, work_package_id: str = WP_P0_002_ID) -> str:
        return f"""# Work Package

## 1. Metadata

| Field | Value |
| --- | --- |
| ID | {work_package_id} |
"""

    def test_registered_active_record_is_valid(self) -> None:
        errors: list[str] = []
        validate_active_work_package_record_text(
            errors,
            self.current(),
            {WP_P0_002_ID: self.record()},
        )
        self.assertEqual([], errors)

    def test_missing_active_canonical_file_is_rejected(self) -> None:
        errors: list[str] = []
        validate_active_work_package_record_text(errors, self.current(), {})
        self.assertTrue(any("canonical file is missing" in error for error in errors))

    def test_wrong_active_canonical_id_is_rejected(self) -> None:
        errors: list[str] = []
        validate_active_work_package_record_text(
            errors,
            self.current(),
            {WP_P0_002_ID: self.record("WP-P0-003")},
        )
        self.assertTrue(any("canonical file ID" in error for error in errors))


class ClosedAuthorizationStateTests(unittest.TestCase):
    def completed_work_package(
        self,
        authorization: str = "CLOSED",
        status: str = "COMPLETED",
        historic_verdict: str = "APPROVED_FOR_IMPLEMENTATION",
        implementation_result: str = "VERIFIED",
    ) -> str:
        return authorization_work_package(
            authorization,
            status=status,
            explicit_field=True,
            historic_verdict=historic_verdict,
            implementation_result=implementation_result,
        )

    def validate(self, work_package: str | None = None) -> list[str]:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("PLANNING_ONLY", active_work_package="NONE"),
            work_package or self.completed_work_package(),
        )
        return errors

    def test_closed_completed_state_is_valid(self) -> None:
        self.assertEqual([], self.validate())

    def test_completed_with_open_authorization_is_rejected(self) -> None:
        errors = self.validate(self.completed_work_package("APPROVED_FOR_IMPLEMENTATION"))
        self.assertTrue(any("must be CLOSED" in error for error in errors))

    def test_closed_authorization_on_unfinished_work_package_is_rejected(self) -> None:
        errors = self.validate(self.completed_work_package(status="IMPLEMENTING"))
        self.assertTrue(any("Status must be exactly" in error for error in errors))

    def test_candidate_status_is_rejected(self) -> None:
        errors = self.validate(self.completed_work_package(status="IMPLEMENTED_CANDIDATE"))
        self.assertTrue(any("Status must be exactly" in error for error in errors))

    def test_historic_verdict_is_required_as_provenance(self) -> None:
        errors = self.validate(self.completed_work_package(historic_verdict="DESIGN_ONLY"))
        self.assertTrue(any("Historic design verdict" in error for error in errors))

    def test_verified_implementation_result_is_required(self) -> None:
        errors = self.validate(self.completed_work_package(implementation_result="PENDING"))
        self.assertTrue(any("Implementation result" in error for error in errors))

    def test_generic_and_explicit_authorization_are_ambiguous(self) -> None:
        work_package = self.completed_work_package().replace(
            "| Current execution authorization | CLOSED |",
            "| Current execution authorization | CLOSED |\n| Authorization | CLOSED |",
        )
        errors = self.validate(work_package)
        self.assertTrue(any("ambiguous" in error for error in errors))


class ApprovedDesignStateTests(unittest.TestCase):
    def validate(
        self,
        current_state_text: str | None = None,
        work_package_text: str | None = None,
        canonical_design_text: str | None = None,
    ) -> list[str]:
        errors: list[str] = []
        validate_approved_design_state_text(
            errors,
            current_state_text or approved_design_current_state(),
            work_package_text or approved_design_work_package(),
            canonical_design_text,
        )
        return errors

    def test_valid_approved_design_record(self) -> None:
        self.assertEqual([], self.validate(canonical_design_text=canonical_design()))

    def test_missing_canonical_design_is_rejected(self) -> None:
        errors = self.validate(canonical_design_text=None)
        self.assertTrue(any("approved canonical design is missing" in e for e in errors))

    def test_wrong_design_status_is_rejected(self) -> None:
        errors = self.validate(
            canonical_design_text=canonical_design({"status": "DESIGN_ONLY"})
        )
        self.assertTrue(any("design status must be exactly" in e for e in errors))

    def test_wrong_design_work_package_is_rejected(self) -> None:
        errors = self.validate(
            canonical_design_text=canonical_design({"work_package": "WP-P0-999"})
        )
        self.assertTrue(any("design work_package must be exactly" in e for e in errors))

    def test_wrong_design_repository_is_rejected(self) -> None:
        errors = self.validate(
            canonical_design_text=canonical_design({"repository": "other-repository"})
        )
        self.assertTrue(any("design repository must be exactly" in e for e in errors))

    def test_wrong_design_product_is_rejected(self) -> None:
        errors = self.validate(
            canonical_design_text=canonical_design({"product": "Other Product"})
        )
        self.assertTrue(any("design product must be exactly" in e for e in errors))

    def test_wrong_design_document_type_is_rejected(self) -> None:
        errors = self.validate(
            canonical_design_text=canonical_design({"document_type": "other design"})
        )
        self.assertTrue(any("design document_type must be exactly" in e for e in errors))

    def test_missing_current_state_canonical_path_is_rejected(self) -> None:
        errors = self.validate(
            current_state_text=approved_design_current_state(paths=()),
            canonical_design_text=canonical_design(),
        )
        self.assertTrue(
            any("CURRENT_STATE canonical design path is missing" in e for e in errors)
        )

    def test_wrong_current_state_canonical_path_is_rejected(self) -> None:
        errors = self.validate(
            current_state_text=approved_design_current_state(paths=("docs/wrong.md",)),
            canonical_design_text=canonical_design(),
        )
        self.assertTrue(
            any("CURRENT_STATE canonical design path must" in e for e in errors)
        )

    def test_missing_work_package_canonical_path_is_rejected(self) -> None:
        errors = self.validate(
            work_package_text=approved_design_work_package(paths=()),
            canonical_design_text=canonical_design(),
        )
        self.assertTrue(
            any("WP-P0-001 canonical design path is missing" in e for e in errors)
        )

    def test_wrong_work_package_canonical_path_is_rejected(self) -> None:
        errors = self.validate(
            work_package_text=approved_design_work_package(paths=("docs/wrong.md",)),
            canonical_design_text=canonical_design(),
        )
        self.assertTrue(any("WP-P0-001 canonical design path must" in e for e in errors))

    def test_duplicate_design_metadata_is_rejected(self) -> None:
        errors = self.validate(
            canonical_design_text=canonical_design(duplicate_field="repository")
        )
        self.assertTrue(
            any("design repository is missing or duplicated" in e for e in errors)
        )

    def test_missing_design_metadata_field_is_rejected(self) -> None:
        missing_product = canonical_design().replace(
            "product: MarketOps Russia\n",
            "",
        )
        errors = self.validate(canonical_design_text=missing_product)
        self.assertTrue(
            any("design product is missing or duplicated" in e for e in errors)
        )

    def test_malformed_design_metadata_is_rejected(self) -> None:
        errors = self.validate(canonical_design_text="# Canonical Design\n\nNo metadata.\n")
        self.assertTrue(any("leading metadata is malformed" in e for e in errors))

    def test_design_only_state_needs_no_approved_record(self) -> None:
        errors: list[str] = []
        validate_approved_design_state_text(
            errors,
            approved_design_current_state("DESIGN_ONLY", paths=()),
            approved_design_work_package("DESIGN_ONLY", paths=()),
            None,
        )
        self.assertEqual([], errors)


def completed_current_state(
    active_work_package: str = "NONE",
    active_gate: str = "CONTROLLER_PHASE_0_PLANNING",
    objective: str = "Controller performs Phase 0 planning for the next Work Package.",
) -> str:
    return f"""# Current State

```yaml
active_work_package: {active_work_package}
active_gate: {active_gate}
authorization: PLANNING_ONLY
production_write_enabled: false
```

## Active objective

{objective}

## Next authorized action

Controller performs Phase 0 planning for the next Work Package.
"""


def completed_work_package(status: str = "COMPLETED") -> str:
    return f"""# WP-P0-001

## 1. Metadata

| Field | Value |
| --- | --- |
| Status | {status} |
| Historic design verdict | APPROVED_FOR_IMPLEMENTATION |
| Current execution authorization | CLOSED |
| Implementation result | VERIFIED |
"""


def phase_zero_backlog(
    status: str = "COMPLETED",
    *,
    include_wp: bool = True,
    duplicate_wp: bool = False,
    wp_p0_002_status: str = "DRAFT",
    extra_ready: bool = False,
) -> str:
    wp_row = (
        f"| WP-P0-001 | Repository, Governance & CI Foundation | {status} | None | D-03 |\n"
        if include_wp
        else ""
    )
    duplicate = wp_row if duplicate_wp else ""
    extra = (
        "| WP-P0-003 | Ingestion | READY_FOR_DESIGN | WP-P0-001/002 | INT-001 |\n"
        if extra_ready
        else ""
    )
    return f"""# Phase 0 Work Package Backlog

| ID | Title | Status | Dependencies | Core source requirements |
| --- | --- | --- | --- | --- |
{wp_row}{duplicate}| WP-P0-002 | Metadata | {wp_p0_002_status} | WP-P0-001 | IAM-001 |
{extra}"""


class BacklogCompletionStateTests(unittest.TestCase):
    def validate(self, backlog: str) -> list[str]:
        errors: list[str] = []
        validate_backlog_state_text(
            errors,
            completed_current_state(),
            completed_work_package(),
            backlog,
        )
        return errors

    def test_completed_backlog_row_is_valid(self) -> None:
        self.assertEqual([], self.validate(phase_zero_backlog()))

    def test_stale_ready_for_design_status_is_rejected(self) -> None:
        errors = self.validate(phase_zero_backlog("READY_FOR_DESIGN"))
        self.assertTrue(any("Status must be exactly: COMPLETED" in error for error in errors))

    def test_missing_work_package_row_is_rejected(self) -> None:
        errors = self.validate(phase_zero_backlog(include_wp=False))
        self.assertTrue(any("exactly one WP-P0-001 row" in error for error in errors))

    def test_duplicate_work_package_row_is_rejected(self) -> None:
        errors = self.validate(phase_zero_backlog(duplicate_wp=True))
        self.assertTrue(any("exactly one WP-P0-001 row" in error for error in errors))

    def test_unknown_backlog_status_is_rejected(self) -> None:
        errors = self.validate(phase_zero_backlog("UNRECOGNIZED"))
        self.assertTrue(any("unknown Status" in error for error in errors))


def active_design_current_state(
    *,
    active_work_package: str = WP_P0_002_ID,
    active_gate: str = "READY_FOR_DESIGN",
    authorization: str = "DESIGN_ONLY",
) -> str:
    return f"""# Current State

```yaml
active_work_package: {active_work_package}
active_gate: {active_gate}
authorization: {authorization}
production_write_enabled: false
```

## Active objective

Claude produces the WP-P0-002 Design artifact.

## Next authorized action

Claude produces the WP-P0-002 Design artifact. Implementation remains prohibited.
"""


def active_design_work_package(
    *, status: str = "READY_FOR_DESIGN", authorization: str = "DESIGN_ONLY"
) -> str:
    return f"""# WP-P0-002

## 1. Metadata

| Field | Value |
| --- | --- |
| ID | WP-P0-002 |
| Status | {status} |
| Authorization | {authorization} |
"""


class ActiveDesignBacklogTests(unittest.TestCase):
    def validate(
        self,
        *,
        current: str | None = None,
        backlog: str | None = None,
        active_wp: str | None = None,
    ) -> list[str]:
        errors: list[str] = []
        validate_backlog_state_text(
            errors,
            current or active_design_current_state(),
            completed_work_package(),
            backlog or phase_zero_backlog(wp_p0_002_status="READY_FOR_DESIGN"),
            active_wp or active_design_work_package(),
        )
        return errors

    def test_active_design_backlog_transition_is_valid(self) -> None:
        self.assertEqual([], self.validate())

    def test_multiple_ready_for_design_rows_are_rejected(self) -> None:
        errors = self.validate(
            backlog=phase_zero_backlog(
                wp_p0_002_status="READY_FOR_DESIGN", extra_ready=True
            )
        )
        self.assertTrue(any("multiple READY_FOR_DESIGN" in error for error in errors))

    def test_active_backlog_row_must_match_current_state(self) -> None:
        errors = self.validate(
            current=active_design_current_state(active_work_package="WP-P0-003")
        )
        self.assertTrue(any("active Work Package row" in error for error in errors))

    def test_active_work_package_status_must_match_backlog(self) -> None:
        errors = self.validate(active_wp=active_design_work_package(status="DRAFT"))
        self.assertTrue(any("active Work Package Status" in error for error in errors))

    def test_active_implementation_backlog_transition_is_valid(self) -> None:
        errors = self.validate(
            current=active_design_current_state(
                active_gate="IMPLEMENTING",
                authorization="APPROVED_FOR_IMPLEMENTATION",
            ),
            backlog=phase_zero_backlog(wp_p0_002_status="IMPLEMENTING"),
            active_wp=active_design_work_package(
                status="IMPLEMENTING",
                authorization="APPROVED_FOR_IMPLEMENTATION",
            ),
        )
        self.assertEqual([], errors)

    def test_implementing_state_rejects_a_ready_for_design_row(self) -> None:
        errors = self.validate(
            current=active_design_current_state(
                active_gate="IMPLEMENTING",
                authorization="APPROVED_FOR_IMPLEMENTATION",
            ),
            backlog=phase_zero_backlog(
                wp_p0_002_status="IMPLEMENTING", extra_ready=True
            ),
            active_wp=active_design_work_package(
                status="IMPLEMENTING",
                authorization="APPROVED_FOR_IMPLEMENTATION",
            ),
        )
        self.assertTrue(
            any("cannot retain a READY_FOR_DESIGN" in error for error in errors)
        )

    def test_implementing_stage_requires_an_implementing_backlog_row(self) -> None:
        errors = self.validate(
            current=active_design_current_state(
                active_gate="IMPLEMENTING",
                authorization="APPROVED_FOR_IMPLEMENTATION",
            ),
            backlog=phase_zero_backlog(wp_p0_002_status="READY_FOR_DESIGN"),
            active_wp=active_design_work_package(
                status="IMPLEMENTING",
                authorization="APPROVED_FOR_IMPLEMENTATION",
            ),
        )
        self.assertTrue(
            any("Status must be exactly: IMPLEMENTING" in error for error in errors)
        )

    def test_closed_state_rejects_ready_for_design_row(self) -> None:
        errors: list[str] = []
        validate_backlog_state_text(
            errors,
            completed_current_state(),
            completed_work_package(),
            phase_zero_backlog(wp_p0_002_status="READY_FOR_DESIGN"),
        )
        self.assertTrue(any("cannot retain" in error for error in errors))


def completed_traceability(
    changed_id: str | None = None,
    changed_field: str | None = None,
    changed_value: str = "",
) -> str:
    header = (
        "source_id,source_type,phase,title,work_package,design_record,"
        "code_location,test_case,evidence,status,notes"
    )
    rows = []
    for source_id in ("D-02", "D-03", "D-07", "D-10", "D-15", "D-16", "D-17", "HR-06"):
        row = {
            "source_id": source_id,
            "source_type": "Owner Decision",
            "phase": "0",
            "title": "completed control",
            "work_package": (
                "WP-P0-001;WP-P0-003" if source_id == "D-03" else "WP-P0-001"
            ),
            "design_record": "design.md",
            "code_location": "code.java",
            "test_case": "TC-GOV-001",
            "evidence": "evidence.md",
            "status": (
                "ACTIVE_CONTROL"
                if source_id in {"D-03", "D-15", "D-16", "D-17"}
                else "VERIFIED"
            ),
            "notes": (
                "MULTI-WP: Modular Monolith foundation verified; the internal "
                "PostgreSQL Task/Worker is allocated to WP-P0-003 and explicitly "
                "excludes INT-017; D-03 remains ACTIVE_CONTROL"
                if source_id == "D-03"
                else "complete"
            ),
        }
        if source_id == changed_id and changed_field is not None:
            row[changed_field] = changed_value
        rows.append(",".join(row[field] for field in header.split(",")))
    return header + "\n" + "\n".join(rows) + "\n"


class CompletionStateTests(unittest.TestCase):
    def validate(
        self,
        current: str | None = None,
        work_package: str | None = None,
        traceability: str | None = None,
    ) -> list[str]:
        errors: list[str] = []
        validate_completion_state_text(
            errors,
            current or completed_current_state(),
            work_package or completed_work_package(),
            traceability or completed_traceability(),
        )
        return errors

    def test_completed_transition_is_valid(self) -> None:
        self.assertEqual([], self.validate())

    def test_candidate_status_regression_is_rejected(self) -> None:
        errors = self.validate(work_package=completed_work_package("IMPLEMENTED_CANDIDATE"))
        self.assertTrue(any("Status must be exactly" in error for error in errors))

    def test_stale_active_work_package_is_rejected(self) -> None:
        errors = self.validate(current=completed_current_state(active_work_package="WP-P0-001"))
        self.assertTrue(any("active_work_package" in error for error in errors))

    def test_stale_gate_is_rejected(self) -> None:
        errors = self.validate(current=completed_current_state(active_gate="PULL_REQUEST_GATE"))
        self.assertTrue(any("active_gate" in error for error in errors))

    def test_stale_objective_is_rejected(self) -> None:
        errors = self.validate(current=completed_current_state(objective="Produce the artifact."))
        self.assertTrue(any("Active objective" in error for error in errors))

    def test_in_progress_traceability_is_rejected(self) -> None:
        errors = self.validate(
            traceability=completed_traceability("D-03", "status", "IN_PROGRESS")
        )
        self.assertTrue(any("D-03 status" in error for error in errors))

    def test_d03_fully_verified_without_worker_evidence_is_rejected(self) -> None:
        errors = self.validate(
            traceability=completed_traceability("D-03", "status", "VERIFIED")
        )
        self.assertTrue(any("must remain ACTIVE_CONTROL" in error for error in errors))

    def test_d03_wrong_worker_work_package_is_rejected(self) -> None:
        errors = self.validate(
            traceability=completed_traceability("D-03", "work_package", "WP-P0-001")
        )
        self.assertTrue(any("D-03 work_package" in error for error in errors))

    def test_missing_traceability_evidence_is_rejected(self) -> None:
        errors = self.validate(
            traceability=completed_traceability("HR-06", "evidence", "")
        )
        self.assertTrue(any("HR-06 missing evidence" in error for error in errors))

    def test_wp_p0_002_active_design_transition_preserves_wp_p0_001_closure(self) -> None:
        self.assertEqual([], self.validate(current=active_design_current_state()))

    def test_wp_p0_002_active_design_requires_design_only(self) -> None:
        errors = self.validate(
            current=active_design_current_state(authorization="PLANNING_ONLY")
        )
        self.assertTrue(any("requires authorization" in error for error in errors))

    def test_wp_p0_002_next_action_must_prohibit_implementation(self) -> None:
        current = active_design_current_state().replace(
            "Implementation remains prohibited.",
            "Implementation may begin.",
        )
        errors = self.validate(current=current)
        self.assertTrue(any("must prohibit implementation" in error for error in errors))


class ReadmeRuntimeStateTests(unittest.TestCase):
    def test_stable_current_state_entry_is_valid(self) -> None:
        errors: list[str] = []
        validate_readme_runtime_state_text(
            errors,
            "Current runtime state: docs/00-governance/CURRENT_STATE.md\n",
        )
        self.assertEqual([], errors)

    def test_duplicate_active_work_package_claim_is_rejected(self) -> None:
        errors: list[str] = []
        validate_readme_runtime_state_text(
            errors,
            "docs/00-governance/CURRENT_STATE.md\n"
            "当前活动 Work Package：WP-P0-002\n",
        )
        self.assertTrue(any("duplicates" in error for error in errors))

    def test_missing_current_state_entry_is_rejected(self) -> None:
        errors: list[str] = []
        validate_readme_runtime_state_text(errors, "# README\n")
        self.assertTrue(any("must link" in error for error in errors))


def controller_standard_fixture() -> str:
    return "\n".join(
        (
            "## 2. The 11+1 review standard",
            "Full repository cross-check",
            "Full production-grade scope",
            "No in-scope deferred item",
            "No compromise implementation",
            "Three global hard rules",
            "Standalone review and prompt artifacts",
            "+1 — Project-grade distinction",
            "## 4. Artifact Contract",
            "Controller Review `.md`",
            "Next-action Prompt `.md`",
            "SHA-256",
            "NEXT_AUTHORIZED_ACTOR",
            "NEXT_ACTION",
            "natural Chinese",
        )
    )


class ControllerReviewStandardTests(unittest.TestCase):
    def instructions(self) -> str:
        return (
            "At the start of every Controller task read "
            "CONTROLLER_REVIEW_STANDARD.md and apply its 11+1 review standard."
        )

    def test_complete_standard_and_loader_are_valid(self) -> None:
        errors: list[str] = []
        validate_controller_review_standard_text(
            errors,
            controller_standard_fixture(),
            self.instructions(),
        )
        self.assertEqual([], errors)

    def test_missing_artifact_hash_contract_is_rejected(self) -> None:
        errors: list[str] = []
        validate_controller_review_standard_text(
            errors,
            controller_standard_fixture().replace("SHA-256", "hash omitted"),
            self.instructions(),
        )
        self.assertTrue(any("SHA-256" in error for error in errors))

    def test_project_instructions_must_load_standard(self) -> None:
        errors: list[str] = []
        validate_controller_review_standard_text(
            errors,
            controller_standard_fixture(),
            "No task-start standard.",
        )
        self.assertTrue(any("do not load" in error for error in errors))


def wp_p0_002_traceability(*, completed: bool = False) -> str:
    base = completed_traceability().rstrip("\n")
    rows = [
        ("IAM-001", "WP-P0-002", "PARTIAL in WP-P0-002; runtime IAM"),
        ("IAM-004", "WP-P0-002", "PARTIAL in WP-P0-002; runtime IAM"),
        ("IAM-006", "WP-P0-002", "PARTIAL in WP-P0-002; runtime integration"),
        ("IAM-007", "WP-P0-002", "PARTIAL in WP-P0-002; runtime IAM"),
        (
            "INT-002",
            "WP-P0-002;WP-P0-005;WP-P0-006",
            "PARTIAL in WP-P0-002; WP-P0-005/006",
        ),
        ("INT-003", "WP-P0-002", "PARTIAL in WP-P0-002; OQ-006"),
        ("ADM-001", "WP-P0-002", "FULL closure in WP-P0-002; fail-closed"),
        (
            "ADM-002",
            "WP-P0-002;WP-P0-003;WP-P0-005;WP-P0-006",
            "PARTIAL in WP-P0-002; WP-P0-003; WP-P0-005/006",
        ),
    ]
    completed_notes = {
        "IAM-001": "PARTIAL in WP-P0-002; runtime IAM; WP-P0-002 subset VERIFIED; whole source requirement remains OPEN",
        "IAM-004": "PARTIAL in WP-P0-002; runtime IAM; WP-P0-002 subset VERIFIED; whole source requirement remains OPEN",
        "IAM-006": "PARTIAL in WP-P0-002; runtime integration; WP-P0-002 subset VERIFIED; whole source requirement remains OPEN",
        "IAM-007": "PARTIAL in WP-P0-002; runtime IAM; WP-P0-002 subset VERIFIED; whole source requirement remains OPEN",
        "INT-002": "PARTIAL in WP-P0-002; WP-P0-005/006; WP-P0-002 subset VERIFIED; whole source requirement remains OPEN",
        "INT-003": "PARTIAL in WP-P0-002; OQ-006; WP-P0-002 subset VERIFIED; whole source requirement remains OPEN",
        "ADM-001": "FULL closure in WP-P0-002; fail-closed; WP-P0-002 VERIFIED",
        "ADM-002": "PARTIAL in WP-P0-002; WP-P0-003; WP-P0-005/006; WP-P0-002 subset VERIFIED; whole source requirement remains OPEN",
    }
    additions = []
    for source_id, work_packages, notes in rows:
        status = "VERIFIED" if completed and source_id == "ADM-001" else "ACTIVE_CONTROL" if completed else "PLANNED"
        evidence = (
            "docs/07-phase-evidence/WP-P0-002/acceptance-criteria.md"
            if completed
            else "evidence"
        )
        final_notes = completed_notes[source_id] if completed else notes
        additions.append(
            f'{source_id},Requirement,0,title,{work_packages},'
            f'{WP_P0_002_DESIGN_RELATIVE_PATH},module,test-case,{evidence},{status},"{final_notes}"'
        )
    return base + "\n" + "\n".join(additions) + "\n"


class WpP0002TraceabilityTests(unittest.TestCase):
    def validate(self, text: str) -> list[str]:
        errors: list[str] = []
        validate_wp_p0_002_traceability_text(errors, text)
        return errors

    def test_complete_partial_full_contract_is_valid(self) -> None:
        self.assertEqual([], self.validate(wp_p0_002_traceability()))

    def test_partial_requirement_cannot_be_preverified(self) -> None:
        text = wp_p0_002_traceability().replace(
            "evidence,PLANNED,\"PARTIAL in WP-P0-002; OQ-006\"",
            "evidence,VERIFIED,\"PARTIAL in WP-P0-002; OQ-006\"",
        )
        errors = self.validate(text)
        self.assertTrue(any("must remain PLANNED" in error for error in errors))

    def test_unfilled_implementation_columns_are_rejected(self) -> None:
        text = wp_p0_002_traceability().replace(
            "module,test-case,evidence,PLANNED,\"PARTIAL in WP-P0-002; OQ-006\"",
            "module,,evidence,PLANNED,\"PARTIAL in WP-P0-002; OQ-006\"",
        )
        errors = self.validate(text)
        self.assertTrue(any("missing test_case" in error for error in errors))

    def test_missing_later_closure_disposition_is_rejected(self) -> None:
        text = wp_p0_002_traceability().replace(
            "PARTIAL in WP-P0-002; WP-P0-005/006",
            "PARTIAL in WP-P0-002",
        )
        errors = self.validate(text)
        self.assertTrue(any("WP-P0-005/006" in error for error in errors))

    def test_wrong_adm002_work_package_allocation_is_rejected(self) -> None:
        text = wp_p0_002_traceability().replace(
            "ADM-002,Requirement,0,title,WP-P0-002;WP-P0-003;WP-P0-005;WP-P0-006",
            "ADM-002,Requirement,0,title,WP-P0-002",
        )
        errors = self.validate(text)
        self.assertTrue(any("ADM-002 work_package" in error for error in errors))

    def test_completed_partial_and_full_contract_is_valid(self) -> None:
        errors: list[str] = []
        validate_wp_p0_002_traceability_text(
            errors, wp_p0_002_traceability(completed=True), completed=True
        )
        self.assertEqual([], errors)

    def test_partial_requirement_cannot_be_fully_verified_after_completion(self) -> None:
        text = wp_p0_002_traceability(completed=True).replace(
            "evidence/WP-P0-002/acceptance-criteria.md,ACTIVE_CONTROL,\"PARTIAL in WP-P0-002; OQ-006",
            "evidence/WP-P0-002/acceptance-criteria.md,VERIFIED,\"PARTIAL in WP-P0-002; OQ-006",
        )
        errors: list[str] = []
        validate_wp_p0_002_traceability_text(errors, text, completed=True)
        self.assertTrue(any("not fully VERIFIED" in error for error in errors))

    def test_adm001_full_closure_must_be_verified(self) -> None:
        text = wp_p0_002_traceability(completed=True).replace(
            "acceptance-criteria.md,VERIFIED,\"FULL closure in WP-P0-002",
            "acceptance-criteria.md,ACTIVE_CONTROL,\"FULL closure in WP-P0-002",
        )
        errors: list[str] = []
        validate_wp_p0_002_traceability_text(errors, text, completed=True)
        self.assertTrue(any("ADM-001 status must be exactly: VERIFIED" in error for error in errors))


def wp_p0_002_closure_current_state() -> str:
    return """# Current State

```yaml
active_work_package: NONE
active_gate: CONTROLLER_PHASE_0_PLANNING
authorization: PLANNING_ONLY
production_write_enabled: false
owner_git_workflow_guidance: REQUIRED
owner_git_workflow_guidance_exit: HUMAN_OWNER_EXPLICIT_CONFIRMATION
owner_git_execution_delegation: ACTIVE
owner_git_execution_delegate: CODEX
owner_git_execution_delegation_scope: PR_READY_AND_MERGE_AFTER_ALL_GATES
owner_git_execution_delegation_exit: HUMAN_OWNER_EXPLICIT_REVOCATION
```

## Completed

PR #10 was squash-merged as
203b509e765959560fdfbd0edbde428ba9c6d763 with merged tree
6a2db6f565b29847bed6065d2b04d1df800b516b. Post-merge Controller artifact
SHA-256: 4e65f0a7fb1c997096c5fd98fb56f42211c546cca323fae5b12d39eaa0c1c8ab.

## Active objective

Controller Phase 0 planning selects the next bounded Work Package.
WP-P0-003 remains DRAFT.

## Next authorized action

Controller Phase 0 planning retains the Design Gate. WP-P0-003 remains DRAFT.

OQ-101 OQ-005 OQ-006 OQ-102 remain open.
"""


def wp_p0_002_completed_work_package() -> str:
    return f"""# WP-P0-002

## 1. Metadata

| Field | Value |
| --- | --- |
| ID | WP-P0-002 |
| Status | COMPLETED |
| Historic design verdict | APPROVED_FOR_IMPLEMENTATION |
| Current execution authorization | CLOSED |
| Implementation result | VERIFIED |
| Design artifact | `{WP_P0_002_DESIGN_RELATIVE_PATH}` |
| Approved Design v1.2 SHA-256 | 3e524c666e56b3d5fdecd6e2098a22d1bd9fd88711dd9c524858ca0cdd3859b2 |

PR #10 state: MERGED / CLOSED / NOT_DRAFT
Controller merge verdict: PASS — APPROVE_FOR_HUMAN_MERGE
Controller approval artifact SHA-256: d477bb77846d1c9f3f50de58a6795450327b445853794fc38192ee96d4cd3c9f
The Human Owner approved D-17 Ready and squash merge of PR #10 on the exact accepted identity.
Approved Base: 3c4f6a6210db377b5471d6014da6afd5bfef6127
Approved Head: ce8eb44f2f750d73d7329fb78a17640ef3fc80c1
Approved tested merge: fdcbf2bc69a0a80d1b6fb98455e91bf7e6373fef
Squash merge SHA: 203b509e765959560fdfbd0edbde428ba9c6d763
Merged main tree: 6a2db6f565b29847bed6065d2b04d1df800b516b
Squash parent: 3c4f6a6210db377b5471d6014da6afd5bfef6127
Commit signature: VERIFIED
Post-merge Controller verdict: PASS — MERGE_EXECUTION_VERIFIED
Next lifecycle state: Controller Phase 0 planning
WP-P0-003 remains DRAFT
Production writes: DISABLED
"""


def wp_p0_002_closure_backlog(wp_status: str = "COMPLETED", wp3_status: str = "DRAFT") -> str:
    return f"""# Phase 0 Work Package Backlog

| ID | Title | Status | Dependencies | Core source requirements |
| --- | --- | --- | --- | --- |
| WP-P0-001 | Foundation | COMPLETED | None | D-03 |
| WP-P0-002 | Metadata | {wp_status} | WP-P0-001 | IAM-001 |
| WP-P0-003 | Ingestion | {wp3_status} | WP-P0-001/002 | INT-001 |
"""


def wp_p0_002_closure_evidence(extra: str = "") -> str:
    return """# Evidence

docs/07-phase-evidence/WP-P0-002/acceptance-criteria.md
PR #10 is merged and closed.
Controller verdict: PASS — APPROVE_FOR_HUMAN_MERGE
Controller approval artifact: d477bb77846d1c9f3f50de58a6795450327b445853794fc38192ee96d4cd3c9f
The Human Owner approved D-17 Ready and squash merge of PR #10 on the exact accepted identity.
Approved Base: 3c4f6a6210db377b5471d6014da6afd5bfef6127
Approved Head: ce8eb44f2f750d73d7329fb78a17640ef3fc80c1
Approved tested merge: fdcbf2bc69a0a80d1b6fb98455e91bf7e6373fef
Squash merge: 203b509e765959560fdfbd0edbde428ba9c6d763
Merged main tree: 6a2db6f565b29847bed6065d2b04d1df800b516b
Squash parent | `3c4f6a6210db377b5471d6014da6afd5bfef6127`
Merged at: 2026-08-19T17:44:16Z
Commit signature: VERIFIED
Post-merge Controller artifact: 4e65f0a7fb1c997096c5fd98fb56f42211c546cca323fae5b12d39eaa0c1c8ab
Remote task branch: deleted after merge
Controller Phase 0 planning is next.
WP-P0-003 remains DRAFT.
production_write_enabled: false
OQ-101 OQ-005 OQ-006 OQ-102 remain open.
""" + extra


def wp_p0_002_acceptance_evidence(count: int = 16) -> str:
    header = (
        "PR #10 merged as 203b509e765959560fdfbd0edbde428ba9c6d763 "
        "with tree 6a2db6f565b29847bed6065d2b04d1df800b516b. "
        "WP-P0-003 remains DRAFT.\n\n"
        "| Criterion | Criterion text | Closure status | Production location | "
        "Exact tests | Exact evidence | Remaining boundary |\n"
        "| --- | --- | --- | --- | --- | --- | --- |\n"
    )
    rows = "".join(
        f"| {number} | criterion {number} | VERIFIED | production | test | evidence | boundary |\n"
        for number in range(1, count + 1)
    )
    return header + rows


IDENTITY_TEST_PATH = (
    "backend/marketops-server/src/test/java/com/mimococo/marketops/ExampleApiIT.java"
)


def identity_java_source(*definitions: tuple[str, str]) -> str:
    methods = "\n".join(
        f'    @DisplayName("{test_id} {method}")\n'
        f"    void {method}() {{}}"
        for test_id, method in definitions
    )
    return f"class ExampleApiIT {{\n{methods}\n}}\n"


def identity_traceability(*test_ids: str) -> str:
    return (
        "source_id,source_type,phase,title,work_package,design_record,"
        "code_location,test_case,evidence,status,notes\n"
        "IAM-001,Requirement,0,title,WP-P0-002,design,code,"
        + ";".join(test_ids)
        + ",evidence,ACTIVE_CONTROL,notes\n"
    )


def identity_acceptance(*bindings: tuple[str, str, str]) -> str:
    references = "<br>".join(
        f"`{relative}#{method}` (`{test_id}`)"
        for relative, method, test_id in bindings
    )
    return (
        "| Criterion | Criterion text | Closure status | Production location | "
        "Exact tests | Exact evidence | Remaining boundary |\n"
        "| --- | --- | --- | --- | --- | --- | --- |\n"
        f"| 1 | criterion | VERIFIED | production | {references} | evidence | boundary |\n"
    )


def identity_strategy(*test_ids: str) -> str:
    return "| " + ", ".join(test_ids) + " | coverage | suite |\n"


class WpP0002TestIdentityContractTests(unittest.TestCase):
    def validate(
        self,
        *,
        sources: dict[str, str],
        trace_ids: tuple[str, ...],
        bindings: tuple[tuple[str, str, str], ...],
        strategy_ids: tuple[str, ...],
    ) -> list[str]:
        errors: list[str] = []
        validate_wp_p0_002_test_identity_contract(
            errors,
            identity_traceability(*trace_ids),
            identity_acceptance(*bindings),
            identity_strategy(*strategy_ids),
            sources,
        )
        return errors

    def test_duplicate_tc_api_080_is_rejected(self) -> None:
        errors = self.validate(
            sources={
                IDENTITY_TEST_PATH: identity_java_source(
                    ("TC-API-080", "first"), ("TC-API-080", "second")
                )
            },
            trace_ids=("TC-API-080",),
            bindings=((IDENTITY_TEST_PATH, "first", "TC-API-080"),),
            strategy_ids=("TC-API-080",),
        )
        self.assertTrue(
            any("duplicate Java test ID TC-API-080" in error for error in errors)
        )

    def test_duplicate_tc_api_081_is_rejected(self) -> None:
        errors = self.validate(
            sources={
                IDENTITY_TEST_PATH: identity_java_source(
                    ("TC-API-081", "first"), ("TC-API-081", "second")
                )
            },
            trace_ids=("TC-API-081",),
            bindings=((IDENTITY_TEST_PATH, "first", "TC-API-081"),),
            strategy_ids=("TC-API-081",),
        )
        self.assertTrue(
            any("duplicate Java test ID TC-API-081" in error for error in errors)
        )

    def test_acceptance_method_with_another_methods_id_is_rejected(self) -> None:
        errors = self.validate(
            sources={
                IDENTITY_TEST_PATH: identity_java_source(
                    ("TC-API-080", "first"), ("TC-API-081", "second")
                )
            },
            trace_ids=("TC-API-080", "TC-API-081"),
            bindings=((IDENTITY_TEST_PATH, "first", "TC-API-081"),),
            strategy_ids=("TC-API-080", "TC-API-081"),
        )
        self.assertTrue(
            any("belongs to" in error and "not" in error for error in errors)
        )

    def test_acceptance_id_without_exact_method_binding_is_rejected(self) -> None:
        sources = {
            IDENTITY_TEST_PATH: identity_java_source(
                ("TC-API-080", "first"), ("TC-API-081", "second")
            )
        }
        acceptance = identity_acceptance(
            (IDENTITY_TEST_PATH, "first", "TC-API-080")
        ).replace(
            " | evidence | boundary |",
            "<br>`TC-API-081` | evidence | boundary |",
        )
        errors: list[str] = []
        validate_wp_p0_002_test_identity_contract(
            errors,
            identity_traceability("TC-API-080", "TC-API-081"),
            acceptance,
            identity_strategy("TC-API-080", "TC-API-081"),
            sources,
        )
        self.assertTrue(any("not bound exactly once" in error for error in errors))

    def test_traceability_missing_id_is_rejected(self) -> None:
        errors = self.validate(
            sources={
                IDENTITY_TEST_PATH: identity_java_source(("TC-API-080", "first"))
            },
            trace_ids=("TC-API-081",),
            bindings=((IDENTITY_TEST_PATH, "first", "TC-API-080"),),
            strategy_ids=("TC-API-080",),
        )
        self.assertTrue(
            any("cites missing test ID: TC-API-081" in error for error in errors)
        )

    def test_traceability_duplicated_id_is_rejected(self) -> None:
        errors = self.validate(
            sources={
                IDENTITY_TEST_PATH: identity_java_source(
                    ("TC-API-082", "first"), ("TC-API-082", "second")
                )
            },
            trace_ids=("TC-API-082",),
            bindings=((IDENTITY_TEST_PATH, "first", "TC-API-082"),),
            strategy_ids=("TC-API-082",),
        )
        self.assertTrue(
            any("cites ambiguous test ID: TC-API-082" in error for error in errors)
        )

    def test_unique_correctly_bound_ids_pass(self) -> None:
        errors = self.validate(
            sources={
                IDENTITY_TEST_PATH: identity_java_source(
                    ("TC-API-080", "first"), ("TC-API-081", "second")
                )
            },
            trace_ids=("TC-API-080", "TC-API-081"),
            bindings=(
                (IDENTITY_TEST_PATH, "first", "TC-API-080"),
                (IDENTITY_TEST_PATH, "second", "TC-API-081"),
            ),
            strategy_ids=("TC-API-080", "TC-API-081"),
        )
        self.assertEqual([], errors)

    def test_test_strategy_omission_is_rejected(self) -> None:
        errors = self.validate(
            sources={
                IDENTITY_TEST_PATH: identity_java_source(
                    ("TC-API-080", "first"), ("TC-API-081", "second")
                )
            },
            trace_ids=("TC-API-080", "TC-API-081"),
            bindings=(
                (IDENTITY_TEST_PATH, "first", "TC-API-080"),
                (IDENTITY_TEST_PATH, "second", "TC-API-081"),
            ),
            strategy_ids=("TC-API-080",),
        )
        self.assertTrue(any("omits implemented" in error for error in errors))

    def test_inventory_records_nested_group_and_occurrence_count(self) -> None:
        source = (
            'class ExampleApiIT {\n'
            '    @DisplayName("TC-SEC-201 codes")\n'
            '    class Codes {}\n'
            '}\n'
        )
        inventory, errors = java_test_identity_inventory(
            {IDENTITY_TEST_PATH: source}
        )
        self.assertEqual([], errors)
        self.assertEqual("nested_group", inventory[0].member_kind)
        self.assertEqual("Codes", inventory[0].member)
        self.assertEqual(1, inventory[0].occurrence_count)


class WpP0002ClosureStateTests(unittest.TestCase):
    def validate(
        self,
        *,
        current: str | None = None,
        work_package: str | None = None,
        backlog: str | None = None,
        traceability: str | None = None,
        evidence: str | None = None,
        acceptance: str | None = None,
    ) -> list[str]:
        errors: list[str] = []
        validate_wp_p0_002_completion_text(
            errors,
            current or wp_p0_002_closure_current_state(),
            work_package or wp_p0_002_completed_work_package(),
            backlog or wp_p0_002_closure_backlog(),
            traceability or wp_p0_002_traceability(completed=True),
            evidence or wp_p0_002_closure_evidence(),
            acceptance or wp_p0_002_acceptance_evidence(),
        )
        return errors

    def test_complete_closure_state_is_valid(self) -> None:
        self.assertEqual([], self.validate())
        self.assertEqual(
            [str(number) for number in range(1, 17)],
            [
                row["Criterion"]
                for row in wp_p0_002_acceptance_rows(
                    wp_p0_002_acceptance_evidence()
                ) or []
            ],
        )

    def test_completed_work_package_cannot_remain_active(self) -> None:
        current = wp_p0_002_closure_current_state().replace(
            "active_work_package: NONE", "active_work_package: WP-P0-002"
        )
        errors = self.validate(current=current)
        self.assertTrue(any("cannot remain the active" in error for error in errors))

    def test_closed_planning_rejects_implementing_backlog(self) -> None:
        errors = self.validate(backlog=wp_p0_002_closure_backlog("IMPLEMENTING"))
        self.assertTrue(any("closed planning state" in error for error in errors))

    def test_completed_backlog_rejects_implementing_work_package(self) -> None:
        work_package = wp_p0_002_completed_work_package().replace(
            "| Status | COMPLETED |", "| Status | IMPLEMENTING |"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("requires the Work Package Status COMPLETED" in error for error in errors))

    def test_closed_authorization_is_required(self) -> None:
        work_package = wp_p0_002_completed_work_package().replace(
            "| Current execution authorization | CLOSED |",
            "| Current execution authorization | APPROVED_FOR_IMPLEMENTATION |",
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("authorization must be CLOSED" in error for error in errors))

    def test_verified_result_is_required(self) -> None:
        work_package = wp_p0_002_completed_work_package().replace(
            "| Implementation result | VERIFIED |",
            "| Implementation result | PENDING |",
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("Implementation result" in error for error in errors))

    def test_historic_design_identity_is_required(self) -> None:
        work_package = wp_p0_002_completed_work_package().replace(
            "3e524c666e56b3d5fdecd6e2098a22d1bd9fd88711dd9c524858ca0cdd3859b2",
            "0" * 64,
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("Approved Design v1.2 SHA-256" in error for error in errors))

    def test_missing_acceptance_row_is_rejected(self) -> None:
        errors = self.validate(acceptance=wp_p0_002_acceptance_evidence(15))
        self.assertTrue(any("criteria 1 through 16" in error for error in errors))

    def test_nonverified_acceptance_row_is_rejected(self) -> None:
        acceptance = wp_p0_002_acceptance_evidence().replace(
            "| 8 | criterion 8 | VERIFIED |",
            "| 8 | criterion 8 | PLANNED |",
        )
        errors = self.validate(acceptance=acceptance)
        self.assertTrue(any("criterion 8 must be VERIFIED" in error for error in errors))

    def test_stale_pending_evidence_is_rejected(self) -> None:
        errors = self.validate(
            evidence=wp_p0_002_closure_evidence(
                "\nrepository CI Gate: pending the repair push\n"
            )
        )
        self.assertTrue(any("stale repository CI pending" in error for error in errors))

    def test_design_activation_denial_is_rejected(self) -> None:
        work_package = wp_p0_002_completed_work_package() + (
            "\nThis Design-activation PR does not verify any requirement.\n"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("stale Design-activation denial" in error for error in errors))

    def test_eventual_implementation_narration_is_rejected(self) -> None:
        work_package = wp_p0_002_completed_work_package() + (
            "\nwhen WP-P0-002 is eventually\nimplemented, evidence follows.\n"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("stale future implementation" in error for error in errors))

    def test_live_claude_design_instruction_is_rejected(self) -> None:
        work_package = wp_p0_002_completed_work_package() + (
            "\nClaude Design must specify the future contract.\n"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("stale live Claude Design" in error for error in errors))

    def test_live_claude_delivery_instruction_is_rejected(self) -> None:
        work_package = wp_p0_002_completed_work_package() + (
            "\nClaude must return a standalone artifact.\n"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("stale live Claude delivery" in error for error in errors))

    def test_live_planning_record_instruction_is_rejected(self) -> None:
        work_package = wp_p0_002_completed_work_package() + (
            "\nthis Planning record does not decide the API.\n"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("stale live Planning record" in error for error in errors))

    def test_live_after_design_return_instruction_is_rejected(self) -> None:
        work_package = wp_p0_002_completed_work_package() + (
            "\nAfter Design return, the Controller reviews the artifact.\n"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("stale live post-Design" in error for error in errors))

    def test_overbroad_domain_table_absence_is_rejected(self) -> None:
        current = wp_p0_002_closure_current_state() + (
            "\nAll business/domain tables remain absent.\n"
        )
        errors = self.validate(current=current)
        self.assertTrue(any("overbroad domain-table absence" in error for error in errors))

    def test_wrong_squash_sha_is_rejected(self) -> None:
        evidence = wp_p0_002_closure_evidence().replace(
            "Squash merge: 203b509e765959560fdfbd0edbde428ba9c6d763",
            "Squash merge: " + "0" * 40,
        )
        errors = self.validate(evidence=evidence)
        self.assertTrue(
            any("missing required post-merge provenance" in error for error in errors)
        )

    def test_wrong_merged_tree_is_rejected(self) -> None:
        evidence = wp_p0_002_closure_evidence().replace(
            "Merged main tree: 6a2db6f565b29847bed6065d2b04d1df800b516b",
            "Merged main tree: " + "0" * 40,
        )
        errors = self.validate(evidence=evidence)
        self.assertTrue(
            any("6a2db6f565b29847bed6065d2b04d1df800b516b" in error for error in errors)
        )

    def test_wrong_squash_parent_is_rejected(self) -> None:
        evidence = wp_p0_002_closure_evidence().replace(
            "Squash parent | `3c4f6a6210db377b5471d6014da6afd5bfef6127`",
            "Squash parent | `" + "0" * 40 + "`",
        )
        errors = self.validate(evidence=evidence)
        self.assertTrue(
            any("3c4f6a6210db377b5471d6014da6afd5bfef6127" in error for error in errors)
        )

    def test_stale_draft_pr_wording_is_rejected(self) -> None:
        current = wp_p0_002_closure_current_state() + "\nPR #10 remains Draft.\n"
        errors = self.validate(current=current)
        self.assertTrue(any("stale Draft PR state" in error for error in errors))

    def test_stale_not_merged_wording_is_rejected(self) -> None:
        current = (
            wp_p0_002_closure_current_state()
            + "\nPR #10 remains a Draft closure candidate and is not merged.\n"
        )
        errors = self.validate(current=current)
        self.assertTrue(any("stale not-merged PR state" in error for error in errors))

    def test_stale_final_controller_review_wording_is_rejected(self) -> None:
        evidence = wp_p0_002_closure_evidence(
            "\nawaiting final independent Controller re-review\n"
        )
        errors = self.validate(evidence=evidence)
        self.assertTrue(
            any("stale final Controller re-review pending" in error for error in errors)
        )

    def test_stale_ready_and_merge_authorization_wording_is_rejected(self) -> None:
        work_package = wp_p0_002_completed_work_package() + (
            "\nReady: NOT_AUTHORIZED\nMerge: NOT_AUTHORIZED\n"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("stale Ready authorization pending" in error for error in errors))
        self.assertTrue(any("stale merge authorization pending" in error for error in errors))

    def test_stale_future_merge_condition_is_rejected(self) -> None:
        current = wp_p0_002_closure_current_state() + (
            "\nIf that exact closure Head is later merged, planning resumes.\n"
        )
        errors = self.validate(current=current)
        self.assertTrue(any("stale future closure merge" in error for error in errors))

    def test_stale_draft_closure_evidence_location_is_rejected(self) -> None:
        acceptance = wp_p0_002_acceptance_evidence() + (
            "\nThe final closure Head and tree belong in Draft PR #10.\n"
        )
        errors = self.validate(acceptance=acceptance)
        self.assertTrue(
            any("stale Draft closure-evidence location" in error for error in errors)
        )

    def test_explicit_historic_contract_quotation_is_allowed(self) -> None:
        work_package = (
            wp_p0_002_completed_work_package()
            + "\n"
            + HISTORIC_CONTRACT_BEGIN
            + "\n> Claude Design must specify the historic contract.\n"
            + HISTORIC_CONTRACT_END
            + "\n"
        )
        self.assertEqual([], self.validate(work_package=work_package))

    def test_unclosed_historic_contract_quotation_is_rejected(self) -> None:
        work_package = (
            wp_p0_002_completed_work_package()
            + "\n"
            + HISTORIC_CONTRACT_BEGIN
            + "\n> historic text\n"
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("unclosed historic-contract" in error for error in errors))

    def test_nested_historic_contract_quotation_is_rejected(self) -> None:
        work_package = (
            wp_p0_002_completed_work_package()
            + "\n"
            + HISTORIC_CONTRACT_BEGIN
            + "\n"
            + HISTORIC_CONTRACT_BEGIN
            + "\n> historic text\n"
            + HISTORIC_CONTRACT_END
            + "\n"
            + HISTORIC_CONTRACT_END
        )
        errors = self.validate(work_package=work_package)
        self.assertTrue(any("nested historic-contract" in error for error in errors))

    def test_wp_p0_003_activation_is_rejected(self) -> None:
        errors = self.validate(backlog=wp_p0_002_closure_backlog(wp3_status="READY_FOR_DESIGN"))
        self.assertTrue(any("WP-P0-003 must remain DRAFT" in error for error in errors))

    def test_production_write_control_cannot_change(self) -> None:
        current = wp_p0_002_closure_current_state().replace(
            "production_write_enabled: false", "production_write_enabled: true"
        )
        errors = self.validate(current=current)
        self.assertTrue(any("production_write_enabled: false" in error for error in errors))

    def test_d16_guidance_control_cannot_change(self) -> None:
        current = wp_p0_002_closure_current_state().replace(
            "owner_git_workflow_guidance: REQUIRED",
            "owner_git_workflow_guidance: DISABLED",
        )
        errors = self.validate(current=current)
        self.assertTrue(any("owner_git_workflow_guidance: REQUIRED" in error for error in errors))

    def test_d17_delegation_control_cannot_change(self) -> None:
        current = wp_p0_002_closure_current_state().replace(
            "owner_git_execution_delegation: ACTIVE",
            "owner_git_execution_delegation: INACTIVE",
        )
        errors = self.validate(current=current)
        self.assertTrue(any("owner_git_execution_delegation: ACTIVE" in error for error in errors))

    def test_duplicate_current_state_authority_is_rejected(self) -> None:
        current = wp_p0_002_closure_current_state().replace(
            "active_work_package: NONE",
            "active_work_package: NONE\nactive_work_package: NONE",
        )
        errors = self.validate(current=current)
        self.assertTrue(any("active_work_package: NONE" in error for error in errors))


def repository_governance_text(relative: str) -> str:
    return (Path(__file__).resolve().parents[1] / relative).read_text(encoding="utf-8")


def wp_p0_003_historical_current_state() -> str:
    """Frozen live-state fixture for the now-historical WP-P0-003 validators."""
    return """# Current State

```yaml
lifecycle_state: EXECUTING_PHASE_0
active_work_package: WP-P0-003
active_gate: CONTROLLER_WP_P0_003_DESIGN_FINALIZATION
authorization: DESIGN_ONLY
production_write_enabled: false
implementation_backed_design_validation: VERIFIED
bounded_validation_authorization: CLOSED
pr16_merge_execution: VERIFIED
full_design_approved: false
full_implementation_authorized: false
```

## Active objective

The Controller performs WP-P0-003 Design finalization.

## Next authorized action

CONTROLLER_WP_P0_003_DESIGN_FINALIZATION under DESIGN_ONLY. Full Design approval
and implementation authorization remain false; OQ-006 remains a later Gate.
"""


def wp_p0_003_historical_open_questions() -> str:
    return """# Open Questions

| ID | Question | Status |
| --- | --- | --- |
| OQ-006 | Object storage and Secret provider | OPEN |

## WP-P0-003 Planning dispositions — questions remain OPEN

OQ-005, OQ-006, OQ-101, OQ-102, OQ-106 and OQ-107 remain explicit.
No provider is selected. No Secret is selected.
Implementation authorization remains gated.
bounded INT-010/HR-01 Raw acceptance remains gated.
"""


def mutate_traceability_field(
    text: str, source_id: str, field: str, value: str
) -> str:
    rows = list(csv.DictReader(text.splitlines()))
    output = io.StringIO()
    writer = csv.DictWriter(
        output,
        fieldnames=list(rows[0]),
        lineterminator="\n",
    )
    writer.writeheader()
    for row in rows:
        if row["source_id"] == source_id:
            row[field] = value
        writer.writerow(row)
    return output.getvalue()


class WpP0003PostMergeClosureTests(unittest.TestCase):
    def validate(
        self,
        *,
        current: str | None = None,
        work_package: str | None = None,
        addendum: str | None = None,
        evidence: str | None = None,
        post_merge_evidence: str | None = None,
    ) -> list[str]:
        errors: list[str] = []
        validate_wp_p0_003_post_merge_closure_text(
            errors,
            current or wp_p0_003_historical_current_state(),
            work_package or repository_governance_text(WP_P0_003_RELATIVE_PATH),
            addendum
            or repository_governance_text(
                "docs/02-architecture/designs/"
                "WP-P0-003-executable-design-validation-addendum.md"
            ),
            evidence
            or repository_governance_text(
                "docs/07-phase-evidence/WP-P0-003/"
                "executable-design-validation.md"
            ),
            post_merge_evidence
            or repository_governance_text(
                "docs/07-phase-evidence/WP-P0-003/"
                "post-merge-execution-verification.md"
            ),
        )
        return errors

    def test_exact_post_merge_closure_is_valid(self) -> None:
        self.assertEqual([], self.validate())

    def test_full_design_approval_inflation_is_rejected(self) -> None:
        current = wp_p0_003_historical_current_state().replace(
            "full_design_approved: false", "full_design_approved: true", 1
        )
        self.assertTrue(
            any(
                "full_design_approved" in error
                for error in self.validate(current=current)
            )
        )

    def test_full_implementation_authorization_inflation_is_rejected(self) -> None:
        current = wp_p0_003_historical_current_state().replace(
            "full_implementation_authorized: false",
            "full_implementation_authorized: true",
            1,
        )
        self.assertTrue(
            any(
                "full_implementation_authorized" in error
                for error in self.validate(current=current)
            )
        )

    def test_production_write_enablement_is_rejected(self) -> None:
        current = wp_p0_003_historical_current_state().replace(
            "production_write_enabled: false",
            "production_write_enabled: true",
            1,
        )
        self.assertTrue(
            any(
                "production_write_enabled" in error
                for error in self.validate(current=current)
            )
        )

    def test_pr16_merge_cannot_complete_the_work_package(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| Status | DESIGN_FINALIZATION_REQUIRED |",
            "| Status | COMPLETED |",
            1,
        )
        self.assertTrue(
            any(
                "must not mark WP-P0-003 COMPLETED" in error
                for error in self.validate(work_package=work_package)
            )
        )

    def test_pr_state_reverted_to_open_draft_is_rejected(self) -> None:
        post_merge = repository_governance_text(
            "docs/07-phase-evidence/WP-P0-003/post-merge-execution-verification.md"
        ).replace("pr_state: MERGED_CLOSED_NOT_DRAFT", "pr_state: OPEN_DRAFT", 1)
        self.assertTrue(
            any(
                "pr_state" in error
                for error in self.validate(post_merge_evidence=post_merge)
            )
        )

    def test_merge_commit_and_tree_drift_are_rejected(self) -> None:
        original = repository_governance_text(
            "docs/07-phase-evidence/WP-P0-003/post-merge-execution-verification.md"
        )
        for field, value in (
            ("actual_squash_commit", "0" * 40),
            ("actual_main_tree", "1" * 40),
        ):
            with self.subTest(field=field):
                post_merge = original.replace(
                    next(
                        line
                        for line in original.splitlines()
                        if line.startswith(f"{field}:")
                    ),
                    f"{field}: {value}",
                    1,
                )
                self.assertTrue(
                    any(
                        field in error
                        for error in self.validate(post_merge_evidence=post_merge)
                    )
                )

    def test_bounded_validation_verified_cannot_be_removed(self) -> None:
        current = wp_p0_003_historical_current_state().replace(
            "implementation_backed_design_validation: VERIFIED",
            "implementation_backed_design_validation: PENDING",
            1,
        )
        self.assertTrue(
            any(
                "implementation_backed_design_validation" in error
                for error in self.validate(current=current)
            )
        )

    def test_pre_merge_gate_cannot_be_presented_as_current_authority(self) -> None:
        current = wp_p0_003_historical_current_state().replace(
            "active_gate: CONTROLLER_WP_P0_003_DESIGN_FINALIZATION",
            "active_gate: READY_FOR_DESIGN",
            1,
        )
        self.assertTrue(
            any("active_gate" in error for error in self.validate(current=current))
        )

    def test_stale_awaiting_controller_marker_is_rejected(self) -> None:
        evidence = repository_governance_text(
            "docs/07-phase-evidence/WP-P0-003/executable-design-validation.md"
        ) + "\nIMPLEMENTED_AWAITING_CONTROLLER\n"
        self.assertTrue(
            any(
                "awaiting-Controller status" in error
                for error in self.validate(evidence=evidence)
            )
        )


class WpP0003ActivationContractTests(unittest.TestCase):
    def activation_errors(
        self,
        *,
        current: str | None = None,
        work_package: str | None = None,
        backlog: str | None = None,
        open_questions: str | None = None,
        decision_request: str | None = None,
    ) -> list[str]:
        errors: list[str] = []
        validate_wp_p0_003_activation_text(
            errors,
            current or wp_p0_003_historical_current_state(),
            work_package or repository_governance_text(WP_P0_003_RELATIVE_PATH),
            backlog or repository_governance_text("docs/03-work-items/BACKLOG-PHASE-0.md"),
            open_questions or wp_p0_003_historical_open_questions(),
            decision_request or repository_governance_text(
                "docs/00-governance/DR-0002-split-controlled-file-import-from-wp-p0-003.md"
            ),
        )
        return errors

    def traceability_errors(self, text: str | None = None) -> list[str]:
        errors: list[str] = []
        validate_wp_p0_003_traceability_text(
            errors,
            text or repository_governance_text("docs/01-requirements/traceability.csv"),
        )
        return errors

    def work_package_errors(self, text: str) -> list[str]:
        errors: list[str] = []
        validate_wp_p0_003_work_package_text(errors, text)
        return errors

    def assert_prior_evidence_provenance_mutations_rejected(
        self,
        source_id: str,
        field: str,
        incorrectly_attributed_source_id: str,
    ) -> None:
        original = repository_governance_text("docs/01-requirements/traceability.csv")
        rows = {
            row["source_id"]: row
            for row in csv.DictReader(original.splitlines())
        }
        wp_p0_003_looking = {
            "code_location": (
                "backend/marketops-server/src/main/java/com/mimococo/marketops/"
                "marketplaceintegration/internal/application/IngestionWorker.java"
            ),
            "test_case": "TC-ING-003",
            "evidence": "docs/07-phase-evidence/WP-P0-003/acceptance-criteria.md",
        }
        mutations = {
            "arbitrary_non_empty": "arbitrary-non-empty-replacement",
            "wp_p0_003_looking": wp_p0_003_looking[field],
            "incorrect_existing_prior_subset": rows[
                incorrectly_attributed_source_id
            ][field],
        }
        self.assertTrue(rows[source_id][field])
        for mutation_name, replacement in mutations.items():
            with self.subTest(
                source_id=source_id,
                field=field,
                mutation=mutation_name,
            ):
                self.assertNotEqual(rows[source_id][field], replacement)
                traceability = mutate_traceability_field(
                    original,
                    source_id,
                    field,
                    replacement,
                )
                errors = self.traceability_errors(traceability)
                self.assertTrue(
                    any(
                        f"{source_id} prior evidence {field} must be exactly"
                        in error
                        for error in errors
                    )
                )

    def assert_dr_body_authority_declaration_rejected(
        self, declaration: str, field: str
    ) -> None:
        decision_request = repository_governance_text(
            "docs/00-governance/DR-0002-split-controlled-file-import-from-wp-p0-003.md"
        ) + f"\n{declaration}\n"
        errors = self.activation_errors(decision_request=decision_request)
        self.assertTrue(
            any(
                f"DR-0002 {field} must appear exactly once in leading YAML "
                "and nowhere else" in error
                for error in errors
            )
        )

    def test_exact_activation_and_traceability_contract_are_valid(self) -> None:
        self.assertEqual([], self.activation_errors())
        self.assertEqual([], self.traceability_errors())

    def test_r04_legitimate_prior_evidence_contract_is_valid(self) -> None:
        self.assertEqual([], self.traceability_errors())

    def test_r04_d03_code_location_rejects_provenance_drift(self) -> None:
        self.assert_prior_evidence_provenance_mutations_rejected(
            "D-03", "code_location", "ADM-002"
        )

    def test_r04_d03_test_case_rejects_provenance_drift(self) -> None:
        self.assert_prior_evidence_provenance_mutations_rejected(
            "D-03", "test_case", "ADM-002"
        )

    def test_r04_d03_evidence_rejects_provenance_drift(self) -> None:
        self.assert_prior_evidence_provenance_mutations_rejected(
            "D-03", "evidence", "ADM-002"
        )

    def test_r04_adm002_code_location_rejects_provenance_drift(self) -> None:
        self.assert_prior_evidence_provenance_mutations_rejected(
            "ADM-002", "code_location", "D-03"
        )

    def test_r04_adm002_test_case_rejects_provenance_drift(self) -> None:
        self.assert_prior_evidence_provenance_mutations_rejected(
            "ADM-002", "test_case", "D-03"
        )

    def test_r04_adm002_evidence_rejects_provenance_drift(self) -> None:
        self.assert_prior_evidence_provenance_mutations_rejected(
            "ADM-002", "evidence", "D-03"
        )

    def test_s01_dr_body_status_parallel_declaration_is_rejected(self) -> None:
        self.assert_dr_body_authority_declaration_rejected(
            "status: REJECTED", "status"
        )

    def test_s01_dr_body_owner_approval_parallel_declaration_is_rejected(self) -> None:
        self.assert_dr_body_authority_declaration_rejected(
            "owner_approval: NONE", "owner_approval"
        )

    def test_s01_dr_body_effective_condition_parallel_declaration_is_rejected(self) -> None:
        self.assert_dr_body_authority_declaration_rejected(
            "effective_condition: IMMEDIATE", "effective_condition"
        )

    def test_current_state_must_remain_design_only(self) -> None:
        current = wp_p0_003_historical_current_state().replace(
            "authorization: DESIGN_ONLY", "authorization: PLANNING_ONLY", 1
        )
        errors = self.activation_errors(current=current)
        self.assertTrue(any("authorization: DESIGN_ONLY" in error for error in errors))

    def test_production_write_control_cannot_change_in_active_state(self) -> None:
        current = wp_p0_003_historical_current_state().replace(
            "production_write_enabled: false", "production_write_enabled: true", 1
        )
        errors = self.activation_errors(current=current)
        self.assertTrue(any("production_write_enabled: false" in error for error in errors))

    def test_canonical_metadata_cannot_authorize_implementation(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| Authorization | DESIGN_ONLY |",
            "| Authorization | APPROVED_FOR_IMPLEMENTATION |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("Authorization must be exactly: DESIGN_ONLY" in error for error in errors))

    def test_wp_p0_003_is_the_only_ready_for_design_row(self) -> None:
        backlog = repository_governance_text(
            "docs/03-work-items/BACKLOG-PHASE-0.md"
        ).replace(
            "| WP-P0-003B | Controlled File Import & Source Intake Security | DRAFT |",
            "| WP-P0-003B | Controlled File Import & Source Intake Security | READY_FOR_DESIGN |",
        )
        errors = self.activation_errors(backlog=backlog)
        self.assertTrue(any("only READY_FOR_DESIGN" in error for error in errors))

    def test_duplicate_backlog_id_is_rejected(self) -> None:
        backlog = repository_governance_text(
            "docs/03-work-items/BACKLOG-PHASE-0.md"
        )
        row = next(
            line for line in backlog.splitlines() if line.startswith("| WP-P0-003 |")
        )
        backlog = backlog.replace(row, row + "\n" + row, 1)
        errors = self.activation_errors(backlog=backlog)
        self.assertTrue(any("duplicate Work Package IDs" in error for error in errors))

    def test_int019_cannot_be_claimed_full(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| INT-019 | OUT_OF_SCOPE |",
            "| INT-019 | FULL |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("cannot claim INT-019 FULL" in error for error in errors))

    def test_non_full_closure_must_name_later_owner(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| D-04 | PARTIAL | Immutable Raw evidence | Inventory and Financial Ledgers | WP-P0-007 |",
            "| D-04 | PARTIAL | Immutable Raw evidence | Inventory and Financial Ledgers | N/A |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("must name its later owner" in error for error in errors))

    def test_r01_business_failure_returned_bytes_cannot_be_failure_record_only(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| Business-meaningful failed call | YES | Immutable Raw exact bytes plus request metadata, hash, schema version, source time, ingestion time and provenance; never failure-record-only |",
            "| Business-meaningful failed call | YES | Attributable failure-record-only treatment is permitted |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("Raw outcome contract" in error for error in errors))

    def test_r02_rate_limit_credential_dimension_cannot_be_removed(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| Credential | Opaque Credential reference/identity; no Secret retrieval |",
            "| Credential | Account and Endpoint only |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("rate-limit identity contract" in error for error in errors))

    def test_r02_distinct_credential_identities_cannot_be_silently_merged(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| Partitioning | Distinct Credential scopes/identities under the same Account and Endpoint must not be silently merged unless future verified platform evidence explicitly permits it |",
            "| Partitioning | Merge every Credential under Account and Endpoint |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("rate-limit identity contract" in error for error in errors))

    def test_r03_adm004_trace_allocation_cannot_drop_wp_p0_003(self) -> None:
        traceability = repository_governance_text(
            "docs/01-requirements/traceability.csv"
        ).replace(
            "ADM-004,Requirement,0,Job Run / Error Queue / Replay / Dead-letter management,WP-P0-003;WP-P0-008,",
            "ADM-004,Requirement,0,Job Run / Error Queue / Replay / Dead-letter management,WP-P0-008,",
        )
        errors = self.traceability_errors(traceability)
        self.assertTrue(any("ADM-004 work_package" in error for error in errors))

    def test_r03_adm004_closure_cannot_claim_full(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| ADM-004 | PARTIAL / MULTI-WP |",
            "| ADM-004 | FULL |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("ADM-004 closure model" in error for error in errors))

    def test_public_webhook_exclusion_is_binding(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "No public webhook endpoint",
            "A public webhook endpoint",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("No public webhook endpoint" in error for error in errors))

    def test_oq006_must_remain_open(self) -> None:
        open_questions = repository_governance_text(
            "docs/00-governance/OPEN_QUESTIONS.md"
        ).replace("| Owner + Security | OPEN |", "| Owner + Security | RESOLVED |", 1)
        errors = self.activation_errors(open_questions=open_questions)
        self.assertTrue(any("OQ-006 must remain uniquely OPEN" in error for error in errors))

    def test_oq006_implementation_and_acceptance_gate_is_sensitive(self) -> None:
        open_questions = repository_governance_text(
            "docs/00-governance/OPEN_QUESTIONS.md"
        ).replace(
            "Blocked boundaries: Concrete Object Storage/Secret Final Design approval,\n"
            "  Implementation authorization",
            "Blocked boundaries: Concrete Object Storage/Secret Final Design approval,\n"
            "  future authorization",
            1,
        )
        errors = self.activation_errors(open_questions=open_questions)
        self.assertTrue(any("Implementation authorization" in error for error in errors))

    def test_required_traceability_seed_row_cannot_disappear(self) -> None:
        traceability = repository_governance_text(
            "docs/01-requirements/traceability.csv"
        )
        traceability = "\n".join(
            line for line in traceability.splitlines() if not line.startswith("INT-004,")
        ) + "\n"
        errors = self.traceability_errors(traceability)
        self.assertTrue(any("row is missing: INT-004" in error for error in errors))

    def test_int019_traceability_must_bind_draft_wp_p0_003b(self) -> None:
        traceability = repository_governance_text(
            "docs/01-requirements/traceability.csv"
        ).replace(
            "INT-019,Requirement,0,Controlled CSV / Excel / report import,WP-P0-003B,",
            "INT-019,Requirement,0,Controlled CSV / Excel / report import,WP-P0-003,",
        )
        errors = self.traceability_errors(traceability)
        self.assertTrue(any("INT-019 work_package" in error for error in errors))

    def test_r04_every_preimplementation_field_rejects_premature_evidence(self) -> None:
        original = repository_governance_text("docs/01-requirements/traceability.csv")
        for source_id in sorted(WP_P0_003_PREIMPLEMENTATION_EMPTY_TRACEABILITY_IDS):
            for field in ("code_location", "test_case", "evidence"):
                with self.subTest(source_id=source_id, field=field):
                    traceability = mutate_traceability_field(
                        original,
                        source_id,
                        field,
                        "premature-proof",
                    )
                    errors = self.traceability_errors(traceability)
                    self.assertTrue(
                        any(
                            f"{source_id} {field} must remain empty" in error
                            for error in errors
                        )
                    )

    def test_prior_evidence_notes_distinguish_closed_and_unimplemented_subsets(self) -> None:
        original = repository_governance_text("docs/01-requirements/traceability.csv")
        for source_id, required_note in (
            ("D-03", "WP-P0-001 verified"),
            ("ADM-002", "WP-P0-002 subset VERIFIED"),
        ):
            with self.subTest(source_id=source_id):
                row = next(
                    row
                    for row in csv.DictReader(original.splitlines())
                    if row["source_id"] == source_id
                )
                traceability = mutate_traceability_field(
                    original,
                    source_id,
                    "notes",
                    row["notes"].replace(required_note, "prior subset", 1),
                )
                errors = self.traceability_errors(traceability)
                self.assertTrue(
                    any(
                        f"{source_id} notes missing" in error
                        for error in errors
                    )
                )

    def test_duplicate_traceability_source_id_is_rejected(self) -> None:
        traceability = repository_governance_text(
            "docs/01-requirements/traceability.csv"
        )
        row = next(
            line for line in traceability.splitlines() if line.startswith("ADM-004,")
        )
        traceability = traceability + row + "\n"
        errors = self.traceability_errors(traceability)
        self.assertTrue(any("duplicate source_id" in error for error in errors))

    def test_wp_p0_003b_canonical_record_is_rejected(self) -> None:
        errors: list[str] = []
        validate_wp_p0_003_record_paths(
            errors,
            {"docs/03-work-items/WP-P0-003B-controlled-file-import.md"},
        )
        self.assertTrue(any("without a canonical Work Package file" in error for error in errors))

    def test_dr_leading_authority_fields_reject_duplicates_and_conflicts(self) -> None:
        original = repository_governance_text(
            "docs/00-governance/DR-0002-split-controlled-file-import-from-wp-p0-003.md"
        )
        for field, contradiction in (
            ("status", "REJECTED"),
            ("owner_approval", "NONE"),
            ("effective_condition", "IMMEDIATE"),
        ):
            with self.subTest(field=field):
                current = next(
                    line for line in original.splitlines() if line.startswith(field + ":")
                )
                decision_request = original.replace(
                    current,
                    current + f"\n{field}: {contradiction}",
                    1,
                )
                errors = self.activation_errors(decision_request=decision_request)
                self.assertTrue(any("duplicate fields" in error for error in errors))

    def test_second_runtime_authority_declarations_are_rejected(self) -> None:
        original = repository_governance_text(WP_P0_003_RELATIVE_PATH)
        for capability in (
            "Job scheduler/worker",
            "Cursor/checkpoint writer",
            "Replay/dead-letter recovery command executor",
            "Raw object-store intake coordinator",
        ):
            with self.subTest(capability=capability):
                row = next(
                    line
                    for line in original.splitlines()
                    if line.startswith(f"| {capability} |")
                )
                second = (
                    f"| {capability} | adminobservability | marketplaceintegration | SINGLE |"
                )
                work_package = original.replace(row, row + "\n" + second, 1)
                errors = self.work_package_errors(work_package)
                self.assertTrue(any("duplicate Capability" in error for error in errors))

    def test_oq005_public_surface_contradiction_is_rejected(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| OQ-005 | OPEN | Internal provider-neutral worker and operator contract Design | Any authenticated/public operator, webhook, manual-trigger or file-upload runtime surface | Future runtime IAM Work Package selected by the Controller |",
            "| OQ-005 | OPEN | Public operator runtime surface | None | Future runtime IAM Work Package selected by the Controller |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("Owner Gate allocation" in error for error in errors))

    def test_oq006_concrete_provider_contradiction_is_rejected(self) -> None:
        work_package = repository_governance_text(WP_P0_003_RELATIVE_PATH).replace(
            "| OQ-006 | OPEN | Provider-neutral object and opaque Credential-reference contract Design | Concrete Object Storage/Secret Final Design approval, Implementation authorization, bounded Raw acceptance, Secret retrieval and real quota assumptions | Human Owner + Security, then WP-P0-005/WP-P0-006 platform evidence |",
            "| OQ-006 | OPEN | Concrete provider is approved | None | Human Owner + Security, then WP-P0-005/WP-P0-006 platform evidence |",
        )
        errors = self.work_package_errors(work_package)
        self.assertTrue(any("Owner Gate allocation" in error for error in errors))

    def test_decision_request_cannot_claim_runtime_change(self) -> None:
        decision_request = repository_governance_text(
            "docs/00-governance/DR-0002-split-controlled-file-import-from-wp-p0-003.md"
        ).replace(
            "No Design, migration or implementation",
            "A Design, migration or implementation",
        )
        errors = self.activation_errors(decision_request=decision_request)
        self.assertTrue(any("No Design, migration or implementation" in error for error in errors))


class V1RequiredFileTests(unittest.TestCase):
    def test_complete_required_inventory_is_valid(self) -> None:
        errors: list[str] = []
        validate_required_file_set(
            errors,
            set(REQUIRED_FILES + DR0003_REQUIRED_FILES + DR0004_REQUIRED_FILES),
        )
        self.assertEqual([], errors)

    def test_missing_dr_owner_slice_capability_and_assurance_files_are_rejected(self) -> None:
        required = set(REQUIRED_FILES + DR0003_REQUIRED_FILES + DR0004_REQUIRED_FILES)
        for relative in (
            "docs/00-governance/DR-0003-v1-product-delivery-baseline-reset.md",
            "docs/00-governance/OWNER_DECISIONS_V1.md",
            "docs/02-architecture/adr/ADR-0008-unified-capability-model-and-selective-controlled-write.md",
            "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md",
            "docs/04-api/V1_CAPABILITY_MATRIX.md",
            "docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md",
            "docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md",
            "docs/08-handoffs/CONTROLLER-PR18-DR-0003-INDEPENDENT-RE-REVIEW-R2.md",
        ):
            with self.subTest(relative=relative):
                errors: list[str] = []
                validate_required_file_set(errors, required - {relative})
                self.assertIn(f"missing required file: {relative}", errors)

    def test_historical_evidence_tree_pins_match_and_detect_mutation(self) -> None:
        root = Path(__file__).resolve().parents[1]
        for relative, expected in HISTORICAL_EVIDENCE_TREE_HASHES.items():
            with self.subTest(relative=relative):
                self.assertEqual(expected, directory_tree_sha256(root / relative))
        with TemporaryDirectory() as directory:
            path = Path(directory)
            evidence = path / "evidence.md"
            evidence.write_text("accepted\n", encoding="utf-8")
            before = directory_tree_sha256(path)
            evidence.write_text("mutated\n", encoding="utf-8")
            self.assertNotEqual(before, directory_tree_sha256(path))


class Dr0003R1WhitespaceAttributeTests(unittest.TestCase):
    def root(self) -> Path:
        return Path(__file__).resolve().parents[1]

    def attributes(self) -> str:
        return (self.root() / ".gitattributes").read_text(encoding="utf-8")

    def validate(
        self,
        attributes: str,
        *,
        root: Path | None = None,
        required_files: set[str] | None = None,
    ) -> list[str]:
        errors: list[str] = []
        validate_dr0003_r1_whitespace_exception(
            errors,
            attributes,
            root=root or self.root(),
            required_files=(
                set(DR0003_REQUIRED_FILES)
                if required_files is None
                else required_files
            ),
        )
        return errors

    def test_exact_actual_whitespace_exception_path_is_accepted(self) -> None:
        expected = f"{DR0003_R1_REVIEW_RELATIVE_PATH} -whitespace"
        self.assertIn(expected, self.attributes().splitlines())
        self.assertEqual([], self.validate(self.attributes()))

    def test_slash_directory_typo_is_rejected(self) -> None:
        wrong = (
            "docs/08/handoffs/"
            "CONTROLLER-PR18-DR-0003-INDEPENDENT-REVIEW-R1.md"
        )
        mutated = self.attributes().replace(DR0003_R1_REVIEW_RELATIVE_PATH, wrong)
        errors = self.validate(mutated)
        self.assertTrue(any("exact and singular" in error for error in errors))

    def test_missing_whitespace_exception_target_is_rejected(self) -> None:
        with TemporaryDirectory() as directory:
            errors = self.validate(self.attributes(), root=Path(directory))
        self.assertTrue(any("target does not exist" in error for error in errors))

    def test_whitespace_exception_target_must_remain_required(self) -> None:
        required = set(DR0003_REQUIRED_FILES) - {DR0003_R1_REVIEW_RELATIVE_PATH}
        errors = self.validate(self.attributes(), required_files=required)
        self.assertTrue(any("target must be a required file" in error for error in errors))

    def test_second_or_directory_wide_whitespace_exception_is_rejected(self) -> None:
        additions = (
            f"{DR0003_R1_REVIEW_RELATIVE_PATH} -whitespace",
            "docs/08-handoffs/** -whitespace",
        )
        for addition in additions:
            with self.subTest(addition=addition):
                errors = self.validate(self.attributes() + addition + "\n")
                self.assertTrue(any("exact and singular" in error for error in errors))

    def test_all_three_r1_artifact_hashes_remain_exact(self) -> None:
        self.assertEqual(3, len(DR0003_R1_ARTIFACT_HASHES))
        for relative, expected in DR0003_R1_ARTIFACT_HASHES.items():
            with self.subTest(relative=relative):
                actual = hashlib.sha256((self.root() / relative).read_bytes()).hexdigest()
                self.assertEqual(expected, actual)
        errors: list[str] = []
        validate_dr0003_r1_artifacts(errors)
        self.assertEqual([], errors)

    def test_r2_artifacts_are_exact_and_non_authorizing(self) -> None:
        self.assertEqual(3, len(DR0003_R2_ARTIFACT_HASHES))
        for relative, expected in DR0003_R2_ARTIFACT_HASHES.items():
            with self.subTest(relative=relative):
                actual = hashlib.sha256((self.root() / relative).read_bytes()).hexdigest()
                self.assertEqual(expected, actual)
        errors: list[str] = []
        validate_dr0003_r2_artifacts(errors)
        self.assertEqual([], errors)


class V1CurrentStateContractTests(unittest.TestCase):
    def current(self) -> str:
        return repository_governance_text("docs/00-governance/CURRENT_STATE.md")

    def charter(self) -> str:
        return repository_governance_text("docs/00-governance/PROJECT_CHARTER.md")

    def slice_contract_bytes(self) -> bytes:
        return (
            Path(__file__).resolve().parents[1]
            / "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md"
        ).read_bytes()

    def validate(
        self,
        current: str | None = None,
        charter: str | None = None,
        slice_contract_bytes: bytes | None = None,
    ) -> list[str]:
        errors: list[str] = []
        validate_v1_current_state_text(
            errors,
            current or self.current(),
            charter or self.charter(),
            slice_contract_bytes,
        )
        return errors

    def test_exact_v1_state_is_valid(self) -> None:
        self.assertEqual([], self.validate())

    def test_old_phase_wp_design_state_is_rejected(self) -> None:
        current = self.current().replace(
            "lifecycle_state: EXECUTING_V1",
            "lifecycle_state: EXECUTING_PHASE_0\nactive_work_package: WP-P0-003",
            1,
        )
        errors = self.validate(current=current)
        self.assertTrue(any("lifecycle_state" in error for error in errors))
        self.assertTrue(any("Work Package" in error for error in errors))

    def test_duplicate_lifecycle_is_rejected(self) -> None:
        current = self.current().replace(
            "lifecycle_state: EXECUTING_V1",
            "lifecycle_state: EXECUTING_V1\nlifecycle_state: EXECUTING_V1",
            1,
        )
        self.assertTrue(any("lifecycle_state" in error for error in self.validate(current=current)))

    def test_duplicate_active_slice_is_rejected(self) -> None:
        current = self.current().replace(
            "active_delivery_slice: SLICE-V1-001",
            "active_delivery_slice: SLICE-V1-001\nactive_delivery_slice: SLICE-V1-001",
            1,
        )
        self.assertTrue(any("active_delivery_slice" in error for error in self.validate(current=current)))

    def test_wrong_contract_gate_and_authorization_are_rejected(self) -> None:
        mutations = (
            (
                "active_slice_contract: docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md",
                "active_slice_contract: docs/03-work-items/other.md",
                "active_slice_contract",
            ),
            ("active_gate: SLICE_CONTRACT_APPROVED", "active_gate: READY_FOR_DESIGN", "active_gate"),
            ("authorization: FULL_SCOPE_IMPLEMENTATION", "authorization: DESIGN_ONLY", "authorization"),
        )
        for old, new, field in mutations:
            with self.subTest(field=field):
                errors = self.validate(current=self.current().replace(old, new, 1))
                self.assertTrue(any(field in error for error in errors))

    def test_enabled_global_or_platform_write_is_rejected(self) -> None:
        mutations = (
            ("production_write_enabled: false", "production_write_enabled: true"),
            (
                "ozon_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE",
                "ozon_price_write: ENABLED",
            ),
            (
                "wildberries_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE",
                "wildberries_price_write: ENABLED",
            ),
        )
        for old, new in mutations:
            with self.subTest(field=old.split(":", 1)[0]):
                self.assertTrue(self.validate(current=self.current().replace(old, new, 1)))

    def test_contract_byte_change_with_old_hash_is_rejected(self) -> None:
        mutated = self.slice_contract_bytes().replace(
            b"SKU Growth & Profit Diagnostic Loop",
            b"SKU Growth & Profit Diagnostic Lo0p",
            1,
        )
        errors = self.validate(slice_contract_bytes=mutated)
        self.assertTrue(any("immutable" in error for error in errors))
        self.assertTrue(any("does not match" in error for error in errors))

    def test_hash_change_with_original_contract_is_rejected(self) -> None:
        current = self.current().replace(
            V1_ACTIVE_SLICE_CONTRACT_SHA256,
            "0" * 64,
            1,
        )
        errors = self.validate(current=current)
        self.assertTrue(any("active_slice_contract_sha256" in error for error in errors))
        self.assertTrue(any("FULL_SCOPE_IMPLEMENTATION" in error for error in errors))

    def test_coordinated_contract_and_hash_change_is_not_an_amendment(self) -> None:
        mutated = self.slice_contract_bytes() + b"\nR1 mutation requiring re-review\n"
        mutated_hash = hashlib.sha256(mutated).hexdigest()
        current = self.current().replace(
            V1_ACTIVE_SLICE_CONTRACT_SHA256,
            mutated_hash,
            1,
        )
        errors = self.validate(
            current=current,
            slice_contract_bytes=mutated,
        )
        self.assertTrue(any("additive Amendment" in error for error in errors))

    def test_full_scope_requires_exact_review_authorization_condition(self) -> None:
        current = self.current().replace(
            "EXACT_HASH_INDEPENDENTLY_REVIEWED_AND_OWNER_AUTHORIZED_ON_PROTECTED_MAIN",
            "HASH_UPDATED_WITHOUT_REVIEW",
            1,
        )
        errors = self.validate(current=current)
        self.assertTrue(any("authorization_condition" in error for error in errors))
        self.assertTrue(any("FULL_SCOPE_IMPLEMENTATION" in error for error in errors))

    def test_rework_identity_phase_and_actor_cannot_drift(self) -> None:
        mutations = (
            ("next_authorized_actor: GPT-5.6 Pro Controller", "next_authorized_actor: CLAUDE_FABLE_5"),
            ("slice_v1_001_rework_phase: R2_FIX_VERIFY", "slice_v1_001_rework_phase: CLOSED"),
            (
                "slice_v1_001_pr_state: OPEN_DRAFT_R2_CANDIDATE",
                "slice_v1_001_pr_state: MERGED",
            ),
            (
                "slice_v1_001_initial_remote_ci: PASS_12_OF_12_REQUIRED_CONTEXTS",
                "slice_v1_001_initial_remote_ci: PASS_11_OF_12_REQUIRED_CONTEXTS",
            ),
            ("slice_v1_001_finding_count: 9", "slice_v1_001_finding_count: 8"),
            ("slice_v1_001_closure_claim: NONE", "slice_v1_001_closure_claim: APPROVED"),
            ("candidate_state_scope: PR_BRANCH_ONLY", "candidate_state_scope: PROTECTED_MAIN"),
            ("gate_ev: NOT_AUTHORIZED", "gate_ev: AUTHORIZED"),
        )
        for old, new in mutations:
            with self.subTest(field=old):
                self.assertIn(old, self.current())
                self.assertTrue(self.validate(current=self.current().replace(old, new, 1)))

    def test_codeql_authority_and_persisted_dispositions_cannot_drift(self) -> None:
        root = Path(__file__).resolve().parents[1] / "docs/07-phase-evidence/SLICE-V1-001/rework-r1/codeql-v1.1"
        artifacts = {path.name: path.read_bytes() for path in root.glob("*") if path.is_file()}
        errors: list[str] = []
        validate_codeql_disposition_artifacts(errors, artifacts)
        self.assertEqual([], errors)
        for name in artifacts:
            with self.subTest(artifact=name):
                changed = dict(artifacts)
                changed[name] += b"\n"
                errors = []
                validate_codeql_disposition_artifacts(errors, changed)
                self.assertTrue(errors)
        for changed in ({}, {**artifacts, "unapproved-alert.json": b"{}"}):
            errors = []
            validate_codeql_disposition_artifacts(errors, changed)
            self.assertTrue(errors)

    def test_review_artifacts_and_acceptance_matrix_are_mutation_sensitive(self) -> None:
        root = Path(__file__).resolve().parents[1] / "docs/07-phase-evidence/SLICE-V1-001"
        artifacts = {name: (root / "rework-r1/frozen" / name).read_bytes() for name in SLICE_REWORK_ARTIFACT_HASHES}
        acceptance = (root / "acceptance-status.md").read_text()
        errors: list[str] = []
        validate_slice_rework_evidence_text(errors, acceptance, artifacts)
        self.assertEqual([], errors)
        for name in artifacts:
            changed = dict(artifacts)
            changed[name] += b"\n"
            errors = []
            validate_slice_rework_evidence_text(errors, acceptance, changed)
            self.assertTrue(any("hash mismatch" in error for error in errors))
        mutations = (
            acceptance.replace("`S1-AC-001`", "`S1-AC-002`", 1),
            acceptance.replace(
                "`OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001` |",
                "`EXECUTABLY_VERIFIED` |",
                1,
            ),
            acceptance.replace("`IMPLEMENTED_UNPROVEN` | 24", "`IMPLEMENTED_UNPROVEN` | 23", 1),
            acceptance.replace(
                "assessed_against: SUPPLEMENTAL_R2_DRAFT_PR_22_CONTAINING_COMMIT",
                "assessed_against: CLOSED",
                1,
            ),
        )
        for changed in mutations:
            self.assertNotEqual(acceptance, changed)
            errors = []
            validate_slice_rework_evidence_text(errors, changed, artifacts)
            self.assertTrue(errors)


class V1DecisionContractTests(unittest.TestCase):
    def test_exact_decision_log_and_owner_decisions_are_valid(self) -> None:
        errors: list[str] = []
        validate_decision_log_v1_text(
            errors, repository_governance_text("docs/00-governance/DECISION_LOG.md")
        )
        validate_owner_decisions_v1_text(
            errors,
            repository_governance_text("docs/00-governance/OWNER_DECISIONS_V1.md"),
        )
        self.assertEqual([], errors)

    def test_superseded_decision_cannot_be_reactivated(self) -> None:
        text = repository_governance_text("docs/00-governance/DECISION_LOG.md").replace(
            "| D-01 | 2026-08-06 | SUPERSEDED |",
            "| D-01 | 2026-08-06 | ACCEPTED |",
            1,
        )
        errors: list[str] = []
        validate_decision_log_v1_text(errors, text)
        self.assertTrue(any("D-01" in error and "SUPERSEDED" in error for error in errors))

    def test_retained_or_new_decision_cannot_be_removed(self) -> None:
        original = repository_governance_text("docs/00-governance/DECISION_LOG.md")
        for decision_id in ("D-03", "D-17", "D-24", "D-25"):
            with self.subTest(decision_id=decision_id):
                text = "\n".join(
                    line for line in original.splitlines()
                    if not line.startswith(f"| {decision_id} |")
                )
                errors: list[str] = []
                validate_decision_log_v1_text(errors, text)
                self.assertTrue(any(decision_id in error for error in errors))

    def test_owner_decision_missing_or_duplicate_is_rejected(self) -> None:
        original = repository_governance_text(
            "docs/00-governance/OWNER_DECISIONS_V1.md"
        )
        for mutated in (
            original.replace("OD-V1-024", "OD-V1-999", 1),
            original.replace("CD-V1-011", "CD-V1-010", 1),
        ):
            errors: list[str] = []
            validate_owner_decisions_v1_text(errors, mutated)
            self.assertTrue(errors)


class V1SliceContractTests(unittest.TestCase):
    def test_exact_backlog_delivery_plan_and_slice_are_valid(self) -> None:
        errors: list[str] = []
        validate_backlog_v1_text(
            errors, repository_governance_text("docs/03-work-items/BACKLOG-PHASE-0.md")
        )
        validate_delivery_slices_v1_text(
            errors, repository_governance_text("docs/03-work-items/V1_DELIVERY_SLICES.md")
        )
        validate_slice_v1_001_text(
            errors,
            repository_governance_text(
                "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md"
            ),
        )
        self.assertEqual([], errors)

    def test_backlog_without_exact_historical_banner_is_rejected(self) -> None:
        original = repository_governance_text("docs/03-work-items/BACKLOG-PHASE-0.md")
        mutated = original.replace("HISTORICAL PROVENANCE ONLY", "ACTIVE PLAN", 1)
        errors: list[str] = []
        validate_backlog_v1_text(errors, mutated)
        self.assertTrue(errors)

    def test_missing_slice_section_is_rejected(self) -> None:
        original = repository_governance_text(
            "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md"
        )
        mutated = original.replace("## 12. Stop conditions during implementation", "## Stop conditions", 1)
        errors: list[str] = []
        validate_slice_v1_001_text(errors, mutated)
        self.assertTrue(any("Stop conditions" in error for error in errors))

    def test_missing_invariant_or_acceptance_id_is_rejected(self) -> None:
        original = repository_governance_text(
            "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md"
        )
        for mutated, expected in (
            (original.replace("20. One Slice", "One Slice", 1), "invariants"),
            (original.replace("S1-AC-041", "S1-AC-MISSING", 1), "S1-AC-041"),
        ):
            with self.subTest(expected=expected):
                errors: list[str] = []
                validate_slice_v1_001_text(errors, mutated)
                self.assertTrue(any(expected in error for error in errors))

    def test_unknown_readback_restore_and_kill_switch_chain_is_required(self) -> None:
        original = repository_governance_text(
            "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md"
        )
        for token in (
            "Timeout/unknown platform result is never blindly resubmitted",
            "Platform success is not final success until required Readback converges.",
            "Restore/compensate cannot overwrite a later legitimate external change.",
            "global and scoped Kill Switches prevent new writes",
        ):
            with self.subTest(token=token):
                errors: list[str] = []
                validate_slice_v1_001_text(errors, original.replace(token, "removed", 1))
                self.assertTrue(errors)


class V1CapabilityAiAndWriteContractTests(unittest.TestCase):
    def test_exact_capability_ai_product_and_design_gate_contracts_are_valid(self) -> None:
        errors: list[str] = []
        validate_capability_matrix_v1_text(
            errors, repository_governance_text("docs/04-api/V1_CAPABILITY_MATRIX.md")
        )
        validate_ai_execution_boundary_text(
            errors,
            repository_governance_text(
                "docs/02-architecture/V1_AI_DATA_AND_EXECUTION_BOUNDARY.md"
            ),
        )
        validate_v1_product_contract_text(
            errors, repository_governance_text("docs/01-requirements/V1_PRODUCT_CONTRACT.md")
        )
        validate_ai_operating_model_v1_text(
            errors, repository_governance_text("docs/00-governance/AI_OPERATING_MODEL.md")
        )
        self.assertEqual([], errors)

    def test_write_capability_cannot_start_verified(self) -> None:
        original = repository_governance_text("docs/04-api/V1_CAPABILITY_MATRIX.md")
        row = next(
            line
            for line in original.splitlines()
            if line.startswith("| Ozon | `PRICE_CHANGE` |")
        )
        mutated = original.replace(
            row, row.replace("| UNVERIFIED |", "| PASS |", 1), 1
        )
        errors: list[str] = []
        validate_capability_matrix_v1_text(errors, mutated)
        self.assertTrue(any("UNVERIFIED" in error for error in errors))

    def test_guessed_endpoint_or_quota_is_rejected(self) -> None:
        original = repository_governance_text("docs/04-api/V1_CAPABILITY_MATRIX.md")
        for addition in (
            "\nendpoint: https://api-seller.example/v1/prices\n",
            "\nquota: 100 requests per minute\n",
        ):
            with self.subTest(addition=addition.strip()):
                errors: list[str] = []
                validate_capability_matrix_v1_text(errors, original + addition)
                self.assertTrue(any("guessed" in error for error in errors))

    def test_ai_cannot_gain_fact_policy_approval_command_or_credential_authority(self) -> None:
        original = repository_governance_text(
            "docs/02-architecture/V1_AI_DATA_AND_EXECUTION_BOUNDARY.md"
        )
        mutated = original.replace(
            "AI never owns the approval decision, idempotency key, Outbox writer, Marketplace",
            "AI owns the approval decision, Outbox writer and Marketplace",
            1,
        )
        errors: list[str] = []
        validate_ai_execution_boundary_text(errors, mutated)
        self.assertTrue(errors)

    def test_controlled_write_chain_cannot_lose_readback_or_restore(self) -> None:
        original = repository_governance_text("docs/01-requirements/V1_PRODUCT_CONTRACT.md")
        for token in (
            "→ Provider State + Readback",
            "→ Audit + Restore/Compensate + Outcome Follow-up",
        ):
            with self.subTest(token=token):
                errors: list[str] = []
                validate_v1_product_contract_text(errors, original.replace(token, "removed", 1))
                self.assertTrue(errors)

    def test_conditional_gate_cannot_cover_ordinary_engineering(self) -> None:
        original = repository_governance_text("docs/00-governance/AI_OPERATING_MODEL.md")
        mutated = original.replace("not trigger the Gate.", "trigger the Gate.", 1)
        errors: list[str] = []
        validate_ai_operating_model_v1_text(errors, mutated)
        self.assertTrue(errors)


class V1R1GovernanceContractTests(unittest.TestCase):
    def documents(self) -> dict[str, str]:
        paths = {
            "current": "docs/00-governance/CURRENT_STATE.md",
            "charter": "docs/00-governance/PROJECT_CHARTER.md",
            "dr": "docs/00-governance/DR-0003-v1-product-delivery-baseline-reset.md",
            "owner": "docs/00-governance/OWNER_DECISIONS_V1.md",
            "product": "docs/01-requirements/V1_PRODUCT_CONTRACT.md",
            "slices": "docs/03-work-items/V1_DELIVERY_SLICES.md",
            "slice": "docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md",
            "review_standard": "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md",
            "quality": "docs/00-governance/QUALITY_GATES.md",
            "assurance": "docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md",
            "operating": "docs/00-governance/AI_OPERATING_MODEL.md",
            "capability": "docs/04-api/V1_CAPABILITY_MATRIX.md",
            "handoff": "docs/00-governance/HANDOFF_PROTOCOL.md",
            "guide": "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md",
            "questions": "docs/00-governance/OPEN_QUESTIONS.md",
            "traceability": "docs/01-requirements/v1-traceability.csv",
            "pr_template": ".github/pull_request_template.md",
            "agents": "AGENTS.md",
            "claude": "CLAUDE.md",
            "claude_project": "docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md",
            "chatgpt_project": "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md",
        }
        return {
            name: repository_governance_text(relative)
            for name, relative in paths.items()
        }

    def test_exact_r1_finding_authority_and_gate_ev_contracts_are_valid(self) -> None:
        documents = self.documents()
        errors: list[str] = []
        validate_finding_vocabulary_texts(errors, documents)
        validate_v1_authority_effect_texts(errors, documents)
        validate_gate_ev_contract_texts(errors, documents)
        self.assertEqual([], errors)

    def test_second_finding_taxonomy_is_rejected_but_delivery_risk_is_allowed(self) -> None:
        documents = self.documents()
        self.assertIn("delivery_risk: CRITICAL", documents["slice"])
        errors: list[str] = []
        validate_finding_vocabulary_texts(errors, documents)
        self.assertEqual([], errors)
        documents["product"] += "\nNo unresolved Critical/High finding remains.\n"
        validate_finding_vocabulary_texts(errors, documents)
        self.assertTrue(any("forbidden" in error for error in errors))

    def test_stale_pending_merge_metadata_is_rejected_in_executing_v1(self) -> None:
        mutations = {
            "dr": (
                "status: ACCEPTED_EFFECTIVE_ON_PROTECTED_MAIN",
                "status: CONTROLLER_APPROVED_PENDING_REPOSITORY_EFFECT",
            ),
            "owner": (
                "repository_effect: EFFECTIVE_WHEN_PRESENT_ON_PROTECTED_MAIN",
                "repository_effect: PENDING_DR_0003_MERGE",
            ),
            "product": (
                "status: APPROVED_EFFECTIVE_ON_PROTECTED_MAIN",
                "status: APPROVED_BY_DR_0003_PENDING_REPOSITORY_EFFECT",
            ),
            "slices": (
                "CONTRACT_APPROVED_EFFECTIVE_ON_PROTECTED_MAIN",
                "CONTRACT_APPROVED / PENDING_RESET_MERGE",
            ),
        }
        for name, (old, new) in mutations.items():
            with self.subTest(document=name):
                documents = self.documents()
                self.assertIn("lifecycle_state: EXECUTING_V1", documents["current"])
                documents[name] = documents[name].replace(old, new, 1)
                errors: list[str] = []
                validate_v1_authority_effect_texts(errors, documents)
                self.assertTrue(any("pending-merge" in error for error in errors))

    def test_gate_ev_cannot_be_collapsed_into_gate_e_or_enablement(self) -> None:
        documents = self.documents()
        documents["quality"] = documents["quality"].replace(
            "## Gate EV — Bounded Real-Write Verification Authorization",
            "## Gate E — Controlled Capability Enablement",
            1,
        )
        errors: list[str] = []
        validate_gate_ev_contract_texts(errors, documents)
        self.assertTrue(any("Gate EV quality" in error for error in errors))

        documents = self.documents()
        documents["current"] = documents["current"].replace(
            "bounded_real_write_verification_authorization: NONE",
            "bounded_real_write_verification_authorization: AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION",
            1,
        )
        errors = []
        validate_gate_ev_contract_texts(errors, documents)
        self.assertTrue(any("Gate EV current" in error for error in errors))

    def test_gate_ev_requires_the_complete_bounded_authorization_envelope(self) -> None:
        required = (
            "explicit Human Owner authorization",
            "Platform, opaque Account/Store reference, Capability and SKU allowlist",
            "one-time or time-bounded verification window",
            "maximum price delta and cumulative exposure",
            "current official-source and real-account Capability evidence",
            "current deterministic Guardrails and a passing Dry Run",
            "supervised operator, abort owner and manual-stop procedure",
            "global and scoped Kill Switches",
            "captured pre-state",
            "Readback and Restore/Compensate procedure",
            "unknown-result and manual-resolution behavior",
            "complete Audit and durable redacted evidence-retention plan",
        )
        for token in required:
            with self.subTest(token=token):
                documents = self.documents()
                documents["quality"] = documents["quality"].replace(
                    token, "removed Gate-EV envelope field", 1
                )
                errors: list[str] = []
                validate_gate_ev_contract_texts(errors, documents)
                self.assertTrue(any("Gate EV quality" in error for error in errors))

    def test_implementation_merge_and_gate_ev_cannot_enable_controlled_pilot(self) -> None:
        mutations = {
            "operating": (
                "`FULL_SCOPE_IMPLEMENTATION`, merge and Gate EV do not authorize ongoing",
                "`FULL_SCOPE_IMPLEMENTATION`, merge and Gate EV authorize ongoing",
            ),
            "product": (
                "not substitute for Gate EV",
                "substitute for Gate EV",
            ),
            "assurance": (
                "completion or Gate EV does not automatically enable the Capability",
                "completion or Gate EV automatically enables the Capability",
            ),
        }
        for name, (old, new) in mutations.items():
            with self.subTest(document=name):
                documents = self.documents()
                documents[name] = documents[name].replace(old, new, 1)
                errors: list[str] = []
                validate_gate_ev_contract_texts(errors, documents)
                self.assertTrue(any(f"Gate EV {name}" in error for error in errors))

    def test_r1_controller_artifacts_are_exact_and_non_authorizing(self) -> None:
        errors: list[str] = []
        validate_dr0003_r1_artifacts(errors)
        self.assertEqual([], errors)


class V1TraceabilityAndOpenQuestionTests(unittest.TestCase):
    def traceability(self) -> str:
        return repository_governance_text("docs/01-requirements/v1-traceability.csv")

    def test_exact_v1_traceability_and_open_questions_are_valid(self) -> None:
        errors: list[str] = []
        validate_v1_traceability_text(errors, self.traceability())
        validate_open_questions_v1_text(
            errors, repository_governance_text("docs/00-governance/OPEN_QUESTIONS.md")
        )
        self.assertEqual([], errors)

    def test_missing_decision_or_duplicate_source_id_is_rejected(self) -> None:
        original = self.traceability()
        missing = "\n".join(
            line for line in original.splitlines() if not line.startswith("D-25,")
        ) + "\n"
        duplicate = original + next(
            line for line in original.splitlines() if line.startswith("D-18,")
        ) + "\n"
        for mutated in (missing, duplicate):
            errors: list[str] = []
            validate_v1_traceability_text(errors, mutated)
            self.assertTrue(errors)

    def test_invalid_status_and_ungrounded_verified_are_rejected(self) -> None:
        invalid = mutate_traceability_field(
            self.traceability(), "D-18", "status", "COMPLETE"
        )
        ungrounded = mutate_traceability_field(
            self.traceability(), "D-19", "status", "VERIFIED"
        )
        for mutated in (invalid, ungrounded):
            errors: list[str] = []
            validate_v1_traceability_text(errors, mutated)
            self.assertTrue(errors)

    def test_open_question_cannot_block_slice_start(self) -> None:
        original = repository_governance_text("docs/00-governance/OPEN_QUESTIONS.md")
        mutated = original.replace(
            "| Onboarding and Pilot enablement |", "| IMPLEMENTATION_START |", 1
        )
        errors: list[str] = []
        validate_open_questions_v1_text(errors, mutated)
        self.assertTrue(any("implementation start" in error for error in errors))


class Dr0003ArtifactAuthorityTests(unittest.TestCase):
    def test_exact_review_and_hash_binding_are_valid(self) -> None:
        errors: list[str] = []
        validate_dr0003_controller_review_text(
            errors,
            repository_governance_text(
                "docs/08-handoffs/CONTROLLER-DR-0003-V1-BASELINE-RESET-REVIEW.md"
            ),
        )
        validate_dr0003_hash_binding_text(
            errors,
            repository_governance_text(
                "docs/08-handoffs/DR-0003-CONTROLLER-ARTIFACT-HASHES.md"
            ),
        )
        self.assertEqual([], errors)

    def test_package_cannot_claim_merge_or_production_authority(self) -> None:
        original = repository_governance_text(
            "docs/08-handoffs/CONTROLLER-DR-0003-V1-BASELINE-RESET-REVIEW.md"
        )
        for old, new in (
            ("merge_verdict: NOT_ISSUED", "merge_verdict: APPROVE_FOR_HUMAN_MERGE"),
            ("production_enablement: NOT_AUTHORIZED", "production_enablement: AUTHORIZED"),
        ):
            with self.subTest(field=old.split(":", 1)[0]):
                errors: list[str] = []
                validate_dr0003_controller_review_text(
                    errors, original.replace(old, new, 1)
                )
                self.assertTrue(errors)


class Dr0004GovernanceContractTests(unittest.TestCase):
    def root(self) -> Path:
        return Path(__file__).resolve().parents[1]

    def documents(self) -> dict[str, str]:
        paths = {
            "dr0004": "docs/00-governance/DR-0004-engineering-execution-closure-protocol-alignment.md",
            "envelope": "docs/00-governance/EXECUTION_ENVELOPE_POLICY.md",
            "closure_standard": "docs/00-governance/CLOSURE_SNAPSHOT_STANDARD.md",
            "current": "docs/00-governance/CURRENT_STATE.md",
            "change": "docs/00-governance/CHANGE_CONTROL.md",
            "claude_project": "docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md",
            "claude": "CLAUDE.md",
            "operating": "docs/00-governance/AI_OPERATING_MODEL.md",
            "review_standard": "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md",
            "quality": "docs/00-governance/QUALITY_GATES.md",
            "handoff": "docs/00-governance/HANDOFF_PROTOCOL.md",
            "source": "docs/01-requirements/SOURCE_MANIFEST.md",
            "guide": "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md",
            "agents": "AGENTS.md",
            "chatgpt_project": "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md",
            "contributing": "CONTRIBUTING.md",
            "readme": "README.md",
            "start": "START_HERE.md",
            "pr_template": ".github/pull_request_template.md",
            "decision": "docs/00-governance/DECISION_LOG.md",
            "v1_traceability": "docs/01-requirements/v1-traceability.csv",
        }
        return {
            name: (self.root() / relative).read_text(encoding="utf-8-sig")
            for name, relative in paths.items()
        }

    def validate(self, documents: dict[str, str] | None = None) -> list[str]:
        errors: list[str] = []
        validate_dr0004_protocol_texts(errors, documents or self.documents())
        return errors

    def test_exact_protocol_and_current_state_are_valid(self) -> None:
        self.assertEqual([], self.validate())
        errors: list[str] = []
        validate_dr0004_artifact_hashes(errors)
        self.assertEqual([], errors)

    def test_all_required_current_state_modes_are_fail_closed(self) -> None:
        for field, value in DR0004_CURRENT_STATE.items():
            with self.subTest(field=field):
                documents = self.documents()
                documents["current"] = documents["current"].replace(
                    f"{field}: {value}", f"{field}: REGRESSED", 1
                )
                errors = self.validate(documents)
                self.assertTrue(
                    any(f"CURRENT_STATE {field}" in error for error in errors)
                )

    def test_exact_accepted_r1_artifacts_and_original_contracts_are_byte_frozen(self) -> None:
        expected = {
            **DR0004_ARTIFACT_HASHES,
            **DR0004_R1_ARTIFACT_HASHES,
            **DR0004_PROTECTED_CONTRACT_HASHES,
        }
        for relative, digest in expected.items():
            with self.subTest(relative=relative):
                actual = hashlib.sha256((self.root() / relative).read_bytes()).hexdigest()
                self.assertEqual(digest, actual)

        with TemporaryDirectory() as directory:
            temp_root = Path(directory)
            for relative in expected:
                target = temp_root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((self.root() / relative).read_bytes())
            protected = temp_root / next(iter(DR0004_PROTECTED_CONTRACT_HASHES))
            protected.write_bytes(protected.read_bytes() + b"\nmutated original\n")
            errors: list[str] = []
            validate_dr0004_artifact_hashes(errors, root=temp_root)
            self.assertTrue(any("immutable hash mismatch" in error for error in errors))

    def test_missing_amendment_or_owner_acceptance_evidence_is_rejected(self) -> None:
        expected = {
            **DR0004_ARTIFACT_HASHES,
            **DR0004_R1_ARTIFACT_HASHES,
            **DR0004_PROTECTED_CONTRACT_HASHES,
        }
        targets = (
            "docs/00-governance/DR-0004-AMENDMENT-001-activation-and-owner-acceptance-provenance.md",
            "docs/08-handoffs/OWNER-DR-0004-ACCEPTANCE-EVIDENCE.md",
        )
        for missing in targets:
            with self.subTest(missing=missing), TemporaryDirectory() as directory:
                temp_root = Path(directory)
                for relative in expected:
                    if relative == missing:
                        continue
                    target = temp_root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes((self.root() / relative).read_bytes())
                errors: list[str] = []
                validate_dr0004_artifact_hashes(errors, root=temp_root)
                self.assertTrue(
                    any(
                        "DR-0004 hash target missing" in error and missing in error
                        for error in errors
                    )
                )

    def test_mismatched_amendment_or_owner_acceptance_evidence_is_rejected(self) -> None:
        expected = {
            **DR0004_ARTIFACT_HASHES,
            **DR0004_R1_ARTIFACT_HASHES,
            **DR0004_PROTECTED_CONTRACT_HASHES,
        }
        targets = (
            "docs/00-governance/DR-0004-AMENDMENT-001-activation-and-owner-acceptance-provenance.md",
            "docs/08-handoffs/OWNER-DR-0004-ACCEPTANCE-EVIDENCE.md",
        )
        for mismatched in targets:
            with self.subTest(mismatched=mismatched), TemporaryDirectory() as directory:
                temp_root = Path(directory)
                for relative in expected:
                    target = temp_root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes((self.root() / relative).read_bytes())
                target = temp_root / mismatched
                target.write_bytes(target.read_bytes() + b"\nmutated provenance\n")
                errors: list[str] = []
                validate_dr0004_artifact_hashes(errors, root=temp_root)
                self.assertTrue(
                    any(
                        "immutable hash mismatch" in error and mismatched in error
                        for error in errors
                    )
                )

    def test_proposal_status_cannot_be_interpreted_as_live_effective_state(self) -> None:
        for field, live, proposal in (
            (
                "dr0004_acceptance",
                "HUMAN_OWNER_ACCEPTED",
                "PROPOSED_PENDING_EXACT_OWNER_ACCEPTANCE",
            ),
            (
                "dr0004_repository_effect",
                "ACTIVE_ON_PROTECTED_MAIN",
                "PROPOSED_BY_DR_0004",
            ),
            (
                "dr0004_frozen_original_status_semantics",
                "PROPOSAL_TIME_PROVENANCE_ONLY",
                "LIVE_REPOSITORY_STATE",
            ),
        ):
            with self.subTest(field=field):
                documents = self.documents()
                documents["current"] = documents["current"].replace(
                    f"{field}: {live}", f"{field}: {proposal}", 1
                )
                errors = self.validate(documents)
                self.assertTrue(
                    any(f"CURRENT_STATE {field}" in error for error in errors)
                )

    def test_activation_requires_amendment_and_acceptance_evidence_bindings(self) -> None:
        for document, token in (
            (
                "current",
                "docs/08-handoffs/OWNER-DR-0004-ACCEPTANCE-EVIDENCE.md",
            ),
            (
                "source",
                "f83349ea537fd48575787dccfaa624ec39c5079181ccf0da6c69e996768bda88",
            ),
            (
                "source",
                "cea88c6b72b480ad7f39a45390e457de316b6be6511dad45a5d0f6c63716779c",
            ),
        ):
            with self.subTest(document=document, token=token):
                documents = self.documents()
                documents[document] = documents[document].replace(token, "removed", 1)
                errors = self.validate(documents)
                expected_label = (
                    "DR-0004 CURRENT_STATE"
                    if document == "current"
                    else f"DR-0004 {document}"
                )
                self.assertTrue(any(expected_label in error for error in errors))

    def test_d25_indexes_dr0004_without_product_or_slice_scope_change(self) -> None:
        documents = self.documents()
        documents["decision"] = documents["decision"].replace(
            "No V1 Product scope change and no SLICE-V1-001 scope change.",
            "V1 and Slice scope may change.",
            1,
        )
        errors = self.validate(documents)
        self.assertTrue(any("DR-0004 decision" in error for error in errors))

    def test_claude_has_one_combined_implementation_responsibility(self) -> None:
        documents = self.documents()
        documents["operating"] += (
            "\n- performs Detailed Design and Initial Full Implementation "
            "continuously inside the active Contract.\n"
        )
        errors = self.validate(documents)
        self.assertTrue(
            any("exactly one Claude implementation responsibility" in error for error in errors)
        )

    def test_accepted_contract_requires_additive_amendment(self) -> None:
        documents = self.documents()
        documents["change"] = documents["change"].replace(
            "An accepted original Product or Slice Contract is permanently byte-frozen.",
            "An accepted Contract may be edited and re-hashed.",
            1,
        )
        errors = self.validate(documents)
        self.assertTrue(any("DR-0004 change" in error for error in errors))

    def test_ordinary_claude_remote_git_authority_is_rejected(self) -> None:
        documents = self.documents()
        documents["claude"] += "\nClaude may push and create a PR under ordinary authority.\n"
        errors = self.validate(documents)
        self.assertTrue(any("prohibited ordinary Claude" in error for error in errors))

    def test_execution_envelope_levels_and_level_three_default_deny_are_required(self) -> None:
        documents = self.documents()
        documents["envelope"] = documents["envelope"].replace(
            "remote_git_default: DENY", "remote_git_default: ALLOW", 1
        )
        errors = self.validate(documents)
        self.assertTrue(any("DR-0004 envelope" in error for error in errors))

    def test_remote_publication_cannot_reconstruct_checkpoint(self) -> None:
        documents = self.documents()
        documents["handoff"] = documents["handoff"].replace(
            "It may not reconstruct, redesign or improve the\nimplementation during publication.",
            "It may reconstruct the implementation during publication.",
            1,
        )
        errors = self.validate(documents)
        self.assertTrue(any("DR-0004 handoff" in error for error in errors))

    def test_deep_review_requires_one_hash_bound_frozen_finding_set(self) -> None:
        documents = self.documents()
        documents["review_standard"] = documents["review_standard"].replace(
            "## 7. One-shot Deep Review and Frozen Finding Set",
            "## 7. Repeatable informal review",
            1,
        )
        errors = self.validate(documents)
        self.assertTrue(any("DR-0004 review_standard" in error for error in errors))

    def test_final_gate_rejects_open_ended_second_discovery(self) -> None:
        documents = self.documents()
        documents["review_standard"] = documents["review_standard"].replace(
            "It is not an open-ended second discovery review.",
            "It is an open-ended second discovery review.",
            1,
        )
        errors = self.validate(documents)
        self.assertTrue(any("DR-0004 review_standard" in error for error in errors))

    def test_old_evidence_miss_requires_coverage_failure_classification(self) -> None:
        documents = self.documents()
        documents["quality"] = documents["quality"].replace(
            "`CONTROLLER_REVIEW_COVERAGE_FAILURE`",
            "`NEW_DISCOVERY_ROUND`",
            1,
        )
        errors = self.validate(documents)
        self.assertTrue(any("DR-0004 quality" in error for error in errors))

    def test_owner_formal_closure_and_snapshot_are_required_before_next_slice(self) -> None:
        documents = self.documents()
        documents["guide"] = documents["guide"].replace(
            "Owner-accepted Closure Snapshot is required before the next Slice",
            "Closure Snapshot is optional before the next Slice",
            1,
        )
        errors = self.validate(documents)
        self.assertTrue(any("DR-0004 guide" in error for error in errors))

    def test_dual_truth_and_all_conflict_classes_are_required(self) -> None:
        for token in (
            "Normative Truth is ordered as:",
            "Implementation Fact is ordered as:",
            "IMPLEMENTATION_DEFECT",
            "CONTRACT_DEFECT",
            "DOCUMENTATION_DRIFT",
        ):
            with self.subTest(token=token):
                documents = self.documents()
                documents["source"] = documents["source"].replace(token, "removed", 1)
                errors = self.validate(documents)
                self.assertTrue(any("DR-0004 source" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
