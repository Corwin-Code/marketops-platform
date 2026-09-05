# Command/control finding and AC evidence shard

This shard covers seven Frozen Findings and all **60** distinct associated ACs. Every AC text was checked against the exact accepted Contract. All 22 original findings were read to identify shared dependencies. It does not modify the accepted Contract, Frozen Finding Set or Controller verdict.

**Current status:** canonical baseline, Ordinary approval and control corrections passed the coherent 248-test source-r12 superset. The new Finance review V0065 correction and final full/remote verification remain pending; 88-test and 14-test runs are historical records.

## Preserved executed runs

- `controls-88`: 88 passed, zero failures/errors/skips. Log: `docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/logs/slice3-command-controls-it-r2.log`; SHA-256 `10fc992fafce91efb35f0502e04d69570829a70eae530f2e1449bca72a51843b`.
- `compensation-14`: 14 passed, zero failures/errors/skips. Log: `docs/07-phase-evidence/SLICE-V1-003/rework-r1/workstreams/logs/slice3-compensation-control-it-r2.log`; SHA-256 `41334bb93446e74cb2e316933bc32ee5e8e75d2e2bead6501da150db3c64e244`.

The JSON shard records exact commands, source paths/symbols, test method names and current inspected file hashes. A current source hash must not be mistaken for a measured-run source identity.

## Finding contributions

### S3-DR-009 — Approval Lease is re-anchored at command preparation and does not use the earliest authority bound

The final seal fixes finalApprovedAt and the earliest finite authority bounds; command creation accepts recommendation/version/reservation only. Creator, lease/attempt and retry gates consume the same sealed identity and append-only invalidation, including selected canonical baseline authority.

ACs: `S3-AC-107`, `S3-AC-108`, `S3-AC-136`, `S3-AC-149`.

Concrete supporting methods: `AdvertisingSealedAuthorityIT.realApplicationCreatorUsesExactImmutableApprovalAndMinimumFrozenExpiry`, `AdvertisingSealedAuthorityIT.changedPolicyAfterEndorsementCannotBeSilentlySealed`, `AdvertisingTransmissionBoundaryIT.anExpiredApprovalIsNamedAsItsOwnRefusal`, `AdvertisingTransmissionBoundaryIT.leasedWorkCannotTransmitAfterTheReservationLapses`, `AdvertisingReservationIT.containmentPermanentlyInvalidatesPriorApprovalAssets`.

Evidence limits: The named targeted run proves the minimum selected baseline bound and expiry/transmission/invalidation cases; it is not a claim that every authority-minimum permutation was independently exercised. The subsequent canonical baseline attestation changes require an integrated rerun.

### S3-DR-010 — The SECURITY DEFINER command creator permits actor and authority spoofing at the database boundary

A separate issuer signs a one-use proof for the actual authenticated identity, exact target/version and physical application backend/transaction. The final seal derives maker/endorser/approver, selected baseline, Bundle and expiry from canonical rows; app grants cannot mint proofs or invoke the private creator. Native object, target, parameter, full affected Product and Store authority remain independently checked.

ACs: `S3-AC-006`, `S3-AC-016`, `S3-AC-104`, `S3-AC-106`, `S3-AC-111`, `S3-AC-112`, `S3-AC-198`.

Concrete supporting methods: `AdvertisingSealedAuthorityIT.realApplicationCreatorUsesExactImmutableApprovalAndMinimumFrozenExpiry`, `AdvertisingSealedAuthorityIT.applicationCannotMintProofOrRestoreTheRemovedActorParameterCreator`, `AdvertisingSealedAuthorityIT.arbitraryApplicationGucCannotImpersonateFinalApprover`, `AdvertisingSealedAuthorityIT.grantFromDifferentPhysicalApplicationSessionCannotBeReplayed`, `AdvertisingSealedAuthorityIT.makerProofCannotBecomeOwnersFinalApproval`, `AdvertisingSealedAuthorityIT.currentActorRevocationInvalidatesAnAlreadyIssuedProof`, `AdvertisingSealedAuthorityIT.storeOnlyApprovalGrantCannotAuthoriseUndisclosedAffectedProducts`, `AdvertisingSealedAuthorityIT.finalApprovalCannotSwapInAnUnapprovedBaseline`.

