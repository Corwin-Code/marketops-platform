#!/usr/bin/env python3
"""Enforce the three global production-readiness rules for this repository.

The rules exist because a foundation that ships an unfinished path, a
history-narrating comment, or a scaffold identifier teaches every later work
package to do the same.

============  ===========================================================
Rule          Question it answers
============  ===========================================================
TC-GLOBAL-001 Does any compromise, placeholder, or superseded parallel
              implementation remain in a production path?
TC-GLOBAL-002 Does any comment describe project history or a future cleanup
              instead of current behaviour?
TC-GLOBAL-003 Does any production identifier use a placeholder or scaffold
              name instead of the agreed production name?
============  ===========================================================

Scope is explicit rather than repository-wide. Governance records, work items,
requirements, and phase evidence legitimately describe history and superseded
decisions, so they are excluded from the comment and naming rules while
remaining subject to the secret and placeholder rules that apply everywhere.
Test sources may use deliberately invalid names, because architecture fixtures
have to violate the rules they protect.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

BACKEND = "backend/marketops-server"
FRONTEND = "frontend/marketops-console"

# Directories whose contents are production behaviour.
PRODUCTION_ROOTS = (
    f"{BACKEND}/src/main",
    f"{FRONTEND}/src",
    "infra",
    "scripts",
    ".github/workflows",
)

# Directories whose contents are tests or deliberately invalid fixtures.
TEST_ROOTS = (
    f"{BACKEND}/src/test",
    f"{FRONTEND}/src/__tests__",
    f"{FRONTEND}/tests",
    "tests",
)

# Documents that describe the current system and must not narrate history.
CANONICAL_DOC_PATHS = (
    "docs/02-architecture/designs",
    "docs/06-runbooks",
    "README.md",
)

# Documents whose purpose is to record history, decisions, or evidence.
HISTORICAL_DOC_ROOTS = (
    "docs/00-governance",
    "docs/01-requirements",
    "docs/02-architecture/adr",
    "docs/03-work-items",
    "docs/07-phase-evidence",
    "docs/08-handoffs",
    "CHANGELOG.md",
)

SOURCE_SUFFIXES = {".java", ".ts", ".tsx", ".js", ".mjs", ".py", ".sh", ".sql", ".yml", ".yaml"}
COMMENT_SUFFIXES = {".java", ".ts", ".tsx", ".js", ".mjs", ".sql"}

SKIP_DIR_NAMES = {
    ".git", "node_modules", "target", "dist", "coverage", ".vite",
    "__pycache__", ".venv", ".mvn",
}

# --------------------------------------------------------------------------
# TC-GLOBAL-001 — compromise retirement
# --------------------------------------------------------------------------

# A marker counts only in annotation form. A mention wrapped in backticks or
# quotes is documentation or test data describing the rule, not unfinished work,
# so a plain word search would report the rule's own definition forever and train
# reviewers to ignore the result.
UNRESOLVED_MARKERS = re.compile(r"""(?<![`'"\w])(?:TODO|FIXME|HACK|XXX)(?![`'"\w])""")

# The approved migration set. A migration appears here in the same change that
# adds the file; an unlisted migration file fails the check in both directions.
APPROVED_MIGRATIONS = (
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
)

DEFERRED_EVIDENCE_REGISTER = (
    "docs/07-phase-evidence/SLICE-V1-001/deferred-evidence-register.json"
)
DEFERRED_ACCEPTANCE_IDS = (
    "S1-AC-001", "S1-AC-003", "S1-AC-005", "S1-AC-006", "S1-AC-007",
    "S1-AC-008", "S1-AC-009", "S1-AC-010", "S1-AC-012", "S1-AC-023",
    "S1-AC-025", "S1-AC-026", "S1-AC-031", "S1-AC-032", "S1-AC-033",
    "S1-AC-038", "S1-AC-040",
)
DEFERRED_CURRENT_STATUS = "OWNER_ACCEPTED_DEFERRED_TO_RELEASE_V1_001"
DEFERRED_ENGINEERING_STATUS = "ENGINEERING_VERIFIED_RELEASE_EVIDENCE_PENDING"
DEFERRED_RELEASE_STATES = {
    DEFERRED_CURRENT_STATUS,
    "GATE_EV_DEFERRED_TO_RELEASE_V1_001",
    "OWNER_RELEASE_EVIDENCE_DEFERRED_TO_RELEASE_V1_001",
}
PROHIBITED_DEFERRED_STATUSES = {
    "VERIFIED", "EXECUTABLY_VERIFIED", "NOT_APPLICABLE", "REAL_PROVIDER_PROVEN",
    "PRODUCTION_READY",
}
DEFERRED_AUTHORITY_HASHES = {
    "amendmentSha256": "92fdd8d67b327fbd2288ba99290b5b59f2797106c4b691ce2bff22bb80198b93",
    "ownerAcceptanceSha256": "f28ad2395e22a7dd996ace6db4883f35e408bb4ea24de61e777e03b8616d9923",
}

# An applied migration is immutable. The pin covers the earliest migration,
# which every environment has applied; editing it in place would desynchronise
# Flyway history from the repository.
FOUNDATION_MIGRATION_SHA256 = (
    "7c7f34ba3a3746883e236a5a4e6eb0efc87e58e3b33f895d7f1d71c369d0eb0d"
)

# Metadata migrations create structure and seed reference rows. No migration in
# this stage may destroy schemas, tables or rows.
DESTRUCTIVE_MIGRATION_STATEMENT = re.compile(
    r"^\s*(?:DROP\s+(?:SCHEMA|TABLE|INDEX|ROLE)|TRUNCATE\b|DELETE\s+FROM)",
    re.IGNORECASE,
)


def approved_index_replacement(path: Path, text: str, line: str) -> bool:
    """Allow one exact non-data index replacement whose old shape is unsafe.

    The old active-grant uniqueness key predates Product scope. Keeping it would
    collapse every Product grant for one user/action into one row. This narrow
    exception requires the exact V0034 file, exact old index, and the complete
    replacement key; it does not permit a table/row/schema drop or arbitrary
    index retirement.
    """
    return (
        path.name == "V0034__close_availability_deep_review_findings.sql"
        and line.strip().upper() == "DROP INDEX IAM.USER_SCOPE_GRANT_ACTIVE_UQ;"
        and "CREATE UNIQUE INDEX user_scope_grant_active_uq" in text
        and "warehouse_ref_id,\n        product_variant_ref_id)" in text
        and "NULLS NOT DISTINCT\n    WHERE status = 'ACTIVE';" in text
    )

# The files that define these rules necessarily contain every pattern the rules
# search for. They are the rule definition, not a subject of it. Both exclusion
# lists in this validator are exact path lists asserted by tests so they cannot
# widen silently; every other file in the repository — including all
# documentation — is scanned.
RULE_DEFINITION_PATHS = (
    "scripts/validate_production_readiness.py",
    "tests/test_validate_production_readiness.py",
)

# Controller-approved design artifacts pinned byte-exact by SHA-256 in
# validate_governance.py. Their integrity contract is the hash: any edit —
# including one that would satisfy a content scan — breaks the approval pin,
# so the content scans do not apply to them.
HASH_PINNED_DOC_PATHS = (
    "docs/02-architecture/designs/"
    "WP-P0-002-organization-store-warehouse-credential-metadata-design.md",
)

# Retired designs. Each entry states what must not reappear and why.
RETIRED_ARTEFACTS = (
    ("ops.health_probe", "a probe table was replaced by the datasource health indicator"),
    ("health_probe", "a probe table was replaced by the datasource health indicator"),
    ("spring-boot-starter-data-jdbc", "the plain JDBC starter is the approved dependency"),
    ("Type.OPEN", "modules are closed; an open module disables encapsulation and cycle detection"),
    ("failOnEmptyShould", "empty-subject handling is per rule, never a global override"),
    ("com.<company>", "the Java root package is com.mimococo.marketops"),
    ("<company>", "the Java root package is com.mimococo.marketops"),
)

# Files that may never exist, keyed by the design they belonged to.
RETIRED_PATHS = (
    ("archunit.properties", "empty-subject handling is per rule, never a global override"),
    (
        "docs/00-governance/CURRENT_STATE_PROPOSAL_WP-P0-001.md",
        "CURRENT_STATE.md is the only canonical runtime state source",
    ),
    (
        "docs/02-architecture/designs/WP-P0-001-foundation-design-v1.1.md",
        "the canonical design carries no review-version suffix",
    ),
    (
        "docs/02-architecture/designs/WP-P0-001-foundation-design-v1.2.md",
        "the canonical design carries no review-version suffix",
    ),
    (
        "docs/02-architecture/designs/WP-P0-001-foundation-design-v1.3.md",
        "the canonical design carries no review-version suffix",
    ),
)

# Dependencies that must not appear in the backend build.
FORBIDDEN_BACKEND_DEPENDENCIES = (
    ("spring-boot-starter-data-jdbc", "use spring-boot-starter-jdbc"),
    ("spring-boot-starter-data-jpa", "JPA is out of scope for this foundation"),
    ("hibernate-core", "JPA is out of scope for this foundation"),
)

# --------------------------------------------------------------------------
# TC-GLOBAL-002 — functional comments
# --------------------------------------------------------------------------

HISTORY_COMMENT_PATTERNS = (
    (re.compile(r"\bWP-P0-00\d\b"), "work package narration"),
    (re.compile(r"\bwork packages?\b", re.I), "work package narration"),
    (
        re.compile(r"\b(?:in|at|during) (?:this|the) (?:stage|phase)\b", re.I),
        "project-stage narration",
    ),
    (
        re.compile(
            r"\bfuture (?:[a-z0-9-]+ ){0,2}"
            r"(?:capability|implementation|migration|runtime|stage|phase)\b",
            re.I,
        ),
        "future implementation narration",
    ),
    (re.compile(r"(?<![A-Za-z0-9])C(?:[1-9]|10)\b(?=[^A-Za-z0-9]*(?:commit|stage|step|later|phase))", re.I), "commit-stage narration"),
    (re.compile(r"\bv1\.[0-3]\b"), "review-revision narration"),
    (re.compile(r"\bremove (?:this )?(?:in a )?(?:future|later)\b", re.I), "future cleanup instruction"),
    (re.compile(r"\bremove later\b", re.I), "future cleanup instruction"),
    (re.compile(r"\bfor now\b", re.I), "provisional wording"),
    (re.compile(r"\btemporary (?:compromise|workaround|shim)\b", re.I), "compromise wording"),
    (re.compile(r"\breview finding\b", re.I), "review narration"),
    (re.compile(r"\bcontroller requested\b", re.I), "review narration"),
    (re.compile(r"\blegacy compatibility\b", re.I), "compatibility narration"),
    (re.compile(r"\bhistorically\b", re.I), "history narration"),
    (re.compile(r"\bpreviously\b", re.I), "history narration"),
    (re.compile(r"\bformer implementation\b", re.I), "history narration"),
    (re.compile(r"\bold path\b", re.I), "history narration"),
    (re.compile(r"\breview iteration\b", re.I), "review narration"),
    (re.compile(r"\ba work package that introduces\b", re.I), "work package narration"),
    (
        re.compile(r"\b(?:controller|rework|revision) (?:finding|request|iteration|stage)\b", re.I),
        "review narration",
    ),
)

# --------------------------------------------------------------------------
# TC-GLOBAL-003 — production naming
# --------------------------------------------------------------------------

REQUIRED_NAMES = {
    "product_display_name": "MarketOps Russia",
    "repository": "marketops-platform",
    "backend_application": "marketops-server",
    "frontend_package": "marketops-console",
    "java_root_package": "com.mimococo.marketops",
    "database": "marketops",
    "migration_role": "marketops_migration",
    "application_role": "marketops_app",
    "backend_env_prefix": "MARKETOPS_",
    "frontend_env_prefix": "VITE_MARKETOPS_",
}

SCAFFOLD_TERMS = (
    "hello-world", "helloworld", "hello world",
    "foo", "bar", "prototype",
)

# Scaffold terms that are only rejected when they name an identifier, because the
# same words appear legitimately in prose such as "for example" or "a sample of".
IDENTIFIER_SCAFFOLD_TERMS = ("example", "demo", "sample", "temp", "temporary")

IDENTIFIER_CONTEXT = re.compile(
    r"(?:package|class|interface|enum|record|artifactId|groupId|name|id)\s*[:=> ]\s*"
    r"[\"'<]?([A-Za-z0-9_.\-]*)",
    re.I,
)

ACTION_REFERENCE = re.compile(
    r"^\s*(?:-\s*)?uses:\s*[^@\s]+@(?P<ref>[^\s#]+)"
    r"(?:\s+#\s*(?P<version>v\d+(?:\.\d+)*))?\s*$"
)
IMMUTABLE_ACTION_REF = re.compile(r"^[0-9a-f]{40}$")
PATH_RESTRICTION = re.compile(
    r"guard-repo-path|path (?:contains|without) (?:whitespace|spaces?|a single quote)|"
    r"move the clone to a path without",
    re.I,
)
PENDING_EVIDENCE = re.compile(r"PENDING_(?:LOCAL|CODEX_GITHUB)_EXECUTION")
BASE_HIKARI_AUTOCOMMIT_TOKENS = (
    "  datasource:",
    "    hikari:",
    "      auto-commit: true",
)
UNSAFE_THROWABLE_LOGGING = re.compile(
    r"\b(?:log|logger)\.(?:trace|debug|info|warn|error)\s*\([^;]*,\s*"
    r"(?:exception|throwable|error|failure)\s*\)|\.setCause\s*\(",
    re.S,
)

ARCHITECTURE_RULE_TOKENS = (
    "moduleInternalsAreNotAccessedFromOtherModules",
    "modulesAreFreeOfCycles",
    "theSharedModuleDependsOnNoBusinessModule",
    "domainDoesNotDependOutward",
    "applicationAndPortDoNotDependOutward",
    "vendorSdkTypesStayInsidePlatformAdapters",
    "vendorSdkTypesDoNotAppearInDomainOrModuleApiSignatures",
    "marketplaceintegration.adapter.<platform>",
    "isWithin(item.getPackageName(), owningModulePackage)",
    "isNamedInterface(item)",
    "item.getPackage().isAnnotatedWith(NamedInterface.class)",
    "segment.equals(INTERNAL_SEGMENT)",
)

STRUCTURED_LOGGING_TOKENS = (
    "console: ecs",
    "exclude: tags",
    "include: false",
    "customizer: com.mimococo.marketops.shared.internal.logging.EcsCorrelationIdJsonMembersCustomizer",
    "application: ${spring.application.name}",
    "environment: ${marketops.environment}",
    "buildVersion: ${spring.application.version}",
)

ECS_CORRELATION_CUSTOMIZER_TOKENS = (
    "StructuredLoggingJsonMembersCustomizer<ILoggingEvent>",
    "members.add(CorrelationId.LOG_CONTEXT_KEY, this::correlationId)",
    "event.getMDCPropertyMap().get(CorrelationId.LOG_CONTEXT_KEY)",
    'NO_REQUEST = "none"',
    "!CorrelationId.LOG_CONTEXT_KEY.equals(pair.key)",
)

LOCAL_LOGGING_TOKENS = (
    "application=${spring.application.name}",
    "environment=${marketops.environment}",
    "buildVersion=${spring.application.version}",
    "correlationId=%X{correlationId:-none}",
    "%kvp",
)

COMPLETION_STATE_TOKENS = (
    "lifecycle_state: EXECUTING_V1",
    "active_delivery_slice: SLICE-V1-002",
    "active_slice_contract: docs/03-work-items/"
    "SLICE-V1-002-stockout-availability-risk-and-accountable-response.md",
    "active_slice_contract_sha256: "
    "d89ea296d0ff854c7d57895b448f9467a22106881d26de4c62a0e8629600556e",
    "active_slice_contract_git_blob_sha1: 1caa50f1b33011f7d226c83654835401c00bde1e",
    "active_slice_acceptance_evidence_sha256: "
    "4e243c85412c549975ef70ee46bb09502a3157c0d4bb6a1b2679b7745b96538e",
    "active_slice_amendment: NONE_ACCEPTED",
    "active_slice_contract_authorization_condition: EXACT_HASH_INDEPENDENTLY_REVIEWED_AND_OWNER_AUTHORIZED_ON_PROTECTED_MAIN",
    "active_gate: SLICE_V1_002_FULL_SCOPE_IMPLEMENTATION",
    "authorization: FULL_SCOPE_IMPLEMENTATION",
    "slice_v1_002_implementation_state: ROOT_CAUSE_REWORK_VERIFIED_LOCAL",
    "slice_v1_002_branch: fix/SLICE-V1-002-root-cause-rework-r1",
    "slice_v1_002_reviewed_source_head: c5d896a4ca01ecdc6d4add85fb4fd2e33ba8e4c6",
    "slice_v1_002_reviewed_source_tree: c94341232b5fa67b5c40a1e6be121a7696e748c4",
    "slice_v1_002_frozen_findings_sha256: "
    "60589cfa9303d17e71910e085fd18f1d68b87dd9e3b56a99bf6f799879ebcf94",
    "slice_v1_002_engineering_findings_addressed: "
    "18_OF_18_PENDING_INDEPENDENT_CLOSURE_VERIFICATION",
    "slice_v1_002_controller_verdict: NOT_CLAIMED",
    "slice_v1_002_owner_formal_closure: NOT_CLAIMED",
    "slice_v1_002_remote_publication: DRAFT_PR_26_OPEN_REQUIRED_CHECKS_PASS",
    "slice_v1_002_draft_pr: 26",
    "slice_v1_002_draft_pr_url: https://github.com/Corwin-Code/marketops-platform/pull/26",
    "slice_v1_002_controlled_write_target: NONE_IN_THIS_SLICE",
    "slice_v1_002_real_provider_calls: NONE",
    "slice_v1_002_r1_finding_closure: docs/07-phase-evidence/SLICE-V1-002/r1-finding-closure.json",
    "slice_v1_002_r1_final_handoff: docs/07-phase-evidence/SLICE-V1-002/r1-final-handoff.md",
    "slice_v1_001_controller_final_gate: PASS_R2_ENGINEERING_FINAL_GATE",
    "slice_v1_001_actual_squash_commit: d562b81f4f0271aa33a53b21ccaffc88b5610c0c",
    "slice_v1_001_actual_squash_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b",
    "slice_v1_001_actual_squash_sole_parent: db92cf2f8bd818f36dd8f5aa17b8589c4140b669",
    "slice_v1_001_closure_claim: ENGINEERING_IMPLEMENTATION_CLOSED",
    "slice_v1_001_production_readiness: DEFERRED_TO_RELEASE_V1_001",
    "slice_v1_001_owner_formal_closure: HUMAN_OWNER_ACCEPTED",
    "slice_v1_001_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS",
    "slice_v1_001_controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING",
    "slice_v1_001_controller_bookkeeping_comment: 5469802650",
    "slice_v1_001_snapshot_git_blob_sha1: e26359ec216c04319a4bf1e7126906eb204593d2",
    "slice_v1_001_snapshot_sha256: 5abce67327673dc0248f11ece1f31cd11d1ec7c0e69a1e84823ddedf30aab2e3",
    "slice_v1_001_owner_acceptance_comment: 5469935477",
    "slice_v1_001_owner_acceptance_evidence_sha256: 50c171f24037cf36ccb4724288a7b82831b7dd008985f9b594ef2020c1c5ef33",
    "next_authorized_actor: GPT-5.6 Pro Controller",
    "next_action: CONTROLLER_SLICE_V1_002_FINAL_CLOSURE_VERIFICATION",
    "production_write_enabled: false",
    "bounded_real_write_verification_authorization: NONE",
    "bounded_real_write_verification_gate: REQUIRED_BEFORE_FIRST_REAL_WRITE",
    "ozon_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE",
    "wildberries_price_write: DISABLED_PENDING_VERIFIED_CAPABILITY_AND_RELEASE_GATE",
)

COMPLETED_WORK_PACKAGE_TOKENS = (
    "| Status | COMPLETED |",
    "| Historic design verdict | APPROVED_FOR_IMPLEMENTATION |",
    "| Current execution authorization | CLOSED |",
    "| Implementation result | VERIFIED |",
)

POLLING_CONTRACT_TOKENS = (
    "HEALTH_REFRESH_INTERVAL_MS",
    "HEALTH_RETRY_DELAYS_MS",
    "failedAttempts",
    "retryDelaysMs",
    "inFlight",
    "setTimeout",
    "lifecycle.abort()",
    "manualRefresh.current",
)

BUILT_PREVIEW_COMMAND = (
    "npm run build && npm run preview -- --host 127.0.0.1 --port 4173 --strictPort"
)

BACKLOG_HEADER = ("ID", "Title", "Status", "Dependencies", "Core source requirements")
BACKLOG_ALLOWED_STATES = {"DRAFT", "READY_FOR_DESIGN", "IMPLEMENTING", "COMPLETED"}
SOURCE_HEAD_ENVIRONMENT_VARIABLE = "MARKETOPS_SOURCE_HEAD_SHA"
SOURCE_HEAD_EXPRESSION = "${{ github.event.pull_request.head.sha || github.sha }}"

PR_SECURITY_EVIDENCE_TOKENS = (
    "source_head_sha",
    "tested_merge_sha",
    "PR review threads",
    "CodeQL PR annotations",
    "Code scanning",
    "Dependabot",
    "Secret scanning",
)


@dataclass
class Violation:
    rule: str
    path: str
    line: int
    detail: str


@dataclass
class Report:
    violations: list[Violation] = field(default_factory=list)
    inspected_files: int = 0

    def add(self, rule: str, path: Path | str, line: int, detail: str) -> None:
        relative = path if isinstance(path, str) else str(path.relative_to(ROOT))
        self.violations.append(Violation(rule, relative, line, detail))

    def for_rule(self, rule: str) -> list[Violation]:
        return [violation for violation in self.violations if violation.rule == rule]


def is_rule_definition(path: Path) -> bool:
    """Report whether ``path`` defines the rules rather than being subject to them."""
    return str(path.relative_to(ROOT)) in RULE_DEFINITION_PATHS


def under(path: Path, roots: tuple[str, ...]) -> bool:
    relative = str(path.relative_to(ROOT))
    return any(relative == root or relative.startswith(root + "/") for root in roots)


def iter_files() -> list[Path]:
    files: list[Path] = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file():
            continue
        if any(part in SKIP_DIR_NAMES for part in path.relative_to(ROOT).parts):
            continue
        files.append(path)
    return files


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None


def matching_lines(text: str, pattern: re.Pattern[str]) -> list[tuple[int, str]]:
    """Return every line matched by a repository-contract pattern."""
    return [
        (number, line)
        for number, line in enumerate(text.splitlines(), start=1)
        if pattern.search(line)
    ]


def contract_token_violations(
    text: str,
    required: tuple[str, ...] = (),
    prohibited: tuple[str, ...] = (),
) -> list[str]:
    """Return absent requirements and present retired contract markers."""
    violations = [f"required contract is absent: {token}" for token in required if token not in text]
    violations.extend(
        f"prohibited contract is present: {token}" for token in prohibited if token in text
    )
    return violations


def base_environment_identity_violations(text: str) -> list[int]:
    """Return base-config lines that commit a marketops environment fallback."""
    violations: list[int] = []
    in_marketops = False
    for number, line in enumerate(text.splitlines(), start=1):
        if line and not line[0].isspace() and not line.lstrip().startswith("#"):
            in_marketops = line.strip() == "marketops:"
            continue
        if in_marketops and re.match(r"^\s{2}environment\s*:", line):
            violations.append(number)
    return violations


def unsafe_throwable_logging_violations(text: str) -> list[int]:
    """Return source lines that hand an exception object to a logger."""
    return [text.count("\n", 0, match.start()) + 1 for match in UNSAFE_THROWABLE_LOGGING.finditer(text)]


def browser_acceptance_contract_violations(config_text: str, scenario_text: str) -> list[str]:
    """Check that browser acceptance covers the built artifact and full recovery."""
    violations = contract_token_violations(
        config_text,
        required=(BUILT_PREVIEW_COMMAND, "baseURL: 'http://127.0.0.1:4173'"),
        prohibited=("npm run dev", "127.0.0.1:5173"),
    )
    violations.extend(
        contract_token_violations(
            scenario_text,
            required=(
                "compose('stop', 'postgres')",
                "waitForDatabaseStatus(page, 'DOWN')",
                "compose('up', '-d', '--wait', 'postgres')",
                "waitForDatabaseStatus(page, 'UP')",
                "expectCorrelation(recovered.response, recovered.body)",
            ),
        )
    )
    if len(re.findall(r"'data-state',\s*'ready'", scenario_text)) < 2:
        violations.append("initial and recovered Ready UI assertions are both required")
    return violations


def markdown_table_cells(line: str) -> list[str] | None:
    """Return the cells of one structurally delimited Markdown table row."""
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return None
    return [cell.strip() for cell in stripped[1:-1].split("|")]


def backlog_completion_contract_violations(text: str) -> list[str]:
    """Require one completed WP-P0-001 row in the canonical backlog table."""
    lines = text.splitlines()
    tables: list[list[list[str]]] = []
    for index, line in enumerate(lines):
        if tuple(markdown_table_cells(line) or ()) != BACKLOG_HEADER:
            continue
        if index + 1 >= len(lines):
            return ["Phase 0 backlog table has no separator row"]
        separator = markdown_table_cells(lines[index + 1])
        if separator is None or len(separator) != len(BACKLOG_HEADER):
            return ["Phase 0 backlog table separator is malformed"]
        if any(re.fullmatch(r":?-{3,}:?", cell) is None for cell in separator):
            return ["Phase 0 backlog table separator is malformed"]
        rows: list[list[str]] = []
        for row_line in lines[index + 2:]:
            cells = markdown_table_cells(row_line)
            if cells is None:
                break
            if len(cells) != len(BACKLOG_HEADER):
                return ["Phase 0 backlog table row width is malformed"]
            rows.append(cells)
        tables.append(rows)
    if len(tables) != 1:
        return ["Phase 0 backlog must contain exactly one canonical table"]

    violations: list[str] = []
    for row in tables[0]:
        if row[2] not in BACKLOG_ALLOWED_STATES:
            violations.append(f"backlog {row[0]} has unknown Status: {row[2]}")
    wp_rows = [row for row in tables[0] if row[0] == "WP-P0-001"]
    if len(wp_rows) != 1:
        violations.append("Phase 0 backlog must contain exactly one WP-P0-001 row")
    elif wp_rows[0][2] != "COMPLETED":
        violations.append("backlog WP-P0-001 Status must be COMPLETED")
    return violations


def workflow_job_body(text: str, job: str) -> str | None:
    """Return one top-level GitHub Actions job body without parsing run scripts."""
    match = re.search(
        rf"(?ms)^  {re.escape(job)}:\s*$\n(?P<body>.*?)(?=^  [A-Za-z0-9_-]+:\s*$|\Z)",
        text,
    )
    return match.group("body") if match else None


def browser_source_identity_contract_violations(
    workflow_text: str,
    config_text: str,
    scenario_text: str,
    resolver_text: str,
) -> list[str]:
    """Separate the authored source identity from a temporary merge checkout."""
    violations: list[str] = []
    frontend_test = workflow_job_body(workflow_text, "frontend-test")
    expected_assignment = f"{SOURCE_HEAD_ENVIRONMENT_VARIABLE}: {SOURCE_HEAD_EXPRESSION}"
    if frontend_test is None or expected_assignment not in frontend_test:
        violations.append(
            "frontend-test must pass the pull-request authored source identity explicitly"
        )

    shared_resolution = "const sourceHead = resolveBrowserSourceIdentity(repositoryRoot);"
    for surface, text in (("Playwright configuration", config_text), ("browser scenario", scenario_text)):
        if shared_resolution not in text:
            violations.append(f"{surface} must use the shared source identity resolver")
        if "execFileSync('git', ['rev-parse', 'HEAD']" in text:
            violations.append(f"{surface} must not treat checkout HEAD as authored source")
    if "MARKETOPS_BUILD_COMMIT: sourceHead" not in config_text:
        violations.append("browser build must consume the shared source identity")
    if "`Console ${frontendVersion} (${sourceHead})`" not in scenario_text:
        violations.append("browser assertion must consume the shared source identity")

    for token in (
        "if (isContinuousIntegration(environment))",
        "CI browser verification requires ${SOURCE_HEAD_ENVIRONMENT_VARIABLE}",
        "repositoryHeadReader(repositoryRoot)",
        "FULL_SOURCE_SHA",
    ):
        if token not in resolver_text:
            violations.append(f"source identity resolver contract is absent: {token}")
    return violations


def pr_security_evidence_violations(text: str, expected_source_head: str) -> list[str]:
    """Check that PR security evidence covers every surface on the tested source Head."""
    violations = contract_token_violations(text, required=PR_SECURITY_EVIDENCE_TOKENS)
    if expected_source_head not in text:
        violations.append(f"security evidence is stale for source Head {expected_source_head}")
    return violations


def action_reference_violations(text: str) -> list[tuple[int, str]]:
    """Return mutable or unlabelled external action references."""
    violations: list[tuple[int, str]] = []
    for number, line in enumerate(text.splitlines(), start=1):
        match = ACTION_REFERENCE.match(line)
        if match is None:
            continue
        reference = match.group("ref")
        version = match.group("version")
        if IMMUTABLE_ACTION_REF.fullmatch(reference) is None or version is None:
            violations.append((number, line.strip()))
    return violations


def runner_reference_violations(text: str) -> list[tuple[int, str]]:
    """Return workflow runner declarations that do not pin Ubuntu 24.04."""
    return [
        (number, line.strip())
        for number, line in enumerate(text.splitlines(), start=1)
        if "runs-on:" in line and "ubuntu-24.04" not in line
    ]


def require_tokens(report: Report, rule: str, path: Path, tokens: tuple[str, ...]) -> None:
    """Report each required contract token absent from a text file."""
    text = read_text(path)
    if text is None:
        report.add(rule, path, 0, "required contract file is missing or unreadable")
        return
    for detail in contract_token_violations(text, required=tokens):
        report.add(rule, path, 0, detail)


def reject_tokens(report: Report, rule: str, path: Path, tokens: tuple[str, ...]) -> None:
    """Report each prohibited contract token present in a text file."""
    text = read_text(path)
    if text is None:
        report.add(rule, path, 0, "prohibited-contract file is missing or unreadable")
        return
    for detail in contract_token_violations(text, prohibited=tokens):
        report.add(rule, path, 0, detail)


def check_repository_contracts(report: Report) -> None:
    """Enforce the foundation capabilities whose absence previously looked green."""
    rule = "TC-GLOBAL-001"

    required_paths = (
        f"{BACKEND}/.mvn/wrapper/maven-wrapper.jar",
        f"{FRONTEND}/package-lock.json",
        f"{FRONTEND}/playwright.config.ts",
        f"{FRONTEND}/tests/browser/health-shell.spec.ts",
    )
    for relative in required_paths:
        if not (ROOT / relative).is_file():
            report.add(rule, relative, 0, "required foundation artefact is absent")

    for relative in ("Makefile", "scripts/dev_doctor.py", "scripts/fresh_clone_check.sh"):
        path = ROOT / relative
        text = read_text(path) or ""
        for number, line in matching_lines(text, PATH_RESTRICTION):
            report.add(rule, path, number, f"repository path restriction: {line.strip()[:100]}")

    require_tokens(
        report,
        rule,
        ROOT / "Makefile",
        (
            "bootstrap: preserving the complete existing ignored configuration",
            "local configuration is incomplete",
        ),
    )

    workflows = ROOT / ".github" / "workflows"
    if workflows.exists():
        for path in sorted(workflows.glob("*.yml")) + sorted(workflows.glob("*.yaml")):
            text = read_text(path) or ""
            for number, line in runner_reference_violations(text):
                report.add(
                    rule,
                    path,
                    number,
                    f"workflow runner must be ubuntu-24.04: {line}",
                )
            for number, line in action_reference_violations(text):
                report.add(rule, path, number, f"action is not a full SHA with version comment: {line}")

    evidence = ROOT / "docs" / "07-phase-evidence" / "WP-P0-001"
    if evidence.exists():
        for path in sorted(evidence.glob("*.md")):
            text = read_text(path) or ""
            for number, line in matching_lines(text, PENDING_EVIDENCE):
                report.add(rule, path, number, f"implementation evidence remains pending: {line.strip()[:100]}")

    wrapper_properties = ROOT / BACKEND / ".mvn" / "wrapper" / "maven-wrapper.properties"
    require_tokens(
        report,
        rule,
        wrapper_properties,
        (
            "wrapperVersion=3.3.4",
            "distributionType=bin",
            "apache-maven/3.9.16/apache-maven-3.9.16-bin.zip",
            "distributionSha256Sum=",
            "maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar",
            "wrapperSha256Sum=",
        ),
    )
    require_tokens(
        report,
        rule,
        ROOT / "scripts/verify_coverage_thresholds.sh",
        ("-Djacoco.line.coverage=1.00", "--coverage.thresholds.lines=100"),
    )
    wrapper_jar = ROOT / BACKEND / ".mvn" / "wrapper" / "maven-wrapper.jar"
    properties_text = read_text(wrapper_properties) or ""
    expected_wrapper_hash = re.search(r"(?m)^wrapperSha256Sum=([0-9a-f]{64})$", properties_text)
    if wrapper_jar.is_file() and expected_wrapper_hash is not None:
        actual = hashlib.sha256(wrapper_jar.read_bytes()).hexdigest()
        if actual != expected_wrapper_hash.group(1):
            report.add(rule, wrapper_jar, 0, "wrapper JAR does not match wrapperSha256Sum")

    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "pom.xml",
        (
            "<propertyName>failsafeArgLine</propertyName>",
            "<proc>full</proc>",
            "<argLine>@{argLine} -javaagent:",
            "<argLine>@{failsafeArgLine} -javaagent:",
            "<jacoco.line.coverage>0.80</jacoco.line.coverage>",
            "<jacoco.branch.coverage>0.70</jacoco.branch.coverage>",
        ),
    )

    architecture_rules = ROOT / BACKEND / "src/test/java/com/mimococo/marketops/architecture/ArchitectureRules.java"
    require_tokens(report, rule, architecture_rules, ARCHITECTURE_RULE_TOKENS)
    architecture_text = read_text(architecture_rules) or ""
    for method in (
        "moduleInternalsAreNotAccessedFromOtherModules",
        "theSharedModuleDependsOnNoBusinessModule",
        "vendorSdkTypesStayInsidePlatformAdapters",
        "vendorSdkTypesDoNotAppearInDomainOrModuleApiSignatures",
    ):
        if re.search(rf"{method}\(String basePackage\).*?return classes\(\)", architecture_text, re.S) is None:
            report.add(rule, architecture_rules, 0, f"custom ArchUnit condition is not a positive classes() rule: {method}")

    required_architecture_fixtures = (
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/moduleinternals/alphabeta/AlphaBetaReadsAlphaInternals.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/domainoutward/orders/domain/DomainOrder.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/applicationoutward/orders/application/OrderUseCase.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/portoutward/orders/port/OrderPort.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/vendorlocation/orders/other/SdkUseOutsideAdapter.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/vendorapi/orders/OrderFacade.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/vendorapi/orders/domain/DomainOffer.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/vendorapi/namedinterface/orders/api/package-info.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/violation/vendorapi/namedinterface/orders/api/NamedInterfaceVendorApi.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/conforming/architecture/marketplaceintegration/adapter/ozon/OzonMarketplaceAdapter.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/conforming/architecture/orders/api/package-info.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/conforming/architecture/orders/api/PlatformOrderApi.java",
        f"{BACKEND}/src/test/java/com/mimococo/marketops/testfixture/conforming/internalization/beta/InternalizationConsumer.java",
    )
    for relative in required_architecture_fixtures:
        if not (ROOT / relative).is_file():
            report.add(rule, relative, 0, "required architecture sensitivity fixture is absent")

    for relative in (
        f"{BACKEND}/src/main/java/com/mimococo/marketops/shared/internal/errors/GlobalExceptionHandler.java",
        f"{BACKEND}/src/main/java/com/mimococo/marketops/adminobservability/internal/MetaStatusAssembler.java",
    ):
        path = ROOT / relative
        text = read_text(path) or ""
        for line in unsafe_throwable_logging_violations(text):
            report.add(rule, path, line, "public boundary passes a throwable object to the logger")
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/test/java/com/mimococo/marketops/shared/internal/errors/GlobalExceptionHandlerTest.java",
        ("getThrowableProxy()).isNull()", "logsContainOnlySanitizedFailureCategories"),
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/test/java/com/mimococo/marketops/adminobservability/internal/MetaStatusAssemblerTest.java",
        (
            "getThrowableProxy()).isNull()",
            "probeLogsContainOnlySanitizedFailureCategories",
            "repeatedProbeFailureIsBoundedAndRecoveryRearmsIt",
        ),
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/main/java/com/mimococo/marketops/adminobservability/internal/MetaStatusAssembler.java",
        (
            "AtomicBoolean",
            "compareAndSet(false, true)",
            'addKeyValue("correlationId"',
            'addKeyValue("exceptionClass"',
        ),
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/main/java/com/mimococo/marketops/shared/internal/errors/GlobalExceptionHandler.java",
        (
            "log.atWarn()",
            "log.atInfo()",
            "log.atError()",
            'addKeyValue("event"',
            'addKeyValue("errorCode"',
            'addKeyValue("correlationId"',
            'addKeyValue("exceptionClass"',
        ),
    )

    for path in (ROOT / BACKEND / "src/test").rglob("*.java") if (ROOT / BACKEND / "src/test").exists() else ():
        text = read_text(path) or ""
        if "org.testcontainers.containers.PostgreSQLContainer" in text:
            report.add(rule, path, 0, "Testcontainers 2.x PostgreSQL import uses the retired package")

    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/main/java/com/mimococo/marketops/shared/internal/config/CorsProperties.java",
        (
            "@ConfigurationProperties(prefix = \"marketops.web.cors\")",
            "http://127\\\\.0\\\\.0\\\\.1:(?:5173|4173)",
            "@Size(max = 2)",
            "@Pattern(regexp = LOCAL_CONSOLE_ORIGIN)",
        ),
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/main/java/com/mimococo/marketops/shared/internal/config/WebConfig.java",
        (
            'policy.setAllowedMethods(List.of("GET", "OPTIONS"))',
            'policy.setAllowedHeaders(List.of("Accept", CorrelationId.HEADER_NAME))',
            "policy.setAllowCredentials(false)",
            'source.registerCorsConfiguration("/api/v1/meta/**", policy)',
        ),
    )
    base_configuration_path = ROOT / BACKEND / "src/main/resources/application.yaml"
    require_tokens(report, rule, base_configuration_path, BASE_HIKARI_AUTOCOMMIT_TOKENS)
    base_configuration = read_text(base_configuration_path) or ""
    for line in base_environment_identity_violations(base_configuration):
        report.add(
            rule,
            base_configuration_path,
            line,
            "base configuration must not provide marketops.environment",
        )
    if "allowed-origins" in base_configuration:
        report.add(rule, base_configuration_path, 0, "base profile must enable no CORS origin")
    finite_origins = (
        "allowed-origins: http://127.0.0.1:5173,http://127.0.0.1:4173",
    )
    for profile in ("application-local.yaml", "application-ci.yaml"):
        require_tokens(
            report,
            rule,
            ROOT / BACKEND / "src/main/resources" / profile,
            finite_origins,
        )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/main/resources/application-ci.yaml",
        STRUCTURED_LOGGING_TOKENS,
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND
        / "src/main/java/com/mimococo/marketops/shared/internal/logging/"
        / "EcsCorrelationIdJsonMembersCustomizer.java",
        ECS_CORRELATION_CUSTOMIZER_TOKENS,
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/main/resources/application-local.yaml",
        LOCAL_LOGGING_TOKENS,
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/test/java/com/mimococo/marketops/LoggingContractTest.java",
        (
            "StructuredLogEncoder",
            "new ObjectMapper().readTree",
            "ciEncoderUsesTheEstablishedMdcCorrelationIdentifier",
            "ciEncoderUsesNoneWhenMdcIsAbsent",
            'containsOnlyOnce("\\\"correlationId\\\"")',
            'record.has("tags")',
            'record.has("error")',
            "localPatternProducesReadableSafeOutput",
        ),
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/test/java/com/mimococo/marketops/ApplicationEnvironmentFailClosedTest.java",
        ("unprofiledStartFailsClosed", 'hasMessageContaining("marketops.environment")'),
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/main/resources/application.yaml",
        (
            "show-details: never",
            "show-components: always",
            "org.flywaydb: WARN",
            "com.zaxxer.hikari: WARN",
            "org.postgresql: WARN",
        ),
    )
    for profile in ("application-local.yaml", "application-ci.yaml"):
        reject_tokens(
            report,
            rule,
            ROOT / BACKEND / "src/main/resources" / profile,
            ("org.flywaydb:", "com.zaxxer.hikari:", "org.postgresql:"),
        )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/test/java/com/mimococo/marketops/ApplicationSmokeIT.java",
        ("healthResponseNamesComponentsButWithholdsDetails", 'doesNotContain("\\\"details\\\""'),
    )
    require_tokens(
        report,
        rule,
        ROOT / BACKEND / "src/test/java/com/mimococo/marketops/shared/internal/config/CorsContractTest.java",
        (
            "emptyOriginListDisablesCors",
            "localConsoleOriginsAreAllowed",
            "unknownOriginIsRejected",
            "unknownConfiguredOriginFailsBindingValidation",
            "preflightContractIsFinite",
        ),
    )

    frontend_manifest = ROOT / FRONTEND / "package.json"
    require_tokens(
        report,
        rule,
        frontend_manifest,
        (
            '"@cyclonedx/cyclonedx-npm"',
            '"@playwright/test"',
            '"fsevents": false',
            '"libxmljs2": false',
            '"test:browser"',
            '"sbom"',
        ),
    )
    require_tokens(
        report,
        rule,
        ROOT / FRONTEND / "vite.config.ts",
        (
            "lines: 80",
            "branches: 70",
            "functions: 80",
            "statements: 80",
            "readFileSync(new URL('./package.json'",
            "frontendPackageVersion(packageManifest)",
        ),
    )
    frontend_config = ROOT / FRONTEND / "src/config.ts"
    config_text = read_text(frontend_config) or ""
    for retired_default in ("DEFAULT_API_BASE_URL", "DEFAULT_ENVIRONMENT"):
        if retired_default in config_text:
            report.add(rule, frontend_config, 0, f"frontend silently defaults {retired_default}")
    require_tokens(
        report,
        rule,
        frontend_config,
        ("REQUIRED_CONFIG_KEYS", "missingKeys", "ok: false"),
    )
    playwright_config = ROOT / FRONTEND / "playwright.config.ts"
    browser_scenario = ROOT / FRONTEND / "tests/browser/health-shell.spec.ts"
    source_identity_resolver = ROOT / FRONTEND / "tests/browser/sourceIdentity.ts"
    for detail in browser_acceptance_contract_violations(
        read_text(playwright_config) or "", read_text(browser_scenario) or ""
    ):
        report.add(rule, browser_scenario, 0, detail)
    for detail in browser_source_identity_contract_violations(
        read_text(ROOT / ".github/workflows/frontend.yml") or "",
        read_text(playwright_config) or "",
        read_text(browser_scenario) or "",
        read_text(source_identity_resolver) or "",
    ):
        report.add(rule, browser_scenario, 0, detail)
    require_tokens(
        report,
        rule,
        browser_scenario,
        ("frontendPackageVersion(packageManifest)",),
    )
    reject_tokens(
        report,
        rule,
        ROOT / ".github/workflows/frontend.yml",
        ("MARKETOPS_BUILD_VERSION", "github.ref_name"),
    )

    require_tokens(
        report,
        rule,
        ROOT / "docs/00-governance/CURRENT_STATE.md",
        COMPLETION_STATE_TOKENS,
    )
    require_tokens(
        report,
        rule,
        ROOT / "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md",
        COMPLETED_WORK_PACKAGE_TOKENS,
    )
    backlog_path = ROOT / "docs/03-work-items/BACKLOG-PHASE-0.md"
    for detail in backlog_completion_contract_violations(read_text(backlog_path) or ""):
        report.add(rule, backlog_path, 0, detail)
    reject_tokens(
        report,
        rule,
        ROOT / "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md",
        ("IMPLEMENTED_CANDIDATE",),
    )
    require_tokens(
        report,
        rule,
        ROOT / "docs/01-requirements/traceability.csv",
        (
            "D-03,Owner Decision",
            "WP-P0-001;WP-P0-003",
            "ACTIVE_CONTROL",
            "PostgreSQL Task/Outbox Worker",
        ),
    )
    require_tokens(
        report,
        rule,
        ROOT / FRONTEND / "src/health/HealthShell.tsx",
        POLLING_CONTRACT_TOKENS,
    )
    health_shell_text = read_text(ROOT / FRONTEND / "src/health/HealthShell.tsx") or ""
    if "setInterval" in health_shell_text:
        report.add(rule, ROOT / FRONTEND / "src/health/HealthShell.tsx", 0, "polling must self-schedule after settlement, not overlap through setInterval")
    require_tokens(
        report,
        rule,
        ROOT / FRONTEND / "src/__tests__/HealthShell.test.tsx",
        (
            "refreshes automatically at the bounded normal interval",
            "uses three bounded backoff retries",
            "never overlaps a scheduled or manual refresh",
            "aborts the active request and schedules nothing after unmount",
            "leaves one scheduler under StrictMode",
        ),
    )

    workflow_contracts = {
        ROOT / ".github/workflows/frontend.yml": (
            "actions/setup-node@820762786026740c76f36085b0efc47a31fe5020 # v7",
            "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7",
        ),
        ROOT / ".github/workflows/backend.yml": (
            "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7",
        ),
        ROOT / ".github/workflows/security.yml": (
            "actions/dependency-review-action@a1d282b36b6f3519aa1f3fc636f609c47dddb294 # v5",
        ),
    }
    for path, tokens in workflow_contracts.items():
        require_tokens(report, rule, path, tokens)
    require_tokens(
        report,
        rule,
        ROOT / "scripts/collect_supply_chain.py",
        ("frontend-sbom.json", 'sbom.get("bomFormat") != "CycloneDX"'),
    )
    require_tokens(
        report,
        rule,
        ROOT / "scripts/fresh_clone_check.sh",
        (
            "MarketOps clone's verification",
            "make env-init",
            "./mvnw -B -ntp verify",
            "npm ci",
            "npm ls --all",
            "npm run lint",
            "npm run format:check",
            "npm run typecheck",
            "npm run test:ci",
            "npm run build",
            "npm run verify:bundle",
            "scripts/verify_coverage_thresholds.sh all",
            "npm run test:browser",
            "scripts/collect_supply_chain.py",
            "scripts/verify_local_config.sh",
            "down --volumes --remove-orphans",
        ),
    )


