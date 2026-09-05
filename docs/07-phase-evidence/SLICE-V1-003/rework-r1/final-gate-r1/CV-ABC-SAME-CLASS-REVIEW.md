# CV-A / CV-B / CV-C same-class review

Status: implementation review and dirty-worktree diagnostic evidence, recorded
2026-09-06. This document is additive working evidence. It is not an independent
Controller verdict, new-Head PASS, full-regression result, or CI receipt.

The review starts from the preserved Controller checks against
`3ff042df66d5d6924b587cac96fc652b93bf5e7a` in
[FINAL-CLOSURE-VERIFICATION.md](controller-package/FINAL-CLOSURE-VERIFICATION.md).
The accepted authority remains the immutable
[Slice Contract](../../../../03-work-items/SLICE-V1-003-advertising-traffic-efficiency.md),
particularly §6.8 purpose-specific qualification, §6.15 exact cause-bound targets,
§6.23 frozen comparability, §6.25 cause-specific Protection outcomes and §6.26
operational versus settled profit. CV-A/B/C and this same-class review do not add
Findings or amend the accepted Contract or Frozen Finding Set.

## Implemented authority and source map

All paths below are relative to `backend/marketops-server/`. Java application
paths abbreviated `advertising/` mean
`src/main/java/com/mimococo/marketops/advertisingefficiency/internal/`.
Paths abbreviated `analytics/` mean
`src/main/java/com/mimococo/marketops/analyticsdecision/`.

| Boundary | Actual implementation files | Resulting behavior |
| --- | --- | --- |
| CV-B exact cause dependencies | `advertising/domain/AdActionDependencyPolicy.java`, `advertising/domain/AdCaseCalculation.java`, `advertising/application/AdvertisingCaseCalculationService.java`, `AdvertisingProposalService.java` | Economic cause-bound protection accepts complete canonical negative economics despite unavailable conversion. It does not infer loss from unknown profit. Physical causes retain their own one-sided dependencies. Unsupported cause codes produce `CAUSE_BOUND_CAUSE_UNSUPPORTED`. |
| Preview and controlled-write parity | `advertising/application/AdvertisingDecisionService.java`, `advertising/infrastructure/jdbc/AdvertisingDecisionRepository.java`, `src/main/resources/db/migration/V0066__qualify_economic_cause_bound_protection.sql` | Planner, Preview, seal, command admission and immediate write gate use matching cause-specific evidence requirements and retained blockers. |
| Case purpose qualification | `advertising/application/AdvertisingPurposeFreshness.java`, `advertising/infrastructure/jdbc/AdvertisingEvidenceRepository.java` | Each consumed segment must satisfy source and acceptance time independently. Aggregate minimum source/acceptance controls age; maximum timestamps and every linked-line timestamp prevent future/null members from hiding behind a valid minimum. Configuration acceptance is provenance ingestion time; affected-set acceptance is creation time. |
| Canonical historical cohort economics | `analytics/MetricQuery.java`, `analytics/internal/application/AnalyticsQueryService.java`, `analytics/internal/infrastructure/jdbc/MetricRepository.java`, `advertising/application/AdvertisingEvidenceGatherer.java` | A read overload selects canonical Metric values whose business period covers the linked cohort, filtering applicability before latest-version selection. Every consumed line for each listing must fit the selected period. An unrelated newer favorable window cannot replace applicable evidence. |
| CV-A frozen per-kind authority | `advertising/application/AdvertisingOutcomeFreshness.java`, `AdvertisingOutcomeEvidenceService.java`, `AdvertisingOutcomePlanningService.java`, `advertising/infrastructure/jdbc/AdvertisingPolicyRepository.java`, `src/main/resources/db/migration/V0067__validate_frozen_outcome_input_profiles.sql` | Baselines freeze exact stage/kind profile identities and authority digests. Observation verifies effective validity, scope, source and acceptance bounds, completeness, correction, coverage and incident constraints without selecting a newer more permissive profile. |
| CV-C original action and accepted window | `advertising/application/AdvertisingProtectionWindow.java`, `AdvertisingOutcomeAssessment.java`, `AdvertisingOutcomeEvidenceService.java`, `AdvertisingOutcomePlanningService.java`, `AdvertisingOutcomeService.java`, V0067 | Canonical original cause, semantic profile and lineage generation are frozen and checked against the current object and every relevant historical configuration. Physical risk clearance consumes complete cause-specific history through the accepted window. Exposure-stopped requires full source coverage independently of a permissive minimum-coverage profile. |
| Input-driven revisions and reopening | `advertising/infrastructure/jdbc/AdvertisingOutcomeRepository.java`, V0067, `src/main/resources/db/migration/V0069__reopen_invalidated_protection_outcomes.sql`, `src/main/java/com/mimococo/marketops/operationsworkflow/internal/application/AdvertisingOutcomeReviewService.java` | Changes to consumed authority, evidence or qualification boundaries schedule one relevant revision; unchanged qualified input remains idle. Invalidating evidence or observed recurrence uses existing quarantine/reservation and original-Task escalation authority while retaining stage and revision history. |

