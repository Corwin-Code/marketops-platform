# Control fault-seeding receipt

This receipt separates the exact boundary each test reaches. It does not turn a privilege failure into live-gate coverage or a local suite result into a passing whole batch. The JSON records source hashes, methods, positive controls, exact injected variables and run receipts.

| Fault | Positive control | Boundary | Actual test | Expected result |
| --- | --- | --- | --- | --- |
| APP_PRIVILEGE | SEALED_CREATOR | EARLY_PRIVILEGE_REFUSAL | `AdvertisingSealedAuthorityIT#applicationCannotMintProofOrRestoreTheRemovedActorParameterCreator` | INSERT/UPDATE/DELETE false, private creator EXECUTE false, SET ROLE refused. |
| FORGED_GUC | SEALED_CREATOR | AUTHENTICATED_SEAL_BOUNDARY | `AdvertisingSealedAuthorityIT#arbitraryApplicationGucCannotImpersonateFinalApprover` | one-use transaction-bound authenticated invocation required |
| WRONG_SESSION | SEALED_CREATOR | AUTHENTICATED_SEAL_BOUNDARY | `AdvertisingSealedAuthorityIT#grantFromDifferentPhysicalApplicationSessionCannotBeReplayed` | Replay refuses; original application transaction still seals. |
| WRONG_ACTOR | SEALED_CREATOR | AUTHENTICATED_SEAL_BOUNDARY | `AdvertisingSealedAuthorityIT#makerProofCannotBecomeOwnersFinalApproval` | final approval identity refusal |
| ACTOR_REVOKED | SEALED_CREATOR | AUTHENTICATED_SEAL_BOUNDARY | `AdvertisingSealedAuthorityIT#currentActorRevocationInvalidatesAnAlreadyIssuedProof` | Transaction-bound invocation refuses current credential epoch. |
| PRODUCT_SCOPE_MISSING | SEALED_CREATOR | SCOPED_SEAL_BOUNDARY | `AdvertisingSealedAuthorityIT#storeOnlyApprovalGrantCannotAuthoriseUndisclosedAffectedProducts` | current exact Material/Ordinary scoped final approval required |
| BASELINE_SWAPPED | SEALED_CREATOR | SEALED_SELECTION_BOUNDARY | `AdvertisingSealedAuthorityIT#finalApprovalCannotSwapInAnUnapprovedBaseline` | final approval identity or sealed selection is invalid |
| ENDORSED_POLICY_DRIFT | SEALED_CREATOR | SEALED_AUTHORITY_SNAPSHOT_BOUNDARY | `AdvertisingSealedAuthorityIT#changedPolicyAfterEndorsementCannotBeSilentlySealed` | selection/endorsement authority changed |
| CUMULATIVE_MAJOR_CURRENCY | SEALED_CREATOR | ACTUAL_EXPOSURE_GATE_AXIS | `AdvertisingSealedAuthorityIT#cumulativeExposureIsMajorCurrencyAndUsesAllBroaderPolicies` | CUMULATIVE_BID_CHANGE and AGGREGATE_ENVELOPE_BLOCKED |
| CAUSE_STEP_RATIO | CAUSE_BOUND_CREATOR | CONTROLLED_CREATOR_PREDICATE | `AdvertisingSealedAuthorityIT#causeBoundStepCannotExceedExactOwnerPolicyRatio` | exact target and Provider policy refusal |
| DANGER_EVIDENCE_MISSING | CAUSE_BOUND_CREATOR | EVIDENCE_SEAL_BOUNDARY | `AdvertisingSealedAuthorityIT#causeBoundProtectionCannotTreatMissingDangerAsFreshEvidence` | all evidence purpose expiry bounds required |
| CRITICAL_UNKNOWN | CAUSE_BOUND_CREATOR | EVIDENCE_SEAL_BOUNDARY | `AdvertisingSealedAuthorityIT#causeBoundFinancialExceptionNeverIgnoresUnknownCriticalSafety` | action-specific evidence blockers remain unresolved |
| UNAPPROVED_RESERVATION | SEALED_CREATOR | CANONICAL_RESERVATION_ADMISSION | `AdvertisingReservationIT#pendingRecommendationCannotReserve` | exact intervention refusal |
| CALLER_RELEASE_BOOLEAN | SEALED_CREATOR | EARLY_PRIVILEGE_REFUSAL | `AdvertisingReservationIT#applicationCannotWriteReservationOrAssertReleaseConditions` | All DML and caller-boolean function denied. |
| OVERLAPPING_SCOPE | SEALED_CREATOR | ACTUAL_OVERLAP_READ | `AdvertisingReservationIT#overlappingVariantsNameTheExistingHolderEvenForAnotherObjectKey` | Returns the already held reservation. |
| EARLY_SAFETY_ABSENT | SEALED_CREATOR | CANONICAL_RELEASE_GATE | `AdvertisingReservationIT#missingConfigurationAndEarlySafetyEvidenceKeepReservationHeld` | Release false; ACTIVE and early_observation_complete=false. |
| STOP_SCOPE | SEALED_CREATOR | AUTHENTICATED_CONTAINMENT_AND_READ_GATE | `AdvertisingReservationIT#authenticatedOperationsStopCoversTheStoreCapability` | KILL_SWITCH_ACTIVE returned. |
| DIFFERENT_DIGEST_OVERLAP | SEALED_CREATOR | ACTUAL_CONTAINMENT_INTERSECTION | `AdvertisingReservationIT#affectedSetStopIntersectsVariantsAcrossDifferentDigests` | EMERGENCY_ENTITY_HOLD still returned. |
| STOP_SELF_ENDORSE | SEALED_CREATOR | AUTHENTICATED_RECOVERY_BOUNDARY | `AdvertisingReservationIT#stopperCannotEndorseTheirOwnReenablement` | independent scoped evidence attestation required |
| BUSINESS_AS_TECH | SEALED_CREATOR | AUTHENTICATED_RECOVERY_BOUNDARY | `AdvertisingReservationIT#businessRoleCannotFabricateTechnicalSecurityAttestation` | independent scoped evidence attestation required |
| STOP_OLD_APPROVAL | SEALED_CREATOR | ACTUAL_LIVE_GATE_WITH_PERMANENT_INVALIDATION | `AdvertisingReservationIT#containmentPermanentlyInvalidatesPriorApprovalAssets` | AUTHORITY_PERMANENTLY_INVALIDATED plus KILL_SWITCH_ACTIVE; durable invalidation row. |
| BUNDLE_GATE_DRIFT | SEALED_CREATOR | AUTHENTICATED_BUNDLE_ACTIVATION | `AdvertisingReservationIT#bundleGateChangedAfterEndorsementCannotBecomeApprovedAuthority` | Activation refuses changed endorsed authority. |
| COMP_MAKER_ENDORSE | COMPENSATION_OPEN_GATE | AUTHENTICATED_COMPENSATION_QUORUM | `AdvertisingReservationIT#exactCompensationUsesNewHumanChainAndCanOpenOnlyCapturedPriorBidRestore` | distinct scoped Operations Lead refusal |
| COMP_ENDORSER_APPROVE | COMPENSATION_OPEN_GATE | AUTHENTICATED_COMPENSATION_QUORUM | `AdvertisingReservationIT#exactCompensationUsesNewHumanChainAndCanOpenOnlyCapturedPriorBidRestore` | new distinct scoped Owner approval required |
| COMP_CREDENTIAL_RESTORED | COMPENSATION_OPEN_GATE | ACTUAL_PREVIOUSLY_OPEN_COMPENSATION_GATE | `AdvertisingReservationIT#exactCompensationUsesNewHumanChainAndCanOpenOnlyCapturedPriorBidRestore` | EXACT_COMPENSATION_APPROVAL_ABSENT_OR_STALE persists. |
| LEASE_EXPIRED | SEALED_CREATOR | ACTUAL_NAMED_TRANSMISSION_GATE_REASON | `AdvertisingTransmissionBoundaryIT#anExpiredApprovalIsNamedAsItsOwnRefusal` | APPROVAL_LEASE_EXPIRED added by actual gate. |
| MIDFLIGHT_STOP | SEALED_CREATOR | ACTUAL_ATTEMPT_GATE | `AdvertisingTransmissionBoundaryIT#aKillSwitchThrownMidFlightClosesTheGate` | KILL_SWITCH_ACTIVE added; opening APPLY fails closed at transmission. |
| UNKNOWN_REPEAT | SEALED_CREATOR | ACTUAL_ATTEMPT_LIFECYCLE_BOUNDARY | `AdvertisingTransmissionBoundaryIT#anUnknownResultIsNeverRepeated` | Unknown-state transition and duplicate-mutation checks refuse. |
| STALE_FENCE | SEALED_CREATOR | ACTUAL_ATTEMPT_FENCE_BOUNDARY | `AdvertisingTransmissionBoundaryIT#aStaleFenceOpensNothing` | Lease that authorised attempt is not current. |
| WORKER_pendingNativeStatusPollPreservesTaskAndNeverAppliesAgain | scripted worker precondition | UNIT_SCRIPTED_PORT_BOUNDARY | `AdBidCommandWorkerTest#pendingNativeStatusPollPreservesTaskAndNeverAppliesAgain` | Poll STATUS only, preserve task identity, never dispatch APPLY. |
| WORKER_sameCommandRetryNeedsIndependentDatabaseProof | scripted worker precondition | UNIT_SCRIPTED_PORT_BOUNDARY | `AdBidCommandWorkerTest#sameCommandRetryNeedsIndependentDatabaseProof` | No APPLY retry. |
| WORKER_compensationCannotOverwriteThirdPartyCurrentValue | scripted worker precondition | UNIT_SCRIPTED_PORT_BOUNDARY | `AdBidCommandWorkerTest#compensationCannotOverwriteThirdPartyCurrentValue` | No RESTORE, investigate mismatch. |
| WORKER_unreadableObservationStaysUnknown | scripted worker precondition | UNIT_SCRIPTED_PORT_BOUNDARY | `AdBidCommandWorkerTest#unreadableObservationStaysUnknown` | No successful readback or invented numeric value. |
| MATERIALITY_ABSOLUTE_BID_CHANGE | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_RELATIVE_BID_CHANGE | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_OFFICIAL_SPEND_EXPOSURE | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_AFFECTED_VARIANT_EXPOSURE | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_CUMULATIVE_BID_CHANGE | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_LIFECYCLE_OR_GOVERNED_COHORT_UNRESOLVED | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_FIXED_UNKNOWN_DECISION_EVIDENCE | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_FIXED_REGRESSION_OR_UNKNOWN_EXECUTION | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_FIXED_CRITICAL_PROTECTED_SALES_EXPOSURE | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| MATERIALITY_EXACT_ORDINARY_PROMOTION_ABSENT | ORDINARY_ASSESSMENT | ACTUAL_APP_SQL_MATERIALITY_ASSESSMENT | `AdvertisingMaterialityIT#aSingleHardAxisCannotBeCompensatedByTheRemainingOrdinaryEvidence` | MATERIAL_IMPACT with the exact named axis reason. |
| ORDINARY_immutableBundlePromotionReferenceMustResolveAtTransactionCommit | ORDINARY_HUMAN_CHAIN | DEFERRED_SCHEMA_CONSTRAINT | `AdvertisingOrdinaryApprovalIT#immutableBundlePromotionReferenceMustResolveAtTransactionCommit` | Deferred FK refuses orphan transaction. |
| ORDINARY_inactivePromotionCannotActivateOrDelegateAnOrdinaryBundle | ORDINARY_HUMAN_CHAIN | ACTUAL_BUNDLE_AND_MATERIALITY_GATE | `AdvertisingOrdinaryApprovalIT#inactivePromotionCannotActivateOrDelegateAnOrdinaryBundle` | No activation or Ordinary delegation. |
| ORDINARY_promotionWithoutOwnerEvidenceCannotBeStored | ORDINARY_HUMAN_CHAIN | EARLY_SCHEMA_CONSTRAINT | `AdvertisingOrdinaryApprovalIT#promotionWithoutOwnerEvidenceCannotBeStored` | ad_ordinary_promotion_evidence_present row CHECK refuses. |
| ORDINARY_regressionAfterOrdinaryEndorsementCannotUseOpsFinalApproval | ORDINARY_HUMAN_CHAIN | ACTUAL_HUMAN_FINAL_APPROVAL_BOUNDARY | `AdvertisingOrdinaryApprovalIT#regressionAfterOrdinaryEndorsementCannotUseOpsFinalApproval` | Ops final approval refuses Material route. |
| ORDINARY_unknownEvidenceAfterOrdinaryEndorsementCannotUseOpsFinalApproval | ORDINARY_HUMAN_CHAIN | ACTUAL_HUMAN_FINAL_APPROVAL_BOUNDARY | `AdvertisingOrdinaryApprovalIT#unknownEvidenceAfterOrdinaryEndorsementCannotUseOpsFinalApproval` | Ops final approval refuses Material route. |
| FINANCE_PRIMARY_REBIND | REAL_FINANCIAL_RECONCILIATION | EARLY_SCHEMA_CONSTRAINT | `AdvertisingFinanceReviewIT#applicationCannotRebindThePrimaryAdvertisingTaskAsItsFinanceReview` | ad_review_task_distinct_ck refuses. |
| FINANCE_UNKNOWN | REAL_FINANCIAL_RECONCILIATION | ACTUAL_CANONICAL_REVIEW_CONTEXT | `AdvertisingFinanceReviewIT#earlySafetyAndUnknownSettledEvidenceCannotForgeFinanceResponsibility` | No conclusive Finance review responsibility can be minted. |

