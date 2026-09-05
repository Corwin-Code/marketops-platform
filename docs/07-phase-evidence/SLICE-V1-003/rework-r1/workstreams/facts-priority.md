# Facts, economics, purpose qualification and priority workstream

Status: source implementation and targeted PostgreSQL/unit verification complete; final integrated verification pending. This is not a full-verification or closure verdict.

## Root causes and implemented changes

- F002/F003: the canonical MetricEngine v2 `PLATFORM_FEES_PER_UNIT` already demands full FeeFamily coverage, including promotion, fulfilment, delivery, storage and other variable fees. The advertising path erroneously demanded a second permanently absent promotion component. `AdvertisingAttributedEconomics` now applies the existing Metric authority's complete costs once to each exact linked sale line, preserving real quantities, product/listing mappings, currencies, source references and definition versions. Per-variant Allowable CPA policies contribute independently. An incomplete line or mixed currency blocks the atomic object; neither SQL SUM nor affected-set order may hide it.
- F003 same-class scan: official aggregates preserve partial nulls, reject mixed currencies and overlapping report windows; live sale events retain deterministic linkage, exact definition, affected set, business window and accepted-time lineage. Supersessions use the calculation's accepted-time boundary. Historical-window late-correction reads are reused by frozen Outcome. Actual SETTLED facts are matched to exact retained cohorts; duplicate cohort attribution is unresolved, not counted twice.
- F004: the conversion stage comes from the unique exact active bundle, or an unambiguous effective shadow definition. Freshness resolves separately by purpose/evidence kind, including Semantic Profile scope and effective interval conflicts. Real source/accepted timestamps, coverage and expiry are persisted in `mart.ad_case_purpose_evidence`; case.as_of is not substituted for source age. Task and write qualification use distinct policies, full coverage/window/sample/confidence/baseline criteria and consecutive distinct policy windows. Replaying a refresh cannot manufacture another qualified period.
- F005: canonical rank is lane/subtier plus the Contract's lane-specific lexicographic factors and stable case identity. A later commercial term cannot compensate for an earlier one. Missing factors remain NULL with `UNRESOLVED:` reasons; the scalar score is severity display only. Java and SQL use the same factor order. Task SLO timestamps and actual case age are consumed rather than object age. Critical-unit signals now read the actual frozen required-unit guards; known regressions and unresolved siblings remain distinct. SLO rank reads the public staffed-clock port, including as-of pause authority, preserved first-raised age and unresolved historical profiles. Dual-axis gap is represented by independent absolute-profit and profit-per-RUB factors in lexicographic order, never a sum of incompatible units. These gaps are only read from a fresh exact current calculation/policy/affected-set frozen 30-day plan; unrelated or outdated observations remain NULL.

## Focused evidence

Command, executed from repository root:

```text
mvn -f backend/marketops-server/pom.xml -Dtest=AdvertisingAttributedEconomicsTest,AdvertisingPurposeFreshnessTest,AdPriorityLexicographicTest,AdPriorityPolicyTest,AdvertisingCaseCalculationServiceTest,AdvertisingCaseMeasureCalculationTest test
```

Result: BUILD SUCCESS; 81 tests, 0 failures, 0 errors, 0 skipped. Compilation and test compilation passed. Raw local log: `/tmp/slice3-facts-unit.log`.

New tests cover unequal linked quantities, different per-variant CPA policies, permutation invariance, partially missing money, mixed currencies, missing one policy, confirmed zero spend, estimated economics, hard lexicographic precedence, stable ties, missing-versus-zero factors, real-source ageing, purpose separation and exact minimum expiry. Existing tests that asserted the perpetual promotion-feed defect or weighted Watch ranking were rewritten around the accepted behavior; unrelated assertions remain.

## PostgreSQL and subsequent integration evidence

- `/tmp/slice3-canonical-pg.log`: 144 tests PASS, 0 failures/errors/skips, 2026-09-05 07:54:35 +08:00. Includes 7 actual PostgreSQL canonical fact scenarios.
- `/tmp/slice3-frozen-outcome-pg-r5.log`: 154 tests, 153 PASS and 1 fixture metadata error, 08:30:58 +08:00. All 9 canonical fact PostgreSQL tests and all selected domain/calculation tests passed. The failing late-correction fixture omitted its company listing IDs; the corrected Outcome class subsequently passed 5/5 in `/tmp/slice3-frozen-outcome-pg-r6.log`.
- `/tmp/ad-query-probe.log`: 19 actual production repository SQL SELECT probes passed against the isolated fictional graph before subsequent changes. Later queries must rely on the later targeted/full runs, not this historical probe.
- New rank permutation test varies input ordering 24 times and proves independent profit axes precede arbitrary later Spend; confirmed zero stays distinct from unresolved.
- New pure calculator test proves missing conversion produces a fresh one-sided Protection case plus independent Data Repair, and qualifies only the cause-bound evidence dependencies. The root workstream additionally tests the actual proposal/responsibility path.

