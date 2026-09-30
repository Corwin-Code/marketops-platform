package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.OwnedResource;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.application.PromotionDecisionService;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PromotionDecisionRepository.DecisionRow;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Promotion decisions taken by hand in the marketplace back office: the newest per product and
 * promotion for anybody who may read the diagnosis, and recording one for whoever may decide.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/stores")
class PromotionDecisionConsoleController {

    private final PromotionDecisionService decisions;
    private final BusinessAuthorization authorization;

    PromotionDecisionConsoleController(PromotionDecisionService decisions, BusinessAuthorization authorization) {
        this.decisions = decisions;
        this.authorization = authorization;
    }

    /** The newest decision on every product of every promotion of the store. */
    @GetMapping(value = "/{storeId}/promotion-decisions", produces = MediaType.APPLICATION_JSON_VALUE)
    List<Decision> latest(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        return decisions.latest(actor.organizationId(), storeId).stream().map(Decision::of).toList();
    }

    /** Record that one product of a promotion was joined, skipped or left in the back office. */
    @PostMapping(value = "/{storeId}/promotions/{promotionId}/decisions", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Decision record(AuthenticatedActor actor, @PathVariable UUID storeId, @PathVariable UUID promotionId,
                    @Valid @RequestBody DecisionRequest request) {
        authorization.requireOwned(actor, ActionScopeCode.PROMOTION_DECISION_RECORD,
                new OwnedResource(OwnedResource.Kind.LISTING_VARIANT, request.listingVariantId(), storeId));
        return Decision.of(decisions.record(actor, storeId, promotionId, request.listingVariantId(),
                request.decision(), request.actionPrice(), request.currencyCode(), request.note()));
    }

    /**
     * One decision to record.
     *
     * @param decision JOINED, SKIPPED or LEFT
     * @param actionPrice the price set in the promotion, only for JOINED
     */
    record DecisionRequest(@NotNull UUID listingVariantId, @NotBlank String decision, BigDecimal actionPrice,
                           String currencyCode, String note) {
    }

    /** One recorded decision; the price is decimal text. */
    record Decision(UUID decisionId, UUID promotionId, UUID listingVariantId, String decision, String actionPrice,
                    String currencyCode, String note, UUID decidedByUserId, Instant decidedAt) {

        static Decision of(DecisionRow row) {
            return new Decision(row.decisionId(), row.promotionId(), row.listingVariantId(), row.decision(),
                    row.actionPrice() == null ? null : row.actionPrice().toPlainString(), row.currencyCode(),
                    row.note(), row.decidedByUserId(), row.decidedAt());
        }
    }
}
