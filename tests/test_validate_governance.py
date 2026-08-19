from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts.validate_governance import (
    CANONICAL_DESIGN_RELATIVE_PATH,
    HISTORIC_CONTRACT_BEGIN,
    HISTORIC_CONTRACT_END,
    WP_P0_002_ID,
    WP_P0_002_DESIGN_RELATIVE_PATH,
    WP_P0_002_RELATIVE_PATH,
    git_scan_paths,
    java_test_identity_inventory,
    validate_active_work_package_record_text,
    validate_backlog_state_text,
    validate_approved_design_state_text,
    validate_authorization_state_text,
    validate_completion_state_text,
    validate_controller_review_standard_text,
    validate_lifecycle_state_text,
    validate_owner_control_state_text,
    validate_parallel_current_state_paths,
    validate_prior_closed_transition_text,
    validate_readme_runtime_state_text,
    validate_wp_p0_002_completion_text,
    validate_wp_p0_002_test_identity_contract,
    validate_wp_p0_002_traceability_text,
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
                "Modular Monolith foundation verified; PostgreSQL Task/Outbox Worker "
                "is allocated to WP-P0-003 and is outside WP-P0-001 scope"
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
            "WP-P0-002;WP-P0-003",
            "PARTIAL in WP-P0-002; WP-P0-003",
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
        "ADM-002": "PARTIAL in WP-P0-002; WP-P0-003; WP-P0-002 subset VERIFIED; whole source requirement remains OPEN",
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
            "ADM-002,Requirement,0,title,WP-P0-002;WP-P0-003",
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


if __name__ == "__main__":
    unittest.main()
