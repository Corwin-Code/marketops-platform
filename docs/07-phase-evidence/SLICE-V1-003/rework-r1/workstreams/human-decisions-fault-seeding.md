# Human decisions: systematic fault seeding

These are named fault assertions on current implementation paths, not a PIT
mutation score. A schema rejection and a business gate rejection are different
results. Final execution status must come from the full measured-source receipt.
The intermediate r15 run executed HumanWorkflow 11, Materiality 13, finite-set 5,
guardrail 16 and staffed-clock 6 successfully; its containing run still failed
on two separate migration/browser-history fixture assertions.

| Boundary and positive control | Injected fault | Required result and actual assertion boundary |
| --- | --- | --- |
| `AdvertisingHumanWorkflowIT.responsibilityIsIndependentOfCandidateAndPageViewIsNotAcknowledgement`: one existing cause Task can be read and acknowledged | Read a page, submit unproven repair, assign an ineligible actor, close unresolved risk | Page view records no acknowledgement/action. Task service rejects unproven action, invalid assignment and risk-hiding closure. No SQL constraint is substituted for this service test. |
| `independentCauseKeepsOneConcurrentTaskAndThreeFiniteChoicesWithoutFutureSloLeakage`: one exact case/cause | Four concurrent responsibility calls; different cause on same object; query before binding | All concurrent calls return one Task distinct from the other cause. Earlier SLO query remains absent. Actual PostgreSQL row locking and unique authority are exercised. |
| `unresolvedResponseProfileEscalatesOnceOnTheSameTaskWithoutClaimingTimeliness` | Missing Owner response profile; replay reconciliation | Current state remains unresolved with null deadlines; one escalation remains on the same Task; zero commands. No 15-minute fallback. |
| `anOwnerProfileBecomingAvailableRepairsTheSameTaskWithoutRewritingPastUnknowns` | Bind a profile after first raise, then query the earlier instant | Current deadlines become available; original age and prior unresolved evidence remain unchanged. |
| `ownerAcceptedRiskPausesOnlyActionAndMustEndBeforeNewPreviewOrIntent` | Request preview, selection or Task reopen while accepted risk is active | Actual services reject competing intent; only Action time pauses, acknowledgement deadline is preserved. Ending risk reopens the same Task and cancels earlier recommendation authority. |
| `restoredRoleCannotResurrectAnExceptionAndSameTaskReopens`, `restoredCaseAuthorityCannotRestoreAcceptedRiskAndViewReadsTheReopenedTask` | Revoke then restore IAM or Case authority | Permanent invalidation survives restoration; current read shows reopened Task and action required. The tests do not treat restored configuration as renewed approval. |
| `reconciliationRecordsExpiryOnceWithoutRewritingApprovalOrReleasingExposure` | Expired minimum authority, repeated maintenance | One expiry is appended; sealed approval JSON is unchanged and exposure remains held. |
| `aSchedulerClockAheadCannotExpireCurrentApprovalOrRecommendation` | Maintenance caller is one day ahead of database time | Current approval remains APPROVED, zero expiry/invalidation. Production maintenance clamps observation time; the database future-time refusal remains intact. |
| `AdvertisingMaterialityIT.completeOrdinaryAssessmentExposesEveryIndependentAxisButGrantsNoExecution` | Each of ten independent faults listed below | SQL assessment changes from the complete Ordinary control to `MATERIAL_IMPACT` with the exact reason. The parameterized tests reach `ops.ad_materiality_assessment`, rather than relying on a failed fixture insert. No approval/command is granted by an assessment. |
| `expiredMaterialityAuthorityIsUnresolvedRatherThanAPromotedApproval` | Retire materiality policy | SQL assessment reports `MATERIALITY_UNRESOLVED`, never an Ordinary fallback. |
| `missingSpendKeepsItsUnknownValueAndCannotRetainAnOrdinaryRoute` | Set official Spend to explicit NOT_AVAILABLE/null | Assessment retains null value and named `OFFICIAL_SPEND_UNKNOWN` Material reason. |
| `BidCandidateSetTest.finiteOrderedSetIsDeterministicAndAZeroPolicyGeneratesNothing` | Policy count zero; repeat identical complete input | Zero count produces no candidates; repeated complete input gives the same ordered finite set. This is a domain boundary test, not a database/Provider test. |
| `intermediateProtectionRequiresExplicitPolicyPermission`, `providerRoundingCannotCrossTheAuthorizedAbsoluteOrRelativeFloor` | Disable intermediate permission; normalize a 99 target to a 90 grid under maximum change 1 | Only the conservative terminal target survives without permission; a rounded target outside authority produces no candidate. |
| `minorUnitBidComparesAgainstConvertedEconomicsAndAbsoluteBounds`, `unknownDenominationNeverProducesAUsableGrid` | Use minor-unit bid with major-unit economics/absolute bound; unknown denomination | A 0.50 RUB bound becomes exactly 50 minor units once; unknown units cannot form a usable grid. |
| `AdBidGuardrailTest.theConservativePolicyCeilingCannotBeReplacedWithTheRawEconomicCeiling`, `absentHeadroomAuthorityCannotSilentlyUseZero` | Bid below raw Max CPC but above headroom-adjusted ceiling; missing headroom | Actual guardrail rejects the target and unavailable authority. SQL creator's independent equivalent is covered in command controls. |
| `AdvertisingProposalServiceTest.missingProfitKeepsItsUncertaintyButDoesNotSilenceAQualifiedOneSidedDecrease` | Cause-bound sellability Protection with unavailable irrelevant profit components | Exact allowed cause can propose an exposure-only decrease while preserving the visible financial uncertainty; this uses mocked ports around the production proposal service. |
| `unknownCriticalSafetyCannotBeExcusedByTheCauseBoundBasis`, `causeBoundRequiresExactlyOneCurrentPurposeProofForEveryDependency` | Unknown critical safety; missing, expired or duplicated sellability proof | Proposal service emits no recommendation. The permitted one-sided exception never suppresses required current safety/identity evidence. |

The ten single-factor Materiality injections are absolute Bid change, relative
Bid change, official Spend exposure, affected Variant count, cumulative Bid
change, missing lifecycle/cohort, unknown decision evidence, active regression,
fixed critical sales and absent exact Ordinary promotion. Every other axis keeps
the positive fixture value, so another axis cannot compensate for the fault.

Clock domain tests additionally cover Friday-to-Monday continuation, a partial
last staffed minute, the next response boundary, the repeated DST hour and
overnight coverage owned by its opening day. They assert explicit instants and
durations; elapsed test runtime is not calendar evidence.

The complete Maker/Operations/Owner positive chain runs through the production
planner, services and creator in `realMakerOpsOwnerChainFreezesOneBaselineAndCreatesOneCommand`.
Forgery, private-issuer, seal, retry, permanent containment and compensation
injections are recorded separately in the command-control fault matrix. Browser
role journeys are separate actual HTTP evidence. No entry here is permission to
contact a real Provider or enable production writes.
