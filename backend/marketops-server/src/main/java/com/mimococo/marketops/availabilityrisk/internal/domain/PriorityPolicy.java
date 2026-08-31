package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a calculated child into a rank, deterministically and visibly.
 *
 * <p>The rank is lexicographic, not additive. The lane occupies a band far
 * wider than any commercial term can cross, so a CRITICAL card cannot be
 * overtaken by a merely valuable HIGH one no matter how much profit is at
 * stake. That is the Contract's hard rule — an imminent stockout cannot be
 * buried by a commercial score — expressed as arithmetic rather than as a
 * sorting special case somebody could refactor away.
 *
 * <p>Within a band the visible factors decide, and every one of them is
 * returned alongside the score so the UI can show the reasoning.
 */
public final class PriorityPolicy {

    /** How wide one lane band is. No commercial term can reach this far. */
    static final BigDecimal LANE_BAND = BigDecimal.valueOf(100_000);

    private static final MathContext CONTEXT = new MathContext(12, RoundingMode.HALF_UP);
    private static final BigDecimal TIME_WEIGHT = BigDecimal.valueOf(400);
    private static final BigDecimal PROFIT_WEIGHT = BigDecimal.valueOf(300);
    private static final BigDecimal VELOCITY_WEIGHT = BigDecimal.valueOf(200);
    private static final BigDecimal LIFECYCLE_WEIGHT = BigDecimal.valueOf(100);
    private static final BigDecimal CONFIDENCE_WEIGHT = BigDecimal.valueOf(-150);

    private PriorityPolicy() {
    }

    /** The score and the factors that produced it. */
    public record Ranking(BigDecimal score, List<RankFactor> factors) {
    }

    /**
     * Rank one child.
     *
     * @param risk the calculated child
     * @param lifecycleWeight the Owner-approved lifecycle multiplier, 0..1
     */
    public static Ranking rank(ChildRisk risk, BigDecimal lifecycleWeight) {
        List<RankFactor> factors = new ArrayList<>();
        BigDecimal commercial = BigDecimal.ZERO;

        BigDecimal cover = risk.daysOfCover();
        BigDecimal timeValue = cover == null ? BigDecimal.ZERO
                : BigDecimal.ONE.divide(BigDecimal.ONE.add(cover), CONTEXT);
        BigDecimal timeContribution = timeValue.multiply(TIME_WEIGHT, CONTEXT);
        factors.add(new RankFactor(RankFactor.Code.TIME_TO_STOCKOUT,
                cover == null ? BigDecimal.ZERO : cover, TIME_WEIGHT, timeContribution,
                cover == null ? "no projected stockout for this cover"
                        : "runs out in " + cover.setScale(1, RoundingMode.HALF_UP) + " days"));
        commercial = commercial.add(timeContribution);

        BigDecimal exposure = risk.profit().exposureFor(
                risk.demand().selectedRate() == null ? null
                        : risk.demand().selectedRate().multiply(
                                BigDecimal.valueOf(Math.max(1,
                                        risk.leadTime().resolved()
                                                ? risk.leadTime().coverageHorizonDays() : 1))));
        BigDecimal exposureValue = exposure == null ? BigDecimal.ZERO : exposure;
        BigDecimal profitContribution = normalise(exposureValue).multiply(PROFIT_WEIGHT, CONTEXT);
        factors.add(new RankFactor(RankFactor.Code.CONTRIBUTION_PROFIT_AT_RISK,
                exposureValue, PROFIT_WEIGHT, profitContribution,
                exposure == null ? "profit at risk is not known for this lane"
                        : "contribution profit exposed over the coverage horizon"));
        commercial = commercial.add(profitContribution);

        BigDecimal velocity = risk.demand().selectedRate() == null
                ? BigDecimal.ZERO : risk.demand().selectedRate();
        BigDecimal velocityContribution = normalise(velocity).multiply(VELOCITY_WEIGHT, CONTEXT);
        factors.add(new RankFactor(RankFactor.Code.SALES_VELOCITY, velocity, VELOCITY_WEIGHT,
                velocityContribution, "observed units per day from "
                + (risk.demand().selectedWindow() == null ? "no eligible window"
                        : risk.demand().selectedWindow().name())));
        commercial = commercial.add(velocityContribution);

        BigDecimal lifecycle = lifecycleWeight == null ? BigDecimal.ZERO : lifecycleWeight;
        BigDecimal lifecycleContribution = lifecycle.multiply(LIFECYCLE_WEIGHT, CONTEXT);
        factors.add(new RankFactor(RankFactor.Code.LIFECYCLE_STRATEGY, lifecycle,
                LIFECYCLE_WEIGHT, lifecycleContribution,
                "Owner-approved lifecycle weight for this variant"));
        commercial = commercial.add(lifecycleContribution);

        BigDecimal penaltyValue = BigDecimal.valueOf(risk.confidence().penaltyRank());
        BigDecimal penaltyContribution = penaltyValue.multiply(CONFIDENCE_WEIGHT, CONTEXT);
        factors.add(new RankFactor(RankFactor.Code.CONFIDENCE_PENALTY, penaltyValue,
                CONFIDENCE_WEIGHT, penaltyContribution,
                "confidence " + risk.confidence().name() + " reduces commercial ordering only"));
        commercial = commercial.add(penaltyContribution);

        // Clamp the commercial part into its band before adding the lane. An
        // unclamped term could otherwise reach across a band and reorder lanes.
        BigDecimal clamped = commercial.max(BigDecimal.ZERO).min(LANE_BAND.subtract(BigDecimal.ONE));
        BigDecimal score = LANE_BAND.multiply(BigDecimal.valueOf(band(risk.lane()))).add(clamped);
        return new Ranking(score.setScale(4, RoundingMode.HALF_UP), List.copyOf(factors));
    }

    /**
     * Which band a lane occupies.
     *
     * <p>Evidence-limited lanes share a band with HIGH rather than sitting below
     * WATCH: not knowing whether a profitable variant is about to run out is not
     * a mild condition.
     */
    static int band(AvailabilityLane lane) {
        return lane.severityOrdinal();
    }

    /** Squash an unbounded positive quantity into 0..1 without a magic cap. */
    private static BigDecimal normalise(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value.divide(BigDecimal.ONE.add(value), CONTEXT);
    }
}
