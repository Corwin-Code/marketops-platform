package com.mimococo.marketops.analyticsdecision.internal.config;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How old a fact may be before the metric derived from it is called stale, and
 * the thresholds the deterministic rules compare against.
 *
 * <p>Freshness is per domain because the domains genuinely differ: a price goes
 * stale in hours and a settlement takes days. One global target would either
 * block on data that is still fine or accept money figures that are not.
 *
 * <p>The rule thresholds are operating configuration rather than product
 * definition. They appear in every finding's detail, so a triggered rule can
 * always be checked against the number it compared with, and a rule whose
 * threshold is unset declines rather than passing quietly.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.analytics")
public final class AnalyticsProperties {

    @NotNull
    private Duration funnelFreshness = Duration.ofHours(48);

    @NotNull
    private Duration salesFreshness = Duration.ofHours(24);

    @NotNull
    private Duration returnsFreshness = Duration.ofHours(48);

    @NotNull
    private Duration inventoryFreshness = Duration.ofHours(6);

    @NotNull
    private Duration advertisingFreshness = Duration.ofHours(24);

    @NotNull
    private Duration costFreshness = Duration.ofDays(7);

    @NotNull
    private Thresholds thresholds = new Thresholds();

    /** How old a funnel fact may be. */
    public Duration getFunnelFreshness() {
        return funnelFreshness;
    }

    /** Bind the funnel freshness target. */
    public void setFunnelFreshness(Duration funnelFreshness) {
        this.funnelFreshness = funnelFreshness;
    }

    /** How old a sales fact may be. */
    public Duration getSalesFreshness() {
        return salesFreshness;
    }

    /** Bind the sales freshness target. */
    public void setSalesFreshness(Duration salesFreshness) {
        this.salesFreshness = salesFreshness;
    }

    /** How old a return fact may be. */
    public Duration getReturnsFreshness() {
        return returnsFreshness;
    }

    /** Bind the returns freshness target. */
    public void setReturnsFreshness(Duration returnsFreshness) {
        this.returnsFreshness = returnsFreshness;
    }

    /** How old an inventory fact may be. */
    public Duration getInventoryFreshness() {
        return inventoryFreshness;
    }

    /** Bind the inventory freshness target. */
    public void setInventoryFreshness(Duration inventoryFreshness) {
        this.inventoryFreshness = inventoryFreshness;
    }

    /** How old an advertising fact may be. */
    public Duration getAdvertisingFreshness() {
        return advertisingFreshness;
    }

    /** Bind the advertising freshness target. */
    public void setAdvertisingFreshness(Duration advertisingFreshness) {
        this.advertisingFreshness = advertisingFreshness;
    }

    /** How old a cost or finance input may be. */
    public Duration getCostFreshness() {
        return costFreshness;
    }

    /** Bind the cost freshness target. */
    public void setCostFreshness(Duration costFreshness) {
        this.costFreshness = costFreshness;
    }

    /** The numbers the deterministic rules compare against. */
    public Thresholds getThresholds() {
        return thresholds;
    }

    /** Bind the rule thresholds. */
    public void setThresholds(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    /**
     * What each deterministic rule compares against.
     *
     * <p>Every sample floor exists because a ratio over a handful of events is
     * noise. A return rate of one in three is a business problem over three
     * hundred units and a coincidence over three.
     */
    public static final class Thresholds {

        private BigDecimal minimumDataCompleteness = new BigDecimal("0.80");

        private Long lowImpressionFloor = 100L;

        private BigDecimal lowClickThroughRate = new BigDecimal("0.0100");

        private BigDecimal lowConversionRate = new BigDecimal("0.0100");

        private BigDecimal highReturnRate = new BigDecimal("0.2000");

        private Long highReturnMinimumUnits = 10L;

        private BigDecimal stockCoverDaysFloor = new BigDecimal("7");

        private BigDecimal advertisingCostOfSaleCeiling = new BigDecimal("0.3000");

        private Long lowClickThroughMinimumImpressions = 500L;

        private Long lowConversionMinimumReach = 200L;

        /** Least share of profit inputs that must resolve canonically. */
        public BigDecimal getMinimumDataCompleteness() {
            return minimumDataCompleteness;
        }

        /** Bind the data-completeness floor. */
        public void setMinimumDataCompleteness(BigDecimal minimumDataCompleteness) {
            this.minimumDataCompleteness = minimumDataCompleteness;
        }

        /** Impressions below which exposure is called low. */
        public Long getLowImpressionFloor() {
            return lowImpressionFloor;
        }

        /** Bind the impression floor. */
        public void setLowImpressionFloor(Long lowImpressionFloor) {
            this.lowImpressionFloor = lowImpressionFloor;
        }

        /** Click-through rate below which the listing is called weak. */
        public BigDecimal getLowClickThroughRate() {
            return lowClickThroughRate;
        }

        /** Bind the click-through floor. */
        public void setLowClickThroughRate(BigDecimal lowClickThroughRate) {
            this.lowClickThroughRate = lowClickThroughRate;
        }

        /** Conversion rate below which the listing is called weak. */
        public BigDecimal getLowConversionRate() {
            return lowConversionRate;
        }

        /** Bind the conversion floor. */
        public void setLowConversionRate(BigDecimal lowConversionRate) {
            this.lowConversionRate = lowConversionRate;
        }

        /** Return rate above which returns are called high. */
        public BigDecimal getHighReturnRate() {
            return highReturnRate;
        }

        /** Bind the return-rate ceiling. */
        public void setHighReturnRate(BigDecimal highReturnRate) {
            this.highReturnRate = highReturnRate;
        }

        /** Completed units below which a return rate is not meaningful. */
        public Long getHighReturnMinimumUnits() {
            return highReturnMinimumUnits;
        }

        /** Bind the return-rate sample floor. */
        public void setHighReturnMinimumUnits(Long highReturnMinimumUnits) {
            this.highReturnMinimumUnits = highReturnMinimumUnits;
        }

        /** Days of cover below which stock is called at risk. */
        public BigDecimal getStockCoverDaysFloor() {
            return stockCoverDaysFloor;
        }

        /** Bind the stock-cover floor. */
        public void setStockCoverDaysFloor(BigDecimal stockCoverDaysFloor) {
            this.stockCoverDaysFloor = stockCoverDaysFloor;
        }

        /** Advertising cost of sale above which spending is called inefficient. */
        public BigDecimal getAdvertisingCostOfSaleCeiling() {
            return advertisingCostOfSaleCeiling;
        }

        /** Bind the advertising ceiling. */
        public void setAdvertisingCostOfSaleCeiling(BigDecimal advertisingCostOfSaleCeiling) {
            this.advertisingCostOfSaleCeiling = advertisingCostOfSaleCeiling;
        }

        /** Impressions below which a click-through rate is not meaningful. */
        public Long getLowClickThroughMinimumImpressions() {
            return lowClickThroughMinimumImpressions;
        }

        /** Bind the click-through sample floor. */
        public void setLowClickThroughMinimumImpressions(Long value) {
            this.lowClickThroughMinimumImpressions = value;
        }

        /** Visits or clicks below which a conversion rate is not meaningful. */
        public Long getLowConversionMinimumReach() {
            return lowConversionMinimumReach;
        }

        /** Bind the conversion sample floor. */
        public void setLowConversionMinimumReach(Long value) {
            this.lowConversionMinimumReach = value;
        }
    }
}
