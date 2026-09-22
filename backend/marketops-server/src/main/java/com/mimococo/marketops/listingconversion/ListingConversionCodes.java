package com.mimococo.marketops.listingconversion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every fixed statement, state and reason code this Slice's backend can return.
 *
 * <p>The bilingual console catalogue is checked against this list: a code the
 * backend can produce that the catalogue cannot present in both Chinese and
 * Russian is a test failure, not a blank cell.
 */
public final class ListingConversionCodes {

    private ListingConversionCodes() {
    }

    public static Map<String, List<String>> all() {
        Map<String, List<String>> codes = new LinkedHashMap<>();
        codes.put("actionState", names(ListingActionState.values()));
        codes.put("candidateKind", names(CandidateKind.values()));
        codes.put("executionPath", names(ExecutionPath.values()));
        codes.put("materialityRoute", names(MaterialityRoute.values()));
        codes.put("ratioState", names(RatioState.values()));
        codes.put("nodeVerdict", names(NodeVerdict.values()));
        codes.put("protectionVerdict", names(ProtectionVerdict.values()));
        codes.put("containmentCauseClass", names(ContainmentCauseClass.values()));
        codes.put("releaseBasis", names(ReleaseBasis.values()));
        codes.put("recalculationClass", names(RecalculationClass.values()));
        codes.put("evidencePath", names(EvidencePath.values()));
        codes.put("healthState", List.of("PASS", "FAIL", "UNKNOWN"));
        codes.put("eligibility", List.of("ELIGIBLE", "INELIGIBLE", "UNKNOWN"));
        codes.put("healthCondition", List.of("AFFECTED_SET_COMPLETE", "MAPPING_RESOLVED", "DESCRIPTION_OBSERVED",
                "NOT_CONTAINED", "CALIBRATION_RESOLVED"));
        codes.put("opportunity", List.of("DESCRIPTION_NOT_RUSSIAN", "KIZ_MARKING_UNDECLARED",
                "SOURCE_STRATIFICATION_MISSING", "NOT_SELLABLE_AT_LAST_OBSERVATION", "FEEDBACK_THEMES_PRESENT"));
        codes.put("qualificationReason", List.of("VISIT_FACTS_ABSENT", "PURCHASE_LINKS_ABSENT",
                "SOURCE_STRATIFICATION_MISSING", "EQUIVALENCE_PROFILE_ABSENT", "EQUIVALENCE_NOT_PROVEN",
                "EQUIVALENCE_NUMERATOR_UNCOVERED", "EQUIVALENCE_DENOMINATOR_UNCOVERED",
                "EQUIVALENCE_TIME_ATTRIBUTION_UNCOVERED", "EQUIVALENCE_MATURITY_UNCOVERED",
                "EQUIVALENCE_REVISION_UNCOVERED"));
        codes.put("bindingGap", List.of("ACTION_NOT_FOUND", "BINDING_MISSING", "BINDING_INAPPLICABLE", "BINDING_EXPIRED",
                "AFFECTED_SET_DIGEST_CHANGED", "CURRENT_TEXT_MOVED", "CALIBRATION_NOT_CURRENT",
                "AUTHORIZATION_INVALID_OR_EXPIRED", "RECOMMENDATION_STALE"));
        codes.put("decisionBlocker", List.of("CALIBRATION_UNRESOLVED", "CALIBRATION_CONFLICTED", "AFFECTED_SET_INCOMPLETE",
                "AFFECTED_SET_DIGEST_CHANGED", "CURRENT_TEXT_MOVED", "LISTING_HEALTH_NECESSARY_FAILED",
                "LISTING_HEALTH_UNKNOWN", "SCOPE_CONTAINED", "TEXT_LENGTH_OUT_OF_BOUNDS", "KIZ_MARKED_UNDECLARED",
                "MATERIALITY_UNRESOLVED", "REVIEW_MISSING", "ENTITY_VERSION_CHANGED"));
        codes.put("gateReason", List.of("COMMAND_NOT_FOUND", "CAPABILITY_NOT_VERIFIED", "CAPABILITY_NOT_AVAILABLE_FOR_STORE",
                "CAPABILITY_SWITCH_DISABLED", "GLOBAL_SWITCH_DISABLED", "SCOPED_SWITCH_DISABLED", "ENTITY_NOT_ALLOWLISTED",
                "PRODUCTION_WRITE_DISABLED", "AUTHORIZATION_INVALID_OR_EXPIRED", "APPROVAL_LEASE_EXPIRED",
                "ACTION_NOT_LAUNCHED", "ALLOWANCE_NOT_OCCUPIED", "SCOPE_CONTAINED", "EXECUTION_PASS_MISSING",
                "NON_TARGET_FIELD_RISK", "KIZ_MARKED_UNDECLARED", "TEXT_LENGTH_OUT_OF_BOUNDS", "PRIOR_TEXT_MOVED"));
        codes.put("allowanceShortfall", List.of("ALLOWANCE_UNRESOLVED", "REQUEST_UNSTATED"));
        codes.put("allowanceAxis", List.of("CONCURRENT_LISTINGS", "AFFECTED_VARIANTS", "REVENUE_EXPOSURE", "CATEGORY_SHARE"));
        codes.put("commandState", List.of("PENDING", "LEASED", "EXECUTING", "PLATFORM_PENDING", "READBACK_PENDING",
                "READBACK_MATCHED", "RETRY_WAIT", "UNKNOWN_REQUIRES_READBACK", "READBACK_MISMATCH",
                "LATER_CHANGE_OR_MISMATCH_INVESTIGATION", "MANUAL_RESOLUTION", "FAILED_FINAL",
                "TERMINATED_WITHOUT_PROVIDER_CALL", "COMPENSATION_PENDING", "COMPENSATED", "COMPENSATION_FAILED"));
        codes.put("readbackMatch", List.of("MATCHES_TARGET", "MATCHES_PRIOR", "DIFFERENT", "UNREADABLE"));
        codes.put("attemptOutcome", List.of("IN_FLIGHT", "ACCEPTED", "REJECTED", "RETRIABLE_ERROR", "TIMEOUT", "UNKNOWN_STATE"));
        codes.put("packetState", List.of("ISSUED", "REPORTED", "VERIFIED", "EXPIRED", "WITHDRAWN"));
        codes.put("reportState", List.of("APPLIED", "NOT_APPLIED", "PARTIAL"));
        codes.put("managementMatch", List.of("MATCHED_TARGET", "MATCHED_PRIOR", "DIFFERENT", "UNKNOWN"));
        codes.put("displayState", List.of("DISPLAYED", "NOT_DISPLAYED", "UNKNOWN"));
        codes.put("verificationBasis", List.of("INDEPENDENT_HUMAN", "OFFICIAL_EVIDENCE"));
        codes.put("engagementState", List.of("ACTIVE", "EXITING", "STOPPED", "CLEARED"));
        codes.put("engagementKind", List.of("OFFICIAL_PROMOTION_PARTICIPATION", "SELLER_DIRECT_DISCOUNT"));
        codes.put("exitReason", List.of("MARGIN_BELOW_BOUND", "RETURN_RATE_ABOVE_BOUND", "SUPPLY_COVERAGE_LOST",
                "PLATFORM_TERMS_CHANGED", "OWNER_DECISION"));
        codes.put("occupationState", List.of("ACQUIRED", "ACTUAL", "UNKNOWN", "RELEASED"));
        codes.put("containmentScope", List.of("LISTING", "STORE", "PLATFORM", "ORGANIZATION", "BATCH"));
        codes.put("containmentState", List.of("ACTIVE", "REENABLED"));
        codes.put("attestationKind", List.of("REPAIR_ATTESTATION", "BUSINESS_CONSENT"));
        codes.put("associationKind", List.of("LAWFUL_LATE_REPORT", "UNAUTHORISED_DEVIATION", "UNRESOLVED_CHANGE"));
        codes.put("associationState", List.of("OPEN", "LINKED", "UNDER_VERIFICATION", "CLOSED"));
        codes.put("queueState", List.of("QUEUED", "RUNNING", "FINISHED", "FAILED"));
        codes.put("evaluationStage", List.of("OPERATIONAL", "SETTLED"));
        codes.put("protection", List.of("DIRECT_CONTRIBUTION_PROFIT", "LINKED_SCOPE_PROFIT", "OVERALL_RETURN_RATE",
                "CRITICAL_VARIANT_RETURN", "SUPPLY_COVERAGE"));
        codes.put("simulationState", List.of("COMPUTED", "NO_SOLUTION", "UNDETERMINED"));
        codes.put("candidateState", List.of("OPEN", "SELECTED", "DISMISSED"));
        codes.put("reviewVerdict", List.of("ATTESTED", "RETURNED"));
        codes.put("bindingState", List.of("BOUND", "INAPPLICABLE"));
        codes.put("errorCode", List.of("CALIBRATION_UNRESOLVED", "CALIBRATION_CONFLICTED", "AFFECTED_SET_INCOMPLETE",
                "BINDING_INAPPLICABLE", "INDEPENDENCE_REQUIRED", "ALLOWANCE_INSUFFICIENT", "SCOPE_CONTAINED",
                "LISTING_HEALTH_BLOCKS_LAUNCH", "EXECUTION_PATH_MISMATCH", "RESTORE_UNSUPPORTED",
                "EVIDENCE_PATH_UNQUALIFIED", "MATERIALITY_UNRESOLVED", "EXIT_REASON_NOT_APPROVED", "STEP_UP_REQUIRED",
                "ACTION_NOT_PERMITTED", "RESOURCE_SCOPE_DENIED", "RESOURCE_NOT_FOUND", "VERSION_CONFLICT",
                "INVALID_STATE_TRANSITION", "GUARDRAIL_BLOCKED", "APPROVAL_REQUIRED", "RECOMMENDATION_STALE",
                "COMMAND_STATE_INVALID", "WRITE_GATE_CLOSED", "VALIDATION_FAILED", "DUPLICATE_IDENTITY",
                "POLICY_AUTHORIZATION_UNUSABLE", "CAPABILITY_NOT_USABLE", "READBACK_REQUIRED", "COMPENSATION_UNSAFE"));
        return codes;
    }

    private static List<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }
}
