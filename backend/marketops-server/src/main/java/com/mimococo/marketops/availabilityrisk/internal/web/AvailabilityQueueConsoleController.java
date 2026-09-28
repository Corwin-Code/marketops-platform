package com.mimococo.marketops.availabilityrisk.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.availabilityrisk.AvailabilityCardView;
import com.mimococo.marketops.availabilityrisk.AvailabilityQueuePage;
import com.mimococo.marketops.availabilityrisk.AvailabilityRiskQuery;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityQueryRepository;
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

    /** The most variants one picker lookup returns. */
    private static final int MAX_VARIANTS = 50;

    private final AvailabilityRiskQuery risks;
    private final AvailabilityQueryRepository queries;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;

    AvailabilityQueueConsoleController(AvailabilityRiskQuery risks,
                                       AvailabilityQueryRepository queries,
                                       BusinessAuthorization authorization,
                                       MetadataAuditRecorder audit) {
        this.risks = risks;
        this.queries = queries;
        this.authorization = authorization;
        this.audit = audit;
    }

    /**
     * One page of the queue, most urgent first, with the matching total.
     *
     * <p>{@code lane} filters rather than reorders. An operator narrowing to
     * CRITICAL is asking a different question, not asking for the same list
     * sorted differently. {@code q} narrows by SKU or variant name the same way.
     */
    @GetMapping("/queue")
    @Transactional
    AvailabilityQueuePage queue(AuthenticatedActor actor,
                                @RequestParam(required = false) String lane,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "50") int limit,
                                @RequestParam(defaultValue = "0") int offset) {
        List<UUID> stores =
                authorization.permittedStoreIds(actor, ActionScopeCode.AVAILABILITY_VIEW);
        List<UUID> products = authorization.permittedProductVariantIds(
                actor, ActionScopeCode.AVAILABILITY_VIEW);
        AvailabilityQueuePage result = risks.queue(actor.organizationId(), stores, products,
                blankToNull(lane), blankToNull(q), limit, offset);
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

    /**
     * Internal variants this person may name for one purpose, found by SKU or
     * name.
     *
     * <p>A picker helper, never an authorization: the list is narrowed to the
     * variants the caller's own grant for the purpose covers, and every write
     * that names a chosen variant is still authorized on its own. Catalogue
     * names are reference data, so the lookup is not journalled as a read of
     * risk.
     */
    @GetMapping("/variants")
    @Transactional(readOnly = true)
    List<AvailabilityQueryRepository.VariantRow> variants(
            AuthenticatedActor actor,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "VIEW") VariantPurpose purpose) {
        List<UUID> permitted = authorization.permittedProductVariantIds(actor, purpose.action);
        if (permitted.isEmpty()) {
            return List.of();
        }
        return queries.variants(actor.organizationId(), permitted.toArray(UUID[]::new),
                blankToNull(q), Math.clamp(limit, 1, MAX_VARIANTS));
    }

    /**
     * What a variant is being picked for, and so which grant narrows the list.
     *
     * <p>A closed set: the caller chooses which of its own grants to narrow by,
     * never an action this surface does not perform.
     */
    enum VariantPurpose {
        VIEW(ActionScopeCode.AVAILABILITY_VIEW),
        INBOUND_ATTEST(ActionScopeCode.INBOUND_ATTEST),
        SUPPLY_POLICY_MANAGE(ActionScopeCode.SUPPLY_POLICY_MANAGE);

        private final ActionScopeCode action;

        VariantPurpose(ActionScopeCode action) {
            this.action = action;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void auditRead(AuthenticatedActor actor, String entityType,
                           UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.AVAILABILITY_RISK,
                actor.userId().toString(), AuditAction.READ, entityType, entityId, null,
                Map.of(), reason, null));
    }
}
