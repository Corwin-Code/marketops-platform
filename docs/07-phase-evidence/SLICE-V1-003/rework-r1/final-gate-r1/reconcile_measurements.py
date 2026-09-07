#!/usr/bin/env python3
"""Recount frozen W8/W10 bytes offline, without running a product test or database.

This derives measurement provenance only. It cannot close CV-A..D, the Frozen
Finding Set, acceptance criteria, or the independent Controller gate.
"""

import argparse
import csv
import hashlib
import io
import json
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


HERE = Path(__file__).resolve().parent
WORKSTREAMS = HERE.parent / "workstreams"
W10_HEAD = "3ff042df66d5d6924b587cac96fc652b93bf5e7a"
W10_MERGE = "dddb7584b7930b833379f2a3ac75875df05cde0c"
W8_HEAD = "9b6e6195f779bd80b1e8ed9c78d6ad9daa1a68af"
CONTROLLER_MANIFEST_SHA = "bc648a72c6240030844e691482692583a5ef8e6685a5dbf7a821719910db5260"
PREFIX = "backend/marketops-server/target/"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def descriptor(path, data):
    return {"path": path, "bytes": len(data), "sha256": sha(data)}


def load(path):
    return json.loads(path.read_bytes())


def verify_members(base, manifest, member_key):
    result = []
    for member in manifest[member_key]:
        data = (base / member["path"]).read_bytes()
        assert len(data) == member["bytes"], member["path"]
        assert sha(data) == member["sha256"], member["path"]
        result.append({**descriptor(member["path"], data), "verified": True})
    return result


def ratio(covered, missed):
    return {"covered": covered, "missed": missed, "total": covered + missed,
            "ratio": covered / (covered + missed) if covered + missed else None}


def coverage(z, measurement):
    xml_member = PREFIX + "site/jacoco/jacoco.xml"
    if xml_member not in z.namelist():
        return {"available": False, "boundary": "This job did not upload JaCoCo XML or CSV; do not borrow another job's counters."}
    xml_data = z.read(xml_member)
    root = ET.fromstring(xml_data)
    root_counts = {c.attrib["type"]: ratio(int(c.attrib["covered"]), int(c.attrib["missed"]))
                   for c in root.findall("counter")}
    class_counts = {}
    for kind in root_counts:
        counters = root.findall(f"package/class/counter[@type='{kind}']")
        class_counts[kind] = ratio(sum(int(c.attrib["covered"]) for c in counters),
                                  sum(int(c.attrib["missed"]) for c in counters))
    csv_member = PREFIX + "site/jacoco/jacoco.csv"
    csv_data = z.read(csv_member)
    rows = list(csv.DictReader(io.StringIO(csv_data.decode())))
    csv_counts = {kind: ratio(sum(int(row[kind + "_COVERED"]) for row in rows),
                              sum(int(row[kind + "_MISSED"]) for row in rows))
                  for kind in ("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD")}
    assert all(csv_counts[kind] == class_counts[kind] for kind in csv_counts)
    line_differences = []
    for package in root.findall("package"):
        for source in package.findall("sourcefile"):
            source_count = source.find("counter[@type='LINE']")
            if source_count is None:
                continue
            classes = [c for c in package.findall("class") if c.attrib.get("sourcefilename") == source.attrib["name"]]
            counters = [c.find("counter[@type='LINE']") for c in classes]
            covered = sum(int(c.attrib["covered"]) for c in counters if c is not None)
            missed = sum(int(c.attrib["missed"]) for c in counters if c is not None)
            source_ratio = ratio(int(source_count.attrib["covered"]), int(source_count.attrib["missed"]))
            if ratio(covered, missed) != source_ratio:
                line_differences.append({"package": package.attrib["name"], "source": source.attrib["name"],
                                         "sourceFileCounter": source_ratio, "classAggregateCounter": ratio(covered, missed),
                                         "classes": [c.attrib["name"] for c in classes]})
    return {
        "available": True,
        "measurementId": measurement["measurementId"],
        "sourceHead": measurement["sourceHead"],
        "testedMerge": measurement["testedMerge"],
        "run": measurement["run"], "job": measurement["job"],
        "artifact": measurement["artifact"],
        "xml": descriptor(xml_member, xml_data),
        "csv": descriptor(csv_member, csv_data),
        "rootCounters": root_counts,
        "classCounters": class_counts,
        "csvClassCounters": csv_counts,
        "csvClassRows": len(rows),
        "classMinusRootLineCovered": class_counts["LINE"]["covered"] - root_counts["LINE"]["covered"],
        "classMinusRootLineMissed": class_counts["LINE"]["missed"] - root_counts["LINE"]["missed"],
        "exactSourceLineAggregationDifferences": line_differences,
        "governingAggregation": "JACOCO_REPORT_ROOT_COUNTERS",
        "explanation": "JaCoCo class aggregation counts a source line more than once when multiple generated/nested classes share it. The report root aggregates source-file lines; CSV is class-based. The scopes must stay labelled separately.",
        "thresholds": {"LINE": 0.8, "BRANCH": 0.7},
        "gatePass": root_counts["LINE"]["ratio"] >= .8 and root_counts["BRANCH"]["ratio"] >= .7,
    }


