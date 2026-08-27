# SLICE-V1-001 executable evidence

```yaml
document_type: executable_evidence_record
slice: SLICE-V1-001
executed_at: 2026-08-28
executed_on: LOCAL_ISOLATED_FIXTURES
assessment: REWORK_IN_PROGRESS
final_exact_commit_verified: false
remote_rework_ci: NOT_YET_PUBLISHED
external_business_systems_contacted: NONE
```

## Evidence boundary

These are successive source-bound worktree checkpoints, not a single final
commit or a Controller verdict. The accepted original Slice Contract and
Amendment-001 are identified in [Current State](../../00-governance/CURRENT_STATE.md).
All thirteen Frozen Findings remain open for final independent verification.
The [41-criterion matrix](acceptance-status.md) keeps local implementation,
external evidence and Owner/Gate conditions separate.

## Executed checkpoints

| Command / checkpoint | Observed result | Scope and artifact |
| --- | --- | --- |
| `make backend-check`, 134 | 845 unit/architecture + 373 integration; zero failures/errors/skips; LINE 12177/14480, BRANCH 3211/4456 | [610 unchanged inputs, method-level results and full report](rework-r1/full-backend-134/ARTIFACT-HASHES.json); includes all seven scanner corrections and profile-audit/numeric-boundary checks |
| `make backend-check`, 128 | 845 unit/architecture + 370 integration tests; zero failures/errors/skips; coverage gates pass | [Input hashes, counters and full log](rework-r1/full-backend-128/ARTIFACT-HASHES.json); all 609 input hashes unchanged after the run |
| Independent `make backend-integration`, 131 | 845 unit/architecture + 371 integration; zero failures/errors/skips | [610 unchanged inputs and full report](rework-r1/full-backend-131/ARTIFACT-HASHES.json), including stored-Raw crash/replay |
| Coverage, 131 | LINE 12155/14466; BRANCH 3209/4450 | JaCoCo in the same checkpoint; required 80%/70% unchanged |
| `make frontend-check`, 130 | 196 tests / 13 files; lint, types, format, coverage, build and bundle isolation pass | [Archived log and coverage counters](rework-r1/checks-132/ARTIFACT-HASHES.json) |
| `make frontend-browser`, isolated 129 | 11 Chromium scenarios pass | [Source-bound browser record](rework-r1/business-browser-129/ARTIFACT-HASHES.json); real signed JWT/servlet/SQL/worker, synthetic external identity and price port; database/API outages and existing-command recovery |
| Focused telemetry verification, 125 | 23 unit/architecture + 61 integration tests pass | Loopback-only access, spoofed forwarded-header refusal, real DB lock timeout/recovery, unknown/mismatch/gate state signals and custody failures |
| Python telemetry/plan controls, 126 | 16 tests pass | Closed telemetry schema, bounded transport, no secret logging, IAM/file/timer/plan mutation rejection |
| Terraform, 130 | Bootstrap 11 resources; staging/production foundation 67 and runtime 75; missing migration evidence refuses runtime | [Locked provider schema, logs and losslessly compressed mock plans](rework-r1/terraform-telemetry-130/ARTIFACT-HASHES.json); no real account or state |
| Managed bootstrap, 79/87 | Complete backend checks passed at their source checkpoints | [Managed bootstrap history](rework-r1/managed-pg-bootstrap-progress.md); standard/managed PG17 clean, upgrade, validation, refusal, equivalence and restore scenarios |
| Packaged runtime, 130 | Actual Boot classpath, 28 SQL resources, two image/artifact bindings, test-fixture absence and incorrect artifact/missing-envelope refusal pass | [Packaged runtime record](rework-r1/packaged-runtime-130/ARTIFACT-HASHES.json); no database or provider connections |
| Governance, 131 and negative coverage, 132 | 372 Python tests and both coverage-gate refusal checks pass | [Archived logs](rework-r1/checks-132/ARTIFACT-HASHES.json); backend JAR and JaCoCo XML preserved |

Every Maven command used the configured local JDK 21 and repository wrapper.
The final report must retain exact commands, source/commit identities, all
required local suites, GitHub jobs, CodeQL disposition and protected-byte proof.
None of the rows above substitutes for that final verification.

## Representative performance and restore

[Checkpoint 131](rework-r1/full-backend-131/representative-v1.json) records the
actual dataset, PostgreSQL/JVM settings, measurements, query plans, indexes,
asynchronous export and ephemeral database/object restore. The synthetic
profile contains 5,000 SKUs, 3 skewed stores, 360,000 source order records over
180 days, 720,000 sales facts, 825,240 metrics, 2,475,720 metric references,
285,660 findings and 30,000 recommendations. It is an explicit capacity
assumption, not an Owner-approved Pilot cohort or a production performance claim.

The API enqueues export work and publishes only a completed, immutable snapshot.
The representative export has 488,000 records, split into bounded parts; the
report includes exact bytes, hashes and elapsed times. Restore starts a separate
PG17 database, restores the dump, validates migration history and privileges,
refuses missing object bytes, and accepts only the exact restored content.
This is not Yandex PITR or a production restore.

Checkpoint 131 exported 488,000 rows / 164,224,134 bytes / 44 parts in 23,626 ms
after a 23 ms submission. The isolated dump/object recovery took 61,791 ms.
The [migration compatibility report](rework-r1/migration-compatibility-and-recovery.md)
records each changed V0011+ file, protected-byte proof and the recovery boundary.

See [failure-drill mapping](rework-r1/failure-drill-index.md),
[export procedure](../../06-runbooks/diagnostic-export.md),
[restore procedure](../../06-runbooks/database-restore-drill.md) and
[monitoring procedure](../../06-runbooks/operational-monitoring.md).

## Preserved failures and remaining work

The original reviewed Head had 380 unit/225 integration tests passing but failed
coverage (68.67% lines/52.39% branches), required CI and governance checks.
Its report remains in Git at `30d16e5d7db2d2190635a06fececd5883093a876`, and
[the frozen review](rework-r1/frozen/CONTROLLER-SLICE-V1-001-COMPREHENSIVE-DEEP-REVIEW-R1.md)
records those failures. They were not waived. Subsequent failed worktree attempts
remain in checkpoint logs; successful later runs are not retroactive passes.

Full regression after the latest changes, exact final commit/merge identities,
remote CI and seven CodeQL review-thread resolutions remain outstanding.
Real Yandex bootstrap/state secrecy/PITR/alert delivery, real OIDC/Marketplace/AI
provider interoperability, Gate EV/E and Owner cohort evidence remain separate
unauthorized boundaries. Local mocks, public documentation and test counts
cannot establish those facts. No Ready, merge, deployment or production
write enablement was performed.
