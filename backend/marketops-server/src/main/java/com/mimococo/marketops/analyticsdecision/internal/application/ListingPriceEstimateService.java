package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.ListingPriceEstimateQuery;
import com.mimococo.marketops.analyticsdecision.ListingUnitEconomics;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.internal.config.AnalyticsProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Unit economics at an arbitrary buyer price, from the inputs the store diagnosis reads. */
@Service
class ListingPriceEstimateService implements ListingPriceEstimateQuery {

    private final ListingEconomicsInputs inputs;
    private final PriceEconomicsQuery economics;
    private final AnalyticsProperties properties;

    ListingPriceEstimateService(ListingEconomicsInputs inputs, PriceEconomicsQuery economics,
                                AnalyticsProperties properties) {
        this.inputs = inputs;
        this.economics = economics;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListingUnitEconomics.Estimate> estimateAt(UUID storeId, UUID listingVariantId, BigDecimal price,
                                                              String currencyCode, Instant asOf) {
        if (price == null || price.signum() <= 0) {
            return Optional.empty();
        }
        ListingEconomicsInputs.Inputs read = inputs.of(listingVariantId,
                economics.activeFulfillmentModes(storeId, asOf), asOf);
        if (read.terms() == null || read.cost() == null
                || !read.cost().unitCost().currencyCode().equals(currencyCode)) {
            return Optional.empty();
        }
        return Optional.of(ListingUnitEconomics.estimate(price, read.terms(), read.cost().unitCost().amount(),
                properties.getThresholds().getMinimumUnitMarginRate()));
    }
}
