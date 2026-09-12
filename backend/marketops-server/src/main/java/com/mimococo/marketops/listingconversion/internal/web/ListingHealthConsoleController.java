package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.listingconversion.ConversionMeasurementView;
import com.mimococo.marketops.listingconversion.EvidencePath;
import com.mimococo.marketops.listingconversion.ListingHealthView;
import com.mimococo.marketops.listingconversion.internal.application.ConversionMeasurementService;
import com.mimococo.marketops.listingconversion.internal.application.ListingFactIntakeService;
import com.mimococo.marketops.listingconversion.internal.application.ListingHealthService;
import com.mimococo.marketops.listingconversion.internal.application.ListingScopeAuthorization;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listing Health and conversion measurement, and the manual fact intake that
 * feeds them.
 *
 * <p>The queue is built from the stores the actor may see; an empty grant is an
 * empty queue. Every recorded fact keeps its own observation and acquisition
 * time, and a recompute is an ordinary recalculation of the listing, never a
 * new authority over it.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/listing/health")
class ListingHealthConsoleController {

    private final ListingHealthService health;
    private final ConversionMeasurementService measurements;
    private final ListingFactIntakeService facts;
    private final ListingScopeAuthorization listings;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;

    ListingHealthConsoleController(ListingHealthService health, ConversionMeasurementService measurements,
                                   ListingFactIntakeService facts, ListingScopeAuthorization listings,
                                   BusinessAuthorization authorization, MetadataAuditRecorder audit) {
        this.health = health;
        this.measurements = measurements;
        this.facts = facts;
        this.listings = listings;
        this.authorization = authorization;
        this.audit = audit;
    }

    @Transactional
    @GetMapping(value = "/queue", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ListingHealthView> queue(AuthenticatedActor actor,
                                  @RequestParam(required = false) String necessaryState,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<UUID> stores = authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CONVERSION_VIEW);
        List<ListingHealthView> result = health.queue(actor.organizationId(), stores, necessaryState, limit);
        auditRead(actor, "lc-health-queue", actor.organizationId(), "queue");
        return result;
    }

