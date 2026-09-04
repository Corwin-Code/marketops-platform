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
import com.mimococo.marketops.marketplaceintegration.AdBidCommandRequest;
import com.mimococo.marketops.marketplaceintegration.AdBidCommandView;
import com.mimococo.marketops.marketplaceintegration.AdBidCommandGateway;
import com.mimococo.marketops.marketplaceintegration.PriceCommandGateway;
import com.mimococo.marketops.marketplaceintegration.PriceCommandRequest;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.PriceSnapshot;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionScope;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.GuardrailVerdict;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ApprovalRepository;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.shared.CorrelationId;
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
 *
 * <p>Two actions have a platform write behind them, and both come through here.
 * A second execution service for advertising would be a second place that
 * decides an approval may be spent, which is exactly the thing this product
 * does not have. What differs between them is what a command is made of, so the
 * dispatch below is on the action and everything shared stays shared.
 */
@Service
public class ExecutionService {

    static final String ENTITY_TYPE = "recommendation";

    private final RecommendationService recommendations;
    private final GuardrailService guardrails;
    private final ApprovalRepository approvals;
    private final PriceCommandGateway commands;
    private final AdBidCommandGateway adCommands;
    private final AdvertisingDecisionAuthority adDecisions;
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
                     AdBidCommandGateway adCommands,
                     AdvertisingDecisionAuthority adDecisions,
                     ListingIdentityDirectory listings,
                     OperatingFactQuery facts,
                     BusinessAuthorization authorization,
                     MetadataAuditRecorder auditRecorder,
                     Clock clock) {
        this.recommendations = recommendations;
        this.guardrails = guardrails;
        this.approvals = approvals;
        this.commands = commands;
        this.adCommands = adCommands;
        this.adDecisions = adDecisions;
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
        if (proposal.actionKind() == ActionKind.AD_BID_CHANGE) {
            return createAdBidCommand(actor, proposal, expectedVersion);
        }
        if (proposal.actionKind() != ActionKind.PRICE_CHANGE) {
            // Every other action is work a person performs. There is no command
            // to create, and saying so here is what stops one being invented.
            throw OperationRejectedException.of(ErrorCode.CAPABILITY_NOT_USABLE);
        }
        PriceChangeParameterContract.requireValid(proposal.proposedParameters());
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
        if (commands.priceChangeCapability(context.platformCode()).isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.CAPABILITY_NOT_USABLE);
        }

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
                recommendationId, expectedVersion, actor.userId()));

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
     * Create the advertising command for an authorized proposal.
     *
     * <p>The same shape as the price path and for the same reasons: authority
     * first, version second, approval third, then the execution guardrail, then
     * the thing that makes the command specific. What differs is the last part.
     * A bid change is made of a candidate, a reservation and a policy bundle,
     * and this service does not assemble any of them — it asks the advertising
     * module for the decision they compose and refuses if it is not complete.
     *
     * <p>The database checks every one of those again. This is here so an
     * operator learns why before pressing the button rather than after.
     */
    private Created createAdBidCommand(AuthenticatedActor actor, RecommendationView proposal,
                                       long expectedVersion) {
        AdBidChangeParameterContract.requireValid(proposal.proposedParameters());
        authorization.require(actor, ActionScopeCode.AD_BID_CHANGE_APPROVE,
                ResourceScope.store(proposal.storeId()));
        if (proposal.version() != expectedVersion) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        if (!proposal.state().authorized()) {
            throw OperationRejectedException.of(ErrorCode.APPROVAL_REQUIRED);
        }

        Optional<AdBidCommandView> existing = adCommands.forRecommendation(proposal.id());
        if (existing.isPresent()) {
            return new Created(existing.get().id(), null);
        }

        GuardrailVerdict verdict = guardrails.evaluate(proposal, null,
                GuardrailPurpose.EXECUTION);
        if (!verdict.passed()) {
            throw OperationRejectedException.of(ErrorCode.GUARDRAIL_BLOCKED);
        }

        AdvertisingDecisionScope scope = adDecisions.decisionScope(proposal.id())
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.CAPABILITY_NOT_USABLE));
        if (!scope.candidateId().equals(
                AdBidChangeParameterContract.candidateId(proposal.proposedParameters()))
                || scope.targetBidAmount().compareTo(
                        AdBidChangeParameterContract.targetBid(proposal.proposedParameters())) != 0
                || !scope.direction().equals(
                        AdBidChangeParameterContract.direction(proposal.proposedParameters()))) {
            // The approved parameters and the resolved decision describe
            // different changes. Neither is authoritative on its own, so
            // neither is used.
            throw OperationRejectedException.of(ErrorCode.RECOMMENDATION_STALE);
        }

        // The reservation is taken here and nowhere earlier. Until this point
        // the proposal was a decision somebody might make; from this point it is
        // an intervention that stops anything else touching the same product
        // variants and consumes aggregate exposure. Reserving when the proposal
        // was created would have made every unactioned case in the queue look
        // like a live intervention.
        UUID reservationId = adDecisions
                .reserveForExecution(proposal.id(), CorrelationId.current())
                .orElseThrow(() -> OperationRejectedException.of(
                        ErrorCode.CONCURRENT_INTERVENTION));

        UUID commandId = adCommands.submit(new AdBidCommandRequest(
                proposal.id(), expectedVersion, actor.userId(),
                reservationId, scope.bundleId(), scope.approvalExpiresAt()));

        recommendations.transition(actor.userId().toString(), proposal.id(),
                RecommendationState.COMMAND_CREATED, null, expectedVersion);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.COMMAND_TRANSITION, ENTITY_TYPE, proposal.id(), null,
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
