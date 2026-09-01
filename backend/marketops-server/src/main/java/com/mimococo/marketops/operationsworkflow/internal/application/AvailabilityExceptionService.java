package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionState;
import com.mimococo.marketops.operationsworkflow.AcceptedExceptionView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionDelegationView;
import com.mimococo.marketops.operationsworkflow.AvailabilityCaseView;
import com.mimococo.marketops.operationsworkflow.AvailabilityExceptionGovernance;
import com.mimococo.marketops.operationsworkflow.ExceptionAuthorityLevel;
import com.mimococo.marketops.operationsworkflow.ExceptionScopeKind;
import com.mimococo.marketops.operationsworkflow.internal.domain.ExceptionMaterialityPolicy;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityCaseRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AvailabilityExceptionRepository;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
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
        if (!governed.organizationId().equals(request.organizationId())
                || !governed.childId().equals(request.childId())
                || !governed.causeCode().equals(request.causeCode())) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
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
            recordAudit(id, AuditAction.CREATE,
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
        recordAudit(id, AuditAction.CREATE,
                request.requestedByUserId().toString(),
                Map.of("state", new FieldChange(null, AcceptedExceptionState.REQUESTED.name()),
                        "requiredAuthority", new FieldChange(null, required.name())));
        return find(id);
    }

    @Override
    @Transactional
    public AcceptedExceptionView decide(ExceptionDecision decision) {
        validateDecision(decision);
        AcceptedExceptionView existing = find(decision.exceptionId());
        if (existing.state() != AcceptedExceptionState.REQUESTED) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        BusinessRoleCode effectiveRole = resolveDecisionRole(existing, decision);
        AvailabilityCaseView governed = cases.find(existing.caseId())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));

        Optional<ExceptionMaterialityPolicy> policy =
                exceptions.resolveMateriality(existing.organizationId(), decision.at());
        if (policy.isEmpty()) {
            // The version that sized the request is gone. Nothing decides this
            // now, so the request goes back to blocked and the risk stays live.
            // Separation is recorded as required because nothing established
            // that it was not: an unsized decision is not a lenient one.
            return blockAuthority(existing, decision, effectiveRole, true);
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

        if (!ExceptionAuthorityLevel.levelsFor(effectiveRole).contains(required)) {
            return blockAuthority(existing, decision, effectiveRole, separationRequired);
        }
        if (!exceptions.decisionAuthorityLive(existing.organizationId(),
                decision.decidedByUserId(), effectiveRole.name(),
                decision.delegationReference(), decision.at())) {
            return blockAuthority(existing, decision, effectiveRole, separationRequired);
        }

        if (!decision.approved()) {
            exceptions.insertDecision(decisionRow(existing, decision, effectiveRole,
                    "REJECTED", required, requesterIsApprover, separationRequired, null, null));
            exceptions.setState(existing.id(), AcceptedExceptionState.REJECTED, decision.at());
            recordAudit(existing.id(), AuditAction.STATUS_CHANGE,
                    decision.decidedByUserId().toString(),
                    Map.of("state", new FieldChange(existing.state().name(),
                            AcceptedExceptionState.REJECTED.name())));
            return find(existing.id());
        }
        if (separationRequired && requesterIsApprover) {
            // The database refuses this row too. Refusing it here as well gives
            // the caller a stable code instead of a constraint name.
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        validateApprovalProof(decision);
        if (sizing.exceedsMaximum(existing.effectiveFrom(), existing.expiresAt())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        exceptions.insertDecision(decisionRow(existing, decision, effectiveRole, "APPROVED",
                required, requesterIsApprover, separationRequired, existing.effectiveFrom(),
                existing.expiresAt()));
        exceptions.activate(existing.id(), existing.effectiveFrom(), existing.expiresAt(),
                existing.reviewAt(), sizing.policyId(), sizing.policyVersion(), decision.at());
        caseService.acceptRisk(existing.caseId(),
                "accepted risk " + existing.reasonCode() + " granted until " + existing.expiresAt(),
                decision.at());
        recordAudit(existing.id(), AuditAction.STATUS_CHANGE,
                decision.decidedByUserId().toString(),
                Map.of("state", new FieldChange(existing.state().name(),
                        AcceptedExceptionState.ACTIVE.name())));
        return find(existing.id());
    }

    @Override
    @Transactional
    public AvailabilityExceptionDelegationView grantDelegation(ExceptionDelegationGrant grant) {
        validateGrant(grant);
        if (!exceptions.decisionAuthorityLive(grant.organizationId(), grant.grantedByUserId(),
                grant.grantedByRole().name(), null, grant.at())) {
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        if (!ExceptionAuthorityLevel.levelsFor(grant.grantedByRole())
                .containsAll(ExceptionAuthorityLevel.levelsFor(grant.delegatedRole()))) {
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        UUID id = ids.newId();
        try {
            exceptions.insertDelegation(new AvailabilityExceptionRepository.DelegationRow(
                    id, grant.organizationId(), grant.delegationReference(),
                    grant.delegateUserId(), grant.delegatedRole(), grant.grantedByUserId(),
                    grant.grantedByRole(), grant.effectiveFrom(), grant.effectiveTo(),
                    grant.evidenceReference(), grant.at(), grant.correlationId()));
        } catch (DuplicateKeyException duplicate) {
            throw OperationRejectedException.of(ErrorCode.DUPLICATE_IDENTITY);
        }
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                grant.grantedByUserId().toString(), AuditAction.GRANT,
                "availability_exception_delegation", id, grant.delegationReference(),
                Map.of("delegateUserId", new FieldChange(null,
                                grant.delegateUserId().toString()),
                        "delegatedRole", new FieldChange(null, grant.delegatedRole().name()),
                        "effectiveTo", new FieldChange(null, grant.effectiveTo().toString())),
                "bounded accepted-risk decision delegation", grant.evidenceReference()));
        return findDelegation(grant.organizationId(), grant.delegationReference());
    }

    @Override
    @Transactional
    public AvailabilityExceptionDelegationView revokeDelegation(
            ExceptionDelegationRevocation revocation) {
        validateRevocation(revocation);
        AvailabilityExceptionDelegationView existing = findDelegation(
                revocation.organizationId(), revocation.delegationReference());
        if (existing.revokedAt() != null) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!exceptions.decisionAuthorityLive(revocation.organizationId(),
                revocation.revokedByUserId(), revocation.revokedByRole().name(), null,
                revocation.at())
                || !ExceptionAuthorityLevel.levelsFor(revocation.revokedByRole())
                .containsAll(ExceptionAuthorityLevel.levelsFor(existing.delegatedRole()))) {
            throw OperationRejectedException.of(ErrorCode.ACTION_NOT_PERMITTED);
        }
        if (!exceptions.revokeDelegation(revocation.organizationId(),
                revocation.delegationReference(), revocation.revokedByUserId(),
                revocation.reason(), revocation.at())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                revocation.revokedByUserId().toString(), AuditAction.REVOKE,
                "availability_exception_delegation", existing.id(),
                existing.delegationReference(),
                Map.of("revokedAt", new FieldChange(null, revocation.at().toString()),
                        "revocationReason", new FieldChange(null, revocation.reason())),
                "revoke accepted-risk decision delegation", existing.evidenceReference()));
        return findDelegation(revocation.organizationId(), revocation.delegationReference());
    }

    @Override
    @Transactional
    public AcceptedExceptionView withdraw(UUID exceptionId, String reason, Instant at) {
        AcceptedExceptionView existing = find(exceptionId);
        if (blank(reason)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (existing.state() != AcceptedExceptionState.REQUESTED
                && existing.state() != AcceptedExceptionState.AUTHORITY_BLOCKED) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        exceptions.setState(exceptionId, AcceptedExceptionState.WITHDRAWN, at);
        recordAudit(exceptionId, AuditAction.STATUS_CHANGE,
                existing.requestedByUserId().toString(),
                Map.of("state", new FieldChange(existing.state().name(),
                                AcceptedExceptionState.WITHDRAWN.name()),
                        "withdrawalReason", new FieldChange(null, reason)));
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
    @Transactional
    public List<AcceptedExceptionView> revalidateActive(UUID organizationId, Instant at) {
        List<AcceptedExceptionView> invalidated = new ArrayList<>();
        Optional<ExceptionMaterialityPolicy> livePolicy =
                exceptions.resolveMateriality(organizationId, at);
        for (AcceptedExceptionView accepted : exceptions.active(organizationId, at)) {
            InvalidationCause cause = invalidationCause(accepted, livePolicy.orElse(null), at);
            if (cause != null) {
                invalidated.add(invalidate(accepted.id(), cause,
                        "automatic re-evaluation against current risk and authority", at));
            }
        }
        return List.copyOf(invalidated);
    }

    private InvalidationCause invalidationCause(AcceptedExceptionView accepted,
                                                ExceptionMaterialityPolicy livePolicy,
                                                Instant at) {
        if (livePolicy == null || !livePolicy.policyId().equals(accepted.materialityPolicyId())) {
            return InvalidationCause.GOVERNING_POLICY_CHANGED;
        }
        AvailabilityExceptionRepository.CurrentRisk current =
                exceptions.currentRisk(accepted.id()).orElse(null);
        if (current == null || !accepted.causeCode().equals(current.causeCode())) {
            return InvalidationCause.CAUSE_CHANGED;
        }
        if (!scopeStillMatches(accepted, current)) {
            return InvalidationCause.SCOPE_CHANGED;
        }
        if (!exceptions.approvalAuthorityLive(accepted.id(), at)) {
            return InvalidationCause.AUTHORITY_LOST;
        }
        if (current.acceptedCaseReopenCount() != null
                && current.currentCaseReopenCount() > current.acceptedCaseReopenCount()) {
            return InvalidationCause.REPEATED_CONDITION;
        }
        if (severityRank(current.severity()) > severityRank(current.acceptedSeverity())) {
            return InvalidationCause.MATERIALITY_INCREASED;
        }
        ExceptionAuthorityLevel nowRequired = livePolicy.requiredAuthority(current.severity(),
                accepted.occurrenceCount(), current.profitAtRiskAmount(),
                current.profitAtRiskCurrency(), accepted.effectiveFrom(), accepted.expiresAt());
        if (!accepted.requiredAuthority().satisfies(nowRequired)) {
            return accepted.occurrenceCount() > 1
                    ? InvalidationCause.REPEATED_CONDITION
                    : InvalidationCause.MATERIALITY_INCREASED;
        }
        if (accepted.consequenceAmount() != null && current.profitAtRiskAmount() != null
                && java.util.Objects.equals(accepted.consequenceCurrency(),
                        current.profitAtRiskCurrency())
                && current.profitAtRiskAmount().compareTo(accepted.consequenceAmount()) > 0) {
            return InvalidationCause.MATERIALITY_INCREASED;
        }
        if (current.acceptedProfitAtRiskAmount() != null
                && current.profitAtRiskAmount() != null
                && java.util.Objects.equals(current.acceptedProfitAtRiskCurrency(),
                        current.profitAtRiskCurrency())
                && current.profitAtRiskAmount()
                        .compareTo(current.acceptedProfitAtRiskAmount()) > 0) {
            return InvalidationCause.MATERIALITY_INCREASED;
        }
        if (current.acceptedRiskDigest() == null
                || !current.acceptedRiskDigest().equals(current.riskDigest())) {
            return InvalidationCause.EVIDENCE_CONFLICT;
        }
        return null;
    }

    private static int severityRank(String severity) {
        return switch (severity == null ? "" : severity) {
            case "WATCH" -> 1;
            case "HIGH" -> 2;
            case "CRITICAL", "REVIEW", "UNRESOLVED" -> 3;
            default -> 0;
        };
    }

    private static boolean scopeStillMatches(AcceptedExceptionView accepted,
                                             AvailabilityExceptionRepository.CurrentRisk current) {
        String reference = accepted.scopeReference();
        return switch (accepted.scopeKind()) {
            case CHILD -> accepted.childId().toString().equals(reference);
            case VARIANT -> current.productVariantId().toString().equals(reference);
            case STORE -> current.storeId() != null && current.storeId().toString().equals(reference);
            case CHANNEL -> current.platformListingVariantId() != null
                    && (current.platformListingVariantId() + "|" + current.fulfillmentModeCode())
                    .equals(reference);
        };
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
        recordAudit(existing.id(), AuditAction.STATUS_CHANGE,
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
                                                 BusinessRoleCode effectiveRole,
                                                 boolean separationRequired) {
        exceptions.insertDecision(decisionRow(existing, decision, effectiveRole,
                "AUTHORITY_BLOCKED", existing.requiredAuthority(),
                existing.requestedByUserId().equals(decision.decidedByUserId()),
                separationRequired, null, null));
        exceptions.setState(existing.id(), AcceptedExceptionState.AUTHORITY_BLOCKED, decision.at());
        recordAudit(existing.id(), AuditAction.STATUS_CHANGE,
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
            AcceptedExceptionView existing, ExceptionDecision decision,
            BusinessRoleCode effectiveRole, String verdict,
            ExceptionAuthorityLevel level, boolean requesterIsApprover, boolean separationRequired,
            Instant grantedFrom, Instant grantedUntil) {
        return new AvailabilityExceptionRepository.DecisionRow(ids.newId(),
                existing.organizationId(), existing.id(), verdict, level,
                decision.decidedByUserId(), effectiveRole.name(),
                decision.delegationReference(), requesterIsApprover, separationRequired,
                decision.authenticatedAt(), decision.stepUpSatisfied(), decision.reason(),
                grantedFrom, grantedUntil, decision.at(), decision.correlationId());
    }

    private AcceptedExceptionView find(UUID id) {
        return exceptions.find(id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private AvailabilityExceptionDelegationView findDelegation(UUID organizationId,
                                                                String reference) {
        return exceptions.findDelegation(organizationId, reference)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private BusinessRoleCode resolveDecisionRole(AcceptedExceptionView existing,
                                                  ExceptionDecision decision) {
        if (!blank(decision.delegationReference())) {
            return exceptions.delegatedRole(existing.organizationId(),
                            decision.decidedByUserId(), decision.delegationReference(),
                            decision.at())
                    .orElseThrow(() -> OperationRejectedException.of(
                            ErrorCode.ACTION_NOT_PERMITTED));
        }
        if (decision.decidedByRole() == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return decision.decidedByRole();
    }

    private static void validateGrant(ExceptionDelegationGrant grant) {
        if (grant.organizationId() == null || grant.delegateUserId() == null
                || grant.delegatedRole() == null || grant.grantedByUserId() == null
                || grant.grantedByRole() == null || grant.effectiveFrom() == null
                || grant.effectiveTo() == null || grant.at() == null
                || blank(grant.delegationReference()) || blank(grant.evidenceReference())
                || blank(grant.correlationId())
                || grant.delegateUserId().equals(grant.grantedByUserId())
                || !grant.effectiveTo().isAfter(grant.at())
                || !grant.effectiveTo().isAfter(grant.effectiveFrom())
                || ExceptionAuthorityLevel.levelsFor(grant.delegatedRole()).isEmpty()
                || ExceptionAuthorityLevel.levelsFor(grant.grantedByRole()).isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * Validate caller-shaped decision data before the governed decision flow.
     *
     * <p>Keeping input validation in a dedicated fail-closed boundary separates
     * input rejection from the later policy, authority and step-up flow. A
     * malformed request is rejected before that flow and cannot obtain an
     * alternate decision path.
     */
    private static void validateDecision(ExceptionDecision decision) {
        if (decision == null || decision.exceptionId() == null
                || decision.decidedByUserId() == null || decision.at() == null
                || blank(decision.reason()) || blank(decision.correlationId())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** Validate the two independent proofs required for an approval. */
    private static void validateApprovalProof(ExceptionDecision decision) {
        boolean stepUpSatisfied = decision.stepUpSatisfied();
        Instant authenticatedAt = decision.authenticatedAt();
        if (!stepUpSatisfied || authenticatedAt == null) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
    }

    /** Validate caller-shaped revocation data before any authority lookup. */
    private static void validateRevocation(ExceptionDelegationRevocation revocation) {
        if (revocation == null || revocation.organizationId() == null
                || revocation.revokedByUserId() == null || revocation.revokedByRole() == null
                || revocation.at() == null || blank(revocation.delegationReference())
                || blank(revocation.reason()) || blank(revocation.correlationId())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
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

    private void recordAudit(UUID exceptionId, AuditAction action, String actorId,
                             Map<String, FieldChange> changes) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATIONS_WORKFLOW,
                actorId, action, "availability_accepted_exception", exceptionId, null,
                changes, "accepted risk governance", null));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
