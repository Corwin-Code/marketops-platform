package com.mimococo.marketops.listingconversion.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/**
 * A contrast using the source weights fixed before the action. Actual totals
 * remain separate. This arithmetic does not establish control-group eligibility
 * or a valid repeated-testing schedule: the frozen method must establish those.
 */
public final class FixedTrafficComparison {
    private static final Set<String> SOURCES = Set.of("ADVERTISING", "ORGANIC");
    private FixedTrafficComparison() { }

    public record Count(long visits, long retained) {
        public Count {
            if (visits < 0 || retained < 0 || retained > visits) {
                throw new IllegalArgumentException("retained visits must be a subset of the same visit cohort");
            }
        }
    }

    public record Result(BigDecimal referenceStandardized, BigDecimal targetStandardized,
                         BigDecimal observedDifference, BigDecimal lowerDifference,
                         BigDecimal upperDifference, String qualification) { }

    /**
     * Wilson component intervals use the critical value of the admitted frozen
     * method, including its multiplicity allocation across sources and nodes.
     * No confidence value is supplied by a developer default or an Outcome request.
     * Formula reference: NIST e-Handbook, prc241 (Wilson proportion interval).
     */
    public static Result compare(Map<String, BigDecimal> frozenWeights, Map<String, Count> reference,
                                 Map<String, Count> target, BigDecimal criticalValue,
                                 boolean methodAndScheduleQualified) {
        if (frozenWeights == null || !frozenWeights.keySet().equals(SOURCES)
                || frozenWeights.values().stream().anyMatch(w -> w == null || w.signum() < 0)
                || frozenWeights.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal.ONE) != 0) {
            return absent("FROZEN_SOURCE_WEIGHTS_UNQUALIFIED");
        }
        if (reference == null || target == null || !reference.keySet().equals(SOURCES) || !target.keySet().equals(SOURCES)) {
            return absent("SOURCE_STRATIFICATION_INCOMPLETE");
        }
        BigDecimal prior=BigDecimal.ZERO, current=BigDecimal.ZERO;
        BigDecimal lower=BigDecimal.ZERO, upper=BigDecimal.ZERO;
        boolean boundQualified=methodAndScheduleQualified && criticalValue != null && criticalValue.signum()>0
                && Double.isFinite(criticalValue.doubleValue());
        for (String source : SOURCES.stream().sorted().toList()) {
            BigDecimal weight=frozenWeights.get(source);
            if (weight.signum()==0) continue;
            Count a=reference.get(source), b=target.get(source);
            if (a==null || b==null || a.visits()==0 || b.visits()==0) return absent("COMPARISON_COHORT_EMPTY");
            prior=prior.add(weight.multiply(rate(a)));
            current=current.add(weight.multiply(rate(b)));
            if (boundQualified) {
                double[] ai=wilson(a,criticalValue.doubleValue()), bi=wilson(b,criticalValue.doubleValue());
                if (!Double.isFinite(ai[0]) || !Double.isFinite(ai[1]) || !Double.isFinite(bi[0]) || !Double.isFinite(bi[1])) {
                    boundQualified=false;
                } else {
                    lower=lower.add(weight.multiply(BigDecimal.valueOf(bi[0]-ai[1])));
                    upper=upper.add(weight.multiply(BigDecimal.valueOf(bi[1]-ai[0])));
                }
            }
        }
        return new Result(prior,current,current.subtract(prior),
                boundQualified ? lower.setScale(6,RoundingMode.FLOOR):null,
                boundQualified ? upper.setScale(6,RoundingMode.CEILING):null,
                boundQualified ? "QUALIFIED_METHOD_ARITHMETIC":"METHOD_OR_SCHEDULE_UNQUALIFIED");
    }

    private static BigDecimal rate(Count count) {
        return BigDecimal.valueOf(count.retained()).divide(BigDecimal.valueOf(count.visits()),18,RoundingMode.HALF_EVEN);
    }

    private static double[] wilson(Count count,double z) {
        double n=count.visits(), p=(double) count.retained()/n, z2=z*z;
        double center=(p+z2/(2*n))/(1+z2/n);
        double half=z*Math.sqrt(p*(1-p)/n+z2/(4*n*n))/(1+z2/n);
        return new double[]{Math.max(0,center-half),Math.min(1,center+half)};
    }

    private static Result absent(String reason) { return new Result(null,null,null,null,null,reason); }
}