Evidence limits: AC006 architecture and AC198 all-channel disclosure require the parent architecture/security/UI results as well as this DB boundary evidence. Trusted canonical baseline proof is a transitive correction now awaiting a fresh integrated run.

### S3-DR-012 — Idempotency, NOT_APPLIED retry and exact native Readback semantics are incomplete

Verified operation snapshots freeze request/native identity; task status remains observation-only until converged. Explicit NOT_APPLIED plus exact current prior value and live bounded authority is required for the non-native retry route. Readback parses actual Raw value/currency/unit with exact equality; HTTP acceptance and third values cannot become success or overwrite authority.

ACs: `S3-AC-111`, `S3-AC-112`, `S3-AC-113`, `S3-AC-114`, `S3-AC-115`, `S3-AC-116`, `S3-AC-117`, `S3-AC-118`, `S3-AC-119`, `S3-AC-120`, `S3-AC-121`.

Concrete supporting methods: `AdBidCommandWorkerTest.pendingNativeStatusPollPreservesTaskAndNeverAppliesAgain`, `AdBidCommandWorkerTest.resolvedNativeStatusMustReadBackBeforeSuccess`, `AdBidWritePortContractTest.sameCallSameDigest`, `AdBidWritePortContractTest.unitIsPartOfTheIdentity`, `AdBidWriteDispatchTest.anAnsweredCallCarriesItsOwnEvidence`, `AdBidCommandWorkerTest.sameCommandRetryNeedsIndependentDatabaseProof`, `AdBidCommandWorkerTest.aThirdValueRoutesToInvestigation`, `AdBidCommandWorkerTest.unreadableObservationStaysUnknown`, `AdBidCommandWorkerTest.timeoutAndUnknownAreTheSame`, `AdvertisingTransmissionBoundaryIT.anUnknownResultIsNeverRepeated`, `AdBidWriteRefusalTest.staleAttemptAtTheSocketStopsTheCall`, `AdBidWriteDispatchTest.truncatedBytesAreNeverAccepted`.

Evidence limits: Worker/port/adapter unit tests use a fictional protocol and test doubles at the Provider boundary; they are not real Ozon/Wildberries capability verification.

### S3-DR-013 — Same-object reentry and exact Compensation are not an executable governed path

General same-object replacement remains disabled. Compensation uses the original reservation and captured prior bid, current matched ownership, an action-bound human Stop or current Regression, a new Maker preview, independent Ops endorsement and Owner final approval, and an exact compensation Bundle/Gate. RESTORE is separately leased and observed, with permanent authorization invalidation and no automatic reversal.

ACs: `S3-AC-122`, `S3-AC-123`, `S3-AC-124`, `S3-AC-125`, `S3-AC-126`, `S3-AC-150`.

Concrete supporting methods: `AdvertisingReservationIT.exactCompensationUsesNewHumanChainAndCanOpenOnlyCapturedPriorBidRestore`, `AdBidCommandWorkerTest.compensationKeepsItsLeaseUntilExactPriorReadback`, `AdBidWritePortContractTest.restoreUsesADifferentIdempotencyKey`, `AdBidCommandWorkerTest.compensationCannotOverwriteThirdPartyCurrentValue`, `AdvertisingReservationIT.exactCompensationUsesNewHumanChainAndCanOpenOnlyCapturedPriorBidRestore`.

Evidence limits: The PostgreSQL positive test opens the exact RESTORE attempt; it never calls a Provider. Worker tests separately prove prior-value readback classification. Real exact-compensation capability remains disabled absent an exact external Gate envelope.

### S3-DR-014 — Reservation and aggregate Exposure enforce only a subset of the accepted real-intervention envelope

Real controlled/manual actions reserve the canonical full affected set. Admission is serialized and intersections are checked across objects/digests. The shared exposure snapshot evaluates six independent axes at every applicable Store/platform/organization scope, preserving unknown states and recovery headroom. Release derives exact configuration, current early company and frozen critical safety; regression reacquires quarantine while preserving other holders.

ACs: `S3-AC-127`, `S3-AC-128`, `S3-AC-129`, `S3-AC-130`, `S3-AC-131`, `S3-AC-132`, `S3-AC-133`, `S3-AC-134`, `S3-AC-135`, `S3-AC-136`, `S3-AC-137`.