The unqualified filenames in a table row inherit the directory of the first file
in that row. These are implementation locations, not claims that every file was
independently changed by the author of this review.

## CV-B guards retained

`PROVEN_ADVERTISING_LOSS` requires all seven evidence kinds:
`OFFICIAL_AD_SPEND`, `AD_OBJECT_CONFIGURATION`, `AFFECTED_SET`,
`AD_LINKED_SALE_EVENT`, `COST_AND_FEE`, `SELLABILITY` and `AVAILABILITY`.
Only `AD_LINKED_CONVERSION_NOT_WRITE_GRADE` is exempted for that cause. Missing
quantity lineage, unresolved currency, incomplete costs, material attribution
gaps, incomplete affected scope, unknown physical state, failed sales protection,
Accepted Exceptions, scope revocation and missing independent human approvals
remain blocking conditions. The exact active Target Policy must explicitly
accept the cause and generate the finite lower candidate.

The new canonical Metric API is read-only and delegates to the existing Metric
repository. There is no additional Metric writer, private computed-profit table,
estimated conversion substitute or fallback to a different business cohort.
A mature historical cohort and an old still-effective cost version can qualify
when their actual applicability and fresh canonical proof satisfy the frozen
purpose; age of the business period alone is not a rejection rule.

`AdvertisingVerticalPathIT.linked()` now declares the cohort ending at the last
completed hour, matching the existing `FactWindow.alignedEndingAt` authority.
It asserts that the actual event lies inside that interval. The correction
aligns declared fixture evidence with the Metric Engine; it does not discard an
out-of-window event to make a result favorable.

## Same-class temporal, scope and correction boundaries

The completed bounded scan covered the transitive consumers of CV-A/B/C:

- An unbounded age limit disables expiry on that axis only. Missing or future
  source/acceptance timestamps remain inadmissible. A fresh last segment cannot
  refresh an older consumed segment; a fresh minimum cannot hide another future
  or missing timestamp. Outcome company coverage checks every consumed retained,
  return and QC component, and uses the existing canonical return-coverage enum.
- Known physical evidence must cover every affected product. A
  `CANONICAL_CONFIRMED` label does not convert unknown stock or sellability into
  a known state. Product-only affected scope derives listing history only from
  the frozen company units in the object's store; it requires every affected
  product and stable mapping through the window. Mapping conflicts and
  contradictory same-time affected sets are unresolved.
- Identical retrospective refreshes of a historical physical state can renew
  its proof without inventing a transition. Contradictory reports at the same
  effective instant are unresolved, never selected by UUID. A repair observed
  only after the accepted window cannot prove continuous clearance during it.
- The frozen original cause uses canonical `PROMOTED_VARIANT_NOT_SELLABLE` and
  `PROMOTED_VARIANT_UNAVAILABLE` names. Rebuilt object generation, changed
  semantic identity or a different generation in consumed configuration history
  cannot close the original action's danger.
- Complete, exact, fresh zero-spend evidence can establish exposure stopped
  while profit remains unknown. Partial zero-spend coverage cannot establish
  that terminal even if the frozen purpose profile permits coverage below 1.
  Neither Protection terminal asserts repaired inventory, preserved company
  sales or primary efficiency success.

