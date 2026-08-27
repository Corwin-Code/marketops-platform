# PR #20 rework checkpoint — security, performance and accepted managed bootstrap

> Historical pre-publication checkpoint. Its HEAD, pending-work statements and
> counts describe the worktree at checkpoint 131, not the eventual published
> candidate. See [the current evidence index](../executable-evidence.md) and the
> final handoff for later verification and publication identities. No historical
> failure or pending state below is retroactively converted to a pass.

```yaml
document_type: incomplete_rework_checkpoint
as_of: 2026-08-28
scope: LOCAL_UNCOMMITTED_CANDIDATE
overall_result: INCOMPLETE
frozen_findings_closed: 0
acceptance_criteria_completed: 0
immediate_next_actor: CODEX
immediate_next_action: CONTINUE_AUTHORIZED_REWORK_AND_VERIFICATION
f010_decision_state: AMENDMENT_001_HUMAN_OWNER_ACCEPTED
f010_implementation_state: IN_PROGRESS
final_closure_handoff: NOT_READY
```

## Accepted managed bootstrap direction

The Human Owner accepted exact [Amendment-001](../../../03-work-items/SLICE-V1-001-AMENDMENT-001-YANDEX-MANAGED-PG-BOOTSTRAP.md)
on 2026-08-28. It authorizes PG17 and provider-managed extensions with one
attested V0002 executor in the explicit managed profile. The original Contract,
V0001–V0010 bytes and standard SQL V0002 behavior remain unchanged. The prior
[Decision Request](DECISION-REQUEST-S1-F010-MANAGED-PG-BOOTSTRAP.md) is resolved
as an implementation direction, not as a completed finding. Local A-010
clean/negative/equivalence/upgrade/restore tests now pass; exact final-commit
verification, remote CI and real staging evidence remain outstanding.
This remains existing S1-F010, not a new Frozen Finding Set. The test counts below distinguish the
earlier compatibility runs from subsequent security/coverage checkpoints; none
is a final exact-commit Controller closure result.

## Exact Git and authority state

| Item | Value |
| --- | --- |
| Repository | `Corwin-Code/marketops-platform` |
| Branch | `feat/SLICE-V1-001-sku-growth-profit-loop` |
| Base / local `origin/main` | `89fc29be45327b592a9bcbeffbfec54c96fb66ed` |
| Starting and current HEAD | `30d16e5d7db2d2190635a06fececd5883093a876` |
| Starting and current HEAD tree | `13b1b789cd4cff292d0d6ab24daca976afbba6da` |
| Original commits above base | 13; not rewritten |
| Rework commits / push | None; rework remains in the shared worktree |
| PR #20, checked 2026-08-28 | OPEN / DRAFT / UNMERGED; original Head |
| Tested final merge/tree/parents | Not available; there is no final rework commit |
| Contract SHA-256 | `0bf558d6539e9620424058e31ccd03062a5195642b58434c1ce11d8d861db3d5` |
| Frozen Finding Set SHA-256 | `8e5bd4ee3f5727bff9e9d1a7fc58739c635e6fd75483f28a4f302fcb222ae3a8` |
| Accepted Slice Amendments | SLICE-V1-001-AMENDMENT-001, SHA-256 `8a36bbe0f2cd1d8e40efb171d368d8c4058ecc913da2a76f43f7e0a14de6854d` |

Contract and V0001–V0010 hashes were rechecked against
[starting-identity.json](starting-identity.json); all match. The four focused
JUnit reports, protected hashes and pinned public-source identity are recorded
in [migration-compatibility-evidence.json](migration-compatibility-evidence.json).
Do not mistake HEAD for the modified/tested worktree, or reset the uncommitted
work as if it were disposable.

## Candidate work and outstanding verification

All thirteen findings remain **OPEN**. This table summarizes work; it is not a
closure verdict and does not replace the Frozen Finding Set's requirements.

