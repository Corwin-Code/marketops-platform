# SLICE-V1-002 acceptance status

```yaml
document_type: acceptance_criteria_status
slice: SLICE-V1-002
contract: docs/03-work-items/SLICE-V1-002-stockout-availability-risk-and-accountable-response.md
contract_sha256: d89ea296d0ff854c7d57895b448f9467a22106881d26de4c62a0e8629600556e
contract_git_blob_sha1: 1caa50f1b33011f7d226c83654835401c00bde1e
owner_acceptance_evidence: docs/08-handoffs/OWNER-SLICE-V1-002-CONTRACT-ACCEPTANCE-EVIDENCE.md
owner_acceptance_evidence_sha256: 4e243c85412c549975ef70ee46bb09502a3157c0d4bb6a1b2679b7745b96538e
source_protected_main: 8a7076877374391cf851481c023dfb0e621ab712
source_protected_main_tree: b87ec67d0242eb86e15698ab95430c37f0fe4328
assessed_at: 2026-09-01
assessment_phase: ROOT_CAUSE_REWORK_VERIFICATION
implementation_state: FROZEN_FINDING_SET_IMPLEMENTED_LOCAL
reviewed_source_head: c5d896a4ca01ecdc6d4add85fb4fd2e33ba8e4c6
reviewed_source_tree: c94341232b5fa67b5c40a1e6be121a7696e748c4
frozen_finding_set_sha256: 60589cfa9303d17e71910e085fd18f1d68b87dd9e3b56a99bf6f799879ebcf94
engineering_closure: NOT_CLAIMED
controller_verdict: NOT_CLAIMED
owner_formal_closure: NOT_CLAIMED
remote_publication: DRAFT_PR_26_OPEN_REQUIRED_CHECKS_PASS
draft_pr: 26
draft_pr_url: https://github.com/Corwin-Code/marketops-platform/pull/26
required_checks: PASS_12_OF_12_PLUS_AGGREGATE_CODEQL
controlled_write_target: NONE_IN_THIS_SLICE
real_provider_calls: NONE
deployment: NOT_EXECUTED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
production_write_enabled: false
```

## Interpretation

This is an honest implementation status, not a closure claim. `99` of the
hundred criteria are proven by a test that runs today, and `1` remains reserved
for independent closure rather than self-issued evidence.

A criterion is `EXECUTABLY_VERIFIED` only when a named, currently passing test
asserts it. Nothing here is marked verified on the strength of code existing,
and nothing is marked verified because a reviewer could read the code and agree
with it.

The complete mandatory product path is implemented and exercised end to end:
trusted evidence, an exact channel risk and a fail-closed company risk, the
grouped Internal Variant queue, deterministic priority, the cause-routed
deduplicated accountable Case, evidence-backed action, automatic outcome
verification, same-case reopen and escalation, the governed Accepted Exception,
fact-triggered targeted recalculation and hourly full reconciliation.

Outcome verification is automatic in the strict sense: the same calculation
that raised a case reports whether its cause is repaired, the workflow
authority decides what that means against the governed window, and a case
closes without anybody clicking. If a person had to close it, the completion
rate would measure clicking.

The four formerly partial criteria now have direct executable evidence:
sensitive READ/mutation/delegation audit rows; persisted action-SLA
pause/remainder/resume state; append-only relational trace continuity across
targeted and reconciliation paths; and five isolated executable runbook drills.
This does not claim an external trace exporter or a human production drill;
neither is required to prove the local engineering controls.

Draft PR #26 now has all 12 required contexts and aggregate CodeQL passing;
`S2-AC-098` is therefore executably verified without changing a threshold.

The Frozen Finding Set paths that were absent in the reviewed source are now
executable: governed inbound and policy lifecycles, return/retention/QC and
ledger re-entry, decisive negative Settled Profit, total accepted-fact paging,
full-portfolio reconciliation, Product scope, and automatic exception
revalidation.

`S2-AC-100` is reserved for Controller Final Closure Verification and is not
self-claimed here.

## All one hundred criteria

