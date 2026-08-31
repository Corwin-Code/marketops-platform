# V1 Production Assurance Matrix

```yaml
document_type: production_assurance_contract
product_version: V1
active_slice: SLICE-V1-002
review_style: RISK_DRIVEN
quality_policy: PRODUCTION_GRADE_NO_COMPROMISE
```

## 1. Purpose

This Matrix replaces review-by-ceremony with evidence proportional to the actual
failure surface. It does not reduce tests or production controls. It defines what
must be falsified before a Slice or Capability is accepted.

Evidence strength is classified independently from a model or reviewer opinion.

## 2. Evidence classes

| Code | Evidence class | Valid examples | Not sufficient alone |
| --- | --- | --- | --- |
| `SRC` | Primary source / contract | official provider docs, accepted product contract, exact repository source | agent summary |
| `UNIT` | Unit/property/invariant | deterministic tests, mutation-sensitive rules | prose plan |
| `RDB` | Real database | Testcontainers/approved PostgreSQL, concurrency/crash tests | in-memory repository |
| `OBJ` | Object storage | approved provider or protocol integration, hash/readback/retention evidence | byte-array fake |
| `REAL_EXT` | Real external system/provider | Ozon/WB/IdP/Yandex/model provider evidence | fixture/sandbox claim without real proof |
| `SEC_NEG` | Security negative | authz bypass, secret/PII, upload, SSRF, injection, browser bundle tests | happy-path login |
| `REPLAY` | Replay/reconciliation | duplicate/late/out-of-order/replay and total reconciliation | one successful ingest |
| `BROWSER` | Browser E2E | production-like API/database/UI path | component snapshot |
| `PERF` | Performance/capacity | representative load/query/backfill evidence | local single-row timing |
| `DR` | Recovery/disaster drill | database/object restore, worker recovery, kill switch | written rollback only |
| `OPS` | Operator runbook drill | executed runbook with observable result | runbook file existence |
| `AUDIT` | Audit trace | end-to-end actor/evidence/command/readback chain | log line only |

## 2a. SLICE-V1-001 post-merge engineering evidence state

```yaml
assessed_at: 2026-08-30
assessed_against: ACTUAL_SQUASH_COMMIT_D562B81F4F0271AA33A53B21CCAFFC88B5610C0C
controller_verdict: PASS_R2_ENGINEERING_FINAL_GATE
controller_comment_id: 5469390502
approved_engineering_head: f35327a584b980ec4acf7ace7c88e124d6d79709
approved_engineering_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b
approved_tested_merge: bcc3b37965003c3ea1af720ea847dc27fb473a9e
actual_squash_commit: d562b81f4f0271aa33a53b21ccaffc88b5610c0c
actual_squash_tree: 390ebe37bea778b7a4548381ad357fc99aa0da6b
actual_squash_sole_parent: db92cf2f8bd818f36dd8f5aa17b8589c4140b669
engineering_implementation: ENGINEERING_IMPLEMENTATION_CLOSED
production_readiness: DEFERRED_TO_RELEASE_V1_001
owner_formal_closure: HUMAN_OWNER_ACCEPTED
slice_state: CLOSED_ENGINEERING_WITH_DEFERRED_RELEASE_OBLIGATIONS
controller_bookkeeping_verdict: PASS_POST_MERGE_CLOSURE_BOOKKEEPING
controller_bookkeeping_comment: 5469802650
owner_acceptance_comment: 5469935477
owner_acceptance_evidence: docs/08-handoffs/OWNER-SLICE-V1-001-FORMAL-CLOSURE-ACCEPTANCE-EVIDENCE.md
detail: docs/07-phase-evidence/SLICE-V1-001/acceptance-status.md
executable_evidence: docs/07-phase-evidence/SLICE-V1-001/executable-evidence.md
deferred_evidence: docs/07-phase-evidence/SLICE-V1-001/deferred-evidence-register.json
```

