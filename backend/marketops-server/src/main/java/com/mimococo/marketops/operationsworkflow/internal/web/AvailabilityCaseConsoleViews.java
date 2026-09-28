package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityExceptionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the availability console reads about cases and acceptances.
 *
 * <p>These shapes belong to the console, not to the modules around it: the
 * cross-module {@link AvailabilityCaseView} and {@link AcceptedExceptionView}
 * stay exactly as other modules know them, and everything added here — what a
 * case is about, who did what, what the viewer may do next — is added beside
 * them.
 *
 * <p>"May do" is computed on the server for the person asking and is advice
 * for the page, never authority: every action is authorized again, and checked
 * against the rules again, at the moment it is taken.
 */
final class AvailabilityCaseConsoleViews {

    private AvailabilityCaseConsoleViews() {
    }

    /** What a person may do to a case. */
    enum CaseAction { RECORD_ACTION, ESCALATE, REQUEST_EXCEPTION }

    /** What a person may do to an acceptance request. */
    enum ExceptionAction { APPROVE, REJECT, WITHDRAW }

    /**
     * Why an action is not offered to this person now.
     *
     * <p>Stable codes rather than sentences, so the console words them in the
     * operator's language and a support conversation can name them exactly.
     */
    enum BlockReason {
        /** The person does not hold the grant or a role the action is taken under. */
        NOT_PERMITTED,
        /** The case is finished. */
        CASE_CLOSED,
        /** An action is recorded and the outcome is being verified. */
        AWAITING_VERIFICATION,
        /** A governed acceptance is in force. */
        RISK_ACCEPTED,
        /** The case is already escalated and has not moved since. */
        ALREADY_ESCALATED,
        /** The case is at the highest escalation level. */
        ESCALATION_LIMIT_REACHED,
        /** The case's state does not allow this action. */
        STATE_NOT_ALLOWED,
        /** An acceptance request or acceptance already occupies the case. */
        EXCEPTION_OPEN,
        /** The request is no longer waiting for a decision. */
        EXCEPTION_NOT_PENDING,
        /** No materiality version is in force, so a decision would block the request. */
        MATERIALITY_POLICY_MISSING,
        /** The person's strongest acceptance authority is below what is required. */
        AUTHORITY_INSUFFICIENT,
        /** The requester may not also approve this request. */
        SEPARATION_REQUIRED,
        /** The requested period is longer than the version in force allows. */
        PERIOD_EXCEEDS_MAXIMUM,
        /** Only the requester may withdraw a request. */
        NOT_REQUESTER
    }

    /** One action not offered, and why. */
    record BlockedAction(String action, BlockReason reason) {
    }

    /** The actions offered to the viewer and the ones withheld with their reasons. */
    record Offer(List<String> allowed, List<BlockedAction> blocked) {

        Offer {
            allowed = List.copyOf(allowed);
            blocked = List.copyOf(blocked);
        }
    }

    /**
     * The product and channel a case is about.
     *
     * @param childKind {@code CHANNEL} or {@code COMPANY}
     * @param platformCode marketplace of a channel child, or {@code null}
     * @param platformSkuKey the marketplace's own key for the listing variant, or {@code null}
     */
    record Subject(UUID productVariantId, String skuCode, String displayName, String childKind,
                   String platformCode, String fulfillmentModeCode, UUID storeId,
                   String storeCode, String storeName, String platformSkuKey) {

        static Subject of(AvailabilityCaseRepository.CaseSubject subject) {
            return new Subject(subject.productVariantId(), subject.skuCode(),
                    subject.displayName(), subject.childKind(), subject.platformCode(),
                    subject.fulfillmentModeCode(), subject.storeId(), subject.storeCode(),
                    subject.storeName(), subject.platformSkuKey());
        }
    }

    /** The acceptance occupying a case: requested, blocked or in force. */
    record OpenException(UUID id, String state, String requiredAuthority, Instant expiresAt) {

        static OpenException of(AvailabilityCaseRepository.OpenException open) {
            return open == null ? null
                    : new OpenException(open.id(), open.state(), open.requiredAuthority(),
                            open.expiresAt());
        }
    }