The actual compensation test reaches an empty compensation gate before revocation and opens only RESTORE for the captured prior bid. The ordinary sealed-creator control intentionally retains disabled transport reasons. Source-r12 is 248/248 PASS. Source-r15 Finance6 and Frozen10 pass inside a 288-test batch with 286 passed, one failure and one error; that batch remains PARTIAL_FAILURE. Historical `controls-88` worker/transmission evidence precedes current baseline revisions and must be rerun.

The old gate and privilege suites plus exact obsolete-ingress revoke passed Gate8/Privilege10 in W1 r18; Transmission7 also passed. The whole r18 batch is PARTIAL_FAILURE due to one Vertical error. Full current Head verification and exact remote CI evidence remain pending. Production enablement remains false; no real Provider call or Controller verdict is claimed.

## W1 r18 control verification

Exact W1 `60638b1fc1a227b50f4b3ede1ba0bb983407bfdc` passed Gate **8/8**, Privilege **10/10** and Transmission **7/7** in source-r18. Its whole batch remains **27 tests / 26 passed / 0 failures / 1 error / 0 skipped**: the Vertical positive journey lacked a proven-loss Case. The original failure log is preserved with SHA-256 `7daf4fff0f738e727f74dbdc81044351032e13db68b6c63ca601d224c0501b6c`. `command-controls-r18-receipt.json` contains the exact command, time, execution scope and limitations. This result does not replace full clean verification or final remote CI.

