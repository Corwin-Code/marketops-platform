package com.mimococo.marketops.analyticsdecision;

/**
 * The canonical metrics this product computes.
 *
 * <p>The enum mirrors the seeded definition set exactly. Keeping the two in step
 * is checked by a test rather than by memory, because a metric the engine can
 * name but the database has never heard of would produce values nobody can
 * resolve a definition for.
 *
 * <p>The domain each metric belongs to decides its freshness target: a price
 * goes stale in hours and a settlement in days, so one global target would
 * either block on fresh data or accept stale money.
 */
public enum MetricCode {

    IMPRESSIONS(MetricDomain.FUNNEL),
    CLICKS(MetricDomain.FUNNEL),
    CLICK_THROUGH_RATE(MetricDomain.FUNNEL),
    CONVERSION_RATE(MetricDomain.FUNNEL),
    COMPLETED_UNITS(MetricDomain.SALES),
    COMPLETED_NET_SALES(MetricDomain.SALES),
    RETAINED_UNITS(MetricDomain.SALES),
    RETAINED_NET_SALES(MetricDomain.SALES),
    SETTLED_NET_SALES(MetricDomain.SALES),
    RETURN_UNITS(MetricDomain.RETURNS),
    RETURN_RATE(MetricDomain.RETURNS),
    PLATFORM_AVAILABLE_UNITS(MetricDomain.INVENTORY),
    INTERNAL_AVAILABLE_UNITS(MetricDomain.INVENTORY),
    STOCK_COVER_DAYS(MetricDomain.INVENTORY),
    AD_SPEND(MetricDomain.ADVERTISING),
    AD_COST_OF_SALE(MetricDomain.ADVERTISING),
    UNIT_COST(MetricDomain.COST),
    PLATFORM_FEES(MetricDomain.COST),
    RETURN_LOSS(MetricDomain.COST),
    VARIABLE_TAX_ESTIMATE(MetricDomain.COST),
    OPERATIONAL_CONTRIBUTION_PROFIT(MetricDomain.PROFIT),
    SETTLED_CONTRIBUTION_PROFIT(MetricDomain.PROFIT),
    CONTRIBUTION_MARGIN(MetricDomain.PROFIT),
    OBSERVED_SELLING_PRICE(MetricDomain.PROFIT),
    MINIMUM_PRICE(MetricDomain.PROFIT),
    DATA_COMPLETENESS(MetricDomain.QUALITY);

    /** The version of every definition this release computes. */
    public static final int DEFINITION_VERSION = 1;

    private final MetricDomain domain;

    MetricCode(MetricDomain domain) {
        this.domain = domain;
    }

    /** Which domain's freshness target applies to this metric. */
    public MetricDomain domain() {
        return domain;
    }

    /** The fact families a freshness target is set for. */
    public enum MetricDomain {
        FUNNEL,
        SALES,
        RETURNS,
        INVENTORY,
        ADVERTISING,
        COST,
        PROFIT,
        QUALITY
    }
}
