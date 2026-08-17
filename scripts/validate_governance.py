#!/usr/bin/env python3
from __future__ import annotations

import csv
import hashlib
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REQUIRED_FILES = [
    "README.md",
    "CLAUDE.md",
    "AGENTS.md",
    "docs/00-governance/PROJECT_CHARTER.md",
    "docs/00-governance/CURRENT_STATE.md",
    "docs/00-governance/DECISION_LOG.md",
    "docs/00-governance/DR-0001-temporary-codex-git-execution-delegation.md",
    "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md",
    "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md",
    "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md",
    "docs/00-governance/HANDOFF_PROTOCOL.md",
    "docs/00-governance/OPEN_QUESTIONS.md",
    "docs/00-governance/QUALITY_GATES.md",
    "docs/01-requirements/baseline-v1.0-cn.md",
    "docs/01-requirements/naming-baseline-cn.md",
    "docs/01-requirements/SHA256SUMS.txt",
    "docs/01-requirements/traceability.csv",
    "docs/02-architecture/designs/WP-P0-001-foundation-design.md",
    "docs/03-work-items/BACKLOG-PHASE-0.md",
    "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md",
    "docs/03-work-items/WP-P0-002-organization-store-warehouse-credential-metadata.md",
    ".github/pull_request_template.md",
    ".github/workflows/governance.yml",
    "tests/test_validate_governance.py",
]

ACTIVE_AUTHORIZATION_STATES = {"DESIGN_ONLY", "APPROVED_FOR_IMPLEMENTATION"}
CURRENT_AUTHORIZATION_ALLOWED_STATES = ACTIVE_AUTHORIZATION_STATES | {"PLANNING_ONLY"}
WP_EXECUTION_AUTHORIZATION_ALLOWED_STATES = ACTIVE_AUTHORIZATION_STATES | {"CLOSED"}
LIFECYCLE_ALLOWED_STATES = {"INITIATING", "EXECUTING_PHASE_0"}
CANONICAL_DESIGN_RELATIVE_PATH = (
    "docs/02-architecture/designs/WP-P0-001-foundation-design.md"
)
CANONICAL_DESIGN_METADATA = {
    "document_type": "module foundation design",
    "status": "APPROVED_FOR_IMPLEMENTATION",
    "work_package": "WP-P0-001",
    "product": "MarketOps Russia",
    "repository": "marketops-platform",
}
OWNER_GUIDANCE_ALLOWED_STATES = {"REQUIRED", "DISABLED"}
OWNER_GUIDANCE_EXIT_AUTHORITY = "HUMAN_OWNER_EXPLICIT_CONFIRMATION"
OWNER_DELEGATION_ALLOWED_STATES = {"ACTIVE", "INACTIVE"}
OWNER_DELEGATION_ALLOWED_EXECUTORS = {"CODEX", "NONE"}
OWNER_DELEGATION_SCOPE = "PR_READY_AND_MERGE_AFTER_ALL_GATES"
OWNER_DELEGATION_INACTIVE_SCOPE = "NONE"
OWNER_DELEGATION_EXIT_AUTHORITY = "HUMAN_OWNER_EXPLICIT_REVOCATION"
COMPLETED_WP_STATUS = "COMPLETED"
COMPLETED_WP_AUTHORIZATION = "CLOSED"
COMPLETED_WP_RESULT = "VERIFIED"
HISTORIC_DESIGN_VERDICT = "APPROVED_FOR_IMPLEMENTATION"
POST_WP_ACTIVE_GATE = "CONTROLLER_PHASE_0_PLANNING"
DESIGN_ACTIVE_GATE = "READY_FOR_DESIGN"
WP_P0_001_ID = "WP-P0-001"
WP_P0_002_ID = "WP-P0-002"
WP_P0_001_RELATIVE_PATH = (
    "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md"
)
WP_P0_002_RELATIVE_PATH = (
    "docs/03-work-items/"
    "WP-P0-002-organization-store-warehouse-credential-metadata.md"
)
WORK_PACKAGE_PATHS = {
    WP_P0_001_ID: WP_P0_001_RELATIVE_PATH,
    WP_P0_002_ID: WP_P0_002_RELATIVE_PATH,
}
CONTROLLER_REVIEW_STANDARD_RELATIVE_PATH = (
    "docs/00-governance/CONTROLLER_REVIEW_STANDARD.md"
)
BACKLOG_HEADER = ["ID", "Title", "Status", "Dependencies", "Core source requirements"]
BACKLOG_ALLOWED_STATES = {"DRAFT", "READY_FOR_DESIGN", "COMPLETED"}
COMPLETED_TRACEABILITY_STATES = {"VERIFIED", "ACTIVE_CONTROL"}
COMPLETED_TRACEABILITY_IDS = {"D-02", "D-03", "D-07", "D-10", "D-15", "D-16", "D-17", "HR-06"}
D03_WORK_PACKAGES = "WP-P0-001;WP-P0-003"
D03_WORKER_WORK_PACKAGE = "WP-P0-003"
PARALLEL_CURRENT_STATE_PATHS = {
    "docs/00-governance/CURRENT_STATE_PROPOSAL_WP-P0-001.md",
}

WP_P0_001_REQUIRED_HEADINGS = [
    "## 1. Metadata",
    "## 2. Outcome",
    "## 3. Source Requirements",
    "## 4. Scope",
    "## 5. Non-goals",
    "## 6. Design Deliverables",
    "## 7. Acceptance Criteria",
    "## 8. Required Evidence",
    "## 9. Risks and Constraints",
    "## 10. Controller Gate",
]