    /**
     * One case as the console lists it: every field of the case, what it is
     * about, what occupies it, and what the viewer may do.
     */
    record CaseItem(UUID id, UUID organizationId, UUID cardId, UUID childId, String causeCode,
                    String causeKey, String severity, String state, String accountableRoleCode,
                    UUID assigneeUserId, Instant actionDueAt, Instant originalActionDueAt,
                    Instant actionSlaPausedAt, Long actionSlaRemainingMillis,
                    Instant outcomeDueAt, int reopenCount, int escalationLevel,
                    Instant firstActivatedAt, Instant lastEvidenceAt,
                    Instant improvementFirstSeenAt, Subject subject,
                    OpenException openException, List<String> allowedActions,
                    List<BlockedAction> blockedActions) {

        static CaseItem of(AvailabilityCaseRepository.ConsoleCaseRow row, Offer offer) {
            AvailabilityCaseView view = row.view();
            return new CaseItem(view.id(), view.organizationId(), view.cardId(), view.childId(),
                    view.causeCode(), view.causeKey(), view.severity(), view.state().name(),
                    view.accountableRoleCode(), view.assigneeUserId(), view.actionDueAt(),
                    view.originalActionDueAt(), view.actionSlaPausedAt(),
                    view.actionSlaRemainingMillis(), view.outcomeDueAt(), view.reopenCount(),
                    view.escalationLevel(), view.firstActivatedAt(), view.lastEvidenceAt(),
                    view.improvementFirstSeenAt(), Subject.of(row.subject()),
                    OpenException.of(row.openException()), offer.allowed(), offer.blocked());
        }
    }

    /** One page of cases and how many match in total. */
    record CasePage(List<CaseItem> items, long total, int offset, int limit) {
    }

    /**
     * One entry of a case's journal, with the person named.
     *
     * @param actorName the staff name of who did it, or {@code null} when nothing human did
     */
    record JournalEntry(int sequenceNo, String eventKind, String fromState, String toState,
                        String actionKind, String verificationKind, String verificationOutcome,
                        UUID actorUserId, String actorName, String actorRoleCode, String reason,
                        String evidenceReference, Instant observedAt, Instant occurredAt) {

        static JournalEntry of(AvailabilityCaseRepository.CaseJournalEntry entry, String name) {
            return new JournalEntry(entry.sequenceNo(), entry.eventKind(), entry.fromState(),
                    entry.toState(), entry.actionKind(), entry.verificationKind(),
                    entry.verificationOutcome(), entry.actorUserId(), name,
                    entry.actorRoleCode(), entry.reason(), entry.evidenceReference(),
                    entry.observedAt(), entry.occurredAt());
        }
    }

    /**
     * One acceptance request with every recorded field and the requester named.
     *
     * @param requestedByName the requester's staff name, or {@code null}
     */
    record ExceptionItem(UUID id, UUID organizationId, UUID caseId, UUID childId,
                         String causeCode, String scopeKind, String scopeReference,
                         String reasonCode, String rationale, String expectedConsequence,
                         BigDecimal consequenceAmount, String consequenceCurrency,
                         String evidenceReference, UUID requestedByUserId,
                         String requestedByName, Instant requestedAt,
                         String decisionOwnerRoleCode, String requiredAuthority, String state,
                         Instant effectiveFrom, Instant expiresAt, Instant reviewAt,
                         Instant invalidatedAt, String invalidationReason,
                         UUID materialityPolicyId, int occurrenceCount, String acceptedSeverity,
                         BigDecimal acceptedProfitAtRiskAmount,
                         String acceptedProfitAtRiskCurrency, Integer acceptedCaseReopenCount) {

        static ExceptionItem of(AcceptedExceptionView view, String requesterName) {
            return new ExceptionItem(view.id(), view.organizationId(), view.caseId(),
                    view.childId(), view.causeCode(), view.scopeKind().name(),
                    view.scopeReference(), view.reasonCode().name(), view.rationale(),
                    view.expectedConsequence(), view.consequenceAmount(),
                    view.consequenceCurrency(), view.evidenceReference(),
                    view.requestedByUserId(), requesterName, view.requestedAt(),
                    view.decisionOwnerRoleCode(), view.requiredAuthority().name(),
                    view.state().name(), view.effectiveFrom(), view.expiresAt(),
                    view.reviewAt(), view.invalidatedAt(), view.invalidationReason(),
                    view.materialityPolicyId(), view.occurrenceCount(), view.acceptedSeverity(),
                    view.acceptedProfitAtRiskAmount(), view.acceptedProfitAtRiskCurrency(),
                    view.acceptedCaseReopenCount());
        }
    }

