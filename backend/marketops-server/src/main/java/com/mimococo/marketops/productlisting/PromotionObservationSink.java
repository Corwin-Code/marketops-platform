package com.mimococo.marketops.productlisting;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the identity of a store's marketplace promotions is recorded and resolved.
 *
 * <p>Like listing identity, this module owns it and normalization reaches it only through this
 * seam: a promotion snapshot records the promotions it names, and a record about one promotion
 * (its candidates, its participants) resolves the promotion it was asked about. Recording is
 * idempotent on the marketplace's own key, so replaying stored evidence changes nothing.
 */
public interface PromotionObservationSink {

    /** Record one promotion a store's snapshot named at an instant, and return its identifier. */
    UUID record(UUID organizationId, UUID storeId, ObservedPromotion promotion, Instant observedAt);

    /** The identifier of a promotion a store's snapshots recorded, by the marketplace's own key. */
    Optional<UUID> find(UUID storeId, String nativePromotionKey);
}