WP_P0_002_REQUIRED_HEADINGS = [
    "## 1. Metadata",
    "## 2. Business Outcome",
    "## 3. Source Requirements and Planning Inputs",
    "## 4. Ownership and Association Semantics",
    "## 5. Requirement Closure Contract",
    "## 6. Scope",
    "## 7. Non-goals",
    "## 8. Inputs, Outputs and Failure States",
    "## 9. Security and Data Boundaries",
    "## 10. Acceptance Criteria",
    "## 11. Migration, Rollback and Observability Expectations",
    "## 12. Design Deliverables and Required Evidence",
    "## 13. Risks and Constraints",
    "## 14. Controller Gate",
]

WP_P0_002_METADATA = {
    "ID": WP_P0_002_ID,
    "Title": "Organization, Store, Warehouse & Credential Metadata",
    "Phase": "Sprint 0 / Phase 0",
    "Status": "READY_FOR_DESIGN",
    "Authorization": "DESIGN_ONLY",
    "Risk": "HIGH",
    "Target branch": "`main`",
    "Implementation authorization": "PROHIBITED",
}

WP_P0_002_TRACEABILITY_CONTRACT = {
    "IAM-001": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "runtime IAM"),
    "IAM-004": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "runtime IAM"),
    "IAM-006": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "runtime integration"),
    "IAM-007": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "runtime IAM"),
    "INT-002": (
        "WP-P0-002;WP-P0-005;WP-P0-006",
        "PARTIAL in WP-P0-002",
        "WP-P0-005/006",
    ),
    "INT-003": (WP_P0_002_ID, "PARTIAL in WP-P0-002", "OQ-006"),
    "ADM-001": (WP_P0_002_ID, "FULL closure in WP-P0-002", "fail-closed"),
    "ADM-002": (
        "WP-P0-002;WP-P0-003",
        "PARTIAL in WP-P0-002",
        "WP-P0-003",
    ),
}

TRACEABILITY_HEADER = [
    "source_id",
    "source_type",
    "phase",
    "title",
    "work_package",
    "design_record",
    "code_location",
    "test_case",
    "evidence",
    "status",
    "notes",
]

SCAN_EXTENSIONS = {
    ".yml", ".yaml", ".json", ".properties", ".toml", ".xml",
    ".java", ".kt", ".ts", ".tsx", ".js", ".jsx", ".py", ".sh", ".ps1",
}

SECRET_PATTERNS = [
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bghp_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{30,}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
    re.compile(r"\bsk-[A-Za-z0-9]{24,}\b"),
    re.compile(r"(?i)(?:password|passwd|secret|token|api[_-]?key)\s*[:=]\s*[\"'][^\"']{12,}[\"']"),
]


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_required_files(errors: list[str]) -> None:
    for relative in REQUIRED_FILES:
        if not (ROOT / relative).is_file():
            errors.append(f"missing required file: {relative}")


def validate_source_checksums(errors: list[str]) -> None:
    sums_path = ROOT / "docs/01-requirements/SHA256SUMS.txt"
    if not sums_path.exists():
        return
    base = sums_path.parent
    for raw_line in sums_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            expected, filename = line.split(None, 1)
        except ValueError:
            errors.append(f"invalid checksum line: {raw_line}")
            continue
        target = base / filename.strip()
        if not target.is_file():
            errors.append(f"checksum target missing: {target.relative_to(ROOT)}")
            continue
        actual = sha256(target)
        if actual != expected:
            errors.append(f"source baseline checksum mismatch: {target.relative_to(ROOT)}")


def validate_work_package(errors: list[str]) -> None:
    wp_p0_001 = ROOT / WP_P0_001_RELATIVE_PATH
    if wp_p0_001.exists():
        text = wp_p0_001.read_text(encoding="utf-8")
        for heading in WP_P0_001_REQUIRED_HEADINGS:
            if heading not in text:
                errors.append(f"WP-P0-001 missing heading: {heading}")

    wp_p0_002 = ROOT / WP_P0_002_RELATIVE_PATH
    if not wp_p0_002.exists():
        return
    text = wp_p0_002.read_text(encoding="utf-8")
    for heading in WP_P0_002_REQUIRED_HEADINGS:
        if heading not in text:
            errors.append(f"WP-P0-002 missing heading: {heading}")
    for field, expected in WP_P0_002_METADATA.items():
        actual = work_package_metadata_value(text, field)
        if actual != expected:
            errors.append(f"WP-P0-002 {field} must be exactly: {expected}")

    for requirement, closure in {
        "IAM-001": "PARTIAL",
        "IAM-004": "PARTIAL",
        "IAM-006": "PARTIAL",
        "IAM-007": "PARTIAL",
        "INT-002": "PARTIAL",
        "INT-003": "PARTIAL",
        "ADM-001": "FULL",
        "ADM-002": "PARTIAL",
    }.items():
        if f"| {requirement} | {closure} |" not in text:
            errors.append(
                f"WP-P0-002 Requirement Closure Contract missing {requirement}: {closure}"
            )

    for required in [
        "Organization → Legal Entity → Marketplace Account → Store",
        "Legal Entity-owned operational node",
        "configurable service / fulfillment association",
        "UNKNOWN until verified platform evidence exists",
        "Marketplace-fulfilled",
        "Seller-fulfilled",
        "maintenance remains local/internal/admin-only and fail closed",
        "audit` is a conceptual responsibility, not an approved PostgreSQL schema",
        "iam / platform / raw / staging / core / ledger / mart / ops",
        "Implementation: PROHIBITED until APPROVED_FOR_IMPLEMENTATION",
    ]:
        if required not in text:
            errors.append(f"WP-P0-002 missing Design contract: {required}")


def validate_parallel_current_state_paths(
    errors: list[str], existing_paths: set[str]
) -> None:
    """Reject a second state source even when it calls itself a proposal."""
    for relative in sorted(PARALLEL_CURRENT_STATE_PATHS & existing_paths):
        errors.append(f"parallel Current State source is prohibited: {relative}")