    /**
     * One recorded decision with the decider named.
     *
     * @param decidedByName the decider's staff name, or {@code null}
     */
    record DecisionItem(UUID id, String decision, String authorityLevel, UUID decidedByUserId,
                        String decidedByName, String decidedByRoleCode,
                        String delegationReference, boolean requesterIsApprover,
                        boolean separationRequired, boolean stepUpSatisfied, String reason,
                        Instant grantedEffectiveFrom, Instant grantedExpiresAt,
                        Instant decidedAt) {

        static DecisionItem of(AvailabilityExceptionRepository.RecordedDecision decision,
                               String name) {
            return new DecisionItem(decision.id(), decision.decision(),
                    decision.authorityLevel().name(), decision.decidedByUserId(), name,
                    decision.decidedByRoleCode(), decision.delegationReference(),
                    decision.requesterIsApprover(), decision.separationRequired(),
                    decision.stepUpSatisfied(), decision.reason(),
                    decision.grantedEffectiveFrom(), decision.grantedExpiresAt(),
                    decision.decidedAt());
        }
    }

    /** The case an acceptance disposes of, as far as a decider needs it. */
    record CaseSummary(UUID caseId, String causeCode, String severity, String state,
                       String accountableRoleCode, int escalationLevel, int reopenCount) {

        static CaseSummary of(AvailabilityCaseView view) {
            return new CaseSummary(view.id(), view.causeCode(), view.severity(),
                    view.state().name(), view.accountableRoleCode(), view.escalationLevel(),
                    view.reopenCount());
        }
    }

    /**
     * How a decision on the request would be sized now.
     *
     * @param policyInForce whether a materiality version is in force
     * @param periodExceedsMaximum whether an approval would be refused for its period
     */
    record DecisionTerms(boolean policyInForce, String requiredAuthority,
                         boolean separationRequired, boolean periodExceedsMaximum) {

        static DecisionTerms of(AvailabilityExceptionGovernance.ExceptionPreview preview) {
            return new DecisionTerms(preview.policyInForce(), preview.requiredAuthority().name(),
                    preview.separationRequired(), preview.periodExceedsMaximum());
        }
    }

    /**
     * The person asking, as far as a decision page needs them.
     *
     * <p>The identifier lets the page mark "you"; it is never used by the page
     * to decide what the person may do.
     *
     * @param decidingRole the strongest acceptance authority they hold, or {@code null}
     */
    record Viewer(UUID userId, String decidingRole, boolean stepUpSatisfied,
                  Instant stepUpValidUntil) {
    }

    /** One acceptance request in full, with what the viewer may do about it. */
    record ExceptionDetail(ExceptionItem exception, List<DecisionItem> decisions,
                           CaseSummary caseSummary, Subject subject, DecisionTerms decisionTerms,
                           Viewer viewer, List<String> allowedActions,
                           List<BlockedAction> blockedActions) {
    }

    /**
     * One scope a request on this case may name.
     *
     * @param reference the exact scope reference the request must carry
     * @param label a language-neutral identity of the scope, for when the console
     *              has nothing better to show
     */
    record ScopeOption(String scopeKind, String reference, String label, String platformCode,
                       String fulfillmentModeCode, UUID storeId, String storeCode,
                       String storeName, String platformSkuKey, String skuCode,
                       String displayName) {
    }

    /**
     * Everything a request form needs before anybody asks.
     *
     * <p>Every threshold is the published materiality version's own value; with
     * none in force they are empty and a request is recorded as blocked.
     *
     * @param occurrenceCount which acceptance of this cause a new one would be, or {@code null}
     * @param profitAtRiskAmount the child's calculated exposure, as a hint, or {@code null}
     */
    record ExceptionOptions(UUID caseId, String causeCode, String severity,
                            List<ScopeOption> scopes, List<String> reasonCodes,
                            boolean policyInForce, Long maxDurationDays,
                            Long materialDurationDays, BigDecimal materialProfitAtRisk,
                            String materialCurrency, Integer repeatOccurrenceCount,
                            Long repeatLookbackDays, Integer occurrenceCount,
                            BigDecimal profitAtRiskAmount, String profitAtRiskCurrency) {

        static Long days(Duration duration) {
            return duration == null ? null : duration.toDays();
        }
    }

    /** How much authority a request would need, as the rules would decide now. */
    record ExceptionPreviewView(boolean policyInForce, String requiredAuthority,
                                boolean separationRequired, Integer occurrenceCount) {

        static ExceptionPreviewView of(AvailabilityExceptionGovernance.ExceptionPreview preview) {
            return new ExceptionPreviewView(preview.policyInForce(),
                    preview.requiredAuthority().name(), preview.separationRequired(),
                    preview.occurrenceCount());
        }
    }
}