This implementation-fact section does not amend the evidence classes or
requirements below. Counts are checkpoint results, not a final release verdict.

| Class | Candidate evidence | Remaining boundary |
| --- | --- | --- |
| `SRC` | Immutable original Contract, accepted Amendment-001 and Amendment-002, preserved ADRs, R1 Finding Set, Supplemental R2 review, exact final Head/tree/tested merge, Controller PASS, actual SQUASH identity and exact Owner Formal Closure evidence. | Production release evidence remains deferred. |
| `UNIT` | R2 mutation-sensitive metric identity, four-state fee-family coverage, target-aware fixed/percentage/tier economics, exact `PRICE_CHANGE` parameter-schema parity, transaction-time freshness, Guardrail, audience, required-context and deferred-status tests passed on the exact final Head. | Real-provider and release evidence remains deferred where Amendment-002 specifies it. |
| `RDB` | Actual-service facts→single DB as-of capture→bounded metric/diagnosis/history reads→evaluated/snapshot identity comparison→Guardrail→approval→command→worker-lease path passed on PostgreSQL; DB rechecks mode/profile/components and eight watermarks at command/worker time. | Production database execution is prohibited and unproven. |
| `OBJ` | Actual filesystem and local HTTP adapter tests, exact hash/length verification, immutable DB custody and retention IaC. | Approved Yandex store, real retention/IAM operation and provider recovery remain unverified. |
| `REAL_EXT` | No real business provider or cloud account used. Synthetic fixtures are explicitly scoped and cannot promote deferred evidence. | Exact Amendment-002 rows remain production-blocking in `RELEASE-V1-001`. |
| `SEC_NEG` | Signed-token/live-scope refusal plus blank/wrong audience, actual-peer loopback enforcement, forwarding-header spoof refusal and existing outbound/PII/Secret controls; exact-Head Security CI passed. | Real identity/provider interoperability remains deferred. |
| `REPLAY` | Real PG17 stored-Raw replay and exact-final-Head regression cover parser/missing-object refusal, crash after fact commit and repeat without duplicate logical fact or source call. | Real source coverage remains external. |
| `BROWSER` | 11 exact-final-Head Chromium scenarios include real signed JWT/SQL evidence→approval→command→readback, export, new-login command recovery and actual local database outage. | Real identity/Marketplace interoperability remains deferred. |
| `PERF` | PG17 representative 5,000-SKU/360,000-order profile, query plans, 488,000-record asynchronous export, explicit thresholds and settings. | Owner cohort and deployed capacity are unproven; no production throughput claim. |
| `DR` | Executed isolated PG17 dump/restore, migration/privilege validation, missing-object refusal and exact-byte recovery. | Real Yandex PITR, deployment rollback and environment restore remain pending. |
| `OPS` | Failure-drill index maps runbooks to executed local faults. Private signals and bounded telemetry transport/No Data are tested. | Actual alert creation, channel delivery, support acknowledgement and staging drill. |
| `AUDIT` | DB authority, immutable attempt/readback/approval/import/verification evidence, actor-bound browser/service paths, Controller Engineering Final Gate, exact merge identity and Owner Formal Closure evidence. | Any future real Gate-EV evidence remains deferred. |

The original Maker evidence assessment is preserved at the reviewed PR Head.
Local evidence does not replace REAL_EXT, Owner approval, Gate EV or Gate E.
R1/C3 receipts remain historical. The R2 final handoff, protected Ruleset proof,
Controller PASS and actual SQUASH identity are the current engineering record.

## 2c. SLICE-V1-002 implementation evidence state

```yaml
assessed_at: 2026-08-31
slice: SLICE-V1-002
contract_sha256: d89ea296d0ff854c7d57895b448f9467a22106881d26de4c62a0e8629600556e
implementation_state: MANDATORY_PRODUCT_PATH_IMPLEMENTED
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
detail: docs/07-phase-evidence/SLICE-V1-002/acceptance-status.md
executable_evidence: docs/07-phase-evidence/SLICE-V1-002/executable-evidence.md
deferred_release: docs/07-phase-evidence/SLICE-V1-002/deferred-release-register.json
```

