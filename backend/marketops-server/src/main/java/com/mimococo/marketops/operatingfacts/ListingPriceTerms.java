package com.mimococo.marketops.operatingfacts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The newest price of a listing variant with everything the marketplace stated
 * alongside it: the lowest competitor price, the seller's own unit cost and the
 * tariffs that apply to a sale at that price.
 *
 * <p>Every amount is in {@code currencyCode}; every absent value is
 * {@code null}, never zero. The competitor price is platform analytics
 * (evidence grade C): it explains a diagnosis and never drives a price change.
 *
 * @param buyerPrice the price a buyer is offered: the seller's promotion price when there is one,
 *        the selling price otherwise
 * @param vatRate the VAT rate the listing is sold at, contained in the price (0.05 = 5 %)
 * @param evidence what the answer was derived from
 */
public record ListingPriceTerms(
        UUID observationId,
        Instant observedAt,
        String currencyCode,
        BigDecimal buyerPrice,
        BigDecimal platformCompetitorMinPrice,
        String priceIndexNative,
        BigDecimal sellerCostPrice,
        BigDecimal salesCommissionPercentFbs,
        BigDecimal salesCommissionPercentFbo,
        BigDecimal fbsFirstMileMax,
        BigDecimal fbsDirectFlowMax,
        BigDecimal fbsLastMile,
        BigDecimal fboDirectFlowMax,
        BigDecimal fboLastMile,
        BigDecimal acquiringMax,
        BigDecimal vatRate,
        FactEvidence evidence) {
}
