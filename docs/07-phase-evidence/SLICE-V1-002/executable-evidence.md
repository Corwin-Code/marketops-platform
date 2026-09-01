# SLICE-V1-002 executable evidence

```yaml
document_type: executable_evidence_record
slice: SLICE-V1-002
contract_sha256: d89ea296d0ff854c7d57895b448f9467a22106881d26de4c62a0e8629600556e
owner_acceptance_evidence_sha256: 4e243c85412c549975ef70ee46bb09502a3157c0d4bb6a1b2679b7745b96538e
source_protected_main: 8a7076877374391cf851481c023dfb0e621ab712
source_protected_main_tree: b87ec67d0242eb86e15698ab95430c37f0fe4328
reviewed_source_head: c5d896a4ca01ecdc6d4add85fb4fd2e33ba8e4c6
reviewed_source_tree: c94341232b5fa67b5c40a1e6be121a7696e748c4
frozen_finding_set_sha256: 60589cfa9303d17e71910e085fd18f1d68b87dd9e3b56a99bf6f799879ebcf94
executed_at: 2026-09-01
executed_on: LOCAL_ISOLATED_CONTAINERS_AND_SYNTHETIC_FIXTURES
assessment: ROOT_CAUSE_REWORK_VERIFIED_LOCAL
engineering_findings_addressed: 18_OF_18_PENDING_INDEPENDENT_CLOSURE_VERIFICATION
engineering_closure: NOT_CLAIMED
controller_verdict: NOT_CLAIMED
owner_formal_closure: NOT_CLAIMED
remote_publication: DRAFT_PR_26_OPEN_REQUIRED_CHECKS_PASS
draft_pr: 26
draft_pr_url: https://github.com/Corwin-Code/marketops-platform/pull/26
required_checks: PASS_12_OF_12_PLUS_AGGREGATE_CODEQL
external_business_systems_contacted: NONE
deployment: NOT_EXECUTED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
production_write_enabled: false
```

## Evidence boundary

Everything below ran locally against isolated containers and synthetic
fixtures. No marketplace, model provider, identity provider or cloud account
was contacted, no credential was used, and nothing was deployed. The Slice has
no controlled write target, so there is no write path to evidence and none is
claimed.

This is an implementation record rather than a closure claim. It reports what
runs today, and the [acceptance status](acceptance-status.md) records which
criteria that leaves unproven and why.

## Environment

The database evidence is real PostgreSQL, not an in-memory substitute.

| Component | Version |
| --- | --- |
| Backend runtime | Java 21.0.10, Spring Boot 4.1.0 |
| Database under test | PostgreSQL 18.4 via Testcontainers 2.0.5 |
| Migration inventory | V0001–V0035; V0001–V0033 unchanged; forward-only V0034/V0035 repairs |
| Frontend toolchain | Node 24.20.0, npm 11.19.0 |
| Browser | Chromium via Playwright 1.62.1 |
| Governance tooling | Python 3.11.15 |

## Commands and results