The Slice carries no controlled-write target at all, so Gate EV and Gate E have
nothing here to authorize and no write-evidence row exists to fill. What this
Slice must instead prove is that a calculated risk becomes accountable work and
that the work is verified rather than merely reported: the two-stage action and
outcome distinction, the one-case-per-cause rule under concurrency and replay,
the fail-closed company answer, and the response obligation measured from the
fact rather than from the worker.

The evidence classes below apply unchanged. Fixture and in-memory results
remain fixture results; nothing in this Slice's record is offered as real
provider, production release or business outcome evidence.

## 2b. Supplemental R2 mutation-sensitive matrix

| # | Required proof | Source |
| --- | --- | --- |
| 1 | Non-hour clock uses one exact stored/queried window | `AnalyticsCalculationServiceWindowTest` |
| 2 | Start boundary included; end boundary excluded | `OperatingFlowIT` |
| 3 | Same-tier target uses the exact proposed price and component identity | `PriceEconomicsCalculatorTest.sameTierUsesTheSameComponentAndExactProposedPrice` |
| 4 | Crossing higher/lower tiers selects the corresponding tier identity | `PriceEconomicsCalculatorTest.crossingHigherTierSelectsTheHigherTierIdentity`; `crossingLowerTierSelectsTheLowerTierIdentity` |
| 5 | Fulfillment modes resolve distinct scoped components | `PriceEconomicsCalculatorTest.fulfilmentModesHaveDistinctScopedComponents` |
| 6 | Fixed plus percentage components both use the proposed price | `PriceEconomicsCalculatorTest.fixedPlusPercentageUsesBothTermsAtTheProposedPrice` |
| 7 | Minimum Price uses the same profile/components as projection | `PriceEconomicsCalculatorTest.minimumPriceUsesTheSameProfileAndSelectedComponentsAsProjection` |
| 8 | Tier overlap, gap/non-resolution and multiple profile authority fail closed | `PriceEconomicsCalculatorTest.overlappingTierAuthorityFailsClosed`; `OperatingFlowIT.profileResolutionFailuresBlockTheActualPreviewService` |
| 9 | Missing/expired profile authority blocks the actual preview service | `OperatingFlowIT.profileResolutionFailuresBlockTheActualPreviewService` |
| 10 | Impact Preview and persisted Minimum Price bind the exact profile id/version/mode | `OperatingFlowIT.approveTheProposal` |
| 11 | Mutating an economics component invalidates an approved snapshot/command | `PriceWritePathIT.anEconomicsComponentChangeInvalidatesThePreviouslyApprovedSnapshot` |
| 12 | Every required historical fee family is independently necessary | `PriceEconomicsCalculatorTest.everyRequiredHistoricalPlatformFeeFamilyMustBePresentIndependently`; `OperatingFlowIT.everyRequiredFeeFamilyIsNecessaryToTheServicePath` |
| 13 | Required-family absence never becomes zero | `PriceEconomicsCalculatorTest.aMissingRequiredFamilyNeverBecomesZero` |
| 14 | Explicit sourced zero remains covered | `PriceEconomicsCalculatorTest.explicitSourcedZeroIsCoveredButVerifiedNonApplicabilityIsNotAZeroFact` |
| 15 | Verified non-applicability does not invent an amount | `PriceEconomicsCalculatorTest.verifiedHistoricalNonApplicabilityPassesWithoutInventingAnAmount` |
| 16 | Missing return/ad/tax/cost and every required economics input independently block | `MetricEngineTest`; `OperatingFlowIT.everyRequiredEconomicsInputIsNecessaryToTheServicePath` |
| 17 | Missing stock and currency mismatch block | `GuardrailEngineTest` |
| 18 | Current freshness ages with wall clock without a new metric/watermark | `PriceEconomicsCalculatorTest.decisionAgeAdvancesWithoutCreatingANewMetricOrWatermark`; `PriceWritePathIT.wallClockAloneExpiresApprovalAndCommandAuthorityUntilAttributedRefresh` |
| 19 | Reconciliation watermark, not source-window start, is attributable authority | `PriceEconomicsCalculatorTest.reconciliationWatermarkIsTheAttributableFreshnessAuthorityNotWindowStart` |
| 20 | Stale/missing feed watermark blocks the actual preview service and attributable refresh restores it | `OperatingFlowIT.currentFeedWatermarksGovernTheActualPreviewService` |
| 21 | DB command/worker gate reevaluates all eight watermarks at transaction time | `PriceWritePathIT.wallClockAloneExpiresApprovalAndCommandAuthorityUntilAttributedRefresh` |
| 22 | Old snapshots remain invalid after a new attributable refresh | `PriceWritePathIT.wallClockAloneExpiresApprovalAndCommandAuthorityUntilAttributedRefresh` |
| 23 | Mapping resolution changes completeness and identity | `MetricEngineTest` |
| 24 | Late facts correct current state without rewriting history | `MetricEngineTest`; `OperatingFlowIT` |
| 25 | Actual-service facts-to-command path passes with no early-return alternative | `OperatingFlowIT.computeMetrics`; `approveTheProposal`; `createTheCommand` |
| 26 | Non-loopback maintenance requests and forwarding-header spoofing are denied | `MaintenanceWriteGateApiIT` |
| 27 | Blank/wrong serving audience fails closed | `IdentityConfigurationContractTest`; `SignedBearerIdentityIT`; `test_yandex_runtime.py` |
| 28 | CodeQL source annotation is identified exactly as run `99214089692`, `AdminMetadataGuard.java:88`, Missing catch of NumberFormatException | Controller source annotation; `AdminMetadataGuard` regression compilation |
| 29 | Numeric remote-address parsing catches `NumberFormatException` and denies instead of escaping | `AdminMetadataGuard`; `MaintenanceWriteGateApiIT` |
| 30 | Required remote contexts, including aggregate CodeQL, pass on exact final Head/tested merge | PR #22: 12/12 required contexts plus aggregate CodeQL SUCCESS; aggregate annotations empty |
| 31 | One DB statement captures `evaluation_as_of` and the complete authority snapshot; the JVM clock is not an evaluation authority | `GuardrailRepository.captureAuthority`; `OperatingFlowIT.profileBoundaryCannotSplitEvaluatedAndStoredAuthority`; `watermarkBoundaryCannotSplitEvaluatedAndStoredAuthority` |
| 32 | Every metric, diagnosis and price-history read used by Guardrail is bounded by the captured DB instant | `MetricQuery.currentValuesAt`; `DiagnosisQuery.currentFindingsAt`; `PriceChangeHistory.priorChangesAt`; `OperatingFlowIT.profileBoundaryCannotSplitEvaluatedAndStoredAuthority` |
| 33 | Evaluated metric entity, mode, profile and component identities must equal the stored snapshot; mismatched persistence is rejected by PostgreSQL | `OperatingFlowIT.profileBoundaryCannotSplitEvaluatedAndStoredAuthority`; `OperatingFlowIT.watermarkBoundaryCannotSplitEvaluatedAndStoredAuthority`; `PriceWritePathIT.aGuardrailCannotPersistAnEvaluatedIdentityDifferentFromItsSnapshot` |
| 34 | Exact parameter schema is required `targetPrice`, optional `fulfillmentModeCode`, and no additional keys in Java and PostgreSQL | `PriceChangeParameterContractTest`; `PriceWritePathIT.oneExactParameterSchemaIsSharedBySnapshotCommandAndWorkerFunctions` |
| 35 | One active fulfillment mode permits an omitted key and binds the unique mode | `OperatingFlowIT.approveTheProposal` |
| 36 | Multiple active modes reject omission; an explicit active mode with distinct economics traverses actual Preview→Approval→Command→worker lease | `OperatingFlowIT.approveTheProposal`; `OperatingFlowIT.createTheCommand` |
| 37 | `UNKNOWN`, inactive and syntactically invalid modes, plus arbitrary extra keys, fail closed | `PriceChangeParameterContractTest.unknownInactiveSyntaxAndEveryExtraKeyFailClosed`; `OperatingFlowIT.approveTheProposal`; `PriceWritePathIT.arbitraryProposalParametersInvalidateCommandAndWorkerAuthority` |
| 38 | Post-approval mode/profile mutation closes command/worker authority while target and idempotency mutation controls remain intact | `OperatingFlowIT.createTheCommand`; `PriceWritePathIT.aPostApprovalModeMutationClosesTheWorkerGate`; `aPostApprovalProfileMutationClosesTheWorkerGate`; `aCommandCannotSubstituteAnyApprovedInput`; `creationDerivesThePriceAndIsIdempotentForTheSameAuthorization` |
| 39 | Selected mode is durable across snapshot, Guardrail evaluation, approval, command API/view, worker row and DB predicate | `OperatingFlowIT.createTheCommand`; `PriceCommandView`; `PriceCommandRepository`; `CommandTimeline`; `PriceWritePathIT.aCommandCannotSubstituteAnyApprovedInput` |

