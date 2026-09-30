package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreStandingRepository;
import com.mimococo.marketops.shared.ConsoleApi;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The store's own standing on the marketplace: its subscription, penalty balance and localization,
 * its ratings with the marketplace's own verdict on each, and its warehouses. It describes the
 * store rather than a listing; a store that stops being able to ship stops selling everything.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/stores")
class StoreStandingConsoleController {

    private final StoreStandingRepository standing;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;

    StoreStandingConsoleController(StoreStandingRepository standing, BusinessAuthorization authorization,
                                   MetadataAuditRecorder audit) {
        this.standing = standing;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** The newest rating summary, ratings and warehouses; each part absent until it was collected. */
    @GetMapping("/{storeId}/standing")
    @Transactional
    StoreStanding standing(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        Summary summary = standing.summary(actor.organizationId(), storeId)
                .map(found -> new Summary(found.premium(), found.premiumPlus(), found.penaltyScoreExceeded(),
                        found.localizationCalculatedAt(), text(found.localizationPercentage()), found.observedAt()))
                .orElse(null);
        List<StoreStandingRepository.Rating> ratingRows = standing.ratings(actor.organizationId(), storeId);
        List<StoreStandingRepository.Warehouse> warehouseRows = standing.warehouses(actor.organizationId(), storeId);
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATING_FACTS,
                actor.userId().toString(), AuditAction.READ, "store_standing", storeId, null,
                Map.of(), "standing", null));
        return new StoreStanding(storeId, summary,
                ratingRows.stream().map(row -> new Rating(row.ratingKey(), row.groupName(), row.ratingName(),
                        row.valueType(), row.direction(), row.status(), text(row.currentValue()),
                        text(row.pastValue()), row.changeDirection(), row.changeMeaning())).toList(),
                ratingRows.isEmpty() ? null : ratingRows.getFirst().observedAt(),
                warehouseRows.stream().map(row -> new Warehouse(row.nativeWarehouseKey(), row.warehouseType(),
                        row.status(), row.rfbs(), row.express(), row.largeGoods(), row.autoAssembly(),
                        row.firstMileKind(), row.workingDayCount(), row.handoverMinutes(), row.assemblyMinutes(),
                        row.postingsLimit(), row.minPostingsLimit(), row.hasPostingsLimit(), row.pausedAt(),
                        row.sourceUpdatedAt(), row.timeZone())).toList(),
                warehouseRows.isEmpty() ? null : warehouseRows.getFirst().observedAt());
    }

    /** A value as decimal text without trailing zeros, or {@code null}. */
    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * The store's standing.
     *
     * @param summary {@code null} until a rating summary was collected
     * @param ratingsAt when the marketplace considered the ratings true, or {@code null} without any
     * @param warehousesAt when the marketplace considered the warehouses true, or {@code null} without any
     */
    record StoreStanding(UUID storeId, Summary summary, List<Rating> ratings, Instant ratingsAt,
                         List<Warehouse> warehouses, Instant warehousesAt) {
    }

    /**
     * Subscription, penalty balance and localization.
     *
     * @param localizationCalculatedAt {@code null} when the store sold nothing in the last 14 days
     */
    record Summary(Boolean premium, Boolean premiumPlus, Boolean penaltyScoreExceeded,
                   Instant localizationCalculatedAt, String localizationPercentage, Instant observedAt) {
    }

    /** One rating with the marketplace's status; values as stated, as decimal text. */
    record Rating(String ratingKey, String groupName, String ratingName, String valueType, String direction,
                  String status, String currentValue, String pastValue, String changeDirection,
                  String changeMeaning) {
    }

    /** One warehouse; its name, address and phone are never read. */
    record Warehouse(String nativeWarehouseKey, String warehouseType, String status, Boolean rfbs, Boolean express,
                     Boolean largeGoods, Boolean autoAssembly, String firstMileKind, Integer workingDayCount,
                     Integer handoverMinutes, Integer assemblyMinutes, Integer postingsLimit,
                     Integer minPostingsLimit, Boolean hasPostingsLimit, Instant pausedAt, Instant sourceUpdatedAt,
                     String timeZone) {
    }
}