def leading_yaml_body(text: str, expected_heading: str | None = None) -> str | None:
    heading = re.escape(expected_heading) if expected_heading else r"#[^\n]+"
    match = re.search(
        rf"(?ms)\A{heading}\s*$\n\s*```yaml\s*$\n(?P<body>.*?)^```\s*$",
        text,
    )
    return match.group("body") if match else None


def fenced_yaml_body(text: str) -> str | None:
    return leading_yaml_body(text, "# Current State")


def unique_yaml_value(text: str, field: str) -> str | None:
    matches = re.findall(
        rf"(?m)^{re.escape(field)}:\s*(.*?)\s*$",
        text,
    )
    values = [match.strip() for match in matches]
    return values[0] if len(values) == 1 and values[0] else None


def current_state_value(text: str, field: str) -> str | None:
    metadata = fenced_yaml_body(text)
    return unique_yaml_value(metadata if metadata is not None else text, field)


def current_state_metadata_value(text: str, field: str) -> str | None:
    metadata = fenced_yaml_body(text)
    return unique_yaml_value(metadata, field) if metadata is not None else None


def h2_section_body(text: str, heading: str) -> str | None:
    section = re.search(
        rf"(?ms)^{re.escape(heading)}\s*$\n(?P<body>.*?)(?=^## |\Z)",
        text,
    )
    return section.group("body") if section else None


def markdown_table_value(text: str, heading: str, field: str) -> str | None:
    body = h2_section_body(text, heading)
    if body is None:
        return None

    values: list[str] = []
    for line in body.splitlines():
        if not line.startswith("|") or not line.endswith("|"):
            continue
        cells = [cell.strip() for cell in line[1:-1].split("|")]
        if len(cells) == 2 and cells[0] == field:
            values.append(cells[1])
    return values[0] if len(values) == 1 else None


def work_package_metadata_value(text: str, field: str) -> str | None:
    return markdown_table_value(text, "## 1. Metadata", field)


def work_package_execution_authorization(text: str) -> str | None:
    """Return the unambiguous current authorization of a Work Package.

    Completed packages use the explicit field so their historic design verdict
    cannot be mistaken for current permission. Active-package records may use
    the generic field, but both fields at once are ambiguous and invalid.
    """
    explicit = work_package_metadata_value(text, "Current execution authorization")
    generic = work_package_metadata_value(text, "Authorization")
    if explicit is not None and generic is not None:
        return None
    return explicit if explicit is not None else generic


def validate_completed_wp_p0_001_text(errors: list[str], text: str) -> None:
    """Preserve the independently verified WP-P0-001 closure in every later state."""
    expected = {
        "Status": COMPLETED_WP_STATUS,
        "Historic design verdict": HISTORIC_DESIGN_VERDICT,
        "Implementation result": COMPLETED_WP_RESULT,
    }
    for field, value in expected.items():
        if work_package_metadata_value(text, field) != value:
            errors.append(f"WP-P0-001 {field} must be exactly: {value}")
    if work_package_execution_authorization(text) != COMPLETED_WP_AUTHORIZATION:
        errors.append("WP-P0-001 current execution authorization must be CLOSED")


def validate_active_work_package_record_text(
    errors: list[str],
    current_state_text: str,
    work_package_records: dict[str, str],
) -> None:
    """Resolve an active Work Package to its one canonical repository record."""
    active = current_state_metadata_value(current_state_text, "active_work_package")
    if active is None:
        errors.append("CURRENT_STATE active_work_package metadata is missing or duplicated")
        return
    if active == "NONE":
        return
    if active not in WORK_PACKAGE_PATHS:
        errors.append(f"CURRENT_STATE active Work Package is not registered: {active}")
        return
    record = work_package_records.get(active)
    if record is None:
        errors.append(
            f"active Work Package canonical file is missing: {WORK_PACKAGE_PATHS[active]}"
        )
        return
    if work_package_metadata_value(record, "ID") != active:
        errors.append(f"active Work Package canonical file ID must be exactly: {active}")


def markdown_table_cells(line: str) -> list[str] | None:
    """Return trimmed cells for one pipe-delimited Markdown table row."""
    stripped = line.strip()
    if not stripped.startswith("|") or not stripped.endswith("|"):
        return None
    return [cell.strip() for cell in stripped[1:-1].split("|")]


def phase_zero_backlog_rows(text: str) -> list[dict[str, str]] | None:
    """Parse the single canonical Phase 0 backlog table structurally."""
    lines = text.splitlines()
    parsed_tables: list[list[dict[str, str]]] = []
    for index, line in enumerate(lines):
        if markdown_table_cells(line) != BACKLOG_HEADER:
            continue
        if index + 1 >= len(lines):
            return None
        separator = markdown_table_cells(lines[index + 1])
        if separator is None or len(separator) != len(BACKLOG_HEADER):
            return None
        if any(re.fullmatch(r":?-{3,}:?", cell) is None for cell in separator):
            return None

        rows: list[dict[str, str]] = []
        for row_line in lines[index + 2:]:
            cells = markdown_table_cells(row_line)
            if cells is None:
                break
            if len(cells) != len(BACKLOG_HEADER):
                return None
            rows.append(dict(zip(BACKLOG_HEADER, cells)))
        parsed_tables.append(rows)
    return parsed_tables[0] if len(parsed_tables) == 1 else None