Rows 28–30 deliberately separate the exact historical source annotation, its
local correction, and the successful exact-final-Head aggregate remote result.
An earlier empty open-alert query is not evidence that the annotation never
existed and is not substituted for the final CodeQL check.

Rows 31–39 are the final transitive-closure proof set accepted by Controller
comment `5469390502`. They bind the single as-of source,
evaluated-versus-snapshot identity comparison, exact mode parameter schema and
multi-mode real-PostgreSQL end-to-end path without claiming production readiness
or production readiness. Human Owner Formal Closure is recorded separately and
does not promote any deferred evidence row.

## 3. Risk dimensions

Every Deep Review and Final Gate explicitly assesses applicable dimensions:

1. Product outcome and user workflow;
2. authority/source-of-truth integrity;
3. data correctness, idempotency, late/unknown state and replay;
4. money/profit precision and versioning;
5. concurrency, lease/fencing and crash windows;
6. authentication, authorization, Secret/PII and file intake;
7. external provider volatility, quota and degraded operation;
8. platform write, unknown result, Readback, restore and blast radius;
9. AI grounding, privacy, structured output and deterministic-boundary bypass;
10. migration/backfill/compatibility and rollback;
11. observability, alerting, runbook and disaster recovery;
12. frontend accessibility, safe state representation and browser behavior;
13. performance/capacity and operating cost where material;
14. traceability and release-state truthfulness.