def declared_dependency_artifacts(pom_text: str) -> list[tuple[str, str]]:
    """Return the group and artifact of every dependency a POM declares.

    The document is parsed rather than searched as text. A build file names a
    forbidden coordinate twice for opposite purposes: once if it depends on it,
    and once inside the enforcer rule that bans it. Only an element named
    ``dependency`` expresses the first, so a substring scan would report the ban
    as the very thing the ban prevents.

    :raises ElementTree.ParseError: if the document is not well-formed XML
    """
    root = ElementTree.fromstring(pom_text)
    namespace = ""
    if root.tag.startswith("{"):
        namespace = root.tag[: root.tag.index("}") + 1]
    declared: list[tuple[str, str]] = []
    for element in root.iter(f"{namespace}dependency"):
        group = (element.findtext(f"{namespace}groupId") or "").strip()
        artifact = (element.findtext(f"{namespace}artifactId") or "").strip()
        declared.append((group, artifact))
    return declared


def comment_lines(text: str, suffix: str) -> list[tuple[int, str]]:
    """Return the comment lines of a source file with their line numbers.

    Only comments are examined, so a rule name or an identifier that legitimately
    contains a matching word in executable code is never reported.
    """
    results: list[tuple[int, str]] = []
    in_block = False
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if in_block:
            results.append((number, line))
            if "*/" in line:
                in_block = False
            continue
        if line.startswith("/*"):
            results.append((number, line))
            if "*/" not in line:
                in_block = True
            continue
        if line.startswith("//"):
            results.append((number, line))
            continue
        if suffix == ".sql" and "--" in line:
            index = line.find("--")
            if line.count("'", 0, index) % 2 == 0:
                results.append((number, line[index:]))
            continue
        if "//" in line and suffix in COMMENT_SUFFIXES:
            index = line.find("//")
            if line.count('"', 0, index) % 2 == 0:
                results.append((number, line[index:]))
    return results


