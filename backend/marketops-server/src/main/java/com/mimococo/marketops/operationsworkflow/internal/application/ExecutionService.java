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
import com.mimococo.marketops.marketplaceintegration.PriceCommandGateway;
import com.mimococo.marketops.marketplaceintegration.PriceCommandRequest;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.PriceSnapshot;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailVerdict;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ApprovalRepository;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.Money;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turning an authorized proposal into a command the write path can execute.
 *
 * <p>This is where the deterministic guardrail runs for execution specifically.
 * The approval verdict is not reused: hours can pass between a decision and a
 * command, and the cooldown, the stock, the cost and the switches can all have
 * moved. The write gate itself requires an execution pass to exist, so this is
 * not an optional extra step but the one that makes the gate satisfiable.
 *
 * <p>Creating a command still makes no call. Every condition is evaluated again
 * inside the transaction that claims the command for a worker, so a switch
 * thrown between here and there closes the door.
 */
@Service
public class ExecutionService {

    static final String ENTITY_TYPE = "recommendation";

    private final RecommendationService recommendations;
    private final GuardrailService guardrails;
    private final ApprovalRepository approvals;
    private final PriceCommandGateway commands;
    private final ListingIdentityDirectory listings;
    private final OperatingFactQuery facts;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder auditRecorder;
    private final Clock clock;

    /** How many retriable failures a new command may absorb. */
    private static final int RETRY_BUDGET = 3;

    ExecutionService(RecommendationService recommendations,
                     GuardrailService guardrails,
                     ApprovalRepository approvals,
                     PriceCommandGateway commands,
                     ListingIdentityDirectory listings,
                     OperatingFactQuery facts,
                     BusinessAuthorization authorization,
                     MetadataAuditRecorder auditRecorder,
                     Clock clock) {
        this.recommendations = recommendations;
        this.guardrails = guardrails;
        this.approvals = approvals;
        this.commands = commands;
        this.listings = listings;
        this.facts = facts;
        this.authorization = authorization;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    /**
     * Create the command for an authorized proposal.
     *
     * <p>Idempotent: a proposal that already has a command returns it rather
     * than creating a second one, because two commands for one authorization
     * would be two licences to change the same price.
     */
    @Transactional
    public Created createCommand(AuthenticatedActor actor, UUID recommendationId,
                                 long expectedVersion) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, ActionScopeCode.PRICE_CHANGE_APPROVE,
                ResourceScope.store(proposal.storeId()));
        if (proposal.version() != expectedVersion) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        if (!proposal.state().authorized()) {
            throw OperationRejectedException.of(ErrorCode.APPROVAL_REQUIRED);
        }

        Optional<PriceCommandView> existing = commands.forRecommendation(recommendationId);
        if (existing.isPresent()) {
            return new Created(existing.get().id(), null);
        }

        Instant now = clock.instant();
        ApprovalRepository.DecisionRow decision = approvals
                .standingAuthorization(recommendationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.APPROVAL_REQUIRED));
        if (!decision.scopeExpiresAt().isAfter(now)) {
            throw OperationRejectedException.of(ErrorCode.RECOMMENDATION_STALE);
        }

        GuardrailVerdict verdict = guardrails.evaluate(proposal, null,
                GuardrailPurpose.EXECUTION);
        if (!verdict.passed()) {
            throw OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED);
        }

        ListingVariantContext context = listings
                .variantContext(proposal.subjectId(), now)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.MAPPING_UNRESOLVED));
        UUID capabilityId = commands.priceChangeCapability(context.platformCode())
                .orElseThrow(() -> OperationRejectedException.of(
                        ErrorCode.CAPABILITY_NOT_USABLE));

        PriceSnapshot priceNow = facts.latestPrice(proposal.subjectId(), now)
                .orElseThrow(() -> OperationRejectedException.of(
                        ErrorCode.METRIC_INPUT_UNAVAILABLE));
        Money priorPrice = priceNow.effectivePrice();
        if (priorPrice == null) {
            throw OperationRejectedException.of(ErrorCode.METRIC_INPUT_UNAVAILABLE);
        }
        Money targetPrice = targetPrice(proposal, priorPrice.currencyCode());
        if (targetPrice.amount().compareTo(priorPrice.amount()) == 0) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        UUID commandId = commands.submit(new PriceCommandRequest(
                proposal.organizationId(), recommendationId, decision.id(), proposal.storeId(),
                proposal.subjectId(), context.platformCode(), capabilityId, priorPrice,
                targetPrice, priceNow.observationId(), proposal.entityVersionDigest(),
                RETRY_BUDGET));

        recommendations.transition(actor.userId().toString(), recommendationId,
                RecommendationState.COMMAND_CREATED, null, expectedVersion);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.COMMAND_TRANSITION, ENTITY_TYPE, recommendationId, null,
                Map.of(
                        "commandId", new FieldChange(null, commandId.toString()),
                        "state", new FieldChange(proposal.state().name(), "COMMAND_CREATED"),
                        "guardrailEvaluationId", new FieldChange(null,
                                verdict.evaluationId().toString())),
                null, null));
        return new Created(commandId, verdict);
    }

    /**
     * The price the proposal asks for, in the currency the platform holds.
     *
     * <p>A proposal that names no price, or names one this product cannot read
     * as a number, is refused rather than defaulted. Every default here would be
     * a real price on a real marketplace.
     */
    private static Money targetPrice(RecommendationView proposal, String currencyCode) {
        String target = proposal.proposedParameters().get("targetPrice");
        if (target == null || target.isBlank()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        try {
            BigDecimal amount = new BigDecimal(target);
            if (amount.signum() <= 0) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            return Money.of(amount, currencyCode);
        } catch (NumberFormatException notANumber) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    /**
     * What creating a command produced.
     *
     * @param commandId the command
     * @param verdict the execution verdict it rests on, or {@code null} when the
     *                command already existed
     */
    public record Created(UUID commandId, GuardrailVerdict verdict) {
    }
}