## W1 named boundary additions

The JSON now records 62 fault rows and 7 explicit positive controls. W1 rows share a real issuer/seal/idempotent-creator graph, while global/capability and execution transport gates remain closed. Each named live-gate fault is distinguished from app privilege, row shape and separate reservation/creator refusal.

| Fault | Boundary | Actual test | Expected result |
| --- | --- | --- | --- |
| W1_MISSING_COMMAND | ACTUAL_LIVE_GATE | `AdBidWriteGateAdversarialIT#anAbsentCommandIsRefusedByName` | Exactly COMMAND_NOT_FOUND. |
| W1_AUTHENTICATED_KILL | ACTUAL_LIVE_GATE | `AdBidWriteGateAdversarialIT#aKillSwitchAddsItsReasonAndPermanentlyInvalidatesAuthority` | KILL_SWITCH_ACTIVE and AUTHORITY_PERMANENTLY_INVALIDATED; no quarantine conflation. |
| W1_AUTHENTICATED_HOLD | ACTUAL_LIVE_GATE | `AdBidWriteGateAdversarialIT#aQuarantineIsNotAKillSwitch` | QUARANTINE_ACTIVE and AUTHORITY_PERMANENTLY_INVALIDATED; no Kill conflation. |
| W1_RESTORATION_PRIVILEGE | EARLY_DATABASE_PRIVILEGE | `AdBidWriteGateAdversarialIT#aKillSwitchAddsItsReasonAndPermanentlyInvalidatesAuthority` | Root SQLException SQLSTATE 42501 permission denied. |
| W1_RESTORED_AUTHORITY_REUSE | ACTUAL_GATE_AND_DISTINCT_RESERVATION_CREATOR_BOUNDARIES | `AdBidWriteGateAdversarialIT#aKillSwitchAddsItsReasonAndPermanentlyInvalidatesAuthority` | Named Stop axis disappears but invalidation remains; reservation MO097 and directly invoked creator MO092 refuse; command count remains one. |
| W1_ENVELOPE_RESTORATION | ACTUAL_LIVE_GATE_AND_CANONICAL_ADMISSION | `AdBidWriteGateAdversarialIT#aResolvedEnvelopeCannotRevivePriorAuthority` | AGGREGATE_ENVELOPE_UNRESOLVED appears then disappears, while permanent invalidation and reservation/creator refusals remain. |
| W1_RELEASE_APP_ASSERTION | EARLY_PRIVILEGE_AND_CANONICAL_RELEASE | `AdBidWriteGateAdversarialIT#aLiveReservationCannotBeReleasedEarly` | SQLSTATE 42501; canonical release returns false. |
| W1_RELEASE_INVALID_SHAPE | EARLY_SCHEMA_CONSTRAINT | `AdBidWriteGateAdversarialIT#aLiveReservationCannotBeReleasedEarly` | SQLSTATE 23514 ad_action_reservation_release_conditions_ck. |
| W1_RELEASE_STALE_WORK | ACTUAL_LIVE_GATE | `AdBidWriteGateAdversarialIT#aLiveReservationCannotBeReleasedEarly` | Live gate adds RESERVATION_CONFLICT. |
| W1_PRIVATE_PROOF_READ | DATABASE_PRIVILEGE_INVENTORY | `AdvertisingPrivilegeBoundaryIT#lineageTablesRefuseEveryWrite` | Private proof ledgers have no SELECT; every listed immutable/control table denies app INSERT/UPDATE/DELETE. |
| W1_OBSOLETE_INGRESS | EARLY_DATABASE_PRIVILEGE | `AdvertisingPrivilegeBoundaryIT#obsoleteCallerAssertionRoutesRefuseAtThePrivilegeBoundary` | Both root SQLSTATE 42501, preserving canonical ingress only. |
| W1_DIRECT_COMMAND_INSERT | EARLY_DATABASE_PRIVILEGE | `AdvertisingPrivilegeBoundaryIT#applicationRoleCannotInsertACommand` | Direct JDBC refusal message contains permission denied; this method does not independently assert SQLSTATE. |
