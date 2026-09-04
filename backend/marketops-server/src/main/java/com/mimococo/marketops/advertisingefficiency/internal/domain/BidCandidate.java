package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * One bid this product would be willing to ask for, and how far it may ask.
 *
 * <p>A candidate is bounded from four directions at once, and every bound is a
 * different way of being wrong:
 *
 * <ul>
 *   <li>the ceiling a click may be worth, so a bid never exceeds what the
 *       traffic can earn;</li>
 *   <li>the policy's relative and absolute step limits, so no single decision
 *       moves spend further than a person agreed a decision may;</li>
 *   <li>the platform's own grid, so the value sent is one the platform will
 *       accept unchanged;</li>
 *   <li>the direction, so a decrease can only go down and an increase can only
 *       go up.</li>
 * </ul>
 *
 * <p>All four are applied before the value exists. There is no partially-bounded
 * candidate that a later check is expected to catch.
 */
public record BidCandidate(
        String direction,
        String candidateBasis,
        BigDecimal currentBid,
        BigDecimal requestedAmount,
        BigDecimal providerNormalizedAmount,
        String currencyCode,
        String bidUnitCode) {

    /** Directions, as the schema spells them. */
    public static final String PROTECTION_DECREASE = "PROTECTION_DECREASE";
    public static final String OPTIMIZATION_INCREASE = "OPTIMIZATION_INCREASE";
    public static final String EXACT_PRIOR_BID_COMPENSATION = "EXACT_PRIOR_BID_COMPENSATION";

    /** The two bases the schema admits, and there is no third. */
    public static final String MAX_CPC_BOUNDED = "MAX_CPC_BOUNDED";
    public static final String CAUSE_BOUND_PROTECTION_STEP = "CAUSE_BOUND_PROTECTION_STEP";

    private static final int STORED_SCALE = 4;

    public BidCandidate {
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(currentBid, "currentBid");
        Objects.requireNonNull(providerNormalizedAmount, "providerNormalizedAmount");
        if (providerNormalizedAmount.compareTo(currentBid) == 0) {
            throw new IllegalArgumentException(
                    "a candidate that proposes the current bid proposes nothing");
        }
        if (providerNormalizedAmount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "a zero bid withdraws an object rather than lowering it");
        }
    }

    /**
     * The bounded, normalized decrease for one case, if one exists.
     *
     * <p>{@code maxCpc} is the ceiling and it is not optional. A decrease whose
     * ceiling could not be computed has no target to move toward, and moving
     * "somewhat down" without one would be a number chosen by nothing.
     */
    public static Optional<BidCandidate> decrease(
            AdMeasure currentBid, MaxCpc maxCpc, BidStepLimits limits, ProviderBidGrid grid,
            String candidateBasis) {
        BigDecimal current = writeGradeBid(currentBid, maxCpc, limits, grid);
        if (current == null) {
            return Optional.empty();
        }
        BigDecimal ceiling = limits.applyCeilingHeadroom(maxCpc.ceiling().amount());
        if (ceiling.compareTo(current) >= 0) {
            // The bid is already at or below what a click is worth. Nothing to
            // protect against, so no candidate rather than a token decrease.
            return Optional.empty();
        }
        BigDecimal floor = limits.lowestPermittedFrom(current);
        BigDecimal requested = ceiling.max(floor).setScale(STORED_SCALE, RoundingMode.FLOOR);
        return grid.normalizeDownward(requested)
                .filter(normalized -> normalized.compareTo(current) < 0)
                .map(normalized -> new BidCandidate(PROTECTION_DECREASE, candidateBasis,
                        current, requested, normalized, grid.bidCurrencyCode(),
                        grid.bidUnitCode()));
    }

    /**
     * The bounded, normalized increase for one case, if one exists.
     *
     * <p>The requested value is the smaller of the step limit and the ceiling,
     * so an increase can approach what a click is worth but never pass it. The
     * grid then rounds down, which can only make it safer.
     */
    public static Optional<BidCandidate> increase(
            AdMeasure currentBid, MaxCpc maxCpc, BidStepLimits limits, ProviderBidGrid grid,
            String candidateBasis) {
        BigDecimal current = writeGradeBid(currentBid, maxCpc, limits, grid);
        if (current == null) {
            return Optional.empty();
        }
        BigDecimal ceiling = limits.applyCeilingHeadroom(maxCpc.ceiling().amount());
        if (ceiling.compareTo(current) <= 0) {
            return Optional.empty();
        }
        BigDecimal highestPermitted = limits.highestPermittedFrom(current);
        BigDecimal requested =
                ceiling.min(highestPermitted).setScale(STORED_SCALE, RoundingMode.FLOOR);
        return grid.normalizeDownward(requested)
                .filter(normalized -> normalized.compareTo(current) > 0)
                .map(normalized -> new BidCandidate(OPTIMIZATION_INCREASE, candidateBasis,
                        current, requested, normalized, grid.bidCurrencyCode(),
                        grid.bidUnitCode()));
    }

    /**
     * A decrease bounded by the cause rather than by a ceiling.
     *
     * <p>The route for the two Protection causes where no conversion figure is
     * needed to know the spend is wasted: a variant that cannot be sold, and a
     * variant that is not there to sell. Every rouble spent promoting either is
     * certainly wasted, so a decrease is justified without a Max CPC — which is
     * the whole point, because those are exactly the cases where conversion data
     * is least likely to exist.
     *
     * <p>It is still bounded three ways: by the step ratio the policy names for
     * this cause, by the policy's ordinary step limits, and by the platform's
     * grid. What it is not bounded by is an economic ceiling, and the candidate
     * says so by carrying {@code CAUSE_BOUND_PROTECTION_STEP} rather than
     * pretending to a ceiling it never had.
     */
    public static Optional<BidCandidate> causeBoundDecrease(
            AdMeasure currentBid, BigDecimal causeStepRatio, BidStepLimits limits,
            ProviderBidGrid grid) {
        if (currentBid == null || limits == null || grid == null || causeStepRatio == null
                || causeStepRatio.signum() <= 0
                || causeStepRatio.compareTo(BigDecimal.ONE) > 0
                || !currentBid.sufficientForWrite() || !grid.usable()) {
            return Optional.empty();
        }
        BigDecimal current = currentBid.orElse(null);
        if (current == null || current.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal causeTarget = current
                .subtract(current.multiply(causeStepRatio))
                .setScale(STORED_SCALE, RoundingMode.FLOOR);
        BigDecimal floor = limits.lowestPermittedFrom(current);
        BigDecimal requested = causeTarget.max(floor).setScale(STORED_SCALE, RoundingMode.FLOOR);
        return grid.normalizeDownward(requested)
                .filter(normalized -> normalized.compareTo(current) < 0)
                .map(normalized -> new BidCandidate(PROTECTION_DECREASE,
                        CAUSE_BOUND_PROTECTION_STEP, current, requested, normalized,
                        grid.bidCurrencyCode(), grid.bidUnitCode()));
    }

    /**
     * The current bid, when every precondition for proposing a change holds.
     *
     * <p>{@code null} rather than an exception, because "no candidate" is an
     * ordinary outcome for most objects on most cycles. The currency check is
     * here rather than later: a ceiling denominated in one currency and a bid in
     * another cannot be compared at all, and comparing them anyway is how a
     * ceiling stops meaning anything.
     */
    private static BigDecimal writeGradeBid(AdMeasure currentBid, MaxCpc maxCpc,
                                            BidStepLimits limits, ProviderBidGrid grid) {
        if (currentBid == null || maxCpc == null || limits == null || grid == null
                || !currentBid.sufficientForWrite() || !maxCpc.writeGrade()
                || !grid.usable()) {
            return null;
        }
        if (grid.bidCurrencyCode() == null
                || !grid.bidCurrencyCode().equals(maxCpc.ceiling().currencyCode())) {
            return null;
        }
        BigDecimal current = currentBid.orElse(null);
        return current == null || current.signum() <= 0 ? null : current;
    }

    /** How far this candidate moves the bid, as a positive amount. */
    public BigDecimal changeAmount() {
        return providerNormalizedAmount.subtract(currentBid).abs();
    }
}
