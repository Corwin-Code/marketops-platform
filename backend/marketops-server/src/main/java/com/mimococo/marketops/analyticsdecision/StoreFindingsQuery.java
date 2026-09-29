package com.mimococo.marketops.analyticsdecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the newest completed calculation of a store concluded about each of its listings, with the
 * identifiers of the findings and values behind it, so that a summary of the store can cite them.
 */
public interface StoreFindingsQuery {

    /**
     * The newest completed run of the store over the window: every listing it evaluated, the
     * findings that stand for it and triggered, and its values of the named metrics.
     */
    Optional<StoreRun> latest(UUID organizationId, UUID storeId, MetricWindow window, Set<MetricCode> metricCodes);

    /** One completed run. */
    record StoreRun(UUID runId, Instant periodStart, Instant periodEnd, List<ListingResult> listings) {

        public StoreRun {
            listings = List.copyOf(listings);
        }
    }

    /** One listing's triggered findings, in rule order, and metric values. */
    record ListingResult(UUID listingVariantId, List<Finding> findings, Map<MetricCode, Value> metrics) {

        public ListingResult {
            findings = List.copyOf(findings);
            metrics = Map.copyOf(metrics);
        }
    }

    /** One triggered finding and the values it compared. */
    record Finding(UUID findingId, String ruleCode, String severity, Map<String, String> detail) {

        public Finding {
            detail = Map.copyOf(detail);
        }
    }

    /** One metric value; {@code numericValue} is {@code null} unless the value is available. */
    record Value(UUID valueId, MetricCode metricCode, ValueState valueState, BigDecimal numericValue,
                 String currencyCode) {
    }
}
