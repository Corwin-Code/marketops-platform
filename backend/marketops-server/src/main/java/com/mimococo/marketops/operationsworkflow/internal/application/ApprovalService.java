package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailVerdict;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ApprovalRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PolicyRepository;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a proposal becomes something a platform write may rest on.
 *
 * <p>A decision is recorded only after the guardrail has passed for approval
 * specifically. Recording first and checking later would leave an approval in
 * the journal that authorizes nothing, which is worse than no approval: it
 * reads as permission.
 *
 * <p>Two ways exist to authorize, and they are exclusive. A person decides,
 * naming when they authenticated, or a bounded standing authorization is spent.
 * A decision that could be attributed to both would leave nobody accountable
 * for it.
 *
 * <p>The digest of the reviewed facts travels into the decision. That is what
 * makes the approval specific: the write gate compares it against the facts at
 * the moment of the write, so an approval cannot survive the case moving under
 * it.
 */
@Service
public class ApprovalService {

    static final String ENTITY_TYPE = "approval-decision";

    /**
     * How long an authorization keeps covering a write.
     *
     * <p>Bounded because an approval is a judgement about a situation, and a
     * situation does not stay the same indefinitely. A command that has not been
     * executed within the window has to be decided again.
     */
    private static final Duration AUTHORIZATION_SCOPE = Duration.ofHours(24);

    private final RecommendationService recommendations;
    private final GuardrailService guardrails;
    private final ApprovalRepository approvals;
    private final PolicyRepository policies;
    private final ListingIdentityDirectory listings;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final AdvertisingHumanDecisionService advertisingHumans;
    private final com.mimococo.marketops.marketplaceintegration.AdBidApprovalAuthority advertisingApproval;

    ApprovalService(RecommendationService recommendations,
                    GuardrailService guardrails,
                    ApprovalRepository approvals,
                    PolicyRepository policies,
                    ListingIdentityDirectory listings,
                    BusinessAuthorization authorization,
                    MetadataAuditRecorder auditRecorder,
                    IdGenerator idGenerator,
                    Clock clock, AdvertisingHumanDecisionService advertisingHumans,
                    com.mimococo.marketops.marketplaceintegration.AdBidApprovalAuthority advertisingApproval) {
        this.recommendations = recommendations;
        this.guardrails = guardrails;
        this.approvals = approvals;
        this.policies = policies;
        this.listings = listings;
        this.authorization = authorization;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.advertisingHumans = advertisingHumans;
        this.advertisingApproval = advertisingApproval;
    }

    /**
     * A person decides a proposal may proceed.
     *
     * <p>The step-up requirement is enforced against the person's own recorded
     * authentication time rather than against a session flag, so an old session
     * cannot approve a price change on a real marketplace.
     */
    @Transactional
    public Decision approve(AuthenticatedActor actor, UUID recommendationId, String reason,
                            long expectedVersion) {
        RecommendationView proposal = requireDecidable(actor, recommendationId,
                expectedVersion);
        Instant now = clock.instant();
        if (!actor.stepUpSatisfiedAt(now)) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }

        UUID advertisingBaseline = proposal.actionKind() == ActionKind.AD_BID_CHANGE
                ? advertisingHumans.requireFinalApproval(actor, proposal) : null;
        GuardrailVerdict verdict = guardrails.evaluate(proposal, null,
                GuardrailPurpose.APPROVAL);
        if (!verdict.passed()) {
            throw OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED);
        }

        UUID decisionId = record(proposal, "APPROVED", actor.userId(), null,
                actor.authenticatedAt(), true, reason, now);
        recommendations.transition(actor.userId().toString(), recommendationId,
                RecommendationState.APPROVED, null, expectedVersion);
        if (proposal.actionKind() == ActionKind.AD_BID_CHANGE) {
            advertisingApproval.seal(recommendationId, decisionId, advertisingBaseline);
            advertisingHumans.recordAction(actor,recommendationId,"DECISION_APPROVED",decisionId,reason);
        }
        return new Decision(decisionId, RecommendationState.APPROVED, verdict, null);
    }

    /** A person decides a proposal may not proceed. */
    @Transactional
    public Decision reject(AuthenticatedActor actor, UUID recommendationId, String reason,
                           long expectedVersion) {
        RecommendationView proposal = requireDecidable(actor, recommendationId,
                expectedVersion);
        if(proposal.actionKind()==ActionKind.AD_BID_CHANGE) advertisingHumans.requireFinalApproval(actor,proposal);
        Instant now = clock.instant();
        UUID decisionId = record(proposal, "REJECTED", actor.userId(), null,
                actor.authenticatedAt(), actor.stepUpSatisfiedAt(now), reason, now);
        recommendations.transition(actor.userId().toString(), recommendationId,
                RecommendationState.REJECTED, "REJECTED_BY_REVIEWER", expectedVersion);
        if(proposal.actionKind()==ActionKind.AD_BID_CHANGE)
            advertisingHumans.recordAction(actor,recommendationId,"DECISION_REJECTED",decisionId,reason);
        return new Decision(decisionId, RecommendationState.REJECTED, null, null);
    }

    /**
     * Spend a bounded standing authorization instead of asking a person.
     *
     * <p>The guardrail is evaluated with the authorization's own bound in the
     * input, so a change larger than the authorization permits is refused before
     * a use is spent. The consuming function rechecks every bound against the
     * row it locks, which is what makes the limit hold when two approvals race.
     */
    @Transactional
    public Decision authorizeByPolicy(AuthenticatedActor actor, UUID recommendationId,
                                      String reason, long expectedVersion) {
        RecommendationView proposal = requireDecidable(actor, recommendationId,
                expectedVersion);
        if (proposal.actionKind() == ActionKind.AD_BID_CHANGE) {
            // Standing policy automation is not part of this product's
            // advertising capability. A bid change is decided by a person, every
            // time, and refusing here rather than failing later on a missing
            // change rate is the difference between a rule and an accident.
            throw OperationRejectedException.of(ErrorCode.POLICY_AUTHORIZATION_UNUSABLE);
        }
        Instant now = clock.instant();

        Optional<ListingVariantContext> context =
                listings.variantContext(proposal.subjectId(), now);
        UUID productVariantId = context.map(ListingVariantContext::productVariantId)
                .orElse(null);
        PolicyRepository.AuthorizationRow standing = policies
                .usableAuthorization(proposal.organizationId(), proposal.storeId(),
                        productVariantId, now)
                .orElseThrow(() -> OperationRejectedException.of(
                        ErrorCode.POLICY_AUTHORIZATION_UNUSABLE));

        GuardrailVerdict verdict = guardrails.evaluate(proposal, standing.maxChangeRate(),
                GuardrailPurpose.APPROVAL);
        if (!verdict.passed()) {
            throw OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED);
        }

        BigDecimal changeRate = changeRateOf(verdict);
        int remaining = policies.consumeAuthorization(standing.id(), changeRate,
                proposal.storeId(), productVariantId);

        UUID decisionId = record(proposal, "POLICY_AUTHORIZED", null, standing.id(), null,
                false, reason, now);
        recommendations.transition(actor.userId().toString(), recommendationId,
                RecommendationState.POLICY_AUTHORIZED, null, expectedVersion);
        return new Decision(decisionId, RecommendationState.POLICY_AUTHORIZED, verdict,
                remaining);
    }

    /** The standing authorization of one proposal, when it has one. */
    @Transactional(readOnly = true)
    public Optional<ApprovalRepository.DecisionRow> standingAuthorization(
            UUID recommendationId) {
        return approvals.standingAuthorization(recommendationId);
    }

    /** Every decision about one proposal, newest first. */
    @Transactional(readOnly = true)
    public java.util.List<ApprovalRepository.DecisionRow> history(UUID recommendationId) {
        return approvals.history(recommendationId);
    }

    /**
     * The proposal must be one that can still be decided by this actor.
     *
     * <p>Scope is checked against the store the subject sits on rather than
     * against the organization, so an operator with one store's grant cannot
     * approve a price change on another's.
     *
     * <p>The grant required depends on the action. Somebody who may approve a
     * price change has not thereby been given authority over advertising spend,
     * and a single grant covering both would make that distinction unsayable.
     */
    private RecommendationView requireDecidable(AuthenticatedActor actor,
                                                UUID recommendationId, long expectedVersion) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, approvalScopeOf(proposal),
                ResourceScope.store(proposal.storeId()));
        if (proposal.version() != expectedVersion) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        if (proposal.state() != RecommendationState.READY_FOR_REVIEW) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!proposal.actionKind().writeCapable()) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!proposal.validUntil().isAfter(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.RECOMMENDATION_STALE);
        }
        return proposal;
    }

    /**
     * The grant this action's approval requires.
     *
     * <p>Two write-capable actions, two grants, and no default. A new
     * write-capable action added without a grant of its own would not silently
     * inherit one — the switch would not compile.
     */
    private static ActionScopeCode approvalScopeOf(RecommendationView proposal) {
        return switch (proposal.actionKind()) {
            case PRICE_CHANGE -> ActionScopeCode.PRICE_CHANGE_APPROVE;
            case AD_BID_CHANGE -> ActionScopeCode.AD_BID_CHANGE_APPROVE;
            case RESOLVE_MAPPING, RESTOCK_REVIEW, LISTING_CONTENT_REVIEW,
                 ADVERTISING_REVIEW, COST_DATA_REVIEW ->
                    // Not decidable at all. requireDecidable refuses these on
                    // write-capability grounds; naming a grant here would be
                    // describing an authority nobody can hold.
                    throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        };
    }

    private UUID record(RecommendationView proposal, String decision, UUID decidedByUserId,
                        UUID policyAuthorizationId, Instant authenticatedAt,
                        boolean stepUpSatisfied, String reason, Instant now) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        UUID decisionId = idGenerator.newId();
        approvals.insert(decisionId, proposal.organizationId(), proposal.id(), decision,
                decidedByUserId, policyAuthorizationId, authenticatedAt, stepUpSatisfied,
                proposal.entityVersionDigest(), now.plus(AUTHORIZATION_SCOPE), validReason,
                now, CorrelationId.current());
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW,
                decidedByUserId == null ? "policy-authorization" : decidedByUserId.toString(),
                AuditAction.APPROVAL_DECISION, ENTITY_TYPE, decisionId, null,
                Map.of(
                        "recommendationId", new FieldChange(null, proposal.id().toString()),
                        "decision", new FieldChange(null, decision),
                        "entityVersionDigest",
                        new FieldChange(null, proposal.entityVersionDigest()),
                        "stepUpSatisfied", new FieldChange(null,
                                Boolean.toString(stepUpSatisfied))),
                validReason, null));
        return decisionId;
    }

    /** The change the verdict measured, as the consuming function expects it. */
    private static BigDecimal changeRateOf(GuardrailVerdict verdict) {
        String rate = verdict.detail().get("changeRate");
        if (rate == null) {
            throw OperationRejectedException.of(ErrorCode.POLICY_AUTHORIZATION_UNUSABLE);
        }
        return new BigDecimal(rate).abs();
    }

    /**
     * What a decision produced.
     *
     * @param decisionId the recorded decision
     * @param state the state the proposal now stands in
     * @param verdict the guardrail verdict it rested on, or {@code null} for a rejection
     * @param authorizationUsesRemaining uses left on the authorization spent, or {@code null}
     */
    public record Decision(UUID decisionId, RecommendationState state, GuardrailVerdict verdict,
                           Integer authorizationUsesRemaining) {
    }
}