def validate_backlog_state_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    backlog_text: str,
    active_work_package_text: str | None = None,
) -> None:
    """Reconcile closed planning and one active READY_FOR_DESIGN transition."""
    rows = phase_zero_backlog_rows(backlog_text)
    if rows is None:
        errors.append("Phase 0 backlog must contain exactly one structurally valid backlog table")
        return

    ids = [row["ID"] for row in rows]
    if len(ids) != len(set(ids)):
        errors.append("Phase 0 backlog contains duplicate Work Package IDs")

    for row in rows:
        if row["Status"] not in BACKLOG_ALLOWED_STATES:
            errors.append(
                f"backlog {row['ID']} has unknown Status: {row['Status']}"
            )

    wp_rows = [row for row in rows if row["ID"] == "WP-P0-001"]
    if len(wp_rows) != 1:
        errors.append("Phase 0 backlog must contain exactly one WP-P0-001 row")
        return
    if wp_rows[0]["Status"] != COMPLETED_WP_STATUS:
        errors.append(
            f"backlog WP-P0-001 Status must be exactly: {COMPLETED_WP_STATUS}"
        )
    validate_completed_wp_p0_001_text(errors, work_package_text)

    active = current_state_metadata_value(current_state_text, "active_work_package")
    ready_rows = [row for row in rows if row["Status"] == DESIGN_ACTIVE_GATE]
    if len(ready_rows) > 1:
        errors.append(
            "Phase 0 backlog cannot contain multiple READY_FOR_DESIGN Work Packages"
        )

    if active == "NONE":
        expected_current = {
            "active_work_package": "NONE",
            "active_gate": POST_WP_ACTIVE_GATE,
            "authorization": "PLANNING_ONLY",
        }
        if ready_rows:
            errors.append(
                "closed planning state cannot retain a READY_FOR_DESIGN backlog row"
            )
    elif active is not None:
        expected_current = {
            "active_work_package": active,
            "active_gate": DESIGN_ACTIVE_GATE,
            "authorization": "DESIGN_ONLY",
        }
        active_rows = [row for row in rows if row["ID"] == active]
        if len(active_rows) != 1:
            errors.append(
                f"Phase 0 backlog must contain exactly one active Work Package row: {active}"
            )
        elif active_rows[0]["Status"] != DESIGN_ACTIVE_GATE:
            errors.append(
                f"backlog active Work Package {active} Status must be exactly: "
                + DESIGN_ACTIVE_GATE
            )
        if len(ready_rows) != 1 or ready_rows[0]["ID"] != active:
            errors.append(
                "the only READY_FOR_DESIGN backlog row must match CURRENT_STATE "
                "active_work_package"
            )
        if active_work_package_text is None:
            errors.append("active Work Package canonical text is required for backlog validation")
        else:
            if work_package_metadata_value(active_work_package_text, "Status") != DESIGN_ACTIVE_GATE:
                errors.append(
                    f"active Work Package Status must be exactly: {DESIGN_ACTIVE_GATE}"
                )
            if work_package_execution_authorization(active_work_package_text) != "DESIGN_ONLY":
                errors.append("active Work Package authorization must be exactly: DESIGN_ONLY")
    else:
        expected_current = {}

    for field, expected in expected_current.items():
        if current_state_metadata_value(current_state_text, field) != expected:
            errors.append(
                f"backlog transition requires CURRENT_STATE {field}: {expected}"
            )


def project_charter_status(text: str) -> str | None:
    return markdown_table_value(text, "## 1. Identity", "Status")


def validate_lifecycle_state_text(
    errors: list[str],
    current_state_text: str,
    project_charter_text: str,
) -> None:
    lifecycle_state = current_state_metadata_value(
        current_state_text,
        "lifecycle_state",
    )
    charter_status = project_charter_status(project_charter_text)

    if lifecycle_state is None:
        errors.append("CURRENT_STATE lifecycle_state metadata is missing or duplicated")
    elif lifecycle_state not in LIFECYCLE_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE lifecycle_state must be exactly one of: "
            + ", ".join(sorted(LIFECYCLE_ALLOWED_STATES))
        )

    if charter_status is None:
        errors.append("PROJECT_CHARTER Status metadata is missing or duplicated")
    elif charter_status not in LIFECYCLE_ALLOWED_STATES:
        errors.append(
            "PROJECT_CHARTER Status must be exactly one of: "
            + ", ".join(sorted(LIFECYCLE_ALLOWED_STATES))
        )

    if (
        lifecycle_state in LIFECYCLE_ALLOWED_STATES
        and charter_status in LIFECYCLE_ALLOWED_STATES
        and lifecycle_state != charter_status
    ):
        errors.append(
            "lifecycle mismatch: CURRENT_STATE "
            f"{lifecycle_state} != PROJECT_CHARTER {charter_status}"
        )


def current_state_canonical_design_path(text: str) -> str | None:
    body = h2_section_body(text, "## Approved design of record")
    if body is None:
        return None
    matches = re.findall(r"(?m)^Canonical design:\s*([^\s#]+)\s*$", body)
    return matches[0] if len(matches) == 1 else None


def work_package_canonical_design_path(text: str) -> str | None:
    body = h2_section_body(text, "## 6. Design Deliverables")
    if body is None:
        return None
    matches = re.findall(
        r"(?m)^The approved canonical design at\s*$\n"
        r"`([^`\n]+)` defines:\s*$",
        body,
    )
    return matches[0] if len(matches) == 1 else None


