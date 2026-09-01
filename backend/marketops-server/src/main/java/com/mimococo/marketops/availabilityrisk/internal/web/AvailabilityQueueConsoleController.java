package com.mimococo.marketops.availabilityrisk.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
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
    private final MetadataAuditRecorder audit;

    AvailabilityQueueConsoleController(AvailabilityRiskQuery risks,
                                       BusinessAuthorization authorization,
                                       MetadataAuditRecorder audit) {
        this.risks = risks;
        this.authorization = authorization;
        this.audit = audit;
    }

    /**
     * The queue, most urgent first.
     *
     * <p>{@code lane} filters rather than reorders. An operator narrowing to
     * CRITICAL is asking a different question, not asking for the same list
     * sorted differently.
     */
    @GetMapping("/queue")
    @Transactional
    List<AvailabilityCardView> queue(AuthenticatedActor actor,
                                     @RequestParam(required = false) String lane,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "0") int offset) {
        List<UUID> stores =
                authorization.permittedStoreIds(actor, ActionScopeCode.AVAILABILITY_VIEW);
        List<UUID> products = authorization.permittedProductVariantIds(
                actor, ActionScopeCode.AVAILABILITY_VIEW);
        List<AvailabilityCardView> result =
                risks.queue(actor.organizationId(), stores, products, lane, limit, offset);
        auditRead(actor, "availability_queue", actor.organizationId(), "queue");
        return result;
    }

    /** One grouped card with every child, factor and window behind it. */
    @GetMapping("/cards/{productVariantId}")
    @Transactional
    AvailabilityCardView card(AuthenticatedActor actor, @PathVariable UUID productVariantId) {
        authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW,
                ResourceScope.productVariant(productVariantId));
        List<UUID> stores = authorization.permittedStoreIds(
                actor, ActionScopeCode.AVAILABILITY_VIEW);
        List<UUID> products = authorization.permittedProductVariantIds(
                actor, ActionScopeCode.AVAILABILITY_VIEW);
        AvailabilityCardView result = risks.card(
                        actor.organizationId(), productVariantId, stores, products)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        auditRead(actor, "availability_card", productVariantId, "card");
        return result;
    }

    private void auditRead(AuthenticatedActor actor, String entityType,
                           UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.AVAILABILITY_RISK,
                actor.userId().toString(), AuditAction.READ, entityType, entityId, null,
                Map.of(), reason, null));
    }
}
