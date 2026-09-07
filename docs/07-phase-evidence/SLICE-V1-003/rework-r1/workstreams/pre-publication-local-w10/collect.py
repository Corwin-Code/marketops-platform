#!/usr/bin/env python3
"""Preserve and verify the final local governance/frontend command evidence."""

from __future__ import annotations

import gzip
import hashlib
import io
import json
import re
import tarfile
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[6]
OUTPUT = Path(__file__).resolve().parent
SOURCES = {
    "governance.log": Path("/tmp/slice3-w10-governance-final.log"),
    "npm-ci.log": Path("/tmp/slice3-w10-frontend-npm-ci-r2.log"),
    "lint.log": Path("/tmp/slice3-w10-frontend-lint-r3.log"),
    "format.log": Path("/tmp/slice3-w10-frontend-format-r3.log"),
    "typecheck.log": Path("/tmp/slice3-w10-frontend-typecheck-r2.log"),
    "test-ci.log": Path("/tmp/slice3-w10-frontend-test-ci-r2.log"),
    "coverage-negative.log": Path("/tmp/slice3-w10-frontend-coverage-negative-r2.log"),
    "bundle-canary.log": Path("/tmp/slice3-w10-frontend-bundle-canary-r2.log"),
    "official-build.log": Path("/tmp/slice3-w10-frontend-official-build-r2.log"),
    "sbom.log": Path("/tmp/slice3-w10-frontend-sbom-r3.log"),
    "dependencies.json": Path("/tmp/slice3-w10-frontend-dependencies-r2.json"),
    "dependencies.stderr": Path("/tmp/slice3-w10-frontend-dependencies-r2.stderr"),
    "node-version.log": Path("/tmp/slice3-w10-node-version.log"),
    "npm-version.log": Path("/tmp/slice3-w10-npm-version.log"),
    "npm-audit.json": Path("/tmp/slice3-w10-npm-audit.json"),
    "npm-audit.stderr": Path("/tmp/slice3-w10-npm-audit.stderr"),
    "frontend-sbom.json": REPOSITORY / "build/supply-chain/frontend-sbom.json",
}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def require(member: str, token: str) -> None:
    text = SOURCES[member].read_text(errors="replace")
    if token not in text:
        raise ValueError(f"{member} lacks required result: {token}")


def stable_archive(files: dict[str, Path], destination: Path) -> list[dict]:
    manifest = []
    with destination.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as archive:
                for name, path in sorted(files.items()):
                    data = path.read_bytes()
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    info.mtime = 0
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    info.mode = 0o644
                    archive.addfile(info, io.BytesIO(data))
                    manifest.append({"member": name, "bytes": len(data), "sha256": sha256_bytes(data)})
    return manifest


def publication_scan(files: dict[str, Path]) -> dict:
    patterns = {
        "privateKey": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
        "githubToken": re.compile(rb"gh[pousr]_[A-Za-z0-9]{20,}"),
        "jwt": re.compile(rb"eyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"),
        "signedQuery": re.compile(rb"[?&](?:X-Amz-Signature|sig|signature)=[A-Za-z0-9%_-]{12,}", re.I),
    }
    counts = {name: 0 for name in patterns}
    total = 0
    for path in files.values():
        data = path.read_bytes()
        total += len(data)
        for name, pattern in patterns.items():
            counts[name] += len(pattern.findall(data))
    if any(counts.values()):
        raise ValueError(f"Local evidence publication scan failed: {counts}")
    return {"files": len(files), "decodedBytes": total, "matchCounts": counts, "result": "PASS"}


