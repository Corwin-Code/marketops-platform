# Test Strategy

## Baseline layers

- Unit: parsers, mappings, money, state and rules;
- Property/Invariant: idempotency, replay, Ledger and stock invariants;
- Integration: PostgreSQL, Flyway, object storage and worker;
- Contract: Ozon/WB requests, responses, schema and error samples;
- Replay: approved redacted Raw payloads;
- Reconciliation: platform totals versus internal facts;
- E2E: ingestion to console/task/approval/readback as phases mature;
- Security: authorization boundaries, secret leakage, export and file upload;
- Performance and disaster-recovery drills.

## WP-P0-001 minimum tests

| ID | Test | Evidence |
| --- | --- | --- |
| TC-GOV-001 | Required governance files and source checksums are present | `governance` CI |
| TC-GOV-002 | Work Package contains mandatory sections | `governance` CI |
| TC-GOV-003 | Common secret patterns are absent from executable/config files | `governance` CI + repository security setting |
| TC-BE-001 | Backend compiles and smoke/unit test passes | backend CI |
| TC-BE-002 | Health/readiness endpoint returns safe status | integration/smoke evidence |
| TC-DB-001 | Clean PostgreSQL receives Flyway bootstrap migration | migration CI |
| TC-DB-002 | Flyway validate passes after application | migration CI |
| TC-ARCH-001 | Prohibited example module dependency fails architecture test | architecture CI |
| TC-FE-001 | Lint, type check, unit test and production build pass | frontend CI |
| TC-E2E-001 | Console shell can display backend health/data state | smoke/E2E evidence |

## Evidence rule

A model statement such as “tests should pass” is not evidence. Evidence must be a reproducible command result, CI check, test report, migration output or inspected runtime artifact.
