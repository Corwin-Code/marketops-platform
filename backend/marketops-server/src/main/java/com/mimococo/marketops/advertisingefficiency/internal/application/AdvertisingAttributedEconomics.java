package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdvertisingContributionProfit;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.LinkedSaleAggregate;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.AllowableCpaDefinition;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Ad attribution applies the single Metric authority's complete unit costs to exact linked quantities. */
final class AdvertisingAttributedEconomics {
    private AdvertisingAttributedEconomics() { }

    record Result(AdvertisingContributionProfit profit, AdMeasure beforeAdContribution,
                  AdMeasure allowableSpend, List<UUID> lineage) { }

    static Result calculate(LinkedSaleAggregate sales,
            Map<UUID, AdvertisingEvidenceGatherer.VariantEconomics> economics,
            Map<UUID, AllowableCpaDefinition> cpas, AdMeasure spend, String currency) {
        List<String> missing = new ArrayList<>();
        BigDecimal contribution = BigDecimal.ZERO;
        BigDecimal allowable = BigDecimal.ZERO;
        boolean cpaComplete = true;
        AdEvidenceState state = AdEvidenceState.CANONICAL_CONFIRMED;
        List<UUID> lineage = new ArrayList<>();
        if (sales == null || sales.lines().isEmpty()) { missing.add("AD_LINKED_QUANTITY_LINEAGE_UNAVAILABLE"); }
        if (sales != null) {
            if (!java.util.Objects.equals(currency, sales.currencyCode())) { missing.add("MIXED_OR_UNRESOLVED_SALES_CURRENCY"); }
            for (var line : sales.lines()) {
                var costs = economics.get(line.platformListingVariantId());
                if (costs == null) { costs = economics.get(line.productVariantId()); }
                if (costs == null || line.productVariantId() == null || line.netSalesAmount() == null
                        || !java.util.Objects.equals(currency, line.currencyCode())
                        || !java.util.Objects.equals(currency, costs.currencyCode())) {
                    missing.add("LINE_ECONOMICS_OR_MAPPING_UNRESOLVED:" + line.id());
                    continue;
                }
                // PLATFORM_FEES_PER_UNIT v2 already includes commission, fulfilment, delivery,
                // storage, promotion and other variable fees, with complete family coverage.
                // Charging an extra promotion component would charge the same cost twice.
                boolean retained = "CANONICAL_AD_LINKED_RETAINED_SALE".equals(line.saleStage());
                // Retained net sales already exclude cancellations/refusals/returns.
                // The same cohort loss cannot be deducted a second time.
                List<AdMeasure> components = retained ? List.of(costs.unitCost(), costs.platformFeesPerUnit(), costs.variableTaxPerUnit())
                        : List.of(costs.unitCost(), costs.platformFeesPerUnit(), costs.returnLossPerUnit(), costs.variableTaxPerUnit());
                if (components.stream().anyMatch(value -> !value.present())) {
                    missing.add("LINE_COST_COMPONENT_UNAVAILABLE:" + line.id());
                    continue;
                }
                BigDecimal perUnit = components.stream().map(AdMeasure::value).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal lineContribution = line.netSalesAmount().subtract(perUnit.multiply(BigDecimal.valueOf(line.units())));
                contribution = contribution.add(lineContribution);
                for (var value : components) { state = state.weakest(value.evidenceState()); }
                var cpa = cpas.get(line.productVariantId());
                if (cpa == null || !line.saleStage().equals(cpa.saleStage()) || !currency.equals(cpa.currencyCode())
                        || retained && !"INCLUDED_IN_STAGE_CONTRIBUTION".equals(cpa.returnLossTreatment())
                        || !List.of("INCLUDED_IN_STAGE_CONTRIBUTION", "APPLIED_ONCE_ON_TOP").contains(cpa.returnLossTreatment())) {
                    cpaComplete = false;
                } else {
                    allowable = allowable.add(lineContribution.multiply(cpa.targetContributionRetentionRatio()));
                    lineage.add(cpa.id());
                }
                costs.lineage().forEach(value -> lineage.add(value.metricValueId()));
                lineage.add(line.id());
                lineage.add(line.provenanceId());
            }
        }
        if (currency == null || spend == null || !spend.present()) { missing.add("OFFICIAL_SPEND_OR_CURRENCY_UNAVAILABLE"); }
        if (!missing.isEmpty()) {
            var absent = AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED);
            return new Result(AdvertisingContributionProfit.blocked(currency == null ? "XXX" : currency, missing), absent, absent, List.copyOf(lineage));
        }
        state = state.weakest(spend.evidenceState());
        BigDecimal absolute = contribution.subtract(spend.value()).setScale(4, RoundingMode.HALF_UP);
        AdMeasure perRub = spend.value().signum() == 0 ? AdMeasure.undefined(state)
                : AdMeasure.available(absolute.divide(spend.value(), 6, RoundingMode.HALF_UP), state);
        return new Result(new AdvertisingContributionProfit(AdMeasure.available(absolute, state), perRub, currency, List.of()),
                AdMeasure.available(contribution, state),
                cpaComplete ? AdMeasure.available(allowable, state) : AdMeasure.notAvailable(AdEvidenceState.POLICY_BLOCKED),
                lineage.stream().distinct().sorted().toList());
    }
}