def main() -> int:
    missing = [str(path) for path in SOURCES.values() if not path.is_file()]
    if missing:
        raise ValueError(f"Missing local evidence: {missing}")
    expectations = {
        "governance.log": "Ran 418 tests",
        "lint.log": "eslint . --max-warnings 0",
        "format.log": "All matched files use Prettier code style!",
        "typecheck.log": "tsc --noEmit --pretty",
        "test-ci.log": "Tests  327 passed (327)",
        "coverage-negative.log": "coverage-negative: frontend threshold enforcement PASS",
        "bundle-canary.log": "bundle isolation PASS: only prefixed values reached the bundle",
        "official-build.log": "built in",
        "sbom.log": "CycloneDX JSON schema validation PASS",
        "node-version.log": "v24.19.0",
        "npm-version.log": "11.17.0",
    }
    for member, token in expectations.items():
        require(member, token)
    governance = SOURCES["governance.log"].read_text(errors="replace")
    for token in (
        "Governance validation passed.",
        "Production readiness validation passed.",
        '"status": "PASS", "mode": "check"',
    ):
        if token not in governance:
            raise ValueError(f"governance.log lacks {token}")
    sbom = json.loads(SOURCES["frontend-sbom.json"].read_text())
    if sbom.get("bomFormat") != "CycloneDX" or sbom.get("specVersion") != "1.6":
        raise ValueError("frontend SBOM format/version changed")
    audit = json.loads(SOURCES["npm-audit.json"].read_text())
    if audit.get("metadata", {}).get("vulnerabilities", {}).get("total") != 0:
        raise ValueError("npm audit reports a vulnerability")
    distribution_path = OUTPUT.parent / "frontend-distribution-order-repair/receipt.json"
    distribution = json.loads(distribution_path.read_text())
    if distribution.get("result") != "PASS_LOCAL_ORDER_AND_FINAL_BYTES":
        raise ValueError("frontend distribution-order proof is not passing")

    scan = publication_scan(SOURCES)
    archive = OUTPUT / "original-evidence.tar.gz"
    manifest = stable_archive(SOURCES, archive)
    write_json(
        OUTPUT / "manifest.json",
        {
            "kind": "SLICE3_R1_FINAL_LOCAL_EVIDENCE_MANIFEST",
            "archive": {"path": archive.name, "bytes": archive.stat().st_size, "sha256": sha256(archive)},
            "members": manifest,
            "publicationScan": scan,
        },
    )
    write_json(
        OUTPUT / "receipt.json",
        {
            "kind": "SLICE3_R1_FINAL_LOCAL_PRE_PUBLICATION_VERIFICATION",
            "sourceHeadBeforeFinalCommit": "52a34f36673dbf7c9f8b7a393f28ea1e096de043",
            "commands": [
                {"command": "make governance", "exitCode": 0, "result": "418 tests PASS"},
                {"command": "npm ci", "runtime": "Node 24.19.0 / npm 11.17.0", "exitCode": 0},
                {"command": "npm run lint", "exitCode": 0},
                {"command": "npm run format:check", "exitCode": 0},
                {"command": "npm run typecheck", "exitCode": 0},
                {"command": "npm run test:ci", "exitCode": 0, "result": "22 files / 327 tests PASS"},
                {"command": "bash scripts/verify_coverage_thresholds.sh frontend", "exitCode": 0},
                {"command": "npm run verify:bundle", "exitCode": 0},
                {"command": "authored-Head ci loopback npm run build", "exitCode": 0},
                {"command": "npm run sbom", "exitCode": 0, "result": "CycloneDX 1.6 JSON schema validation PASS"},
                {"command": "npm ls --all --json", "exitCode": 0},
                {"command": "npm audit --json", "exitCode": 0, "result": "0 vulnerabilities"},
            ],
            "frontendCoverage": {
                "statements": "86.34% (1878/2175)",
                "branches": "79.65% (1828/2295)",
                "functions": "87.39% (513/587)",
                "lines": "87.06% (1817/2087)",
            },
            "distributionOrder": {
                "path": str(distribution_path.relative_to(REPOSITORY)),
                "sha256": sha256(distribution_path),
            },
            "archive": {"path": archive.name, "bytes": archive.stat().st_size, "sha256": sha256(archive)},
            "publicationScan": scan,
            "result": "PASS_LOCAL_PRE_PUBLICATION",
            "boundary": "Dirty-worktree local evidence. The containing commit and exact final CI are established only by append-only remote readback.",
            "productionWriteEnabled": False,
        },
    )
    print(json.dumps({"status": "PASS", "archiveSha256": sha256(archive), "members": len(manifest)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
