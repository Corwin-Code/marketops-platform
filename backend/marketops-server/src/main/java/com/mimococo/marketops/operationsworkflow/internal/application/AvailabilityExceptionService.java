package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionState;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.operationsworkflow.ExceptionAuthorityLevel;
import com.mimococo.marketops.operationsworkflow.ExceptionScopeKind;
import com.mimococo.marketops.operationsworkflow.internal.domain.ExceptionMaterialityPolicy;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityExceptionRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governed acceptance of a calculated availability risk.
 *
 * <p>Four rules shape every method here.
 *
 * <p>The risk does not move. An acceptance changes the case's disposition and
 * nothing else: the lane, the evidence and the cause stay exactly as calculated,
 * and no path here can produce a verified outcome.
 *
 * <p>Approval is proportional and sized by a published version. With no version
 * in force the request is recorded as {@code AUTHORITY_BLOCKED} and the ordinary
 * risk stays active — the fail-closed answer, not a lenient default.
 *
 * <p>Every grant is bounded and reviewable. The period is decided up front, the
 * schema refuses an active acceptance without one, and expiry returns the case
 * to somebody rather than letting it lapse into silence.
 *
 * <p>Separation is enforced where the Contract requires it, and the answer is
 * written onto the decision row so a reviewer reads the rule that applied rather
 * than trusting that some service applied it.
 */
@Service
public class AvailabilityExceptionService implements AvailabilityExceptionGovernance {

    private final AvailabilityExceptionRepository exceptions;
    private final AvailabilityCaseRepository cases;
    private final AvailabilityCaseService caseService;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;

    public AvailabilityExceptionService(AvailabilityExceptionRepository exceptions,
                                        AvailabilityCaseRepository cases,
                                        AvailabilityCaseService caseService,
                                        MetadataAuditRecorder audit,
                                        IdGenerator ids) {
        this.exceptions = exceptions;
        this.cases = cases;
        this.caseService = caseService;
        this.audit = audit;
        this.ids = ids;
    }

