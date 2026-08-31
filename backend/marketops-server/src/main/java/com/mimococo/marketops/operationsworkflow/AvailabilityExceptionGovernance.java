package com.mimococo.marketops.operationsworkflow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Governed acceptance of a calculated availability risk.
 *
 * <p>An acceptance is a business disposition, not a correction. Nothing here
 * can change a lane, mark an outcome verified, or remove a risk from
 * monitoring; the only thing it changes is who is expected to act on it and
 * until when.
 *
 * <p>Every grant is bounded. There is no method that grants an unbounded
 * acceptance, because a permanent hidden monitoring exclusion is exactly what
 * this feature must not be able to express.
 */
public interface AvailabilityExceptionGovernance {

    /**
     * Ask to accept one cause on one scope.
     *
     * <p>The request is recorded whatever the answer. When no materiality
     * version is in force it is recorded as {@code AUTHORITY_BLOCKED}: the
     * attempt is auditable, and the ordinary risk stays active because nothing
     * sized the decision.
     */
    AcceptedExceptionView request(ExceptionRequest request);

    /**
     * Decide one request.
     *
     * <p>An approval grants exactly the period it names and nothing longer than
     * the governing policy allows. A rejection leaves the risk where it was.
     */
    AcceptedExceptionView decide(ExceptionDecision decision);

    /** Withdraw a request nobody has decided yet. */
    AcceptedExceptionView withdraw(UUID exceptionId, String reason, Instant at);

    /**
     * End an acceptance because something it depended on stopped being true.
     *
     * <p>The same case reopens, with its history, rather than a new one being
     * raised: the risk never went away, and the record should not suggest it
     * did.
     */
    AcceptedExceptionView invalidate(UUID exceptionId, InvalidationCause cause, String reason,
                                     Instant at);

    /** End every acceptance whose granted period has run out. */
    List<AcceptedExceptionView> expireDue(UUID organizationId, Instant at);

    /** Re-evaluate every active acceptance against current risk, policy and authority. */
    List<AcceptedExceptionView> revalidateActive(UUID organizationId, Instant at);

    /** The acceptance occupying one cause and scope, when one exists. */
    Optional<AcceptedExceptionView> occupying(UUID organizationId, UUID childId, String causeCode,
                                              ExceptionScopeKind scopeKind, String scopeReference);

    /** Every acceptance recorded against one case, newest first. */
    List<AcceptedExceptionView> forCase(UUID caseId);

    /**
     * Why an acceptance stopped being valid.
     *
     * <p>Named rather than free text because each of these is a different
     * governance failure with a different follow-up, and a review that cannot
     * tell them apart cannot improve any of them.
     */
    enum InvalidationCause {

        /** The exposure grew past what the granting authority could accept. */
        MATERIALITY_INCREASED,

        /** The risk is now a different cause. */
        CAUSE_CHANGED,

        /** The risk moved outside the accepted scope. */
        SCOPE_CHANGED,

        /** The evidence behind the request is contradicted by newer evidence. */
        EVIDENCE_CONFLICT,

        /** The approver no longer holds the authority they decided under. */
        AUTHORITY_LOST,

        /** The same cause has been accepted too often to keep accepting. */
        REPEATED_CONDITION,

        /** The materiality policy that sized the decision changed. */
        GOVERNING_POLICY_CHANGED
    }

    /**
     * What somebody is asking to accept.
     *
     * @param organizationId owning organization
     * @param caseId the case the acceptance disposes of
     * @param childId the exact calculated child
     * @param causeCode the cause being accepted
     * @param severity the calculated lane, which sizes the approval
     * @param scopeKind what the acceptance should cover
     * @param scopeReference the exact scope
     * @param reasonCode the business reason
     * @param rationale why, in the requester's words
     * @param expectedConsequence what the business expects to lose
     * @param consequenceAmount the expected exposure, or {@code null}
     * @param consequenceCurrency its currency, or {@code null}
     * @param evidenceReference the evidence behind the request
     * @param requestedByUserId who is asking
     * @param decisionOwnerRoleCode the role accountable for deciding
     * @param requestedFrom when the acceptance should start
     * @param requestedUntil when it should end
     * @param reviewAt when it must be reviewed
     * @param correlationId the request's own identity
     * @param at the instant of the request
     */
    record ExceptionRequest(
            UUID organizationId,
            UUID caseId,
            UUID childId,
            String causeCode,
            String severity,
            ExceptionScopeKind scopeKind,
            String scopeReference,
            ExceptionReasonCode reasonCode,
            String rationale,
            String expectedConsequence,
            BigDecimal consequenceAmount,
            String consequenceCurrency,
            String evidenceReference,
            UUID requestedByUserId,
            String decisionOwnerRoleCode,
            Instant requestedFrom,
            Instant requestedUntil,
            Instant reviewAt,
            String correlationId,
            Instant at) {
    }

    /**
     * One recorded decision about one request.
     *
     * @param exceptionId the request
     * @param approved whether it is granted
     * @param decidedByUserId who decided
     * @param decidedByRole the role they decided as
     * @param delegationReference the effective-dated delegation, or {@code null}
     * @param authenticatedAt when they re-authenticated
     * @param stepUpSatisfied whether the step-up requirement was met
     * @param reason why
     * @param correlationId the decision's own identity
     * @param at the instant of the decision
     */
    record ExceptionDecision(
            UUID exceptionId,
            boolean approved,
            UUID decidedByUserId,
            com.mimococo.marketops.identityaccess.BusinessRoleCode decidedByRole,
            String delegationReference,
            Instant authenticatedAt,
            boolean stepUpSatisfied,
            String reason,
            String correlationId,
            Instant at) {
    }
}