def check_compromise_retirement(report: Report, files: list[Path]) -> None:
    """TC-GLOBAL-001 — no unresolved or superseded implementation in production."""
    rule = "TC-GLOBAL-001"

    for retired_path, reason in RETIRED_PATHS:
        candidate = ROOT / retired_path
        if candidate.exists():
            report.add(rule, retired_path, 0, f"retired artefact present: {reason}")
        if "/" not in retired_path:
            for path in files:
                if path.name == retired_path:
                    report.add(rule, path, 0, f"retired artefact present: {reason}")

    for path in files:
        if path.suffix.lower() not in SOURCE_SUFFIXES and path.suffix.lower() != ".md":
            continue
        if is_rule_definition(path):
            continue
        text = read_text(path)
        if text is None:
            continue

        is_production = under(path, PRODUCTION_ROOTS)
        is_test = under(path, TEST_ROOTS)
        is_canonical_doc = any(
            str(path.relative_to(ROOT)) == doc or str(path.relative_to(ROOT)).startswith(doc + "/")
            for doc in CANONICAL_DOC_PATHS
        )
        if str(path.relative_to(ROOT)) in HASH_PINNED_DOC_PATHS:
            continue

        if is_production or is_test or is_canonical_doc:
            for number, line in enumerate(text.splitlines(), start=1):
                if UNRESOLVED_MARKERS.search(line):
                    report.add(rule, path, number, f"unresolved marker: {line.strip()[:80]}")

        if not is_production and not is_canonical_doc:
            continue

        for marker, reason in RETIRED_ARTEFACTS:
            for number, line in enumerate(text.splitlines(), start=1):
                if marker in line:
                    report.add(rule, path, number, f"retired construct '{marker}': {reason}")

    pom = ROOT / BACKEND / "pom.xml"
    if pom.exists():
        pom_text = read_text(pom) or ""
        try:
            declared = declared_dependency_artifacts(pom_text)
        except ElementTree.ParseError as error:
            report.add(rule, pom, 0, f"the build file is not well-formed XML: {error}")
            declared = []
        artifacts = {artifact for _, artifact in declared}
        for dependency, reason in FORBIDDEN_BACKEND_DEPENDENCIES:
            if dependency in artifacts:
                report.add(rule, pom, 0, f"forbidden dependency '{dependency}': {reason}")
        if "flyway-core" in artifacts and "spring-boot-starter-flyway" in artifacts:
            report.add(
                rule, pom, 0,
                "flyway-core is provided by the Boot starter and must not be declared",
            )

    migrations = ROOT / BACKEND / "src/main/resources/db/migration"
    if migrations.exists():
        versioned = sorted(p.name for p in migrations.glob("V*.sql"))
        if versioned != sorted(APPROVED_MIGRATIONS):
            report.add(
                rule, "db/migration", 0,
                f"the approved migration set is {sorted(APPROVED_MIGRATIONS)}; found {versioned}",
            )
        foundation = migrations / "V0001__create_foundation_schemas.sql"
        foundation_text = read_text(foundation)
        if foundation_text is None:
            report.add(rule, foundation, 0, "the foundation migration must exist")
        else:
            digest = hashlib.sha256(foundation_text.encode("utf-8")).hexdigest()
            if digest != FOUNDATION_MIGRATION_SHA256:
                report.add(
                    rule, foundation, 0,
                    "the applied foundation migration is immutable; "
                    f"expected sha256 {FOUNDATION_MIGRATION_SHA256}, found {digest}",
                )
        for path in migrations.glob("*.sql"):
            text = read_text(path) or ""
            upper = text.upper()
            if "IF NOT EXISTS" in upper:
                report.add(
                    rule, path, 0,
                    "schema creation is strict and must not tolerate an existing object",
                )
            for number, line in enumerate(text.splitlines(), start=1):
                stripped = line.strip().upper()
                if stripped.startswith("--"):
                    continue
                if DESTRUCTIVE_MIGRATION_STATEMENT.search(line):
                    if approved_index_replacement(path, text, line):
                        continue
                    report.add(
                        rule, path, number,
                        f"destructive statement in a metadata migration: {line.strip()[:80]}",
                    )

    check_repository_contracts(report)


