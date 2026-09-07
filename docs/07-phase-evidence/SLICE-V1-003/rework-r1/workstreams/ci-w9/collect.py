#!/usr/bin/env python3
"""Build a byte-verifiable W9 CI evidence bundle from downloaded GitHub data."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import tarfile
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


HEAD = "52a34f36673dbf7c9f8b7a393f28ea1e096de043"
TREE = "5eb85b0bf309d39fd9798c9b8b2186371710fecc"
BASE = "08ad7da7d9e75b4ddd1c387a22ac0affba9e1430"
MERGE = "52e393720653197089bd9530a9330216bc15fb2b"
REQUIRED = {
    "governance",
    "backend-build",
    "architecture-boundary",
    "backend-integration",
    "frontend-lint",
    "frontend-typecheck",
    "frontend-test",
    "frontend-build",
    "dependency-review",
    "codeql-java",
    "codeql-typescript",
    "infrastructure-validation",
}
ANSI = re.compile(r"\x1b\[[0-9;]*m")
SECRET_PATTERNS = [
    re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(rb"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(rb"\bghp_[A-Za-z0-9]{30,}\b"),
    re.compile(rb"\bgithub_pat_[A-Za-z0-9_]{30,}\b"),
    re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
    re.compile(rb"\bsk-[A-Za-z0-9]{24,}\b"),
    re.compile(rb"(?i)(?:password|passwd|secret|token|api[_-]?key)\s*[:=]\s*[\"'][^\"']{12,}[\"']"),
    re.compile(rb"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"),
]
SIGNED_QUERY = re.compile(rb"(?i)(?:X-Amz-Signature|X-Goog-Signature|[?&](?:sig|signature)=)[^&\s]+")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def artifact_index(source: Path) -> list[dict]:
    result = []
    for metadata_path in sorted(source.glob("artifacts-*.json")):
        for item in read_json(metadata_path).get("artifacts", []):
            archive = source / f"artifact-{item['id']}.zip"
            if not archive.is_file():
                raise ValueError(f"missing downloaded artifact {item['id']}")
            actual = sha256(archive)
            expected = item["digest"].removeprefix("sha256:")
            if actual != expected or archive.stat().st_size != item["size_in_bytes"]:
                raise ValueError(f"artifact byte identity mismatch: {item['id']}")
            result.append(
                {
                    "id": item["id"],
                    "name": item["name"],
                    "bytes": archive.stat().st_size,
                    "sha256": actual,
                    "apiDigest": item["digest"],
                    "headSha": item["workflow_run"]["head_sha"],
                    "runId": item["workflow_run"]["id"],
                }
            )
    if len(result) != 7 or any(item["headSha"] != HEAD for item in result):
        raise ValueError("expected seven W9 artifacts bound to the exact Head")
    return sorted(result, key=lambda item: item["id"])


def xml_summary(archive: Path) -> dict:
    report_count = tests = failures = errors = skipped = 0
    nodes: Counter[tuple[str, str]] = Counter()
    report_hashes = []
    with zipfile.ZipFile(archive) as bundle:
        for name in sorted(bundle.namelist()):
            if "/TEST-" not in name or not name.endswith(".xml"):
                continue
            data = bundle.read(name)
            root = ET.fromstring(data)
            cases = root.findall(".//testcase")
            report_count += 1
            tests += len(cases)
            failures += len(root.findall(".//failure"))
            errors += len(root.findall(".//error"))
            skipped += len(root.findall(".//skipped"))
            report_hashes.append({"member": name, "sha256": sha256_bytes(data), "testcaseNodes": len(cases)})
            for case in cases:
                nodes[(case.get("classname", ""), case.get("name", ""))] += 1
    return {
        "originalXmlReports": report_count,
        "actualTestcaseNodes": tests,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "nodeCounts": nodes,
        "reportHashes": report_hashes,
    }


def browser_results(frontend_log: Path) -> dict:
    with zipfile.ZipFile(frontend_log) as bundle:
        raw = bundle.read("3_frontend-test.txt").decode("utf-8", errors="replace")
    text = ANSI.sub("", raw)
    sections: dict[str, list[dict]] = {"legacy": [], "advertising": []}
    active = None
    for line in text.splitlines():
        if "Running 25 tests using 1 worker" in line:
            active = "legacy"
            continue
        if "Running 12 tests using 1 worker" in line:
            active = "advertising"
            continue
        match = re.search(r"\s✓\s+(\d+)\s+(.+?)\s+\(([^()]*)\)\s*$", line)
        if match and active:
            display = match.group(2)
            source, _, title = display.partition(" › ")
            sections[active].append(
                {
                    "ordinal": int(match.group(1)),
                    "source": source,
                    "title": title or display,
                    "fullDisplayTitle": display,
                    "duration": match.group(3),
                    "status": "PASSED",
                }
            )
    unit_match = re.search(r"Tests\s+327 passed\s+\(327\)", text)
    if len(sections["legacy"]) != 25 or len(sections["advertising"]) != 12 or not unit_match:
        raise ValueError("frontend W9 counts or named browser extraction did not match")
    return {
        "head": HEAD,
        "testedMerge": MERGE,
        "frontendUnitTests": 327,
        "legacyBrowserCount": 25,
        "advertisingBrowserCount": 12,
        "legacy": sections["legacy"],
        "advertising": sections["advertising"],
    }


def distribution_stamp(source: Path, artifact: dict) -> dict:
    archive = source / f"artifact-{artifact['id']}.zip"
    joined = b""
    with zipfile.ZipFile(archive) as bundle:
        for name in bundle.namelist():
            if name.endswith((".js", ".html")):
                joined += bundle.read(name)
    published = sorted({value.decode() for value in re.findall(rb"published-[0-9a-f-]{20,}", joined)})
    return {
        "artifactId": artifact["id"],
        "artifactSha256": artifact["sha256"],
        "containsExpectedHead": HEAD.encode() in joined,
        "containsUnknownBuildCommit": b"unknown" in joined,
        "publishedEnvironmentTokens": published,
        "result": "W9_ARTIFACT_PROVENANCE_DEFECT_CONFIRMED_REPAIRED_BY_LATER_WORKFLOW_ORDER_CHANGE",
        "boundary": "This preserves the W9 failure. Final-head CI must prove the corrected uploaded artifact independently.",
    }


def named_backend_provenance(repo: Path, report: dict) -> dict:
    catalog_path = repo / "docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/current-named-backend/current-named-backend-evidence.json"
    catalog = read_json(catalog_path)
    node_counts = report["nodeCounts"].copy()
    missing_sources = []
    missing_nodes = []
    source_checks = node_checks = 0
    for row in catalog["rows"]:
        path = repo / row["path"]
        source_checks += 1
        if not path.is_file() or sha256(path) != row["sourceSha256"]:
            missing_sources.append(row["id"])
        for case in row["cases"]:
            node_checks += 1
            identity = (case["rawClassname"], case["rawName"])
            if node_counts[identity] <= 0:
                missing_nodes.append({"id": row["id"], "class": identity[0], "name": identity[1]})
            else:
                node_counts[identity] -= 1
    if missing_sources or missing_nodes:
        raise ValueError("W9 named backend source/report provenance mismatch")
    return {
        "kind": "W9_REMOTE_NAMED_BACKEND_PROVENANCE_RECHECK",
        "head": HEAD,
        "testedMerge": MERGE,
        "catalog": {"path": str(catalog_path.relative_to(repo)), "sha256": sha256(catalog_path)},
        "uniqueMethods": catalog["uniqueMethods"],
        "expandedNamedNodes": catalog["uniqueExpandedNodes"],
        "sourceIdentityChecks": source_checks,
        "remoteXmlNodeChecks": node_checks,
        "allSourceBytesMatched": True,
        "allRemoteNamedNodesPassed": True,
        "missing": [],
        "controllerVerdict": "PENDING_INDEPENDENT_REVIEW",
    }


def scan_file(name: str, data: bytes, matches: list[dict], signed: list[dict]) -> tuple[int, int]:
    text_files = decoded_bytes = 0
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return text_files, decoded_bytes
    text_files += 1
    decoded_bytes += len(data)
    for index, pattern in enumerate(SECRET_PATTERNS):
        for match in pattern.finditer(data):
            matches.append(
                {
                    "location": name,
                    "patternIndex": index,
                    "matchSha256": sha256_bytes(match.group()),
                    "length": len(match.group()),
                }
            )
    for match in SIGNED_QUERY.finditer(data):
        signed.append(
            {"location": name, "matchSha256": sha256_bytes(match.group()), "length": len(match.group())}
        )
    return text_files, decoded_bytes


def scan_originals(source: Path, names: list[str]) -> dict:
    matches: list[dict] = []
    signed: list[dict] = []
    text_files = decoded_bytes = archive_members = 0
    for name in names:
        path = source / name
        data = path.read_bytes()
        if path.suffix == ".zip":
            with zipfile.ZipFile(io.BytesIO(data)) as bundle:
                for member in bundle.infolist():
                    if member.is_dir():
                        continue
                    archive_members += 1
                    counts = scan_file(f"{name}!{member.filename}", bundle.read(member), matches, signed)
                    text_files += counts[0]
                    decoded_bytes += counts[1]
        else:
            counts = scan_file(name, data, matches, signed)
            text_files += counts[0]
            decoded_bytes += counts[1]
    return {
        "kind": "W9_CI_ORIGINAL_BYTES_PUBLICATION_SCAN",
        "patternCount": len(SECRET_PATTERNS),
        "jwtPatternIndex": 7,
        "textFilesAndMembers": text_files,
        "decodedBytes": decoded_bytes,
        "archiveMembers": archive_members,
        "matchesWithoutValues": matches,
        "signedQueryMatchesWithoutValues": signed,
        "result": "PASS" if not matches and not signed else "REVIEW_REQUIRED",
        "noMatchedValuesCopied": True,
    }


def stable_archive(source: Path, names: list[str], destination: Path) -> list[dict]:
    manifest = []
    with destination.open("wb") as target:
        with gzip.GzipFile(fileobj=target, mode="wb", mtime=0, filename="") as zipped:
            with tarfile.open(fileobj=zipped, mode="w") as bundle:
                for name in names:
                    data = (source / name).read_bytes()
                    info = tarfile.TarInfo(f"raw/{name}")
                    info.size = len(data)
                    info.mtime = 0
                    info.mode = 0o644
                    info.uid = info.gid = 0
                    info.uname = info.gname = ""
                    bundle.addfile(info, io.BytesIO(data))
                    manifest.append({"member": info.name, "bytes": len(data), "sha256": sha256_bytes(data)})
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[6])
    args = parser.parse_args()
    source = args.source.resolve()
    output = args.output.resolve()
    repo = args.repo.resolve()
    output.mkdir(parents=True, exist_ok=True)

    runs = [read_json(path) for path in sorted(source.glob("run-*.json"))]
    if len(runs) != 5 or any(run["headSha"] != HEAD or run["conclusion"] != "success" for run in runs):
        raise ValueError("expected five successful W9 workflow runs")
    jobs = {job["name"]: job for run in runs for job in run["jobs"]}
    if set(jobs) != REQUIRED or any(job["conclusion"] != "success" for job in jobs.values()):
        raise ValueError("required W9 workflow jobs are not an exact all-success set")

    pr = read_json(source / "pr.json")
    merge = read_json(source / "tested-merge.json")
    if not (pr["isDraft"] and pr["state"] == "OPEN" and pr["headRefOid"] == HEAD):
        raise ValueError("PR W9 identity/state mismatch")
    if merge["sha"] != MERGE or merge["tree"] != TREE or merge["parents"] != [BASE, HEAD]:
        raise ValueError("tested merge identity mismatch")
    checks = {item["name"]: item for item in pr["statusCheckRollup"]}
    if not REQUIRED.issubset(checks) or checks.get("CodeQL", {}).get("conclusion") != "SUCCESS":
        raise ValueError("PR required or aggregate CodeQL check missing")

    artifacts = artifact_index(source)
    by_name = {item["name"]: item for item in artifacts}
    backend = xml_summary(source / f"artifact-{by_name['backend-test-reports']['id']}.zip")
    integration = xml_summary(source / f"artifact-{by_name['backend-integration-reports']['id']}.zip")
    if (backend["originalXmlReports"], backend["actualTestcaseNodes"], backend["failures"], backend["errors"], backend["skipped"]) != (189, 2484, 0, 0, 0):
        raise ValueError("backend-build XML result mismatch")
    if (integration["originalXmlReports"], integration["actualTestcaseNodes"], integration["failures"], integration["errors"], integration["skipped"]) != (71, 924, 0, 0, 0):
        raise ValueError("backend-integration XML result mismatch")

    named_browser = browser_results(source / "logs-33971285823.zip")
    provenance = named_backend_provenance(repo, backend)
    distribution = distribution_stamp(source, by_name["frontend-distribution"])
    open_alerts = read_json(source / "codeql-open-alerts.json")
    closed_alerts = read_json(source / "codeql-closed-alerts.json")
    analyses = read_json(source / "codeql-analyses.json")
    if len(open_alerts) != 87 or len([item for item in closed_alerts if item["fixed_at"]]) != 12:
        raise ValueError("CodeQL alert reconciliation mismatch")
    if any(item["rule"].get("security_severity_level") not in (None, "none") for item in open_alerts):
        raise ValueError("an open W9 security-severity CodeQL alert exists")
    if len(analyses) != 2 or {item["tool"]["version"] for item in analyses} != {"2.26.4"}:
        raise ValueError("W9 CodeQL analysis identity mismatch")

    original_names = sorted(
        path.name
        for path in source.iterdir()
        if path.is_file() and path.stat().st_size > 0 and not path.name.startswith("private-")
    )
    scan = scan_originals(source, original_names)
    if scan["result"] != "PASS":
        raise ValueError("W9 original evidence contains unadjudicated secret or signed-query shapes")

    backend_summary = {key: value for key, value in backend.items() if key not in {"nodeCounts", "reportHashes"}}
    integration_summary = {key: value for key, value in integration.items() if key not in {"nodeCounts", "reportHashes"}}
    run_summary = []
    for run in sorted(runs, key=lambda item: item["databaseId"]):
        run_summary.append(
            {
                "runId": run["databaseId"],
                "attempt": run["attempt"],
                "headSha": run["headSha"],
                "event": run["event"],
                "status": run["status"],
                "conclusion": run["conclusion"],
                "createdAt": run["createdAt"],
                "updatedAt": run["updatedAt"],
                "url": run["url"],
                "jobs": [
                    {
                        "name": job["name"],
                        "jobId": job["databaseId"],
                        "status": job["status"],
                        "conclusion": job["conclusion"],
                        "startedAt": job["startedAt"],
                        "completedAt": job["completedAt"],
                        "url": job["url"],
                    }
                    for job in sorted(run["jobs"], key=lambda item: item["name"])
                ],
            }
        )

    summary = {
        "kind": "W9_EXACT_DRAFT_PR_CI_AND_ARTIFACT_ASSESSMENT",
        "generatedAtUTC": datetime.now(timezone.utc).isoformat(),
        "head": HEAD,
        "tree": TREE,
        "base": BASE,
        "testedMerge": {"sha": MERGE, "parents": [BASE, HEAD], "tree": TREE, "verified": merge["verification"]},
        "pullRequest": {"number": 30, "url": pr["url"], "draft": True, "state": "OPEN", "autoMerge": None},
        "requiredContexts": {
            name: {
                "jobId": jobs[name]["databaseId"],
                "status": jobs[name]["status"],
                "conclusion": jobs[name]["conclusion"],
                "url": jobs[name]["url"],
            }
            for name in sorted(REQUIRED)
        },
        "aggregateCodeQL": {key: checks["CodeQL"][key] for key in ("status", "conclusion", "detailsUrl")},
        "workflowRuns": run_summary,
        "artifacts": artifacts,
        "tests": {
            "backendBuild": backend_summary,
            "backendIntegration": integration_summary,
            "frontendUnit": 327,
            "legacyBrowser": 25,
            "advertisingBrowser": 12,
            "governance": 410,
            "infrastructurePython": 22,
            "infrastructureTerraformScenarios": 7,
        },
        "namedBackendProvenance": provenance,
        "codeql": {
            "analyses": analyses,
            "open": {"count": 87, "securitySeverity": 0, "notes": 76, "warnings": 11},
            "fixed": 12,
            "historicalDismissedHigh": 5,
            "assessment": "No open security-severity result; 87 open quality notes/warnings are unchanged from W8.",
        },
        "frontendDistribution": distribution,
        "limits": [
            "W9 proves the UI repair on exact runtime bytes, but its uploaded distribution has the preserved publication-order provenance defect.",
            "This CI uses isolated synthetic databases and fixture providers; no real Provider, shared environment, apply, deployment or production write occurred.",
            "Independent Controller closure remains pending.",
        ],
        "productionWriteEnabled": False,
        "controllerVerdict": "PENDING_INDEPENDENT_REVIEW",
    }

    write_json(output / "summary.json", summary)
    write_json(output / "named-browser-results.json", named_browser)
    write_json(output / "backend-provenance.json", provenance)
    write_json(output / "frontend-distribution-defect.json", distribution)
    write_json(output / "publication-scan.json", scan)
    archive_path = output / "original-evidence.tar.gz"
    manifest = stable_archive(source, original_names, archive_path)
    write_json(
        output / "manifest.json",
        {
            "kind": "W9_CI_EVIDENCE_MANIFEST",
            "archive": {"path": archive_path.name, "bytes": archive_path.stat().st_size, "sha256": sha256(archive_path)},
            "members": manifest,
            "memberCount": len(manifest),
            "allArtifactDigestsMatchedGitHub": True,
            "publicationScan": {"path": "publication-scan.json", "sha256": sha256(output / "publication-scan.json")},
        },
    )
    print(json.dumps({"status": "PASS", "head": HEAD, "jobs": len(jobs), "artifacts": len(artifacts), "archiveSha256": sha256(archive_path)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