    @Transactional
    @GetMapping(value = "/listings/{listingId}", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> listing(AuthenticatedActor actor, @PathVariable UUID listingId,
                                @RequestParam(defaultValue = "12") @Min(1) @Max(100) int measurementLimit) {
        ListingScopeAuthorization.ListingScope scope =
                listings.require(actor, listingId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("listingId", scope.listingId());
        result.put("storeId", scope.storeId());
        result.put("platformCode", scope.platformCode());
        result.put("nativeListingKey", scope.nativeListingKey());
        result.put("health", health.latest(listingId).orElse(null));
        result.put("measurements", measurements.history(listingId, measurementLimit));
        auditRead(actor, "lc-listing-health", listingId, "listing");
        return result;
    }

    @Transactional
    @GetMapping(value = "/listings/{listingId}/measurements", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ConversionMeasurementView> history(AuthenticatedActor actor, @PathVariable UUID listingId,
                                            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int limit) {
        listings.require(actor, listingId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        List<ConversionMeasurementView> result = measurements.history(listingId, limit);
        auditRead(actor, "lc-conversion-measurement", listingId, "history");
        return result;
    }

    @PostMapping(value = "/listings/{listingId}/recompute", produces = MediaType.APPLICATION_JSON_VALUE)
    ListingHealthView recompute(AuthenticatedActor actor, @PathVariable UUID listingId) {
        listings.require(actor, listingId, ActionScopeCode.LISTING_ACTION_PREPARE);
        return health.recompute(listingId, "MANUAL", actor.userId());
    }

    @PostMapping(value = "/listings/{listingId}/measurements", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ConversionMeasurementView measure(AuthenticatedActor actor, @PathVariable UUID listingId,
                                      @Valid @RequestBody MeasureRequest request) {
        listings.require(actor, listingId, ActionScopeCode.LISTING_ACTION_PREPARE);
        return measurements.measure(listingId, request.windowStart(), request.windowEnd(), request.retentionDays(),
                request.evidencePath(), "MANUAL", actor.userId());
    }

    @PostMapping(value = "/listings/{listingId}/facts/description", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> recordDescription(AuthenticatedActor actor, @PathVariable UUID listingId,
                                        @Valid @RequestBody DescriptionFactRequest request) {
        return Map.of("observationId", facts.recordDescription(actor, listingId, request.text(),
                request.languageCode(), request.kizMarkedDeclared(), request.observedAt(), request.note()));
    }

    @PostMapping(value="/listings/{listingId}/facts/promotion",consumes=MediaType.APPLICATION_JSON_VALUE)
    Map<String,UUID> recordPromotion(AuthenticatedActor actor,@PathVariable UUID listingId,
                                    @Valid @RequestBody PromotionFactRequest request) {
        return Map.of("observationId",facts.recordPromotion(actor,listingId,request.declaration(),request.engagementKind(),request.nativePromotionKey(),
                request.participationState(),request.observedAt(),request.evidenceReference()));
    }

    @PostMapping(value = "/listings/{listingId}/facts/display", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> recordDisplay(AuthenticatedActor actor, @PathVariable UUID listingId,
                                    @Valid @RequestBody DisplayFactRequest request) {
        return Map.of("observationId", facts.recordDisplay(actor, listingId, request.displayState(),
                request.displayedText(), request.observedAt(), request.evidenceReference()));
    }

    @PostMapping(value = "/listings/{listingId}/facts/visit", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> recordVisit(AuthenticatedActor actor, @PathVariable UUID listingId,
                                  @Valid @RequestBody VisitFactRequest request) {
        return Map.of("visitId", facts.recordVisit(actor, listingId, request.variantId(), request.visitKey(),
                request.visitedAt(), request.sellable(), request.channel(), request.keyGroup()));
    }

    @PostMapping(value = "/listings/{listingId}/facts/purchase-link", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> linkPurchase(AuthenticatedActor actor, @PathVariable UUID listingId,
                                   @Valid @RequestBody PurchaseLinkRequest request) {
        return Map.of("linkId", facts.linkPurchase(actor, listingId, request.visitKey(), request.salesFactId(),
                request.basis()));
    }

    @PostMapping(value = "/listings/{listingId}/facts/official-summary", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> recordOfficialSummary(AuthenticatedActor actor, @PathVariable UUID listingId,
                                            @Valid @RequestBody OfficialSummaryRequest request) {
        return Map.of("observationId", facts.recordOfficialSummary(actor, listingId, request.periodStart(),
                request.periodEnd(), request.visits(), request.retainedPurchases(), request.label(),
                request.observedAt(), request.retentionDays()));
    }

    @PostMapping(value = "/listings/{listingId}/facts/feedback-theme", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> recordFeedbackTheme(AuthenticatedActor actor, @PathVariable UUID listingId,
                                          @Valid @RequestBody FeedbackThemeRequest request) {
        return Map.of("themeId", facts.recordFeedbackTheme(actor, listingId, request.periodStart(),
                request.periodEnd(), request.themeCode(), request.mentionCount(), request.observedAt()));
    }

    @PostMapping(value = "/listings/{listingId}/facts/measurement-coverage", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> recordMeasurementCoverage(AuthenticatedActor actor, @PathVariable UUID listingId,
                                               @Valid @RequestBody CoverageRequest request) {
        return Map.of("coverageId", facts.recordMeasurementCoverage(actor, listingId, request.evidencePath(),
                request.windowStart(), request.windowEnd(), request.retentionDays(), request.sourceCompleteThrough(),
                request.sourceReference(), request.expectedVisitRows(), request.expectedLinkRows(), request.summaryObservationId()));
    }

    record CoverageRequest(@NotNull EvidencePath evidencePath, @NotNull Instant windowStart,
                           @NotNull Instant windowEnd, @Min(7) @Max(30) int retentionDays,
                           @NotNull Instant sourceCompleteThrough, @NotBlank String sourceReference,
                           Long expectedVisitRows, Long expectedLinkRows, UUID summaryObservationId) { }

    private void auditRead(AuthenticatedActor actor, String entityType, UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION,
                actor.userId().toString(), AuditAction.READ, entityType, entityId, null, Map.of(), reason, null));
    }

    record MeasureRequest(@NotNull Instant windowStart, @NotNull Instant windowEnd,
                          @Min(7) @Max(30) int retentionDays, @NotNull EvidencePath evidencePath) {
    }

    record DescriptionFactRequest(@NotNull String text, @NotBlank String languageCode, Boolean kizMarkedDeclared,
                                  Instant observedAt, String note) {
    }

    record PromotionFactRequest(com.mimococo.marketops.listingconversion.PromotionTerms declaration,
                                @NotBlank String engagementKind,@NotBlank String nativePromotionKey,
                                @NotBlank String participationState,@NotNull Instant observedAt,@NotBlank String evidenceReference) { }

    record DisplayFactRequest(@NotBlank String displayState, String displayedText, Instant observedAt,
                              @NotBlank String evidenceReference) {
    }

    record VisitFactRequest(UUID variantId, @NotBlank String visitKey, @NotNull Instant visitedAt,
                            @NotBlank String sellable, @NotBlank String channel, String keyGroup) {
    }

    record PurchaseLinkRequest(@NotBlank String visitKey, @NotNull UUID salesFactId, @NotBlank String basis) {
    }

    record OfficialSummaryRequest(@NotNull Instant periodStart, @NotNull Instant periodEnd, Long visits,
                                  Long retainedPurchases, String label, Instant observedAt, @NotNull Integer retentionDays) {
    }

    record FeedbackThemeRequest(@NotNull Instant periodStart, @NotNull Instant periodEnd, @NotBlank String themeCode,
                                @Min(0) int mentionCount, Instant observedAt) {
    }
}
