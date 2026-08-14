from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts.validate_governance import (
    CANONICAL_DESIGN_RELATIVE_PATH,
    git_scan_paths,
    validate_approved_design_state_text,
    validate_authorization_state_text,
    validate_lifecycle_state_text,
    validate_owner_control_state_text,
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


def authorization_current_state(authorization: str | None) -> str:
    field = f"authorization: {authorization}\n" if authorization is not None else ""
    return f"""# Current State

```yaml
{field}production_write_enabled: false
```
"""


def authorization_work_package(
    authorization: str | None,
    controller_verdict: str | None = None,
    *,
    include_controller_verdict: bool = True,
) -> str:
    row = f"| Authorization | {authorization} |\n" if authorization is not None else ""
    if controller_verdict is None:
        controller_verdict = (
            "APPROVED_FOR_IMPLEMENTATION"
            if authorization == "APPROVED_FOR_IMPLEMENTATION"
            else "AUTHORIZED_TO_START_DESIGN"
        )
    verdict = (
        f"Current verdict:\n\n```text\n{controller_verdict}\n```\n"
        if include_controller_verdict
        else ""
    )
    return f"""# WP-P0-001

## 1. Metadata

| Field | Value |
| --- | --- |
{row}| Status | READY_FOR_IMPLEMENTATION |

## 2. Outcome

Current functional contract.

## 10. Controller Gate

{verdict}
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
    return f"""# WP-P0-001

## 1. Metadata

| Field | Value |
| --- | --- |
| Authorization | {authorization} |

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
        self.assert_valid(
            "APPROVED_FOR_IMPLEMENTATION",
            "APPROVED_FOR_IMPLEMENTATION",
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
        self.assertTrue(any("WP-P0-001 Authorization must" in error for error in errors))

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
                "WP-P0-001 Authorization metadata is missing" in error
                for error in errors
            )
        )


class ControllerGateStateTests(unittest.TestCase):
    def test_controller_gate_matches_design_only_state(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("DESIGN_ONLY"),
            authorization_work_package("DESIGN_ONLY"),
        )
        self.assertEqual([], errors)

    def test_controller_gate_mismatch_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("APPROVED_FOR_IMPLEMENTATION"),
            authorization_work_package(
                "APPROVED_FOR_IMPLEMENTATION",
                "AUTHORIZED_TO_START_DESIGN",
            ),
        )
        self.assertTrue(any("controller gate mismatch" in error for error in errors))

    def test_missing_controller_gate_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("APPROVED_FOR_IMPLEMENTATION"),
            authorization_work_package(
                "APPROVED_FOR_IMPLEMENTATION",
                include_controller_verdict=False,
            ),
        )
        self.assertTrue(
            any("current Controller verdict is missing" in error for error in errors)
        )

    def test_unknown_controller_gate_is_rejected(self) -> None:
        errors: list[str] = []
        validate_authorization_state_text(
            errors,
            authorization_current_state("APPROVED_FOR_IMPLEMENTATION"),
            authorization_work_package(
                "APPROVED_FOR_IMPLEMENTATION",
                "APPROVED_FOR_LATER",
            ),
        )
        self.assertTrue(
            any("current Controller verdict must" in error for error in errors)
        )


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


if __name__ == "__main__":
    unittest.main()
