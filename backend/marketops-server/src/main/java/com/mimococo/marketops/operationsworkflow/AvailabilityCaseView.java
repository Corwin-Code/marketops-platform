package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.UUID;

/**
 * One accountable availability case, as other modules see it.
 *
 * <p>The card identity travels as plain identifiers rather than as the risk
 * module's own types, so the workflow authority owns the case without depending
 * on the module that raised it.
 *
 * @param id the case
 * @param organizationId owning organization
 * @param cardId the card the case was raised from
 * @param childId the exact child that produced it
 * @param causeCode why somebody is needed
 * @param causeKey the deduplication identity
 * @param severity the lane that activated it
 * @param state where it stands
 * @param accountableRoleCode the role that owns the cause
 * @param assigneeUserId who owns it, or {@code null}
 * @param actionDueAt when the action stage is due
 * @param outcomeDueAt when the outcome stage is due, or {@code null}
 * @param reopenCount how many times the same cause has returned
 * @param escalationLevel how far it has been raised
 * @param firstActivatedAt when the cause was first raised
 * @param lastEvidenceAt when evidence was last appended
 * @param improvementFirstSeenAt when the cause was first observed repaired, or {@code null}
 */
public record AvailabilityCaseView(
        UUID id,
        UUID organizationId,
        UUID cardId,
        UUID childId,
        String causeCode,
        String causeKey,
        String severity,
        AvailabilityCaseState state,
        String accountableRoleCode,
        UUID assigneeUserId,
        Instant actionDueAt,
        Instant outcomeDueAt,
        int reopenCount,
        int escalationLevel,
        Instant firstActivatedAt,
        Instant lastEvidenceAt,
        Instant improvementFirstSeenAt) {

    /** Whether the case is still somebody's work. */
    public boolean live() {
        return state.live();
    }
}