A non-applicable dimension is marked `N/A` with rationale. It is not silently
omitted.

## 4. Minimum Slice 1 assurance coverage

| Area | Required evidence classes | Required failure cases |
| --- | --- | --- |
| OIDC/MFA/RBAC | SRC, REAL_EXT, SEC_NEG, BROWSER, AUDIT | unauthenticated, disabled user, wrong Store/action scope, stale session/step-up |
| Secret/PII | UNIT, SEC_NEG, BROWSER | DB/log/trace/error/browser/AI/file export leakage |
| Worker/acquisition | UNIT, RDB, REPLAY, OPS | duplicate, 429, 5xx, timeout, stale lease/fence, crash-before/after Raw, backlog |
| Raw/Object Storage | RDB, OBJ, REPLAY, DR | wrong hash, already present mismatch, missing/orphan object, read failure, restore |
| Product mapping | UNIT, RDB, BROWSER | duplicate barcode, one-to-many conflict, unknown ID, effective-time change |
| Excel/CSV intake | UNIT, RDB, SEC_NEG, BROWSER, REPLAY | malformed/type/size/malware, duplicate hash, partial reject, schema drift, replay |
| Metrics/profit | UNIT/property, RDB, REPLAY | missing COGS, late fee/return, estimated vs canonical, decimal/rounding, version change |
| AI | UNIT, REAL_EXT, SEC_NEG, BROWSER | PII/Secret, prompt injection, nonexistent metric, malformed output, timeout/provider outage |
| Workflow/policy | UNIT, RDB, SEC_NEG, BROWSER, AUDIT | expired/rejected approval, wrong scope, stale recommendation, policy override/expiry |
| `PRICE_CHANGE` | SRC, UNIT, RDB, REAL_EXT, REPLAY, BROWSER, OPS, AUDIT | duplicate, timeout, partial/native failure, unknown result, readback mismatch, later external change, kill switch |
| UI | BROWSER, PERF, SEC_NEG | stale/estimated/unknown mislabeled, accessibility, API error, session expiry |
| Deployment/recovery | SRC, REAL_EXT, DR, OPS | DB/object restore, deployment rollback, provider outage, credential revoke |

