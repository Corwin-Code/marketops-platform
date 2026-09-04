package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a case into a rank that a commercial number cannot buy its way up.
 *
 * <p>The device is arithmetic rather than convention. A case's rank is
 *
 * <pre>
 *   BAND * tierIndex + clamp(commercial, 0, BAND - 1)
 * </pre>
 *
 * <p>where {@code BAND} is 100000 and the commercial part is clamped strictly
 * below it. A P2 Protection case with no measurable loss therefore still
 * outranks every Data Repair case, and a Data Repair case outranks every
 * Optimization case no matter how large the opportunity. That is the
 * Contract's non-compensating requirement made structural: there is no input
 * value that can move a case across a boundary, so no future weight change can
 * accidentally introduce one.
 *
 * <p>Confidence is the one term allowed to be negative, and its weight is
 * required to be non-positive by the policy record, so uncertainty can lower a
 * rank inside its band and can never raise one or escape it.
 *
 * <p>The band arithmetic is mirrored in SQL by the queue repository, because the
 * read path re-derives the rank of the child a scoped viewer may actually see.
 * The two must stay in lockstep, and {@code AdPriorityPolicyTest} asserts the
 * constants that make them the same.
 */
public final class AdPriorityPolicy {

    /** The width of one lane or Protection sub-tier band. */
    public static final BigDecimal BAND = BigDecimal.valueOf(100_000);

    private static final MathContext CONTEXT = new MathContext(12, RoundingMode.HALF_UP);

    private AdPriorityPolicy() {
    }

    /** The published weights behind the commercial part of a rank. */
    public record Weights(
            BigDecimal profitLossWeight,
            BigDecimal spendExposureWeight,
            BigDecimal criticalSalesWeight,
            BigDecimal recoverableProfitWeight,
            BigDecimal evidenceMaturityWeight,
            BigDecimal ageWeight,
            BigDecimal confidenceWeight) {

        public Weights {
            Objects.requireNonNull(profitLossWeight, "profitLossWeight");
            Objects.requireNonNull(spendExposureWeight, "spendExposureWeight");
            Objects.requireNonNull(criticalSalesWeight, "criticalSalesWeight");
            Objects.requireNonNull(recoverableProfitWeight, "recoverableProfitWeight");
            Objects.requireNonNull(evidenceMaturityWeight, "evidenceMaturityWeight");
            Objects.requireNonNull(ageWeight, "ageWeight");
            Objects.requireNonNull(confidenceWeight, "confidenceWeight");
            if (confidenceWeight.signum() > 0) {
                throw new IllegalArgumentException(
                        "the confidence weight can only subtract, so it must not be positive");
            }
        }
    }

    /** The inputs a rank is computed from, all already resolved by the caller. */
    public record Inputs(
            AdvertisingLane lane,
            ProtectionTier protectionTier,
            AdMeasure confirmedProfitLossRate,
            AdMeasure officialSpendExposure,
            AdMeasure criticalSalesExposure,
            AdMeasure recoverableProfit,
            BigDecimal evidenceMaturityRatio,
            BigDecimal caseAgeDays,
            AdConfidence confidence) {

        public Inputs {
            Objects.requireNonNull(lane, "lane");
            Objects.requireNonNull(confidence, "confidence");
        }
    }

    /** A rank together with the terms that produced it. */
    public record Ranking(BigDecimal score, List<AdRankFactor> factors) {

        public Ranking {
            Objects.requireNonNull(score, "score");
            factors = List.copyOf(Objects.requireNonNull(factors, "factors"));
        }
    }

