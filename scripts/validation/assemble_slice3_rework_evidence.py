#!/usr/bin/env python3
"""Assemble current R1 traceability without promoting mappings to test results.

Accepted authorities are read-only inputs. This command validates the complete
criterion set and writes only the additive R1 progress matrices. It deliberately
cannot produce a VERIFIED or Controller-approved verdict from source references.
"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = Path("docs/07-phase-evidence/SLICE-V1-003/rework-r1")
CONTRACT = Path("docs/03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md")
FROZEN = Path("docs/07-phase-evidence/SLICE-V1-003/SLICE-V1-003-FROZEN-FINDING-SET-001.json")
SHARDS = (
    "command-controls-traceability.json",
    "command-controls-additional-ac.json",
    "facts-outcome-traceability.json",
    "console-orchestration-traceability.json",
    "human-decisions-traceability.json",
    "governance-traceability.json",
)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path: Path):
    return json.loads((ROOT / path).read_text())


def references(row: dict, keys: tuple[str, ...]) -> list[dict]:
    result = []
    for key in keys:
        for item in row.get(key, []):
            if isinstance(item, dict) and item.get("path"):
                relative = Path(item["path"])
                path = ROOT / relative
                if relative.is_absolute() or ".." in relative.parts or not path.is_file():
                    raise ValueError(f"Invalid evidence path: {relative}")
                method = item.get("method")
                if method:
                    content = path.read_text()
                    pattern = (r"[\"']" + re.escape(method) + r"[\"']"
                               if path.suffix in (".ts", ".tsx", ".js")
                               else r"\b" + re.escape(method) + r"\s*\(")
                    if not re.search(pattern, content):
                        raise ValueError(f"Missing test method: {relative}#{method}")
                result.append({"path": relative.as_posix(), "current_sha256": digest(path),
                               **({"method": method} if method else {})})
    return result


def assemble() -> None:
    if digest(ROOT / CONTRACT) != "1606a844934c49a9e67dc0a1a15d49f4003913efc678bae94403c3c29ecb811c":
        raise ValueError("Accepted Contract identity changed")
    if digest(ROOT / FROZEN) != "f4af74f5086772dc70c3ec3cc7aa8808e9441e96109d301b145e70c18f6131a0":
        raise ValueError("Frozen Finding Set identity changed")
    criteria = dict(re.findall(r"^- `(S3-AC-\d{3})` — (.+)$", (ROOT / CONTRACT).read_text(), re.M))
    if set(criteria) != {f"S3-AC-{n:03}" for n in range(1, 201)}:
        raise ValueError("Accepted criteria are incomplete")
    frozen = {row["id"]: row for row in read(FROZEN)["findings"]}
    ac_matrix = read(BASE / "S3-AC-REWORK-STATUS.json")
    finding_matrix = read(BASE / "FINDING-CLOSURE-MATRIX.json")
    mappings = {identity: [] for identity in criteria}
    finding_shards = {}
    for name in SHARDS:
        relative = BASE / "workstreams" / name
        data = read(relative)
        for row in data.get("acceptanceCriteria", data.get("criteria", [])):
            identity = row["id"]
            if identity not in criteria:
                raise ValueError(f"Unknown criterion in {name}: {identity}")
            claimed_text = row.get("criterion", row.get("acceptedText"))
            if claimed_text is not None and claimed_text != criteria[identity]:
                raise ValueError(f"Altered accepted criterion in {name}: {identity}")
            sources = references(row, ("sourceReferences", "sourceEvidence", "sources"))
            tests = references(row, ("testReferences", "testEvidence", "tests", "positiveTests", "negativeTests"))
            mappings[identity].append({
                "shard": relative.as_posix(), "shard_sha256": digest(ROOT / relative),
                "row_id": identity, "reported_status": row.get("status"),
                "source_references": sources, "test_references": tests,
                "evidence_limit": row.get("evidenceLimits", row.get("remainingVerification", row.get("evidenceLimit"))),
            })
        for row in data.get("findings", []):
            identity = row["id"]
            if identity not in frozen or identity in finding_shards:
                raise ValueError(f"Unknown/duplicate finding in {name}: {identity}")
            finding_shards[identity] = (relative, row)
    missing = [identity for identity, rows in mappings.items() if not rows]
    if missing:
        raise ValueError(f"Unmapped accepted criteria: {missing}")
    for row in ac_matrix["entries"]:
        identity = row["id"]
        if row["criterion"] != criteria[identity]:
            raise ValueError(f"Altered criterion in central matrix: {identity}")
        row.update(status="REWORK_EVIDENCE_ASSEMBLED_VERIFICATION_PENDING", engineering_evidence=mappings[identity],
                   finding_ids=[fid for fid, finding in frozen.items() if identity in finding["criteria"]],
                   notes="Mappings record inspected source and named tests. Full measured-source execution, precise per-criterion assessment and final remote CI remain required. Mapping completeness alone proves no business result.")
    for row in finding_matrix["entries"]:
        identity = row["id"]
        original = frozen[identity]
        row["reproduction"] = original["observed"]
        row["required_rework"] = original["required_rework"]
        row["required_verification"] = original["verification"]
        row["acceptance_criteria"] = original["criteria"]
        if identity in finding_shards:
            relative, shard = finding_shards[identity]
            row["root_cause"] = shard.get("rootCause")
            row["implemented_behavior"] = shard.get("implementedBehavior")
            row["same_class_scan"] = shard.get("sameClassScan", [])
            row["transitive_impact"] = shard.get("transitiveImpact", [])
            row["changed_files"] = shard.get("changedFiles", shard.get("sourceEvidence", []))
            row["tests"] = {"positive": shard.get("positiveTests", []), "negative": shard.get("negativeTests", [])}
            row["evidence"] = [{"path": relative.as_posix(), "sha256": digest(ROOT / relative), "row_id": identity}]
            row["remaining_limitations"] = shard.get("evidenceLimits", shard.get("pending", []))
        row["status"] = "REWORK_EVIDENCE_ASSEMBLED_VERIFICATION_PENDING"
        row["closed_by_codex_engineering_assessment"] = False
        row["controller_verdict"] = "PENDING_INDEPENDENT_REVIEW"
    for name, data in (("S3-AC-REWORK-STATUS.json", ac_matrix), ("FINDING-CLOSURE-MATRIX.json", finding_matrix)):
        data["assembly_boundary"] = "Source mappings only; no automatic PASS, engineering closure or Controller verdict."
        data["closure_claim_made"] = False
        data["engineering_closure_claim_made"] = False
        data["production_write_enabled"] = False
        (ROOT / BASE / name).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    print(f"Assembled {len(criteria)} exact criteria and {len(frozen)} exact findings; all remain verification-pending.")


if __name__ == "__main__":
    assemble()
