from __future__ import annotations

import unittest

from scripts.validate_governance import validate_owner_control_state_text


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


if __name__ == "__main__":
    unittest.main()
