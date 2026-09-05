# F015 — Frozen advertising Outcome workstream

Status: implementation and targeted source verification complete; final integrated verification pending. This file is not an independent Controller verdict or production enablement.

## Root causes and repair

The former Outcome reader built its baseline after the action, used order/retention ratios as financial amounts, allowed company totals to hide required-unit collapse, and used a single profit metric. The repaired path freezes an immutable pre-action plan and three stage snapshots, with every affected product/channel and the Owner-selected critical-unit rules. Human selection and final sealing bind the exact baseline ID. A reviewed chain cannot replace that baseline; changed or expired authority requires a new chain.

The wire `OPERATIONAL` observation is the early Completed-Sales safety guard only. It can release a reservation when exact configuration, complete company preservation and every frozen critical unit pass. It never proves primary efficiency. `RETAINED` is the 30-day Operational profit result; `SETTLED` uses actual financial facts and exact cohort attribution. Absolute contribution and contribution per advertising RUB are independent axes. Missing, stale, zero-denominator, unmapped and mixed-currency evidence remains explicit. Known failure survives another unit's UNKNOWN state.

Policy fields now separately freeze material thresholds, non-worsening bands, minimum advertising-spend denominator, comparison scale/rounding/inclusive semantics, negative-profit terminal behavior and sales tolerances. Missing fields make a new plan INCOMPLETE. No new production policy is published by this workstream.

Actual company monetary SQL preserves missing values, exact stages and point-in-time supersession. A complete fresh canonical report can prove zero events; absent coverage cannot. Settled company amounts match actual SETTLED facts to exact retained cohort order/line identities, require equal quantities and reject duplicate cohort attribution. Advertising settled profit requires explicit financial-to-ad-linked attribution, without a retained/order proxy.

API and governed manual packets share the same evidence reader, plan, evaluator, immutable observation/revision lineage, required-unit guards and narrow reservation/containment controls. Manual configuration proof remains separate from sales safety. Provider transmission is never required to construct a manual outcome.

## Targeted evidence

- `/tmp/slice3-outcome-unit.log`: 133 unit tests PASS in the earlier implementation batch (07:38:32 local).
- `/tmp/slice3-canonical-pg.log`: 144 tests PASS, 0 failures/errors/skips (07:54:35 local), including 7 real PostgreSQL canonical fact tests.
- `/tmp/slice3-frozen-outcome-pg.log` through `-r5.log`: retained diagnostic attempts. These exposed a test policy-window constraint, an unrelated new trigger ambiguity, synthetic command terminal-state metadata, two dynamically executed reservation SQL column names and a missing fixture company-listing membership. They are not passing full-suite evidence.
- `/tmp/slice3-frozen-outcome-pg-r5.log`: 154 tests, 153 PASS and 1 error, 08:30:58 local. All 9 canonical fact PostgreSQL tests and all selected domain/calculation tests passed; the remaining error was the synthetic baseline's omitted listing IDs, which prevented correction fanout in that test.
- `/tmp/slice3-frozen-outcome-pg-r6.log`: `mvn -f backend/marketops-server/pom.xml -Dtest=AdvertisingFrozenOutcomeIT test` — 5 tests PASS, 0 failures/errors/skips, 25.322 seconds, finished 2026-09-05 08:32:14 +08:00.

The five real PostgreSQL scenarios cover:

1. Actual company Completed facts plus complete coverage, exact configuration and every frozen critical unit PASS produce early safety UNCHANGED, release the reservation, and keep the profit verdict UNRESOLVED.
2. Missing coverage leaves the company/critical guard unknown and preserves the reservation.
3. Complete source coverage with zero actual company sales proves REGRESSION and creates quarantine.
4. Late corrected Completed sales append revision 2, preserve original UNCHANGED history, quarantine and reacquire the safe overlap reservation.
5. A reviewed command cannot change its frozen baseline.