| Findings | Candidate changes present | Still required |
| --- | --- | --- |
| S1-F001–F002 | Database command authority, exact target/approval and wire binding, immutable Raw completion, causal APPLY evidence, fresh conditional RESTORE and separate RESTORE idempotency. Thirty-five registry wire scenarios and eighteen worker integration tests are included in full checkpoints 79/87. | Final same-class scan and exact final evidence mapping. |
| S1-F003–F004 | Shared bounded outbound destination policy; descriptor-based secret reads; object-level organization/store authorization; exact MFA claim, mandatory JWT expiry, live revocation and step-up checks with real signed-token/DB tests. | Complete final same-class/browser regression and CodeQL/thread closure. |
| S1-F005–F006 | External-I/O transaction separation, durable acquisition states, shared database quota, bounded response/parser/normalization handling. | Final crash/concurrency/replay evidence and representative outage/backlog drill. |
| S1-F007 | V0027 account-bound, audited registry verification flow and stricter operation semantics; synthetic verification tests. | Final DB path coverage and closure audit; real-account capabilities remain UNVERIFIED. |
| S1-F008–F009 | Typed complete imports, exact row/value handling, XLSX workbook semantics; versioned per-kind AI schemas and recovery/UI changes. | Complete regression, browser flow, same-class scan and criterion-specific evidence. |
| S1-F010 | PG17 managed resolver/executor, hash-pinned bootstrap and per-attempt custody, prior-release upgrade/failure resume, strict standard V0002, packaged JAR/image checks, foundation/runtime sequencing, host artifact/restart guards and synchronized public input examples. Private topology and write-only secrets remain preserved. | Final host/runtime/alert provisioning audit, representative recovery evidence and exact-commit CI. No real apply, VM restart, Yandex staging or state secrecy proof. |
| S1-F011 | Meaningful security/parser/normalization tests; full backend checks now pass unchanged 80%/70% coverage; CI no longer excludes IT execution; negative coverage checks preserve build artifacts. | Final exact-commit full verification, seven review threads, CodeQL and required remote CI. |
| S1-F012 | PG17 5,000-SKU / 360,000-order benchmark, V0028 asynchronous export with DB fencing/ownership/custody, 488,000-record (164 MB) export and ephemeral DB/object restore pass locally at checkpoint 114. Authenticated browser journey through real servlet/SQL/worker/custody passes at 121. | Final full regression and exact-commit CI; actual alert creation/delivery remains external. Real provider PITR and production capacity remain unproven. |
| S1-F013 | Conservative 41-criterion matrix, current branch state and frozen-input binding. | Final as-built/runbook/evidence synchronization, exact final tree/CI and Controller handoff. |

The [41-criterion matrix](../acceptance-status.md) remains conservative:
32 `IMPLEMENTATION_DEFECT`, 8 `IMPLEMENTED_UNPROVEN`, 1 `OWNER_PENDING`.
No external pending requirement was relabeled as met.

Candidate migration edits are V0014 (typed imports), V0017 (AI evidence/state),
V0020 (command authority), V0021 (API shape), V0022 (durable acquisition),
V0024 (write-operation constraints), V0025 (attempt/completion authority),
V0026 (dependent function rename propagation), plus new V0027 (account-bound
verification) and V0028 (bounded diagnostic exports). Their final compatibility report/hash inventory is still due.
V0001–V0010 were not edited.

## Executed local evidence — exact scope matters

Full backend 128 passes 845 unit/architecture and 370 integration tests, with
LINE 12035/14466 and BRANCH 3201/4450. All 609 recorded input hashes remained
unchanged. [Artifacts](full-backend-128/ARTIFACT-HASHES.json) include the full
representative performance/export/restore result. Independent full
`make backend-integration` 131 is running with the additional real PG17 stored
Raw replay test; no final-commit result is claimed.

[Browser 129](business-browser-129/ARTIFACT-HASHES.json) passes 11 scenarios
with 659 unchanged recorded inputs. It exercises actual signed JWT/SQL, source
provenance, approval, command/readback, asynchronous download, new-login command
recovery, API interruption and local DB stop/restart. Its own Compose database
was removed; original local configuration was unchanged. External identity and
Marketplace ports remain synthetic. Full frontend 130 passes 196 tests.

