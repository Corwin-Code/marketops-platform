package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdvertisingContributionProfit;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.operatingfacts.FactWindow;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.shared.Digest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** One evidence reader for frozen baselines and subsequent outcome observations. */
@Component
class AdvertisingOutcomeEvidenceService {
    record Unit(UUID productVariantId, UUID listingVariantId, UUID storeId, UUID ruleId) { }
    record UnitSales(Unit unit, AdMeasure sales) { }
    record Snapshot(String stage, Instant from, Instant to, AdvertisingContributionProfit profit,
                    AdMeasure companySales, List<UnitSales> units, Long traffic, BigDecimal coverage,
                    String confounderDigest, List<UUID> evidenceIds, List<String> blockers,
                    com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile freshnessProfile, AdMeasure officialSpend) { }

    private final OperatingFactQuery companyFacts;
    private final AdvertisingEvidenceRepository adFacts;
    private final AdvertisingEvidenceGatherer gatherer;
    AdvertisingOutcomeEvidenceService(OperatingFactQuery companyFacts, AdvertisingEvidenceRepository adFacts,
            AdvertisingEvidenceGatherer gatherer) {
        this.companyFacts = companyFacts; this.adFacts = adFacts; this.gatherer = gatherer;
    }

    Snapshot snapshot(UUID organization, UUID object, UUID affectedSet, String stage,
            List<Unit> units, Instant from, Instant to, Instant readAt, com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile profile) {
        Duration freshness = profile == null ? Duration.ZERO : Duration.ofMinutes(
                profile.sourceMaxAgeMinutes() == null ? (profile.acceptedFactMaxAgeMinutes() == null ? 0 : profile.acceptedFactMaxAgeMinutes())
                : profile.acceptedFactMaxAgeMinutes() == null ? profile.sourceMaxAgeMinutes()
                : Math.min(profile.sourceMaxAgeMinutes(), profile.acceptedFactMaxAgeMinutes()));
        List<String> blockers = new ArrayList<>();
        List<UUID> evidenceIds = new ArrayList<>();
        List<UnitSales> sales = new ArrayList<>();
        List<String> context = new ArrayList<>();
        SaleStage companyStage = switch (stage) {
            case "OPERATIONAL" -> SaleStage.COMPLETED;
            case "RETAINED" -> SaleStage.RETAINED;
            default -> SaleStage.SETTLED;
        };
        BigDecimal companyTotal = BigDecimal.ZERO;
        String currency = null;
        boolean companyComplete = !units.isEmpty();
        for (Unit unit : units) {
            var fact = adFacts.companySales(organization,unit.listingVariantId(),companyStage.name(),from,to,readAt);
            var coverage = companyFacts.returnQualityEvidence(unit.listingVariantId(), new FactWindow(from, to), freshness, readAt);
            boolean covered=coverage.state().name().startsWith("FRESH_COMPLETE") && coverage.acceptedAt()!=null
                    && profile!=null && (profile.acceptedFactMaxAgeMinutes()==null || !readAt.isAfter(coverage.acceptedAt().plusSeconds(profile.acceptedFactMaxAgeMinutes()*60L)));
            var financial = "SETTLED".equals(stage) ? adFacts.settledCompanySales(organization,unit.listingVariantId(),from,to,readAt) : null;
            BigDecimal netAmount=financial==null?(fact.factCount()==0 && covered?BigDecimal.ZERO:fact.amount()):financial.netAmount();
            String unitCurrency=financial==null?fact.currency():financial.currency();
            boolean complete = (financial==null?true:financial.complete()) && netAmount != null && covered;
            if (financial!=null) { evidenceIds.addAll(financial.evidenceIds()); }
            if (complete && currency != null && unitCurrency != null && !currency.equals(unitCurrency)) { complete = false; }
            AdMeasure value = complete ? AdMeasure.available(netAmount, AdEvidenceState.CANONICAL_CONFIRMED)
                    : AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE);
            sales.add(new UnitSales(unit, value));
            if (!complete) { companyComplete = false; blockers.add("COMPANY_SALES_SCOPE_UNRESOLVED:" + unit.listingVariantId()); }
            else { if(unitCurrency!=null) currency = unitCurrency; companyTotal = companyTotal.add(netAmount); }
            evidenceIds.addAll(fact.provenanceIds());
            if (coverage.snapshotId() != null) { evidenceIds.add(coverage.snapshotId()); }
            var price = companyFacts.latestPrice(unit.listingVariantId(), readAt);
            var sellability = companyFacts.latestSellability(unit.listingVariantId(), readAt);
            var stock = companyFacts.latestStock(unit.listingVariantId(), readAt);
            if (price.isEmpty() || price.get().effectivePrice() == null || sellability.isEmpty() || !stock.evidence().usable()) {
                blockers.add("CONFOUNDER_CONTEXT_UNRESOLVED:" + unit.listingVariantId());
            }
            context.add(unit.listingVariantId() + ":price=" + price.map(valuePrice -> valuePrice.effectivePrice() + ":" + valuePrice.promotionActive()).orElse("UNKNOWN")
                    + ":sellable=" + sellability.map(valueSell -> valueSell.sellable()).orElse("UNKNOWN")
                    + ":stock=" + (stock.evidence().usable() ? (stock.totalAvailable() > 0 ? "POSITIVE" : "ZERO") : "UNKNOWN"));
        }
        var facts = adFacts.objectFacts(organization, object, from, to, readAt);
        var linked = adFacts.linkedSales(organization, object,
                "OPERATIONAL".equals(stage) ? "CANONICAL_AD_LINKED_COMPLETED_SALE" : "CANONICAL_AD_LINKED_RETAINED_SALE",
                from, to, readAt);
        if ("SETTLED".equals(stage)) { linked = adFacts.settledSales(organization, object, from, to, readAt); }
        if (linked.isPresent() && linked.get().lines().stream().anyMatch(line -> !affectedSet.equals(line.affectedSetId()))) {
            blockers.add("ACTION_TIME_AFFECTED_SET_LINEAGE_MISMATCH"); linked = java.util.Optional.empty();
        }
        AdMeasure spend = facts.filter(value -> value.spendAmount() != null && value.everyWindowComplete() && !value.anyCorrectionWindowOpen())
                .map(value -> AdMeasure.available(value.spendAmount(), AdEvidenceState.CANONICAL_CONFIRMED))
                .orElseGet(() -> AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE));
        String adCurrency = facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::currencyCode).orElse(null);
        var economics = gatherer.economicsForSales(linked, from, to, readAt);
        var calculated = AdvertisingAttributedEconomics.calculate(linked.orElse(null), economics, Map.of(), spend, adCurrency);
        var profit = calculated.profit();
        evidenceIds.addAll(calculated.lineage());
        facts.ifPresent(value -> evidenceIds.add(value.latestFactId()));
        // Retention is not settlement. Without exact financial attribution the
        // financial axes stay absent, even when every order was retained.
        boolean settledCosts=economics.values().stream().flatMap(value->value.lineage().stream())
                .filter(value->value.metricCode()!=com.mimococo.marketops.analyticsdecision.MetricCode.RETURN_LOSS_PER_UNIT)
                .allMatch(value->value.confidenceState()==com.mimococo.marketops.analyticsdecision.ConfidenceState.CANONICAL_CONFIRMED);
        if ("SETTLED".equals(stage) && (!settledCosts || !adFacts.settlementAttributionComplete(organization, object, from, to, readAt))) {
            profit = AdvertisingContributionProfit.blocked(adCurrency == null ? "XXX" : adCurrency,
                    List.of("SETTLEMENT_ATTRIBUTION_UNRESOLVED"));
            blockers.add("SETTLEMENT_ATTRIBUTION_UNRESOLVED");
        }
        if (profile == null || (profile.providerIncidentBlocks() && adFacts.providerIncidentOpen(organization, object, readAt))) {
            blockers.add("OUTCOME_PURPOSE_FRESHNESS_UNRESOLVED");
            profit = AdvertisingContributionProfit.blocked(adCurrency, List.of("OUTCOME_PURPOSE_FRESHNESS_UNRESOLVED"));
            companyComplete=false;
            sales=sales.stream().map(value->new UnitSales(value.unit(),AdMeasure.notAvailable(AdEvidenceState.STALE))).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            spend=AdMeasure.notAvailable(AdEvidenceState.STALE);
        }
        if (!profit.resolved()) { blockers.addAll(profit.missingComponentCodes()); }
        AdMeasure total = companyComplete ? AdMeasure.available(companyTotal, AdEvidenceState.CANONICAL_CONFIRMED)
                : AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE);
        return new Snapshot(stage, from, to, profit, total, List.copyOf(sales),
                facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::clicks).orElse(null),
                facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::coverageRatio).orElse(null),
                Digest.ofComponents(context.stream().sorted().toList()), evidenceIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList(),
                blockers.stream().distinct().toList(), profile, spend);
    }
}