Concrete supporting methods: `AdvertisingReservationIT.takingTwiceForOneSealedInterventionIsIdempotent`, `AdvertisingSealedAuthorityIT.cumulativeExposureIsMajorCurrencyAndUsesAllBroaderPolicies`, `AdvertisingReservationIT.applicationCannotWriteReservationOrAssertReleaseConditions`, `AdvertisingReservationIT.pendingRecommendationCannotReserve`, `AdvertisingReservationIT.overlappingVariantsNameTheExistingHolderEvenForAnotherObjectKey`, `AdvertisingReservationIT.missingConfigurationAndEarlySafetyEvidenceKeepReservationHeld`.

Evidence limits: Real company/critical early release, missing-evidence hold and late-correction reacquisition are cross-stream AdvertisingFrozenOutcomeIT evidence; parent must attach its current exact rerun. Manual parity, recovery-capacity and cross-domain confounding require the current Manual/capacity/Outcome suites and full integrated evidence, not these four reservation assertions alone.

### S3-DR-016 — Quarantine, Kill and reenablement do not preserve cause-proportional scope or non-resurrection

Canonical affected membership defines intersection; cause-proportional object/set/version/account-Store-capability containment activates immediately through live scoped authority. Pending approvals/commands/manual assets are permanently invalidated, unsent attempts recheck fences, and sent facts retain observation-only reconciliation. Reenablement requires distinct Ops/Owner and technical attestation when applicable plus new exact authority; no old asset is revived.

ACs: `S3-AC-138`, `S3-AC-139`, `S3-AC-140`, `S3-AC-141`, `S3-AC-142`, `S3-AC-143`, `S3-AC-144`, `S3-AC-145`, `S3-AC-146`, `S3-AC-147`, `S3-AC-148`, `S3-AC-149`, `S3-AC-150`.

Concrete supporting methods: `AdvertisingReservationIT.authenticatedOperationsStopCoversTheStoreCapability`, `AdvertisingReservationIT.affectedSetStopIntersectsVariantsAcrossDifferentDigests`, `AdvertisingReservationIT.emptyScopeHasNoContainment`, `AdvertisingReservationIT.stopperCannotEndorseTheirOwnReenablement`, `AdvertisingReservationIT.businessRoleCannotFabricateTechnicalSecurityAttestation`, `AdvertisingReservationIT.containmentPermanentlyInvalidatesPriorApprovalAssets`, `AdvertisingTransmissionBoundaryIT.aKillSwitchThrownMidFlightClosesTheGate`, `AdvertisingTransmissionBoundaryIT.aStaleFenceOpensNothing`.

Evidence limits: These targeted cases do not by themselves prove every role/scope combination or a complete positive technical reenablement chain. Parent must combine current full suite and browser evidence before claiming all ACs verified. Manual in-progress reconciliation and Outcome regression fanout are shared implementation/evidence obligations.

### S3-DR-017 — Gate EV, Gate E, Ordinary promotion and complete Policy-Bundle activation authorities are absent or incomplete

Structured inactive Gate EV/E and Ordinary records bind exact scope, values, period, actors, evidence, exposure and demonstrated prior authority. Bundle publication freezes every referenced component and the exact proposed Gate at independent endorsement; distinct final approval activates atomically, retires prior authority and creates permanent invalidation. Missing, conflicting or widened authority refuses; generic reentry and unverified platform routes remain disabled.

ACs: `S3-AC-006`, `S3-AC-109`, `S3-AC-110`, `S3-AC-169`, `S3-AC-171`, `S3-AC-172`, `S3-AC-173`, `S3-AC-174`, `S3-AC-175`, `S3-AC-176`, `S3-AC-177`, `S3-AC-178`, `S3-AC-179`, `S3-AC-180`.

Concrete supporting methods: `AdvertisingReservationIT.newBundleRequiresThreeActorsAndAtomicallyRetiresPriorAuthority`, `AdvertisingReservationIT.bundleGateChangedAfterEndorsementCannotBecomeApprovedAuthority`, `AdvertisingSealedAuthorityIT.changedPolicyAfterEndorsementCannotBeSilentlySealed`, `AdvertisingTransmissionBoundaryIT.unverifiedCapabilityClosesTheGate`.

Evidence limits: Engineering inactive models and fail-closed tests do not grant real Gate EV, Pilot Gate E, Ordinary promotion or production use. AC174 includes continuing real 30-day/Settled obligations; its external evidence belongs in the separate REL/deferred register. Every adjacent-axis Gate EV/E/promotion rejection and architecture coverage must be verified by the final full suite; this targeted Bundle run alone is not that complete matrix.

