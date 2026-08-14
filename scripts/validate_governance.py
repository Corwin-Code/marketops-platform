#!/usr/bin/env python3
from __future__ import annotations

import csv
import hashlib
import re
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
    "docs/00-governance/OPEN_QUESTIONS.md",
    "docs/00-governance/QUALITY_GATES.md",
    "docs/01-requirements/baseline-v1.0-cn.md",
    "docs/01-requirements/naming-baseline-cn.md",
    "docs/01-requirements/SHA256SUMS.txt",
    "docs/01-requirements/traceability.csv",
    "docs/02-architecture/designs/WP-P0-001-foundation-design.md",
    "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md",
    ".github/pull_request_template.md",
    ".github/workflows/governance.yml",
    "tests/test_validate_governance.py",
]

AUTHORIZATION_ALLOWED_STATES = {"DESIGN_ONLY", "APPROVED_FOR_IMPLEMENTATION"}
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
CONTROLLER_VERDICT_BY_AUTHORIZATION = {
    "DESIGN_ONLY": "AUTHORIZED_TO_START_DESIGN",
    "APPROVED_FOR_IMPLEMENTATION": "APPROVED_FOR_IMPLEMENTATION",
}
OWNER_GUIDANCE_ALLOWED_STATES = {"REQUIRED", "DISABLED"}
OWNER_GUIDANCE_EXIT_AUTHORITY = "HUMAN_OWNER_EXPLICIT_CONFIRMATION"
OWNER_DELEGATION_ALLOWED_STATES = {"ACTIVE", "INACTIVE"}
OWNER_DELEGATION_ALLOWED_EXECUTORS = {"CODEX", "NONE"}
OWNER_DELEGATION_SCOPE = "PR_READY_AND_MERGE_AFTER_ALL_GATES"
OWNER_DELEGATION_INACTIVE_SCOPE = "NONE"
OWNER_DELEGATION_EXIT_AUTHORITY = "HUMAN_OWNER_EXPLICIT_REVOCATION"

WP_REQUIRED_HEADINGS = [
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
    wp = ROOT / "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md"
    if not wp.exists():
        return
    text = wp.read_text(encoding="utf-8")
    for heading in WP_REQUIRED_HEADINGS:
        if heading not in text:
            errors.append(f"WP-P0-001 missing heading: {heading}")


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


def work_package_controller_verdict(text: str) -> str | None:
    section = re.search(
        r"(?ms)^## 10\. Controller Gate\s*$\n(?P<body>.*?)(?=^## \d+\.|\Z)",
        text,
    )
    if section is None:
        return None
    matches = re.findall(
        r"(?ms)^Current verdict:\s*$\n\s*```text\s*$\n"
        r"\s*([A-Z][A-Z0-9_]*)\s*$\n```\s*$",
        section.group("body"),
    )
    return matches[0] if len(matches) == 1 else None


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
    wp_authorization = work_package_metadata_value(work_package_text, "Authorization")
    if wp_authorization != "APPROVED_FOR_IMPLEMENTATION":
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
) -> None:
    current_authorization = current_state_metadata_value(
        current_state_text,
        "authorization",
    )
    wp_authorization = work_package_metadata_value(work_package_text, "Authorization")
    controller_verdict = work_package_controller_verdict(work_package_text)

    if current_authorization is None:
        errors.append("CURRENT_STATE authorization metadata is missing or duplicated")
    elif current_authorization not in AUTHORIZATION_ALLOWED_STATES:
        errors.append(
            "CURRENT_STATE authorization must be exactly one of: "
            + ", ".join(sorted(AUTHORIZATION_ALLOWED_STATES))
        )

    if wp_authorization is None:
        errors.append("WP-P0-001 Authorization metadata is missing or duplicated")
    elif wp_authorization not in AUTHORIZATION_ALLOWED_STATES:
        errors.append(
            "WP-P0-001 Authorization must be exactly one of: "
            + ", ".join(sorted(AUTHORIZATION_ALLOWED_STATES))
        )

    allowed_controller_verdicts = set(CONTROLLER_VERDICT_BY_AUTHORIZATION.values())
    if controller_verdict is None:
        errors.append("WP-P0-001 current Controller verdict is missing or duplicated")
    elif controller_verdict not in allowed_controller_verdicts:
        errors.append(
            "WP-P0-001 current Controller verdict must be exactly one of: "
            + ", ".join(sorted(allowed_controller_verdicts))
        )

    if (
        current_authorization in AUTHORIZATION_ALLOWED_STATES
        and wp_authorization in AUTHORIZATION_ALLOWED_STATES
        and current_authorization != wp_authorization
    ):
        errors.append(
            "authorization mismatch: CURRENT_STATE "
            f"{current_authorization} != WP-P0-001 {wp_authorization}"
        )

    if (
        wp_authorization in AUTHORIZATION_ALLOWED_STATES
        and controller_verdict in allowed_controller_verdicts
        and controller_verdict != CONTROLLER_VERDICT_BY_AUTHORIZATION[wp_authorization]
    ):
        errors.append(
            "controller gate mismatch: WP-P0-001 Authorization "
            f"{wp_authorization} requires verdict "
            f"{CONTROLLER_VERDICT_BY_AUTHORIZATION[wp_authorization]}, got "
            f"{controller_verdict}"
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
    work_package_path = (
        ROOT / "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md"
    )
    if not current_state_path.exists() or not work_package_path.exists():
        return
    validate_authorization_state_text(
        errors,
        current_state_path.read_text(encoding="utf-8"),
        work_package_path.read_text(encoding="utf-8"),
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


def validate_traceability(errors: list[str]) -> None:
    path = ROOT / "docs/01-requirements/traceability.csv"
    if not path.exists():
        return
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


def validate_common_secrets(errors: list[str]) -> None:
    excluded = {
        ROOT / "scripts/validate_governance.py",
    }
    for path in ROOT.rglob("*"):
        if not path.is_file() or path in excluded:
            continue
        if ".git" in path.parts:
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
    validate_required_files(errors)
    validate_source_checksums(errors)
    validate_work_package(errors)
    validate_current_state(errors)
    validate_lifecycle_state(errors)
    validate_authorization_state(errors)
    validate_approved_design_state(errors)
    validate_owner_git_workflow_guidance(errors)
    validate_traceability(errors)
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
