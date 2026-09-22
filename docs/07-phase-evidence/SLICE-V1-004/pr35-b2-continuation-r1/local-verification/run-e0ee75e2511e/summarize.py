#!/usr/bin/env python3
"""Derive per-class and per-stage summaries from one fresh-clone full run directory.

Reads the raw log, the stage markers and the mirrored XML reports; writes
fresh-clone-full.test-summary.tsv, xml-test-summary.tsv and summary.txt beside them.
Nothing is inferred that the inputs do not state.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

CLASS_LINE = re.compile(
    r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), Time elapsed: ([0-9.,]+) (?:s|sec).*? -+ in (\S+)"
)
TOTAL_LINE = re.compile(r"^\[(?:INFO|WARNING|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$")
PLUGIN_LINE = re.compile(r"--- (surefire|failsafe):[^ ]+:([a-z-]+) ")


def main(run: Path) -> int:
    log = (run / "fresh-clone-full.log").read_text(encoding="utf-8", errors="replace").splitlines()
    phase = "-"
    classes: list[tuple[str, str, int, int, int, int, str]] = []
    totals: list[tuple[str, str]] = []
    for line in log:
        plugin = PLUGIN_LINE.search(line)
        if plugin:
            phase = plugin.group(1)
        match = CLASS_LINE.search(line)
        if match:
            tests, failures, errors, skipped = (int(match.group(i)) for i in range(1, 5))
            outcome = "PASS" if failures == 0 and errors == 0 else "FAIL"
            classes.append((phase, match.group(6), tests, failures, errors, skipped, outcome))
            continue
        total = TOTAL_LINE.match(line)
        if total:
            totals.append((phase, line.split("] ", 1)[1]))
    with (run / "fresh-clone-full.test-summary.tsv").open("w", encoding="utf-8") as handle:
        handle.write("phase\tclass\ttests\tfailures\terrors\tskipped\toutcome\n")
        for row in classes:
            handle.write("\t".join(str(value) for value in row) + "\n")

    xml_rows = []
    for plugin in ("surefire", "failsafe"):
        for path in sorted((run / "reports/backend/marketops-server/target" / f"{plugin}-reports").glob("TEST-*.xml")):
            try:
                root = ElementTree.parse(path).getroot()
            except ElementTree.ParseError:
                xml_rows.append((plugin, path.name, "unparseable", "", "", "", ""))
                continue
            xml_rows.append((plugin, root.get("name", path.name), root.get("tests"), root.get("failures"),
                             root.get("errors"), root.get("skipped"), root.get("time")))
    with (run / "xml-test-summary.tsv").open("w", encoding="utf-8") as handle:
        handle.write("plugin\tsuite\ttests\tfailures\terrors\tskipped\ttime_s\n")
        for row in xml_rows:
            handle.write("\t".join(str(value) for value in row) + "\n")

    def phase_sum(name: str) -> tuple[int, int, int, int, int]:
        rows = [row for row in classes if row[0] == name]
        return (len(rows), sum(r[2] for r in rows), sum(r[3] for r in rows), sum(r[4] for r in rows),
                sum(r[5] for r in rows))

    def xml_sum(name: str) -> tuple[int, int, int, int, int]:
        rows = [row for row in xml_rows if row[0] == name and row[2] not in (None, "unparseable")]
        return (len(rows), *(sum(int(r[i] or 0) for r in rows) for i in (2, 3, 4, 5)))

    lines = [f"run directory: {run.name}"]
    for name in ("surefire", "failsafe"):
        lines.append(f"{name} (log class lines): classes={phase_sum(name)[0]} tests={phase_sum(name)[1]} "
                     f"failures={phase_sum(name)[2]} errors={phase_sum(name)[3]} skipped={phase_sum(name)[4]}")
        lines.append(f"{name} (mirrored XML): suites={xml_sum(name)[0]} tests={xml_sum(name)[1]} "
                     f"failures={xml_sum(name)[2]} errors={xml_sum(name)[3]} skipped={xml_sum(name)[4]}")
    lines.append("maven result totals (log):")
    lines += [f"  [{phase_name}] {text}" for phase_name, text in totals]
    lines.append("not passing classes (log):")
    lines += [f"  [{r[0]}] {r[1]} tests={r[2]} failures={r[3]} errors={r[4]}" for r in classes if r[6] != "PASS"] or ["  none"]
    interesting = re.compile(
        r"All coverage checks have been met|Coverage checks have not been met|BUILD (SUCCESS|FAILURE)|"
        r"Test Files |Tests  |Statements |Branches |Functions |Lines |"
        r"\d+ (passed|failed|flaky|skipped)|^fresh-clone:|^business-browser:|^verify-local-config:|"
        r"coverage-thresholds|verify-coverage|supply-chain|CycloneDX|Tomcat started|Started \w+Application"
    )
    lines.append("marker lines (log, in order):")
    lines += [f"  {line.strip()[:220]}" for line in log if interesting.search(line)]
    (run / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines[:12]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(Path(sys.argv[1])))
