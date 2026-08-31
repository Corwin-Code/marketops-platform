package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.UUID;

/**
 * What one internal warehouse last reported for one internal variant.
 *
 * <p>{@link OperatingFactQuery#internalStock} sums across every warehouse, which
 * answers "how much do we hold" but cannot answer "is the platform view of this
 * store the same goods as this warehouse holds". Deduplication needs the
 * warehouse, so it is exposed rather than reconstructed by a second reader of
 * the same table.
 *
 * @param warehouseId the warehouse
 * @param quantityOnHand units physically present
 * @param quantityReserved units committed, or {@code null} when unrecorded
 * @param observedAt when it was true, or {@code null} when unknown
 * @param provenanceId the fact this came from
 */
public record WarehouseStockSnapshot(
        UUID warehouseId, int quantityOnHand, Integer quantityReserved,
        Instant observedAt, UUID provenanceId) {
}