[Stored Raw replay 130](stored-raw-replay-130/ARTIFACT-HASHES.json) proves on PG17
that parser/missing-byte failure leaves progress unchanged, a crash after fact
commit can replay the same stored bytes without a duplicate logical fact, and
replay makes no additional source call. The telemetry snapshot, two-second DB
failure/recovery, loopback/forwarded-header boundary and state signals pass in
full 128. Python transport/plan mutation tests pass; real alert delivery is
still unverified. Terraform 130 uses the locked provider, 67-resource foundation
and 75-resource runtime plans, with launch refused without migration evidence.
Packaged runtime 130 verifies all 28 migrations and excludes test-only browser
authority from the production JAR.

Logs remain under `/private/tmp/marketops-s1-r1/`. These are successive worktree
checkpoints, not results on a single final commit.

[Async export checkpoint 114](async-export-114/summary.json) retains focused
PG17/managed-profile tests, actual snapshot query plans, the full representative
performance/export/restore report and frontend checks. The 488,000-record export
used 44 parts / 164,224,134 bytes and completed with verification in 23,525 ms;
submission returned 202 in 26 ms. Local standard PG17 database/object restore
completed in 61,530 ms and revalidation applied zero migrations. Focused 113
passed 29 unit / 25 integration tests; performance 114 passed 9 unit / 1
integration tests; frontend 112 passed 177 tests with unchanged thresholds.
These are not final full-backend or remote-CI results. The backend input inventory
in that checkpoint was captured after the run, not at a final commit.

Latest managed-bootstrap checkpoints are described in
[managed-pg-bootstrap-progress.md](managed-pg-bootstrap-progress.md).
`make backend-check` at 79 and independent `make backend-integration` at 87 each
passed 836 unit and 355 integration tests. Checkpoint 87 binds 598 unchanged
backend input files and JAR SHA-256
`4ee1bd781a6f5e97120258951c3420175539ce4356e719f4e705611373d2e6c1`.
Its unchanged coverage gates pass at LINE 11766/14208 and BRANCH 3141/4384.
Packaged checkpoint 99 verifies all 27 SQL resources through the actual Boot
classpath, two local image/JAR bindings and incorrect-artifact/missing-manifest
refusals, without a database connection. Terraform checkpoint 100 passes
foundation (65 resources), missing-migration-evidence refusal and complete
runtime (73 resources) plans in both environments, plus state bootstrap (11).
Governance checkpoint 101 passes 363 Python tests; subsequent public-example
and runtime checks at 102 pass 21 tests. These results do not close a finding.

The subsequent [wire-intent checkpoint 44](wire-intent-checkpoint-44.json)
records seven reproduced failures, their corrections, thirty-three APPLY /
STATUS_ENQUIRY / READBACK / RESTORE cases and a passing `make backend-check`
(821 unit + 340 integration). It binds the modified source files and logs;
checkpoint 35's source manifest remains historical. The new
[performance profile](performance-profile-v1.md) and
[checkpoint 48](performance-checkpoint-48.json) are synthetic local evidence and
do not claim actual Owner cohort sizing or completed S1-F012 evidence. Earlier
46/47 tracing measurements remain historical; checkpoint 48 preserves and checks
the actual service transaction.

[Backend/performance checkpoint 49](backend-performance-checkpoint-49.json)
subsequently passes `make backend-check` (821 unit + 342 integration) and
`make governance` (358 Python tests). It binds the source manifest, packaged JAR,
coverage and performance report after the real read-budget tests and tracing
correction. Independent `make backend-integration` still needs to be repeated
on the final candidate; the earlier checkpoint 36 is historical.

The subsequent S1-F002 reproduction confirmed the missing own-APPLY and stale
RESTORE authorization gaps. V0020/V0025 and the wire guard now require a causal
ACCEPTED/UNKNOWN APPLY, a latest matching readback younger than thirty seconds,
the current fence and a distinct RESTORE idempotency key. Real DB and worker
tests cover absence/rejection, staleness, conditional failure, unknown response
and unreadable final observation. These are in-scope corrections included in
79/87; their final exact-commit traceability and Controller closure are pending.