V0069 distinguishes two forms of invalidation. A revision that removes proof
for the same accepted window may reopen that window's previous verified
Protection result. A later, longer stage with unknown attribution or missing
settlement inputs does not invalidate a lawful result for the earlier window.
Cross-stage reopening requires exact original affected scope and observed
continuing harm: fresh canonical positive spend after exposure-stopped,
qualified unsafe evidence for the original physical cause, or canonical negative
profit for the original economic cause. The existing financial/sales
`REGRESSED` mechanism remains separate.

The SQL selects the immediately preceding applicable result for the same action,
baseline, object, affected digest and window start, with a nonfuture evaluation
and no later window end. It selects that predecessor before checking whether it
was terminal; an older terminal cannot repeatedly reopen already-active
responsibility. Reopening preserves earlier receipts, uses the original Task,
and does not rewrite an unknown financial verdict into measured harm.

## Concrete positive and adverse test oracles

These tests live in
`src/test/java/com/mimococo/marketops/advertisingefficiency/internal/application/`.
Integration tests use the actual Spring application services and isolated
PostgreSQL, synthetic canonical source facts, and the fixture Provider path.
Unit tests are identified separately below.

| Test class | Positive method | Adverse or boundary method |
| --- | --- | --- |
| `AdvertisingEconomicCauseBoundIT` | `completeNegativeEconomicsWithUnavailableConversionTraversesTheActualGovernedPath` | `unresolvedEconomicsNeverBecomeAnEconomicDangerOrCandidate`; `anExactPolicyThatDoesNotAcceptThisCauseCannotGenerateTheStep` |
| `AdvertisingEconomicCauseBoundIT` | `completeFreshSegmentsRetainTheirExactOldestAcceptanceAndCanQualify` | `aNewerReportSegmentDoesNotRefreshTheOlderConsumedSource`; `oldSourceWithNewAcceptanceCannotProveEconomicDanger` |
| `AdvertisingEconomicCauseBoundIT` | `aNewerFavorableUnrelatedMetricWindowCannotReplaceTheApplicableHistoricalCohort` retains the applicable negative proof | `onlyAnUnrelatedFavorableCanonicalWindowLeavesHistoricalEconomicsUnresolved`; `oneListingCannotReuseOneCoveredLineToAuthorizeAnotherUncoveredCohort` |
| `AdvertisingEconomicCauseBoundIT` | `JavaAndSqlUseTheSameEvidenceKindsForEveryBasisAndCause` | Seven-kind `everyCauseDependencyIsRequiredAtTheRealPlannerAndPreview`; ten-blocker `economicDependencyExemptionRetainsEveryNonConversionBlockerInJavaAndSql`; `anUnsupportedCauseCannotHaveAClearJavaOrSqlPreviewDependencySet` |
| `AdvertisingEconomicCauseBoundIT` | Governed-path method above includes independent endorsement/approval, seal, admission and fixture apply/readback | `causeBoundProofDoesNotReplaceIndependentEndorsementOrOwnerApproval`; `anActualAcceptedExceptionKeepsTheEconomicCandidateInert`; `revokedMakerScopeCannotSelectTheEconomicCandidate`; three-kind `revokingEconomicOrPhysicalSafetyAfterApprovalPreventsTheActualFixtureTransmission` |
| `AdvertisingOutcomePurposeFreshnessIT` | `freshClosedExactZeroWindowProvesExposureStoppedWithUnresolvedProfit`; `companySplitAgesDoesNotApplyAcceptedAgeLimitToAnOtherwiseFreshSource` | `oldSourceWithNewAcceptanceCannotConfirmEvenClosedZeroSpend`; `outcomePermissiveCoverageCannotConvertPartialZeroSpendIntoExposureStopped`; incomplete/open-correction/profile-revocation/provider-incident methods |
| `AdvertisingOutcomePurposeFreshnessIT` | `physicalSellabilityCauseClearsAcrossWindowWhileProfitStaysUnknown`; `physicalAvailabilityCauseClearsIndependentlyOfUnresolvedProfit` | `physicalSellabilityRestoredOnlyAfterWindowCannotClaimContinuousRiskClearance`; `physicalAvailabilityContinuingHarmRemainsOpenDespitePositiveSpendReport`; old-source and unknown-state methods |
| `AdvertisingOutcomePurposeFreshnessIT` | `physicalSellabilityRecurrenceAtTheNextMatureStageReopensOnceAndPreservesTheEarlyWindow` verifies exactly one containment and original-Task escalation | `physicalSellabilityRebuiltCurrentObjectCannotCloseTheOriginalActionDanger`; `physicalSellabilityDifferentHistoricalConfigurationGenerationCannotCloseTheOriginalDanger`; `physicalSellabilityLateInvalidationReopensSameFrozenActionAndPreservesEarlierClaim` |
| `AdvertisingOutcomeInputIntegrityIT` | `physicalSellabilityIdenticalRetrospectiveRefreshKeepsHistoricalWindowUsable`; `retainedCompleteObservedReturnsUseTheExistingCanonicalCoverageEnum` | Conflicting same-instant reports, mapping conflict, same-time affected-set conflict, future second source, future consumed QC and missing unconsumed component methods |
| `AdvertisingOutcomeRevisionFreshnessIT` | `unchangedInputsAndClockAdvancementWithinQualificationDoNotAppendRevision`; `costMetricRefreshAloneRevisesTheConsumedCanonicalCostAndThenStaysIdle` | Exact profile revocation/scope mutation/expiry; future acceptance boundary; company, configuration, price and physical-only invalidation; `newerNonCoveringMetricCannotMaskTheConsumedCohortCostRefresh` |
| `AdvertisingFrozenOutcomeIT` | `matureRetainedAndFavorableActualSettlementPreserveBothStageHistories` | `matureUnallocatedFinancialFactCannotBecomeSettledEfficiencySuccess` preserves unknown settlement without false quarantine; `matureActualSettlementContradictionDoesNotOverwritePriorRetainedSuccess`; late actual sales regression |