def validate_approved_design_state_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    canonical_design_text: str | None,
) -> None:
    historic_verdict = work_package_metadata_value(
        work_package_text, "Historic design verdict"
    )
    execution_authorization = work_package_execution_authorization(work_package_text)
    if (
        historic_verdict != HISTORIC_DESIGN_VERDICT
        and execution_authorization != "APPROVED_FOR_IMPLEMENTATION"
    ):
        return

    current_path = current_state_canonical_design_path(current_state_text)
    if current_path is None:
        errors.append("CURRENT_STATE canonical design path is missing or duplicated")
    elif current_path != CANONICAL_DESIGN_RELATIVE_PATH:
        errors.append(
            "CURRENT_STATE canonical design path must be exactly: "
            + CANONICAL_DESIGN_RELATIVE_PATH
        )

    wp_path = work_package_canonical_design_path(work_package_text)
    if wp_path is None:
        errors.append("WP-P0-001 canonical design path is missing or duplicated")
    elif wp_path != CANONICAL_DESIGN_RELATIVE_PATH:
        errors.append(
            "WP-P0-001 canonical design path must be exactly: "
            + CANONICAL_DESIGN_RELATIVE_PATH
        )

    if canonical_design_text is None:
        errors.append("approved canonical design is missing")
        return

    metadata = leading_yaml_body(canonical_design_text)
    if metadata is None:
        errors.append("approved canonical design leading metadata is malformed")
        return

    for field, expected in CANONICAL_DESIGN_METADATA.items():
        actual = unique_yaml_value(metadata, field)
        if actual is None:
            errors.append(
                f"approved canonical design {field} is missing or duplicated"
            )
        elif actual != expected:
            errors.append(
                f"approved canonical design {field} must be exactly: {expected}"
            )


def validate_authorization_state_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    work_package_id: str = WP_P0_001_ID,
) -> None:
    current_authorization = current_state_metadata_value(current_state_text, "authorization")
    active_work_package = current_state_metadata_value(
        current_state_text, "active_work_package"
    )
    wp_status = work_package_metadata_value(work_package_text, "Status")
    wp_authorization = work_package_execution_authorization(work_package_text)

    if current_authorization is None:
        errors.append("CURRENT_STATE authorization metadata is missing or duplicated")
    elif current_authorization not in CURRENT_AUTHORIZATION_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE authorization must be exactly one of: "
            + ", ".join(sorted(CURRENT_AUTHORIZATION_ALLOWED_STATES))
        )

    if active_work_package is None:
        errors.append("CURRENT_STATE active_work_package metadata is missing or duplicated")

    if wp_authorization is None:
        errors.append(
            f"{work_package_id} current execution authorization is missing, "
            "duplicated or ambiguous"
        )
    elif wp_authorization not in WP_EXECUTION_AUTHORIZATION_ALLOWED_STATES:
        errors.append(
            f"{work_package_id} current execution authorization must be exactly one of: "
            + ", ".join(sorted(WP_EXECUTION_AUTHORIZATION_ALLOWED_STATES))
        )

    if active_work_package == "NONE":
        if current_authorization != "PLANNING_ONLY":
            errors.append(
                "CURRENT_STATE active_work_package NONE requires authorization PLANNING_ONLY"
            )
        if work_package_id != WP_P0_001_ID:
            errors.append("closed planning state must validate the WP-P0-001 record")
        else:
            validate_completed_wp_p0_001_text(errors, work_package_text)
    elif active_work_package is not None:
        if active_work_package != work_package_id:
            errors.append(
                "active Work Package record mismatch: CURRENT_STATE "
                f"{active_work_package} != validated record {work_package_id}"
            )
        if current_authorization not in ACTIVE_AUTHORIZATION_STATES:
            errors.append(
                "an active Work Package requires DESIGN_ONLY or APPROVED_FOR_IMPLEMENTATION"
            )
        if wp_authorization not in ACTIVE_AUTHORIZATION_STATES:
            errors.append(
                "an active Work Package cannot have CLOSED execution authorization"
            )
        if (
            current_authorization in ACTIVE_AUTHORIZATION_STATES
            and wp_authorization in ACTIVE_AUTHORIZATION_STATES
            and current_authorization != wp_authorization
        ):
            errors.append(
                "authorization mismatch: CURRENT_STATE "
                f"{current_authorization} != active Work Package {wp_authorization}"
            )
        if wp_status == COMPLETED_WP_STATUS:
            errors.append("a COMPLETED Work Package cannot remain active")
        if current_authorization == "DESIGN_ONLY":
            if current_state_metadata_value(current_state_text, "active_gate") != DESIGN_ACTIVE_GATE:
                errors.append(
                    f"DESIGN_ONLY requires CURRENT_STATE active_gate: {DESIGN_ACTIVE_GATE}"
                )
            if wp_status != DESIGN_ACTIVE_GATE:
                errors.append(
                    f"DESIGN_ONLY active Work Package Status must be: {DESIGN_ACTIVE_GATE}"
                )


def validate_owner_control_state_text(errors: list[str], text: str) -> None:
    guidance_state = current_state_value(text, "owner_git_workflow_guidance")
    if guidance_state not in OWNER_GUIDANCE_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE owner_git_workflow_guidance must be exactly one of: "
            + ", ".join(sorted(OWNER_GUIDANCE_ALLOWED_STATES))
        )

    guidance_exit = current_state_value(text, "owner_git_workflow_guidance_exit")
    if guidance_exit != OWNER_GUIDANCE_EXIT_AUTHORITY:
        errors.append(
            "CURRENT_STATE owner_git_workflow_guidance_exit must be exactly: "
            + OWNER_GUIDANCE_EXIT_AUTHORITY
        )

    delegation_state = current_state_value(text, "owner_git_execution_delegation")
    if delegation_state not in OWNER_DELEGATION_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE owner_git_execution_delegation must be exactly one of: "
            + ", ".join(sorted(OWNER_DELEGATION_ALLOWED_STATES))
        )

    delegate = current_state_value(text, "owner_git_execution_delegate")
    if delegate not in OWNER_DELEGATION_ALLOWED_EXECUTORS:
        errors.append(
            "CURRENT_STATE owner_git_execution_delegate must be exactly one of: "
            + ", ".join(sorted(OWNER_DELEGATION_ALLOWED_EXECUTORS))
        )

    delegation_scope = current_state_value(text, "owner_git_execution_delegation_scope")
    delegation_exit = current_state_value(text, "owner_git_execution_delegation_exit")
    if delegation_exit != OWNER_DELEGATION_EXIT_AUTHORITY:
        errors.append(
            "Owner Git execution delegation exit must be exactly: "
            + OWNER_DELEGATION_EXIT_AUTHORITY
        )
    if delegation_state == "ACTIVE":
        if delegate == "NONE":
            errors.append("ACTIVE Owner Git execution delegation requires a named delegate")
        if delegation_scope != OWNER_DELEGATION_SCOPE:
            errors.append(
                "ACTIVE Owner Git execution delegation scope must be exactly: "
                + OWNER_DELEGATION_SCOPE
            )
    elif delegation_state == "INACTIVE":
        if delegate != "NONE":
            errors.append("INACTIVE Owner Git execution delegation must use delegate NONE")
        if delegation_scope != OWNER_DELEGATION_INACTIVE_SCOPE:
            errors.append("INACTIVE Owner Git execution delegation scope must be NONE")


