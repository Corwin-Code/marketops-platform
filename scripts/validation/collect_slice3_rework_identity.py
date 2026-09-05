#!/usr/bin/env python3
"""Record exact R1 Git/source identity without claiming tests or Controller approval."""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = "08ad7da7d9e75b4ddd1c387a22ac0affba9e1430"
REVIEWED = "a0711f1ae430e70ab7ec06917004e9dbfd1fb4eb"
BRANCH = "feat/SLICE-V1-003-advertising-traffic-efficiency"
CONTRACT = "docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md"
IMMUTABLE = {
    "docs/08-handoffs/OWNER-SLICE-V1-003-CODEX-REWORK-AUTHORIZATION-EVIDENCE.md":
        "23a2954d68abeebf87d7710f3ab749af5246cdfcbe4a3029dde73dbb34647a11",
    "docs/08-handoffs/OWNER-SLICE-V1-003-CONTRACT-ACCEPTANCE-EVIDENCE.md":
        "d0532ff25806c5cbc96411aad81db8524671fba8b987a57a41843bff78bcce7d",
    CONTRACT: "1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c",
    "docs/07-phase-evidence/SLICE-V1-003/SLICE-V1-003-FROZEN-FINDING-SET-001.md":
        "15b3c076fc7f1d283a2c7359d9647d91d3ecfccd9b229be1f734f4e7d4ceefc1",
    "docs/07-phase-evidence/SLICE-V1-003/SLICE-V1-003-FROZEN-FINDING-SET-001.json":
        "f4af74f5086772dc70c3ec3cc7aa8808e9441e96109d301b145e70c18f6131a0",
}

PROTECTED_BASE_REFERENCES = [{'path': 'CLAUDE.md', 'blob_sha1': 'e0e7cab013bbd7ff0cee04e66e8ec14740d9a0b5'}, {'path': 'docs/00-governance/CURRENT_STATE.md', 'blob_sha1': '3c0fd9a644801ee35005fbc58035bbc56621ae2a'}, {'path': 'docs/00-governance/AI_OPERATING_MODEL.md', 'blob_sha1': 'c624f1437f9455abccc68b8e6b41af35a93be42d'}, {'path': 'docs/00-governance/EXECUTION_ENVELOPE_POLICY.md', 'blob_sha1': 'cb44f2b2cba640b4d20d2415decd479e4818fa39'}, {'path': 'docs/00-governance/OWNER_DECISIONS_V1.md', 'blob_sha1': '5b728ddb6529fc500d63619382eebe87ceed36d2'}, {'path': 'docs/01-requirements/V1_PRODUCT_CONTRACT.md', 'blob_sha1': 'b3004b21f325f52bc3ab48575065f52256f5b5c5'}, {'path': 'docs/03-work-items/V1_DELIVERY_SLICES.md', 'blob_sha1': '4614c4d82b1ac2ab330ab4bde40aafd8f989cf49'}, {'path': 'docs/02-architecture/V1_SHARED_SPINE.md', 'blob_sha1': 'cb20fd8126f42005a15f338156c1e8a080708200'}, {'path': 'docs/01-requirements/baseline-v1.0-cn.md', 'blob_sha1': 'd3c1789a4fc3b93188203b10ffbb95ef2abeafb2'}, {'path': 'docs/03-work-items/SLICE-V1-001-sku-growth-profit-diagnostic-loop.md', 'blob_sha1': 'efe7055d3184cd109bdacba45d7159eebba8a51b'}, {'path': 'docs/03-work-items/SLICE-V1-002-stockout-availability-risk-and-accountable-response.md', 'blob_sha1': '1caa50f1b33011f7d226c83654835401c00bde1e'}, {'path': 'docs/07-phase-evidence/SLICE-V1-002/CLOSURE-SNAPSHOT-DRAFT.md', 'blob_sha1': 'da35a11b30843603c5defdc10299bcf8b53fbc83'}, {'path': 'docs/08-handoffs/OWNER-SLICE-V1-002-CLOSURE-SNAPSHOT-ACCEPTANCE-EVIDENCE.md', 'blob_sha1': '658458e0421ecf41bdbf5bba1c466c2ec69f571b'}, {'path': 'docs/02-architecture/adr/ADR-0006-contract-governed-vibe-coding.md', 'blob_sha1': 'f33175bcde3888732aa013c2cb0bcff5c0d92e25'}, {'path': 'docs/00-governance/CONTROLLER_REVIEW_STANDARD.md', 'blob_sha1': 'a593926051649b694afc317ae535a240fd305b00'}, {'path': 'docs/00-governance/CHANGE_CONTROL.md', 'blob_sha1': 'befd0032ac446fc745fdf3113063323d9e8ca021'}, {'path': 'docs/00-governance/CLOSURE_SNAPSHOT_STANDARD.md', 'blob_sha1': 'b751732d189f0713114a4cf935a127490bc06cc8'}, {'path': 'docs/01-requirements/traceability.csv', 'blob_sha1': '5043e079ca396d333a20bd585b5a0e5de30a4cac'}, {'path': 'docs/01-requirements/v1-traceability.csv', 'blob_sha1': 'e448249f5b23959d458bd5ebd8fdbd562a593211'}, {'path': 'AGENTS.md', 'blob_sha1': '609f925fed66db1e3479791e1b53214d7a67bf03'}]


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], cwd=ROOT)


def sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def collect(expected_head: str, require_clean: bool) -> dict:
    head = git("rev-parse", "HEAD").decode().strip()
    if head != expected_head:
        raise ValueError(f"Expected Head {expected_head}, observed {head}")
    branch = git("branch", "--show-current").decode().strip()
    if branch != BRANCH:
        raise ValueError("Current branch is outside the named R1 transport scope")
    for ancestor in (BASE, REVIEWED):
        subprocess.run(["git", "merge-base", "--is-ancestor", ancestor, head], cwd=ROOT, check=True)
    status = git("status", "--porcelain=v1", "--untracked-files=all").decode().splitlines()
    if require_clean and status:
        raise ValueError("A clean committed source is required for this receipt")
    immutable = []
    for name, expected in IMMUTABLE.items():
        local = (ROOT / name).read_bytes()
        committed = git("show", f"{head}:{name}")
        if sha256(local) != expected or committed != local:
            raise ValueError(f"Immutable authority changed: {name}")
        immutable.append({"path": name, "sha256": expected,
                          "git_blob": git("rev-parse", f"{head}:{name}").decode().strip()})
    protected_base = []
    for reference in PROTECTED_BASE_REFERENCES:
        name = reference["path"]
        original_blob = git("rev-parse", f"{BASE}:{name}").decode().strip()
        if original_blob != reference["blob_sha1"]:
            raise ValueError(f"Protected Base authority mismatch: {name}")
        original = git("show", f"{BASE}:{name}")
        current_blob = git("rev-parse", f"{head}:{name}").decode().strip()
        protected_base.append({**reference, "base": BASE, "sha256": sha256(original),
                               "current_blob_sha1": current_blob,
                               "current_equals_base": current_blob == original_blob})
    # Contracts, accepted amendments and attributable acceptance/closure evidence
    # are pinned to the already reviewed history. Evolving canonical state and
    # traceability indexes are recorded above without pretending they are frozen.
    historical_names = git("ls-tree", "-r", "--name-only", REVIEWED, "--",
                           "docs/03-work-items", "docs/08-handoffs").decode().splitlines()
    frozen_names = [name for name in historical_names
                    if re.search(r"/SLICE-V1-00[12](?:-|\.)", name)
                    or ("ACCEPTANCE-EVIDENCE" in name and "SLICE-V1-00" in name)]
    frozen_names += ["docs/01-requirements/V1_PRODUCT_CONTRACT.md",
                     "docs/00-governance/OWNER_DECISIONS_V1.md",
                     "docs/07-phase-evidence/SLICE-V1-002/CLOSURE-SNAPSHOT-DRAFT.md"]
    frozen_history = []
    for name in sorted(set(frozen_names)):
        original = git("show", f"{REVIEWED}:{name}")
        if git("show", f"{head}:{name}") != original or (ROOT / name).read_bytes() != original:
            raise ValueError(f"Immutable accepted history changed: {name}")
        frozen_history.append({"path": name, "reference_commit": REVIEWED,
                               "sha256": sha256(original)})
    migrations = []
    directory = ROOT / "backend/marketops-server/src/main/resources/db/migration"
    for path in sorted(directory.glob("V*.sql")):
        version = int(re.match(r"V(\d+)__", path.name).group(1))
        relative = path.relative_to(ROOT).as_posix()
        content = path.read_bytes()
        if content != git("show", f"{head}:{relative}"):
            raise ValueError(f"Migration is not committed at the measured Head: {relative}")
        if version <= 35 and any(git("show", f"{ref}:{relative}") != content for ref in (BASE, REVIEWED)):
            raise ValueError(f"Protected migration differs from Base/reviewed Head: {relative}")
        migrations.append({"version": version, "path": relative, "bytes": len(content),
                           "sha256": sha256(content), "protected_prefix": version <= 35})
    if [item["version"] for item in migrations] != list(range(1, len(migrations) + 1)):
        raise ValueError("Migration inventory has a gap or duplicate")
    # This digest proves whether a later evidence-only commit changed runtime,
    # build, CI or test inputs. It does not equate two different Git Heads.
    inputs = git("ls-tree", "-r", "--full-tree", head, "--", "backend", "frontend", "infra",
                 "scripts", "tests", ".github", "Makefile", "bootstrap-manifest.json")
    return {
        "document_type": "SLICE_V1_003_R1_IDENTITY_RECEIPT",
        "collected_at": datetime.now(timezone.utc).isoformat(),
        "repository": "Corwin-Code/marketops-platform", "branch": branch,
        "base": BASE, "base_tree": git("rev-parse", f"{BASE}^{{tree}}").decode().strip(),
        "reviewed_head": REVIEWED, "measured_head": head,
        "measured_tree": git("rev-parse", f"{head}^{{tree}}").decode().strip(),
        "measured_parents": git("show", "-s", "--format=%P", head).decode().strip().split(),
        "runtime_build_test_ci_tree_listing_sha256": sha256(inputs),
        "working_tree_clean": not status, "working_tree_status": status,
        "immutable_authorities": immutable, "protected_base_authorities": protected_base,
        "preserved_accepted_history": frozen_history, "migrations": migrations,
        "production_write_enabled": False, "controller_verdict": "NOT_ISSUED_BY_CODEX",
        "test_result": "NOT_ASSERTED_BY_IDENTITY_COLLECTION",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expect-head", required=True)
    parser.add_argument("--require-clean", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    receipt = collect(arguments.expect_head, arguments.require_clean)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Identity verified for {receipt['measured_head']}; {len(receipt['migrations'])} migrations recorded")


if __name__ == "__main__":
    main()