The focused unit classes add independent boundary checks:
`AdvertisingPurposeFreshnessTest` covers source/acceptance independence, unbounded
axes and effective cost; `AdvertisingSegmentPurposeFreshnessTest` covers oldest
acceptance and per-member null/future source/acceptance; and
`AdvertisingPhysicalPurposeFreshnessTest` covers complete known physical scope
versus unknown or missing affected members. These unit cases supplement the
real application/PostgreSQL paths; they do not substitute for them.

## SQL privilege preservation and authority limits

V0066 preserves the existing public seal, create-command and evaluate-gate
signatures. Their predecessor implementations are renamed to
`seal_ad_action_authorization_before_economic_cause`,
`create_ad_bid_command_before_economic_cause` and
`evaluate_ad_bid_write_gate_before_economic_cause`. Every predecessor has
`REVOKE ALL ... FROM PUBLIC,marketops_app`; only the SECURITY DEFINER wrappers
can invoke them. New definer functions have pinned search paths, and public
execution is revoked. The app role receives only the intended public
entrypoints/read helpers. Existing privilege-boundary tests explicitly forbid
app-role execution of those three predecessors and enumerate intended helper
signatures in `src/test/java/com/mimococo/marketops/database/AdvertisingPrivilegeBoundaryIT.java`.

V0066's economic predicate consumes canonical purpose rows and their eligibility
and expiry; it does not recalculate accepted time from observation/resolution
time. Existing sealing freezes those rows, and the immediate gate validates the
same proof. V0067 validates baseline authority and identity; it does not confer
new human approval. V0069 reuses the existing containment/reservation authority,
including no replacement of a newer scope holder.

No accepted Contract or Frozen Finding Set was modified. No real Provider,
shared/production environment or credentials were used in this subtask.
`production_write_enabled=false` remains required. These changes confer no
Ready, merge, force-push, deployment or production-write permission.

## Diagnostic observations and remaining verification

The following are local dirty-worktree diagnostics. No row binds a final source
commit, tested merge, remote run/job/artifact or Controller acceptance. Counts
from different rows are not additive unique-test counts.

