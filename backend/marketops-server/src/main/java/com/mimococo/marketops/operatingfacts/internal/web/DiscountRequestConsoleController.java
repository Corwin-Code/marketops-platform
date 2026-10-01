package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.DiscountRequestRepository;
import com.mimococo.marketops.shared.ConsoleApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
 * Buyers' requests to buy a product at a lower price (P8): how many, in which states, the discount
 * they asked for, and which products. A request is the buyer telling the price they would pay;
 * deciding one happens in the marketplace's back office, never here.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/stores")
class DiscountRequestConsoleController {

    /** The most requests or SKUs one answer lists. */
    private static final int MAXIMUM_LIMIT = 100;

    /** How many of the most requested SKUs the store-wide answer lists. */
    private static final int ITEM_LIMIT = 10;

    private final DiscountRequestRepository requests;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;
    private final Clock clock;

    DiscountRequestConsoleController(DiscountRequestRepository requests, BusinessAuthorization authorization,
                                     MetadataAuditRecorder audit, Clock clock) {
        this.requests = requests;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The store's discount requests, or one listing variant's: counts, requests per month, the
     * newest requests and, store-wide, the SKUs asked about most.
     */
    @GetMapping("/{storeId}/discount-requests")
    @Transactional
    DiscountRequests list(AuthenticatedActor actor, @PathVariable UUID storeId,
                          @RequestParam(required = false) UUID subjectId,
                          @RequestParam(required = false, defaultValue = "20") int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        UUID organizationId = actor.organizationId();
        int bounded = Math.clamp(limit, 1, MAXIMUM_LIMIT);
        DiscountRequestRepository.Summary counts = requests.summary(organizationId, storeId, subjectId,
                clock.instant());
        List<Month> months = requests.months(organizationId, storeId, subjectId).stream()
                .map(month -> new Month(month.month(), month.requests())).toList();
        List<Item> items = subjectId != null ? List.of() : requests.items(organizationId, storeId, ITEM_LIMIT)
                .stream().map(item -> new Item(item.nativeItemKey(), item.listingVariantId(), item.productName(),
                        item.requests(), item.pending(), item.latestRequestedAt(),
                        text(item.medianDiscountPercent()))).toList();
        List<Request> rows = requests.requests(organizationId, storeId, subjectId, bounded).stream()
                .map(row -> new Request(row.nativeRequestKey(), row.listingVariantId(), row.nativeItemKey(),
                        row.productName(), row.status(), row.requestedAt(), row.moderatedAt(), row.expiresAt(),
                        row.currencyCode(), text(row.originalPrice()), text(row.requestedPrice()),
                        text(row.requestedDiscountPercent()), row.requestedQuantity(), text(row.approvedPrice()),
                        row.approvedQuantity(), row.autoModerated())).toList();
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATING_FACTS,
                actor.userId().toString(), AuditAction.READ, "discount_requests", storeId, null,
                Map.of(), "discount requests", null));
        return new DiscountRequests(storeId, subjectId, requests.observedAt(organizationId, storeId).orElse(null),
                new Summary(counts.total(), counts.pending(), counts.approved(), counts.declined(), counts.items(),
                        counts.itemsInCatalog(), counts.requestsInCatalog(), text(counts.medianDiscountPercent()),
                        counts.firstRequestedAt(), counts.latestRequestedAt(), counts.nextDeadline()),
                months, items, rows);
    }

    /** A value as decimal text without trailing zeros, or {@code null}. */
    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * The requests of a store or of one listing variant.
     *
     * @param subjectId the listing variant asked about, or {@code null} for the whole store
     * @param observedAt when the store's newest answer was observed; {@code null} before the first
     * @param items the SKUs asked about most, store-wide only
     * @param requests the newest requests first
     */
    record DiscountRequests(UUID storeId, UUID subjectId, Instant observedAt, Summary summary, List<Month> months,
                            List<Item> items, List<Request> requests) {
    }

    /**
     * Counts over the requests.
     *
     * @param itemsInCatalog SKUs the catalogue lists today
     * @param medianDiscountPercent the median discount buyers asked for, in percent, or {@code null}
     * @param nextDeadline the earliest time a new request must be decided by, while it is ahead
     */
    record Summary(int total, int pending, int approved, int declined, int items, int itemsInCatalog,
                   int requestsInCatalog, String medianDiscountPercent, Instant firstRequestedAt,
                   Instant latestRequestedAt, Instant nextDeadline) {
    }

    /** Requests made in one UTC month, {@code YYYY-MM}. */
    record Month(String month, int requests) {
    }

    /** One SKU buyers asked about; {@code subjectId} is {@code null} when the catalogue no longer lists it. */
    record Item(String nativeItemKey, UUID subjectId, String productName, int requests, int pending,
                Instant latestRequestedAt, String medianDiscountPercent) {
    }

    /**
     * One request in its newest state; amounts as decimal text in {@code currencyCode}.
     *
     * @param expiresAt for a new request, the time left to decide it
     */
    record Request(String requestKey, UUID subjectId, String nativeItemKey, String productName, String status,
                   Instant requestedAt, Instant moderatedAt, Instant expiresAt, String currencyCode,
                   String originalPrice, String requestedPrice, String requestedDiscountPercent,
                   Integer requestedQuantity, String approvedPrice, Integer approvedQuantity, Boolean autoModerated) {
    }
}
