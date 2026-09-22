package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery.Observation;
import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.listingconversion.ProtectionVerdict;
import java.math.BigDecimal;
import java.util.Objects;

/** Exact complete-accounting comparisons; no sampling interval or causal qualification is inferred. */
public final class CanonicalAccountingComparison {
    private CanonicalAccountingComparison() { }

    public static ProtectionVerdict profit(Observation reference,Observation target,BigDecimal maximumDeclineRatio) {
        if (!qualified(reference) || !qualified(target) || !ratio(maximumDeclineRatio)
                || reference.currencyCode()==null || !reference.currencyCode().equals(target.currencyCode())
                || !matchingDefinitions(reference,target)) return ProtectionVerdict.UNDETERMINED;
        BigDecimal minimum=reference.value().subtract(reference.value().abs().multiply(maximumDeclineRatio));
        return target.value().compareTo(minimum)>=0?ProtectionVerdict.PASS:ProtectionVerdict.FAIL;
    }

    public static ProtectionVerdict returns(Observation reference,Observation target,BigDecimal maximumRateIncrease) {
        if (!qualified(reference) || !qualified(target) || !ratio(maximumRateIncrease)
                || !matchingDefinitions(reference,target)) return ProtectionVerdict.UNDETERMINED;
        BigDecimal oldReturns=sum(reference,MetricCode.RETURN_UNITS),oldCompleted=sum(reference,MetricCode.COMPLETED_UNITS);
        BigDecimal newReturns=sum(target,MetricCode.RETURN_UNITS),newCompleted=sum(target,MetricCode.COMPLETED_UNITS);
        if (oldCompleted==null || newCompleted==null || oldReturns==null || newReturns==null
                || oldCompleted.signum()<=0 || newCompleted.signum()<=0) return ProtectionVerdict.UNDETERMINED;
        // Compare exact count products: rounding a displayed rate never changes the boundary.
        BigDecimal deltaNumerator=newReturns.multiply(oldCompleted).subtract(oldReturns.multiply(newCompleted));
        BigDecimal allowed=maximumRateIncrease.multiply(oldCompleted).multiply(newCompleted);
        return deltaNumerator.compareTo(allowed)<=0?ProtectionVerdict.PASS:ProtectionVerdict.FAIL;
    }

    public static ProtectionVerdict unitProfit(com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery.UnitProfitInput input,
                                               java.time.Instant at) {
        return unitProfit(input,at,input==null || input.contributionProfit()==null?null:input.contributionProfit().periodStart(),
                input==null || input.contributionProfit()==null?null:input.contributionProfit().periodEnd());
    }

    public static ProtectionVerdict unitProfit(com.mimococo.marketops.analyticsdecision.CanonicalScopeMetricQuery.UnitProfitInput input,
            java.time.Instant at,java.time.Instant floorPeriodStart,java.time.Instant floorPeriodEnd) {
        if (input==null || input.contributionProfit()==null || input.unitCount()==null || input.requiredProfitPerUnit()==null)
            return ProtectionVerdict.UNDETERMINED;
        var profit=input.contributionProfit();var units=input.unitCount();var floor=input.requiredProfitPerUnit();
        for (var value:java.util.List.of(profit,units,floor)) {
            if (!qualified(new Observation(value.numericValue(),value.currencyCode(),java.util.List.of(),java.util.List.of(value)))
                    || value.computedAt()==null || value.computedAt().isAfter(at) || value.verifiedAt()==null || value.verifiedAt().isAfter(at)
                    || !profit.subjectId().equals(value.subjectId()) || profit.subjectKind()!=value.subjectKind()
                    || profit.window()!=value.window()) return ProtectionVerdict.UNDETERMINED;
        }
        if (!profit.periodStart().equals(units.periodStart()) || !profit.periodEnd().equals(units.periodEnd())
                || !Objects.equals(floor.periodStart(),floorPeriodStart) || !Objects.equals(floor.periodEnd(),floorPeriodEnd))
            return ProtectionVerdict.UNDETERMINED;
        boolean matchingStage=profit.metricCode()==MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT && units.metricCode()==MetricCode.COMPLETED_UNITS
                || profit.metricCode()==MetricCode.SETTLED_CONTRIBUTION_PROFIT && units.metricCode()==MetricCode.SETTLED_UNITS;
        if (!matchingStage || floor.metricCode()!=MetricCode.REQUIRED_PROFIT_PER_UNIT || units.numericValue().signum()<=0
                || profit.currencyCode()==null || !profit.currencyCode().equals(floor.currencyCode()))
            return ProtectionVerdict.UNDETERMINED;
        return profit.numericValue().compareTo(floor.numericValue().multiply(units.numericValue()))>=0
                ?ProtectionVerdict.PASS:ProtectionVerdict.FAIL;
    }

    private static boolean ratio(BigDecimal value) { return value!=null && value.signum()>=0 && value.compareTo(BigDecimal.ONE)<=0; }
    private static boolean qualified(Observation observation) {
        return observation!=null && observation.available() && !observation.components().isEmpty()
                && observation.components().stream().allMatch(v->v.available() && v.numericValue()!=null && !v.estimated()
                    && v.confidenceState()==ConfidenceState.CANONICAL_CONFIRMED && v.inputDigest()!=null
                    && !v.inputDigest().isBlank() && !v.evidenceRefs().isEmpty() && v.verificationRunId()!=null);
    }
    private static boolean matchingDefinitions(Observation reference,Observation target) {
        return reference.components().size()==target.components().size() && reference.components().stream().allMatch(old->
                target.components().stream().filter(current->old.metricCode()==current.metricCode()
                        && old.subjectKind()==current.subjectKind() && old.subjectId().equals(current.subjectId())
                        && old.window()==current.window() && old.definitionVersion()==current.definitionVersion()
                        && Objects.equals(old.currencyCode(),current.currencyCode())).count()==1);
    }
    private static BigDecimal sum(Observation value,MetricCode code) {
        var rows=value.components().stream().filter(v->v.metricCode()==code).toList();
        if (rows.isEmpty() || rows.stream().anyMatch(v->v.numericValue().signum()<0)) return null;
        return rows.stream().map(MetricValueView::numericValue).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
}
