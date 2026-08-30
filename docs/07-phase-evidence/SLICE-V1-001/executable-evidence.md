# SLICE-V1-001 executable evidence

```yaml
document_type: executable_evidence_record
slice: SLICE-V1-001
executed_at: 2026-08-30
executed_on: LOCAL_ISOLATED_FIXTURES_AND_GITHUB_CI
assessment: ENGINEERING_IMPLEMENTATION_CLOSED
controller_final_gate: PASS_R2_ENGINEERING_FINAL_GATE
controller_comment_id: 5469390502
approved_engineering_head: f35327a584b980ec4acf7ace7c88e124d6d79709
approved_engineering_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b
approved_tested_merge: bcc3b37965003c3ea1af720ea847dc27fb473a9e
actual_squash_commit: d562b81f4f0271aa33a53b21ccaffc88b5610c0c
actual_squash_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b
actual_squash_sole_parent: db92cf2f8bd818f36dd8f5aa17b8589c4140b669
remote_rework_ci: PASS_12_OF_12_REQUIRED_CONTEXTS_AND_AGGREGATE_CODEQL
controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING
controller_bookkeeping_comment: 5469802650
owner_formal_closure: HUMAN_OWNER_ACCEPTED
owner_acceptance_comment: 5469935477
slice_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS
external_business_systems_contacted: NONE
deployment: NOT_EXECUTED
production_write_enabled: false
```

## Evidence boundary

The historical checkpoints below remain source-bound evidence. The current
engineering verdict is Controller comment `5469390502`, which accepted the exact
final Head/tree/signed tested merge, closed all ten frozen Supplemental R2 items
and reported zero unresolved BLOCKER or MAJOR finding. The Human Owner then
authorized the exact protected SQUASH merge recorded above.

This is engineering evidence, not real-provider, deployment or production
readiness evidence. Human Owner Formal Closure is preserved separately in the
[exact acceptance evidence](../../08-handoffs/OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md).
The [41-criterion matrix](acceptance-status.md) keeps the 24
engineering-verified rows separate from all 17 Amendment-002 rows deferred to
`RELEASE-V1-001`.

## Exact R2 final-gate evidence

The approved final Head passed 877 unit, 391 PostgreSQL integration, 65
architecture, 377 Python, 196 frontend and 11 browser tests. PR #22 passed all
12 protected required contexts plus aggregate CodeQL; aggregate annotations were
empty. The actual SQUASH commit has the same approved tree and exactly the
reviewed base as its sole parent. No deployment, Terraform apply, production DB,
Credential, real provider/Marketplace call, Gate EV, Gate E, Pilot or production
write occurred.

## Verified C3 checkpoint and CodeQL v1.1

C3 Head `d4bc5fe51605501da4ebc18c89c5d47ec8dc5ed0`, tree
`db3b2c4df0b46a94575e42989904e4fe80e41444`, is fully verified by
[the preserved C3 packet](rework-r1/checkpoint-c3/REPORT.md): independent full
backend runs 150/151 each pass 846 unit/architecture and 374 integration tests;
372 Python, 196 frontend and 11 browser tests pass. Local backend line/branch
coverage is 84.13%/72.14%; Linux CI is 84.22%/72.23%. The packaged migration
artifact and all three Terraform roots pass. All gates remain unchanged.

The C3 report predates dismissal and remains byte-for-byte historical evidence.
Its security blocker is superseded by the [v1.1 execution record](rework-r1/codeql-v1.1/EXECUTION-RECORD.md):
Owner-authorized alerts 66, 73, 74, 75 and 76 were individually dismissed with
exact comments; their five threads were resolved after readback. Other 26
PR alert records and six unrelated thread records were unchanged. All 11
threads are resolved; all 13 checks, including aggregate CodeQL, are SUCCESS.
The original empty default-branch inventory is not an all-ref inventory.

That historical requirement was satisfied by the exact R2 final Head, signed
tested merge, remote checks and Controller PASS recorded above. The older C3
identities remain historical and are not promoted to final R2 evidence.

## Preserved earlier checkpoints

| Command / checkpoint | Observed result | Scope and artifact |
| --- | --- | --- |
| `make backend-check`, 143 | 846 unit/architecture + 374 integration; zero failures/errors/skips; LINE 12186/14485, BRANCH 3218/4461 | [610 unchanged inputs and full report](rework-r1/full-backend-143/ARTIFACT-HASHES.json); CI follow-up source, boundary tests and complete performance/restore regression |
| `make backend-check`, 134 | 845 unit/architecture + 373 integration; zero failures/errors/skips; LINE 12177/14480, BRANCH 3211/4456 | [610 unchanged inputs, method-level results and full report](rework-r1/full-backend-134/ARTIFACT-HASHES.json); original source-alert corrections and profile-audit/numeric-boundary checks; CSRF disposition remains separate |
| Independent `make backend-integration`, 136 | 845 unit/architecture + 373 integration; zero failures/errors/skips; same coverage and JAR hash as 134 | [C1 backend inputs and full report](rework-r1/full-backend-136/ARTIFACT-HASHES.json) |
| CI follow-up focused run, 141 | 105 unit + 127 integration; zero failures/errors/skips | [Exact command and prior failed attempts](rework-r1/checks-142/summary.json); includes 64-part ceiling, exact outbound body and quoted managed-role password tests |
| Governance/frontend, 144 | 372 Python and 196 frontend tests; validators, lint, format, types, coverage, build and bundle checks pass | [Compressed logs](rework-r1/checks-142/ARTIFACT-HASHES.json) |
| Terraform clean copy, 142 | All three environments pass with the three exact provider lockfiles and no copied `.terraform` cache | [Fresh-copy verification](rework-r1/checks-142/terraform-summary.json); readonly init retained, mock plans only |
| Remote C1 workflows | All 11 required contexts pass, including 196 frontend unit and 11 Chromium scenarios; additional infrastructure and aggregate CodeQL fail | [C1 exact Head/merge/parents and workflow evidence](rework-r1/remote-ci-c1/summary.json); not final CI approval |
| Remote C2 source analysis | All 26 source-corrected CodeQL alerts are fixed; only 66 and 73–76 remain open | [C2 exact analysis and CI snapshot](rework-r1/remote-ci-c2/summary.json); remote dismissals have not been executed |
| Cross-platform Terraform lock, 147/148 | Origin-registry generation adds only the Linux unpacked package hash; all previous hashes and version pins retained; clean local verification passes | [Signed provider output, three-root lock identity and plans](rework-r1/terraform-cross-platform-148/summary.json); the preceding C2 Linux validation failure is preserved |
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

C1 and C2 failures above remain historical evidence. C3 closes the Linux
provider-lock validation failure. Matrix v1.1 supersedes v1.0 and its API-length
blocker; the five exact dispositions have executed. Current canonical sources
are the R2 final handoff, Controller Engineering Final Gate, exact SQUASH
identity, C3 receipts and v1.1 before/after evidence.
Real Yandex bootstrap/state secrecy/PITR/alert delivery, real OIDC/Marketplace/AI
provider interoperability, Gate EV/E and Owner cohort evidence remain separate
unauthorized boundaries. Local mocks, public documentation and test counts
cannot establish those facts. The exact protected merge was performed; no
deployment or production-write enablement was performed.
