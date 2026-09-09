# SLICE-V1-004 — executable evidence (Level 1 local checkpoint)

Every command below was run in the Level 1 local session on the exact working
tree this checkpoint commits. Nothing here touched a shared environment, a
real Provider, a real credential or a remote Git ref. Results are recorded as
they came back, including what was not run.

## Backend

| Command | Result |
| --- | --- |
| `./mvnw -q -B -ntp -DskipTests compile` | exit 0 |
| `./mvnw -q -B -ntp -DskipTests test-compile` | exit 0 (every integration test compiles) |
| `./mvnw -q -B -ntp test` (unit and architecture suites; Surefire excludes `*IT`) | exit 0; 1716 tests, 0 failures, 0 errors, 0 skipped |
| `ModulithArchitectureTest`, `ModuleBoundaryArchitectureTest`, `SoleAuthorityArchitectureTest` (TC-AUTHORITY-004 added), `SchemaVocabularyAgreementTest` (TC-VOCAB-006 added), `IngestionAuthorityArchitectureTest`, `RuleSensitivityArchitectureTest` | pass |
| `ListingConversionCodesTest` (every backend code family present in `backend-codes.json` for `zh` and `ru`) | pass |
| Testcontainers integration tests (`FlywayMigrationIT`, `ControlEpochTriggerIT`, `ListingConversionSchemaIT`, `ListingActionLaunchIT`, `ListingDescriptionWriteGateIT`, `ListingContainmentIT`, every other `*IT`) | NOT_RUN — no container runtime in this session; compiled only |

## Local isolated PostgreSQL 16 (127.0.0.1:55432, not a shared environment)

| Step | Result |
| --- | --- |
| Clean install of V0001–V0079 as the migrating role, one transaction per file | all applied; `public.local_migration_history` records 79 versions |
| Every `lc_` table inventoried in `platform.control_route_inventory` as `NO_ROUTE` | 43 of 43 |
| Application role privileges | no INSERT on `ops.lc_launch`, `ops.lc_description_command`, `ops.lc_containment`, `ops.lc_exposure_occupation`; no INSERT on the Owner-published `core.lc_calibration_package`, `ops.lc_exposure_allowance`, `ops.lc_gate_authority`, `core.lc_summary_equivalence_profile`; INSERT on `ops.lc_action` and `ops.lc_action_review` |
| Fictional fixture (`src/test/resources/listing/lc-fictional-positive.sql` over `advertising/r1-fictional-positive.sql`) | loads in one transaction; two listings, calibration package ACTIVE, allowances `CONCURRENT_LISTINGS=1`, `AFFECTED_VARIANTS=10`, gate authority with `production_write_enabled=false` |
| `ops.acquire_lc_launch_allowance` for action one with a one-use `LISTING_ACTION_LAUNCH` proof | `launched=true`, two occupations `ACQUIRED`, action `LAUNCHED`, `ops.lc_launch` row with proof hash |
| Same for action two | `launched=false`, `insufficientAxes=["CONCURRENT_LISTINGS"]`, action `APPROVED_NOT_LAUNCHABLE` |
| `ops.create_lc_description_command` twice for action one | same command id both times (idempotent), state `PENDING`, prior text captured |
| `ops.evaluate_lc_description_write_gate` | exactly `{PRODUCTION_WRITE_DISABLED}` |
| `ops.lease_lc_description_command` | refused: "the description write gate is closed: PRODUCTION_WRITE_DISABLED" |
| Review by the action's author; packet on an unlaunched action; direct `UPDATE ops.lc_action SET state='LAUNCHED'` | refused `MO092`, `MO090`, `MO092` |
| `ops.lc_description_command_transition` from `UNKNOWN_REQUIRES_READBACK` | exactly `MANUAL_RESOLUTION`, `READBACK_PENDING` |
| `ops.release_lc_occupation` with `STOP_EVIDENCE` and a display observation, then relaunch of action two | released; action two `LAUNCHED` |
| `ops.record_lc_containment` on listing one | action one `CONTAINED`; `ops.lc_scope_contained` true; gate adds `ACTION_NOT_LAUNCHED`, `SCOPE_CONTAINED` |
| `ops.reenable_lc_containment` without attestations; with a repair attestation by the wrong role; with both halves from one person | refused each time with the named rule |
| Repair attestation by the cause-owner role holder plus consent by another person, then re-enable | `REENABLED`; the contained action stays `CONTAINED` |
| `core.lc_resolve_calibration` on an empty scope; `ops.lc_protection_verdict_of` with an undetermined entry; description digest under `WHITESPACE_NORMALIZED`; request template checks | `CALIBRATION_UNRESOLVED`; `UNDETERMINED`; equal digests; pass/refuse as specified |

## Frontend (`frontend/marketops-console`, Node 24)

| Command | Result |
| --- | --- |
| `npm run lint` | exit 0, 0 warnings |
| `npm run typecheck` | exit 0 |
| `npm run format:check` | all files formatted |
| `npm run test:ci` | 25 files, 379 tests passed; coverage statements 87.96%, branches 82.07%, functions 88.95%, lines 88.88% (thresholds 80/70/80/80) |
| `npm run build` | exit 0 |
| `npm run verify:bundle` | bundle isolation PASS |
| Playwright browser scenarios | NOT_RUN |

## Governance

| Command | Result |
| --- | --- |
| `python3 scripts/validate_governance.py` | passed |
| `python3 scripts/validate_production_readiness.py` | passed (TC-GLOBAL-001..004) |
| `python3 -m unittest discover -s tests -p 'test_*.py'` | 422 tests, OK |

## Not run, and why

- Testcontainers integration tests: no Docker in the session. They compile; the
  local isolated PostgreSQL run above covers the same functions by hand.
- Browser end-to-end, capacity, availability, PITR and Raw custody drills:
  require a built environment this session does not have.
- Anything real: no Provider, account, credential, shared or production
  environment was reachable or authorized.
