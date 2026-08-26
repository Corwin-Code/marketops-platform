# Test Strategy

> **Active V1 assurance:** the test layers below remain valid historical and
> reusable strategy. Active Slice and controlled-write evidence requirements are
> governed by `docs/05-testing/V1_PRODUCTION_ASSURANCE_MATRIX.md` and the active
> Slice Acceptance IDs. WP-specific tables remain evidence provenance and do not
> define the current delivery sequence.

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

## WP-P0-002 minimum tests

| ID group | Coverage | Suite |
| --- | --- | --- |
| TC-OA-101…102, TC-IA-101…103, TC-MI-101…104 | Lifecycle state machines and closed vocabularies of every metadata module | `CoreLifecycleTest`, `AccessLifecycleTest`, `IntegrationLifecycleTest` |
| TC-SEC-101…104, TC-SEC-201…204 | Secret-material refusal heuristics and shared field validation, including opaque secret references | `SecretMaterialGuardTest`, `MetadataFieldPolicyTest` |
| TC-FF-101…104 | Fail-closed write-gate bindings: enabled production writes fail startup, the maintenance switch is mandatory | `WriteGateBindingTest` |
| TC-DB-100…103, TC-DB-110…114, TC-DB-118 | PostgreSQL release pin, migration set, schemas, tables, seeds, contaminated-database refusal and disabled clean | `FlywayMigrationIT` |
| TC-DB-104…105, TC-DB-105b, TC-DB-106…109, TC-DB-115…117 | Foundation-role connection, schema, search-path and cluster-level negative privileges | `DatabasePrivilegeIT` |
| TC-DB-201…216 | Metadata relational invariants by SQLSTATE: composite-key ownership and platform pinning, exclusion intervals, uniqueness, checks, generated columns, append-only journals and the object privilege matrix | `MetadataConstraintIT`, `MetadataPrivilegeIT` |
| TC-API-001…003, TC-API-010…013, TC-API-020, TC-API-030…033, TC-API-040…042, TC-API-050, TC-API-060, TC-API-070…071, TC-API-080…087 | The maintenance surface end to end: attribution and gate refusals, strict bodies, commands, complete lifecycle/read paths, credential scope contract, registry fail-closed rules, flags, retirement vetoes and journal attribution | `MetadataMaintenanceApiIT`, `MaintenanceWriteGateApiIT` |
| TC-ARCH-001…008, TC-QUALITY-ARCH-001…004 | Boundary and production-quality rules over the populated module tree and exact detected module set | `ModuleBoundaryArchitectureTest`, `ModulithArchitectureTest`, `RuleSensitivityArchitectureTest` |

## Evidence rule

A model statement such as “tests should pass” is not evidence. Evidence must be a reproducible command result, CI check, test report, migration output or inspected runtime artifact.

## V1 evidence classification

Fixture/in-memory evidence proves only its stated local layer. Real PostgreSQL,
approved Object Storage, Ozon/WB, OIDC IdP, model provider, browser, recovery and
operator-drill obligations require the corresponding evidence class in the V1
Production Assurance Matrix. CI success is necessary but does not promote a mock
to real-provider evidence or enable a production write.
