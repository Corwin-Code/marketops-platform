package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * How far one decision is allowed to move a bid.
 *
 * <p>Two limits apply at once and the tighter wins. A relative limit alone lets
 * a large bid move a large absolute amount; an absolute limit alone lets a small
 * bid move by a multiple of itself. Neither is what a person means when they
 * agree that one decision may adjust spend a little.
 *
 * <p>The ceiling headroom is separate and stricter still: it keeps the target
 * below the Max CPC rather than at it, so a bid does not sit exactly on the
 * value at which a click stops being worth anything.
 */
public record BidStepLimits(
        BigDecimal maxRelativeChangeRatio,
        BigDecimal maxAbsoluteChangeAmount,
        BigDecimal ceilingHeadroomRatio) {

    private static final int STORED_SCALE = 4;
    private static final MathContext CONTEXT = new MathContext(20, RoundingMode.HALF_UP);

    public BidStepLimits {
        Objects.requireNonNull(maxRelativeChangeRatio, "maxRelativeChangeRatio");
        Objects.requireNonNull(maxAbsoluteChangeAmount, "maxAbsoluteChangeAmount");
        if (maxRelativeChangeRatio.signum() < 0 || maxAbsoluteChangeAmount.signum() < 0) {
            throw new IllegalArgumentException("a negative limit permits nothing coherent");
        }
        if (ceilingHeadroomRatio != null
                && (ceilingHeadroomRatio.signum() < 0
                    || ceilingHeadroomRatio.compareTo(BigDecimal.ONE) >= 0)) {
            throw new IllegalArgumentException(
                    "headroom is a fraction of the ceiling, not the whole of it");
        }
    }

    /** The largest permitted step from this bid, by the tighter of the two limits. */
    public BigDecimal permittedStepFrom(BigDecimal currentBid) {
        BigDecimal relative = currentBid.abs().multiply(maxRelativeChangeRatio, CONTEXT);
        return relative.min(maxAbsoluteChangeAmount).setScale(STORED_SCALE, RoundingMode.FLOOR);
    }

    /** Published absolute limits are currency-major amounts; native targets may use minor units. */
    public BidStepLimits inNativeUnits(String unit) {
        return new BidStepLimits(maxRelativeChangeRatio,
                AdBidUnitConversion.toNative(maxAbsoluteChangeAmount,unit),ceilingHeadroomRatio);
    }

    /** The lowest value a decrease from this bid may reach. */
    public BigDecimal lowestPermittedFrom(BigDecimal currentBid) {
        return currentBid.subtract(permittedStepFrom(currentBid))
                .max(BigDecimal.ZERO)
                .setScale(STORED_SCALE, RoundingMode.CEILING);
    }

    /** The highest value an increase from this bid may reach. */
    public BigDecimal highestPermittedFrom(BigDecimal currentBid) {
        return currentBid.add(permittedStepFrom(currentBid))
                .setScale(STORED_SCALE, RoundingMode.FLOOR);
    }

    /**
     * The ceiling, reduced by whatever headroom the policy keeps.
     *
     * <p>No headroom configured means no reduction. That is a policy choice the
     * owner made rather than a default this code invented, so it is honoured as
     * written.
     */
    public BigDecimal applyCeilingHeadroom(BigDecimal ceiling) {
        if (ceilingHeadroomRatio == null || ceilingHeadroomRatio.signum() == 0) {
            return ceiling.setScale(STORED_SCALE, RoundingMode.FLOOR);
        }
        return ceiling.multiply(BigDecimal.ONE.subtract(ceilingHeadroomRatio), CONTEXT)
                .setScale(STORED_SCALE, RoundingMode.FLOOR);
    }
}