## Remaining integrated evidence

The root R1 record must bind final full verification, complete cross-stream AC contributions, append-only branch transport, one Draft PR, and all 12 exact CI contexts to the measured commit/tree. Real Release/Gate EV/Gate E/Ordinary evidence stays in the deferred register; engineering tests authorize none of it.

Current SQL smoke evidence: `logs/slice3-baseline-materiality-seal-probe.log`, SHA-256 `faae1ea7f1445f613d58cc331184dc2caf17269632d30398eb6616524b167717`. The isolated fresh migration, typed baseline, trusted seal, real reservation, command creation and live gate passed; `production_write_enabled=false`. This is not a full verification result.

### Current coherent targeted superset

Source run `source-r12` completed at 2026-09-05 09:53:38 Asia/Taipei: **248/248 passed**, zero failures/errors/skips. It includes the real trusted planner, Human10, Manual9, FrozenOutcome7, Canonical10, SealedAuthority15, Reservation14, OrdinaryApproval7 and Materiality13, plus the named domain suites. Its exact Maven command and report identity are in `command-controls-traceability.json`; durable log `logs/slice3-frozen-outcome-pg-r12.log`, SHA-256 `95a709092538526bf914e2a0a9cbf0a384e194397499cec3d06fa5c032c7ba22`.

The Ordinary positive uses the real Maker → independent Ops endorsement → same Ops final approval → command path and a genuine trusted planner proof. Bundle promotion references are frozen at the initial INSERT, with the reciprocal FK deferred only to transaction commit. Orphan commit, missing Owner evidence, inactive promotion and later content mutation are rejected. Cause-bound fixture transformation now asserts its exact source template; the conservative MaxCPC headroom predicate remains enforced.

This result precedes the additive Finance review V0065 correction. That correction and final repository-wide/remote verification remain pending. The additional AC shard maps 20 new criteria and strengthens AC006, producing 80 unique criteria with the original shard; a source/test mapping is not independent acceptance or Controller approval.

## Per-finding same-class and transitive review

### S3-DR-009

- Command request/service/repository, V0043 lease/attempt/retry functions, V0045 creator, V0058 immutable seal and every referenced-authority invalidation trigger were read end to end.
- The scan found the same renewal risk in command preparation and compensation; both now derive immutable final-approval bounds. Selection/endorsement snapshot equality and exact selected baseline were added after actual human-chain failures exposed incompatible authority shapes.
- Policy, credential, scope, containment and Bundle restoration were reviewed as permanent invalidation cases. Old AdBidWriteGateAdversarialIT expected restoration to revive the command; its faithful eight-scenario replacement is applied and awaits current Java execution.

Transitive impact:

- AdBidCommandRequest is now recommendation/version/reservation only; ApprovalService seals in the final-approval transaction.
- Worker lease, transmission, status/readback and permitted retry consume the same historical authorization and expiry.
- Reconciliation expiry appends invalidation and escalates task responsibility without renewing approval or releasing uncertain reservations.

### S3-DR-010

- Every application-callable advertising command/reservation/containment/compensation/Bundle sink and its table/function grant was inspected, including a real pg_proc/has_function_privilege inventory.
- Caller actor/bundle/expiry and arbitrary GUC identity were removed from the public creator. The scan extended one-use physical backend/transaction binding to Manual/Bundle/Containment/Compensation and trusted Outcome Planner attestation.
- A remaining app EXECUTE grant on V0050 reopen_ad_lineage_after_regression was found in the current database. The obsolete caller-role route is now revoked in V0058; actual isolated SQL confirms denial and the Java suite now asserts SQLSTATE 42501. Current Java/full execution remains pending.

Transitive impact:

- Identity issuer is separately configured, default NOLOGIN/unconfigured and never provisioned with real credentials.
- All actor consumers recheck live credentials, authentication, step-up, roles and exact Store/affected Product authority.
- Baseline freezing now requires a trusted Planner payload attestation; shape-correct arbitrary app JSON cannot create approval authority.

### S3-DR-012

