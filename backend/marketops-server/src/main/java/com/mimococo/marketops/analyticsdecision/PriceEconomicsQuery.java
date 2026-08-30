package com.mimococo.marketops.analyticsdecision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only port for current commercial projection and feed-freshness authority. */
public interface PriceEconomicsQuery {

    /** Resolve exactly one profile for an already known listing scope. */
    PriceEconomicsResolution resolveProfile(UUID organizationId,
                                             String platformCode,
                                             UUID marketplaceAccountId,
                                             UUID storeId,
                                             String fulfillmentModeCode,
                                             Instant at);

    /** Active fulfilment declarations; zero or many is ambiguous for a periodic metric. */
    List<String> activeFulfillmentModes(UUID storeId, Instant at);

    /** Latest verified, append-only source watermark for every required decision feed. */
    DecisionFreshness decisionFreshness(UUID organizationId,
                                        String platformCode,
                                        UUID marketplaceAccountId,
                                        UUID storeId,
                                        Instant at);
}
