# SLICE-V1-002 V0034 root-cause rework evidence

```yaml
document_type: root_cause_rework_evidence
slice: SLICE-V1-002
recorded_at: 2026-09-01
accepted_contract_sha256: d89ea296d0ff854c7d57895b448f9467a22106881d26de4c62a0e8629600556e
reviewed_source_head: c5d896a4ca01ecdc6d4add85fb4fd2e33ba8e4c6
reviewed_source_tree: c94341232b5fa67b5c40a1e6be121a7696e748c4
frozen_finding_set_id: SLICE-V1-002-FROZEN-FINDING-SET-001
frozen_finding_set_sha256: 60589cfa9303d17e71910e085fd18f1d68b87dd9e3b56a99bf6f799879ebcf94
finding_count: 18
engineering_disposition: 18_OF_18_IMPLEMENTED_PENDING_INDEPENDENT_CLOSURE_VERIFICATION
target_branch: fix/SLICE-V1-002-root-cause-rework-r1
controller_verdict: NOT_CLAIMED
owner_formal_closure: NOT_CLAIMED
merge: NOT_EXECUTED
deployment: NOT_EXECUTED
real_provider_calls: NONE
production_write_enabled: false
```

## Evidence boundary

This record binds the rework to the exact reviewed tree and the exact JSON
finding set. It is an engineering disposition, not a Controller verdict or
Owner closure. All runtime evidence is local, synthetic and isolated. No
credential, Buyer PII, provider, shared database, deployment or production
write entered the run. The accepted Contract and V0001–V0033 remain unchanged;
V0034 is the sole forward schema repair.

## Frozen finding disposition

| Finding | Root cause removed | Primary executable evidence |
| --- | --- | --- |
| `S2-F-001` | Inbound is projected at its eligible arrival instant instead of being added to present supply; windows, conflicts, staleness, cancellation and overdue state remain conservative. | `CompanyRiskCalculatorTest` time-phased inbound cases; `AvailabilityRiskFlowIT` governed inbound lifecycle |
| `S2-F-002` | Profit authority is tri-state: a fresh Settled nonpositive value is decisive and fallback occurs only when Settled is unavailable. | `ProfitLaneResolverTest` `TC-PROFIT-LANE-001`–`005` |
| `S2-F-003` | The accepted-fact position is the total tuple `(ingestion_time, provenance_id, item_key)` with explicit backfill start, monotonic persistence and idempotent enqueue. | `AvailabilityRecalculationLoopIT` `TC-LOOP-001`–`003`; V0034 cursor columns and uniqueness |
| `S2-F-004` | Reconciliation is fixed-rate, keyset-paged to exhaustion, progress-recorded, item-failure-isolated and able to fail an abandoned run before the next recovery sweep. | `AvailabilityReconciliationWorkerTest` `TC-RECON-001`–`003`; `AvailabilityRecalculationLoopIT` `TC-LOOP-006`, `008`, `010`; `RepresentativePerformanceIT` 5,000-variant PostgreSQL traversal |
| `S2-F-005` | Company demand uses the union of observable channel intervals and counts each attributable sale once. | `AvailabilityCoverageTest`; `DemandPolicyEngineTest`; `CompanyRiskCalculatorTest` |
| `S2-F-006` | Organization, Store, Product, Data and Action scope are intersected for queue, cards, children, evidence and every mutation; the loaded owned resource is authorized. | `AvailabilityConsoleAuthorizationIT` 14 route/security cases; `AvailabilityRiskSchemaIT` `TC-AVAIL-DB-011` |
| `S2-F-007` | The public manual-success route and console control are absent; only the automatic cause-specific observer can reach verified success. | `AvailabilityConsoleAuthorizationIT` removed-route refusal; `AvailabilityCaseLifecycleIT` `TC-CASE-021`–`025`; `TC-BROWSER-014` |
| `S2-F-008` | Exception, case, child, requester and approver are service-validated and composite-FK-bound inside one organization graph. | `AvailabilityRiskSchemaIT` `TC-AVAIL-DB-006`, `011`; `AvailabilityCaseLifecycleIT` exception adversarial cases |
| `S2-F-009` | Exact fresh channel zero is calculated before company-demand policy resolution, so a company blocker cannot suppress the channel stockout. | `AvailabilityRiskFlowIT` `TC-AVAIL-FLOW-010`; `ChannelRiskCalculatorTest` |
| `S2-F-010` | Fulfillment-mode identity is carried through observations and cases, and availability/demand windows seed from the latest authoritative pre-window state. | `AvailabilityCoverageTest`; `DemandPolicyEngineTest`; `AvailabilityRiskFlowIT` |
| `S2-F-011` | Carry-forward is eligible only when every candidate window is censored and the last eligible window remains inside its bound. | `DemandPolicyEngineTest` truth table and expiry cases |
| `S2-F-012` | Live company supply subtracts reservations, QC lock, damage and write-off and requires current sellability authority. | `CompanyRiskCalculatorTest`; `AvailabilityRiskFlowIT`; V0034 intake fields |
| `S2-F-013` | Versioned return-quality policy, retained/return/refusal assessment and append-only returned-stock re-entry transitions now form one governed path. | `ReturnQualityAssessmentTest`; `AvailabilityRiskFlowIT`; `AvailabilityRiskSchemaIT`; `ReturnInventoryTransitionService` |
| `S2-F-014` | Inbound create/amend/reverify/cancel and policy publish/retire flows are scoped application, API and UI capabilities with audit, effective time, optimistic conflict and recalculation. | `AvailabilityRiskFlowIT` `TC-AVAIL-FLOW-011`, `012`; `AvailabilityConsoleAuthorizationIT`; frontend authority-panel tests |
| `S2-F-015` | Priority weights are effective-dated policy rows and their exact identity participates in projection/card digests. | `PriorityPolicyTest`; `AvailabilityRiskFlowIT`; V0034 priority policy |
| `S2-F-016` | Every sweep revalidates active acceptances against materiality, cause, scope, authority, recurrence, evidence and policy identity and reopens the same case on invalidation. | `AvailabilityExceptionRevalidationTest` `TC-EXC-REVAL-001`–`004`; `AvailabilityCaseLifecycleIT` |
| `S2-F-017` | Aggregate regression is deterministic; 5,000-variant pagination/cadence margin, abandoned-worker recovery, late/reordered/stale evidence, browser verification/reopen/exception, security/supply-chain gates and full traceability are part of the executable gate. | Final command ledger below; `TC-RECON-003`; `TC-LOOP-010`, `011`; `TC-BROWSER-014`; this traceability record |
| `S2-F-018` | The reviewed Claude Head/tree remain immutable evidence; canonical state records the authority conflict and only Codex transports the exact rework branch to a new Draft PR. | `CURRENT_STATE.md`; exact source identity checks; Draft-PR handoff |