| Command / log | Result and limitation |
| --- | --- |
| `./mvnw -B clean verify` — `backend-full-verification-wave-c-16.log` | Earlier full run: 668 unit + 313 integration tests passed. **BUILD FAILURE** at unchanged JaCoCo gate: 10,877/13,689 lines (79.46%), 2,698/3,960 branches (68.13%). Predates migration-runner additions. |
| `./mvnw -B -Dtest=ManagedMigrationRunnerTest,ApplicationConfigurationTest -Dit.test=ManagedMigrationRunnerIT,FlywayMigrationIT integration-test failsafe:verify` — `migration-compatibility-verification-23.log` | Earlier focused run: 26 unit + 13 integration, all pass. Clean V0001–V0027, replay/validate, role-drift rejection and V0002 duplicate-extension reproduction. Does not run the coverage goal. |
| Packaged migration CLI via `PropertiesLauncher`, without arguments — `migration-cli-no-arguments-23.log` | Expected exit 1, exactly `MIGRATION_FAILED`; no connection. Server JAR manifest still selects `MarketOpsServerApplication`. |
| `python3 scripts/verify_terraform.py --terraform /private/tmp/marketops-s1-r1/terraform-1.14.9/terraform --output /private/tmp/marketops-s1-r1/infrastructure-complete-20` | Local PASS: fmt, init without backend, real provider-schema validate, mock plan and static assertions for bootstrap/staging/production. 11/73/73 resources. Not actual account/state/runtime evidence. |
| `python3 -m unittest tests.test_validate_terraform_plan` — `terraform-mutation-tests-20.log` | 6 test methods pass, including negative network, identity, TLS, custody and secret-persistence mutations. |
| `python3 -m unittest tests.test_yandex_runtime` — `yandex-runtime-tests-19.log` | 9 synthetic transport/process tests pass. No real metadata, Lockbox or Docker runtime calls. |
| `python3 -m unittest discover -s tests -p 'test_*.py'` — `python-pre-infra-regression-17.log` | Earlier 343 tests pass; predates the new infrastructure tests/documents. |
| `python3 scripts/validate_governance.py` — `governance-compatibility-checkpoint-25.log` | PASS after the decision/checkpoint documentation. The secret detector was not weakened; a negative plan test now generates its test value at runtime. |
| `python3 scripts/validate_production_readiness.py` — `readiness-compatibility-checkpoint-25.log` | PASS over 914 files, all three global checks. |
| `python3 -m unittest discover -s tests -p 'test_*.py'` — `python-compatibility-checkpoint-25.log` | Latest 358 tests pass, including the infrastructure mutation/runtime tests. |
| `git diff --check` and `git diff --check origin/main...HEAD` | PASS for current tracked worktree edits and the original reviewed commit range. |
| `make backend-check` — `backend-check-full-29.log` | PASS: 781 unit + 316 integration; lines 11,070/13,746 (80.53%), branches 2,841/4,028 (70.53%). Before subsequent signed-bearer/filesystem changes. |
| `bash scripts/verify_coverage_thresholds.sh backend` — `coverage-negative-29.json` | PASS: 100% thresholds fail for the coverage reason; JAR, execution data and XML report hashes unchanged. |
| Packaged migration CLI without arguments — `migration-cli-refusal-29.json` | PASS: logger-only diagnostic emits exactly `MIGRATION_FAILED`, expected exit 1. The preceding architecture failure was fixed without altering its test. |
| `./mvnw -B -Dtest=MountedSecretResolverTest -Dit.test=MountedSecretFilesystemIT integration-test failsafe:verify` — `secret-boundary-linux-31.log` | PASS: 36 unit tests and the disconnected pinned Linux runtime's 19 filesystem scenarios. Unsupported host filesystems refuse resolution. |
| Signed-token focused runs — `signed-bearer-32.log`, `signed-bearer-33.log` | Run 32 reproduced missing-`exp` acceptance (HTTP 200); run 33 passes 64 unit + 21 integration after mandatory expiry validation. No actual OIDC/JWKS service was contacted. |
| `make backend-check` — `backend-check-full-35.log` | PASS: 821 unit + 340 integration; lines 11,294/13,781 (81.95%), branches 2,900/4,052 (71.57%). Includes 23 signed-token servlet/DB cases and the Linux filesystem contract. |
| `make frontend-check` — `frontend-check-35.log` | PASS: lint, formatting, types, 150 tests, build and bundle isolation. Coverage: statements 84.96%, branches 79.12%, functions 93.16%, lines 85.15%. |
| `make governance` and separate Python discovery — `governance-35.log`, `python-tests-35.log` | PASS: governance, all readiness checks over 922 files, and 358 Python tests. Before the subsequent checkpoint-document updates. |
| `make frontend-browser` in the isolated wrapper — `browser-isolated-36-result.json`, `browser-suite-36.log` | PASS: all 8 Playwright cases against the built console/real local backend; real PostgreSQL outage/recovery and refusal checks. Identity/business presentation uses browser fixtures, not provider evidence. New synthetic Compose project/volume removed; root local config unchanged. |
| `make backend-integration` — `backend-integration-full-36.log` | Independent clean full PASS: 821 unit + 340 integration, same 81.95% line / 71.57% branch coverage. |
| Backend/frontend coverage negative checks — `backend-coverage-negative-36.json`, `frontend-coverage-negative-36.log` | Both PASS: 100% thresholds correctly fail for coverage. Backend JAR, execution data and XML hashes remain unchanged. |
| Governance/readiness documentation recheck — `governance-security-docs-36.log`, `readiness-security-docs-36.log` | PASS over 925 files before the summary JSON was written; final checkpoint recheck follows. |
| `make governance` — `governance-security-final-37.log` | PASS after the checkpoint JSON and input manifest were added: readiness over 926 files and 358 Python tests. |

