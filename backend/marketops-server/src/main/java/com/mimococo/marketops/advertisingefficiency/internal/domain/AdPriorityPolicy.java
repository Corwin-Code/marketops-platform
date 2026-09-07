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

/** Canonical lane/tier followed by a non-compensating, lane-specific lexicographic tuple.
 * The numeric score is a severity display only and never the canonical queue order.
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
            AdConfidence confidence, BigDecimal humanSloUrgency, BigDecimal blockedProtection,
            BigDecimal blastRadius, BigDecimal blockedWork, BigDecimal dualAxisGap, BigDecimal criticalSalesHeadroom, BigDecimal perRubGap) {
        public Inputs(AdvertisingLane lane,ProtectionTier tier,AdMeasure loss,AdMeasure spend,AdMeasure critical,AdMeasure recoverable,
                BigDecimal maturity,BigDecimal age,AdConfidence confidence,BigDecimal urgency,BigDecimal blockedProtection,BigDecimal blastRadius,
                BigDecimal blockedWork,BigDecimal absoluteGap,BigDecimal headroom) {
            this(lane,tier,loss,spend,critical,recoverable,maturity,age,confidence,urgency,blockedProtection,blastRadius,blockedWork,absoluteGap,headroom,null);
        }
        public Inputs(AdvertisingLane lane, ProtectionTier tier, AdMeasure loss, AdMeasure spend,
                AdMeasure critical, AdMeasure recoverable, BigDecimal maturity, BigDecimal age, AdConfidence confidence) {
            this(lane, tier, loss, spend, critical, recoverable, maturity, age, confidence,
                    null, null, null, null, null, null);
        }

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
        List<AdRankFactor> factors = new ArrayList<>();
        switch (inputs.lane()) {
            case PROTECTION -> {
                factor(factors, AdRankFactor.Code.HUMAN_SLO_URGENCY, inputs.humanSloUrgency());
                factor(factors, AdRankFactor.Code.CONFIRMED_PROFIT_LOSS_RATE, confirmed(inputs.confirmedProfitLossRate()));
                factor(factors, AdRankFactor.Code.CRITICAL_SALES_EXPOSURE, confirmed(inputs.criticalSalesExposure()));
                factor(factors, AdRankFactor.Code.OFFICIAL_SPEND_EXPOSURE, confirmed(inputs.officialSpendExposure()));
                factor(factors, AdRankFactor.Code.CASE_AGE, inputs.caseAgeDays());
            }
            case DATA_REPAIR -> {
                factor(factors, AdRankFactor.Code.BLOCKED_PROTECTION, inputs.blockedProtection());
                factor(factors, AdRankFactor.Code.BLAST_RADIUS, inputs.blastRadius());
                factor(factors, AdRankFactor.Code.OFFICIAL_SPEND_EXPOSURE, confirmed(inputs.officialSpendExposure()));
                factor(factors, AdRankFactor.Code.BLOCKED_WORK, inputs.blockedWork());
                factor(factors, AdRankFactor.Code.HUMAN_SLO_URGENCY, inputs.humanSloUrgency());
                factor(factors, AdRankFactor.Code.CASE_AGE, inputs.caseAgeDays());
            }
            case OPTIMIZATION -> {
                factor(factors, AdRankFactor.Code.RECOVERABLE_CONTRIBUTION_PROFIT, confirmed(inputs.recoverableProfit()));
                factor(factors, AdRankFactor.Code.DUAL_AXIS_GAP, inputs.dualAxisGap());
                factor(factors, AdRankFactor.Code.DUAL_AXIS_PER_RUB_GAP, inputs.perRubGap());
                factor(factors, AdRankFactor.Code.OFFICIAL_SPEND_EXPOSURE, confirmed(inputs.officialSpendExposure()));
                factor(factors, AdRankFactor.Code.CRITICAL_SALES_HEADROOM, inputs.criticalSalesHeadroom());
                factor(factors, AdRankFactor.Code.EVIDENCE_MATURITY, inputs.evidenceMaturityRatio());
                factor(factors, AdRankFactor.Code.CASE_AGE, inputs.caseAgeDays());
            }
            case WATCH -> { /* Visibility only. Stable identity resolves the final order. */ }
        }
        return new Ranking(BAND.multiply(BigDecimal.valueOf(band(inputs.lane(), inputs.protectionTier()))), factors);
    }

    private static BigDecimal confirmed(AdMeasure value) {
        return value != null && value.sufficientForWrite() ? value.value() : null;
    }

    private static void factor(List<AdRankFactor> factors, AdRankFactor.Code code, BigDecimal value) {
        factors.add(new AdRankFactor(code, value, BigDecimal.ZERO, BigDecimal.ZERO,
                value == null ? "PRIORITY_POLICY_UNRESOLVED:" + code : "LEXICOGRAPHIC:" + (factors.size() + 1)));
    }

    /** Higher severity first; no later exposure can compensate for an earlier factor. */
    public static int compare(Ranking left, String leftIdentity, Ranking right, String rightIdentity) {
        int severity = right.score().compareTo(left.score());
        if (severity != 0) { return severity; }
        for (int index = 0; index < Math.min(left.factors().size(), right.factors().size()); index++) {
            BigDecimal a = left.factors().get(index).value();
            BigDecimal b = right.factors().get(index).value();
            int comparison = a == null ? (b == null ? 0 : 1) : b == null ? -1 : b.compareTo(a);
            if (comparison != 0) { return comparison; }
        }
        return leftIdentity.compareTo(rightIdentity);
    }

    /**
     * The severity-only rank used when no priority policy resolves.
     *
     * <p>An absent policy does not invent weights. The case still sorts into its
     * lane and tier. The unavailable policy is explicit evidence metadata, so
     * a visible severity band is never mistaken for complete ranking authority.
     */
    public static Ranking unranked(AdvertisingLane lane, ProtectionTier tier) {
        BigDecimal score = BAND.multiply(BigDecimal.valueOf(band(lane, tier)))
                .setScale(4, RoundingMode.HALF_UP);
        return new Ranking(score, List.of(new AdRankFactor(AdRankFactor.Code.EVIDENCE_MATURITY,
                null, BigDecimal.ZERO, BigDecimal.ZERO, "PRIORITY_POLICY_UNRESOLVED:PROFILE")));
    }

    /**
     * The number of bands, and the width each gets in the workflow's scale.
     *
     * <p>Seven bands: four Protection tiers, then Data Repair, Optimization and
     * Watch. The widest band that fits under a thousand is a hundred and
     * forty-two, so the top of the scale is 993 and nothing overflows.
     */
    private static final int BANDS = 7;
    private static final int WORKFLOW_BAND_WIDTH = 142;

    /**
     * The same ordering, expressed in the range the workflow queue admits.
     *
     * <p>The advertising rank is a band score that reaches six hundred thousand.
     * {@code ops.recommendation.priority_score} is {@code numeric(9, 4)} bounded
     * at a thousand. Writing one into the other is not a rounding problem, it is
     * a constraint violation on every proposal — and the fix is not to divide,
     * because dividing a non-compensating score by a constant would let a large
     * commercial term in a low band round up past a small one in a high band.
     *
     * <p>So the band survives the mapping intact and the commercial part is
     * scaled inside its own band. No input value can move a case across a
     * boundary here either, which is the property the band arithmetic exists to
     * guarantee.
     */
    public static BigDecimal workflowPriority(BigDecimal bandScore) {
        Objects.requireNonNull(bandScore, "bandScore");
        BigDecimal clamped = bandScore.max(BigDecimal.ZERO);
        BigDecimal band = clamped.divideToIntegralValue(BAND)
                .min(BigDecimal.valueOf(BANDS - 1L));
        BigDecimal commercial = clamped.subtract(band.multiply(BAND))
                .max(BigDecimal.ZERO)
                .min(BAND.subtract(BigDecimal.ONE));
        // Strictly inside the band: the top of one band must stay below the
        // bottom of the next, so the commercial part can reach at most width-1.
        BigDecimal withinBand = commercial
                .multiply(BigDecimal.valueOf(WORKFLOW_BAND_WIDTH - 1L), CONTEXT)
                .divide(BAND, 0, RoundingMode.FLOOR);
        return band.multiply(BigDecimal.valueOf(WORKFLOW_BAND_WIDTH))
                .add(withinBand)
                .setScale(4, RoundingMode.HALF_UP);
    }

}
