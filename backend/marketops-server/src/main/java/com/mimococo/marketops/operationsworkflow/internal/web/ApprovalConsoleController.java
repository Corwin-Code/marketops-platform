package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.marketplaceintegration.AdBidCommandGateway;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.AdBidImpactPreview;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.ImpactPreview;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.application.ApprovalService;
import com.mimococo.marketops.operationsworkflow.internal.application.ExecutionService;
import com.mimococo.marketops.operationsworkflow.internal.application.GuardrailService;
import com.mimococo.marketops.operationsworkflow.internal.application.RecommendationService;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.ApprovalRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.GuardrailRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a person decides whether a real price changes.
 *
 * <p>The preview and the decision run the same deterministic guardrail against
 * the same canonical values, so what a reviewer sees is what will be checked.
 * A preview built from different numbers than the gate would be a way to approve
 * something that then refuses — or worse, something the gate would have refused.
 *
 * <p>Every route here needs the price-approval grant, which is a step-up action.
 * Holding the grant is not enough: the person must have authenticated recently
 * enough for their identity provider's recorded maximum authentication age.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/workflow")
class ApprovalConsoleController {

    private final RecommendationService recommendations;
    private final GuardrailService guardrails;
    private final ApprovalService approvals;
    private final ExecutionService execution;
    private final AdBidCommandGateway adCommands;
    private final BusinessAuthorization authorization;
    private final com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy advertisingDisclosure;
    private final com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingHumanDecisionService advertisingHumans;

    ApprovalConsoleController(RecommendationService recommendations,
                              GuardrailService guardrails,
                              ApprovalService approvals,
                              ExecutionService execution,
                              AdBidCommandGateway adCommands,
                              BusinessAuthorization authorization,
                              com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy advertisingDisclosure,
                              com.mimococo.marketops.operationsworkflow.internal.application.AdvertisingHumanDecisionService advertisingHumans) {
        this.recommendations = recommendations;
        this.guardrails = guardrails;
        this.approvals = approvals;
        this.execution = execution;
        this.adCommands = adCommands;
        this.authorization = authorization;
        this.advertisingDisclosure = advertisingDisclosure;
        this.advertisingHumans = advertisingHumans;
    }

    /**
     * What the change would do, and whether it is currently allowed.
     *
     * <p>Recorded like every other evaluation. A preview is a question somebody
     * asked about a real price, and the answer is worth keeping.
     */
    @PostMapping(value = "/recommendations/{recommendationId}/impact-preview",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ImpactPreview preview(AuthenticatedActor actor, @PathVariable UUID recommendationId) {
        RecommendationView proposal = recommendations.require(recommendationId);
        if(proposal.actionKind()==ActionKind.AD_BID_CHANGE) throw com.mimococo.marketops.shared.OperationRejectedException
                .of(com.mimococo.marketops.shared.ErrorCode.ACTION_NOT_PERMITTED);
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(proposal.storeId()));
        requireAdvertisingDisclosure(actor, proposal);
        return guardrails.preview(proposal, null, GuardrailPurpose.IMPACT_PREVIEW);
    }

