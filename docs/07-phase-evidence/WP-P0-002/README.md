# WP-P0-002 implementation verification evidence

The bounded Maker candidate was imported onto the authoritative short-lived
Codex task branch from the exact approved `main` base, repaired against the
Controller findings and verified locally with the repository's real Java 21,
Maven, Docker and Testcontainers toolchain. These Maker/rework-agent results are
inputs to independent Controller Implementation/PR Review; they do not mark an
acceptance criterion `VERIFIED`, authorize Ready/merge, or enable production
writes.

## Authoritative local verification

| Check | Command | Result |
| --- | --- | --- |
| Governance | `python3 scripts/validate_governance.py` | PASS |
| Global hard rules | `python3 scripts/validate_production_readiness.py` | PASS over 388 files; TC-GLOBAL-001/002/003 PASS |
| Validator sensitivity suites | `python3 -m unittest discover -s tests -p 'test_*.py'` | PASS, 178 tests |
| Backend unit-build Gate | `cd backend/marketops-server && ./mvnw -B -ntp -DskipITs verify` | PASS, Java 21; 180 unit tests; JaCoCo lines 3204/3968 (80.75%), branches 608/861 (70.62%) |
| Backend full verification | `cd backend/marketops-server && ./mvnw -B -ntp verify` | PASS, Java 21; 180 unit tests and 67 integration tests; JaCoCo lines 3807/3968 (95.94%), branches 705/861 (81.88%) |
| Pinned database | same Maven `verify` run | PASS on Testcontainers `postgres:18.4`; runtime `server_version_num=180004`; Flyway validated/applied V0001–V0006 |
| Architecture | `./mvnw -B -ntp -Dtest='*ArchitectureTest' -DfailIfNoTests=true test` | PASS, 31 ArchUnit/Modulith and rule-sensitivity tests |
| Coverage enforcement sensitivity | `bash scripts/verify_coverage_thresholds.sh all` | PASS; both backend and frontend Gates rejected deliberately unmet 100% thresholds |
| Whitespace | `git diff --check` | PASS |

The exact PostgreSQL release proof is executable in `FlywayMigrationIT`; it
asserts both the pinned image name and the running server's numeric/text
versions. The full integration run also proves the six-migration clean path,
V0001 upgrade path, constraints, least privilege, append-only journals,
maintenance API, DENIED auditing, credential/service-account lifecycles,
capability fail-closed behavior and production-write refusal.

## Historical non-gate probe

`local-pg16-validation.sql` and its transcript are retained only as provenance
from the disposable candidate workspace. PostgreSQL 16 was an exploratory
probe and is explicitly **not** acceptance or substitution evidence. The
authoritative Gate is the PostgreSQL 18.4 Testcontainers-backed Maven
verification above.

The original candidate validator transcripts are likewise retained as input
provenance; the authoritative results are the commands executed on the task
branch and reported in the Draft PR/Controller handoff.

## Artifact provenance

| Source artifact | SHA-256 |
| --- | --- |
| Approved Design v1.2 | `3e524c666e56b3d5fdecd6e2098a22d1bd9fd88711dd9c524858ca0cdd3859b2` |
| Original Claude candidate `WP_P0_002_Implementation_Candidate_v1.tar.gz` | `c43928e55c36ee74c45151201f9dcd03d1a068c634809b0dcf511f9c087c853e` |
| Controller implementation deep review | `5629fbeb7afde7dade2ef5e04bd726c10cd84d604e54a130b52d9b0caf08cc9e` |
| Codex new-window handoff pack | `6d32589e24bb72f0fe12d86fdf674e85176cff8fd84966e3fde325e02bb9e0c7` |

No derived repository ZIP or deterministic transformation record exists in the
bound inputs. Consequently, no derived-package hash is asserted or substituted
for the original candidate tarball above.

## Pull-request Gate boundary

Local verification does not predict or replace the pull-request jobs. After a
new repair commit is pushed, `backend-build` must independently execute the
unit-only command, `backend-integration` must independently execute the full
suite, and architecture, security/CodeQL, governance and frontend jobs must
each report their own conclusion on that exact Head/tested merge. Until those
new jobs finish, their status is **pending**, not PASS.

## Boundary

- independent Controller implementation/PR approval: pending;
- repository CI Gate: pending the repair push and evaluated on the Draft PR,
  not inferred from local runs;
- OQ-101, OQ-005, OQ-006 and OQ-102: remain open as allocated;
- Secret/PII/production inventory/real Marketplace connectivity: none;
- production writes: disabled.
