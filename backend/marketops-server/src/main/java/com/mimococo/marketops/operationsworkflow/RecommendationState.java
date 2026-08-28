package com.mimococo.marketops.operationsworkflow;

import java.util.Set;

/**
 * Where a recommendation stands.
 *
 * <p>The allowed moves are declared here and enforced on every transition, so a
 * recommendation cannot reach an approved state without having been validated,
 * and a terminal recommendation cannot be revived into one that authorizes a
 * write.
 *
 * <p>{@link #TASK_ONLY} is not a degraded state. An action with no write
 * capability is complete when a person has done it, and giving that its own
 * state keeps it out of the command path entirely rather than relying on a
 * later check.
 */
public enum RecommendationState {

    /** Created from a calculation run; not yet checked against guardrails. */
    DRAFT,

    /** Guardrails were evaluated for preview and the case holds together. */
    VALIDATED,

    /** Waiting for a person or a bounded authorization to decide. */
    READY_FOR_REVIEW,

    /** The action has no write capability; the work is a task. */
    TASK_ONLY,

    /** A person decided it may proceed. */
    APPROVED,

    /** A bounded standing authorization was consumed instead of a person. */
    POLICY_AUTHORIZED,

    /** A person decided it may not proceed. */
    REJECTED,

    /** Its validity window elapsed before anyone decided. */
    EXPIRED,

    /** Withdrawn before a decision. */
    CANCELLED,

    /** A price command exists for it. */
    COMMAND_CREATED,

    /** The command is being executed and observed. */
    EXECUTION_TRACKING,

    /** The write finished; the effect is being measured. */
    OUTCOME_OBSERVATION,

    /** Everything about it is finished. */
    CLOSED;

    /** Whether nothing further can happen to a recommendation in this state. */
    public boolean terminal() {
        return this == REJECTED || this == EXPIRED || this == CANCELLED || this == CLOSED;
    }

    /** Whether a recommendation in this state stands as an authorization. */
    public boolean authorized() {
        return this == APPROVED || this == POLICY_AUTHORIZED;
    }

    /** The states this one may move to. */
    public Set<RecommendationState> allowedNext() {
        return switch (this) {
            case DRAFT -> Set.of(VALIDATED, TASK_ONLY, EXPIRED, CANCELLED);
            case VALIDATED -> Set.of(READY_FOR_REVIEW, TASK_ONLY, EXPIRED, CANCELLED);
            case READY_FOR_REVIEW ->
                    Set.of(APPROVED, POLICY_AUTHORIZED, REJECTED, EXPIRED, CANCELLED);
            case TASK_ONLY -> Set.of(CLOSED, EXPIRED, CANCELLED);
            case APPROVED, POLICY_AUTHORIZED -> Set.of(COMMAND_CREATED, EXPIRED, CANCELLED);
            case COMMAND_CREATED -> Set.of(EXECUTION_TRACKING, CANCELLED);
            case EXECUTION_TRACKING -> Set.of(OUTCOME_OBSERVATION, CLOSED);
            case OUTCOME_OBSERVATION -> Set.of(CLOSED);
            case REJECTED, EXPIRED, CANCELLED, CLOSED -> Set.of();
        };
    }

    /** Whether this state may move to the given one. */
    public boolean mayMoveTo(RecommendationState next) {
        return allowedNext().contains(next);
    }
}