    /**
     * The band index a lane and tier occupy.
     *
     * <p>Protection's four sub-tiers occupy bands 3 through 6, Data Repair band
     * 2, Optimization band 1 and Watch band 0. The gap between Protection P3
     * and Data Repair is one whole band, which is what stops the largest
     * conceivable data-repair blast radius from outranking the smallest
     * qualified Protection danger.
     */
    public static int band(AdvertisingLane lane, ProtectionTier tier) {
        Objects.requireNonNull(lane, "lane");
        if (lane == AdvertisingLane.PROTECTION) {
            Objects.requireNonNull(tier, "tier");
            return 3 + tier.tierBand();
        }
        return switch (lane) {
            case DATA_REPAIR -> 2;
            case OPTIMIZATION -> 1;
            case WATCH -> 0;
            case PROTECTION -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * Rank a case.
     *
     * <p>Every term is emitted even when its value is zero, so the factor list a
     * reviewer sees is the same shape for every case and a missing term is
     * visibly missing rather than quietly absent.
     */
    public static Ranking rank(Inputs inputs, Weights weights) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(weights, "weights");

        List<AdRankFactor> factors = new ArrayList<>(7);
        BigDecimal commercial = BigDecimal.ZERO;

        commercial = commercial.add(term(factors, AdRankFactor.Code.CONFIRMED_PROFIT_LOSS_RATE,
                normalise(measure(inputs.confirmedProfitLossRate())), weights.profitLossWeight()));
        commercial = commercial.add(term(factors, AdRankFactor.Code.OFFICIAL_SPEND_EXPOSURE,
                normalise(measure(inputs.officialSpendExposure())), weights.spendExposureWeight()));
        commercial = commercial.add(term(factors, AdRankFactor.Code.CRITICAL_SALES_EXPOSURE,
                normalise(measure(inputs.criticalSalesExposure())), weights.criticalSalesWeight()));
        commercial = commercial.add(term(factors, AdRankFactor.Code.RECOVERABLE_CONTRIBUTION_PROFIT,
                normalise(measure(inputs.recoverableProfit())), weights.recoverableProfitWeight()));
        commercial = commercial.add(term(factors, AdRankFactor.Code.EVIDENCE_MATURITY,
                orZero(inputs.evidenceMaturityRatio()), weights.evidenceMaturityWeight()));
        commercial = commercial.add(term(factors, AdRankFactor.Code.CASE_AGE,
                normalise(orZero(inputs.caseAgeDays())), weights.ageWeight()));
        commercial = commercial.add(term(factors, AdRankFactor.Code.CONFIDENCE_PENALTY,
                BigDecimal.valueOf(inputs.confidence().penaltyRank()), weights.confidenceWeight()));

        BigDecimal clamped = commercial.max(BigDecimal.ZERO).min(BAND.subtract(BigDecimal.ONE));
        BigDecimal score = BAND
                .multiply(BigDecimal.valueOf(band(inputs.lane(), inputs.protectionTier())))
                .add(clamped)
                .setScale(4, RoundingMode.HALF_UP);
        return new Ranking(score, factors);
    }

    /**
     * The severity-only rank used when no priority policy resolves.
     *
     * <p>An absent policy does not invent weights. The case still sorts into its
     * lane and tier, and carries no factors at all, so the queue stays ordered
     * and nobody mistakes a default for a published decision.
     */
    public static Ranking unranked(AdvertisingLane lane, ProtectionTier tier) {
        BigDecimal score = BAND.multiply(BigDecimal.valueOf(band(lane, tier)))
                .setScale(4, RoundingMode.HALF_UP);
        return new Ranking(score, List.of());
    }

    private static BigDecimal term(
            List<AdRankFactor> factors, AdRankFactor.Code code, BigDecimal value, BigDecimal weight) {
        BigDecimal contribution = value.multiply(weight, CONTEXT);
        factors.add(new AdRankFactor(code,
                value.setScale(4, RoundingMode.HALF_UP),
                weight.setScale(4, RoundingMode.HALF_UP),
                contribution.setScale(4, RoundingMode.HALF_UP),
                null));
        return contribution;
    }

    private static BigDecimal measure(AdMeasure measure) {
        return measure == null ? BigDecimal.ZERO : measure.orElse(BigDecimal.ZERO);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * Map an unbounded magnitude into [0, 1).
     *
     * <p>Without this a single very large exposure would saturate the clamp and
     * flatten every distinction below it, which would make the commercial part
     * of the rank useless exactly when the queue is busiest.
     */
    private static BigDecimal normalise(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value.divide(BigDecimal.ONE.add(value), CONTEXT);
    }
}
