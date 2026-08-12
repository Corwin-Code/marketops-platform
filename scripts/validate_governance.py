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
    "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md",
    "docs/00-governance/OPEN_QUESTIONS.md",
    "docs/00-governance/QUALITY_GATES.md",
    "docs/01-requirements/baseline-v1.0-cn.md",
    "docs/01-requirements/naming-baseline-cn.md",
    "docs/01-requirements/SHA256SUMS.txt",
    "docs/01-requirements/traceability.csv",
    "docs/03-work-items/WP-P0-001-repository-governance-ci-foundation.md",
    ".github/pull_request_template.md",
    ".github/workflows/governance.yml",
]

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
    if "DESIGN ONLY" not in text:
        errors.append("WP-P0-001 must explicitly state DESIGN ONLY")


def validate_current_state(errors: list[str]) -> None:
    path = ROOT / "docs/00-governance/CURRENT_STATE.md"
    if not path.exists():
        return
    text = path.read_text(encoding="utf-8")
    for required in [
        "lifecycle_state:",
        "active_work_package:",
        "authorization:",
        "production_write_enabled: false",
        "owner_git_workflow_guidance:",
        "owner_git_workflow_guidance_exit:",
    ]:
        if required not in text:
            errors.append(f"CURRENT_STATE missing required field: {required}")


def validate_owner_git_workflow_guidance(errors: list[str]) -> None:
    guide_path = ROOT / "docs/00-governance/OWNER_GIT_WORKFLOW_GUIDE.md"
    if not guide_path.exists():
        return

    guide = guide_path.read_text(encoding="utf-8")
    for required in [
        "status: REQUIRED",
        "activation: every task start",
        "exit_authority: Human Owner explicit confirmation only",
        "sync main",
        "Human Owner merge",
        "local sync/cleanup",
    ]:
        if required not in guide:
            errors.append(f"Owner Git workflow guide missing required contract: {required}")

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
