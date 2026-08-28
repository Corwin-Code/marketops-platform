package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.UUID;

/**
 * What the company itself holds of one internal variant.
 *
 * <p>This is a separate fact from marketplace availability and is never merged
 * with it. A listing that is out of stock on a marketplace while the warehouse
 * is full is a different problem from one where there is nothing to send.
 *
 * @param observedAt when the quantity was true, or {@code null} when unknown
 * @param quantityOnHand units held
 * @param quantityReserved units reserved, or {@code null} when unrecorded
 * @param provenanceId where the quantity came from, or {@code null}
 */
public record InternalStockSnapshot(
        Instant observedAt,
        int quantityOnHand,
        Integer quantityReserved,
        UUID provenanceId) {

    /** An answer nothing contributed to. */
    public static InternalStockSnapshot absent() {
        return new InternalStockSnapshot(null, 0, null, null);
    }

    /** Whether a quantity was actually recorded. */
    public boolean available() {
        return provenanceId != null;
    }
}
