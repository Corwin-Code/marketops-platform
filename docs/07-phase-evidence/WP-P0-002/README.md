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
| Global hard rules | `python3 scripts/validate_production_readiness.py` | PASS over 378 files; TC-GLOBAL-001/002/003 PASS |
| Validator sensitivity suites | `python3 -m unittest discover -s tests -p 'test_*.py'` | PASS, 178 tests |
| Backend final quality Gate | `cd backend/marketops-server && ./mvnw -B -ntp verify` | PASS, Java 21; 146 unit tests and 67 integration tests |
| Pinned database | same Maven `verify` run | PASS on Testcontainers `postgres:18.4`; runtime `server_version_num=180004`; Flyway validated/applied V0001–V0006 |
| Architecture | `./mvnw -B -ntp -Dtest='*ArchitectureTest' -DfailIfNoTests=true test` | PASS, 31 ArchUnit/Modulith and rule-sensitivity tests |
| Coverage | Maven JaCoCo check | PASS; lines 3666/3965 (92.46%), branches 602/859 (70.08%) |
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

## Boundary

- independent Controller implementation/PR approval: pending;
- repository CI Gate: evaluated on the Draft PR, not inferred from local runs;
- OQ-101, OQ-005, OQ-006 and OQ-102: remain open as allocated;
- Secret/PII/production inventory/real Marketplace connectivity: none;
- production writes: disabled.
