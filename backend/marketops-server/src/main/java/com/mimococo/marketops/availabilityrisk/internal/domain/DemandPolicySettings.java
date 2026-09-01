package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * The thresholds one published demand-policy version fixes.
 *
 * <p>These are values from a row, not constants. A card names the version it
 * used, so two cards calculated a month apart can be compared honestly even
 * after the policy changed.
 *
 * @param policyId the version's identity
 * @param policyVersion its monotonic version number
 * @param minimumSampleUnits units a window needs before it is evidence
 * @param accelerationRatio how much D7 must exceed D14 to be called acceleration
 * @param decelerationRatio how far D7 must fall below D14 to be called deceleration
 * @param outlierShareRatio the share one day may contribute before review
 * @param minimumCoverageRatio how much of a window must be observable
 * @param carryForwardMax how long a last-eligible answer may be carried
 * @param stockFreshnessMax how old a stock observation may be and still be current
 */
public record DemandPolicySettings(
        UUID policyId,
        int policyVersion,
        int minimumSampleUnits,
        BigDecimal accelerationRatio,
        BigDecimal decelerationRatio,
        BigDecimal outlierShareRatio,
        BigDecimal minimumCoverageRatio,
        Duration carryForwardMax,
        Duration stockFreshnessMax) {

    public DemandPolicySettings {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(accelerationRatio, "accelerationRatio");
        Objects.requireNonNull(decelerationRatio, "decelerationRatio");
        Objects.requireNonNull(outlierShareRatio, "outlierShareRatio");
        Objects.requireNonNull(minimumCoverageRatio, "minimumCoverageRatio");
        Objects.requireNonNull(carryForwardMax, "carryForwardMax");
        Objects.requireNonNull(stockFreshnessMax, "stockFreshnessMax");
        if (minimumSampleUnits < 1) {
            throw new IllegalArgumentException("minimumSampleUnits must be at least one");
        }
        if (accelerationRatio.compareTo(BigDecimal.ONE) <= 0) {
            throw new IllegalArgumentException("accelerationRatio must exceed one");
        }
        if (decelerationRatio.compareTo(BigDecimal.ONE) >= 0 || decelerationRatio.signum() <= 0) {
            throw new IllegalArgumentException("decelerationRatio must be between zero and one");
        }
    }
}