def validate_prior_closed_transition_text(errors: list[str], text: str) -> None:
    """Allow old state tokens only as explicit provenance inside Current State."""
    active = current_state_metadata_value(text, "active_work_package")
    if active in {None, "NONE"}:
        return
    body = h2_section_body(
        text,
        "## Prior closed planning transition — historical provenance",
    )
    if body is None:
        errors.append("active Design state must preserve classified WP-P0-001 closure provenance")
        return
    for token in (
        "active_work_package: NONE",
        "active_gate: CONTROLLER_PHASE_0_PLANNING",
        "authorization: PLANNING_ONLY",
        "superseded as live runtime state by the leading YAML",
        "not be interpreted as current authorization or a parallel state source",
    ):
        if token not in body:
            errors.append(f"prior closed transition provenance missing contract: {token}")


def validate_current_state(errors: list[str]) -> None:
    path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    for required in [
        "lifecycle_state:",
        "active_work_package:",
        "production_write_enabled: false",
        "owner_git_workflow_guidance:",
        "owner_git_workflow_guidance_exit:",
        "owner_git_execution_delegation:",
        "owner_git_execution_delegate:",
        "owner_git_execution_delegation_scope:",
        "owner_git_execution_delegation_exit:",
    ]:
        if required not in text:
            errors.append(f"CURRENT_STATE missing required field: {required}")
    validate_owner_control_state_text(errors, text)
    validate_prior_closed_transition_text(errors, text)


def validate_lifecycle_state(errors: list[str]) -> None:
    current_state_path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    project_charter_path = ROOT / "docs/00-governance/PROJECT_CHARTER.md"
    if not current_state_path.exists() or not project_charter_path.exists():
        return
    validate_lifecycle_state_text(
        errors,
        current_state_path.read_text(encoding="utf-8"),
        project_charter_path.read_text(encoding="utf-8"),
    )


def validate_authorization_state(errors: list[str]) -> None:
    current_state_path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    wp_p0_001_path = ROOT / WP_P0_001_RELATIVE_PATH
    if not current_state_path.exists() or not wp_p0_001_path.exists():
        return
    current_text = current_state_path.read_text(encoding="utf-8")
    wp_p0_001_text = wp_p0_001_path.read_text(encoding="utf-8")
    active = current_state_metadata_value(current_text, "active_work_package")
    records = {
        work_package_id: (ROOT / relative).read_text(encoding="utf-8")
        for work_package_id, relative in WORK_PACKAGE_PATHS.items()
        if (ROOT / relative).exists()
    }
    validate_active_work_package_record_text(errors, current_text, records)
    validate_completed_wp_p0_001_text(errors, wp_p0_001_text)

    if active == "NONE":
        selected_id = WP_P0_001_ID
        selected_text = wp_p0_001_text
    elif active in records:
        selected_id = active
        selected_text = records[active]
    else:
        return
    validate_authorization_state_text(
        errors,
        current_text,
        selected_text,
        selected_id,
    )


def validate_approved_design_state(errors: list[str]) -> None:
    current_state_path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    work_package_path = (
        ROOT / "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md"
    )
    canonical_design_path = ROOT / CANONICAL_DESIGN_RELATIVE_PATH
    if not current_state_path.exists() or not work_package_path.exists():
        return
    validate_approved_design_state_text(
        errors,
        current_state_path.read_text(encoding="utf-8"),
        work_package_path.read_text(encoding="utf-8"),
        (
            canonical_design_path.read_text(encoding="utf-8")
            if canonical_design_path.exists()
            else None
        ),
    )


def validate_owner_git_workflow_guidance(errors: list[str]) -> None:
    guide_path = ROOT / "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md"
    if not guide_path.exists():
        return

    guide = guide_path.read_text(encoding="utf-8")
    for required in [
        "state_source: docs/00-governance/CURRENT_STATE.md#owner_git_workflow_guidance",
        "supported_states: REQUIRED | DISABLED",
        "activation: every task start while Current State is REQUIRED",
        "exit_authority: Human Owner explicit confirmation only",
        "sync main",
        "Owner-authorized merge execution",
        "local sync/cleanup",
    ]:
        if required not in guide:
            errors.append(f"Owner Git workflow guide missing required contract: {required}")

    if re.search(r"(?m)^status:\s*(?:REQUIRED|DISABLED)\s*$", guide):
        errors.append(
            "Owner Git workflow guide must not duplicate runtime state; "
            "CURRENT_STATE owner_git_workflow_guidance is canonical"
        )

    instruction_files = [
        "AGENTS.md",
        "CLAUDE.md",
        "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md",
        "docs/00-governance/CLAUDE_PROJECT_INSTRUCTIONS.md",
    ]
    for relative in instruction_files:
        path = ROOT / relative
        if path.exists() and "OWNER_GIT_WORKFLOW_GUIDE.md" not in path.read_text(encoding="utf-8"):
            errors.append(f"agent instruction does not load Owner Git workflow guide: {relative}")


