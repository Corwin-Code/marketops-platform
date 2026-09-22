package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The launch allowances of one organization as the Owner maintains them: every
 * version per scope and axis, with what is occupied now and the room left.
 *
 * <p>Decimals travel as plain strings so no limit, reserve or occupied value is
 * rounded by a JSON number on the way to the screen.
 *
 * @param asOf when occupancy was evaluated
 * @param canPublishOrganization whether the caller holds the Owner grant for
 *        organization and platform scopes
 * @param stores stores the caller may see, for choosing a store scope
 * @param platforms platforms a platform scope may name
 * @param reservePolicies the disposal reserve each current calibration package whose scope the caller
 *        may see accepts per axis
 * @param scopeOccupancy what each scope the caller may publish to has occupied now, per axis
 * @param allowances every allowance row visible to the caller, newest version first per scope and axis
 */
public record AllowanceMaintenanceView(Instant asOf, boolean canPublishOrganization, List<StoreOption> stores,
                                       List<String> platforms, List<ReservePolicy> reservePolicies,
                                       List<ScopeOccupancy> scopeOccupancy, List<Allowance> allowances) {

    public AllowanceMaintenanceView {
        stores = List.copyOf(stores);
        platforms = List.copyOf(platforms);
        reservePolicies = List.copyOf(reservePolicies);
        scopeOccupancy = List.copyOf(scopeOccupancy);
        allowances = List.copyOf(allowances);
    }

    /** One store a store-scoped allowance may name. */
    public record StoreOption(UUID storeId, String code, String displayName, String platformCode, String currencyCode,
                              boolean canPublish) {
    }

    /**
     * The ALLOWANCE_RESERVE one current calibration package accepts for one axis.
     * A launch governed by that package is refused when the allowance keeps less.
     *
     * @param reserveValue the value as the package stores it; null when it stores a JSON null, and a
     *        launch the package governs then sees RESERVE_UNRESOLVED
     */
    public record ReservePolicy(UUID packageId, String packageCode, int packageVersion, String purposeCode,
                                String scopeKind, String platformCode, UUID storeId, String storePlatformCode,
                                String axisCode, String reserveValue) {
    }

    /**
     * What one scope has occupied now on one axis, counted the way the launch check
     * counts it whichever allowance row the occupations were taken under. A new
     * version of that scope and axis inherits it, so its headroom can be previewed
     * even when no row exists yet.
     *
     * @param unitCode the unit publishing would derive for the scope and axis
     */
    public record ScopeOccupancy(String scopeKind, String platformCode, UUID storeId, String axisCode,
                                 String unitCode, String occupiedValue, boolean unresolved, int liveOccupations) {
    }

    /**
     * One allowance version.
     *
     * @param lifecycle CURRENT, SCHEDULED, ENDED or RETIRED at {@code asOf}
     * @param occupiedValue computed only for CURRENT and SCHEDULED rows, the same way the launch projection counts it
     * @param headroom limit - reserve - occupied; may be negative
     */
    public record Allowance(UUID id, int version, String axisCode, String scopeKind, String platformCode, UUID storeId,
                            String storeName, String unitCode, String limitValue, String reserveValue,
                            String occupiedValue, String headroom, Boolean occupancyUnresolved,
                            Integer liveOccupations, String lifecycle, Instant effectiveFrom, Instant effectiveTo,
                            Instant publishedAt, UUID publishedByUserId, String publishedByName,
                            String evidenceReference, String publishReason, UUID supersedesAllowanceId,
                            UUID supersededByAllowanceId, Instant retiredAt, String retiredByName,
                            String retireReason, boolean canManage) {
    }

    /** What a publication did, and whether its reserve is below an accepted policy. */
    public record PublishResult(UUID allowanceId, int allowanceVersion, String unitCode, Instant effectiveFrom,
                                List<UUID> endedAllowanceIds, List<UUID> retiredAllowanceIds,
                                List<ReserveWarning> reserveWarnings) {
        public PublishResult {
            endedAllowanceIds = List.copyOf(endedAllowanceIds);
            retiredAllowanceIds = List.copyOf(retiredAllowanceIds);
            reserveWarnings = List.copyOf(reserveWarnings);
        }
    }

    /** A current package whose accepted reserve for the axis is above the published one. */
    public record ReserveWarning(UUID packageId, String packageCode, String purposeCode, String axisCode,
                                 String acceptedReserve, String publishedReserve) {
    }
}
