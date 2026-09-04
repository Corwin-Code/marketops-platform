package com.mimococo.marketops.operationsworkflow;

/**
 * Why the deterministic guardrail refused a proposed action.
 *
 * <p>A refusal is a code rather than a message so the console, the runbooks,
 * the alerts and the tests all name the same condition. An operator who is told
 * {@code COOLDOWN_ACTIVE} can look it up; an operator told "not allowed right
 * now" can only guess.
 *
 * <p>Every reason here is a state of the world rather than a judgement about
 * it. That is what makes the verdict reproducible: the same inputs produce the
 * same set of codes on any day, in any environment.
 */
public enum GuardrailReason {

    /** No commercial policy is in force for this subject at this instant. */
    NO_POLICY_IN_FORCE,

    /** The policy in force does not configure a limit a price write requires. */
    POLICY_LIMIT_NOT_CONFIGURED,

    /** Too many of the inputs the case rests on are unavailable. */
    DATA_COMPLETENESS_BELOW_MINIMUM,

    /** The freshest contributing fact is older than the policy allows. */
    INPUT_TOO_STALE,

    /** A required metric carries no source time, so freshness cannot be established. */
    INPUT_FRESHNESS_UNAVAILABLE,

    /** A required canonical value is missing, so nothing can be compared. */
    REQUIRED_METRIC_UNAVAILABLE,

    /** A required canonical value carries a confidence too low to act on. */
    METRIC_CONFIDENCE_INSUFFICIENT,

    /** No scoped price-economics profile is current for the proposal. */
    ECONOMICS_PROFILE_MISSING,

    /** More than one price-economics profile claims the proposal scope. */
    ECONOMICS_PROFILE_AMBIGUOUS,

    /** The profile or its verification interval elapsed. */
    ECONOMICS_PROFILE_EXPIRED,

    /** The profile has no current verification evidence. */
    ECONOMICS_PROFILE_UNVERIFIED,

    /** A component shape, tier, coverage contract or price solution is unsupported. */
    PROJECTED_ECONOMICS_UNAVAILABLE,

    /** Required monetary values do not all speak the policy currency. */
    CURRENCY_MISMATCH,

    /** The proposed price would leave contribution margin under the floor. */
    MARGIN_BELOW_MINIMUM,

    /** The proposed price would leave unit contribution profit under the floor. */
    UNIT_PROFIT_BELOW_MINIMUM,

    /** The proposed price is below the break-even price. */
    BELOW_BREAK_EVEN,

    /** The proposed price is below contractual Minimum Price. */
    BELOW_MINIMUM_PRICE,

    /** The single change is larger than the policy allows. */
    SINGLE_CHANGE_TOO_LARGE,

    /** Today's changes already reach the cumulative limit. */
    DAILY_CHANGE_EXCEEDED,

    /** The last change to this subject is too recent. */
    COOLDOWN_ACTIVE,

    /** Available units are below the floor a price change requires. */
    INVENTORY_BELOW_MINIMUM,

    /** No canonical platform stock assertion is available. */
    INVENTORY_EVIDENCE_UNAVAILABLE,

    /** The listing has no confirmed internal mapping. */
    MAPPING_UNRESOLVED,

    /** The listing has an unresolved mapping conflict. */
    MAPPING_CONFLICT_OPEN,

    /** A deterministic finding blocks a platform write for this subject. */
    DIAGNOSIS_BLOCKS_EXECUTION,

    /** The change exceeds what the bounded authorization permits. */
    CHANGE_EXCEEDS_POLICY_AUTHORIZATION,

    /** The subject's facts changed since the case was built. */
    ENTITY_VERSION_CHANGED,

    /** The recommendation's own validity window has elapsed. */
    RECOMMENDATION_EXPIRED,

    // The advertising reasons. A bid change refuses for different things from a
    // price change, and naming them apart is what lets a runbook exist for each.

    /** The platform's bid moved since the candidate was computed against it. */
    BID_MOVED_SINCE_CANDIDATE,

    /** Nothing observed the bid, so no change could be exact. */
    CURRENT_BID_NOT_OBSERVED,

    /** The advertising object is not proven to be independently controllable. */
    CONTROL_GRANULARITY_UNPROVEN,

    /** No active reservation is held for the set this change would affect. */
    RESERVATION_NOT_HELD,

    /** No unique complete active policy bundle covers this decision. */
    AD_POLICY_BUNDLE_UNRESOLVED,

    /** An aggregate exposure axis has no headroom left. */
    EXPOSURE_ENVELOPE_EXHAUSTED,

    /** The proposed bid exceeds the ceiling a click may be worth. */
    ABOVE_MAX_CPC,

    /** The ceiling a click may be worth could not be established. */
    MAX_CPC_UNAVAILABLE,

    /** The advertising calculation itself refused, and said why. */
    ADVERTISING_CASE_BLOCKED,

    /** The proposal names the bid the platform already holds. */
    NO_CHANGE_PROPOSED,

    /** No approval-lease policy is in force for this direction. */
    APPROVAL_LEASE_POLICY_ABSENT
}