- Inspected command request/operation snapshots, AdBidCommandWorker apply/status/readback/restore branches, V0043 attempt lifecycle, V0058 status/retry predicates, Raw dispatch/parser and native bid parameter contracts.
- Native status polling had been routed through mutating APPLY; explicit observation-only task polling now preserves task identity. A transport timeout or HTTP acceptance cannot assert NOT_APPLIED or successful configuration.
- Native unit/currency identity was traced through readback, current configuration, MaxCPC/target headroom and exposure major-currency conversion; exact comparisons reject third values and unreadable/truncated Raw.

Transitive impact:

- Execution worker and Raw write port retain actual request/response custody and immutable operation snapshots.
- Retry requires independent current database proof and exact prior ownership; compensation uses distinct RESTORE identity and cannot overwrite a third-party value.
- Console readback now carries its own bid unit; downstream Outcome/release reads the actual observed value, currency and unit instead of trusting the match label.

### S3-DR-013

- Read generic reentry, compensation worker branches, reservation references, compensation API/controller, V0058 preview/endorse/approve/lease/gate and invalidation triggers.
- The scan found caller-selected compensation and generic replacement authority could bypass new quorum. General replacement stays disabled; only action-bound Stop/current regression plus exact captured-prior compensation is admitted.
- Credential status restoration was tested after a fully open fictional compensation gate: the old compensation remains permanently invalid.

Transitive impact:

- Compensation starts a new immutable Maker/Ops/Owner chain and exact Bundle/Gate while retaining the original held reservation.
- RESTORE and its readback use captured prior native bid and separate idempotency identity, never a default target or automatic rollback.
- No ordinary recommendation or settled success is inferred from compensation; task/Outcome history remains attached to the original action.

### S3-DR-014

- Read reservation admission/release, canonical affected membership, six-axis exposure SQL snapshot, every broader scope envelope, Manual start/release parity and correction-driven quarantine reacquisition.
- Same-object/digest-only conflict checking was replaced with canonical Product Variant intersection under organization serialization. Minor-unit delta is normalized to major currency for cumulative amounts; missing canonical evidence is not zero.
- Caller boolean release proof and early guard summaries were traced to canonical readback and latest OPERATIONAL/OPERATIONAL_REVISED company plus every frozen critical unit; source integration tests exercise actual release and late-correction reacquisition.

Transitive impact:

- Command and Manual execution share held exposure; pending recommendations cannot reserve.
- Preview and gates consume the same scope-level exposure snapshot with per-axis usage/state/limit/headroom, rather than separate arithmetic.
- Outcome observations remain durable when release is not yet allowed; legitimate release can be followed by quarantine reacquisition without stealing another holder.

### S3-DR-016

- Inspected all five containment kinds, entity/affected-set/version/Store/account scope resolution, explicit human Stop, automatic regression, reenablement, technical attestation and command/manual invalidation.
- Digest equality was insufficient for overlapping affected sets; canonical membership now controls intersection. Reenablement requires independent evidence and new exact authority, and cannot resurrect historical approvals.
- Real privilege inventory found the obsolete V0050 caller-role reopen route still executable; its applied exact revoke closes a duplicate ingress. The old gate fixture now uses explicit privilege and permanent-invalidation assertions; the SQL probe passed and Java verification remains pending.

Transitive impact:

- Pending approval/command/manual assets are invalidated while already transmitted facts retain observation-only reconciliation.
- Task regression handling and Finance settled-contradiction review preserve original lineage and task ages; a conclusive no-improvement contradiction does not fabricate regression.
- Store/account technical Stop remains separately scoped; business roles cannot manufacture security attestation.

### S3-DR-017

- Read every Bundle component reference and activation validator, Gate EV/E scope, ordinary promotion, Owner evidence, version immutability, all materiality hard axes and previous-Gate proof.
- The full human chain exposed circular immutable Bundle/promotion construction: only that FK is deferred to commit, the initial INSERT freezes its reference, and orphan/missing-Owner/inactive cases refuse.
- Root materiality assessment now evaluates each hard axis independently; sealed route must remain identical at creator/live gate. Ordinary true human approval and critical/regression/unknown refusal have actual PostgreSQL evidence.

Transitive impact:

- Bundle publication uses Maker proposal, independent Ops endorsement and distinct Owner activation; prior authority is retired atomically and permanently invalidated.
- Ordinary Ops final approval is allowed only through an exact accepted promotion with lower-risk canonical axes; any hard trigger retains Material Owner authority.
- Production enablement remains false. Engineering fictional Gate fixtures do not verify real OZON/WILDBERRIES Provider capability or authorize external transport.
