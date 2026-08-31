package com.mimococo.marketops.availabilityrisk.internal.web;

import com.mimococo.marketops.availabilityrisk.AvailabilityCardView;
import com.mimococo.marketops.availabilityrisk.AvailabilityRiskQuery;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ConsoleApi;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Stockout and Availability queue.
 *
 * <p>Authorization happens here and the result narrows the read: the queue is
 * built from the stores this person may act on, and an empty grant produces an
 * empty queue rather than an unfiltered one. Frontend visibility is not
 * authorization, so nothing below trusts a parameter the caller supplied about
 * their own scope.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/availability")
class AvailabilityQueueConsoleController {

    private final AvailabilityRiskQuery risks;
    private final BusinessAuthorization authorization;

    AvailabilityQueueConsoleController(AvailabilityRiskQuery risks,
                                       BusinessAuthorization authorization) {
        this.risks = risks;
        this.authorization = authorization;
    }

    /**
     * The queue, most urgent first.
     *
     * <p>{@code lane} filters rather than reorders. An operator narrowing to
     * CRITICAL is asking a different question, not asking for the same list
     * sorted differently.
     */
    @GetMapping("/queue")
    List<AvailabilityCardView> queue(AuthenticatedActor actor,
                                     @RequestParam(required = false) String lane,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScope.organization(actor.organizationId()));
        List<UUID> stores =
                authorization.permittedStoreIds(actor, ActionScopeCode.AVAILABILITY_VIEW);
        return risks.queue(actor.organizationId(), stores, lane, limit, offset);
    }

    /** One grouped card with every child, factor and window behind it. */
    @GetMapping("/cards/{productVariantId}")
    AvailabilityCardView card(AuthenticatedActor actor, @PathVariable UUID productVariantId) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return risks.card(actor.organizationId(), productVariantId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
