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
assessed_at: 2026-08-31
assessment_phase: IMPLEMENTATION_IN_PROGRESS
implementation_state: PARTIAL_IMPLEMENTATION_CHECKPOINT
engineering_closure: NOT_CLAIMED
controller_verdict: NOT_CLAIMED
owner_formal_closure: NOT_CLAIMED
remote_publication: NOT_CLAIMED
controlled_write_target: NONE_IN_THIS_SLICE
real_provider_calls: NONE
deployment: NOT_EXECUTED
gate_ev: NOT_AUTHORIZED
gate_e: NOT_AUTHORIZED
pilot: NOT_AUTHORIZED
production_write_enabled: false
```

## Interpretation

This is an honest mid-implementation status, not a closure claim. The Slice is
not finished: `43` of the hundred criteria are proven by a test that runs today,
`17` are partially proven — the schema or the domain enforces the rule but the
service, API or worker that completes the path is not yet written — and `45`
have no implementation in this checkpoint at all.

A criterion is `EXECUTABLY_VERIFIED` only when a named, currently passing test
asserts it. Nothing here is marked verified on the strength of code existing.

The unimplemented remainder is concentrated in four areas, all of which depend
on the calculation core that this checkpoint establishes: case activation and
the two-stage action/verification lifecycle, accepted-exception governance and
its approval escalation, the targeted and hourly recalculation workers with
their SLO evidence, and the operating surface — console API, queue UI and the
browser journey over it.

## All one hundred criteria

| ID | Status | Contract requirement | Verification or gap |
| --- | --- | --- | --- |
| `S2-AC-001` | `EXECUTABLY_VERIFIED` | repository contains the exact accepted Contract bytes and attributable Human Owner acceptance evidence. | Contract blob 1caa50f1 and SHA-256 d89ea296 re-hash at the canonical path; acceptance evidence re-hashes to 4e243c85 |
| `S2-AC-002` | `PARTIALLY_VERIFIED` | canonical roadmap and current state reflect the narrowed Stockout/Availability queue-and-Task Scope while preserving original roadmap provenance… | the roadmap and current-state flip to SLICE-V1-002 is not yet made; both validators still pin SLICE-V1-001 as active |
| `S2-AC-003` | `PARTIALLY_VERIFIED` | no Overstock, Allocation, Transfer, Advertising Intervention, replenishment-quantity or `STOCK_CHANGE` capability is implemented or claimed. | no such capability exists in code, but no dedicated absence test asserts it yet |
| `S2-AC-004` | `EXECUTABLY_VERIFIED` | Shared Spine authorities are reused; architecture tests prevent a parallel ingestion, metric, policy, workflow or audit authority. | 65 architecture tests; ModulithArchitectureTest pins the module set including availabilityrisk |
| `S2-AC-005` | `EXECUTABLY_VERIFIED` | conditional Design Gate remains untriggered or any later trigger is handled through the exact governance path rather than silently bypassed. | the Contract gate recorded NOT_TRIGGERED and no later trigger arose in this checkpoint |
| `S2-AC-006` | `PARTIALLY_VERIFIED` | backend Organization/Platform/Store/Product/Data/Action Scope enforcement prevents horizontal and vertical access escalation. | the controller authorizes and then narrows the read by the same grant (TC-AVAIL-FLOW-008); no HTTP-level escalation test exists yet |
| `S2-AC-007` | `NOT_IMPLEMENTED` | channel, company, data, policy, profit and quality Tasks route to the correct accountable role under versioned policy. | not implemented in this checkpoint |
| `S2-AC-008` | `NOT_IMPLEMENTED` | missing or conflicting assignee/approver authority fails closed and remains operator-visible. | not implemented in this checkpoint |
| `S2-AC-009` | `PARTIALLY_VERIFIED` | exception escalation enforces domain-lead, Ops Lead and Owner-designated Risk Authority boundaries, including requester separation for… | availability_exception_decision_separation_ck refuses a requester-approved material exception (TC-AVAIL-DB-006); the escalation service is not yet written |
| `S2-AC-010` | `NOT_IMPLEMENTED` | all sensitive reads/mutations and delegation changes are attributable and audited. | not implemented in this checkpoint |
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
| `S2-AC-022` | `PARTIALLY_VERIFIED` | only role-scoped, evidence-backed Product/Procurement attestation can make inbound eligible. | the attestation schema and repository enforce role-scoped evidence; the attestation service and its authorization call are not yet written |
| `S2-AC-023` | `EXECUTABLY_VERIFIED` | draft/estimated, stale, cancelled, overdue, ambiguous or conflicting inbound cannot reduce risk. | TC-COMPANY-008, TC-COMPANY-010 |
| `S2-AC-024` | `PARTIALLY_VERIFIED` | inbound amendment/expiry/cancellation creates attributable version history and triggers recalculation. | append-only version history exists and is enforced; the amendment service and its recalculation trigger are not yet written |
| `S2-AC-025` | `EXECUTABLY_VERIFIED` | D7/D14/D30 values, sample sufficiency, selected rate, reason, Confidence and policy version are exposed. | mart.demand_window_observation rows written per calculation; TC-AVAIL-FLOW-005 |
| `S2-AC-026` | `EXECUTABLY_VERIFIED` | sustained recent acceleration changes the selected rate under transparent bounded policy; an isolated outlier cannot silently dominate. | TC-DEMAND-002, TC-DEMAND-003, TC-DEMAND-009 |
| `S2-AC-027` | `EXECUTABLY_VERIFIED` | low sample, data gap, window conflict and unexplained outlier fail to explicit Review/Blocked states rather than zero. | TC-DEMAND-003, TC-DEMAND-008, TC-DEMAND-009, TC-DEMAND-010 |
| `S2-AC-028` | `EXECUTABLY_VERIFIED` | Completed Sales are the primary operational demand stage. | the demand path reads only SaleStage.COMPLETED; TC-AVAIL-FLOW-002 |
| `S2-AC-029` | `NOT_IMPLEMENTED` | Retained/Return/QC evidence acts as a distinct quality Guardrail. | not implemented in this checkpoint |
| `S2-AC-030` | `NOT_IMPLEMENTED` | returned supply counts only after an attributable Inventory Ledger re-entry fact. | not implemented in this checkpoint |
| `S2-AC-031` | `EXECUTABLY_VERIFIED` | materially unavailable periods do not count as ordinary zero demand. | TC-DEMAND-005, TC-DEMAND-011 |
| `S2-AC-032` | `EXECUTABLY_VERIFIED` | eligible-window selection remains deterministic and channel/company censoring remains distinguishable. | TC-DEMAND-001 through TC-DEMAND-005 |
| `S2-AC-033` | `EXECUTABLY_VERIFIED` | bounded last-eligible demand carry-forward exposes source period, expiry and Confidence downgrade. | TC-DEMAND-006 |
| `S2-AC-034` | `EXECUTABLY_VERIFIED` | expired carry-forward produces `DATA_BLOCKED`, never zero demand or indefinite historical demand. | TC-DEMAND-007 |
| `S2-AC-035` | `EXECUTABLY_VERIFIED` | lead-time/safety policy resolves by exact scoped fallback and is effective-dated, versioned, evidence-linked and owned. | AvailabilityPolicyRepository.resolveLeadTime; TC-AVAIL-DB-002; TC-AVAIL-FLOW-001 |
| `S2-AC-036` | `EXECUTABLY_VERIFIED` | absent, stale, overlapping or conflicting policy yields `POLICY_BLOCKED`. | TC-COMPANY-011, TC-AVAIL-DB-001, TC-AVAIL-DB-002 |
| `S2-AC-037` | `PARTIALLY_VERIFIED` | Fresh positive Settled Profit yields confirmed eligibility. | the settled lane is implemented in ProfitLaneResolver; no dedicated test seeds a settled metric value yet |
| `S2-AC-038` | `PARTIALLY_VERIFIED` | Fresh complete positive Operational Profit may yield operational eligibility when Settled is unavailable. | the operational lane is implemented in ProfitLaneResolver; no dedicated test seeds an operational metric value yet |
| `S2-AC-039` | `PARTIALLY_VERIFIED` | estimated profit is provisional; stale/incomplete/conflicted profit is blocked. | the provisional and blocked lanes are implemented; no dedicated test seeds an estimated or stale metric value yet |
| `S2-AC-040` | `NOT_IMPLEMENTED` | Fresh complete zero/negative profit cannot enter the primary profitable-stockout queue through lifecycle override. | not implemented in this checkpoint |
| `S2-AC-041` | `EXECUTABLY_VERIFIED` | hard imminent-stockout escalation cannot be buried by commercial scoring. | TC-RANK-001 |
| `S2-AC-042` | `EXECUTABLY_VERIFIED` | normal rank uses only permitted visible factors and deterministic policy. | TC-RANK-002, TC-RANK-004, TC-RANK-005 |
| `S2-AC-043` | `EXECUTABLY_VERIFIED` | AI or free-form text cannot change canonical lane or rank. | RankFactor.Code is a closed set and no AI type is reachable from the rank path; TC-RANK-002 |
| `S2-AC-044` | `EXECUTABLY_VERIFIED` | Fresh exact channel stockout remains actionable despite unrelated material source defects. | TC-CHANNEL-002 |
| `S2-AC-045` | `EXECUTABLY_VERIFIED` | material incomplete/conflicting company evidence can never produce `HEALTHY`, `SAFE` or verified clearance. | TC-COMPANY-004, TC-COMPANY-012, TC-ADV-001, TC-AVAIL-DB-003 |
| `S2-AC-046` | `EXECUTABLY_VERIFIED` | provisional company risk is emitted only when a reproducible known-evidence conservative proof already establishes danger. | TC-COMPANY-005, TC-ADV-002, TC-ADV-003, TC-AVAIL-DB-004 |
| `S2-AC-047` | `EXECUTABLY_VERIFIED` | if the missing fact determines the conclusion, company risk remains unresolved/data-blocked. | TC-COMPANY-004, TC-COMPANY-012, TC-COMPANY-015 |
| `S2-AC-048` | `EXECUTABLY_VERIFIED` | stale stock, Mapping or ownership is context-only after expiry and cannot count as current supply. | TC-CHANNEL-004, TC-COMPANY-013 |
| `S2-AC-049` | `NOT_IMPLEMENTED` | independent channel, data/Mapping and provisional commercial causes route to non-duplicated accountable Tasks. | not implemented in this checkpoint |
| `S2-AC-050` | `PARTIALLY_VERIFIED` | every unexcepted CRITICAL cause automatically creates or updates one accountable Task with due time and evidence. | the case schema and its one-live-case-per-cause index are proven; the activation service is not yet written |
| `S2-AC-051` | `NOT_IMPLEMENTED` | HIGH activates only under its sustained/hard governed condition and does so in the qualifying evaluation cycle. | not implemented in this checkpoint |
| `S2-AC-052` | `NOT_IMPLEMENTED` | WATCH remains queue-visible without automatic Task noise. | not implemented in this checkpoint |
| `S2-AC-053` | `NOT_IMPLEMENTED` | blocker/review cases create cause-specific remediation rather than misleading ordinary restock work. | not implemented in this checkpoint |
| `S2-AC-054` | `PARTIALLY_VERIFIED` | repeated recalculation updates one Case; concurrency and replay cannot duplicate it. | TC-AVAIL-DB-005 proves the database refuses a duplicate live case; the service-level concurrency path is not yet written |
| `S2-AC-055` | `NOT_IMPLEMENTED` | independently actionable causes with different owners may have separate, explicitly related Tasks. | not implemented in this checkpoint |
| `S2-AC-056` | `EXECUTABLY_VERIFIED` | free-text acknowledgement cannot satisfy the action stage. | TC-AVAIL-DB-010 |
| `S2-AC-057` | `NOT_IMPLEMENTED` | structured action evidence transitions to verification without claiming outcome success. | not implemented in this checkpoint |
| `S2-AC-058` | `NOT_IMPLEMENTED` | fresh cause-specific outcome evidence is required for verified success. | not implemented in this checkpoint |
| `S2-AC-059` | `NOT_IMPLEMENTED` | ETA/evidence/policy/source/risk regression automatically reopens or escalates the same Case with history preserved. | not implemented in this checkpoint |
| `S2-AC-060` | `NOT_IMPLEMENTED` | Action SLA and Outcome SLA are separately observable. | not implemented in this checkpoint |
| `S2-AC-061` | `EXECUTABLY_VERIFIED` | exception preserves the calculated risk and uses an explicit accepted-risk disposition. | availability_accepted_exception has no path that alters a calculated lane; TC-AVAIL-DB-007 |
| `S2-AC-062` | `PARTIALLY_VERIFIED` | exception requires exact scope/cause, evidence, rationale, commercial consequence, owner, approver, period, review and policy version. | every required field is a NOT NULL column; the request service is not yet written |
| `S2-AC-063` | `NOT_IMPLEMENTED` | ordinary action SLA may pause only while exception governance and expiry remain active. | not implemented in this checkpoint |
| `S2-AC-064` | `PARTIALLY_VERIFIED` | expiry, materiality/cause/scope change, authority loss, evidence conflict or repeat condition invalidates the exception. | the invalidation columns and their constraint exist; the expiry sweep is not yet written |
| `S2-AC-065` | `NOT_IMPLEMENTED` | invalidation reopens/escalates the same Case. | not implemented in this checkpoint |
| `S2-AC-066` | `NOT_IMPLEMENTED` | no user can convert stale/conflicted evidence into a safe canonical result through an exception. | not implemented in this checkpoint |
| `S2-AC-067` | `EXECUTABLY_VERIFIED` | no permanent hidden monitoring exclusion is introduced. | availability_accepted_exception_active_ck; TC-AVAIL-DB-007 |
| `S2-AC-068` | `NOT_IMPLEMENTED` | every qualifying accepted/invalidated fact recalculates the exact affected Variant and causes. | not implemented in this checkpoint |
| `S2-AC-069` | `EXECUTABLY_VERIFIED` | targeted and full-sweep result are identical for the same as-of evidence and policy. | TC-AVAIL-FLOW-004 compares the whole calculated value, not a summary |
| `S2-AC-070` | `NOT_IMPLEMENTED` | CRITICAL targeted card/Task update meets P95 <= 5 minutes and hard <= 15 minutes from `fact_accepted_at`. | not implemented in this checkpoint |
| `S2-AC-071` | `NOT_IMPLEMENTED` | HIGH/WATCH/blocker targeted update meets hard <= 15 minutes. | not implemented in this checkpoint |
| `S2-AC-072` | `NOT_IMPLEMENTED` | full portfolio reconciliation succeeds at least hourly at the declared acceptance capacity. | not implemented in this checkpoint |
| `S2-AC-073` | `NOT_IMPLEMENTED` | a deliberately dropped targeted trigger is repaired by the next scheduled successful hourly reconciliation. | not implemented in this checkpoint |
| `S2-AC-074` | `NOT_IMPLEMENTED` | missed sweep, backlog or SLO breach becomes an operator-visible incident. | not implemented in this checkpoint |
| `S2-AC-075` | `NOT_IMPLEMENTED` | all source/internal timing fields required by this Contract are queryable and included in evidence. | not implemented in this checkpoint |
| `S2-AC-076` | `NOT_IMPLEMENTED` | late, reordered and expired facts produce deterministic, attributable recalculation without duplicate Case effects. | not implemented in this checkpoint |
| `S2-AC-077` | `PARTIALLY_VERIFIED` | structured queue, grouped card, evidence drill-through, Task and exception surfaces support the complete operating path. | queue, grouped card and evidence drill-through exist and read back (TC-AVAIL-FLOW-007, TC-UI-001); the task and exception surfaces do not |
| `S2-AC-078` | `EXECUTABLY_VERIFIED` | API filtering/pagination and frontend navigation inherit backend scope; platform DTO/SDK types do not leak into public business contracts. | TC-AVAIL-FLOW-008 and TC-AVAIL-FLOW-009; no platform DTO appears in the published contract, enforced by TC-ARCH-007 |
| `S2-AC-079` | `PARTIALLY_VERIFIED` | keyboard use, safe errors, UTC/internal time and Store-local display, UTF-8 and Russian text are verified. | TC-UI-002 proves Russian text renders intact and controls are focusable elements; no browser journey exists yet |
| `S2-AC-080` | `NOT_IMPLEMENTED` | metrics/logs/traces cover targeted processing, sweep, backlog, dedup, verification, exception expiry and SLO. | not implemented in this checkpoint |
| `S2-AC-081` | `NOT_IMPLEMENTED` | runbooks prove operator response to stale source, ownership conflict, policy blocker, backlog/SLO breach and failed reconciliation. | not implemented in this checkpoint |
| `S2-AC-082` | `NOT_IMPLEMENTED` | Secret, Buyer PII, unsafe Raw and real Credentials are absent from Git, fixtures, logs, errors and client bundles. | not implemented in this checkpoint |
| `S2-AC-083` | `NOT_IMPLEMENTED` | no real Provider call occurs in engineering tests or runtime evidence. | not implemented in this checkpoint |
| `S2-AC-084` | `NOT_IMPLEMENTED` | no stock-write Preview, Approval, Command, Adapter write, Readback or hidden manual target path exists. | not implemented in this checkpoint |
| `S2-AC-085` | `NOT_IMPLEMENTED` | `production_write_enabled` is and remains `false`. | not implemented in this checkpoint |
| `S2-AC-086` | `EXECUTABLY_VERIFIED` | applied migrations remain byte-identical; only forward migrations are added when required. | FlywayMigrationIT TC-DB-111 and TC-DB-113; V0001-V0029 unchanged, V0030 is the only addition |
| `S2-AC-087` | `EXECUTABLY_VERIFIED` | clean install and protected-main upgrade paths pass against real PostgreSQL. | FlywayMigrationIT clean-install and upgrade cases against PostgreSQL 18.4 |
| `S2-AC-088` | `PARTIALLY_VERIFIED` | restart, replay, concurrency and reconciliation cannot duplicate facts, cards, Cases, actions, exceptions or audit events. | TC-AVAIL-FLOW-006 proves repeated calculation does not duplicate a card or child; worker restart and replay are not yet exercised |
| `S2-AC-089` | `EXECUTABLY_VERIFIED` | append-only audit and historical policy/evidence versions survive rebuild and forward-fix. | no DELETE grant exists; TC-AVAIL-DB-009 |
| `S2-AC-090` | `NOT_IMPLEMENTED` | unit/property tests cover calculations, policies and state invariants. | not implemented in this checkpoint |
| `S2-AC-091` | `EXECUTABLY_VERIFIED` | architecture tests enforce Shared Spine, module and no-write boundaries. | 65 architecture tests including the module, boundary and rule-sensitivity suites |
| `S2-AC-092` | `PARTIALLY_VERIFIED` | PostgreSQL integration tests cover concurrency, uniqueness, effective-time resolution, recalculation and migrations. | AvailabilityRiskSchemaIT covers uniqueness, effective-time resolution and the fail-closed constraints; concurrency and recalculation-under-load are not yet exercised |
| `S2-AC-093` | `NOT_IMPLEMENTED` | browser E2E covers queue → Task → action → verification → reopen/exception under role scope. | not implemented in this checkpoint |
| `S2-AC-094` | `EXECUTABLY_VERIFIED` | mutation/adversarial tests prove that removing a Gate, deduplication, expiry, scope or fail-closed condition causes test failure. | TC-ADV-001 through TC-ADV-007, TC-AVAIL-DB-001 through TC-AVAIL-DB-010 |
| `S2-AC-095` | `NOT_IMPLEMENTED` | performance evidence proves the internal SLOs and hourly sweep at the declared acceptance capacity. | not implemented in this checkpoint |
| `S2-AC-096` | `NOT_IMPLEMENTED` | fault injection proves missed-trigger recovery, worker restart, late evidence and SLO incident visibility. | not implemented in this checkpoint |
| `S2-AC-097` | `NOT_IMPLEMENTED` | Requirement/Owner Decision → Design → Code → Test → Evidence traceability is complete. | not implemented in this checkpoint |
| `S2-AC-098` | `NOT_IMPLEMENTED` | full repository regression, governance validation, production- readiness validation and security scans pass with no threshold weakening. | not implemented in this checkpoint |
| `S2-AC-099` | `NOT_IMPLEMENTED` | canonical docs, runbooks, evidence inventory and exact Git identity are synchronized in the same implementation. | not implemented in this checkpoint |
| `S2-AC-100` | `NOT_IMPLEMENTED` | no unresolved BLOCKER or MAJOR implementation finding remains at Final Closure Verification. | not implemented in this checkpoint |

## Summary

| Status | Count |
| --- | ---: |
| `EXECUTABLY_VERIFIED` | 43 |
| `PARTIALLY_VERIFIED` | 17 |
| `NOT_IMPLEMENTED` | 40 |
| Total | 100 |

## Deferred Release obligations

The ten `S2-REL-*` rows are recorded separately in
[deferred-release-register.json](deferred-release-register.json). They are
production-blocking and are not acceptance rows: no engineering result in this
repository can satisfy them, and none is claimed to.

`STOCK_CHANGE`, Overstock, Allocation and Transfer are future product
Capabilities rather than deferred evidence, and are absent from that register
by design.
