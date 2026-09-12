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

    record ExposureScope(UUID organizationId, UUID storeId, List<UUID> listingVariantIds,
                         MetricWindow window, Instant asOf, long maximumVerificationAgeSeconds, long maximumPeriodEndAgeSeconds) {
        public ExposureScope { listingVariantIds=List.copyOf(listingVariantIds); }
    }

    /** Same-window retained-sales share; absent or unqualified members never become zero. */
    record Exposure(ExposureScope scope, BigDecimal share, List<String> gaps,
                    MetricValueView storeValue, List<MetricValueView> memberValues) {
        public Exposure { gaps=List.copyOf(gaps); memberValues=List.copyOf(memberValues); }
        public boolean available() { return share!=null && gaps.isEmpty(); }
        /** Compare the unrounded ratio by cross multiplication; display rounding cannot change a route. */
        public Boolean reaches(BigDecimal threshold) {
            Integer comparison=compareWith(threshold);
            return comparison==null?null:comparison>=0;
        }
        public Integer compareWith(BigDecimal threshold) {
            if (!available() || threshold==null) return null;
            BigDecimal numerator=memberValues.stream().map(MetricValueView::numericValue)
                    .reduce(BigDecimal.ZERO,BigDecimal::add);
            return numerator.compareTo(storeValue.numericValue().multiply(threshold));
        }
    }

    Projection project(Scope scope);
    Exposure exposure(ExposureScope scope);
}