    /**
     * What changing this bid would do, and whether it is currently allowed.
     *
     * <p>A separate route from the price preview rather than a widened one. The
     * two answer different questions and an operator asking about a bid should
     * not receive a shape half of which is about margins.
     *
     * <p>The gate reasons are only meaningful once a command exists, because the
     * gate is a question about a command. Before then the list is empty, which
     * is not the same as "the gate would allow it" — the unresolved reasons and
     * the verdict are what speak before creation.
     */
    @PostMapping(value = "/recommendations/{recommendationId}/ad-bid-impact-preview",
            produces = MediaType.APPLICATION_JSON_VALUE)
    AdBidImpactPreview previewAdBidChange(AuthenticatedActor actor,
                                          @PathVariable UUID recommendationId) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, ActionScopeCode.ADVERTISING_VIEW,
                ResourceScope.store(proposal.storeId()));
        if (proposal.actionKind() != ActionKind.AD_BID_CHANGE) {
            throw com.mimococo.marketops.shared.OperationRejectedException.of(
                    com.mimococo.marketops.shared.ErrorCode.VALIDATION_FAILED);
        }
        requireAdvertisingDisclosure(actor, proposal);
        advertisingHumans.preparePreview(actor,recommendationId);
        AdBidImpactPreview preview =
                guardrails.previewAdBidChange(proposal, GuardrailPurpose.IMPACT_PREVIEW);
        List<String> gateReasons = adCommands.forRecommendation(recommendationId)
                .map(command -> adCommands.gateReasons(command.id()))
                .orElseGet(List::of);
        return new AdBidImpactPreview(preview.recommendationId(), preview.projection(),
                gateReasons, preview.unresolvedReasons(), preview.verdict(),preview.evidence());
    }

    /** A person decides the change may proceed. */
    @PostMapping(value = "/recommendations/{recommendationId}/approval",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApprovalService.Decision approve(AuthenticatedActor actor,
                                     @PathVariable UUID recommendationId,
                                     @Valid @RequestBody DecisionRequest request) {
        return approvals.approve(actor, recommendationId, request.reason(),
                request.expectedVersion());
    }

    /** A person decides the change may not proceed. */
    @PostMapping(value = "/recommendations/{recommendationId}/rejection",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApprovalService.Decision reject(AuthenticatedActor actor,
                                    @PathVariable UUID recommendationId,
                                    @Valid @RequestBody DecisionRequest request) {
        return approvals.reject(actor, recommendationId, request.reason(),
                request.expectedVersion());
    }

    /** Spend a bounded standing authorization instead of asking a person. */
    @PostMapping(value = "/recommendations/{recommendationId}/policy-authorization",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ApprovalService.Decision authorizeByPolicy(AuthenticatedActor actor,
                                               @PathVariable UUID recommendationId,
                                               @Valid @RequestBody DecisionRequest request) {
        return approvals.authorizeByPolicy(actor, recommendationId, request.reason(),
                request.expectedVersion());
    }

    /** Create the command for an authorized proposal. */
    @PostMapping(value = "/recommendations/{recommendationId}/command",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ExecutionService.Created createCommand(AuthenticatedActor actor,
                                           @PathVariable UUID recommendationId,
                                           @Valid @RequestBody VersionRequest request) {
        return execution.createCommand(actor, recommendationId, request.expectedVersion());
    }

    /** Every decision made about one proposal. */
    @GetMapping(value = "/recommendations/{recommendationId}/decisions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<ApprovalRepository.DecisionRow> decisions(AuthenticatedActor actor,
                                                   @PathVariable UUID recommendationId) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, proposal.actionKind()==ActionKind.AD_BID_CHANGE
                        ? ActionScopeCode.ADVERTISING_VIEW : ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(proposal.storeId()));
        requireAdvertisingDisclosure(actor, proposal);
        return approvals.history(recommendationId);
    }

    /** Every guardrail verdict about one proposal, newest first. */
    @GetMapping(value = "/recommendations/{recommendationId}/guardrail-evaluations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<GuardrailRepository.EvaluationRow> evaluations(
            AuthenticatedActor actor,
            @PathVariable UUID recommendationId,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, proposal.actionKind()==ActionKind.AD_BID_CHANGE
                        ? ActionScopeCode.ADVERTISING_VIEW : ActionScopeCode.EVIDENCE_VIEW,
                ResourceScope.store(proposal.storeId()));
        requireAdvertisingDisclosure(actor, proposal);
        return guardrails.history(recommendationId, limit);
    }

    @PostMapping(value = "/recommendations/{recommendationId}/endorsement",
            produces = MediaType.APPLICATION_JSON_VALUE)
    RecommendationView endorse(AuthenticatedActor actor, @PathVariable UUID recommendationId,
                                @Valid @RequestBody DecisionRequest request) {
        return advertisingHumans.endorse(actor, recommendationId, request.expectedVersion(), request.reason());
    }

    private void requireAdvertisingDisclosure(AuthenticatedActor actor, RecommendationView proposal) {
        if (proposal.actionKind() == ActionKind.AD_BID_CHANGE) {
            advertisingHumans.requireReviewEvidence(actor,proposal.id());
        }
    }

    record DecisionRequest(@NotBlank String reason, @NotNull Long expectedVersion) {
    }

    record VersionRequest(@NotNull Long expectedVersion) {
    }
}
