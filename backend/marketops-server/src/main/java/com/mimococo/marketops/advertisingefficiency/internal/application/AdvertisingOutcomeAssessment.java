package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.DualAxisVerdict;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation;
import com.mimococo.marketops.advertisingefficiency.internal.domain.SalesPreservation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The same frozen thresholds judge both axes and every required sales unit. */
final class AdvertisingOutcomeAssessment {
    record CriticalResult(AdvertisingOutcomeEvidenceService.Unit unit, String state,
                          AdMeasure baseline, AdMeasure observed) { }
    record Assessment(OutcomeEvaluation evaluation, DualAxisVerdict dualAxis,
                      SalesPreservation sales, List<CriticalResult> critical) { }

    static Assessment evaluate(AdvertisingOutcomeEvidenceService.Snapshot before,
            AdvertisingOutcomeEvidenceService.Snapshot after, AdvertisingOutcomePlanningService.Policy policy,
            boolean due) {
        var stage = OutcomeEvaluation.Stage.valueOf(before.stage());
        List<String> reasons = new ArrayList<>();
        boolean comparable = before.confounderDigest().equals(after.confounderDigest())
                && before.blockers().stream().noneMatch(value -> value.startsWith("CONFOUNDER_"))
                && after.blockers().stream().noneMatch(value -> value.startsWith("CONFOUNDER_"));
        Map<java.util.UUID, AdvertisingOutcomeEvidenceService.UnitSales> current = after.units().stream()
                .collect(Collectors.toMap(value -> value.unit().listingVariantId(), Function.identity()));
        List<CriticalResult> critical = new ArrayList<>();
        List<SalesPreservation.UnitResult> terms = new ArrayList<>();
        for (var baseline : before.units()) {
            if (baseline.unit().ruleId() == null) { continue; }
            var observed = current.get(baseline.unit().listingVariantId());
            AdMeasure value = observed == null ? null : observed.sales();
            var status = due ? salesStatus(baseline.sales(), value, policy.salesTolerance())
                    : SalesPreservation.Status.UNRESOLVED;
            terms.add(new SalesPreservation.UnitResult(baseline.unit().listingVariantId().toString(), true, status));
            critical.add(new CriticalResult(baseline.unit(), !due ? "NOT_DUE" : switch (status) {
                case PASSED -> "PASS"; case FAILED -> "REGRESSED"; case UNRESOLVED -> "UNKNOWN";
            }, baseline.sales(), value));
        }
        SalesPreservation sales = SalesPreservation.evaluate(new SalesPreservation.UnitResult("COMPANY_TOTAL", true,
                due ? salesStatus(before.companySales(), after.companySales(), policy.salesTolerance())
                        : SalesPreservation.Status.UNRESOLVED), terms);
        if (stage == OutcomeEvaluation.Stage.OPERATIONAL) {
            // This wire stage is the early Completed-Sales guard. It cannot
            // prove primary efficiency; that requires the 30-day Retained stage.
            boolean covered = after.coverage() != null && policy.minimumCoverage() != null
                    && after.coverage().compareTo(policy.minimumCoverage()) >= 0;
            OutcomeEvaluation.Verdict early;
            if (!due) { early = OutcomeEvaluation.Verdict.NOT_YET_EVALUABLE; reasons.add("EARLY_COMPLETED_SALES_NOT_DUE"); }
            else if (sales.verdict() == SalesPreservation.Verdict.NOT_PRESERVED) { early = OutcomeEvaluation.Verdict.REGRESSED; }
            else if (sales.preserved() && covered) { early = OutcomeEvaluation.Verdict.UNCHANGED; }
            else { early = OutcomeEvaluation.Verdict.INDETERMINATE; reasons.add("EARLY_COMPLETED_SALES_EVIDENCE_UNRESOLVED"); }
            var dual = new DualAxisVerdict(early == OutcomeEvaluation.Verdict.REGRESSED ? DualAxisVerdict.Outcome.REGRESSION : DualAxisVerdict.Outcome.UNRESOLVED,
                    DualAxisVerdict.AxisMovement.UNRESOLVED, DualAxisVerdict.AxisMovement.UNRESOLVED, sales.preserved(), "EARLY_SAFETY_ONLY_NOT_EFFICIENCY_SUCCESS");
            return new Assessment(new OutcomeEvaluation(stage,early,OutcomeEvaluation.GuardState.NOT_APPLICABLE,null,reasons),dual,sales,List.copyOf(critical));
        }
        DualAxisVerdict dual = DualAxisVerdict.evaluate(before.profit().absoluteProfit(), after.profit().absoluteProfit(),
                before.profit().profitPerAdRub(), after.profit().profitPerAdRub(), policy.absoluteDelta(), policy.perRubDelta(),
                policy.absoluteNonWorseningBand(),policy.perRubNonWorseningBand(),policy.comparisonScale(),policy.roundingMode(),
                Boolean.TRUE.equals(policy.boundaryInclusive()),sales.preserved(), sales.evidenceComplete());
        // A measured failure disproves preservation even if another required
        // unit remains unknown; all unknown terms remain in the stored snapshot.
        if (sales.verdict() == SalesPreservation.Verdict.NOT_PRESERVED) {
            dual = new DualAxisVerdict(DualAxisVerdict.Outcome.REGRESSION, dual.absoluteProfit(),
                    dual.profitPerAdRub(), false, sales.reasonCode());
        }
        boolean covered = after.coverage() != null && policy.minimumCoverage() != null
                && after.coverage().compareTo(policy.minimumCoverage()) >= 0;
        var guard = stage == OutcomeEvaluation.Stage.OPERATIONAL ? OutcomeEvaluation.GuardState.NOT_APPLICABLE
                : !due ? OutcomeEvaluation.GuardState.SALES_TOO_RECENT : covered
                ? OutcomeEvaluation.GuardState.SATISFIED : OutcomeEvaluation.GuardState.COVERAGE_INSUFFICIENT;
        if (!policy.complete()) { reasons.add("OUTCOME_POLICY_INCOMPLETE"); }
        if(!before.freshnessProfiles().keySet().containsAll(List.of(AdvertisingOutcomeFreshness.companyKind(before.stage()),
                "OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC","AD_LINKED_SALE_EVENT","COST_AND_FEE"))) {
            reasons.add("FROZEN_BASELINE_INPUT_PROFILES_INCOMPLETE");
        }
        if(after.protectionEvidence()==null || !after.protectionEvidence().exactAffectedScope()) {
            reasons.add("OUTCOME_AFFECTED_SCOPE_UNRESOLVED");
        }
        if (policy.minimumAdSpend()==null || before.officialSpend()==null || after.officialSpend()==null
                || !before.officialSpend().sufficientForWrite() || !after.officialSpend().sufficientForWrite()
                || before.officialSpend().value().compareTo(policy.minimumAdSpend())<0
                || after.officialSpend().value().compareTo(policy.minimumAdSpend())<0) { reasons.add("OUTCOME_DENOMINATOR_BELOW_POLICY_OR_UNKNOWN"); }
        if (!due) { reasons.add("OUTCOME_WINDOW_NOT_DUE"); }
        if (!covered) { reasons.add("OUTCOME_COVERAGE_INSUFFICIENT"); }
        if (!comparable) { reasons.add("CONFOUNDER_CHANGED_OR_UNRESOLVED"); }
        if (after.traffic() == null || after.traffic() < policy.minimumTraffic()) { reasons.add("TRAFFIC_BELOW_MINIMUM_OR_UNKNOWN"); }
        if (!java.util.Objects.equals(before.profit().currencyCode(), after.profit().currencyCode())) { reasons.add("OUTCOME_CURRENCY_CHANGED"); }
        if (dual.outcome() == DualAxisVerdict.Outcome.UNRESOLVED) { reasons.add(dual.reasonCode()); }
        OutcomeEvaluation.Verdict verdict;
        if (due && sales.verdict() == SalesPreservation.Verdict.NOT_PRESERVED) {
            verdict = OutcomeEvaluation.Verdict.REGRESSED;
            reasons.clear();
        } else if (!reasons.isEmpty()) {
            verdict = due ? OutcomeEvaluation.Verdict.INDETERMINATE : OutcomeEvaluation.Verdict.NOT_YET_EVALUABLE;
            dual = new DualAxisVerdict(DualAxisVerdict.Outcome.UNRESOLVED, dual.absoluteProfit(),
                    dual.profitPerAdRub(), sales.preserved(), reasons.getFirst());
        } else {
            verdict = switch (dual.outcome()) {
                case VERIFIED_EFFICIENCY_SUCCESS -> OutcomeEvaluation.Verdict.IMPROVED;
                case REGRESSION -> OutcomeEvaluation.Verdict.REGRESSED;
                case IMPROVED_NOT_HEALTHY, NO_MATERIAL_IMPROVEMENT -> OutcomeEvaluation.Verdict.UNCHANGED;
                case UNRESOLVED -> OutcomeEvaluation.Verdict.INDETERMINATE;
            };
        }
        return new Assessment(new OutcomeEvaluation(stage, verdict, guard, null, reasons.stream().distinct().toList()),
                dual, sales, List.copyOf(critical));
    }

