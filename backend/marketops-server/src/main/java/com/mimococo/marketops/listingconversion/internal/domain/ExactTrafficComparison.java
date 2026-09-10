package com.mimococo.marketops.listingconversion.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import org.apache.commons.math3.distribution.BetaDistribution;
import org.apache.commons.math3.exception.MathIllegalArgumentException;
import org.apache.commons.math3.exception.MathIllegalStateException;

/**
 * Clopper-Pearson component intervals and a fixed-weight difference. This
 * computes an interval under the frozen method's assumptions; it cannot
 * qualify the provenance, sampling or control design of supplied counts.
 */
public final class ExactTrafficComparison {
    private static final Set<String> SOURCES = Set.of("ADVERTISING", "ORGANIC");
    private static final double QUANTILE_ACCURACY = 1e-12;
    private ExactTrafficComparison() { }

    public record Result(BigDecimal referenceStandardized, BigDecimal targetStandardized,
                         BigDecimal observedDifference, BigDecimal lowerDifference, BigDecimal upperDifference,
                         String arithmeticStatus) { }

    public static Result compare(Map<String, BigDecimal> frozenWeights,
                                 Map<String, FixedTrafficComparison.Count> reference,
                                 Map<String, FixedTrafficComparison.Count> target,
                                 FrozenComparisonMethod method) {
        if (frozenWeights == null || !frozenWeights.keySet().equals(SOURCES)
                || frozenWeights.values().stream().anyMatch(w -> w == null || w.signum() < 0)
                || frozenWeights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(BigDecimal.ONE) != 0)
            return absent("FROZEN_SOURCE_WEIGHTS_UNQUALIFIED");
        if (method == null) return absent("FROZEN_METHOD_UNAVAILABLE");
        if (reference == null || target == null || !reference.keySet().equals(SOURCES) || !target.keySet().equals(SOURCES))
            return absent("SOURCE_STRATIFICATION_INCOMPLETE");
        BigDecimal prior = BigDecimal.ZERO, current = BigDecimal.ZERO, lower = BigDecimal.ZERO, upper = BigDecimal.ZERO;
        try {
            for (String source : SOURCES.stream().sorted().toList()) {
                BigDecimal weight = frozenWeights.get(source);
                FixedTrafficComparison.Count a = reference.get(source), b = target.get(source);
                if (a == null || b == null) return absent("SOURCE_STRATIFICATION_INCOMPLETE");
                if (weight.signum() == 0) continue;
                if (a.visits() == 0 || b.visits() == 0) return absent("COMPARISON_COHORT_EMPTY");
                // Quantile shapes must be represented exactly by this library.
                if (a.visits() > Integer.MAX_VALUE || b.visits() > Integer.MAX_VALUE)
                    return absent("METHOD_COMPUTATION_RANGE_EXCEEDED");
                prior = prior.add(weight.multiply(rate(a)));
                current = current.add(weight.multiply(rate(b)));
                double[] ai = interval(a, method.componentTailAlpha().doubleValue());
                double[] bi = interval(b, method.componentTailAlpha().doubleValue());
                if (!Double.isFinite(ai[0]) || !Double.isFinite(ai[1]) || !Double.isFinite(bi[0]) || !Double.isFinite(bi[1]))
                    return absent("METHOD_NUMERICAL_FAILURE");
                lower = lower.add(weight.multiply(BigDecimal.valueOf(bi[0] - ai[1])));
                upper = upper.add(weight.multiply(BigDecimal.valueOf(bi[1] - ai[0])));
            }
        } catch (MathIllegalArgumentException | MathIllegalStateException failure) {
            return absent("METHOD_NUMERICAL_FAILURE");
        }
        return new Result(prior, current, current.subtract(prior), lower.setScale(6, RoundingMode.FLOOR),
                upper.setScale(6, RoundingMode.CEILING), "COMPUTED_UNDER_FROZEN_ASSUMPTIONS");
    }

    static double[] interval(FixedTrafficComparison.Count count, double tail) {
        double x = count.retained(), n = count.visits();
        double lower = x == 0 ? 0 : new BetaDistribution(null, x, n-x+1, QUANTILE_ACCURACY).inverseCumulativeProbability(tail);
        double upper = x == n ? 1 : new BetaDistribution(null, x+1, n-x, QUANTILE_ACCURACY).inverseCumulativeProbability(1-tail);
        // Widen for the documented absolute quantile tolerance before rounding
        // the difference outward. Zero/all-success cohorts are valid evidence.
        return new double[] {Math.max(0, lower-2*QUANTILE_ACCURACY), Math.min(1, upper+2*QUANTILE_ACCURACY)};
    }

    private static BigDecimal rate(FixedTrafficComparison.Count count) {
        return BigDecimal.valueOf(count.retained()).divide(BigDecimal.valueOf(count.visits()), 18, RoundingMode.HALF_EVEN);
    }

    private static Result absent(String reason) { return new Result(null, null, null, null, null, reason); }
}
