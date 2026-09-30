package com.mimococo.marketops.productlisting;

import java.time.Instant;
import java.util.Objects;

/**
 * One marketplace promotion (Ozon: an action) as a store's promotion snapshot named it.
 *
 * @param nativePromotionKey the marketplace's own identifier of the promotion
 * @param endsAt when the marketplace says the promotion ends, or {@code null} when it did not say
 */
public record ObservedPromotion(String nativePromotionKey, Instant endsAt) {

    public ObservedPromotion {
        Objects.requireNonNull(nativePromotionKey, "nativePromotionKey");
    }
}