def validate_readme_runtime_state_text(errors: list[str], text: str) -> None:
    """Keep README as a stable entry point rather than a second runtime state."""
    if "docs/00-governance/CURRENT_STATE.md" not in text:
        errors.append("README must link to the canonical CURRENT_STATE.md")
    for marker in (
        "项目状态：",
        "当前阶段：",
        "当前活动 Work Package：",
        "当前授权：",
        "INITIAL IMPLEMENTATION",
        "等待总控审查与 Owner 合并",
    ):
        if marker in text:
            errors.append(f"README duplicates or retains stale runtime state: {marker}")


def validate_readme_runtime_state(errors: list[str]) -> None:
    path = ROOT / "README.md"
    if path.exists():
        validate_readme_runtime_state_text(errors, path.read_text(encoding="utf-8"))


def validate_controller_review_standard_text(
    errors: list[str], standard_text: str, instructions_text: str
) -> None:
    required_standard = [
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
    ]
    for required in required_standard:
        if required not in standard_text:
            errors.append(f"Controller Review Standard missing contract: {required}")
    for required in (
        "CONTROLLER_REVIEW_STANDARD.md",
        "start of every Controller task",
        "11+1 review standard",
    ):
        if required not in instructions_text:
            errors.append(
                f"ChatGPT Project Instructions do not load Controller Review Standard: {required}"
            )


def validate_controller_review_standard(errors: list[str]) -> None:
    standard_path = ROOT / CONTROLLER_REVIEW_STANDARD_RELATIVE_PATH
    instructions_path = ROOT / "docs/00-governance/CHATGPT_PROJECT_INSTRUCTIONS.md"
    if not standard_path.exists() or not instructions_path.exists():
        return
    validate_controller_review_standard_text(
        errors,
        standard_path.read_text(encoding="utf-8"),
        instructions_path.read_text(encoding="utf-8"),
    )


def validate_wp_p0_002_traceability_text(errors: list[str], text: str) -> None:
    try:
        rows = list(csv.DictReader(text.splitlines()))
    except csv.Error as error:
        errors.append(f"WP-P0-002 traceability is unreadable: {error}")
        return
    by_id = {row.get("source_id", ""): row for row in rows}
    for source_id, (work_packages, closure, later) in (
        WP_P0_002_TRACEABILITY_CONTRACT.items()
    ):
        row = by_id.get(source_id)
        if row is None:
            errors.append(f"WP-P0-002 traceability row is missing: {source_id}")
            continue
        if row.get("work_package") != work_packages:
            errors.append(
                f"traceability {source_id} work_package must be exactly: {work_packages}"
            )
        if row.get("design_record") != WP_P0_002_RELATIVE_PATH:
            errors.append(
                f"traceability {source_id} must reference the WP-P0-002 canonical record"
            )
        if row.get("status") != "PLANNED":
            errors.append(
                f"traceability {source_id} must remain PLANNED during Design activation"
            )
        notes = row.get("notes", "")
        for token in (closure, later):
            if token not in notes:
                errors.append(
                    f"traceability {source_id} notes missing closure disposition: {token}"
                )


def validate_traceability(errors: list[str]) -> None:
    path = ROOT / "docs/01-requirements/traceability.csv"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8-sig")
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f)
        try:
            header = next(reader)
        except StopIteration:
            errors.append("traceability.csv is empty")
            return
        if header != TRACEABILITY_HEADER:
            errors.append(f"traceability.csv header mismatch: {header}")
        rows = list(reader)
        if not rows:
            errors.append("traceability.csv has no seeded rows")
        ids = [row[0] for row in rows if row]
        if len(ids) != len(set(ids)):
            errors.append("traceability.csv contains duplicate source_id rows")
    validate_wp_p0_002_traceability_text(errors, text)