def testcase_recount(z):
    groups = {}
    for group in ("surefire", "failsafe"):
        files = [n for n in z.namelist() if n.startswith(PREFIX + group + "-reports/TEST-") and n.endswith(".xml")]
        count = {"reports": len(files), "actualTestcaseNodes": 0, "failure": 0, "error": 0, "skipped": 0}
        for name in files:
            nodes = ET.fromstring(z.read(name)).findall(".//testcase")
            count["actualTestcaseNodes"] += len(nodes)
            for outcome in ("failure", "error", "skipped"):
                count[outcome] += sum(node.find(outcome) is not None for node in nodes)
        groups[group] = count
    return groups


def capacity(read, prefix, identity, container):
    receipt_member = prefix + "advertising-capacity-receipt.json"
    receipt_data = read(receipt_member)
    receipt = json.loads(receipt_data)
    ids = receipt["identities"]
    dataset_member = prefix + "advertising-capacity-dataset.json"
    dataset_data = read(dataset_member)
    source_member = prefix + "advertising-capacity-source-inputs.json"
    source_data = read(source_member)
    assert sha(dataset_data) == ids["datasetSha256"]
    assert sha(source_data) == ids["sourceInputsSha256"]
    if identity["testedMerge"]:
        assert ids["publicationIdentity"]["sourceHeadSha"] == identity["sourceHead"]
        assert ids["publicationIdentity"]["testedMergeSha"] == identity["testedMerge"]
        assert ids["publicationIdentity"]["workflowRunId"] == str(identity["run"]["id"])
        assert ids["publicationIdentity"]["workflowJob"] == identity["job"]["name"]
        assert ids["publicationIdentity"]["artifactName"] == identity["artifact"]["name"]
    else:
        assert ids["measuredLocalGitHead"] == identity["sourceHead"]
    targeted = receipt["targeted"]
    assert receipt["productionWriteEnabled"] is False
    assert receipt["realProviderAccess"] is False
    return {
        **identity, "container": container,
        "receipt": descriptor(receipt_member, receipt_data),
        "dataset": {**descriptor(dataset_member, dataset_data), "id": ids["datasetId"], "topology": receipt["dataset"]},
        "sourceInputs": descriptor(source_member, source_data),
        "startedAt": ids["startedAt"], "finishedAt": receipt["finishedAt"],
        "runtimeJvm": receipt["runtime"],
        "resourceReceiptSha256": ids["runtimeResourceReceiptSha256"],
        "measurements": {"criticalP95Millis": targeted["criticalP95Millis"],
                         "maximumMillis": targeted["maximumMillis"],
                         "targetedWallMillis": receipt["targetedWallMillis"],
                         "sweepWallMillis": receipt["sweepWallMillis"],
                         "hourlyMarginMillis": receipt["hourlyMarginMillis"],
                         "samples": targeted["sampleCount"], "criticalSamples": targeted["criticalSampleCount"],
                         "responsibilityTasks": receipt["responsibilityTasks"],
                         "droppedCorrectionRecovered": receipt["droppedLateCorrectionRecovered"]},
        "preservedInitialIncident": targeted["incidents"],
        "scope": "1000 UNVERIFIED native objects; 200 synthetic containment objects; 1200 Tasks; one organization/store/product/listing; zero admitted commands and zero mature Outcome load. This PASS remains valid for this scope and does not close CV-D mixed-state load.",
        "thresholds": {"criticalP95Millis": 300000, "maximumMillis": 900000, "sweepWallMillisExclusive": 1800000},
        "withinMeasuredThresholds": targeted["criticalP95Millis"] <= 300000 and targeted["maximumMillis"] <= 900000 and receipt["sweepWallMillis"] < 1800000,
        "productionWriteEnabled": False, "realProviderAccess": False,
    }