The actual advertising refresh test exposed an invalid PostgreSQL whole-row expression in the shared policy conflict filter (`to_jsonb(schema.table)`). All resolver instances now use a valid outer row name. The same-class fix prevents a conflicted narrower policy from disappearing and silently selecting a broader policy. A PostgreSQL test covers specific selection, conflict refusal and expiry of the conflicting version. The latest resolver and its 10-scenario canonical PostgreSQL class passed in r7.

Cost freshness now uses the actual canonical Metric evaluation timestamp proving effective-dated input applicability, retaining the historical source references. An old still-effective cost transaction is not automatically stale merely because its original date is old. A dedicated fixture tests this distinction; official Spend still uses its actual source timestamp and a newly accepted stale report stays stale.

## Finding-to-evidence fragments for integration

| Finding | Canonical source surfaces | Behavioral evidence | Final status |
|---|---|---|---|
| F002 | `AdvertisingAttributedEconomics`, `AdvertisingEvidenceGatherer.economicsForSales`, canonical Metric v2 lineage | unequal quantity economics, retained no-double-loss, complete fee family and no permanent promotion stub | Targeted evidence; final regression pending |
| F003 | `AdvertisingEvidenceRepository`, `AdvertisingPolicyRepository`, conversion/CPA line authority | null/gap/overlap/currency/as-of SQL, 10:1 variant quantities, missing mapping, exact SETTLED cohort, duplicate attribution and policy conflict tests | Latest resolver and canonical PostgreSQL tests passed r7; final regression pending |
| F004 | `AdvertisingPurposeFreshness`, `AdvertisingCaseCalculationService`, `mart.ad_qualification_period`, `mart.ad_case_purpose_evidence` | source-vs-acceptance age, per-purpose expiry, separate Task/write windows, cause-bound independent danger, exact source expiry consumed by seal | Latest cost-effectiveness test passed r7; integration gates remain |
| F005 | `AdPriorityPolicy`, query canonical ORDER BY, critical-unit repository context, `AdvertisingTaskSloQuery` | commercial-term permutation, NULL-vs-zero, independent axes, actual staffed/pause/first-raised authority | Rank unit/permutation checks passed r7; final orchestration SQL parity pending |
| F015 | shared immutable planner/evidence/evaluator, V0059/V0062, manual binding | actual PostgreSQL early safety release, unknown hold, zero-regression, late revision/quarantine, immutable baseline | See `outcome.md`; Manual actual PostgreSQL positive and business-status assertions passed; final regression pending |

Complete regression, SQL/Java rank parity over the final implementation, final Head and remote CI evidence remain integration requirements. These targeted results are not `VERIFIED_FIXED`, an independent Controller verdict or production enablement. No accepted Contract or Frozen Finding Set was changed; no real Provider access was performed.

The actual orchestration equality oracle exposed a first-calculation dependency on the newly persisted case: DataRepair rank counts changed from unresolved to 0/1/0 during the second identical as-of refresh. The repository now gathers object dependency counts independently before case scoring; same-as-of new projections do not count themselves, and affected-set blast radius comes directly from complete canonical membership. The complete calculation equality assertion remains in the capacity test. Its rerun belongs to the subsequent shared integration window.

Current canonical runbooks `advertising-stale-or-incomplete-data.md`, `advertising-mapping-or-linkage-gap.md` and `advertising-outcome-regression.md` were rewritten to match the repaired source. No accepted normative document or historical evidence was edited.

## Latest coordinated source verification

`/tmp/slice3-frozen-outcome-pg-r7.log` finished 2026-09-05 09:02:26 +08:00 in 57.938 seconds: 175 tests, 0 failures, 2 errors, 0 skipped. All source-owned checks passed: CanonicalFactsIT 10/10, FrozenOutcomeIT 7/7, OutcomeServiceTest 15/15, PurposeFreshnessTest 6/6, AdPriorityLexicographicTest 7/7, plus all selected economics/calculation/domain checks. The root HumanWorkflowIT 9/9 passed, including missing-profile restoration and historical as-of behavior. Compilation and test compilation included the synthetic Manual browser helper.