| Command | Observed result | Scope |
| --- | --- | --- |
| `./mvnw -B -ntp -Dtest='DemandPolicyEngineTest' test` | 12 passed | deterministic D7/D14/D30 selection, interval-union exposure, censoring, carry-forward and its expiry |
| `./mvnw -B -ntp -Dtest='ChannelRiskCalculatorTest' test` | 8 passed | channel independence, fresh-zero and unsellable escalation, staleness, policy-derived lanes |
| `./mvnw -B -ntp -Dtest='CompanyRiskCalculatorTest' test` | 20 passed | fail-closed company answer, deduplication, time-phased inbound eligibility, stock-state authority and conservative proof |
| `./mvnw -B -ntp -Dtest='PriorityPolicyTest' test` | 5 passed | lane band, visible factors, determinism |
| `./mvnw -B -ntp -Dtest='ProfitLaneResolverTest' test` | 5 passed | settled-positive, operational fallback, provisional estimate, stale block and decisive settled-negative profit |
| `./mvnw -B -ntp -Dtest='ReturnQualityAssessmentTest' test` | 8 passed | no report versus complete zero-return versus observed returns, freshness/completeness/conflict, retention, QC disposition and sellability authority |
| `./mvnw -B -ntp -Dtest='AvailabilityReconciliationWorkerTest' test` | 3 passed | failure isolation, full keyset paging and 5,000-variant hourly capacity margin |
| `./mvnw -B -ntp -Dtest='AvailabilityTargetedWorkerCapacityTest' test` | 1 passed | 5,000 CRITICAL response observations inside P95 and hard bounds |
| `./mvnw -B -ntp -Dtest='AvailabilityExceptionRevalidationTest' test` | 10 passed | accepted exception invalidation on cause, scope, severity, consequence, recurrence, direct/delegated authority, policy and evidence conflict |
| `./mvnw -B -ntp -Dtest='AvailabilityRunbookConformanceTest' test` | 5 passed | isolated executable stale-source, ownership-conflict, policy-blocker, backlog/SLO and failed-reconciliation drills |
| `./mvnw -B -ntp -Dtest='WorkActivationPolicyTest' test` | 8 passed | CRITICAL now, blocker on first sighting, HIGH only once sustained, WATCH never, and the two clocks |
| `./mvnw -B -ntp -Dtest='ExceptionMaterialityPolicyTest' test` | 7 passed | proportional authority, requester separation, the untranslated foreign amount, the maximum period |
| `./mvnw -B -ntp -Dtest='OutcomeConditionTest' test` | 5 passed | what repaired means for a shortage and for a defect, and why unusable evidence repairs nothing |
| `./mvnw -B -ntp -Dtest='AvailabilitySloTest' test` | 3 passed | the hard bound per observation and the distribution target per window |
| `./mvnw -B -ntp -Dtest='AvailabilityNonGoalsTest' test` | 4 passed | no write path, no stock command in any migration, no adjacent inventory product, no STOCK_CHANGE action |
| `./mvnw -B -ntp -Dtest='NoFalseSafetyAdversarialTest' test` | 14 passed | the values a removed gate would produce cannot be constructed |
| `./mvnw -B -ntp -Dtest='*ArchitectureTest' test` | 65 passed | module, boundary, acquisition-authority and rule-sensitivity suites with the new module registered |
| `./mvnw -B -ntp -Dit.test='FlywayMigrationIT' verify` | 11 passed | migration history, table inventory, seeded role and action matrix |
| `./mvnw -B -ntp -Dit.test='AvailabilityRiskSchemaIT' verify` | 11 passed | schema refusals, actor/scope ownership, return-ledger chains and policy authority against PostgreSQL 18.4 |
| `./mvnw -B -ntp -Dit.test='AvailabilityRiskFlowIT' verify` | 12 passed | the loop end to end, policy lifecycle/audit/recalculation, return re-entry and governed inbound |
| `./mvnw -B -ntp -Dit.test='AvailabilityCaseLifecycleIT' verify` | 25 passed | activation, the two stages, automatic outcome verification, reopen, escalation and exception governance |
| `./mvnw -B -ntp -Dit.test='AvailabilityRecalculationLoopIT' verify` | 11 passed | total feed/replay, targeted response, dropped-trigger repair, abandoned-worker restart, late stale evidence and incidents |
| `./mvnw -B -ntp -Dit.test='AvailabilityConsoleAuthorizationIT' verify` | 15 passed | every availability, inbound, policy and return route over real product/store/action scope, sensitive-operation audit, and delegation grant/use/revoke/revalidation against a migrated database |
| `./mvnw -B -ntp -Dit.test='ControlBoundaryCompletenessIT' verify` | 10 passed | every new table registered in the route inventory |
| `./mvnw -B -ntp -Dit.test='ControlEpochTriggerIT' verify` | 11 passed | no epoch trigger on a `NO_ROUTE` table |
| `./mvnw -B -ntp -Dit.test='DatabasePrivilegeIT' verify` | 11 passed | least-privilege grants unchanged |
| `./mvnw -B -ntp clean verify` | BUILD SUCCESS in 19:01; 1,028 unit and 465 integration tests passed, 0 failed/errors/skipped | the complete backend unit, PostgreSQL integration, actual-path capacity, export/restore and combined coverage suite |
| `python3 scripts/validate_governance.py` | passed | canonical governance contract, including the flip to SLICE-V1-002 |
| `python3 scripts/validate_production_readiness.py` | passed over 2,533 files | TC-GLOBAL-001 through TC-GLOBAL-004 |
| `python3 -m unittest discover -s tests -p 'test_*.py'` | 385 passed | governance tooling, including V0030 through V0035 and the exact safe index-replacement control |
| `bash scripts/verify_coverage_thresholds.sh backend` | passed | mutation guard independently rejected any backend coverage report below the fixed bundle LINE 0.80 or BRANCH 0.70 gates |
| `python3 scripts/verify_migration_artifact.py` | passed | the packaged application contains the exact ordered V0001-through-V0035 migration inventory |
| `python3 scripts/verify_terraform.py --terraform /private/tmp/marketops-terraform-1.14.9/terraform` | passed; bootstrap 11 resources, staging 75 and production 75 | Terraform 1.14.9 static init/validate/plan inspection only; no provider call or apply |
| `make verify-local-config` | passed | owner-only root configuration, no backend-side secret copy, absolute import and readiness requiring the generated database password |
| `make supply-chain` | passed; backend and frontend CycloneDX 1.6 inventories written | dependency convergence, license inventory and reproducible SBOM generation |
| `npm run lint && npm run format:check && npm run typecheck && npm run test:ci && npm run build && npm run verify:bundle` | 227 passed; all static, coverage, build and bundle gates passed | frontend suite including queue, cases and the governed authority panel |
| `COMPOSE_PROJECT_NAME=marketops-browser-final3 make frontend-browser` | 12 passed in 42.8 seconds | the browser suite against the real backend and an isolated V0035-migrated PostgreSQL database, including real database outage/recovery |
| Draft PR #26 remote required checks | 12/12 required contexts passed; aggregate CodeQL passed with no remaining new alert | exact remote backend, frontend, architecture, governance, infrastructure, dependency and security gates |

