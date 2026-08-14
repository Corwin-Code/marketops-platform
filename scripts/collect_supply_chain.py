#!/usr/bin/env python3
"""Collect the dependency inventory of both applications into one directory.

A software bill of materials answers a question that is asked under time
pressure: when an advisory names a library, is it in this product, and at which
version. Answering it by reading two build files is slow and gets the transitive
answer wrong, so both trees are asked to describe themselves and the results are
written side by side.

The command produces files and reads build definitions. It installs nothing,
contacts no service other than the package registries the builds already use,
and writes only inside the ignored output directory.

Output::

    build/supply-chain/backend-sbom.json          CycloneDX, aggregated
    build/supply-chain/backend-licenses.txt       resolved licence per artefact
    build/supply-chain/frontend-sbom.json         CycloneDX, npm dependency graph
    build/supply-chain/frontend-dependencies.json npm tree, all depths
    build/supply-chain/frontend-licenses.txt      declared licence per package
    build/supply-chain/INVENTORY.md               what was collected and how

Nothing here is committed. The inventory belongs to a build, and a copy checked
in would be a claim about a dependency set that has since moved.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend" / "marketops-server"
FRONTEND = ROOT / "frontend" / "marketops-console"
OUTPUT = ROOT / "build" / "supply-chain"


@dataclass
class Step:
    """One collection step and what it is expected to leave behind."""

    name: str
    working_directory: Path
    command: list[str]
    produces: list[Path]


def steps() -> list[Step]:
    return [
        Step(
            name="backend inventory",
            working_directory=BACKEND,
            command=["./mvnw", "-B", "-ntp", "-DskipTests", "package"],
            produces=[
                BACKEND / "target" / "marketops-server-sbom.json",
                BACKEND / "target" / "licenses" / "backend-license-inventory.txt",
            ],
        ),
        Step(
            name="frontend CycloneDX inventory",
            working_directory=FRONTEND,
            command=["npm", "run", "sbom"],
            produces=[OUTPUT / "frontend-sbom.json"],
        ),
        Step(
            name="frontend dependency tree",
            working_directory=FRONTEND,
            command=["npm", "ls", "--all", "--json"],
            produces=[],
        ),
        Step(
            name="frontend licence inventory",
            working_directory=FRONTEND,
            command=["npm", "ls", "--all", "--long", "--json"],
            produces=[],
        ),
    ]


def run(step: Step, capture: bool) -> str:
    """Run one step and return its output when it is the artefact itself."""
    result = subprocess.run(
        step.command,
        cwd=step.working_directory,
        capture_output=capture,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        detail = (result.stderr or "").strip().splitlines()
        tail = detail[-1] if detail else f"exit status {result.returncode}"
        raise SystemExit(f"{step.name} failed: {tail}")
    return result.stdout if capture else ""


def licence_lines(tree: dict) -> list[str]:
    """Flatten an npm tree into one line per package, sorted by name."""
    collected: dict[str, str] = {}

    def walk(node: dict) -> None:
        for name, entry in (node.get("dependencies") or {}).items():
            # npm represents an uninstalled optional/peer package as an empty
            # object. It is part of another package's possible graph, not part
            # of this build's inventory, and recording it as an unknown package
            # creates a false licence decision.
            version = entry.get("version")
            if not version:
                continue
            licence = entry.get("license") or entry.get("licenses") or "UNDECLARED"
            if isinstance(licence, list):
                licence = ", ".join(sorted(str(item) for item in licence))
            collected[f"{name}@{version}"] = str(licence)
            walk(entry)

    walk(tree)
    return [f"{package}\t{licence}" for package, licence in sorted(collected.items())]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--skip-backend",
        action="store_true",
        help="collect only the console inventory",
    )
    parser.add_argument(
        "--skip-frontend",
        action="store_true",
        help="collect only the backend inventory",
    )
    arguments = parser.parse_args()

    OUTPUT.mkdir(parents=True, exist_ok=True)
    collected: list[str] = []

    if not arguments.skip_backend:
        backend = steps()[0]
        run(backend, capture=False)
        for produced in backend.produces:
            if not produced.exists():
                raise SystemExit(
                    f"{backend.name} reported success but did not produce {produced.name}"
                )
        (OUTPUT / "backend-sbom.json").write_text(
            backend.produces[0].read_text(encoding="utf-8"), encoding="utf-8"
        )
        (OUTPUT / "backend-licenses.txt").write_text(
            backend.produces[1].read_text(encoding="utf-8"), encoding="utf-8"
        )
        collected.append("backend")

    if not arguments.skip_frontend:
        sbom_step, tree_step, licence_step = steps()[1], steps()[2], steps()[3]
        run(sbom_step, capture=False)
        if not sbom_step.produces[0].exists():
            raise SystemExit(
                f"{sbom_step.name} reported success but did not produce frontend-sbom.json"
            )
        sbom = json.loads(sbom_step.produces[0].read_text(encoding="utf-8"))
        if sbom.get("bomFormat") != "CycloneDX" or sbom.get("specVersion") != "1.6":
            raise SystemExit("frontend CycloneDX inventory has an unexpected format or version")
        tree = run(tree_step, capture=True)
        (OUTPUT / "frontend-dependencies.json").write_text(tree, encoding="utf-8")
        detailed = json.loads(run(licence_step, capture=True) or "{}")
        (OUTPUT / "frontend-licenses.txt").write_text(
            "\n".join(licence_lines(detailed)) + "\n", encoding="utf-8"
        )
        collected.append("frontend")

    (OUTPUT / "INVENTORY.md").write_text(
        "\n".join(
            [
                "# Dependency inventory",
                "",
                "Produced by `scripts/collect_supply_chain.py`. Nothing here is committed:",
                "an inventory describes one build, and a checked-in copy would be a claim",
                "about a dependency set that has since moved.",
                "",
                f"Collected: {', '.join(collected) if collected else 'nothing'}",
                "",
                "| File | What it answers |",
                "| --- | --- |",
                "| `backend-sbom.json` | Which components, at which versions, in CycloneDX form |",
                "| `backend-licenses.txt` | The licence resolved for each backend artefact |",
                "| `frontend-sbom.json` | Which console components, at which versions, in CycloneDX form |",
                "| `frontend-dependencies.json` | The console's dependency tree at every depth |",
                "| `frontend-licenses.txt` | The licence each console package declares |",
                "",
                "A package listed as `UNDECLARED` has no licence field. That is a question",
                "for a person, not a value to be filled in from a guess.",
                "",
            ]
        ),
        encoding="utf-8",
    )

    print(f"supply chain inventory written to {OUTPUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
