package com.mimococo.marketops.productlisting;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where normalized listing observations are recorded.
 *
 * <p>The acquisition path owns Raw custody and normalization; this module owns
 * listing identity. The sink is the seam between them, so neither has to reach
 * into the other's tables and there is exactly one writer of listing identity.
 *
 * <p>Recording is idempotent on the source's own keys. Replaying stored evidence
 * moves the observation window and produces no new logical effect, which is what
 * makes a replay safe to run at any time.
 */
public interface ListingObservationSink {

    /**
     * Record observed listings and return the resulting listing-variant
     * identifiers, keyed by native listing key and native variant key.
     *
     * <p>Returning the identifiers is what lets the caller attach price, stock
     * and funnel facts to the same variants in the same pass, without a second
     * lookup that could resolve differently.
     */
    Map<String, Map<String, UUID>> record(List<ObservedListing> listings,
                                          Instant observedAt);
}