## Coverage

The JaCoCo gate is bundle-wide LINE 0.80 and BRANCH 0.70. Measured over the
merged unit and integration run:

| Measure | Observed | Gate |
| --- | ---: | ---: |
| Line | 0.856820 | 0.80 |
| Branch | 0.714626 | 0.70 |
| Line, `availabilityrisk` only | 0.823295 | — |

## What the database refuses

`AvailabilityRiskSchemaIT` asserts refusals rather than happy paths, because a
constraint that has never rejected anything is a comment:

| Case | Refusal proven |
| --- | --- |
| `TC-AVAIL-DB-001` | two overlapping active lead-time versions of one scope |
| `TC-AVAIL-DB-002` | a policy scope naming identifiers it is not scoped by |
| `TC-AVAIL-DB-003` | a company child claiming health on blocked evidence |
| `TC-AVAIL-DB-004` | a provisional child with no proof terms |
| `TC-AVAIL-DB-005` | a second live case for one cause, and a verified success with no recorded action |
| `TC-AVAIL-DB-006` | an approval by the requester where separation is required |
| `TC-AVAIL-DB-007` | an active accepted exception with no expiry |
| `TC-AVAIL-DB-008` | a second pending recalculation for one variant |
| `TC-AVAIL-DB-009` | any `DELETE` of case, event, decision, SLO or attestation history |
| `TC-AVAIL-DB-010` | a free-text acknowledgement offered as a structured action |
| `TC-AVAIL-DB-011` | an exception, case, child or actor spliced across organization ownership |

## Defects this work found and fixed

Recorded rather than removed, because a review that cannot see what the tests
caught cannot judge whether the tests are good enough.

| Defect | How it surfaced |
| --- | --- |
| A company demand window nothing could be observed in kept a null censoring reason, so it looked fully observed | a null-rate failure in `AvailabilityRiskFlowIT` |
| A carried-forward rate could be selected from a window with no observable time | the same failure |
| The conservative proof was recovered by scanning for a quoted key; PostgreSQL renders jsonb with a space after the colon, so every proof came back empty | `TC-AVAIL-FLOW-007` |
| Carry-forward looked for its candidate in the current windows, where by definition none is eligible, so it could never engage | adversarial reading of the diff; now reads stored history and is covered by `TC-DEMAND-006` and `TC-DEMAND-007` |
| An unknown or stale platform quantity blocked a safe company answer even where the declaration already said those units were the warehouse's own | adversarial reading; `TC-COMPANY-016`, `TC-COMPANY-017` |
| A demand rate near zero produced a cover whose projected stockout date overflowed the instant it was expressed as | adversarial reading; `TC-ADV-008` |
| An automatic case activation was attributed to `system` with an organization identifier in the entity-code field | adversarial reading; attribution now names the component that acted |
| Two availability panels each rendered their own "your session has ended" alert, so an operator met the same sentence twice and the message about the panel in front of them was buried | browser verification; it also broke the existing `TC-BROWSER-012` |
| The console recorded the case's accountable role as the actor's role, so a data owner who repaired a mapping would appear in the journal as procurement | adversarial reading; `TC-CONSOLE-007` now pins the role the person actually holds |
| An authority-blocked exception decision asserted that separation was required whatever the policy said | adversarial reading; it now records the rule that applied, except where nothing sized the decision at all |
| Automatic outcome verification did not exist: the state machine was complete but nothing observed the risk, so a case would have waited in `VERIFYING` for a person to click | reading the mandatory product path against the code; `V0033` and `OutcomeCondition` let the recalculation answer it, covered by `TC-CASE-021` through `TC-CASE-025` |
| The case journal would have grown with the recalculation rate, appending an identical "still waiting" row on every pass | adversarial reading; an observation that changes nothing is no longer appended, pinned by `TC-CASE-024` |
| `FlywayMigrationIT` keeps its own approved-migration list and two new migrations were absent from it | the full regression, which is what that list exists for |
| The first Draft-PR CodeQL pass found one unsafe pair index, three contradictory null-flow paths and seven inputs with no semantic effect | the bounds guard, null-flow simplification, meaningful withdrawal audit evidence and exact method signatures close all 11 annotations; Java, TypeScript and aggregate CodeQL pass |
| The accepted-fact cursor and targeted queue are global, but their ordered integration test shared the suite database and assumed no other class had published facts | both remote backend jobs exposed three order-dependent counts; `AvailabilityRecalculationLoopIT` now owns an isolated migrated PostgreSQL database and both full jobs pass |
| Mixed primitive/boxed selection in `AvailabilityProjectionWriter` unboxed a nullable Channel `availableUnits` value during a real portfolio refresh | the 5,000-Variant actual-path profile produced the NPE; explicit boxing preserves the governed null state |
| Successor anti-joins on accepted-fact tables had no supporting partial indexes and repeatedly scanned historical predecessors per Variant | the actual 5,000-Variant worker sustained under one Variant/second; V0035 partial successor indexes improved the same path by roughly 26x and the full SLO profile passes |
| V0035 was absent from the exact Flyway and managed-profile migration inventories | full aggregate migration guards exposed every stale terminal version/count; fresh, managed and V0010-upgrade paths now all bind V0035 |