The tests use a fictional protocol, synthetic identities, isolated PostgreSQL and a logical observation clock; they perform no real Provider call and retain `production_write_enabled=false`.

## Governed manual and latest integration

`/tmp/slice3-orchestration-disclosure-r4.log` ran `AdvertisingManualWorkflowIT` 5/5 PASS (2026-09-05 08:50:19 +08:00). The new positive uses actual `prepareManual`, reuses the same immutable pre-action baseline on a repeat preview, traverses Maker/Ops Lead/Owner controls, proves configuration independently, retains the reservation before the early window is due, then reads real canonical company facts and every required unit to release. It asserts zero API commands. The four other scenarios retain human authority, Raw custody/currentness, replay and revocation assertions. The entire mixed orchestration batch was not green: capacity failures are recorded in the UI workstream and are not represented as passing evidence here.

The outcome worker now exposes exact organization/object evaluation to targeted refresh. API and manual due queries apply that scope in SQL; errors propagate to the queue handler, and bounded batches explicitly report remaining work. The independent scheduled pass remains a recovery mechanism. Manual late financial attribution now triggers revisions as the API path does.

Canonical `advertising-outcome-regression.md` now describes early Completed safety, fixed 30-day retained primary profit, exact financial settlement, independent company/critical guards, separate business conclusions and immutable late regression containment. It removes the obsolete statement that an operational regression reopens nothing.

## Remaining verification

The latest policy-scope/expiry, business-outcome, accepted-age/provider-incident and scoped worker tests passed r7 (7 FrozenOutcomeIT, 15 OutcomeServiceTest, 10 CanonicalFactsIT; no failures/errors). Exact-scoped worker testing proves other organization/object identifiers evaluate no rows, the correct object releases only after actual complete safety, and same-as-of replay creates no second revision. Full-suite checks, exact final Head, remote CI and independent Controller verdict belong to the integration handoff; none is implied by these targeted results. The r7 mixed suite had two legacy Manual oracle deadline errors; r8 corrected those explicit fixture fields and passed ManualWorkflowIT 5/5 at 09:03:31 +08:00. Exact command and attempt counts are retained in `facts-priority.md`.

### Direct SQL baseline authority repair

The direct application-role baseline INSERT / caller `COMPLETE` gap was treated
as an actual in-scope defect. V0059 now revokes INSERT on baseline, stage and
critical-unit tables. The internal canonical planner is the sole Java caller of
`AdvertisingOutcomePlanAttestor`; it attests its computed full payload through the
existing independently configured `marketops_identity_issuer` connection. There
is no HTTP interface accepting caller snapshot JSON for this authority.

A 30-second single-use proof binds the full canonical JSON digest, organization,
baseline identity, application backend and transaction. The SECURITY DEFINER
freeze operation consumes that proof atomically, inserts the frozen rows and an
immutable attestation, then checks complete plans with
`ops.ad_outcome_baseline_is_canonical`. The validator compares the stored complete
payload digest, exact typed Owner policy and scope, immutable plan windows,
required units, all three stage shapes, early company/critical baseline quality
and full purpose freshness authority. API seal/creator/gate and manual lifecycle
consume this same predicate. This preserves one canonical Java financial
calculation authority while preventing application SQL from inventing its output.

Unconfigured trusted issuer returns an unavailable plan without removing the
responsibility Task. No real credentials were provisioned. Synthetic tests use an
explicit isolated issuer and migration-role trusted planner oracles only where a
test intentionally isolates a later control.

`/tmp/slice3-canonical-baseline-authority-probe.log`: the isolated PostgreSQL
application-role probe returned `canonical=true`, `attested=true` and
`appDirectInsert=false`. The new real Planner and adversarial JDBC tests await
the next coordinated Maven window; this probe alone is not their passing result.

The subsequent coordinated attempts are retained without relabeling failures:

- r9 (`/tmp/slice3-frozen-outcome-pg-r9.log`): 208 tests, 0 failures, 54 setup errors. A semicolon inside a shared-fixture line comment was split into a SQL statement. The helper now removes whole line comments before statement splitting. New baseline behavior did not execute in the affected classes.
- r10 (`/tmp/slice3-frozen-outcome-pg-r10.log`): 181 reported tests, 0 failures, 37 setup errors, 42.984 seconds, finished 09:31:34 +08:00. The new V0063 maturity trigger had an unparenthesized CASE expression inside PL/pgSQL IF; all PostgreSQL classes stopped at migration. The UI workstream owns that repair and its independent SQL compile check. These failures do not weaken or disable the baseline gates.

The Manual class now uses the real planner in all five original lifecycle cases,
and adds direct INSERT/self-attestation, changed-value, wrong-backend/transaction,
proof replay, missing-stage and changed-Owner-policy attacks. Its latest passing
result must come from the next post-checkpoint run.


## Coherent targeted checkpoint r12 — 2026-09-05 09:53:38 +08

The exclusive non-clean Maven superset passed **248 tests, 0 failures, 0 errors, 0 skips**, in 1:34 min. It includes CanonicalFacts 10, FrozenOutcome 7, ManualWorkflow 9, OutcomeService 18, HumanWorkflow 10, SealedAuthority 15, Reservation 14, OrdinaryApproval 7, Materiality 13 and Guardrail 16, plus the full selected economics/freshness/ranking/candidate/clock domain suites. Exact command and log digest are recorded in `facts-outcome-traceability.json`; the retained log is `workstreams/logs/slice3-frozen-outcome-pg-r12.log`.

This checkpoint proves the trusted Planner boundary on real isolated PostgreSQL: application direct inserts/self-attestation fail, proof payload/backend/transaction/replay boundaries fail closed, missing stage/changed Owner policy fail, actual Manual planning and early company/critical safety pass, and insufficient company history freezes an attested INCOMPLETE plan with `OUTCOME_BASELINE_INSUFFICIENT` while preserving its responsibility Task and refusing selection. New service assertions cover final confounders versus independent early safety and favorable/contradictory financial stage transitions without replacing Retained history. The financial service assertions use canonical snapshot mocks; they are not represented as full actual financial PostgreSQL orchestration.

The prior r11 212-test attempt remains preserved with its five fixture errors. The corrected Ordinary fixture now constructs its initial immutable Bundle authority graph and the cause-bound fixture matches the exact headroom version; controls were not weakened. AC168 still requires the shared Finance-review path for canonical Settled contradiction, and final exact-head full validation/CI remain pending. No VERIFIED or Controller verdict is asserted by this targeted checkpoint.

### R13–R15 integration findings and actual financial evidence

R13 executed 219 selected tests (208 passed, 11 errors), with the method-specific capacity selector excluding nested test classes; it is not evidence for the complete R12 superset. R14 stopped at test compilation because the new qualification boundary test omitted a SaleStage import. That import was corrected before R15.

R15 executed 288 tests: 286 passed, one assertion failure and one database fixture error. Actual FrozenOutcome10, Manual9, FinanceReview6, Human11, CanonicalFacts10 and QualificationBoundary3 passed. The two remaining failures were root-owned historical migration count (46 expected / 55 actual) and a browser history fixture assigning SATISFIED to an OPERATIONAL guard; neither control was weakened. Exact commands, preserved logs and SHA-256 are in facts-outcome-traceability.json.

Three new PostgreSQL journeys exercise real accepted company, retained cohort, ad-linked and financial attribution readers, shared economics and OutcomeService: favorable actual settlement upgrades earlier no-improvement without rewriting it; actual settlement contradiction preserves retained success and creates the linked Finance Shared Task; absent financial allocation keeps monetary efficiency unknown even when company-level financial sales are known. Pre-action metrics in these golden Outcome tests are explicitly privileged synthetic baseline oracles. They do not substitute for the separate complete facts-to-worker VerticalPathIT, which is being rebuilt from empty derived state and remains pending runtime evidence.