| Diagnostic | Observed result | Scope/limitation |
| --- | --- | --- |
| `/tmp/slice3-finalgate-focused-r1.log` | 117 unit nodes passed; all 34 `AdvertisingEconomicCauseBoundIT` nodes passed. Overall run failed in other Outcome diagnostics. | Earlier candidate state; cannot claim whole-run PASS or current-source CV-B verification. |
| `/tmp/slice3-finalgate-focused-r3.log` | 42 unit nodes passed; Revision 13 and InputIntegrity 8 passed. Total 52 IT nodes included 1 failure and 1 error. | Exposed cross-stage unknown-settlement overreach and a legal-null test read. Both were corrected before r4. |
| `/tmp/slice3-finalgate-focused-r4.log` | BUILD SUCCESS; 18 Outcome service unit nodes and 31 IT nodes (21 Purpose + 10 Frozen), zero failures/errors/skips; 33.354 seconds. | Confirms the two corrections and scoped current-source behavior only. Does not rerun all CV-B, revision, security or capacity tests. |

Diagnostic SHA-256 values:

```text
5851726703ec7cca13cac245606933b707b3d9a84a5be3137eaa82de14ddd305  /tmp/slice3-finalgate-focused-r3.log
58662f700a5fd513454f3a523ffa378120ffce9c03da7ea2b81f10b50c4cf173  /tmp/slice3-finalgate-focused-r4.log
```

At this review checkpoint backend source/tests are frozen for the sequential
mixed-capacity run. The remaining final gate must execute the complete relevant
regression and migration/privilege/coverage/governance checks against coherent
source, verify representative mixed capacity, and publish exact source-Head and
tested-merge CI evidence. This document does not preempt any of those results.
The preserved historical Controller verdict remains unchanged until an
independent Controller reviews the exact final Head.

### Additional confirmed boundary after the r4 checkpoint

A bounded follow-up of the same qualification-to-terminal conversion found
that legal EARLY/RETAINED Profiles may use lower minimum confidence, optional
window/correction requirements and no minimum coverage. V0037 permits this
configuration; it does not weaken §6.25's independent requirements for verified
Protection terminals. Consequently `grade.eligible` alone cannot promote
incomplete or correcting official spend to `CANONICAL_CONFIRMED`, a contradictory
physical window to cleared, or a conflicting set to exact scope. The same
conversion must retain actual configuration completeness, linked-line time and
product integrity, canonical cost completeness and price-window completeness.

At this addendum's checkpoint, `/tmp/slice3-terminal-completeness.patch` was
reviewed and applied to `AdvertisingOutcomeEvidenceService.java`,
`AdvertisingOutcomePurposeFreshnessIT.java` and V0069, with ten additional
actual-application PostgreSQL cases. `git diff --check` passes; execution is
recorded below. The applied refinement reports fresh but incomplete/correcting spend
as `INCOMPLETE`, with `OFFICIAL_AD_SPEND_NOT_CANONICAL_COMPLETE_CLOSED`, preserving
the distinction from stale evidence.
The new cases configure the lawful permissive Profiles before baseline freezing
and explicitly verify ordinary profile eligibility. They pair complete zero and
complete physical positive cases with incomplete/open-correction zero,
same-instant physical conflict, future physical/linked members, affected-set
conflict, unknown later-stage and invalidating same-window revisions.

The patch also records physical window completeness independently of its
profile grade and cleared flag. V0069's cross-stage recurrence predicate consumes
that completeness bit before treating an unsafe state as observed harm; an
unknown later window cannot invalidate an earlier lawful terminal. A directly
superseding revision of that same accepted window may still invalidate its
former proof. The later three-class diagnostic executed all ten new cases;
r4 does not verify them. Final source-bound verification remains outstanding.

### Later fixture diagnostics and historical Metric re-evaluation

`/tmp/slice3-terminal-two-failures-r2.log` recorded 18 Outcome service unit nodes
passing and 72 IT nodes: Purpose 31 and Economic Cause-Bound 34 all passed,
Vertical 7 had one failure, zero errors/skips. The overall run failed. Its
SHA-256 is
`14b83134486c9bb1f6015ccf9bc1d33b2c820026b48e8792a9ee1bce4501d825`.
This is another dirty diagnostic, not a new-Head or whole-run PASS.