def check_functional_comments(report: Report, files: list[Path]) -> None:
    """TC-GLOBAL-002 — comments describe behaviour, not project history."""
    rule = "TC-GLOBAL-002"
    for path in files:
        relative = str(path.relative_to(ROOT))
        if under(path, HISTORICAL_DOC_ROOTS) or relative in HISTORICAL_DOC_ROOTS:
            continue
        if is_rule_definition(path):
            continue
        if path.suffix.lower() not in COMMENT_SUFFIXES:
            continue
        if not (under(path, PRODUCTION_ROOTS) or under(path, TEST_ROOTS)):
            continue
        text = read_text(path)
        if text is None:
            continue
        for number, line in comment_lines(text, path.suffix.lower()):
            for pattern, label in HISTORY_COMMENT_PATTERNS:
                if pattern.search(line):
                    report.add(rule, path, number, f"{label}: {line.strip()[:80]}")


def check_production_naming(report: Report, files: list[Path]) -> None:
    """TC-GLOBAL-003 — production identifiers use the agreed production names."""
    rule = "TC-GLOBAL-003"

    pom = ROOT / BACKEND / "pom.xml"
    if pom.exists():
        pom_text = read_text(pom) or ""
        if f"<groupId>{REQUIRED_NAMES['java_root_package']}</groupId>" not in pom_text:
            report.add(rule, pom, 0, "groupId must be com.mimococo.marketops")
        if f"<artifactId>{REQUIRED_NAMES['backend_application']}</artifactId>" not in pom_text:
            report.add(rule, pom, 0, "artifactId must be marketops-server")

    package_json = ROOT / FRONTEND / "package.json"
    if package_json.exists():
        try:
            manifest = json.loads(read_text(package_json) or "{}")
        except json.JSONDecodeError as error:
            report.add(rule, package_json, 0, f"package.json is not valid JSON: {error}")
            manifest = {}
        if manifest.get("name") != REQUIRED_NAMES["frontend_package"]:
            report.add(rule, package_json, 0, "package name must be marketops-console")
        if manifest.get("private") is not True:
            report.add(rule, package_json, 0, "the frontend package must be private")

    for path in files:
        if not under(path, PRODUCTION_ROOTS):
            continue
        if path.suffix.lower() not in SOURCE_SUFFIXES:
            continue
        if is_rule_definition(path):
            continue
        text = read_text(path)
        if text is None:
            continue
        for term in SCAFFOLD_TERMS:
            pattern = re.compile(rf"(?<![A-Za-z0-9]){re.escape(term)}(?![A-Za-z0-9])", re.I)
            for number, line in enumerate(text.splitlines(), start=1):
                if pattern.search(line):
                    report.add(rule, path, number, f"scaffold term '{term}' in a production path")
        for number, line in enumerate(text.splitlines(), start=1):
            for match in IDENTIFIER_CONTEXT.finditer(line):
                identifier = (match.group(1) or "").lower()
                if not identifier:
                    continue
                for term in IDENTIFIER_SCAFFOLD_TERMS:
                    if identifier == term or identifier.startswith(term + "-") or identifier.startswith(term + "."):
                        report.add(
                            rule, path, number,
                            f"scaffold identifier '{match.group(1)}' in a production path",
                        )


