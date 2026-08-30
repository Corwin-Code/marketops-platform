package com.mimococo.marketops.analyticsdecision.internal.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One thing a computed metric was derived from.
 *
 * <p>The kind matters as much as the identifier. A value derived from a cost
 * version and one derived from a marketplace fee are traceable to different
 * places, and an evidence panel that could not tell them apart would send an
 * operator looking in the wrong system.
 *
 * @param kind what sort of input this is
 * @param referenceId identifier of the input
 */
public record MetricInput(Kind kind, UUID referenceId) {

    public MetricInput {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(referenceId, "referenceId");
    }

    /** An input that is a fact provenance record. */
    public static MetricInput provenance(UUID id) {
        return new MetricInput(Kind.FACT_PROVENANCE, id);
    }

    /** An input that is a cost version. */
    public static MetricInput costVersion(UUID id) {
        return new MetricInput(Kind.COST_VERSION, id);
    }

    /** An input that is a finance input version. */
    public static MetricInput financeInput(UUID id) {
        return new MetricInput(Kind.FINANCE_INPUT_VERSION, id);
    }

    /** An input that is another computed metric value. */
    public static MetricInput metricValue(UUID id) {
        return new MetricInput(Kind.METRIC_VALUE, id);
    }

    /** An input that is the confirmed listing mapping. */
    public static MetricInput listingMapping(UUID id) {
        return new MetricInput(Kind.LISTING_MAPPING, id);
    }

    /** An input that is a scoped economics profile version. */
    public static MetricInput economicsProfile(UUID id) {
        return new MetricInput(Kind.ECONOMICS_PROFILE, id);
    }

    /** An input that is one selected fixed, percentage or tier component. */
    public static MetricInput economicsComponent(UUID id) {
        return new MetricInput(Kind.ECONOMICS_COMPONENT, id);
    }

    /** The sorts of input a metric can have. */
    public enum Kind {
        FACT_PROVENANCE,
        COST_VERSION,
        FINANCE_INPUT_VERSION,
        METRIC_VALUE,
        LISTING_MAPPING,
        ECONOMICS_PROFILE,
        ECONOMICS_COMPONENT
    }
}
