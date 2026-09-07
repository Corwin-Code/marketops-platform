# Read-only triage of the 99 exact PR #30 CodeQL alerts

The scan is bound to Head 247ea5ced6cd0ac110314db9fa606d8995c85cac and test merge 4c1aaa695892f70fe6966d8076b2b05e338c32be. All 33 affected source files are absent from exact Base 08ad7da7d9e75b4ddd1c387a22ac0affba9e1430, whose two CodeQL analyses exist and whose open-alert API returns an empty set. Thus these alerts concern source added by this PR; they are not the four pre-existing Dependabot advisories. This does not assert that every alert is an exploitable defect.

The 99 alerts comprise the assigned 11 HIGH, one MEDIUM test-runner PATH lookup, 11 non-security correctness warnings and 76 maintenance notes. `codeql-99-triage.json` preserves every alert's original severity/level/message/location, exact source hash, Base comparison and individual engineering reason.

No additional proven BLOCKER/MAJOR beyond the assigned eleven HIGH was identified in this bounded review. The null warnings are guarded by early unresolved returns, coverage state transitions or the canonical linked-money/currency pair; the enum warning points to an UNKNOWN_STATE case explicitly present in a grouped label; response bytes are cloned both at construction and access with an existing mutation assertion. Indentation warnings do not alter the intended control flow. Deprecated JSON methods and redundant formals are maintenance observations, not by themselves permission or arithmetic failures. No style cleanup or alert dismissal was performed.

MEDIUM #129 remains explicitly open: a fixed `git rev-parse HEAD` process in test evidence collection relies on runner PATH. A compromised runner PATH can execute a substituted binary and corrupt provenance, although there is no production request input or attacker-controlled argument in this helper. Its context must be reviewed rather than silently waived.

The source review neither turns the failing aggregate CodeQL check green nor independently verifies the ongoing UI/Norm fixes. New exact-Head security scans and final Controller judgment remain required. No Maven, Provider call, workflow mutation or source edit was performed for this triage.