def deferred_evidence_register_violations(data: object, root: Path = ROOT) -> list[str]:
    """Validate Amendment-002's exact deferred evidence boundary."""
    errors: list[str] = []
    if not isinstance(data, dict):
        return ["deferred evidence register must be a JSON object"]
    if data.get("schemaVersion") != 1:
        errors.append("schemaVersion must be exactly 1")
    if data.get("futureGate") != "RELEASE-V1-001":
        errors.append("futureGate must be exactly RELEASE-V1-001")
    if data.get("productionWriteEnabled") is not False:
        errors.append("productionWriteEnabled must remain false")

    authority = data.get("authority")
    if not isinstance(authority, dict):
        errors.append("authority must be an object")
    else:
        for field, expected in DEFERRED_AUTHORITY_HASHES.items():
            if authority.get(field) != expected:
                errors.append(f"authority {field} must be exactly {expected}")
        for path_field, hash_field in (
            ("amendmentPath", "amendmentSha256"),
            ("ownerAcceptancePath", "ownerAcceptanceSha256"),
        ):
            relative = authority.get(path_field)
            if not isinstance(relative, str) or not relative:
                errors.append(f"authority {path_field} must be a repository path")
                continue
            candidate = root / relative
            if not candidate.is_file():
                errors.append(f"authority {path_field} does not exist: {relative}")
                continue
            actual = hashlib.sha256(candidate.read_bytes()).hexdigest()
            expected = authority.get(hash_field)
            if actual != expected:
                errors.append(
                    f"authority {path_field} hash mismatch: expected {expected}, found {actual}"
                )

    entries = data.get("entries")
    if not isinstance(entries, list):
        return errors + ["entries must be an array"]
    ids = [entry.get("acceptanceId") for entry in entries if isinstance(entry, dict)]
    if len(ids) != len(set(ids)):
        errors.append("deferred acceptance IDs must be unique")
    if set(ids) != set(DEFERRED_ACCEPTANCE_IDS) or len(ids) != len(DEFERRED_ACCEPTANCE_IDS):
        errors.append(
            "deferred acceptance IDs must exactly cover Amendment-002: "
            + ", ".join(DEFERRED_ACCEPTANCE_IDS)
        )

    gate_ev_ids = {"S1-AC-031", "S1-AC-032", "S1-AC-033"}
    owner_evidence_ids = {"S1-AC-026", "S1-AC-038", "S1-AC-040"}
    required_text_fields = (
        "engineeringEvidenceClosedNow", "deferredEvidence", "activationPrerequisite",
        "productionBlockingEffect", "currentStatus", "engineeringStatus",
        "releaseEvidenceState", "futureEvidencePath",
    )
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            errors.append(f"entry {index} must be an object")
            continue
        acceptance_id = entry.get("acceptanceId")
        for field in required_text_fields:
            if not isinstance(entry.get(field), str) or not entry.get(field):
                errors.append(f"{acceptance_id or index} {field} must be non-empty")
        if entry.get("currentStatus") != DEFERRED_CURRENT_STATUS:
            errors.append(
                f"{acceptance_id} currentStatus must be {DEFERRED_CURRENT_STATUS}"
            )
        if entry.get("engineeringStatus") != DEFERRED_ENGINEERING_STATUS:
            errors.append(
                f"{acceptance_id} engineeringStatus must be {DEFERRED_ENGINEERING_STATUS}"
            )
        release_state = entry.get("releaseEvidenceState")
        if release_state not in DEFERRED_RELEASE_STATES:
            errors.append(f"{acceptance_id} has invalid releaseEvidenceState: {release_state}")
        expected_release_state = (
            "GATE_EV_DEFERRED_TO_RELEASE_V1_001" if acceptance_id in gate_ev_ids
            else "OWNER_RELEASE_EVIDENCE_DEFERRED_TO_RELEASE_V1_001"
            if acceptance_id in owner_evidence_ids else DEFERRED_CURRENT_STATUS
        )
        if release_state != expected_release_state:
            errors.append(
                f"{acceptance_id} releaseEvidenceState must be {expected_release_state}"
            )
        expected_future_path = (
            f"docs/07-phase-evidence/RELEASE-V1-001/{acceptance_id}/"
        )
        if entry.get("futureEvidencePath") != expected_future_path:
            errors.append(
                f"{acceptance_id} futureEvidencePath must be {expected_future_path}"
            )
        evidence_paths = entry.get("engineeringEvidencePaths")
        if not isinstance(evidence_paths, list) or not evidence_paths:
            errors.append(f"{acceptance_id} engineeringEvidencePaths must be non-empty")
        else:
            for relative in evidence_paths:
                if not isinstance(relative, str) or not relative or not (root / relative).exists():
                    errors.append(
                        f"{acceptance_id} engineering evidence path does not exist: {relative}"
                    )
        for field in ("currentStatus", "engineeringStatus", "releaseEvidenceState"):
            if entry.get(field) in PROHIBITED_DEFERRED_STATUSES:
                errors.append(
                    f"{acceptance_id} deferred evidence must not be relabeled "
                    f"{entry.get(field)}"
                )
    return errors