def validate_completion_state_text(
    errors: list[str],
    current_state_text: str,
    work_package_text: str,
    traceability_text: str,
) -> None:
    """Preserve WP-P0-001 closure across planning and later active-WP states."""
    validate_completed_wp_p0_001_text(errors, work_package_text)

    active_work_package = current_state_metadata_value(
        current_state_text, "active_work_package"
    )
    active_gate = current_state_metadata_value(current_state_text, "active_gate")
    authorization = current_state_metadata_value(current_state_text, "authorization")

    active_objective = h2_section_body(current_state_text, "## Active objective") or ""
    next_action = h2_section_body(current_state_text, "## Next authorized action") or ""

    if active_work_package == "NONE":
        if active_gate != POST_WP_ACTIVE_GATE:
            errors.append(
                f"closed planning state requires CURRENT_STATE active_gate: "
                f"{POST_WP_ACTIVE_GATE}"
            )
        if authorization != "PLANNING_ONLY":
            errors.append("closed planning state requires authorization: PLANNING_ONLY")
        for section_name, section in (
            ("Active objective", active_objective),
            ("Next authorized action", next_action),
        ):
            if "Controller" not in section or "Phase 0 planning" not in section:
                errors.append(
                    f"CURRENT_STATE {section_name} must direct Controller Phase 0 planning"
                )
    elif active_work_package == WP_P0_002_ID:
        if active_gate != DESIGN_ACTIVE_GATE:
            errors.append(
                f"WP-P0-002 Design state requires CURRENT_STATE active_gate: "
                f"{DESIGN_ACTIVE_GATE}"
            )
        if authorization != "DESIGN_ONLY":
            errors.append("WP-P0-002 Design state requires authorization: DESIGN_ONLY")
        for section_name, section in (
            ("Active objective", active_objective),
            ("Next authorized action", next_action),
        ):
            for token in ("Claude", WP_P0_002_ID, "Design"):
                if token not in section:
                    errors.append(
                        f"CURRENT_STATE {section_name} missing Design handoff: {token}"
                    )
        if "Implementation remains prohibited" not in next_action:
            errors.append(
                "CURRENT_STATE Next authorized action must prohibit implementation"
            )
    elif active_work_package is not None:
        errors.append(
            "CURRENT_STATE active_work_package is unsupported or selects a "
            f"completed package: {active_work_package}"
        )

    stale_claims = {
        "WP-P0-001 product implementation has not started": "implementation-not-started claim",
        "C1-C10 implementation artifact has not yet been produced": "missing-artifact claim",
        "READY_FOR_IMPLEMENTATION": "ready-for-implementation state",
    }
    for marker, description in stale_claims.items():
        if marker in current_state_text:
            errors.append(f"CURRENT_STATE retains stale {description}")

    try:
        rows = list(csv.DictReader(traceability_text.splitlines()))
    except csv.Error as error:
        errors.append(f"traceability completion state is unreadable: {error}")
        return
    by_id = {row.get("source_id", ""): row for row in rows}
    for source_id in sorted(COMPLETED_TRACEABILITY_IDS):
        row = by_id.get(source_id)
        if row is None:
            errors.append(f"traceability completion row is missing: {source_id}")
            continue
        if row.get("status") not in COMPLETED_TRACEABILITY_STATES:
            errors.append(
                f"traceability {source_id} status must be VERIFIED or ACTIVE_CONTROL"
            )
        for field in ("code_location", "test_case", "evidence"):
            if not row.get(field, "").strip():
                errors.append(f"traceability {source_id} missing {field}")

    d03 = by_id.get("D-03")
    if d03 is not None:
        if d03.get("status") != "ACTIVE_CONTROL":
            errors.append(
                "traceability D-03 must remain ACTIVE_CONTROL until the PostgreSQL "
                "Task/Outbox Worker is implemented and verified"
            )
        if d03.get("work_package") != D03_WORK_PACKAGES:
            errors.append(
                f"traceability D-03 work_package must be exactly: {D03_WORK_PACKAGES}"
            )
        notes = d03.get("notes", "")
        for token in (
            "Modular Monolith",
            "PostgreSQL Task/Outbox Worker",
            D03_WORKER_WORK_PACKAGE,
            "outside WP-P0-001 scope",
        ):
            if token not in notes:
                errors.append(f"traceability D-03 notes missing disposition: {token}")


def validate_completion_state(errors: list[str]) -> None:
    current_state_path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    work_package_path = ROOT / WP_P0_001_RELATIVE_PATH
    wp_p0_002_path = ROOT / WP_P0_002_RELATIVE_PATH
    traceability_path = ROOT / "docs/01-requirements/traceability.csv"
    backlog_path = ROOT / "docs/03-work-items/BACKLOG-PHASE-0.md"
    if not all(path.exists() for path in (
        current_state_path, work_package_path, traceability_path, backlog_path
    )):
        return
    current_state_text = current_state_path.read_text(encoding="utf-8")
    work_package_text = work_package_path.read_text(encoding="utf-8")
    active = current_state_metadata_value(current_state_text, "active_work_package")
    active_work_package_text = (
        wp_p0_002_path.read_text(encoding="utf-8")
        if active == WP_P0_002_ID and wp_p0_002_path.exists()
        else None
    )
    validate_completion_state_text(
        errors,
        current_state_text,
        work_package_text,
        traceability_path.read_text(encoding="utf-8-sig"),
    )
    validate_backlog_state_text(
        errors,
        current_state_text,
        work_package_text,
        backlog_path.read_text(encoding="utf-8"),
        active_work_package_text,
    )


def git_scan_paths(root: Path = ROOT) -> list[Path]:
    """Return tracked and new candidate paths, excluding ignored build outputs."""
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(detail or "git ls-files failed")
    return [root / item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def validate_common_secrets(errors: list[str]) -> None:
    excluded = {
        ROOT / "scripts/validate_governance.py",
    }
    try:
        paths = git_scan_paths()
    except RuntimeError as error:
        errors.append(f"cannot enumerate tracked files for secret scan: {error}")
        return
    for path in paths:
        if not path.is_file() or path in excluded:
            continue
        if path.suffix.lower() not in SCAN_EXTENSIONS and path.name != ".env":
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for pattern in SECRET_PATTERNS:
            if pattern.search(text):
                errors.append(f"possible secret pattern in {path.relative_to(ROOT)}: {pattern.pattern}")
                break


def main() -> int:
    errors: list[str] = []
    validate_parallel_current_state_paths(
        errors,
        {
            relative
            for relative in PARALLEL_CURRENT_STATE_PATHS
            if (ROOT / relative).exists()
        },
    )
    validate_required_files(errors)
    validate_source_checksums(errors)
    validate_work_package(errors)
    validate_current_state(errors)
    validate_lifecycle_state(errors)
    validate_authorization_state(errors)
    validate_approved_design_state(errors)
    validate_owner_git_workflow_guidance(errors)
    validate_readme_runtime_state(errors)
    validate_controller_review_standard(errors)
    validate_traceability(errors)
    validate_completion_state(errors)
    validate_common_secrets(errors)

    if errors:
        for error in errors:
            fail(error)
        print(f"Governance validation failed with {len(errors)} error(s).", file=sys.stderr)
        return 1

    print("Governance validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