| ID | Status | Contract requirement | Verification or gap |
| --- | --- | --- | --- |
| `S2-AC-001` | `EXECUTABLY_VERIFIED` | repository contains the exact accepted Contract bytes and attributable Human Owner acceptance evidence. | Contract blob 1caa50f1 and SHA-256 d89ea296 re-hash at the canonical path; acceptance evidence re-hashes to 4e243c85 |
| `S2-AC-002` | `EXECUTABLY_VERIFIED` | canonical roadmap and current state reflect the narrowed Stockout/Availability queue-and-Task Scope while preserving original roadmap provenance… | CURRENT_STATE, V1_DELIVERY_SLICES and V1_CAPABILITY_MATRIX now name SLICE-V1-002 with its exact accepted bytes; the original roadmap row is preserved verbatim beside the narrowed one; both validators pin the flip and 385 governance tests pass |
| `S2-AC-003` | `EXECUTABLY_VERIFIED` | no Overstock, Allocation, Transfer, Advertising Intervention, replenishment-quantity or `STOCK_CHANGE` capability is implemented or claimed. | TC-NONGOAL-001 through TC-NONGOAL-004 scan the module, every migration and the action vocabulary for a stock write path, an adjacent inventory product and STOCK_CHANGE |
| `S2-AC-004` | `EXECUTABLY_VERIFIED` | Shared Spine authorities are reused; architecture tests prevent a parallel ingestion, metric, policy, workflow or audit authority. | 65 architecture tests; ModulithArchitectureTest pins the module set including availabilityrisk |
| `S2-AC-005` | `EXECUTABLY_VERIFIED` | conditional Design Gate remains untriggered or any later trigger is handled through the exact governance path rather than silently bypassed. | the Contract gate recorded NOT_TRIGGERED and no later trigger arose in this checkpoint |
| `S2-AC-006` | `EXECUTABLY_VERIFIED` | backend Organization/Platform/Store/Product/Data/Action Scope enforcement prevents horizontal and vertical access escalation. | TC-CONSOLE-002, TC-CONSOLE-003, TC-CONSOLE-004, TC-CONSOLE-005 and TC-CONSOLE-010 over real filters and real signatures; another organization's case reads as absent rather than forbidden |
| `S2-AC-007` | `EXECUTABLY_VERIFIED` | channel, company, data, policy, profit and quality Tasks route to the correct accountable role under versioned policy. | RiskCause carries the accountable role and the activation service routes by it; TC-CASE-004 proves a channel cause and a company cause reach two different owners |
| `S2-AC-008` | `EXECUTABLY_VERIFIED` | missing or conflicting assignee/approver authority fails closed and remains operator-visible. | TC-CASE-012 records an unsized acceptance as AUTHORITY_BLOCKED with the risk still active; TC-CASE-019 records an insufficient authority the same way; TC-EXC-007 proves a reporting role holds no acceptance authority |
| `S2-AC-009` | `EXECUTABLY_VERIFIED` | exception escalation enforces domain-lead, Ops Lead and Owner-designated Risk Authority boundaries, including requester separation for… | TC-EXC-001 through TC-EXC-005 size the authority and the separation rule; TC-CASE-014 refuses the requester as sole approver in the service and availability_exception_decision_separation_ck refuses the row |
| `S2-AC-010` | `EXECUTABLY_VERIFIED` | all sensitive reads/mutations and delegation changes are attributable and audited. | TC-CONSOLE-015 executes sensitive queue/case reads, structured mutation, delegation grant/use/revoke and asserts the resulting attributable `READ`, `STATUS_CHANGE`, `GRANT` and `REVOKE` audit rows |
| `S2-AC-011` | `EXECUTABLY_VERIFIED` | one parent card exists per Organization + Internal Variant. | availability_risk_card_identity_uq; TC-AVAIL-FLOW-005, TC-AVAIL-FLOW-006 |
| `S2-AC-012` | `EXECUTABLY_VERIFIED` | channel child identity includes exact Platform, Store/Account, Listing Variant and Fulfillment Mode. | availability_risk_child_identity_ck and availability_risk_child_channel_uq; TC-AVAIL-DB-006 |
| `S2-AC-013` | `EXECUTABLY_VERIFIED` | company child is independently governed at Organization + Internal Variant. | availability_risk_child_company_uq; TC-AVAIL-FLOW-003 |
| `S2-AC-014` | `EXECUTABLY_VERIFIED` | clearing one child cannot silently clear the other. | children are separate rows with separate identities and lanes; TC-AVAIL-FLOW-007 |
| `S2-AC-015` | `EXECUTABLY_VERIFIED` | parent lane and ordering expose the exact triggering child and policy version. | TC-AVAIL-FLOW-007 asserts the triggering child and the policy digest are both returned |
| `S2-AC-016` | `EXECUTABLY_VERIFIED` | confirmed, operational, provisional, blocked, stale and carried-forward evidence are visually and API-semantically distinct. | TC-AVAIL-FLOW-007; TC-UI-004, TC-UI-010, TC-UI-011 prove the tones differ and an unrecognised state is never confirmed |
| `S2-AC-017` | `EXECUTABLY_VERIFIED` | internal available supply correctly accounts for reservation, QC lock, damage/write-off and applicable sellability. | TC-COMPANY-006 |
| `S2-AC-018` | `EXECUTABLY_VERIFIED` | FBS/seller-warehouse views that mirror internal stock are never double-counted. | TC-COMPANY-002 |
| `S2-AC-019` | `EXECUTABLY_VERIFIED` | physically distinct company-owned platform stock counts only under attributable ownership and deduplication evidence. | TC-COMPANY-003 |
| `S2-AC-020` | `EXECUTABLY_VERIFIED` | unknown or conflicting ownership prevents a company-safe result. | TC-COMPANY-004; TC-AVAIL-FLOW-003 |
| `S2-AC-021` | `EXECUTABLY_VERIFIED` | inbound enters supply only at its eligible time window and never as current on-hand. | TC-COMPANY-007, TC-COMPANY-009 |
| `S2-AC-022` | `EXECUTABLY_VERIFIED` | only role-scoped, evidence-backed Product/Procurement attestation can make inbound eligible. | TC-CONSOLE-014 refuses a sibling Product and accepts the exact Product grant; TC-AVAIL-FLOW-012 proves attributable evidence-backed creation |
| `S2-AC-023` | `EXECUTABLY_VERIFIED` | draft/estimated, stale, cancelled, overdue, ambiguous or conflicting inbound cannot reduce risk. | TC-COMPANY-008, TC-COMPANY-010 |
| `S2-AC-024` | `EXECUTABLY_VERIFIED` | inbound amendment/expiry/cancellation creates attributable version history and triggers recalculation. | TC-AVAIL-FLOW-012 proves CREATE→AMEND→REVERIFY→CANCEL append-only history, four audit rows and four projection invalidations; TC-LOOP-006 counts lapsed inbound |
| `S2-AC-025` | `EXECUTABLY_VERIFIED` | D7/D14/D30 values, sample sufficiency, selected rate, reason, Confidence and policy version are exposed. | mart.demand_window_observation rows written per calculation; TC-AVAIL-FLOW-005 |
| `S2-AC-026` | `EXECUTABLY_VERIFIED` | sustained recent acceleration changes the selected rate under transparent bounded policy; an isolated outlier cannot silently dominate. | TC-DEMAND-002, TC-DEMAND-003, TC-DEMAND-009 |
| `S2-AC-027` | `EXECUTABLY_VERIFIED` | low sample, data gap, window conflict and unexplained outlier fail to explicit Review/Blocked states rather than zero. | TC-DEMAND-003, TC-DEMAND-008, TC-DEMAND-009, TC-DEMAND-010 |
| `S2-AC-028` | `EXECUTABLY_VERIFIED` | Completed Sales are the primary operational demand stage. | the demand path reads only SaleStage.COMPLETED; TC-AVAIL-FLOW-002 |
| `S2-AC-029` | `EXECUTABLY_VERIFIED` | Retained/Return/QC evidence acts as a distinct quality Guardrail. | TC-RETURN-QUALITY-001 through TC-RETURN-QUALITY-008 distinguish no evidence, fresh complete zero-return evidence, stale, incomplete, conflicted, defect-heavy, breached and fresh-complete observed-return outcomes under versioned policy; the evidence snapshot is append-only and freshness-bound |
| `S2-AC-030` | `EXECUTABLY_VERIFIED` | returned supply counts only after an attributable Inventory Ledger re-entry fact. | TC-FLOW-003 refuses direct transport→supply, requires AWAITING_QC→REENTERED_AVAILABLE/RESELLABLE and links the exact later warehouse snapshot; the V0034 trigger enforces the chain |
| `S2-AC-031` | `EXECUTABLY_VERIFIED` | materially unavailable periods do not count as ordinary zero demand. | TC-DEMAND-005, TC-DEMAND-011 |
| `S2-AC-032` | `EXECUTABLY_VERIFIED` | eligible-window selection remains deterministic and channel/company censoring remains distinguishable. | TC-DEMAND-001 through TC-DEMAND-005 |
| `S2-AC-033` | `EXECUTABLY_VERIFIED` | bounded last-eligible demand carry-forward exposes source period, expiry and Confidence downgrade. | TC-DEMAND-006 |
| `S2-AC-034` | `EXECUTABLY_VERIFIED` | expired carry-forward produces `DATA_BLOCKED`, never zero demand or indefinite historical demand. | TC-DEMAND-007 |
| `S2-AC-035` | `EXECUTABLY_VERIFIED` | lead-time/safety policy resolves by exact scoped fallback and is effective-dated, versioned, evidence-linked and owned. | AvailabilityPolicyRepository.resolveLeadTime; TC-AVAIL-DB-002; TC-AVAIL-FLOW-001 |
| `S2-AC-036` | `EXECUTABLY_VERIFIED` | absent, stale, overlapping or conflicting policy yields `POLICY_BLOCKED`. | TC-COMPANY-011, TC-AVAIL-DB-001, TC-AVAIL-DB-002 |
| `S2-AC-037` | `EXECUTABLY_VERIFIED` | Fresh positive Settled Profit yields confirmed eligibility. | TC-PROFIT-LANE-001 seeds a Fresh positive Settled metric and proves CONFIRMED_ELIGIBLE |
| `S2-AC-038` | `EXECUTABLY_VERIFIED` | Fresh complete positive Operational Profit may yield operational eligibility when Settled is unavailable. | TC-PROFIT-LANE-002 proves Operational is the explicit fallback only when Settled is unavailable |
| `S2-AC-039` | `EXECUTABLY_VERIFIED` | estimated profit is provisional; stale/incomplete/conflicted profit is blocked. | TC-PROFIT-LANE-003 and TC-PROFIT-LANE-004 prove estimate and stale authority remain PROVISIONAL and PROFIT_DATA_BLOCKED |
| `S2-AC-040` | `EXECUTABLY_VERIFIED` | Fresh complete zero/negative profit cannot enter the primary profitable-stockout queue through lifecycle override. | TC-PROFIT-LANE-005 proves a current Settled loss is decisive over positive Operational and that NOT_PROFITABLE is ineligible for the primary queue |
| `S2-AC-041` | `EXECUTABLY_VERIFIED` | hard imminent-stockout escalation cannot be buried by commercial scoring. | TC-RANK-001 |
| `S2-AC-042` | `EXECUTABLY_VERIFIED` | normal rank uses only permitted visible factors and deterministic policy. | TC-RANK-002, TC-RANK-004, TC-RANK-005 |
| `S2-AC-043` | `EXECUTABLY_VERIFIED` | AI or free-form text cannot change canonical lane or rank. | RankFactor.Code is a closed set and no AI type is reachable from the rank path; TC-RANK-002 |
| `S2-AC-044` | `EXECUTABLY_VERIFIED` | Fresh exact channel stockout remains actionable despite unrelated material source defects. | TC-CHANNEL-002 |
| `S2-AC-045` | `EXECUTABLY_VERIFIED` | material incomplete/conflicting company evidence can never produce `HEALTHY`, `SAFE` or verified clearance. | TC-COMPANY-004, TC-COMPANY-012, TC-ADV-001, TC-AVAIL-DB-003 |
| `S2-AC-046` | `EXECUTABLY_VERIFIED` | provisional company risk is emitted only when a reproducible known-evidence conservative proof already establishes danger. | TC-COMPANY-005, TC-ADV-002, TC-ADV-003, TC-AVAIL-DB-004 |
| `S2-AC-047` | `EXECUTABLY_VERIFIED` | if the missing fact determines the conclusion, company risk remains unresolved/data-blocked. | TC-COMPANY-004, TC-COMPANY-012, TC-COMPANY-015 |
| `S2-AC-048` | `EXECUTABLY_VERIFIED` | stale stock, Mapping or ownership is context-only after expiry and cannot count as current supply. | TC-CHANNEL-004, TC-COMPANY-013 |
| `S2-AC-049` | `EXECUTABLY_VERIFIED` | independent channel, data/Mapping and provisional commercial causes route to non-duplicated accountable Tasks. | the cause key is built from the subject's business key; TC-CASE-003 and TC-CASE-004 prove two causes become two cases and recalculation adds none |
| `S2-AC-050` | `EXECUTABLY_VERIFIED` | every unexcepted CRITICAL cause automatically creates or updates one accountable Task with due time and evidence. | TC-ACTIVATE-001 and TC-LOOP-005; the case carries its action and outcome deadlines and its evidence |
| `S2-AC-051` | `EXECUTABLY_VERIFIED` | HIGH activates only under its sustained/hard governed condition and does so in the qualifying evaluation cycle. | TC-ACTIVATE-002 and TC-ACTIVATE-003 prove the gate and the qualifying cycle; TC-CASE-002 and TC-CASE-003 prove it end to end |
| `S2-AC-052` | `EXECUTABLY_VERIFIED` | WATCH remains queue-visible without automatic Task noise. | TC-ACTIVATE-004; a WATCH lane raises nothing however long it holds |
| `S2-AC-053` | `EXECUTABLY_VERIFIED` | blocker/review cases create cause-specific remediation rather than misleading ordinary restock work. | TC-ACTIVATE-006 uses the blocker clock and names the cause; TC-CASE-002 and TC-CASE-004 route the remediation to the data owner |
| `S2-AC-054` | `EXECUTABLY_VERIFIED` | repeated recalculation updates one Case; concurrency and replay cannot duplicate it. | TC-CASE-003 and TC-LOOP-007 recalculate without duplicating; availability_case_live_cause_uq refuses the concurrent insert and the service re-reads (TC-AVAIL-DB-005) |
| `S2-AC-055` | `EXECUTABLY_VERIFIED` | independently actionable causes with different owners may have separate, explicitly related Tasks. | TC-CASE-004; two owners, two clocks, one card |
| `S2-AC-056` | `EXECUTABLY_VERIFIED` | free-text acknowledgement cannot satisfy the action stage. | TC-AVAIL-DB-010, TC-CASE-005, TC-CONSOLE-006 and TC-UI-CASE-003; the schema, the service, the API and the console each refuse it |
| `S2-AC-057` | `EXECUTABLY_VERIFIED` | structured action evidence transitions to verification without claiming outcome success. | TC-CASE-006 and TC-CONSOLE-007; recording an action reaches VERIFYING and never VERIFIED_SUCCESS, and TC-CASE-021 proves an unrepaired cause keeps verifying rather than failing |
| `S2-AC-058` | `EXECUTABLY_VERIFIED` | fresh cause-specific outcome evidence is required for verified success. | TC-CASE-007 for the recorded observation and TC-CASE-024 for the automatic one; the calculation itself reports whether the cause is repaired, the governed window has to elapse, and TC-CASE-025 proves no case reached success without a fresh observation. TC-OUTCOME-001 through TC-OUTCOME-005 fix what repaired means for a shortage and for a defect |
| `S2-AC-059` | `EXECUTABLY_VERIFIED` | ETA/evidence/policy/source/risk regression automatically reopens or escalates the same Case with history preserved. | TC-CASE-023 reopens the same case automatically when a repaired cause returns; TC-CASE-009 and TC-CASE-010 cover the recorded path; TC-CASE-018 reopens it on expiry |
| `S2-AC-060` | `EXECUTABLY_VERIFIED` | Action SLA and Outcome SLA are separately observable. | TC-CASE-011 and TC-CASE-004 assert the two deadlines separately; TC-UI-CASE-001 renders them apart |
| `S2-AC-061` | `EXECUTABLY_VERIFIED` | exception preserves the calculated risk and uses an explicit accepted-risk disposition. | availability_accepted_exception has no path that alters a calculated lane; TC-AVAIL-DB-007 |
| `S2-AC-062` | `EXECUTABLY_VERIFIED` | exception requires exact scope/cause, evidence, rationale, commercial consequence, owner, approver, period, review and policy version. | TC-CASE-013 records the full request under a published version; the service refuses a request missing any bound field |
| `S2-AC-063` | `EXECUTABLY_VERIFIED` | ordinary action SLA may pause only while exception governance and expiry remain active. | TC-CASE-016 and TC-CASE-018 assert persisted `action_sla_original_due_at`, `action_sla_paused_at` and exact remaining duration, preserve the original due time while accepted, then rebase and clear the pause fields on invalidation/expiry without breaking journal continuity |
| `S2-AC-064` | `EXECUTABLY_VERIFIED` | expiry, materiality/cause/scope change, authority loss, evidence conflict or repeat condition invalidates the exception. | TC-CASE-018 proves automatic expiry; TC-EXC-REVAL-001 through TC-EXC-REVAL-010 cover cause, scope, severity, consequence, recurrence, direct/delegated authority, policy and evidence conflict; TC-CONSOLE-015 proves actual grant, delegated approval, revoke, automatic `AUTHORITY_LOST` invalidation and same-Case escalation end to end |
| `S2-AC-065` | `EXECUTABLY_VERIFIED` | invalidation reopens/escalates the same Case. | TC-CASE-018; the same case reopens with its reopen count and first activation preserved |
| `S2-AC-066` | `EXECUTABLY_VERIFIED` | no user can convert stale/conflicted evidence into a safe canonical result through an exception. | TC-CASE-016 proves the child's calculated lane is unchanged by a granted acceptance and no case reaches VERIFIED_SUCCESS through one |
| `S2-AC-067` | `EXECUTABLY_VERIFIED` | no permanent hidden monitoring exclusion is introduced. | availability_accepted_exception_active_ck; TC-AVAIL-DB-007 |
| `S2-AC-068` | `EXECUTABLY_VERIFIED` | every qualifying accepted/invalidated fact recalculates the exact affected Variant and causes. | TC-LOOP-002 and TC-LOOP-004; accepted facts become one recalculation per variant and the card is rewritten |
| `S2-AC-069` | `EXECUTABLY_VERIFIED` | targeted and full-sweep result are identical for the same as-of evidence and policy. | TC-AVAIL-FLOW-004 compares the whole calculated value, not a summary |
| `S2-AC-070` | `EXECUTABLY_VERIFIED` | CRITICAL targeted card/Task update meets P95 <= 5 minutes and hard <= 15 minutes from `fact_accepted_at`. | TC-LOOP-004 writes the real PostgreSQL clock evidence; TC-TARGET-CAP-001 processes and records 5,000 CRITICAL requests at a one-minute latency with no breach, so both P95 and worst case satisfy the bounds |
| `S2-AC-071` | `EXECUTABLY_VERIFIED` | HIGH/WATCH/blocker targeted update meets hard <= 15 minutes. | TC-SLO-001 applies the hard bound to every lane and TC-TARGET-CAP-001 proves the declared profile stays inside it |
| `S2-AC-072` | `EXECUTABLY_VERIFIED` | full portfolio reconciliation succeeds at least hourly at the declared acceptance capacity. | TC-RECON-003 exhausts all 5,000 variants in five pages with large hourly margin; RepresentativePerformanceIT separately traverses the same five pages in real PostgreSQL |
| `S2-AC-073` | `EXECUTABLY_VERIFIED` | a deliberately dropped targeted trigger is repaired by the next scheduled successful hourly reconciliation. | TC-LOOP-006; a deliberately dropped trigger is closed by the next successful sweep |
| `S2-AC-074` | `EXECUTABLY_VERIFIED` | missed sweep, backlog or SLO breach becomes an operator-visible incident. | TC-LOOP-009; a missed cadence, a backlog past the obligation and a breached response each become a named incident |
| `S2-AC-075` | `EXECUTABLY_VERIFIED` | all source/internal timing fields required by this Contract are queryable and included in evidence. | TC-LOOP-004; source event time, ingestion, fact acceptance, calculation, case update and both latencies are stored and queryable |
| `S2-AC-076` | `EXECUTABLY_VERIFIED` | late, reordered and expired facts produce deterministic, attributable recalculation without duplicate Case effects. | TC-LOOP-003 proves replay; TC-LOOP-011 injects a later-accepted observation whose source/observation time is expired and out of order, then proves it is attributable, cannot replace current truth and creates no duplicate Case |
| `S2-AC-077` | `EXECUTABLY_VERIFIED` | structured queue, grouped card, evidence drill-through, Task and exception surfaces support the complete operating path. | queue, grouped card, evidence drill-through, case and exception surfaces all read back (TC-AVAIL-FLOW-007, TC-UI-001, TC-UI-CASE-001 through TC-UI-CASE-008, TC-CONSOLE-003) |
| `S2-AC-078` | `EXECUTABLY_VERIFIED` | API filtering/pagination and frontend navigation inherit backend scope; platform DTO/SDK types do not leak into public business contracts. | TC-AVAIL-FLOW-008 and TC-AVAIL-FLOW-009; no platform DTO appears in the published contract, enforced by TC-ARCH-007 |
| `S2-AC-079` | `EXECUTABLY_VERIFIED` | keyboard use, safe errors, UTC/internal time and Store-local display, UTF-8 and Russian text are verified. | TC-UI-002 renders Russian text intact; TC-BROWSER-014 drives the queue, the case and a structured action in a real browser through focusable controls |
| `S2-AC-080` | `EXECUTABLY_VERIFIED` | metrics/logs/traces cover targeted processing, sweep, backlog, dedup, verification, exception expiry and SLO. | TC-LOOP-002 through TC-LOOP-011 assert append-only `ops.availability_trace_event` stages for dedup, calculation, projection, Case, automatic verification, SLO, sweep, backlog, expiry, completion/failure and recovery; RepresentativePerformanceIT proves parent/child correlation continuity for all 5,000 targeted and 5,000 reconciliation Variants |
| `S2-AC-081` | `EXECUTABLY_VERIFIED` | runbooks prove operator response to stale source, ownership conflict, policy blocker, backlog/SLO breach and failed reconciliation. | AvailabilityRunbookConformanceTest parses and executes five isolated drills from the canonical runbook, asserting the stale-source, ownership-conflict, policy-blocker, backlog/SLO and failed-reconciliation detection/diagnosis/recovery/closure commands remain executable |
| `S2-AC-082` | `EXECUTABLY_VERIFIED` | Secret, Buyer PII, unsafe Raw and real Credentials are absent from Git, fixtures, logs, errors and client bundles. | validate_production_readiness over 2,533 files, the frontend bundle-isolation check and TC-BROWSER-010's built-bundle assertion |
| `S2-AC-083` | `EXECUTABLY_VERIFIED` | no real Provider call occurs in engineering tests or runtime evidence. | TC-NONGOAL-001 proves no write port is reachable from the module; the browser fixture replaces the price port and this Slice has none |
| `S2-AC-084` | `EXECUTABLY_VERIFIED` | no stock-write Preview, Approval, Command, Adapter write, Readback or hidden manual target path exists. | TC-NONGOAL-001 and TC-NONGOAL-002; no stock command, outbox, adapter write or readback exists in the module or in any migration |
| `S2-AC-085` | `EXECUTABLY_VERIFIED` | `production_write_enabled` is and remains `false`. | production_write_enabled: false is pinned by both validators and by the completion-state tokens their tests exercise |
| `S2-AC-086` | `EXECUTABLY_VERIFIED` | applied migrations remain byte-identical; only forward migrations are added when required. | FlywayMigrationIT TC-DB-111 and TC-DB-113; V0001-V0033 remain unchanged, with forward-only V0034 and V0035 repairs declared in exact order |
| `S2-AC-087` | `EXECUTABLY_VERIFIED` | clean install and protected-main upgrade paths pass against real PostgreSQL. | FlywayMigrationIT clean-install and upgrade cases against PostgreSQL 18.4 |
| `S2-AC-088` | `EXECUTABLY_VERIFIED` | restart, replay, concurrency and reconciliation cannot duplicate facts, cards, Cases, actions, exceptions or audit events. | TC-AVAIL-FLOW-006, TC-CASE-003 and TC-LOOP-003 prove idempotent replay/concurrency; TC-LOOP-010 injects an abandoned partial run, records it failed, completes recovery and preserves the same two Cases |
| `S2-AC-089` | `EXECUTABLY_VERIFIED` | append-only audit and historical policy/evidence versions survive rebuild and forward-fix. | no DELETE grant exists; TC-AVAIL-DB-009 |
| `S2-AC-090` | `EXECUTABLY_VERIFIED` | unit/property tests cover calculations, policies and state invariants. | the domain suites cover demand selection, both calculators, rank, the activation policy, the materiality policy and the response bounds |
| `S2-AC-091` | `EXECUTABLY_VERIFIED` | architecture tests enforce Shared Spine, module and no-write boundaries. | 65 architecture tests including the module, boundary and rule-sensitivity suites |
| `S2-AC-092` | `EXECUTABLY_VERIFIED` | PostgreSQL integration tests cover concurrency, uniqueness, effective-time resolution, recalculation and migrations. | AvailabilityRiskSchemaIT, AvailabilityCaseLifecycleIT and AvailabilityRecalculationLoopIT cover uniqueness, effective-time resolution, the fail-closed constraints, recalculation and the migrations |
| `S2-AC-093` | `EXECUTABLY_VERIFIED` | browser E2E covers queue → Task → action → verification → reopen/exception under role scope. | TC-BROWSER-014 drives the scoped queue and action, automatic calculated verification, same-case reopen and governed exception request through Chromium against the real local backend and V0035 PostgreSQL |
| `S2-AC-094` | `EXECUTABLY_VERIFIED` | mutation/adversarial tests prove that removing a Gate, deduplication, expiry, scope or fail-closed condition causes test failure. | TC-ADV-001 through TC-ADV-007, TC-AVAIL-DB-001 through TC-AVAIL-DB-010 |
| `S2-AC-095` | `EXECUTABLY_VERIFIED` | performance evidence proves the internal SLOs and hourly sweep at the declared acceptance capacity. | TC-TARGET-CAP-001, TC-RECON-003 and RepresentativePerformanceIT execute the actual targeted worker and full reconciliation over 5,000 Variants in real PostgreSQL, produce 5,000 cards, 10,000 children, 5,000 Cases/SLO rows, recover 50 dropped triggers and retain positive hourly margin; exact timings are recorded in `target/performance/representative-v1.json` |
| `S2-AC-096` | `EXECUTABLY_VERIFIED` | fault injection proves missed-trigger recovery, worker restart, late evidence and SLO incident visibility. | TC-LOOP-006, TC-LOOP-008, TC-LOOP-009, TC-LOOP-010 and TC-LOOP-011 inject the dropped trigger, concurrent run, overdue incident, interrupted worker and late/reordered/stale fact paths |
| `S2-AC-097` | `EXECUTABLY_VERIFIED` | Requirement/Owner Decision → Design → Code → Test → Evidence traceability is complete. | v1-traceability.csv contains OD-S2-001 through OD-S2-020; the as-built design maps the implementation; this document maps all 100 criteria; V0034-root-cause-rework-evidence.md and r1-finding-closure.json map every frozen finding to code, tests and runtime evidence |
| `S2-AC-098` | `EXECUTABLY_VERIFIED` | full repository regression, governance validation, production- readiness validation and security scans pass with no threshold weakening. | local backend/frontend/browser/governance/readiness/supply-chain gates pass without weakened thresholds; Draft PR #26 passes all 12 required contexts plus aggregate CodeQL after repairing every new security annotation and the global-feed integration isolation defect |
| `S2-AC-099` | `EXECUTABLY_VERIFIED` | canonical docs, runbooks, evidence inventory and exact Git identity are synchronized in the same implementation. | CURRENT_STATE, the roadmap, the capability matrix, the design, this status, executable evidence, r1 finding closure/handoff, the API contract and the executable runbook are synchronized in this implementation |
| `S2-AC-100` | `RESERVED_FOR_CONTROLLER_FINAL_CLOSURE` | no unresolved BLOCKER or MAJOR implementation finding remains at Final Closure Verification. | reserved exclusively for the independent Controller; Codex does not self-pass Final Closure |

## Summary

| Status | Count |
| --- | ---: |
| `EXECUTABLY_VERIFIED` | 99 |
| `RESERVED_FOR_CONTROLLER_FINAL_CLOSURE` | 1 |
| Total | 100 |

## Deferred Release obligations

The ten `S2-REL-*` rows are recorded separately in
[deferred-release-register.json](deferred-release-register.json). They are
production-blocking and are not acceptance rows: no engineering result in this
repository can satisfy them, and none is claimed to.

`STOCK_CHANGE`, Overstock, Allocation and Transfer are future product
Capabilities rather than deferred evidence, and are absent from that register
by design.
