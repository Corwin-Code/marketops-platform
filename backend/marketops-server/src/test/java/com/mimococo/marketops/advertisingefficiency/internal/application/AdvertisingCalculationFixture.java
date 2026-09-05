package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Explicit line-level evidence for pure calculator tests. Never used by a runtime path. */
final class AdvertisingCalculationFixture {
    private AdvertisingCalculationFixture() { }
    static AdvertisingEvidenceGatherer.Evidence withLineage(AdvertisingEvidenceGatherer.Evidence e) {
        var set = e.affectedSet().orElse(null);
        var definition = e.conversion().orElse(null);
        Optional<AdvertisingEvidenceRepository.LinkedSaleAggregate> sales = e.completedSales();
        if (sales.isPresent() && set != null && !set.productVariantIds().isEmpty()
                && !set.platformListingVariantIds().isEmpty() && definition != null && sales.get().eventCount() > 0) {
            var row = sales.get();
            var line = new AdvertisingEvidenceRepository.LinkedSaleLine(row.latestEventId(), row.latestEventId(),
                    set.productVariantIds().getFirst(), set.platformListingVariantIds().getFirst(), set.id(),
                    definition.id(), definition.saleStage(), definition.linkageBasis(), row.eventCount(),
                    row.netSalesAmount(), row.currencyCode(), e.windowStart(), e.asOf(), e.asOf(), e.asOf());
            sales = Optional.of(new AdvertisingEvidenceRepository.LinkedSaleAggregate(row.eventCount(), row.netSalesAmount(),
                    row.currencyCode(), row.distinctVariants(), row.latestEventId(), List.of(line)));
        }
        var facts = e.objectFacts().map(row -> new AdvertisingEvidenceRepository.ObjectFactAggregate(row.spendAmount(),
                row.currencyCode(), row.impressions(), row.views(), row.clicks(), row.providerAttributedOrders(),
                row.providerAttributedRevenue(), row.everyWindowComplete(), row.anyCorrectionWindowOpen(),
                e.asOf(), e.asOf(), row.factCount(), row.latestFactId(), BigDecimal.ONE, e.asOf(), e.windowStart(), e.asOf()));
        Map<UUID, AdvertisingPolicyRepository.AllowableCpaDefinition> cpas = new HashMap<>();
        if (set != null) { e.allowableCpa().ifPresent(cpa -> set.productVariantIds().forEach(id -> cpas.put(id, cpa))); }
        Map<String, AdvertisingPolicyRepository.FreshnessProfile> profiles = new HashMap<>();
        for (String purpose : List.of("PROTECTION_RECOMMENDATION", "PROTECTION_BID_WRITE", "TASK_ACTIVATION")) {
            for (String kind : List.of("OFFICIAL_AD_SPEND", "SELLABILITY", "AVAILABILITY", "COST_AND_FEE", "AD_LINKED_SALE_EVENT", "AD_OBJECT_CONFIGURATION", "AFFECTED_SET")) {
                profiles.put(purpose + ":" + kind, new AdvertisingPolicyRepository.FreshnessProfile(
                        e.object().id(), 1, kind, purpose, 60, 60, 0, 0, true, true, BigDecimal.ONE, "CANONICAL_CONFIRMED", false));
            }
        }
        Map<UUID, AdvertisingEvidenceGatherer.VariantAvailability> availability = new HashMap<>();
        e.variantAvailability().forEach((id, value) -> availability.put(id,
                new AdvertisingEvidenceGatherer.VariantAvailability(value.sellabilityState(), value.availabilityState(),
                        e.asOf(), "CANONICAL_CONFIRMED", List.of(id))));
        Map<UUID, AdvertisingEvidenceGatherer.VariantEconomics> economics = new HashMap<>();
        e.economics().forEach((id, value) -> {
            var components = List.of(value.unitCost(), value.platformFeesPerUnit(), value.returnLossPerUnit(), value.variableTaxPerUnit());
            var codes = List.of(com.mimococo.marketops.analyticsdecision.MetricCode.UNIT_COST,
                    com.mimococo.marketops.analyticsdecision.MetricCode.PLATFORM_FEES_PER_UNIT,
                    com.mimococo.marketops.analyticsdecision.MetricCode.RETURN_LOSS_PER_UNIT,
                    com.mimococo.marketops.analyticsdecision.MetricCode.VARIABLE_TAX_PER_UNIT);
            var lineage = java.util.stream.IntStream.range(0, 4).mapToObj(index -> new com.mimococo.marketops.analyticsdecision.MetricValueView(
                    id, codes.get(index), 2, com.mimococo.marketops.analyticsdecision.SubjectKind.PRODUCT_VARIANT, id,
                    com.mimococo.marketops.analyticsdecision.MetricWindow.D30, e.windowStart(), e.asOf(),
                    components.get(index).valueState(), components.get(index).value(), value.currencyCode(),
                    com.mimococo.marketops.analyticsdecision.ConfidenceState.CANONICAL_CONFIRMED, false,
                    e.asOf(), 0L, "a".repeat(64), e.asOf(), List.of(id))).toList();
            economics.put(id, new AdvertisingEvidenceGatherer.VariantEconomics(value.unitCost(), value.platformFeesPerUnit(),
                    value.returnLossPerUnit(), value.variableTaxPerUnit(), value.currencyCode(), lineage));
        });
        return new AdvertisingEvidenceGatherer.Evidence(e.object(), e.affectedSet(), e.configuration(), facts,
                sales, e.retainedSales(), e.variantShares(), e.containment(), e.conversion(), e.allowableCpa(),
                e.writeQualification(), e.taskQualification(), e.priority(), economics, availability,
                e.windowStart(), e.asOf(), new AdvertisingEvidenceGatherer.Authorities(cpas, profiles, Map.of(), false,
                        List.of(), Map.of(), false));
    }
}