    static String businessOutcome(String cause,boolean protection,AdvertisingOutcomeEvidenceService.Snapshot before,
            AdvertisingOutcomeEvidenceService.Snapshot after,AdvertisingOutcomePlanningService.Policy policy,Assessment result,boolean due) {
        if(!due || !policy.complete()) return "OUTCOME_PENDING";
        boolean completeWindow=after.coverage()!=null && after.coverage().compareTo(policy.minimumCoverage())>=0;
        var protectionProof=after.protectionEvidence();
        boolean exactScope=protectionProof!=null && protectionProof.exactAffectedScope();
        if(protection && exactScope && protectionProof.configurationVerified()
                && after.coverage()!=null && after.coverage().compareTo(BigDecimal.ONE)==0
                && after.officialSpend()!=null && after.officialSpend().sufficientForWrite() && after.officialSpend().value().signum()==0) {
            // Spend is read only from facts wholly inside the frozen observation
            // window. Late charges for prior periods cannot be counted as new exposure.
            return "VERIFIED_AD_EXPOSURE_STOPPED";
        }
        if(protection && List.of("PROMOTED_VARIANT_NOT_SELLABLE","PROMOTED_VARIANT_UNAVAILABLE").contains(cause)) {
            boolean cleared=exactScope && switch(cause) {
                case "PROMOTED_VARIANT_NOT_SELLABLE" -> protectionProof.sellabilityCleared();
                case "PROMOTED_VARIANT_UNAVAILABLE" -> protectionProof.availabilityCleared();
                default -> false;
            };
            // The original cause is frozen on the action. Profit uncertainty or
            // the expected recovery of this cause is not an efficiency verdict.
            return cleared?"VERIFIED_AD_RISK_CLEARED":"PROTECTION_IN_PROGRESS";
        }
        if(result.dualAxis().healthy()) return "VERIFIED_EFFICIENCY_SUCCESS";
        if(result.dualAxis().outcome()==DualAxisVerdict.Outcome.IMPROVED_NOT_HEALTHY) return "IMPROVED_NOT_HEALTHY";
        boolean comparable=before.confounderDigest().equals(after.confounderDigest())
                && java.util.stream.Stream.concat(before.blockers().stream(),after.blockers().stream()).noneMatch(value->value.startsWith("CONFOUNDER_"));
        if(protection && "PROVEN_ADVERTISING_LOSS".equals(cause) && List.of("RETAINED","SETTLED").contains(after.stage())
                && exactScope && comparable && completeWindow && after.officialSpend().sufficientForWrite()
                && after.profit().absoluteProfit().sufficientForWrite() && after.profit().absoluteProfit().value().signum()>=0) {
            return "VERIFIED_AD_RISK_CLEARED";
        }
        if(!comparable && !"OPERATIONAL".equals(after.stage())) return "OUTCOME_CONFOUNDED";
        return protection?"PROTECTION_IN_PROGRESS":"OUTCOME_PENDING";
    }

    private static SalesPreservation.Status salesStatus(AdMeasure before, AdMeasure after, BigDecimal tolerance) {
        if (before == null || after == null || !before.sufficientForWrite() || !after.sufficientForWrite()
                || tolerance == null || tolerance.signum() < 0 || tolerance.compareTo(BigDecimal.ONE) >= 0) {
            return SalesPreservation.Status.UNRESOLVED;
        }
        BigDecimal minimum = before.value().subtract(before.value().abs().multiply(tolerance));
        return after.value().compareTo(minimum) >= 0 ? SalesPreservation.Status.PASSED : SalesPreservation.Status.FAILED;
    }
    private AdvertisingOutcomeAssessment() { }
}