The two errors were legacy Manual human-control fixture plans with no maturity fields, exposed by the new exact due scheduler. The fixture now explicitly supplies its existing stage window and offset values. `/tmp/slice3-frozen-outcome-pg-r8.log` reran `mvn -f backend/marketops-server/pom.xml -Dtest=AdvertisingManualWorkflowIT test`: 5/5 PASS, 0 failures/errors/skips, 22.105 seconds, finished 2026-09-05 09:03:31 +08:00. The r7 mixed command remains recorded as a failed attempt; the passing focused rerun does not relabel it as green.

The r7 command was:

```text
mvn -f backend/marketops-server/pom.xml -Dtest=AdvertisingAttributedEconomicsTest,AdvertisingPurposeFreshnessTest,AdPriorityLexicographicTest,AdPriorityPolicyTest,AdvertisingCaseCalculationServiceTest,AdvertisingCaseMeasureCalculationTest,AdvertisingCanonicalFactsIT,AdvertisingFrozenOutcomeIT,AdvertisingOutcomeServiceTest,DualAxisVerdictTest,SalesPreservationTest,BidCandidateSetTest,OutcomeEvaluationTest,StaffedResponseClockTest,AdvertisingManualWorkflowIT,AdvertisingHumanWorkflowIT test
```

All work remains inside the authorized rework. This workstream made no commits, pushes, production policy publications or Provider calls. Final integrated tests, browser/capacity evidence, exact Git Head and CI remain root-owned requirements.


## Coherent targeted checkpoint r12 — 2026-09-05 09:53:38 +08

The exclusive non-clean Maven superset passed **248 tests, 0 failures, 0 errors, 0 skips**, in 1:34 min. It includes CanonicalFacts 10, FrozenOutcome 7, ManualWorkflow 9, OutcomeService 18, HumanWorkflow 10, SealedAuthority 15, Reservation 14, OrdinaryApproval 7, Materiality 13 and Guardrail 16, plus the full selected economics/freshness/ranking/candidate/clock domain suites. Exact command and log digest are recorded in `facts-outcome-traceability.json`; the retained log is `workstreams/logs/slice3-frozen-outcome-pg-r12.log`.

This checkpoint proves the trusted Planner boundary on real isolated PostgreSQL: application direct inserts/self-attestation fail, proof payload/backend/transaction/replay boundaries fail closed, missing stage/changed Owner policy fail, actual Manual planning and early company/critical safety pass, and insufficient company history freezes an attested INCOMPLETE plan with `OUTCOME_BASELINE_INSUFFICIENT` while preserving its responsibility Task and refusing selection. New service assertions cover final confounders versus independent early safety and favorable/contradictory financial stage transitions without replacing Retained history. The financial service assertions use canonical snapshot mocks; they are not represented as full actual financial PostgreSQL orchestration.

The prior r11 212-test attempt remains preserved with its five fixture errors. The corrected Ordinary fixture now constructs its initial immutable Bundle authority graph and the cause-bound fixture matches the exact headroom version; controls were not weakened. AC168 still requires the shared Finance-review path for canonical Settled contradiction, and final exact-head full validation/CI remain pending. No VERIFIED or Controller verdict is asserted by this targeted checkpoint.

### R15 sample boundary and historical replay follow-up

Qualification now uses the independently queried canonical Completed event count even when the active conversion definition prices Retained sales. It no longer treats the selected Retained aggregate as if it were the Completed sample. Three boundary tests passed in R15: independent Completed/Retained samples; every published traffic/sample/spend/window boundary; and refusal for missing comparable history or a Provider incident.

The Vertical rewrite exposed a same-class historical read defect: economicsForSales(readAt) read the latest Metric and then rejected future computedAt, erasing the older valid value. It now calls canonical MetricQuery.currentValuesAt directly. A new PostgreSQL counterexample stores both an earlier and later Metric version and requires the corresponding earlier/later economics, pending the next integrated runtime run.

R15 whole result is 288 tests / 286 passed / 1 failure / 1 error / 0 skipped, not final verified closure. Commands and retained logs are recorded in the traceability shard.

### R18–R29: actual canonical vertical path

