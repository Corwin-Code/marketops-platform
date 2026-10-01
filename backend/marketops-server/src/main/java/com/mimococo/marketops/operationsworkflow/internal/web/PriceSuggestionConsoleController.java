package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.application.PriceDecisionService;
import com.mimococo.marketops.operationsworkflow.internal.application.PriceSuggestionService;
import com.mimococo.marketops.operationsworkflow.internal.application.RecommendationService;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PriceDecisionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
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
 * Price suggestions (P8): proposing them from the newest findings, and recording what a person did
 * with one in the marketplace's back office while the platform's own writes are off.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/workflow")
class PriceSuggestionConsoleController {

    private final PriceSuggestionService suggestions;
    private final PriceDecisionService decisions;
    private final RecommendationService recommendations;
    private final BusinessAuthorization authorization;

    PriceSuggestionConsoleController(PriceSuggestionService suggestions, PriceDecisionService decisions,
                                     RecommendationService recommendations, BusinessAuthorization authorization) {
        this.suggestions = suggestions;
        this.decisions = decisions;
        this.recommendations = recommendations;
        this.authorization = authorization;
    }

    /** Suggest prices from the store's newest seven-day findings now; the scheduler does it after a recalculation. */
    @PostMapping(value = "/stores/{storeId}/price-suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
    PriceSuggestionService.Result generate(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.RECOMMENDATION_MANAGE, ResourceScope.store(storeId));
        return suggestions.generate(actor.organizationId(), storeId);
    }

    /** Record that a suggestion was applied by hand in the back office, or not applied. */
    @PostMapping(value = "/recommendations/{recommendationId}/price-decision",
            produces = MediaType.APPLICATION_JSON_VALUE)
    DecisionRecorded decide(AuthenticatedActor actor, @PathVariable UUID recommendationId,
                            @Valid @RequestBody DecisionRequest request) {
        RecommendationView proposal = recommendations.require(recommendationId);
        authorization.require(actor, ActionScopeCode.RECOMMENDATION_MANAGE, ResourceScope.store(proposal.storeId()));
        return new DecisionRecorded(decisions.decide(actor.organizationId(), actor.userId(), recommendationId,
                request.decision(), request.appliedPrice(), request.note(), request.expectedVersion()));
    }

    /** The store's recorded price decisions, newest first, optionally about one listing variant. */
    @GetMapping(value = "/stores/{storeId}/price-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
    List<DecisionView> list(AuthenticatedActor actor, @PathVariable UUID storeId,
                            @RequestParam(required = false) UUID subjectId,
                            @RequestParam(required = false, defaultValue = "50") int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        return decisions.list(actor.organizationId(), storeId, subjectId, limit).stream()
                .map(DecisionView::of).toList();
    }

    /**
     * One decision.
     *
     * @param decision APPLIED_IN_SELLER_OFFICE or NOT_APPLIED
     * @param appliedPrice the buyer price set in the back office; only when applied
     * @param expectedVersion the version of the suggestion the person read
     */
    record DecisionRequest(@NotBlank String decision, BigDecimal appliedPrice, @Size(max = 500) String note,
                           long expectedVersion) {
    }

    /** The recorded decision's identifier. */
    record DecisionRecorded(UUID decisionId) {
    }

    /** One recorded decision; the price as decimal text. */
    record DecisionView(UUID decisionId, UUID recommendationId, UUID subjectId, String decision,
                        String appliedPrice, String currencyCode, String note, Instant decidedAt) {

        static DecisionView of(PriceDecisionRepository.Decision decision) {
            return new DecisionView(decision.id(), decision.recommendationId(), decision.listingVariantId(),
                    decision.decision(),
                    decision.appliedPrice() == null ? null : decision.appliedPrice().stripTrailingZeros().toPlainString(),
                    decision.currencyCode(), decision.note(), decision.decidedAt());
        }
    }
}
