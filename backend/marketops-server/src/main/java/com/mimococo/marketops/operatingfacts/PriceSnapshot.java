package com.mimococo.marketops.operatingfacts;

import com.mimococo.marketops.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The most recent observed price state of one listing variant.
 *
 * <p>The three amounts are separate because a discount and a promotion are
 * different commercial facts. A guardrail comparing a proposed price against the
 * wrong one would authorise the wrong change, so the caller chooses which it
 * means rather than receiving a single blended number.
 *
 * @param observationId the observation this came from
 * @param observedAt when the marketplace considered it true
 * @param listPrice price before any discount, or {@code null}
 * @param sellingPrice price a buyer currently pays, or {@code null}
 * @param discountPrice discounted price, or {@code null}
 * @param promotionActive whether a promotion is running, as three-valued text
 * @param evidence what the answer was derived from
 */
public record PriceSnapshot(
        UUID observationId,
        Instant observedAt,
        Money listPrice,
        Money sellingPrice,
        Money discountPrice,
        String promotionActive,
        FactEvidence evidence) {

    /** The price a buyer pays, preferring the discounted amount when one exists. */
    public Money effectivePrice() {
        if (discountPrice != null) {
            return discountPrice;
        }
        return sellingPrice != null ? sellingPrice : listPrice;
    }
}