The former `AdvertisingVerticalPathIT` shortcut is replaced by a journey that begins with empty Case, Metric, candidate, recommendation and Outcome-baseline tables. Accepted ledger sales, official ad reports, mapping, exact source provenance, typed cost/fee authority and source coverage feed the real `AnalyticsCalculationService` and advertising refresh. The positive path asserts four fresh canonical economic Metrics, contribution profit `-1000`, MaxCPC `25`, one generated bid recommendation and a real responsibility Task. The negative path leaves economic inputs unresolved and proves visible responsibility with no executable candidate or Provider call.

The same positive graph continues through actual Maker selection, independent Operations endorsement, Owner preview/final approval, trusted baseline sealing and Command creation. The actual worker uses an in-process fixture port and real Raw custody/readback; a second run does not repeat APPLY. All Provider effects are synthetic and `production_write_enabled=false` remains asserted.

R18–R27 retain their actual failures in the machine-readable run ledger. These exposed fixture timestamp/coverage/protocol/FK issues, a stale cost version and an exact synthetic Gate target mismatch; the strict existing production checks remained in place. R28 passed the two complete journeys. R29 then passed the complete narrow superset: **34 tests, 0 failures, 0 errors, 0 skipped**, including Gate8, Privilege10, Transmission7 and NonGoals7. R29 finished `2026-09-05T03:32:37Z`, Maven duration `45.535 s`; log SHA-256 `234b28703331f19c2a0b1983639a7e3332ee43edfa62cdf95419bc2c0dd2af39`.

R29 is a working-tree targeted run based on W1 `60638b1fc1a227b50f4b3ede1ba0bb983407bfdc`. Its `marketops.build.gitCommit` property identifies that base and does not turn the subsequent fixture edits into a clean-source result. Exact final clean verification and CI remain required.

[facts-outcome-fault-seeding.json](facts-outcome-fault-seeding.json) records 47 intentional adverse boundaries with precise test methods, altered inputs, asserted responses and actual reached layers. Database privilege/shape refusals are distinguished from calculator, Planner and Outcome execution.

### Atomic Case identity under concurrent refresh — R34

The Case repository now returns the persisted UUID from `INSERT ... ON CONFLICT ... RETURNING id`; the projection writer uses that UUID for every dependent row. This closes the losing proposed UUID race without introducing additional object locks. The actual PostgreSQL test removes all qualification policies and uses four distinct microsecond evaluation instants, preventing incidental qualification-row serialization. The exact report window legitimately produces two causes; each retains exactly one Case and one responsibility Task. The dedicated writer test also injects an upsert conflict identity and verifies every dependent reference.

Parent-run R34 passed all 97 tests, including Vertical4 and Writer17; the complete command, log SHA and source identity limitation are in `final-targeted-r34.json`. The previous R32 wrong total-Case expectation and Mockito restubbing error remain in the preserved failed-run evidence. Final clean committed-source verification and exact CI are pending.

### Full W3 and the historical unknown-set boundary

The exact clean W3 run executed 2,262 raw testcase nodes (the Maven summaries agree), with 11 failures and 6 errors; the original 2,261 suite-declared sum is retained alongside the one nested-name discrepancy. Advertising capacity3, Canonical11, Frozen10 and the actual Vertical path passed. The complete run remains failed.

The old advertising flow fixture mixed a fixed historical calculation instant with wall-clock resolution and accepted timestamps. Correct historical reads excluded those future inputs. This also exposed a real sink defect: an explicit unknown affected-set Data Repair could not persist its Case because of a non-null column. The current follow-up permits null only for `DATA_REPAIR/AFFECTED_SET_UNRESOLVED` with `AFFECTED_SET_NEVER_RESOLVED`; diagnostic Task/workflow scope remains advertising-specific, while every controlled action still requires a complete set. A new actual PG regression covers future-set exclusion, responsibility, scope refusal and no candidate/command. It is pending the next targeted run.

R38 passed the full24-method advertising flow and its new historical unknown-set/Task/scope/schema boundary. R37 separately passed the other seven classes/74 assertions but failed four queue assertions. The preserved source delta confirms that only EfficiencyFlowIT changed between those runs; this is not a single 98-test passing run. All fresh queue oracles now drain prior trigger work and use newer microsecond acceptance times; CREATED remains strict. Targeted/sweep parity explicitly asserts actual CREATED and positive targeted execution. Reverse arrival order still preserves the exact earlier accepted instant, while deliberately old replay suppression and expired-lease recovery remain unchanged. The new52-row systematic fault matrix labels schema refusal separately from the actual calculation and permission paths. Final clean verification and exact CI remain pending.
