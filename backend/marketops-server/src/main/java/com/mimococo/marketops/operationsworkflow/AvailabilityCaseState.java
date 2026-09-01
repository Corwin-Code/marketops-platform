package com.mimococo.marketops.operationsworkflow;

import java.util.Set;

/**
 * Where an accountable availability case stands.
 *
 * <p>The distinctions are the point. Recording an action is not verifying an
 * outcome, verifying an outcome is not the same as accepting the risk, and a
 * case that came back is not a new case. A generic "done" would collapse all
 * three and make the queue's completion rate meaningless.
 */
public enum AvailabilityCaseState {

    /** Raised and unassigned. */
    OPEN,

    /** Somebody owns it. */
    ASSIGNED,

    /** Somebody is working on it. */
    IN_PROGRESS,

    /** Structured, attributable action evidence exists. The risk may still be real. */
    ACTION_RECORDED,

    /** Waiting for fresh cause-specific evidence that the risk actually improved. */
    VERIFYING,

    /** Fresh evidence showed the risk improved. The only success state. */
    VERIFIED_SUCCESS,

    /** The risk returned, or its evidence expired, on the same case. */
    REOPENED,

    /** Raised to a higher authority under policy. */
    ESCALATED,

    /** The action did not work and the case needs different work. */
    REWORK_REQUIRED,

    /** A governed acceptance is in force. The calculated risk is unchanged. */
    ACCEPTED_RISK,

    /** Withdrawn without a verified outcome. */
    CANCELLED;

    /**
     * The states this one may move to.
     *
     * <p>{@code VERIFIED_SUCCESS} and {@code CANCELLED} are terminal, and a
     * returning risk becomes a new case rather than reviving a closed one — the
     * closed case's history stays exactly as it was left.
     */
    public Set<AvailabilityCaseState> allowedNext() {
        return switch (this) {
            case OPEN -> Set.of(ASSIGNED, ACTION_RECORDED, ESCALATED, ACCEPTED_RISK, CANCELLED);
            case ASSIGNED -> Set.of(IN_PROGRESS, ACTION_RECORDED, ESCALATED, ACCEPTED_RISK,
                    CANCELLED);
            case IN_PROGRESS -> Set.of(ACTION_RECORDED, ESCALATED, ACCEPTED_RISK, CANCELLED);
            case ACTION_RECORDED -> Set.of(VERIFYING, REOPENED, ESCALATED, REWORK_REQUIRED,
                    CANCELLED);
            case VERIFYING -> Set.of(VERIFIED_SUCCESS, REOPENED, REWORK_REQUIRED, ESCALATED,
                    CANCELLED);
            case REOPENED -> Set.of(ASSIGNED, IN_PROGRESS, ACTION_RECORDED, ESCALATED,
                    ACCEPTED_RISK, CANCELLED);
            case ESCALATED -> Set.of(ASSIGNED, IN_PROGRESS, ACTION_RECORDED, ACCEPTED_RISK,
                    CANCELLED);
            case REWORK_REQUIRED -> Set.of(ASSIGNED, IN_PROGRESS, ACTION_RECORDED, ESCALATED,
                    CANCELLED);
            case ACCEPTED_RISK -> Set.of(REOPENED, ESCALATED, CANCELLED);
            case VERIFIED_SUCCESS, CANCELLED -> Set.of();
        };
    }

    /** Whether the case is finished. */
    public boolean terminal() {
        return allowedNext().isEmpty();
    }

    /** Whether this state still counts as live work for cause deduplication. */
    public boolean live() {
        return !terminal();
    }
}
