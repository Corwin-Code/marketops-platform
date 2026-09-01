package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.UUID;

/**
 * Whether a source last said a listing variant could be bought.
 *
 * <p>Availability is two facts, not one: how many units there are, and whether
 * anybody may buy them. A listing with four hundred units and a compliance block
 * is unavailable, and a stockout queue that reads only the quantity would call
 * it healthy.
 *
 * @param observedAt when the source considered it true, or {@code null}
 * @param sellable {@code YES}, {@code NO} or {@code UNKNOWN} as the source stated it
 * @param blockedReason the source's own words for the block, or {@code null}
 * @param provenanceId the fact this came from, or {@code null}
 */
public record SellabilitySnapshot(
        Instant observedAt, String sellable, String blockedReason, UUID provenanceId) {

    /** An answer nothing contributed to. */
    public static SellabilitySnapshot absent() {
        return new SellabilitySnapshot(null, "UNKNOWN", null, null);
    }

    /** Whether a source actually stated a sellability. */
    public boolean present() {
        return provenanceId != null;
    }
}
