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

    /** A required canonical value is missing, so nothing can be compared. */
    REQUIRED_METRIC_UNAVAILABLE,

    /** A required canonical value carries a confidence too low to act on. */
    METRIC_CONFIDENCE_INSUFFICIENT,

    /** The proposed price would leave contribution margin under the floor. */
    MARGIN_BELOW_MINIMUM,

    /** The proposed price would leave unit contribution profit under the floor. */
    UNIT_PROFIT_BELOW_MINIMUM,

    /** The proposed price is below the break-even price. */
    BELOW_BREAK_EVEN,

    /** The single change is larger than the policy allows. */
    SINGLE_CHANGE_TOO_LARGE,

    /** Today's changes already reach the cumulative limit. */
    DAILY_CHANGE_EXCEEDED,

    /** The last change to this subject is too recent. */
    COOLDOWN_ACTIVE,

    /** Available units are below the floor a price change requires. */
    INVENTORY_BELOW_MINIMUM,

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
    RECOMMENDATION_EXPIRED
}