## 5. Gate EV — Bounded Real-Write Verification Authorization

Before any real write is used to generate assurance evidence, Gate EV requires:

- explicit Human Owner authorization;
- exact Platform, opaque Account/Store reference, Capability and SKU allowlist;
- one-time or time-bounded window plus maximum price delta and cumulative
  exposure;
- current official-source and real-account Capability evidence;
- deterministic Guardrails and a passing Dry Run;
- supervised operator, abort owner, manual stop and global/scoped Kill Switch;
- captured pre-state, Readback and Restore/Compensate procedure;
- unknown-result/manual-resolution behavior;
- complete Audit and durable redacted evidence-retention plan.

Gate-EV verdicts are exactly:

```text
AUTHORIZE_BOUNDED_REAL_WRITE_VERIFICATION
CHANGES_REQUIRED
BLOCKED_BY_EXTERNAL_CAPABILITY
BLOCKED_EVIDENCE_INCOMPLETE
```

The authorization is consumed or expires with its named operation/window. It
does not authorize general Pilot scope, unattended scheduling, broad Policy,
production release or any unnamed write.

## 6. Gate E — Controlled-write Capability Enablement

A write Capability is independently enabled per Platform/Account/Store/Scope only
when all are true:

- required real-write evidence was generated under a valid prior Gate-EV
  authorization for the exact scope;
- exact official and real-account Capability evidence is current;
- deterministic Guardrails and permission tests pass;
- Dry Run and stale-state refusal pass;
- command idempotency and transaction/outbox atomicity pass;
- timeout/unknown-result behavior is resolved by status/readback before retry;
- successful execution converges to independent Readback;
- restore/compensate has real bounded proof;
- audit and Kill Switch are complete;
- Pilot Cohort and blast-radius limits are recorded;
- no BLOCKER/MAJOR finding remains;
- Human Owner explicitly enables the exact scope.

Gate E consumes the bounded verification evidence and is the only Gate that may
approve ongoing controlled Pilot production release. Code merge, Slice
completion or Gate EV does not automatically enable the Capability.

## 7. Evidence retention

Evidence index entries record:

```text
contract / acceptance ID
source Head and tested merge identity
command or external test identity
UTC time and environment
result and reviewer
artifact/hash or durable external reference
redaction classification
known limitation / expiry / re-verification date
```

Never commit a credential, Buyer PII, unredacted production payload or a mutable
external link as the only proof.

## 8. Review verdict constraints

- Passing CI is necessary but not sufficient.
- A model claim is never test evidence.
- Fixture evidence cannot be promoted to `REAL_EXT`.
- Missing in-scope evidence is a blocker; it is not silently deferred.
- External evidence needed for Gate EV or production enablement may remain open
  during implementation when code remains fail-closed and the exact consuming
  Gate is recorded.
- Findings use only `BLOCKER`, `MAJOR`, `MINOR` or `INFORMATIONAL` and cite an
  exact source/test/evidence gap.
