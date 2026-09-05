from __future__ import annotations

import json
import unittest
from pathlib import Path

from scripts.validate_production_readiness import (
    APPROVED_MIGRATIONS,
    TOLERANT_SCHEMA_CREATION,
    approved_index_replacement,
    DEFERRED_ACCEPTANCE_IDS,
    DEFERRED_EVIDENCE_REGISTER,
    HASH_PINNED_DOC_PATHS,
    DESTRUCTIVE_MIGRATION_STATEMENT,
    FORBIDDEN_BACKEND_DEPENDENCIES,
    FOUNDATION_MIGRATION_SHA256,
    HISTORY_COMMENT_PATTERNS,
    IDENTIFIER_CONTEXT,
    IDENTIFIER_SCAFFOLD_TERMS,
    REQUIRED_NAMES,
    ROOT,
    RETIRED_ARTEFACTS,
    RULE_DEFINITION_PATHS,
    SCAFFOLD_TERMS,
    UNRESOLVED_MARKERS,
    ACTION_REFERENCE,
    ARCHITECTURE_RULE_TOKENS,
    BUILT_PREVIEW_COMMAND,
    BASE_HIKARI_AUTOCOMMIT_TOKENS,
    COMPLETED_WORK_PACKAGE_TOKENS,
    COMPLETION_STATE_TOKENS,
    completion_state_violations,
    ECS_CORRELATION_CUSTOMIZER_TOKENS,
    LOCAL_LOGGING_TOKENS,
    PATH_RESTRICTION,
    PENDING_EVIDENCE,
    POLLING_CONTRACT_TOKENS,
    PR_SECURITY_EVIDENCE_TOKENS,
    STRUCTURED_LOGGING_TOKENS,
    action_reference_violations,
    browser_acceptance_contract_violations,
    base_environment_identity_violations,
    comment_lines,
    contract_token_violations,
    declared_dependency_artifacts,
    deferred_evidence_register_violations,
    matching_lines,
    pr_security_evidence_violations,
    runner_reference_violations,
    unsafe_throwable_logging_violations,
    backlog_completion_contract_violations,
    browser_source_identity_contract_violations,
)


class ExclusionScopeTests(unittest.TestCase):
    """Both exclusion lists are exact, tested path lists that cannot widen."""

    def test_exclusion_list_is_exactly_the_rule_definition(self) -> None:
        self.assertEqual(
            (
                "scripts/validate_production_readiness.py",
                "tests/test_validate_production_readiness.py",
            ),
            RULE_DEFINITION_PATHS,
        )

    def test_hash_pinned_docs_are_exactly_the_approved_design(self) -> None:
        self.assertEqual(
            (
                "docs/02-architecture/designs/"
                "WP-P0-002-organization-store-warehouse-credential-metadata-design.md",
            ),
            HASH_PINNED_DOC_PATHS,
        )

    def test_no_directory_is_excluded(self) -> None:
        for entry in RULE_DEFINITION_PATHS + HASH_PINNED_DOC_PATHS:
            with self.subTest(entry=entry):
                self.assertFalse(entry.endswith("/"))
                self.assertNotIn("*", entry)


class UnresolvedMarkerTests(unittest.TestCase):
    def test_markers_are_detected(self) -> None:
        for marker in ("TODO", "FIXME", "HACK", "XXX"):
            with self.subTest(marker=marker):
                self.assertTrue(UNRESOLVED_MARKERS.search(f"// {marker}: finish this"))

    def test_word_containing_a_marker_is_not_detected(self) -> None:
        self.assertIsNone(UNRESOLVED_MARKERS.search("the TODOS_TABLE constant"))
        self.assertIsNone(UNRESOLVED_MARKERS.search("xxxyz"))


class DeferredEvidenceRegisterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.register = json.loads((ROOT / DEFERRED_EVIDENCE_REGISTER).read_text())

    def test_register_exactly_covers_amendment_002(self) -> None:
        self.assertEqual([], deferred_evidence_register_violations(self.register))
        self.assertEqual(
            set(DEFERRED_ACCEPTANCE_IDS),
            {entry["acceptanceId"] for entry in self.register["entries"]},
        )

    def test_deferred_evidence_cannot_be_relabelled_verified(self) -> None:
        mutated = json.loads(json.dumps(self.register))
        mutated["entries"][0]["currentStatus"] = "VERIFIED"
        errors = deferred_evidence_register_violations(mutated)
        self.assertTrue(any("must not be relabeled VERIFIED" in error for error in errors))

    def test_a_deferred_acceptance_cannot_disappear(self) -> None:
        mutated = json.loads(json.dumps(self.register))
        mutated["entries"].pop()
        errors = deferred_evidence_register_violations(mutated)
        self.assertTrue(any("exactly cover Amendment-002" in error for error in errors))


