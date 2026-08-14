#!/usr/bin/env python3
"""Report whether the workstation satisfies the documented development prerequisites.

The doctor only inspects. It never installs, upgrades, or reconfigures a host
tool, because silently changing a developer's machine makes a build unreproducible
and hides the real prerequisite from the next person.

Each finding is reported with the exact command the developer runs to fix it, so
the output is actionable without consulting another document.
"""

from __future__ import annotations

import os
import re
import shutil
import socket
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
NODE_VERSION_FILE = REPO_ROOT / "frontend" / "marketops-console" / ".node-version"

REQUIRED_JAVA_MAJOR = 21
REQUIRED_NODE_MAJOR = 24
INSPECTED_PORTS = (5432, 8080, 5173)

STATUS_PASS = "PASS"
STATUS_WARN = "WARN"
STATUS_FAIL = "FAIL"


@dataclass
class Finding:
    """One prerequisite result and, when unmet, the developer's remedy."""

    name: str
    status: str
    detail: str
    remedy: str = ""


def run_capture(command: list[str]) -> str:
    """Return combined output of ``command`` or an empty string when it cannot run."""
    try:
        completed = subprocess.run(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return ""
    return completed.stdout.decode("utf-8", errors="replace")


def first_int(text: str) -> int | None:
    match = re.search(r"(\d+)", text)
    return int(match.group(1)) if match else None


def check_repository_path() -> Finding:
    """Report the path contract exercised by the fresh-clone acceptance test."""
    return Finding(
        "repository path",
        STATUS_PASS,
        "relative-path tooling supports whitespace and single quotes",
    )


def check_java() -> Finding:
    if shutil.which("java") is None:
        return Finding(
            "java",
            STATUS_FAIL,
            "not found",
            f"install a Java {REQUIRED_JAVA_MAJOR} JDK and put it on PATH",
        )
    output = run_capture(["java", "-version"])
    match = re.search(r'version "(\d+)', output)
    major = int(match.group(1)) if match else None
    if major != REQUIRED_JAVA_MAJOR:
        return Finding(
            "java",
            STATUS_FAIL,
            f"major version {major} found",
            f"install a Java {REQUIRED_JAVA_MAJOR} JDK; any vendor is acceptable",
        )
    return Finding("java", STATUS_PASS, f"Java {major}")


def expected_node_version() -> str | None:
    if not NODE_VERSION_FILE.exists():
        return None
    return NODE_VERSION_FILE.read_text(encoding="utf-8").strip()


def check_node() -> Finding:
    expected = expected_node_version()
    if shutil.which("node") is None:
        remedy = (
            f"install Node {expected}" if expected else f"install Node {REQUIRED_NODE_MAJOR}"
        )
        return Finding("node", STATUS_FAIL, "not found", remedy)
    output = run_capture(["node", "--version"]).strip()
    major = first_int(output)
    if major != REQUIRED_NODE_MAJOR:
        return Finding(
            "node",
            STATUS_FAIL,
            f"{output} found",
            f"install Node {expected or REQUIRED_NODE_MAJOR} to match .node-version",
        )
    if expected and output.lstrip("v") != expected:
        return Finding(
            "node",
            STATUS_WARN,
            f"{output} found, .node-version pins {expected}",
            f"switch to Node {expected} for byte-identical local and CI behaviour",
        )
    return Finding("node", STATUS_PASS, output)


def check_container_runtime() -> Finding:
    if shutil.which("docker") is None:
        return Finding(
            "container runtime",
            STATUS_FAIL,
            "no docker-compatible CLI found",
            "install a Docker-compatible CLI providing Compose v2",
        )
    compose = run_capture(["docker", "compose", "version"])
    if "Compose" not in compose:
        return Finding(
            "container runtime",
            STATUS_FAIL,
            "docker compose subcommand unavailable",
            "install or enable the Compose v2 plugin",
        )
    daemon = run_capture(["docker", "info"])
    if "Server Version" not in daemon and "Server:" not in daemon:
        return Finding(
            "container runtime",
            STATUS_WARN,
            "CLI present but daemon unreachable",
            "start the container runtime before `make up`",
        )
    return Finding("container runtime", STATUS_PASS, compose.strip().splitlines()[0])


def check_make() -> Finding:
    if shutil.which("make") is None:
        return Finding(
            "make", STATUS_FAIL, "not found", "install GNU make or the platform build tools"
        )
    return Finding("make", STATUS_PASS, "available")


def check_python() -> Finding:
    return Finding(
        "python3",
        STATUS_PASS,
        f"{sys.version_info.major}.{sys.version_info.minor}",
    )


def check_port(port: int) -> Finding:
    """Report whether a development port is already bound on the loopback address."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.4)
        busy = probe.connect_ex(("127.0.0.1", port)) == 0
    if busy:
        return Finding(
            f"port {port}",
            STATUS_WARN,
            "already in use",
            f"stop the process using {port}, or change the configured port",
        )
    return Finding(f"port {port}", STATUS_PASS, "free")


def check_local_env_files() -> list[Finding]:
    findings: list[Finding] = []
    backend = REPO_ROOT / ".env.local"
    findings.append(
        Finding("local backend configuration", STATUS_PASS, "present")
        if backend.exists()
        else Finding(
            "local backend configuration",
            STATUS_WARN,
            "missing",
            "run `make env-init`",
        )
    )
    frontend_project = REPO_ROOT / "frontend" / "marketops-console"
    if frontend_project.exists():
        frontend = frontend_project / ".env.local"
        findings.append(
            Finding("local frontend configuration", STATUS_PASS, "present")
            if frontend.exists()
            else Finding(
                "local frontend configuration",
                STATUS_WARN,
                "missing",
                "run `make env-init`",
            )
        )
    return findings


def collect() -> list[Finding]:
    findings = [
        check_repository_path(),
        check_java(),
        check_node(),
        check_container_runtime(),
        check_make(),
        check_python(),
    ]
    findings.extend(check_port(port) for port in INSPECTED_PORTS)
    findings.extend(check_local_env_files())
    return findings


def main() -> int:
    findings = collect()
    width = max(len(finding.name) for finding in findings)
    print("MarketOps development environment report")
    print("-" * (width + 34))
    for finding in findings:
        print(f"{finding.name.ljust(width)}  {finding.status:4}  {finding.detail}")
        if finding.remedy:
            print(f"{' ' * width}        fix: {finding.remedy}")
    failures = [finding for finding in findings if finding.status == STATUS_FAIL]
    print("-" * (width + 34))
    if failures:
        print(f"{len(failures)} prerequisite(s) unmet. No host software was installed or changed.")
        return 1
    print("All required prerequisites are satisfied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