The future-linked-member test's first observation is the mature cohort. Its
generic setup initially also inserted an independent overlapping early report;
that old consumed source correctly blocked the proposed positive precondition.
The corrected setup lets the existing mature helper supply that test's complete
company/ad report. The unchanged positive and future-member assertions both
passed. No freshness threshold or business event was relaxed.

Economic and Vertical tests share one PostgreSQL container across distinct
Spring contexts. Their in-memory object storage now shares that same lifetime.
Previously the second context found the first context's content-addressed Raw
record but had a different empty object store; custody verification correctly
refused dispatch. The shared backing preserves the real custody check and
unchanged response bytes. The subsequent Vertical run passed apply/readback and
reached mature Outcome evaluation.

The mature Vertical path additionally requires initial historical mapping and
physical/price evidence before baseline freezing, and current publications of
the same consumed official intervals and physical effective instants. The
fixture now appends those source restatements, preserving their values and
prior receipts. The current affected-set resolution and write freshness remain
unchanged. This does not justify replacing an old cohort's cost with a favorable
newer Metric business period.

`analyticsdecision/internal/application/AnalyticsCalculationService.java` now has a
`runForWindow` overload, with the existing `run` delegating to it. It uses the
same Metric Engine, value writer and diagnosis path. It rejects a future period
end, a duration that differs from the named Metric window, or non-hour-aligned
boundaries before writing; it adds no HTTP endpoint or permission. The existing
`AnalyticsCalculationServiceWindowTest` adds one historical-window case and
three invalid-boundary cases. Vertical invokes the same original historical
D30 interval at retained and settled re-evaluation times.

The coordinated diagnostic executed all five window unit nodes successfully.
A same-input Metric value is deduplicated by its original digest,
so the overload alone does not prove a fresh re-evaluation. Append-only
canonical evaluation proof and consumer integration use V0070 and the existing
writer; no old Metric value, digest or `computedAt` is rewritten. The complete
historical re-evaluation path must pass before final closure can be claimed.

The added `AnalyticsMetricReevaluationIT` uses the real Vertical fixture's
Spring Metric Engine, app-role writer and disposable PostgreSQL. Its thirteen test
nodes cover same-value/same-digest re-evaluation with immutable original rows,
an actual applicable cost correction, latest unavailable cost without fallback
to an older favorable value, failed and future evaluation/completion, rejected
period/store association mismatch, denied app UPDATE/DELETE, and denied
association after run completion. Additional boundaries require no verification
between first computation and successful completion, preserve a latest failed
value without granting proof or falling back to a favorable predecessor, and
bound an unqualified-time current read by actual PostgreSQL statement time so a
future successful re-evaluation cannot refresh the current proof.
Metric rows are produced by the existing
service; synthetic adverse lifecycle states exercise proof qualification without
introducing a second Metric writer. The coordinated run must verify these new
tests and the complete mature Vertical path together.

`/tmp/slice3-metric-proof-focused-r1.log` recorded 41 unit nodes passing and
106 IT nodes with two failures, zero errors/skips. Purpose 31, Economic 34,
Revision Freshness 13 and the then-existing Metric Re-evaluation 10 nodes
passed. The Flyway exact-table inventory lacked the new append-only table;
the Vertical path passed Retained IMPROVED and reached Settled, where its
pre-action official reporting covered only 30 of the required 60 days.
The overall run failed; its SHA-256 is
`1fee065b8308c1670c2c81a8c0cb1459a529e1b340373da9793ec7ff6cf6e6f3`.
The Vertical positive fixture now accepts an explicit complete, closed zero
report for the older 30 days before baseline selection/freezing. Original
recent-window spend, clicks and sales events are unchanged; no frozen baseline
or policy threshold is edited. The three new proof-boundary tests, inventory
correction and completed 60-day fixture await the next coordinated run. These
dirty diagnostics remain distinct from final source-bound or exact-Head CI
evidence.
