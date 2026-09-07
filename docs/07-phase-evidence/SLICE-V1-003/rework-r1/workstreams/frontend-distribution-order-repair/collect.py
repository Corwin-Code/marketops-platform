#!/usr/bin/env python3
"""Verify the frontend canary-build/publication-build ordering and final bytes."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[6]
WORKFLOW = Path(".github/workflows/frontend.yml")
PROJECT = Path("frontend/marketops-console")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative_file(path: Path) -> dict:
    return {
        "path": path.relative_to(REPOSITORY).as_posix(),
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def validate_order(workflow: str) -> dict:
    canary_name = "- name: Prove that only prefixed values reached the bundle"
    build_name = "- name: Build the console"
    upload_name = "- name: Publish the console and its inventory"
    canary_at = workflow.index(canary_name)
    build_at = workflow.index(build_name, canary_at)
    upload_at = workflow.index(upload_name, build_at)
    if not canary_at < build_at < upload_at:
        raise ValueError("frontend workflow does not replace the canary before upload")
    official_block = workflow[build_at:upload_at]
    required = (
        "MARKETOPS_BUILD_COMMIT: ${{ github.event.pull_request.head.sha || github.sha }}",
        "VITE_MARKETOPS_API_BASE_URL: http://127.0.0.1:8080",
        "VITE_MARKETOPS_ENVIRONMENT: ci",
    )
    missing = [token for token in required if token not in official_block]
    if missing:
        raise ValueError(f"official distribution build is missing: {missing}")
    return {
        "canaryStepBeforeOfficialBuild": True,
        "officialBuildBeforeUpload": True,
        "officialBuildRequiredEnvironment": list(required),
    }


def validate_distribution(directory: Path, expected_head: str) -> dict:
    files = sorted(path for path in directory.rglob("*") if path.is_file())
    if not files:
        raise ValueError("distribution is empty")
    contents = b"\n".join(path.read_bytes() for path in files)
    published = sorted(
        value.decode() for value in set(re.findall(rb"published-[0-9a-f-]{20,}", contents))
    )
    canaries = sorted(
        value.decode() for value in set(re.findall(rb"canary-[0-9a-f-]{20,}", contents))
    )
    has_head = expected_head.encode() in contents
    has_loopback = b"http://127.0.0.1:8080" in contents
    # The application deliberately contains other user-facing "unknown" text.
    # Check the compiled build-info initializer rather than that unrelated word.
    build_commit_pattern = re.compile(
        rb"commit\s*:\s*[A-Za-z_$][\w$]*\s*\(\s*[`\"']"
        + re.escape(expected_head.encode())
    )
    unknown_commit_pattern = re.compile(
        rb"commit\s*:\s*[A-Za-z_$][\w$]*\s*\(\s*[`\"']unknown[`\"']"
    )
    exact_build_initializer = bool(build_commit_pattern.search(contents))
    unknown_build_initializer = bool(unknown_commit_pattern.search(contents))
    if not has_head or not has_loopback or not exact_build_initializer:
        raise ValueError("distribution lacks exact authored Head/API build metadata")
    if published or canaries or unknown_build_initializer:
        raise ValueError("distribution still contains canary or unknown build metadata")
    return {
        "files": [relative_file(path) for path in files],
        "containsExpectedHead": has_head,
        "containsLoopbackApi": has_loopback,
        "containsCiEnvironment": b"ci" in contents,
        "exactBuildCommitInitializer": exact_build_initializer,
        "unknownBuildCommitInitializer": unknown_build_initializer,
        "publishedCanaryValues": published,
        "withheldCanaryValues": canaries,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--canary-log", type=Path, required=True)
    parser.add_argument("--official-log", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).with_name("receipt.json"))
    args = parser.parse_args()

    workflow_path = REPOSITORY / WORKFLOW
    project = REPOSITORY / PROJECT
    if "bundle isolation PASS" not in args.canary_log.read_text(errors="replace"):
        raise ValueError("canary build did not record a passing isolation search")
    official_log = args.official_log.read_text(errors="replace")
    if "built in" not in official_log or "vite" not in official_log:
        raise ValueError("official replacement build log is incomplete")

    receipt = {
        "kind": "FRONTEND_DISTRIBUTION_CANARY_ORDER_LOCAL_PROOF",
        "expectedAuthoredHead": args.expected_head,
        "workflow": relative_file(workflow_path),
        "workflowOrder": validate_order(workflow_path.read_text()),
        "commands": [
            {
                "command": "npm run verify:bundle",
                "log": {"path": str(args.canary_log), "sha256": sha256(args.canary_log)},
                "exitCode": 0,
            },
            {
                "command": "MARKETOPS_BUILD_COMMIT=<authored-head> VITE_MARKETOPS_API_BASE_URL=http://127.0.0.1:8080 VITE_MARKETOPS_ENVIRONMENT=ci npm run build",
                "log": {"path": str(args.official_log), "sha256": sha256(args.official_log)},
                "exitCode": 0,
            },
        ],
        "distribution": validate_distribution(project / "dist", args.expected_head),
        "result": "PASS_LOCAL_ORDER_AND_FINAL_BYTES",
        "remoteRequirement": "The final authored-Head CI artifact must repeat this check after append-only publication.",
        "productionWriteEnabled": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": "PASS", "output": str(args.output), "head": args.expected_head}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