The focused migration run first exposed a new JAR packaging ambiguity (two main
classes). The Maven plugin now explicitly retains the server entrypoint; the
successful focused run includes packaging. Earlier failures remain in logs 21
and 22; they are not represented as passing evidence.

Checkpoint 35 is bound to the 732-file
[verification input manifest](verification-inputs-security-35.json), SHA-256
`b4398f39299fe09696eead7ead6b6878e7fda4574e458af1c6cc57cb28018d15`.
It includes application/test/infrastructure/CI inputs, not later narrative
documentation edits. The final publication will need a fresh complete identity.
The [machine-readable security/coverage checkpoint](security-and-coverage-checkpoint.json)
records exact full-run counts, coverage counters, artifact/log hashes, protected
contract/migration hashes, browser isolation/cleanup and the remaining limits.
Run 34 exposed a test-fixture JDBC `Instant` binding error; the fixture now binds
a SQL timestamp and the unchanged negative revocation assertion passes in the
full run. No failed checkpoint is counted as successful verification.
The first browser attempt (35) was blocked before any browser scenario by macOS
`bootstrap_check_in` permission denial. Attempt 36 used the approved escalated
runtime, with no test changes, and passed. Its generated database credentials
stayed in process memory; no existing database/volume was migrated or stopped.

## Explicitly incomplete / not run on final tree

- Final `make governance`, backend/frontend checks and complete browser journey;
  a fresh full pass after all remaining scope and final publication. CI and Make
  backend verification now include integration tests and the original coverage
  thresholds; that local correction is not remote CI evidence.
- Container builds and real container bootstrap/restart/secret/health tests;
  foundation → migration → runtime orchestration and validated host image.
- Exact-Head GitHub infrastructure workflow and all required CI; review-thread
  responses/resolutions. No rework commit has been published.
- Final source/commit binding of representative performance, asynchronous
  export and restore/failure evidence; canonical reconciliation and remote CI.
- Real Yandex, OIDC/MFA, marketplace and AI-provider evidence; state/backup/alert
  delivery verification. They remain external and require their own authority.

No threshold or control was lowered. No production/provider side effect,
credential provisioning, real marketplace write, Ready, merge or approval was
performed. The desired eventual next actor remains GPT-5.6 Sol Pro Controller
for `CONTROLLER_SLICE_V1_001_FINAL_CLOSURE_VERIFICATION`, after the decision,
remaining rework, exact publication and complete verification—not now.

```text
MERGE_AUTHORIZATION: NOT_GRANTED_BY_CODEX
DEPLOYMENT: NOT_AUTHORIZED
PRODUCTION_ENABLEMENT: NOT_AUTHORIZED
GATE_EV: NOT_AUTHORIZED
GATE_E: NOT_AUTHORIZED
```