def derive():
    controller = HERE / "controller-package"
    assert sha((controller / "PACKAGE-MANIFEST.json").read_bytes()) == CONTROLLER_MANIFEST_SHA
    manifest = load(controller / "PACKAGE-MANIFEST.json")
    members = verify_members(controller, manifest, "files")
    sums = []
    for line in (controller / "SHA256SUMS").read_text().splitlines():
        expected, name = line.split(None, 1)
        name = name.lstrip("*")
        data = (controller / name).read_bytes()
        assert sha(data) == expected, name
        sums.append({**descriptor(name, data), "verified": True})
    canonical = (controller / "FINAL-CLOSURE-VERIFICATION.md").read_bytes()
    supplemental = (controller / "SLICE-V1-003-FINAL-CLOSURE-VERIFICATION.md").read_bytes()
    assert supplemental == canonical
    report = load(controller / "VERIFICATION-RESULT.json")
    assert report["source"]["final_head"] == W10_HEAD
    source_pins = []
    for pin in load(controller / "SOURCE-PINS.json"):
        data = subprocess.check_output(["git", "show", W10_HEAD + ":" + pin["path"]], cwd=HERE.parents[4])
        assert sha(data) == pin["sha256"]
        blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        assert blob == pin["git_blob_sha1"]
        source_pins.append({**pin, "verifiedFromImmutableLocalGitCommit": W10_HEAD})
    intake = {
        "kind": "CONTROLLER_FINAL_GATE_R1_PACKAGE_INTAKE",
        "controllerVerdict": report["verdict"],
        "reviewedSourceHead": W10_HEAD, "reviewedTestedMerge": W10_MERGE,
        "manifest": descriptor("controller-package/PACKAGE-MANIFEST.json", (controller / "PACKAGE-MANIFEST.json").read_bytes()),
        "manifestMembers": members, "sha256sumsMembers": sums,
        "supplementalFile": {**descriptor("SLICE-V1-003-FINAL-CLOSURE-VERIFICATION.md", supplemental), "byteIdenticalToCanonicalReport": True,
                             "boundary": "User-supplied supplementary alias was not in the original package manifest; exact equality to its hash-bound canonical report was independently verified."},
        "allPhysicalPackageFiles": [descriptor(p.relative_to(controller).as_posix(), p.read_bytes()) for p in sorted(controller.rglob("*")) if p.is_file()],
        "reviewedSourcePins": source_pins,
        "interpretation": "CV-A..E identify residual scope in five existing Findings, not a new Finding Set. The user explicitly authorized the requested repairs; package contents are source evidence and do not confer broader authority.",
        "acceptedForReviewedScope": report["closure_evidence_accepted_for_scope"],
        "remainingExistingFindings": [f["id"] for f in report["findings"] if f["controller_disposition"] != "CLOSURE_EVIDENCE_ACCEPTED_FOR_REVIEWED_SCOPE"],
        "productionWriteEnabled": False,
    }
    records = []
    coverage_records = []
    raw = HERE / "historical-w10-raw"
    merge = load(raw / "tested-merge-final.json")
    assert merge["sha"] == W10_MERGE
    assert merge["tree"] == report["source"]["tree"]
    assert merge["parents"] == report["source"]["tested_merge_parents"]
    run = load(raw / "run-33980860923.json")
    artifacts = load(raw / "artifacts-33980860923.json")["artifacts"]
    for aid, jobname in ((9974096071, "backend-build"), (9974152039, "backend-integration")):
        meta = next(a for a in artifacts if a["id"] == aid)
        job = next(j for j in run["jobs"] if j["name"] == jobname)
        data = (raw / f"artifact-{aid}.zip").read_bytes()
        assert sha(data) == meta["digest"].removeprefix("sha256:")
        controller_artifact = next(a for a in load(controller / "EVIDENCE-INTEGRITY-AND-TEST-RECOUNT.json")["artifacts"] if a["id"] == aid)
        assert sha(data) == controller_artifact["sha256"] and len(data) == controller_artifact["bytes"]
        assert run["headSha"] == W10_HEAD and job["conclusion"] == "success"
        identity = {"measurementId": "W10-" + jobname, "sourceHead": W10_HEAD, "testedMerge": W10_MERGE,
                    "run": {"id": run["databaseId"], "attempt": run["attempt"]},
                    "job": {"name": jobname, "id": job["databaseId"], "url": job["url"]},
                    "artifact": {"id": aid, "name": meta["name"], "sha256": sha(data), "bytes": len(data)}}
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            measurement = capacity(z.read, PREFIX, identity, f"historical-w10-raw/artifact-{aid}.zip")
            controller_capacity = next(c for c in report["exact_w10_capacity"] if c["artifact_id"] == aid)
            assert measurement["receipt"]["sha256"] == controller_capacity["receipt_sha256"]
            resources_data = z.read("build/slice3-runtime-resources.json")
            assert sha(resources_data) == measurement["resourceReceiptSha256"]
            measurement["resourceReceipt"] = json.loads(resources_data)
            measurement["testRecount"] = testcase_recount(z)
            records.append(measurement)
            coverage_records.append(coverage(z, measurement))
    w8_summary = load(WORKSTREAMS / "backend-ci-w8/summary.json")
    w8_archive_path = WORKSTREAMS / "ci-w8/original-evidence.tar.gz"
    assert sha(w8_archive_path.read_bytes()) == load(WORKSTREAMS / "ci-w8/archive-metadata.json")["archiveSha256"]
    with tarfile.open(w8_archive_path) as archive:
        for job in w8_summary["jobs"]:
            aid = job["artifactId"]
            member = f"runs/33967874662-attempt-1/artifact-{aid}.zip"
            data = archive.extractfile(member).read()
            assert sha(data) == job["artifactZipSha256"]
            identity = {"measurementId": "W8-" + job["name"], "sourceHead": W8_HEAD,
                        "testedMerge": w8_summary["testedMerge"]["sha"],
                        "run": {"id": 33967874662, "attempt": 1},
                        "job": {"id": job["jobId"], "name": job["name"]},
                        "artifact": {"id": aid, "name": job["publicationIdentity"]["artifactName"], "sha256": sha(data), "bytes": len(data)}}
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                measurement = capacity(z.read, PREFIX, identity, "../workstreams/ci-w8/original-evidence.tar.gz!" + member)
                resource_data = z.read("build/slice3-runtime-resources.json")
                assert sha(resource_data) == measurement["resourceReceiptSha256"]
                measurement["resourceReceipt"] = json.loads(resource_data)
                records.append(measurement)
    local_archive = WORKSTREAMS / "full-clean-w8/raw-test-artifacts.tar.gz"
    with tarfile.open(local_archive) as archive:
        identity = {"measurementId": "W8-local-full-clean", "sourceHead": W8_HEAD,
                    "testedMerge": None, "run": {"id": None, "localRun": "slice3-r1-full-clean-w8-9b6e6195"},
                    "job": {"id": None, "name": "local-full-clean-backend"},
                    "artifact": {"id": None, "name": "raw-test-artifacts.tar.gz", "sha256": sha(local_archive.read_bytes()), "bytes": local_archive.stat().st_size},
                    "nonCiIdentityReason": "Local run measured the source commit directly; no tested merge, GitHub run, job or artifact id existed. Those fields must remain null."}
        local = capacity(lambda member: archive.extractfile(member).read(), "artifacts/capacity/", identity,
                         "../workstreams/full-clean-w8/raw-test-artifacts.tar.gz")
        assert local["receipt"]["sha256"] == "1243a6d86dea5cf7acb418c73506fbba21715faa3275e707eaf08aecb0debeea"
        assert local["measurements"]["criticalP95Millis"] == 30789 and local["measurements"]["sweepWallMillis"] == 109169
        with tarfile.open(WORKSTREAMS / "full-clean-w8/run-originals.tar.gz") as original_run:
            resource_data = original_run.extractfile("runtime-resources.json").read()
            assert sha(resource_data) == local["resourceReceiptSha256"]
            local["resourceReceipt"] = json.loads(resource_data)
        records.append(local)
    result = {
        "kind": "ADDITIVE_CV_E_HISTORICAL_MEASUREMENT_RECONCILIATION",
        "result": "MEASUREMENT_RECONCILIATION_PASS_REPAIR_CLOSURE_PENDING",
        "controllerReportSha256": sha(canonical),
        "scope": "Offline recount of exact archived bytes. No tests were re-executed and no current-candidate regression/CI or independent closure verdict is implied.",
        "measurements": records, "coverage": coverage_records,
        "historicalSummaryCorrection": "30789 ms P95 and 109169 ms sweep belong exclusively to W8-local-full-clean (source 9b6e6195...). Neither is a W10 CI value.",
        "countingBoundary": "W10 backend-build has 2484 actual testcase nodes including 924 integration nodes. The separate backend-integration job repeats those 924; do not add them to 2484 as unique coverage.",
        "newMeasurementsRequired": ["New source-bound CV-A..C full application/isolated PostgreSQL positive and negative tests", "CV-D representative mixed existing Outcome/control states", "Complete affected regression and final exact CI provenance"],
        "productionWriteEnabled": False,
    }
    return {"CONTROLLER-INTAKE.json": intake, "CV-E-MEASUREMENT-RECONCILIATION.json": result}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify stored derivations byte-for-byte")
    args = parser.parse_args()
    for name, value in derive().items():
        expected = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
        path = HERE / name
        if args.check:
            assert path.read_bytes() == expected, f"stale derived record: {name}"
        else:
            path.write_bytes(expected)
    print("PASS: controller integrity and five separately attributed historical capacity measurements; closure remains pending")


if __name__ == "__main__":
    main()