## Targeted and sweep equivalence

`TC-AVAIL-FLOW-004` calculates the same variant twice at one as-of instant and
compares the whole returned value, not a summary of it. The two paths call one
calculator over one evidence snapshot, so the equality is structural rather
than coincidental. Both runs also produce the same policy-version digest.

## Automatic outcome verification

`TC-CASE-021` through `TC-CASE-025` drive the second stage without anybody
closing anything:

| Case | Proven |
| --- | --- |
| `TC-CASE-021` | an unrepaired cause keeps the case verifying rather than failing it |
| `TC-CASE-022` | a repaired cause starts the governed window and claims no success |
| `TC-CASE-023` | a repaired cause that comes back reopens the same case, and the new cause raises its own beside it |
| `TC-CASE-024` | an improvement that holds through the window verifies automatically, in exactly two journal entries |
| `TC-CASE-025` | no case in the whole organization reached success without a fresh observation |

`TC-OUTCOME-001` through `TC-OUTCOME-005` fix what repaired means: a shortage by
the lane falling back below activation, a defect by the defect being gone, and
neither by evidence that cannot establish safety in the first place.

## Preserved boundary and known limits

- Docker Hub's blob CDN is denied by this session's egress policy. Images were
  obtained through the configured pull-through mirror, so the digest-pinned
  image references resolve unchanged. No test source, image name or pinned
  digest was modified.
- Declared-capacity evidence is local and synthetic, but it executes the actual
  production application path rather than a mocked refresh. PostgreSQL
  enumerated all 5,000 Variants in five keyset pages in 44 ms; the targeted
  worker completed 5,000 accepted facts in 244,035 ms, wrote 5,000 cards,
  10,000 children, 5,000 Cases and 5,000 SLO rows, and produced exactly 1,000
  observations in each of CRITICAL/HIGH/WATCH/HEALTHY/UNRESOLVED. CRITICAL P95
  was 232,766 ms and worst case 244,110 ms with zero breaches. The full sweep
  completed 5,000 Variants in 337,180 ms with zero failed Variants/retries,
  repaired 50/50 dropped triggers and retained 3,262,820 ms hourly margin. Its
  relational trace linked all 5,000 targeted and 5,000 reconciliation Variants.
  The 600,000-row, 201,207,247-byte asynchronous export completed and verified
  in 35,577 ms (54 parts; submission 26 ms; manifest
  `b9319a32d3d6676883e2a0e96233b0663edc6a2e8ce96ec42e0ff504fa1d554c`),
  and exact-byte PG17 restore passed in 74,289 ms with V0035, zero post-restore
  migrations and dump SHA-256
  `dc090bfddf31abe8c4cc0629debd1614c8efe2d40e40d9b119263dd4cc9d59b5`.
  This is internal capacity evidence, not a deployed Owner cohort or
  real-provider claim.
- Fault injection now covers a dropped trigger (`TC-LOOP-006`), concurrent
  sweep refusal (`TC-LOOP-008`), an abandoned worker/run and restart
  (`TC-LOOP-010`), and later-accepted reordered stale evidence
  (`TC-LOOP-011`). It does not establish production process-supervisor or
  infrastructure failover behavior.
- `TC-BROWSER-014` runs the queue, action, automatic calculated verification,
  same-case reopen and governed exception request through Chromium against the
  real local backend and V0035 PostgreSQL schema. The issuer and all business
  data remain synthetic fixtures.
- Structured correlation identities, logs, append-only relational traces,
  durable run/request/case/audit evidence and health incidents are asserted.
  Five isolated operator-response drills are executable from the canonical
  runbook. An external distributed-trace exporter and a human production drill
  are outside this local engineering evidence and are not claimed.
- `S2-AC-100` remains reserved for independent Controller Final Closure. Local
  implementation and regression success cannot self-issue that verdict.
