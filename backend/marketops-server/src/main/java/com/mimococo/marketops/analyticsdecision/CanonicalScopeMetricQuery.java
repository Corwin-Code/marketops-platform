package com.mimococo.marketops.analyticsdecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read projection of exact-period canonical Metric values over a declared
 * finite scope. The owning Metric module performs the sum and weighted return
 * calculation. This neither proves native scope completeness nor certifies a
 * business protection; consumers must bind the scope and qualify the retained
 * confidence, definition and source evidence under their frozen method.
 */
public interface CanonicalScopeMetricQuery {
    enum ProfitBasis { OPERATIONAL, SETTLED }

    record Scope(UUID organizationId, UUID storeId, List<UUID> listingVariantIds,
                 MetricWindow window, Instant periodStart, Instant periodEnd, Instant asOf,
                 ProfitBasis profitBasis) {
        public Scope {
            listingVariantIds=List.copyOf(listingVariantIds);
        }
    }

    /** Components remain available for diagnosis even when a total is unresolved. */
    record Observation(BigDecimal value, String currencyCode, List<String> gaps,
                       List<MetricValueView> components) {
        public Observation {
            gaps=List.copyOf(gaps);
            components=List.copyOf(components);
        }
        public boolean available() { return value != null && gaps.isEmpty(); }
    }

    record Projection(Scope scope, Observation contributionProfit, Observation returnRate) { }

    Projection project(Scope scope);
}