def check_deferred_evidence_register(report: Report) -> None:
    rule = "TC-GLOBAL-004"
    path = ROOT / DEFERRED_EVIDENCE_REGISTER
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        report.add(rule, DEFERRED_EVIDENCE_REGISTER, 0, f"register is unreadable: {error}")
        return
    for detail in deferred_evidence_register_violations(data):
        report.add(rule, DEFERRED_EVIDENCE_REGISTER, 0, detail)


def main() -> int:
    files = iter_files()
    report = Report(inspected_files=len(files))

    check_compromise_retirement(report, files)
    check_functional_comments(report, files)
    check_production_naming(report, files)
    check_deferred_evidence_register(report)

    rules = ("TC-GLOBAL-001", "TC-GLOBAL-002", "TC-GLOBAL-003", "TC-GLOBAL-004")
    labels = {
        "TC-GLOBAL-001": "Compromise Retirement Check",
        "TC-GLOBAL-002": "Functional JavaDoc Rewrite Check",
        "TC-GLOBAL-003": "Production Naming Check",
        "TC-GLOBAL-004": "Deferred Evidence Boundary Check",
    }

    print(f"Production readiness validation over {report.inspected_files} files.")
    failed = False
    for rule in rules:
        violations = report.for_rule(rule)
        if violations:
            failed = True
            print(f"{rule} {labels[rule]}: {len(violations)} violation(s)", file=sys.stderr)
            for violation in violations:
                location = f"{violation.path}:{violation.line}" if violation.line else violation.path
                print(f"  {location}: {violation.detail}", file=sys.stderr)
        else:
            print(f"{rule} {labels[rule]}: PASS")

    if failed:
        print("Production readiness validation failed.", file=sys.stderr)
        return 1
    print("Production readiness validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
