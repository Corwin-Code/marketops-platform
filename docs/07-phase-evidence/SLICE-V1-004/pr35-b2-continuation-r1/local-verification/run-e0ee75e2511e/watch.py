#!/usr/bin/env python3
"""Observe one fresh-clone full run without changing it.

Reads only. Every 2 s it records new marker lines of the raw log with a UTC time
(stages.tsv) and mirrors report files of the run's clone into OUT/reports, so they
survive the entry's own workspace removal. Every 30 s it records host TCP, listener
and load samples (host-samples.tsv). Values of the generated local configuration are
held in memory only; on stop every evidence file is scanned for them and only key
names, value lengths and occurrence counts are written (generated-config-proof.txt).
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import os
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

CLONE_NAME = "MarketOps clone's verification"
MARKERS = ("fresh-clone:", "business-browser:", "verify-local-config:", "env-init:")
REPORT_PATTERNS = (
    "backend/marketops-server/target/surefire-reports/TEST-*.xml",
    "backend/marketops-server/target/failsafe-reports/TEST-*.xml",
    "backend/marketops-server/target/failsafe-reports/failsafe-summary.xml",
    "backend/marketops-server/target/site/*/jacoco.csv",
    "backend/marketops-server/target/performance/*.json",
    "frontend/marketops-console/coverage/lcov.info",
    "build/supply-chain/*",
    "frontend/marketops-console/playwright-report/**/*",
    "frontend/marketops-console/test-results/**/*",
)
SECRET_WORDS = ("PASSWORD", "SECRET", "TOKEN", "KEY", "CREDENTIAL")
WATCHED_PORTS = (8080, 9999, 8082, 4173)


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run(command: list[str]) -> str:
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return ""


def host_sample() -> list[str]:
    states: dict[str, int] = {}
    ephemeral: set[int] = set()
    proxy = 0
    for line in run(["netstat", "-an", "-p", "tcp"]).splitlines()[2:]:
        fields = line.split()
        if len(fields) < 6:
            continue
        states[fields[5]] = states.get(fields[5], 0) + 1
        local_port = fields[3].rsplit(".", 1)[-1]
        if local_port.isdigit() and 49152 <= int(local_port) <= 65535:
            ephemeral.add(int(local_port))
        if "127.0.0.1.15732" in (fields[3], fields[4]):
            proxy += 1
    load = run(["sysctl", "-n", "vm.loadavg"]).strip().strip("{} ").split(" ")[0]
    listeners = []
    for port in WATCHED_PORTS:
        owners = sorted({
            line[1:] for line in run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-Fc"]).splitlines()
            if line.startswith("c")
        })
        listeners.append(f"{port}={'+'.join(owners) or '-'}")
    return [
        now(), str(len(ephemeral)), str(states.get("ESTABLISHED", 0)), str(states.get("TIME_WAIT", 0)),
        str(states.get("CLOSE_WAIT", 0)), str(states.get("FIN_WAIT_2", 0)), str(proxy), load, " ".join(listeners),
    ]


class Watcher:
    def __init__(self, out: Path, log: Path, tmpdir: Path, started_after: float) -> None:
        self.out = out
        self.log = log
        self.tmpdir = tmpdir
        self.started_after = started_after
        self.offset = 0
        self.pending = b""
        self.clone: Path | None = None
        self.copied: dict[str, tuple[int, float]] = {}
        # Generated values stay in this process. Only digests of whole files are kept
        # to notice regeneration; the values themselves are never written anywhere.
        self.file_digests: dict[str, str] = {}
        self.generations: list[dict] = []
        self.secret_values: list[tuple[int, str, bytes]] = []
        (out / "reports").mkdir(parents=True, exist_ok=True)
        self.stages = (out / "stages.tsv").open("a", encoding="utf-8")
        self.samples = (out / "host-samples.tsv").open("a", encoding="utf-8")
        if self.samples.tell() == 0:
            self.samples.write("utc\tephemeral_ports_in_use\testablished\ttime_wait\tclose_wait\tfin_wait_2"
                               "\tproxy_15732_lines\tload_1m\tlisteners\n")

    def find_clone(self) -> None:
        if self.clone is not None:
            return
        for candidate in sorted(glob.glob(str(self.tmpdir / "marketops-fresh.*"))):
            path = Path(candidate) / CLONE_NAME
            try:
                if Path(candidate).stat().st_mtime >= self.started_after and path.is_dir():
                    self.clone = path
                    self.stages.write(f"{now()}\twatcher\tclone found at {path}\n")
                    self.stages.flush()
                    return
            except OSError:
                continue

    def read_markers(self) -> None:
        try:
            with self.log.open("rb") as handle:
                handle.seek(self.offset)
                chunk = handle.read()
                self.offset = handle.tell()
        except OSError:
            return
        data = self.pending + chunk
        lines = data.split(b"\n")
        self.pending = lines.pop()
        for raw in lines:
            line = raw.decode("utf-8", "replace").rstrip("\r")
            if line.startswith(MARKERS):
                self.stages.write(f"{now()}\tlog\t{line}\n")
        self.stages.flush()

    def capture_generated(self) -> None:
        if self.clone is None:
            return
        for relative in (".env.local", "frontend/marketops-console/.env.local"):
            path = self.clone / relative
            try:
                content = path.read_bytes()
                mode = oct(path.stat().st_mode & 0o777)
            except OSError:
                continue
            digest = hashlib.sha256(content).hexdigest()
            if self.file_digests.get(relative) == digest:
                continue
            self.file_digests[relative] = digest
            generation = len(self.generations) + 1
            keys = []
            for line in content.decode("utf-8", "replace").splitlines():
                if not line or line.startswith("#") or "=" not in line:
                    continue
                name, value = line.split("=", 1)
                secret = any(word in name.upper() for word in SECRET_WORDS)
                keys.append((name, secret, len(value)))
                if secret and value:
                    self.secret_values.append((generation, name, value.encode("utf-8")))
            self.generations.append({"generation": generation, "file": relative, "mode": mode,
                                     "seen": now(), "keys": keys})
            self.stages.write(f"{now()}\twatcher\tgenerated {relative} (generation {generation}, mode {mode}, "
                              f"{len(keys)} keys)\n")
            self.stages.flush()

    def mirror_reports(self) -> None:
        if self.clone is None:
            return
        for pattern in REPORT_PATTERNS:
            for name in glob.glob(str(self.clone / pattern), recursive=True):
                source = Path(name)
                try:
                    if not source.is_file():
                        continue
                    stat = source.stat()
                except OSError:
                    continue
                relative = str(source.relative_to(self.clone))
                signature = (stat.st_size, stat.st_mtime)
                if self.copied.get(relative) == signature:
                    continue
                target = self.out / "reports" / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                try:
                    shutil.copy2(source, target)
                    self.copied[relative] = signature
                except OSError:
                    continue

    def sample(self) -> None:
        self.samples.write("\t".join(host_sample()) + "\n")
        self.samples.flush()

    def prove(self) -> None:
        evidence = sorted(path for path in self.out.rglob("*")
                          if path.is_file() and path.name != "generated-config-proof.txt")
        hits = {(generation, name): 0 for generation, name, _ in self.secret_values}
        for path in evidence:
            try:
                data = path.read_bytes()
            except OSError:
                continue
            for generation, name, value in self.secret_values:
                hits[(generation, name)] += data.count(value)
        lines = [f"# generated local configuration of the run, observed {now()}",
                 "# values were held in the observer's memory only; none is written here",
                 f"# evidence files scanned (plain text, before compression): {len(evidence)}", ""]
        for item in self.generations:
            lines.append(f"generation {item['generation']}: {item['file']} mode {item['mode']} first seen {item['seen']}")
            for name, secret, length in item["keys"]:
                if secret:
                    count = hits.get((item["generation"], name), 0)
                    lines.append(f"  {name}: generated secret, length {length}, occurrences in evidence {count}")
                else:
                    lines.append(f"  {name}: non-secret setting, length {length}")
        lines += ["", f"secret values held: {len(self.secret_values)}",
                  f"total occurrences of generated secret values in evidence: {sum(hits.values())}"]
        (self.out / "generated-config-proof.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--tmpdir", required=True, type=Path)
    parser.add_argument("--started-after", required=True, type=float)
    parser.add_argument("--stop-file", required=True, type=Path)
    arguments = parser.parse_args()
    watcher = Watcher(arguments.out, arguments.log, arguments.tmpdir, arguments.started_after)
    last_sample = 0.0
    while not arguments.stop_file.exists():
        watcher.find_clone()
        watcher.read_markers()
        watcher.capture_generated()
        watcher.mirror_reports()
        if time.monotonic() - last_sample >= 30:
            watcher.sample()
            last_sample = time.monotonic()
        time.sleep(2)
    watcher.read_markers()
    watcher.sample()
    watcher.stages.write(f"{now()}\twatcher\tstopped\n")
    watcher.stages.close()
    watcher.samples.close()
    watcher.prove()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
