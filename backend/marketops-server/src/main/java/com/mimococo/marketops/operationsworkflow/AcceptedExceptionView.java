package com.mimococo.marketops.operationsworkflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One accepted-risk record, as the console and other modules see it.
 *
 * <p>The calculated risk is deliberately absent from this view. An acceptance
 * disposes of a risk; it does not restate it, and a view that carried its own
 * copy of the lane would be a second place for the lane to be wrong.
 *
 * @param id the acceptance
 * @param organizationId owning organization
 * @param caseId the case it disposes of
 * @param childId the exact calculated child
 * @param causeCode the cause being accepted
 * @param scopeKind what the acceptance covers
 * @param scopeReference the exact scope
 * @param reasonCode the business reason
 * @param rationale why, in the requester's words
 * @param expectedConsequence what the business expects to lose
 * @param consequenceAmount the expected exposure, or {@code null}
 * @param consequenceCurrency its currency, or {@code null}
 * @param evidenceReference the evidence behind the request
 * @param requestedByUserId who asked
 * @param requestedAt when they asked
 * @param decisionOwnerRoleCode the role accountable for deciding
 * @param requiredAuthority the level the decision needs
 * @param state where the request stands
 * @param effectiveFrom when the grant starts, or {@code null}
 * @param expiresAt when the grant ends, or {@code null}
 * @param reviewAt when it must be reviewed, or {@code null}
 * @param invalidatedAt when it stopped being valid, or {@code null}
 * @param invalidationReason why it stopped, or {@code null}
 * @param materialityPolicyId the version the decision was sized by, or {@code null}
 * @param occurrenceCount how many times this cause has been accepted in the lookback
 */
public record AcceptedExceptionView(
        UUID id,
        UUID organizationId,
        UUID caseId,
        UUID childId,
        String causeCode,
        ExceptionScopeKind scopeKind,
        String scopeReference,
        ExceptionReasonCode reasonCode,
        String rationale,
        String expectedConsequence,
        BigDecimal consequenceAmount,
        String consequenceCurrency,
        String evidenceReference,
        UUID requestedByUserId,
        Instant requestedAt,
        String decisionOwnerRoleCode,
        ExceptionAuthorityLevel requiredAuthority,
        AcceptedExceptionState state,
        Instant effectiveFrom,
        Instant expiresAt,
        Instant reviewAt,
        Instant invalidatedAt,
        String invalidationReason,
        UUID materialityPolicyId,
        int occurrenceCount) {

    /**
     * Whether this acceptance is in force at an instant.
     *
     * <p>Expiry is checked here as well as by the sweep that expires them,
     * because a reader must never see an acceptance presented as in force one
     * second after it lapsed merely because no sweep has run yet.
     */
    public boolean inForceAt(Instant at) {
        return state.inForce()
                && effectiveFrom != null && !at.isBefore(effectiveFrom)
                && expiresAt != null && at.isBefore(expiresAt);
    }
}