## Normative-to-executable traceability

The accepted Contract's 100 criteria retain their one-row-to-evidence mapping
in [acceptance-status.md](acceptance-status.md). The Owner decisions below bind
that requirement inventory to design, implementation and executable proof.

| Owner decision | Design authority | Implementation surface | Test/evidence |
| --- | --- | --- | --- |
| `OD-S2-001`–`004` | Design §§2.1, 2.3, 2.6 | projection writer/query, priority policy, grouped queue/card | `AvailabilityRiskFlowIT`, `PriorityPolicyTest`, frontend queue tests |
| `OD-S2-005`–`006` | Design §§2.4–2.5 | company supply, inbound attestation service/repository/controller | `CompanyRiskCalculatorTest`, `TC-AVAIL-FLOW-012`, authority-panel tests |
| `OD-S2-007`–`011` | Design §§2.3, 2.6–2.7 | profit resolver, lead-time/demand/return-quality policies, window coverage | `ProfitLaneResolverTest`, `DemandPolicyEngineTest`, `ReturnQualityAssessmentTest` |
| `OD-S2-012`–`015` | Design §§2.8–2.10, 2.14, 2.16 | activation, case service, automatic outcome observer, exception service/revalidation | `AvailabilityCaseLifecycleIT`, `AvailabilityExceptionRevalidationTest`, `TC-BROWSER-014` |
| `OD-S2-016`–`017` | Design §2.12 and §7 | explicit no-write/no-adjacent-capability boundary | `AvailabilityNonGoalsTest`, architecture tests, bundle isolation |
| `OD-S2-018` | Design §§2.11, 2.15 | total feed cursor, targeted worker, full reconciliation and durable progress | `AvailabilityRecalculationLoopIT`, `AvailabilityReconciliationWorkerTest` |
| `OD-S2-019` | Design §§2.1–2.2 | independent exact channel child plus asymmetric fail-closed company child | `ChannelRiskCalculatorTest`, `CompanyRiskCalculatorTest`, `TC-AVAIL-FLOW-010` |
| `OD-S2-020` | Design §2.13 and Contract §10 | response observations, percentile query, fixed-rate hourly sweep, health incidents | `AvailabilitySloTest`, `TC-LOOP-004`, `009`–`011`, `TC-RECON-003`, `RepresentativePerformanceIT` |

## Verification command ledger

The authoritative final results are synchronized with
[executable-evidence.md](executable-evidence.md). The required gate comprises:

```text
git diff --check
exact Contract blob/SHA-256 and frozen JSON SHA-256 checks
./mvnw -B -ntp clean verify
python3 scripts/validate_governance.py
python3 scripts/validate_production_readiness.py
python3 -m unittest discover -s tests -p 'test_*.py'
make supply-chain
npm run lint && npm run format:check && npm run typecheck
npm run test:ci && npm run build && npm run verify:bundle
npm run test:browser
remote Draft-PR required checks, including CodeQL and dependency review
```

## Remaining authority boundary

`S2-AC-100` cannot be self-passed: only independent Controller Final Closure
Verification may establish that no unresolved BLOCKER or MAJOR implementation
finding remains. Draft-PR publication is transport, not approval or merge.
Gate EV, Gate E, Pilot, deployment, provider use and production writes remain
unauthorized; `production_write_enabled` remains `false`.
