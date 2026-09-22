package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExactTrafficComparisonTest {
    static final Map<String, BigDecimal> WEIGHTS = Map.of("ADVERTISING", new BigDecimal("0.5"), "ORGANIC", new BigDecimal("0.5"));
    static Map<String, FixedTrafficComparison.Count> counts(long a, long ax, long o, long ox) {
        return Map.of("ADVERTISING", new FixedTrafficComparison.Count(a, ax), "ORGANIC", new FixedTrafficComparison.Count(o, ox));
    }

    @Test void fixedCompositionPreventsAChangedSourceMixFromBecomingGrowth() {
        var result = compare(counts(900, 180, 100, 100), counts(100, 20, 900, 900));
        assertThat(result.referenceStandardized()).isEqualByComparingTo("0.6");
        assertThat(result.targetStandardized()).isEqualByComparingTo("0.6");
        assertThat(result.observedDifference()).isZero();
        assertThat(result.lowerDifference()).isNegative();
        assertThat(result.upperDifference()).isPositive();
    }

    @Test void genuineLargeImprovementAndHighAbsoluteRateDeteriorationHaveDifferentBounds() {
        var improvement = compare(counts(10000, 1000, 10000, 1000), counts(10000, 3000, 10000, 3000));
        assertThat(improvement.lowerDifference()).isGreaterThan(new BigDecimal("0.17"));
        assertThat(improvement.upperDifference()).isLessThan(new BigDecimal("0.23"));
        var deterioration = compare(counts(10000, 9000, 10000, 9000), counts(10000, 8000, 10000, 8000));
        assertThat(deterioration.upperDifference()).isNegative();
        assertThat(deterioration.observedDifference()).isEqualByComparingTo("-0.1");
    }

    @Test void allAndNoSuccessIntervalsRespectClosedFormBinomialEndpoints() {
        double tail = .025;
        double[] zero = ExactTrafficComparison.interval(new FixedTrafficComparison.Count(10, 0), tail);
        double[] all = ExactTrafficComparison.interval(new FixedTrafficComparison.Count(10, 10), tail);
        assertThat(zero[0]).isZero();
        assertThat(zero[1]).isCloseTo(1 - Math.pow(tail, .1), within(1e-10));
        assertThat(all[0]).isCloseTo(Math.pow(tail, .1), within(1e-10));
        assertThat(all[1]).isEqualTo(1);
        double[] half = ExactTrafficComparison.interval(new FixedTrafficComparison.Count(10, 5), tail);
        assertThat(half[0]).isCloseTo(.18708602844739852, within(1e-10));
        assertThat(half[1]).isCloseTo(.8129139715526015, within(1e-10));
    }

    @Test void smallSampleCoverageIsVerifiedAgainstTheBinomialProbabilityMass() {
        // Independent check of interval coverage, including p=0/1. No normal
        // approximation or inverse-beta formula is used by this oracle.
        for (int n = 1; n <= 20; n++) {
            double[][] intervals = new double[n+1][];
            for (int x = 0; x <= n; x++) intervals[x] = ExactTrafficComparison.interval(new FixedTrafficComparison.Count(n, x), .025);
            for (int step = 0; step <= 100; step++) {
                double p = step / 100.0, covered = 0;
                for (int x = 0; x <= n; x++) {
                    long combinations = 1;
                    for (int k = 1; k <= x; k++) combinations = combinations * (n-k+1) / k;
                    if (intervals[x][0] <= p && p <= intervals[x][1])
                        covered += combinations * Math.pow(p, x) * Math.pow(1-p, n-x);
                }
                assertThat(covered).as("n=%s p=%s", n, p).isGreaterThanOrEqualTo(.95 - 1e-12);
            }
        }
    }

    @Test void missingMethodOrStratumDoesNotUseThePointEstimateOrRenormalize() {
        var prior = counts(1000, 100, 1000, 100);
        assertThat(ExactTrafficComparison.compare(WEIGHTS, prior, prior, null).lowerDifference()).isNull();
        assertThat(compare(prior, counts(1000, 900, 0, 0)).observedDifference()).isNull();
        assertThat(ExactTrafficComparison.compare(Map.of("ADVERTISING", BigDecimal.ONE), prior, prior,
                FrozenComparisonMethodTest.method()).observedDifference()).isNull();
    }

    @Test void morePredeclaredComparisonsWidenTheInterval() {
        var method = FrozenComparisonMethod.resolve(FrozenComparisonMethodTest.nodes("0.05"),
                FrozenComparisonMethodTest.JSON.readTree("[{\"code\":\"SMALL\",\"bound\":\"0.02\"}]"), "D14").orElseThrow();
        var prior = counts(1000, 100, 1000, 100);
        var target = counts(1000, 200, 1000, 200);
        var ordinary = compare(prior, target);
        var grouped = ExactTrafficComparison.compare(WEIGHTS, prior, target, method);
        assertThat(grouped.lowerDifference()).isLessThan(ordinary.lowerDifference());
        assertThat(grouped.upperDifference()).isGreaterThan(ordinary.upperDifference());
    }

    @Test void veryLargeCountsRemainUnavailableWithoutNarrowingOverflow() {
        var tooLarge = counts((long) Integer.MAX_VALUE + 1, 100, 100, 10);
        assertThat(compare(tooLarge, tooLarge).arithmeticStatus()).isEqualTo("METHOD_COMPUTATION_RANGE_EXCEEDED");
    }

    static ExactTrafficComparison.Result compare(Map<String, FixedTrafficComparison.Count> prior,
                                                Map<String, FixedTrafficComparison.Count> target) {
        return ExactTrafficComparison.compare(WEIGHTS, prior, target, FrozenComparisonMethodTest.method());
    }
}