    @Override
    @Transactional
    public AcceptedExceptionView request(ExceptionRequest request) {
        validate(request);
        AvailabilityCaseView governed = cases.find(request.caseId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!governed.live()) {
            // A finished case has nothing left to accept, and recording an
            // acceptance against one would suggest a risk is being carried that
            // nobody is carrying.
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }

        Optional<ExceptionMaterialityPolicy> policy =
                exceptions.resolveMateriality(request.organizationId(), request.at());
        UUID id = ids.newId();
        if (policy.isEmpty()) {
            store(id, request, ExceptionAuthorityLevel.RISK_AUTHORITY,
                    AcceptedExceptionState.AUTHORITY_BLOCKED, null, null, 1);
            recordAudit(request.organizationId(), id, AuditAction.CREATE,
                    request.requestedByUserId().toString(),
                    Map.of("state", new FieldChange(null,
                            AcceptedExceptionState.AUTHORITY_BLOCKED.name())));
            return find(id);
        }

        ExceptionMaterialityPolicy sizing = policy.get();
        if (sizing.exceedsMaximum(request.requestedFrom(), request.requestedUntil())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        int occurrence = exceptions.countGrantedSince(request.organizationId(), request.childId(),
                request.causeCode(), request.at().minus(sizing.repeatLookback())) + 1;
        ExceptionAuthorityLevel required = sizing.requiredAuthority(request.severity(), occurrence,
                request.consequenceAmount(), request.consequenceCurrency(),
                request.requestedFrom(), request.requestedUntil());

        store(id, request, required, AcceptedExceptionState.REQUESTED, sizing.policyId(),
                sizing.policyVersion(), occurrence);
        recordAudit(request.organizationId(), id, AuditAction.CREATE,
                request.requestedByUserId().toString(),
                Map.of("state", new FieldChange(null, AcceptedExceptionState.REQUESTED.name()),
                        "requiredAuthority", new FieldChange(null, required.name())));
        return find(id);
    }

    @Override
    @Transactional
    public AcceptedExceptionView decide(ExceptionDecision decision) {
        AcceptedExceptionView existing = find(decision.exceptionId());
        if (existing.state() != AcceptedExceptionState.REQUESTED) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (blank(decision.reason()) || decision.decidedByUserId() == null
                || decision.decidedByRole() == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        AvailabilityCaseView governed = cases.find(existing.caseId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));

        Optional<ExceptionMaterialityPolicy> policy =
                exceptions.resolveMateriality(existing.organizationId(), decision.at());
        if (policy.isEmpty()) {
            // The version that sized the request is gone. Nothing decides this
            // now, so the request goes back to blocked and the risk stays live.
            // Separation is recorded as required because nothing established
            // that it was not: an unsized decision is not a lenient one.
            return blockAuthority(existing, decision, true);
        }
        ExceptionMaterialityPolicy sizing = policy.get();
        ExceptionAuthorityLevel required = sizing.requiredAuthority(governed.severity(),
                existing.occurrenceCount(), existing.consequenceAmount(),
                existing.consequenceCurrency(), existing.effectiveFrom(), existing.expiresAt());
        boolean separationRequired = sizing.separationRequired(governed.severity(),
                existing.occurrenceCount(), existing.consequenceAmount(),
                existing.consequenceCurrency(), existing.effectiveFrom(), existing.expiresAt());
        boolean requesterIsApprover =
                existing.requestedByUserId().equals(decision.decidedByUserId());

        if (!decision.approved()) {
            exceptions.insertDecision(decisionRow(existing, decision, "REJECTED", required,
                    requesterIsApprover, separationRequired, null, null));
            exceptions.setState(existing.id(), AcceptedExceptionState.REJECTED, decision.at());
            recordAudit(existing.organizationId(), existing.id(), AuditAction.STATUS_CHANGE,
                    decision.decidedByUserId().toString(),
                    Map.of("state", new FieldChange(existing.state().name(),
                            AcceptedExceptionState.REJECTED.name())));
            return find(existing.id());
        }

        if (!ExceptionAuthorityLevel.levelsFor(decision.decidedByRole()).contains(required)) {
            return blockAuthority(existing, decision, separationRequired);
        }
        if (separationRequired && requesterIsApprover) {
            // The database refuses this row too. Refusing it here as well gives
            // the caller a stable code instead of a constraint name.
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        if (!decision.stepUpSatisfied() || decision.authenticatedAt() == null) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (sizing.exceedsMaximum(existing.effectiveFrom(), existing.expiresAt())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        exceptions.insertDecision(decisionRow(existing, decision, "APPROVED", required,
                requesterIsApprover, separationRequired, existing.effectiveFrom(),
                existing.expiresAt()));
        exceptions.activate(existing.id(), existing.effectiveFrom(), existing.expiresAt(),
                existing.reviewAt(), sizing.policyId(), sizing.policyVersion(), decision.at());
        caseService.acceptRisk(existing.caseId(),
                "accepted risk " + existing.reasonCode() + " granted until " + existing.expiresAt(),
                decision.at());
        recordAudit(existing.organizationId(), existing.id(), AuditAction.STATUS_CHANGE,
                decision.decidedByUserId().toString(),
                Map.of("state", new FieldChange(existing.state().name(),
                        AcceptedExceptionState.ACTIVE.name())));
        return find(existing.id());
    }

    @Override
    @Transactional
    public AcceptedExceptionView withdraw(UUID exceptionId, String reason, Instant at) {
        AcceptedExceptionView existing = find(exceptionId);
        if (existing.state() != AcceptedExceptionState.REQUESTED
                && existing.state() != AcceptedExceptionState.AUTHORITY_BLOCKED) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        exceptions.setState(exceptionId, AcceptedExceptionState.WITHDRAWN, at);
        recordAudit(existing.organizationId(), exceptionId, AuditAction.STATUS_CHANGE,
                existing.requestedByUserId().toString(),
                Map.of("state", new FieldChange(existing.state().name(),
                        AcceptedExceptionState.WITHDRAWN.name())));
        return find(exceptionId);
    }

    @Override
    @Transactional
    public AcceptedExceptionView invalidate(UUID exceptionId, InvalidationCause cause,
                                            String reason, Instant at) {
        AcceptedExceptionView existing = find(exceptionId);
        if (existing.state() != AcceptedExceptionState.ACTIVE) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        end(existing, AcceptedExceptionState.INVALIDATED, cause.name() + ": " + reason, at);

        // A governance failure needs a higher authority than the one that let it
        // happen. A risk that has simply come back needs the original owner.
        if (cause == InvalidationCause.REPEATED_CONDITION
                || cause == InvalidationCause.AUTHORITY_LOST
                || cause == InvalidationCause.MATERIALITY_INCREASED) {
            caseService.escalate(existing.caseId(),
                    "accepted risk invalidated: " + cause.name(), at);
        }
        return find(exceptionId);
    }

    @Override
    @Transactional
    public List<AcceptedExceptionView> expireDue(UUID organizationId, Instant at) {
        List<AcceptedExceptionView> ended = new ArrayList<>();
        for (AcceptedExceptionView expiring : exceptions.dueForExpiry(organizationId, at)) {
            end(expiring, AcceptedExceptionState.EXPIRED,
                    "the granted acceptance period ended at " + expiring.expiresAt(), at);
            ended.add(find(expiring.id()));
        }
        return List.copyOf(ended);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AcceptedExceptionView> occupying(UUID organizationId, UUID childId,
                                                     String causeCode, ExceptionScopeKind scopeKind,
                                                     String scopeReference) {
        return exceptions.occupying(organizationId, childId, causeCode, scopeKind, scopeReference);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AcceptedExceptionView> forCase(UUID caseId) {
        return exceptions.forCase(caseId);
    }

    /**
     * End an acceptance and hand the case back.
     *
     * <p>The case reopens rather than being raised again, because the risk was
     * never resolved: it was carried deliberately, and the record has to show
     * one continuous piece of work rather than a series of unrelated ones.
     */
    private void end(AcceptedExceptionView existing, AcceptedExceptionState state, String reason,
                     Instant at) {
        exceptions.close(existing.id(), state, reason, at);
        AvailabilityCaseView governed = cases.find(existing.caseId()).orElse(null);
        if (governed != null && governed.state() == com.mimococo.marketops.operationsworkflow
                .AvailabilityCaseState.ACCEPTED_RISK) {
            caseService.reopenFromException(existing.caseId(), reason, at);
        }
        recordAudit(existing.organizationId(), existing.id(), AuditAction.STATUS_CHANGE,
                "availability-exception-governance",
                Map.of("state", new FieldChange(existing.state().name(), state.name())));
    }

    /**
     * Record that nobody who could decide this was available.
     *
     * <p>Kept as a state rather than an exception because the attempt is part of
     * the governance record: a review asking why a risk was carried unaccepted
     * for a week needs to see that somebody asked and no authority resolved.
     */
    private AcceptedExceptionView blockAuthority(AcceptedExceptionView existing,
                                                 ExceptionDecision decision,
                                                 boolean separationRequired) {
        exceptions.insertDecision(decisionRow(existing, decision, "AUTHORITY_BLOCKED",
                existing.requiredAuthority(),
                existing.requestedByUserId().equals(decision.decidedByUserId()),
                separationRequired, null, null));
        exceptions.setState(existing.id(), AcceptedExceptionState.AUTHORITY_BLOCKED, decision.at());
        recordAudit(existing.organizationId(), existing.id(), AuditAction.STATUS_CHANGE,
                decision.decidedByUserId().toString(),
                Map.of("state", new FieldChange(existing.state().name(),
                        AcceptedExceptionState.AUTHORITY_BLOCKED.name())));
        return find(existing.id());
    }

    private void store(UUID id, ExceptionRequest request, ExceptionAuthorityLevel required,
                       AcceptedExceptionState state, UUID policyId, Integer policyVersion,
                       int occurrence) {
        try {
            exceptions.insert(new AvailabilityExceptionRepository.NewException(id,
                    request.organizationId(), request.caseId(), request.childId(),
                    request.causeCode(), request.scopeKind(), request.scopeReference(),
                    request.reasonCode(), request.rationale(), request.expectedConsequence(),
                    request.consequenceAmount(), request.consequenceCurrency(),
                    request.evidenceReference(), request.requestedByUserId(),
                    request.decisionOwnerRoleCode(), required, state, request.requestedFrom(),
                    request.requestedUntil(), request.reviewAt(), policyId, policyVersion,
                    occurrence, request.at()));
        } catch (DuplicateKeyException occupied) {
            // One live acceptance per cause and scope. Two would leave nobody
            // able to say which expiry governs the risk.
            throw OperationRejectedException.of(ErrorCode.DUPLICATE_IDENTITY);
        }
    }

    private AvailabilityExceptionRepository.DecisionRow decisionRow(
            AcceptedExceptionView existing, ExceptionDecision decision, String verdict,
            ExceptionAuthorityLevel level, boolean requesterIsApprover, boolean separationRequired,
            Instant grantedFrom, Instant grantedUntil) {
        return new AvailabilityExceptionRepository.DecisionRow(ids.newId(),
                existing.organizationId(), existing.id(), verdict, level,
                decision.decidedByUserId(), decision.decidedByRole().name(),
                decision.delegationReference(), requesterIsApprover, separationRequired,
                decision.authenticatedAt(), decision.stepUpSatisfied(), decision.reason(),
                grantedFrom, grantedUntil, decision.at(), decision.correlationId());
    }

    private AcceptedExceptionView find(UUID id) {
        return exceptions.find(id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * Refuse a request that could not be reviewed.
     *
     * <p>Every field checked here is one the Contract names as binding. An
     * acceptance missing its rationale, its expected consequence or its evidence
     * is not a governed decision, and storing it would put an ungoverned one in
     * the audit record.
     */
    private static void validate(ExceptionRequest request) {
        if (request.organizationId() == null || request.caseId() == null
                || request.childId() == null || request.requestedByUserId() == null
                || request.scopeKind() == null || request.reasonCode() == null
                || blank(request.causeCode()) || blank(request.scopeReference())
                || blank(request.rationale()) || blank(request.expectedConsequence())
                || blank(request.evidenceReference()) || blank(request.decisionOwnerRoleCode())
                || blank(request.correlationId())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (request.requestedFrom() == null || request.requestedUntil() == null
                || request.reviewAt() == null
                || !request.requestedUntil().isAfter(request.requestedFrom())
                || request.reviewAt().isBefore(request.requestedFrom())
                || request.reviewAt().isAfter(request.requestedUntil())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if ((request.consequenceAmount() == null) != (request.consequenceCurrency() == null)) {
            throw OperationRejectedException.of(ErrorCode.CURRENCY_MISMATCH);
        }
    }

    private void recordAudit(UUID organizationId, UUID exceptionId, AuditAction action,
                             String actorId, Map<String, FieldChange> changes) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actorId, action, "availability_accepted_exception", exceptionId, null,
                changes, "accepted risk governance", null));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