class RepositoryContractPatternTests(unittest.TestCase):
    def test_mutable_action_reference_is_rejected(self) -> None:
        workflow = "      - uses: actions/checkout@v7\n"
        self.assertEqual([(1, "- uses: actions/checkout@v7")], action_reference_violations(workflow))

    def test_sha_requires_a_version_comment(self) -> None:
        sha = "a" * 40
        self.assertEqual(
            [(1, f"- uses: actions/checkout@{sha}")],
            action_reference_violations(f"      - uses: actions/checkout@{sha}\n"),
        )
        self.assertEqual(
            [],
            action_reference_violations(f"      - uses: actions/checkout@{sha} # v7\n"),
        )

    def test_action_pattern_reads_the_reference_and_version(self) -> None:
        match = ACTION_REFERENCE.match(f"  uses: actions/setup-node@{'b' * 40} # v6")
        self.assertIsNotNone(match)
        self.assertEqual("v6", match.group("version"))

    def test_path_avoidance_language_is_rejected(self) -> None:
        text = "move the clone to a path without spaces\nnormal relative path\n"
        self.assertEqual([(1, "move the clone to a path without spaces")], matching_lines(text, PATH_RESTRICTION))

    def test_pending_implementation_evidence_is_rejected(self) -> None:
        text = "State: PENDING_LOCAL_EXECUTION\nState: PASS\n"
        self.assertEqual([(1, "State: PENDING_LOCAL_EXECUTION")], matching_lines(text, PENDING_EVIDENCE))

    def test_floating_runner_is_rejected(self) -> None:
        self.assertEqual(
            [(2, "runs-on: ubuntu-latest")],
            runner_reference_violations("name: check\n  runs-on: ubuntu-latest\n"),
        )
        self.assertEqual(
            [],
            runner_reference_violations("  runs-on: ubuntu-24.04\n"),
        )

    def test_missing_approved_architecture_rule_is_rejected(self) -> None:
        source = "\n".join(ARCHITECTURE_RULE_TOKENS)
        mutated = source.replace("domainDoesNotDependOutward", "")
        self.assertTrue(
            any(
                "domainDoesNotDependOutward" in violation
                for violation in contract_token_violations(
                    mutated, required=ARCHITECTURE_RULE_TOKENS
                )
            )
        )

    def test_missing_prefix_collision_contract_is_rejected(self) -> None:
        source = "\n".join(ARCHITECTURE_RULE_TOKENS)
        mutated = source.replace("isWithin(item.getPackageName(), owningModulePackage)", "")
        self.assertTrue(
            any("owningModulePackage" in violation for violation in contract_token_violations(
                mutated, required=ARCHITECTURE_RULE_TOKENS
            ))
        )

    def test_missing_named_interface_contract_is_rejected(self) -> None:
        source = "\n".join(ARCHITECTURE_RULE_TOKENS)
        mutated = source.replace("isNamedInterface(item)", "")
        violations = contract_token_violations(mutated, required=ARCHITECTURE_RULE_TOKENS)
        self.assertTrue(any("isNamedInterface(item)" in violation for violation in violations))

    def test_missing_exact_internal_segment_contract_is_rejected(self) -> None:
        source = "\n".join(ARCHITECTURE_RULE_TOKENS)
        mutated = source.replace("segment.equals(INTERNAL_SEGMENT)", "")
        violations = contract_token_violations(mutated, required=ARCHITECTURE_RULE_TOKENS)
        self.assertTrue(any("segment.equals" in violation for violation in violations))

    def test_throwable_logger_argument_is_rejected(self) -> None:
        source = 'log.error("request failed", exception);\n'
        self.assertEqual([1], unsafe_throwable_logging_violations(source))

    def test_profile_database_logger_override_is_rejected(self) -> None:
        violations = contract_token_violations(
            "logging:\n  level:\n    org.flywaydb: INFO\n",
            prohibited=("org.flywaydb:", "com.zaxxer.hikari:", "org.postgresql:"),
        )
        self.assertTrue(any("org.flywaydb:" in violation for violation in violations))

    def test_missing_non_local_structured_logging_is_rejected(self) -> None:
        source = "\n".join(STRUCTURED_LOGGING_TOKENS)
        mutated = source.replace("console: ecs", "")
        violations = contract_token_violations(mutated, required=STRUCTURED_LOGGING_TOKENS)
        self.assertTrue(any("console: ecs" in violation for violation in violations))

    def test_missing_structured_identity_field_is_rejected(self) -> None:
        source = "\n".join(STRUCTURED_LOGGING_TOKENS)
        mutated = source.replace("environment: ${marketops.environment}", "")
        violations = contract_token_violations(mutated, required=STRUCTURED_LOGGING_TOKENS)
        self.assertTrue(any("environment:" in violation for violation in violations))

    def test_no_mdc_ecs_fallback_is_required(self) -> None:
        source = "\n".join(STRUCTURED_LOGGING_TOKENS)
        mutated = source.replace(
            "customizer: com.mimococo.marketops.shared.internal.logging."
            "EcsCorrelationIdJsonMembersCustomizer",
            "",
        )
        violations = contract_token_violations(mutated, required=STRUCTURED_LOGGING_TOKENS)
        self.assertTrue(any("customizer:" in violation for violation in violations))

        customizer = "\n".join(ECS_CORRELATION_CUSTOMIZER_TOKENS)
        no_fallback = customizer.replace('NO_REQUEST = "none"', "")
        violations = contract_token_violations(
            no_fallback, required=ECS_CORRELATION_CUSTOMIZER_TOKENS
        )
        self.assertTrue(any("NO_REQUEST" in violation for violation in violations))

    def test_missing_local_safe_key_values_is_rejected(self) -> None:
        source = "\n".join(LOCAL_LOGGING_TOKENS).replace("%kvp", "")
        violations = contract_token_violations(source, required=LOCAL_LOGGING_TOKENS)
        self.assertTrue(any("%kvp" in violation for violation in violations))

    def test_base_environment_fallback_is_rejected(self) -> None:
        base = "marketops:\n  product: MarketOps Russia\n  environment: unspecified\n"
        self.assertEqual([3], base_environment_identity_violations(base))
        self.assertEqual(
            [],
            base_environment_identity_violations("marketops:\n  product: MarketOps Russia\n"),
        )

    def test_base_hikari_auto_commit_must_remain_true(self) -> None:
        source = "\n".join(BASE_HIKARI_AUTOCOMMIT_TOKENS)
        mutated = source.replace("auto-commit: true", "auto-commit: false")
        violations = contract_token_violations(
            mutated, required=BASE_HIKARI_AUTOCOMMIT_TOKENS
        )

        self.assertTrue(any("auto-commit: true" in violation for violation in violations))

    def test_a_closed_slice_cannot_be_reopened_by_the_next_one_starting(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        mutated = source.replace(
            "slice_v1_001_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
            "slice_v1_001_state: IMPLEMENTATION_IN_PROGRESS",
        )
        violations = contract_token_violations(mutated, required=COMPLETION_STATE_TOKENS)
        self.assertTrue(
            any("CLOSED_ENGINEERING" in violation for violation in violations)
        )

    def test_the_closed_active_slice_cannot_regress_its_exact_closure(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        for token, regressed in (
            (
                "slice_v1_002_controller_verdict: PASS_R3_ENGINEERING_FINAL_GATE",
                "NOT_CLAIMED",
            ),
            (
                "slice_v1_002_owner_formal_closure: HUMAN_OWNER_ACCEPTED",
                "NOT_CLAIMED",
            ),
            (
                "slice_v1_002_remote_publication: PR_26_MERGED_PROTECTED_SQUASH",
                "DRAFT_PR_26_OPEN_REQUIRED_CHECKS_PASS",
            ),
        ):
            with self.subTest(token=token):
                field = token.split(":", 1)[0]
                mutated = source.replace(token, f"{field}: {regressed}")
                violations = contract_token_violations(
                    mutated, required=COMPLETION_STATE_TOKENS
                )
                self.assertTrue(any(field in violation for violation in violations))

    def test_slice_v1_002_squash_identity_is_required(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        mutated = source.replace(
            "slice_v1_002_actual_squash_tree: f7e02da0bf38922f6c5a80d49b263613ade997d9",
            "slice_v1_002_actual_squash_tree: 0000000000000000000000000000000000000000",
        )
        violations = contract_token_violations(mutated, required=COMPLETION_STATE_TOKENS)
        self.assertTrue(any("slice_v1_002_actual_squash_tree" in item for item in violations))

    def test_slice_v1_002_security_fix_identity_and_alert_closure_are_required(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        for old, new in (
            (
                "slice_v1_002_security_fix_actual_squash_commit: "
                "e0184852785f451256a36f52fa3d520ceea2c313",
                "slice_v1_002_security_fix_actual_squash_commit: " + "0" * 40,
            ),
            (
                "slice_v1_002_post_merge_code_scanning_alerts: "
                "116_117_FIXED_BY_CODE_NO_DISMISSAL",
                "slice_v1_002_post_merge_code_scanning_alerts: 116_117_OPEN",
            ),
        ):
            with self.subTest(field=old.split(":", 1)[0]):
                mutated = source.replace(old, new)
                violations = contract_token_violations(
                    mutated, required=COMPLETION_STATE_TOKENS
                )
                self.assertTrue(any(old.split(":", 1)[0] in item for item in violations))

    def test_post_merge_squash_identity_is_required(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        mutated = source.replace(
            "slice_v1_001_actual_squash_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b",
            "slice_v1_001_actual_squash_tree: 0000000000000000000000000000000000000000",
        )
        violations = contract_token_violations(mutated, required=COMPLETION_STATE_TOKENS)
        self.assertTrue(any("actual_squash_tree" in violation for violation in violations))

    def test_formal_closure_evidence_identity_is_required(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        for old, new in (
            (
                "slice_v1_001_owner_formal_closure: HUMAN_OWNER_ACCEPTED",
                "slice_v1_001_owner_formal_closure: PENDING",
            ),
            (
                "slice_v1_001_controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
                "slice_v1_001_controller_bookkeeping_verdict: MISSING",
            ),
            (
                "slice_v1_001_snapshot_sha256: 5abce67327673dc0248f11ece1f31cd11d1ec7c0e69a1e84823ddedf30aab2e3",
                "slice_v1_001_snapshot_sha256: " + "0" * 64,
            ),
            (
                "slice_v1_001_owner_acceptance_evidence_sha256: 50c171f24037cf36ccb4724288a7b82831b7dd008985f9b594ef2020c1c5ef33",
                "slice_v1_001_owner_acceptance_evidence_sha256: " + "0" * 64,
            ),
        ):
            with self.subTest(field=old.split(":", 1)[0]):
                mutated = source.replace(old, new)
                violations = contract_token_violations(
                    mutated, required=COMPLETION_STATE_TOKENS
                )
                self.assertTrue(violations)

    def test_slice3_engineering_result_keeps_controller_and_production_boundaries(self) -> None:
        current = (ROOT / "docs/00-governance/CURRENT_STATE.md").read_text()
        self.assertEqual([], completion_state_violations(current))
        current_line = lambda field: next(line for line in current.splitlines() if line.startswith(field + ": "))
        for before, after in (
            (current_line("slice_v1_003_rework_status"),
             "slice_v1_003_rework_status: FORMALLY_CLOSED"),
            (current_line("slice_v1_003_controller_verdict"),
             "slice_v1_003_controller_verdict: APPROVE_FOR_HUMAN_MERGE"),
            ("slice_v1_003_historical_controller_verdict: NOT_PASS_EXISTING_FINDINGS_NOT_FULLY_CLOSED",
             "slice_v1_003_historical_controller_verdict: PASS"),
            ("slice_v1_003_historical_controller_reviewed_head: 3ff042df66d5d6924b587cac96fc652b93bf5e7a",
             "slice_v1_003_historical_controller_reviewed_head: 0000000000000000000000000000000000000000"),
            ("slice_v1_003_historical_controller_report_sha256: 6f9581d9b09485a35fe404b13ab06422dc2672b7182afc52da2442dcc7660127",
             "slice_v1_003_historical_controller_report_sha256: altered"),
            (current_line("next_authorized_actor"), "next_authorized_actor: IMPLEMENTER_SELF_APPROVAL"),
            ("production_write_enabled: false", "production_write_enabled: true"),
            ("gate_ev: NOT_AUTHORIZED", "gate_ev: AUTHORIZED"),
        ):
            with self.subTest(field=before.split(":", 1)[0]):
                self.assertIn(before, current)
                mutated = current.replace(before, after, 1)
                self.assertNotEqual(current, mutated)
                violations = completion_state_violations(mutated)
                self.assertTrue(any(before.split(":", 1)[0] in item for item in violations))

    def test_enabled_production_write_is_rejected(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        mutated = source.replace(
            "production_write_enabled: false", "production_write_enabled: true"
        )
        violations = contract_token_violations(
            mutated, required=COMPLETION_STATE_TOKENS
        )
        self.assertTrue(any("production_write_enabled: false" in item for item in violations))

    def test_bounded_real_write_authority_defaults_fail_closed(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        mutated = source.replace(
            "bounded_real_write_verification_authorization: NONE",
            "bounded_real_write_verification_authorization: AUTHORIZED",
        )
        violations = contract_token_violations(
            mutated, required=COMPLETION_STATE_TOKENS
        )
        self.assertTrue(
            any(
                "bounded_real_write_verification_authorization: NONE" in item
                for item in violations
            )
        )

    def test_platform_write_must_remain_disabled(self) -> None:
        source = "\n".join(COMPLETION_STATE_TOKENS)
        mutated = source.replace(
            "ozon_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE",
            "ozon_price_write: ENABLED",
        )
        violations = contract_token_violations(
            mutated, required=COMPLETION_STATE_TOKENS
        )
        self.assertTrue(any("ozon_price_write" in item for item in violations))

    def test_candidate_completed_work_package_is_rejected(self) -> None:
        source = "\n".join(COMPLETED_WORK_PACKAGE_TOKENS)
        mutated = source.replace("| Status | COMPLETED |", "| Status | IMPLEMENTED_CANDIDATE |")
        violations = contract_token_violations(mutated, required=COMPLETED_WORK_PACKAGE_TOKENS)
        self.assertTrue(any("Status | COMPLETED" in violation for violation in violations))

    def test_transient_frontend_ref_version_is_rejected(self) -> None:
        violations = contract_token_violations(
            "MARKETOPS_BUILD_VERSION: ${{ github.ref_name }}",
            prohibited=("MARKETOPS_BUILD_VERSION", "github.ref_name"),
        )
        self.assertEqual(2, len(violations))

    def test_safe_structured_logger_fields_are_accepted(self) -> None:
        source = 'log.atError().addKeyValue("exception_class", exception.getClass().getName()).log("failed");\n'
        self.assertEqual([], unsafe_throwable_logging_violations(source))

    def test_dev_server_browser_acceptance_is_rejected(self) -> None:
        config = "baseURL: 'http://127.0.0.1:5173'\ncommand: 'npm run dev'\n"
        violations = browser_acceptance_contract_violations(config, "")
        self.assertTrue(any("npm run dev" in violation for violation in violations))

    def test_missing_recovery_assertion_is_rejected(self) -> None:
        config = f"baseURL: 'http://127.0.0.1:4173'\n{BUILT_PREVIEW_COMMAND}\n"
        scenario = "compose('stop', 'postgres')\nwaitForDatabaseStatus(page, 'DOWN')\n"
        violations = browser_acceptance_contract_violations(config, scenario)
        self.assertTrue(any("waitForDatabaseStatus(page, 'UP')" in violation for violation in violations))

    def test_missing_polling_backoff_contract_is_rejected(self) -> None:
        source = "\n".join(POLLING_CONTRACT_TOKENS).replace("failedAttempts", "")
        violations = contract_token_violations(source, required=POLLING_CONTRACT_TOKENS)
        self.assertTrue(any("failedAttempts" in violation for violation in violations))

    def test_stale_pr_security_evidence_is_rejected(self) -> None:
        expected_head = "a" * 40
        evidence = "\n".join(PR_SECURITY_EVIDENCE_TOKENS) + f"\n{expected_head}\n"
        self.assertEqual([], pr_security_evidence_violations(evidence, expected_head))
        violations = pr_security_evidence_violations(evidence, "b" * 40)
        self.assertTrue(any("security evidence is stale" in violation for violation in violations))

    def test_backlog_status_regressions_are_rejected(self) -> None:
        completed = self.backlog("COMPLETED")
        self.assertEqual([], backlog_completion_contract_violations(completed))
        for status in ("READY_FOR_DESIGN", "UNKNOWN"):
            with self.subTest(status=status):
                self.assertTrue(
                    backlog_completion_contract_violations(
                        completed.replace("| COMPLETED |", f"| {status} |", 1)
                    )
                )

    def test_missing_and_duplicate_backlog_rows_are_rejected(self) -> None:
        completed = self.backlog("COMPLETED")
        row = "| WP-P0-001 | Foundation | COMPLETED | None | D-03 |\n"
        self.assertTrue(backlog_completion_contract_violations(completed.replace(row, "")))
        self.assertTrue(backlog_completion_contract_violations(completed.replace(row, row + row)))

    def test_frontend_test_requires_authored_source_expression(self) -> None:
        workflow, config, scenario, resolver = self.source_identity_contract()
        self.assertEqual(
            [],
            browser_source_identity_contract_violations(workflow, config, scenario, resolver),
        )
        for mutated in (
            workflow.replace(
                "${{ github.event.pull_request.head.sha || github.sha }}",
                "${{ github.sha }}",
            ),
            workflow.replace("MARKETOPS_SOURCE_HEAD_SHA:", "REMOVED_SOURCE_HEAD:"),
        ):
            self.assertTrue(
                browser_source_identity_contract_violations(
                    mutated, config, scenario, resolver
                )
            )

    def test_checkout_head_and_identity_divergence_are_rejected(self) -> None:
        workflow, config, scenario, resolver = self.source_identity_contract()
        checkout_head = scenario.replace(
            "const sourceHead = resolveBrowserSourceIdentity(repositoryRoot);",
            "const sourceHead = execFileSync('git', ['rev-parse', 'HEAD']);",
        )
        divergent = scenario.replace("(${sourceHead})", "(${checkoutHead})")
        self.assertTrue(
            browser_source_identity_contract_violations(
                workflow, config, checkout_head, resolver
            )
        )
        self.assertTrue(
            browser_source_identity_contract_violations(
                workflow, config, divergent, resolver
            )
        )

    def test_ci_source_resolver_cannot_fall_back_to_repository_head(self) -> None:
        workflow, config, scenario, resolver = self.source_identity_contract()
        mutated = resolver.replace("if (isContinuousIntegration(environment))", "if (false)")
        self.assertTrue(
            browser_source_identity_contract_violations(
                workflow, config, scenario, mutated
            )
        )

    @staticmethod
    def backlog(status: str) -> str:
        return f"""# Backlog

| ID | Title | Status | Dependencies | Core source requirements |
| --- | --- | --- | --- | --- |
| WP-P0-001 | Foundation | {status} | None | D-03 |
| WP-P0-002 | Metadata | DRAFT | WP-P0-001 | IAM-001 |
"""

    @staticmethod
    def source_identity_contract() -> tuple[str, str, str, str]:
        workflow = """jobs:
  frontend-test:
    steps:
      - run: npm run test:browser
        env:
          MARKETOPS_SOURCE_HEAD_SHA: ${{ github.event.pull_request.head.sha || github.sha }}
  frontend-build:
    steps: []
"""
        config = """const sourceHead = resolveBrowserSourceIdentity(repositoryRoot);
MARKETOPS_BUILD_COMMIT: sourceHead
"""
        scenario = """const sourceHead = resolveBrowserSourceIdentity(repositoryRoot);
`Console ${frontendVersion} (${sourceHead})`
"""
        resolver = """FULL_SOURCE_SHA
if (isContinuousIntegration(environment))
CI browser verification requires ${SOURCE_HEAD_ENVIRONMENT_VARIABLE}
repositoryHeadReader(repositoryRoot)
"""
        return workflow, config, scenario, resolver


class MigrationContractTests(unittest.TestCase):
    """The approved migration set, the immutability pin and the statement ban."""

    def test_the_approved_set_is_the_metadata_and_control_plane_migrations(self) -> None:
        self.assertEqual(
            (
                "V0001__create_foundation_schemas.sql",
                "V0002__enable_btree_gist_extension.sql",
                "V0003__create_metadata_audit_event.sql",
                "V0004__create_core_organization_metadata.sql",
                "V0005__create_iam_access_metadata.sql",
                "V0006__create_platform_registry_metadata.sql",
                "V0007__create_ingestion_control_plane_authority.sql",
                "V0008__attach_control_epoch_triggers.sql",
                "V0009__create_control_boundary_kinds_and_decision_evidence.sql",
                "V0010__create_ingestion_run_checkpoint_and_raw_evidence.sql",
                "V0011__create_human_identity_and_business_authorization.sql",
                "V0012__create_product_listing_identity_and_mapping.sql",
                "V0013__create_cross_domain_operating_facts.sql",
                "V0014__create_internal_fact_intake_and_file_import.sql",
                "V0015__create_canonical_metric_definitions_and_values.sql",
                "V0016__create_deterministic_diagnosis_rules_and_findings.sql",
                "V0017__create_ai_projection_invocation_and_output.sql",
                "V0018__create_recommendation_task_and_approval_workflow.sql",
                "V0019__create_commercial_policy_and_guardrails.sql",
                "V0020__create_price_command_outbox_readback_and_write_gate.sql",
                "V0021__create_platform_api_profile_and_request_shape.sql",
                "V0022__create_ingestion_run_lifecycle_and_replay_guard.sql",
                "V0023__create_declared_normalization_and_drift_observation.sql",
                "V0024__create_capability_write_operation_shape.sql",
                "V0025__create_price_command_attempt_completion_and_lease_recovery.sql",
                "V0026__rename_operational_capability_column_to_action_kind.sql",
                "V0027__create_account_bound_registry_verification.sql",
                "V0028__create_bounded_diagnostic_export.sql",
                "V0029__version_profit_economics_and_commercial_inputs.sql",
                "V0030__create_availability_risk_policy_inbound_and_case.sql",
                "V0031__track_sustained_availability_lane.sql",
                "V0032__create_availability_fact_feed_cursor.sql",
                "V0033__track_case_improvement_observation.sql",
                "V0034__close_availability_deep_review_findings.sql",
                "V0035__close_availability_targeted_findings.sql",
                "V0036__create_advertising_identity_and_official_facts.sql",
                "V0037__create_advertising_conversion_freshness_and_qualification.sql",
                "V0038__create_advertising_case_projection_and_orchestration.sql",
                "V0039__create_advertising_target_materiality_and_manual_shadow.sql",
                "V0040__widen_write_registry_for_ad_bid_capability.sql",
                "V0041__create_advertising_containment_and_decision_bundle.sql",
                "V0042__create_ad_bid_command_outbox_readback_and_gate.sql",
                "V0043__create_ad_bid_attempt_lifecycle_and_readback.sql",
                "V0044__supersede_advertising_cases_whose_cause_no_longer_holds.sql",
                "V0045__create_ad_bid_command_from_approval.sql",
                "V0046__capture_ad_bid_authority_for_guardrail_evaluation.sql",
                "V0047__refuse_a_zero_target_bid_in_the_parameter_contract.sql",
                "V0048__serialize_advertising_reservations_against_overlap.sql",
                "V0049__create_advertising_outcome_plan_and_lineage.sql",
                "V0050__cause_specific_outcomes_and_same_lineage_reopen.sql",
                "V0051__bind_each_decision_to_its_own_authority.sql",
                "V0052__a_guardrail_verdict_names_the_policy_that_authorised_it.sql",
                "V0053__the_write_gate_must_refuse_rather_than_raise.sql",
                "V0054__index_the_demand_carry_forward_lookup.sql",
                "V0055__record_what_happened_to_a_task_and_who_did_it.sql",
                "V0056__publish_the_daily_brief_and_weekly_review_as_projections.sql",
                "V0057__bind_advertising_responsibility_and_human_decisions.sql",
                "V0058__seal_advertising_authority_and_control_execution.sql",
                "V0059__freeze_advertising_outcome_baselines_and_critical_units.sql",
                "V0060__govern_manual_proposals_packets_and_configuration_proof.sql",
                "V0061__bind_advertising_exception_risk_and_preview_evidence.sql",
                "V0062__share_frozen_outcome_authority_with_governed_manual.sql",
                "V0063__wire_advertising_changes_expiries_and_slo_recovery.sql",
                "V0064__reconcile_expired_advertising_authority.sql",
                "V0065__route_settled_advertising_contradictions_to_finance_review.sql",
                "V0066__qualify_economic_cause_bound_protection.sql",
                "V0067__validate_frozen_outcome_input_profiles.sql",
                "V0068__preserve_critical_sales_guard_case_evidence.sql",
                "V0069__reopen_invalidated_protection_outcomes.sql",
                "V0070__record_canonical_metric_reevaluation_proofs.sql",
            ),
            APPROVED_MIGRATIONS,
        )

    def test_tolerant_schema_creation_is_ddl_and_not_a_plpgsql_guard(self) -> None:
        # The rule refuses schema creation that tolerates an existing object.
        # PL/pgSQL's `IF NOT EXISTS (SELECT ...)` is a boolean expression and
        # creates nothing, so reading the bare string as tolerant DDL refused
        # every migration that used a conditional at all.
        for tolerant in (
            "CREATE TABLE IF NOT EXISTS core.store (id uuid)",
            "CREATE INDEX IF NOT EXISTS store_ix ON core.store (id)",
            "CREATE SCHEMA IF NOT EXISTS ops",
            "ALTER TABLE core.store ADD COLUMN IF NOT EXISTS code text",
        ):
            with self.subTest(statement=tolerant):
                self.assertTrue(TOLERANT_SCHEMA_CREATION.search(tolerant.upper()))
        for guard in (
            "IF NOT EXISTS (SELECT 1 FROM core.store) THEN",
            "    IF NOT EXISTS (",
            "IF NOT EXISTS(SELECT 1) THEN",
        ):
            with self.subTest(statement=guard):
                self.assertIsNone(TOLERANT_SCHEMA_CREATION.search(guard.upper()))

    def test_the_foundation_pin_is_a_sha256_digest(self) -> None:
        self.assertRegex(FOUNDATION_MIGRATION_SHA256, r"^[0-9a-f]{64}$")

    def test_destructive_statements_are_detected(self) -> None:
        for statement in (
            "DROP TABLE core.store;",
            "  drop schema ledger cascade;",
            "TRUNCATE ops.metadata_audit_event;",
            "DELETE FROM platform.credential_metadata;",
            "DROP INDEX credential_metadata_account_ix;",
            "DROP ROLE marketops_app;",
        ):
            with self.subTest(statement=statement):
                self.assertTrue(DESTRUCTIVE_MIGRATION_STATEMENT.search(statement))

    def test_creation_and_grants_are_not_detected(self) -> None:
        for statement in (
            "CREATE TABLE platform.feature_flag (id uuid NOT NULL);",
            "GRANT SELECT, INSERT ON ops.metadata_audit_event TO marketops_app;",
            "CREATE UNIQUE INDEX feature_flag_scope_uq ON platform.feature_flag (flag_code);",
            "ALTER TABLE core.store ADD CONSTRAINT store_pk PRIMARY KEY (id);",
        ):
            with self.subTest(statement=statement):
                self.assertIsNone(DESTRUCTIVE_MIGRATION_STATEMENT.search(statement))

    def test_the_product_scope_index_replacement_is_narrowly_approved(self) -> None:
        root = Path(__file__).resolve().parents[1]
        path = root / "backend/marketops-server/src/main/resources/db/migration/V0034__close_availability_deep_review_findings.sql"
        text = path.read_text(encoding="utf-8")
        line = "DROP INDEX iam.user_scope_grant_active_uq;"

        self.assertTrue(approved_index_replacement(path, text, line))
        self.assertFalse(approved_index_replacement(path.with_name("V9999__unsafe.sql"), text, line))
        self.assertFalse(approved_index_replacement(path, text.replace("product_variant_ref_id", "omitted"), line))


    def test_advertising_identity_index_replacement_preserves_all_three_authorities(self) -> None:
        root = Path(__file__).resolve().parents[1]
        path = root / "backend/marketops-server/src/main/resources/db/migration/V0064__reconcile_expired_advertising_authority.sql"
        text = path.read_text(encoding="utf-8")
        line = "DROP INDEX ops.recommendation_live_uq;"
        self.assertTrue(approved_index_replacement(path, text, line))
        self.assertFalse(approved_index_replacement(path.with_name("V9999__unsafe.sql"), text, line))
        self.assertFalse(approved_index_replacement(path, text, "DROP INDEX ops.ad_case_responsibility;"))
        for removed in ("'caseId'", "'candidateId'", "'APPROVED',", "'ADVERTISING_REVIEW','AD_BID_CHANGE'"):
            with self.subTest(removed=removed):
                self.assertFalse(approved_index_replacement(path, text.replace(removed, ""), line))


class CommentExtractionTests(unittest.TestCase):
    def test_block_comment_lines_are_returned(self) -> None:
        source = "\n".join(
            [
                "package com.mimococo.marketops;",
                "/**",
                " * Behaviour description.",
                " */",
                "class Example {}",
            ]
        )
        numbers = [number for number, _ in comment_lines(source, ".java")]
        self.assertEqual([2, 3, 4], numbers)

    def test_line_comment_is_returned(self) -> None:
        extracted = comment_lines("int a = 1; // explanation", ".java")
        self.assertEqual(1, len(extracted))
        self.assertIn("explanation", extracted[0][1])

    def test_code_outside_comments_is_ignored(self) -> None:
        self.assertEqual([], comment_lines('String value = "TODO";', ".java"))

    def test_double_slash_inside_a_string_is_not_a_comment(self) -> None:
        self.assertEqual([], comment_lines('String url = "https://example.invalid";', ".java"))

    def test_sql_line_comment_is_returned(self) -> None:
        extracted = comment_lines(
            "CREATE TABLE example (id uuid); -- current invariant", ".sql"
        )
        self.assertEqual([(1, "-- current invariant")], extracted)

    def test_double_dash_inside_a_sql_string_is_not_a_comment(self) -> None:
        self.assertEqual([], comment_lines("SELECT 'not--a-comment';", ".sql"))


class HistoryCommentTests(unittest.TestCase):
    def matches(self, line: str) -> bool:
        return any(pattern.search(line) for pattern, _ in HISTORY_COMMENT_PATTERNS)

    def test_history_narration_is_rejected(self) -> None:
        for line in (
            "// WP-P0-001 introduces this",
            "// v1.2 changed this behaviour",
            "// remove later once the module exists",
            "// remove this in a future work package",
            "// for now we accept the default",
            "// temporary workaround until the adapter lands",
            "// review finding from the controller",
            "// legacy compatibility with the old path",
            "// historically this case was fragile",
            "// previously this used a fallback",
            "// former implementation used the old path",
            "// review iteration introduced this branch",
            "// a work package that introduces an SDK updates this list",
            "// rework finding changed the assertion",
            "// later work packages record verified evidence",
            "// availability remains unknown in this stage",
            "-- a future credential-verification capability relaxes this check",
        ):
            with self.subTest(line=line):
                self.assertTrue(self.matches(line))

    def test_functional_wording_is_accepted(self) -> None:
        for line in (
            "// Allows validation when the protected layer has no classes.",
            "// When matching classes exist, the dependency rule is fully enforced.",
            "// Readiness includes the datasource; liveness deliberately does not.",
            "// The correlation identifier is regenerated when the inbound value is invalid.",
            "// Passwords use an alphanumeric alphabet so no escaping is required.",
            "// A credential with an expiry timestamp in the future remains active.",
            "-- The interval excludes its upper bound during overlap checks.",
        ):
            with self.subTest(line=line):
                self.assertFalse(self.matches(line))


class RetiredArtefactTests(unittest.TestCase):
    def test_every_retired_marker_states_a_reason(self) -> None:
        for marker, reason in RETIRED_ARTEFACTS:
            with self.subTest(marker=marker):
                self.assertTrue(marker)
                self.assertGreater(len(reason), 20)

    def test_known_compromises_are_listed(self) -> None:
        markers = {marker for marker, _ in RETIRED_ARTEFACTS}
        self.assertIn("ops.health_probe", markers)
        self.assertIn("Type.OPEN", markers)
        self.assertIn("failOnEmptyShould", markers)
        self.assertIn("spring-boot-starter-data-jdbc", markers)


class DeclaredDependencyTests(unittest.TestCase):
    """A banned coordinate reads differently depending on the element around it."""

    NAMESPACE = 'xmlns="http://maven.apache.org/POM/4.0.0"'

    def pom(self, body: str) -> str:
        return f'<project {self.NAMESPACE}>{body}</project>'

    def test_a_declared_dependency_is_reported(self) -> None:
        document = self.pom(
            "<dependencies><dependency>"
            "<groupId>org.springframework.boot</groupId>"
            "<artifactId>spring-boot-starter-jdbc</artifactId>"
            "</dependency></dependencies>"
        )
        self.assertEqual(
            [("org.springframework.boot", "spring-boot-starter-jdbc")],
            declared_dependency_artifacts(document),
        )

    def test_an_enforcer_exclusion_is_not_a_dependency(self) -> None:
        document = self.pom(
            "<build><plugins><plugin><configuration><rules><bannedDependencies><excludes>"
            "<exclude>org.springframework.boot:spring-boot-starter-data-jpa</exclude>"
            "</excludes></bannedDependencies></rules></configuration></plugin></plugins></build>"
        )
        self.assertEqual([], declared_dependency_artifacts(document))

    def test_a_document_without_a_namespace_is_parsed(self) -> None:
        document = (
            "<project><dependencies><dependency>"
            "<groupId>org.postgresql</groupId><artifactId>postgresql</artifactId>"
            "</dependency></dependencies></project>"
        )
        self.assertEqual([("org.postgresql", "postgresql")], declared_dependency_artifacts(document))

    def test_every_forbidden_dependency_states_a_reason(self) -> None:
        for artifact, reason in FORBIDDEN_BACKEND_DEPENDENCIES:
            with self.subTest(artifact=artifact):
                self.assertNotIn(":", artifact)
                self.assertGreater(len(reason), 10)


class ProductionNamingTests(unittest.TestCase):
    def test_required_names_are_exact(self) -> None:
        self.assertEqual("com.mimococo.marketops", REQUIRED_NAMES["java_root_package"])
        self.assertEqual("marketops-server", REQUIRED_NAMES["backend_application"])
        self.assertEqual("marketops-console", REQUIRED_NAMES["frontend_package"])
        self.assertEqual("marketops_migration", REQUIRED_NAMES["migration_role"])
        self.assertEqual("marketops_app", REQUIRED_NAMES["application_role"])
        self.assertEqual("MARKETOPS_", REQUIRED_NAMES["backend_env_prefix"])
        self.assertEqual("VITE_MARKETOPS_", REQUIRED_NAMES["frontend_env_prefix"])

    def test_scaffold_terms_do_not_overlap_production_names(self) -> None:
        for term in SCAFFOLD_TERMS + IDENTIFIER_SCAFFOLD_TERMS:
            for name in REQUIRED_NAMES.values():
                with self.subTest(term=term, name=name):
                    self.assertNotIn(term, name.lower())

    def test_identifier_context_extracts_a_declared_name(self) -> None:
        match = IDENTIFIER_CONTEXT.search('<artifactId>demo-service</artifactId>')
        self.assertIsNotNone(match)
        self.assertTrue(match.group(1).lower().startswith("demo"))

    def test_prose_use_of_example_is_not_an_identifier(self) -> None:
        self.assertIsNone(IDENTIFIER_CONTEXT.search("This is for example only"))


if __name__ == "__main__":
    unittest.main()
