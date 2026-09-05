#!/usr/bin/env python3
"""Capture safe host/Docker resource limits; never inspect container environments."""

import argparse
import json
import os
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def collect():
    memory = (int(subprocess.check_output(["sysctl", "-n", "hw.memsize"], text=True))
              if platform.system() == "Darwin"
              else os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES"))
    # Request only these three public daemon fields. Do not dump Docker info,
    # registry credentials, container environments or the user's configuration.
    cpu, docker_memory, version = subprocess.check_output(
        ["docker", "info", "--format", "{{.NCPU}} {{.MemTotal}} {{.ServerVersion}}"],
        text=True).strip().split()
    if int(cpu) <= 0 or int(docker_memory) <= 0 or memory <= 0:
        raise ValueError("Declared runtime CPU and memory must be positive")
    return {
        "schemaVersion": "1.0",
        "recordedAt": datetime.now(timezone.utc).isoformat(),
        "host": {"os": platform.system(), "architecture": platform.machine(),
                 "logicalProcessors": os.cpu_count(), "physicalMemoryBytes": memory,
                 "processAffinityProcessors": len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None},
        "docker": {"logicalProcessors": int(cpu), "memoryBytes": int(docker_memory), "serverVersion": version},
        "github": {key: os.environ.get(name) for key, name in (
            ("runId", "GITHUB_RUN_ID"), ("runAttempt", "GITHUB_RUN_ATTEMPT"),
            ("job", "GITHUB_JOB"), ("testedCheckout", "GITHUB_SHA"))},
        "boundary": "Host/JVM resources and Docker VM limits are separate. No provider, cloud or shared database was inspected.",
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = collect()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
