package com.mimococo.marketops.advertisingefficiency.internal.application;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdvertisingContributionProfit;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.SaleStage;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdCaseCalculation.PurposeEvidence;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile;
import java.math.BigDecimal;
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
    record ProtectionEvidence(boolean exactAffectedScope, boolean configurationVerified,
                              boolean sellabilityCleared, boolean availabilityCleared,
                              boolean sellabilityWindowComplete, boolean availabilityWindowComplete) {
        ProtectionEvidence(boolean exactAffectedScope,boolean configurationVerified,boolean sellabilityCleared,boolean availabilityCleared) {
            this(exactAffectedScope,configurationVerified,sellabilityCleared,availabilityCleared,sellabilityCleared,availabilityCleared);
        }
    }
    record ActionIdentity(UUID semanticProfileId,Integer lineageGeneration) { }
    record Snapshot(String stage, Instant from, Instant to, AdvertisingContributionProfit profit,
                    AdMeasure companySales, List<UnitSales> units, Long traffic, BigDecimal coverage,
                    String confounderDigest, List<UUID> evidenceIds, List<String> blockers,
                    FreshnessProfile freshnessProfile, AdMeasure officialSpend, Map<String,FreshnessProfile> freshnessProfiles,
                    List<PurposeEvidence> purposeEvidence, ProtectionEvidence protectionEvidence, String originalCause,
                    ActionIdentity originalIdentity) {
        Snapshot withCause(String cause) {
            return new Snapshot(stage,from,to,profit,companySales,units,traffic,coverage,confounderDigest,evidenceIds,blockers,
                    freshnessProfile,officialSpend,freshnessProfiles,purposeEvidence,protectionEvidence,cause,originalIdentity);
        }
        Snapshot withIdentity(ActionIdentity identity) {
            return new Snapshot(stage,from,to,profit,companySales,units,traffic,coverage,confounderDigest,evidenceIds,blockers,
                    freshnessProfile,officialSpend,freshnessProfiles,purposeEvidence,protectionEvidence,originalCause,identity);
        }
        Snapshot(String stage,Instant from,Instant to,AdvertisingContributionProfit profit,AdMeasure companySales,
                List<UnitSales> units,Long traffic,BigDecimal coverage,String confounderDigest,List<UUID> evidenceIds,
                List<String> blockers,FreshnessProfile freshnessProfile,AdMeasure officialSpend,Map<String,FreshnessProfile> freshnessProfiles,
                List<PurposeEvidence> purposeEvidence,ProtectionEvidence protectionEvidence,String originalCause) {
            this(stage,from,to,profit,companySales,units,traffic,coverage,confounderDigest,evidenceIds,blockers,freshnessProfile,
                    officialSpend,freshnessProfiles,purposeEvidence,protectionEvidence,originalCause,null);
        }
        Snapshot {
            freshnessProfiles=freshnessProfiles==null ? (freshnessProfile==null?Map.of():Map.of(freshnessProfile.evidenceKind(),freshnessProfile)) : Map.copyOf(freshnessProfiles);
            purposeEvidence=purposeEvidence==null?List.of():List.copyOf(purposeEvidence);
        }
        Snapshot(String stage,Instant from,Instant to,AdvertisingContributionProfit profit,AdMeasure companySales,
                List<UnitSales> units,Long traffic,BigDecimal coverage,String confounderDigest,List<UUID> evidenceIds,
                List<String> blockers,FreshnessProfile freshnessProfile,AdMeasure officialSpend) {
            this(stage,from,to,profit,companySales,units,traffic,coverage,confounderDigest,evidenceIds,blockers,freshnessProfile,
                    officialSpend,null,List.of(),null,null);
        }
    }

    private final OperatingFactQuery companyFacts;
    private final AdvertisingEvidenceRepository adFacts;
    private final AdvertisingEvidenceGatherer gatherer;
    private final AdvertisingOutcomeFreshness freshnessRules;
    private final AdvertisingProtectionWindow protectionWindow;
    AdvertisingOutcomeEvidenceService(OperatingFactQuery companyFacts, AdvertisingEvidenceRepository adFacts,
            AdvertisingEvidenceGatherer gatherer, AdvertisingOutcomeFreshness freshnessRules, AdvertisingProtectionWindow protectionWindow) {
        this.companyFacts = companyFacts; this.adFacts = adFacts; this.gatherer = gatherer; this.freshnessRules=freshnessRules; this.protectionWindow=protectionWindow;
    }

    Snapshot bindOriginalIdentity(UUID organization,UUID object,Snapshot observed,ActionIdentity identity,Instant at) {
        boolean current=identity!=null && identity.semanticProfileId()!=null && identity.lineageGeneration()!=null
                && adFacts.object(organization,object).filter(value->value.semanticProfileId().equals(identity.semanticProfileId())
                    && value.lineageGeneration()==identity.lineageGeneration()).isPresent()
                && protectionWindow.configurationIdentity(organization,object,observed.from(),observed.to(),at,
                        identity.semanticProfileId(),identity.lineageGeneration());
        if(current) return observed.withIdentity(identity);
        List<String> blockers=new ArrayList<>(observed.blockers());blockers.add("OUTCOME_ORIGINAL_ACTION_IDENTITY_UNRESOLVED");
        return new Snapshot(observed.stage(),observed.from(),observed.to(),observed.profit(),observed.companySales(),observed.units(),
                observed.traffic(),observed.coverage(),observed.confounderDigest(),observed.evidenceIds(),List.copyOf(blockers),
                observed.freshnessProfile(),observed.officialSpend(),observed.freshnessProfiles(),observed.purposeEvidence(),
                new ProtectionEvidence(false,false,false,false),observed.originalCause(),identity);
    }

    Snapshot snapshot(UUID organization, UUID object, UUID affectedSet, String stage,
            List<Unit> units, Instant from, Instant to, Instant readAt, Map<String,FreshnessProfile> profiles) {
        FreshnessProfile profile=profiles.get(AdvertisingOutcomeFreshness.companyKind(stage));
        boolean incident=adFacts.providerIncidentOpen(organization,object,readAt);
        List<PurposeEvidence> qualifications=new ArrayList<>();
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
            var coverage = freshnessRules.companyCoverage(organization,unit.listingVariantId(),stage,from,to,readAt);
            boolean reportComplete=coverage.complete();
            var companyGrade=freshnessRules.qualify(organization,object,stage,AdvertisingOutcomeFreshness.companyKind(stage),profile,
                    coverage.source(),coverage.acceptedAt(),reportComplete,reportComplete,
                    reportComplete?BigDecimal.ONE:null,to,readAt,incident);
            qualifications.add(companyGrade);
            boolean covered=reportComplete && companyGrade.eligible();
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
        Instant source=facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::earliestSourceTime).orElse(null);
        Instant accepted=facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::oldestAcceptedAt).orElse(null);
        BigDecimal sourceCoverage=facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::coverageRatio).orElse(null);
        boolean complete=facts.map(value->value.everyWindowComplete() && value.currencyCode()!=null
                && value.latestSourceTime()!=null && !value.latestSourceTime().isAfter(readAt)).orElse(false);
        boolean closed=facts.map(value->!value.anyCorrectionWindowOpen()).orElse(false);
        var spendGrade=freshnessRules.qualify(organization,object,stage,"OFFICIAL_AD_SPEND",profiles.get("OFFICIAL_AD_SPEND"),
                source,accepted,complete && facts.map(value->value.spendAmount()!=null).orElse(false),closed,sourceCoverage,to,readAt,incident);
        var trafficGrade=freshnessRules.qualify(organization,object,stage,"OFFICIAL_AD_TRAFFIC",profiles.get("OFFICIAL_AD_TRAFFIC"),
                source,accepted,complete && facts.map(value->value.clicks()!=null).orElse(false),closed,sourceCoverage,to,readAt,incident);
        qualifications.add(spendGrade);qualifications.add(trafficGrade);
        // Qualification under a permissive Profile cannot upgrade incomplete facts
        // into canonical terminal evidence. Preserve the actual source state.
        AdMeasure spend=spendGrade.eligible() && complete && closed && facts.orElseThrow().spendAmount()!=null
                ? AdMeasure.available(facts.orElseThrow().spendAmount(),AdEvidenceState.CANONICAL_CONFIRMED)
                : AdMeasure.notAvailable(spendGrade.eligible()?AdEvidenceState.INCOMPLETE:AdEvidenceState.STALE);
        if(spendGrade.eligible() && !spend.sufficientForWrite()) blockers.add("OFFICIAL_AD_SPEND_NOT_CANONICAL_COMPLETE_CLOSED");
        String adCurrency = facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::currencyCode).orElse(null);
        var economics = gatherer.economicsForSales(linked, from, to, readAt);
        var lines=linked.map(AdvertisingEvidenceRepository.LinkedSaleAggregate::lines).orElse(List.of());
        boolean linkedComplete=!lines.isEmpty() && lines.stream().allMatch(line->line.productVariantId()!=null
                && line.sourceTime()!=null && !line.sourceTime().isAfter(readAt)
                && line.recordedAt()!=null && !line.recordedAt().isAfter(readAt));
        var linkedGrade=freshnessRules.qualify(organization,object,stage,"AD_LINKED_SALE_EVENT",profiles.get("AD_LINKED_SALE_EVENT"),
                lines.stream().map(AdvertisingEvidenceRepository.LinkedSaleLine::sourceTime).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null),
                lines.stream().map(AdvertisingEvidenceRepository.LinkedSaleLine::recordedAt).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null),
                linkedComplete,closed,sourceCoverage,to,readAt,incident);
        var metricInputs=economics.values().stream().flatMap(value->value.lineage().stream())
                .filter(value->"OPERATIONAL".equals(stage) || value.metricCode()!=com.mimococo.marketops.analyticsdecision.MetricCode.RETURN_LOSS_PER_UNIT).toList();
        Instant computed=metricInputs.stream().map(value->value.verifiedAt()).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        boolean costsComplete=!metricInputs.isEmpty() && metricInputs.stream().allMatch(value->value.available()
                && value.confidenceState()==com.mimococo.marketops.analyticsdecision.ConfidenceState.CANONICAL_CONFIRMED
                && value.computedAt()!=null && !value.computedAt().isAfter(readAt)
                && value.verifiedAt()!=null && !value.verifiedAt().isAfter(readAt));
        var costsGrade=freshnessRules.qualify(organization,object,stage,"COST_AND_FEE",profiles.get("COST_AND_FEE"),
                computed,computed,costsComplete,costsComplete,costsComplete?BigDecimal.ONE:null,null,readAt,incident);
        qualifications.add(linkedGrade);qualifications.add(costsGrade);
        var calculated = AdvertisingAttributedEconomics.calculate(linked.orElse(null), economics, Map.of(), spend, adCurrency);
        var profit = calculated.profit();
        if(!linkedComplete || !costsComplete || !linkedGrade.eligible() || !costsGrade.eligible()) profit=AdvertisingContributionProfit.blocked(adCurrency==null?"XXX":adCurrency,
                java.util.stream.Stream.concat(java.util.stream.Stream.concat(linkedGrade.reasonCodes().stream(),costsGrade.reasonCodes().stream()),
                    !linkedComplete || !costsComplete ? java.util.stream.Stream.of("OUTCOME_ECONOMIC_INPUT_INCOMPLETE") : java.util.stream.Stream.empty()).distinct().toList());
        evidenceIds.addAll(calculated.lineage());
        metricInputs.stream().map(value->value.verificationRunId()).filter(java.util.Objects::nonNull)
                .forEach(evidenceIds::add);
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
        var configuration=adFacts.currentConfiguration(organization,object,readAt);
        boolean configComplete=configuration.map(value->value.observedBidAmount()!=null
                && List.of("OFFICIAL_API_READBACK","OFFICIAL_CONFIGURATION_EXPORT").contains(value.evidenceGrade())).orElse(false);
        var configurationProof=protectionWindow.configurationProof(organization,object,from,to,readAt);
        configComplete &= configurationProof.complete();
        evidenceIds.addAll(configurationProof.evidenceIds());
        var configurationGrade=freshnessRules.qualify(organization,object,stage,"AD_OBJECT_CONFIGURATION",profiles.get("AD_OBJECT_CONFIGURATION"),
                configurationProof.source(),configurationProof.accepted(),configComplete,configComplete,
                configComplete?BigDecimal.ONE:null,null,readAt,incident);
        var currentSet=adFacts.affectedSet(organization,object,readAt);
        boolean exact=protectionWindow.exactScope(organization,object,affectedSet,from,to,readAt)
                && protectionWindow.mappingStable(organization,units,from,to,readAt);
        var setGrade=freshnessRules.qualify(organization,object,stage,"AFFECTED_SET",profiles.get("AFFECTED_SET"),
                currentSet.map(AdvertisingEvidenceRepository.AffectedSetRow::resolvedAt).orElse(null),
                currentSet.map(AdvertisingEvidenceRepository.AffectedSetRow::acceptedAt).orElse(null),exact,exact,exact?BigDecimal.ONE:null,null,readAt,incident);
        qualifications.add(configurationGrade);qualifications.add(setGrade);
        Map<String,Boolean> cleared=new java.util.HashMap<>();
        Map<String,Boolean> windowComplete=new java.util.HashMap<>();
        List<UUID> advertisedListings=protectionWindow.affectedListings(organization,object,affectedSet,units);
        for(String kind:List.of("SELLABILITY","AVAILABILITY","PRICE_AND_PROMOTION")) {
            var proof=protectionWindow.read(organization,"PRICE_AND_PROMOTION".equals(kind)?units.stream().map(Unit::listingVariantId).distinct().toList():advertisedListings,kind,from,to,readAt);
            var grade=freshnessRules.qualify(organization,object,stage,kind,profiles.get(kind),proof.source(),proof.accepted(),
                    proof.complete(),proof.complete(),proof.complete()?BigDecimal.ONE:null,to,readAt,incident);
            qualifications.add(grade);evidenceIds.addAll(proof.evidenceIds());cleared.put(kind,grade.eligible() && proof.complete() && proof.safe());
            windowComplete.put(kind,proof.complete());
            if(!grade.eligible() || !proof.complete() || "PRICE_AND_PROMOTION".equals(kind) && !proof.safe()) blockers.add("CONFOUNDER_FRESHNESS_OR_WINDOW_UNRESOLVED:"+kind);
        }
        qualifications.forEach(grade->blockers.addAll(grade.reasonCodes()));
        var protection=new ProtectionEvidence(exact && setGrade.eligible(),configComplete && configurationGrade.eligible()
                && protectionWindow.configurationWindow(organization,object,from,to,readAt),cleared.get("SELLABILITY"),cleared.get("AVAILABILITY"),
                windowComplete.get("SELLABILITY"),windowComplete.get("AVAILABILITY"));
        if (!profit.resolved()) { blockers.addAll(profit.missingComponentCodes()); }
        AdMeasure total = companyComplete ? AdMeasure.available(companyTotal, AdEvidenceState.CANONICAL_CONFIRMED)
                : AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE);
        return new Snapshot(stage, from, to, profit, total, List.copyOf(sales),
                trafficGrade.eligible() && complete && closed?facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::clicks).orElse(null):null,
                facts.map(AdvertisingEvidenceRepository.ObjectFactAggregate::coverageRatio).orElse(null),
                Digest.ofComponents(context.stream().sorted().toList()), evidenceIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList(),
                blockers.stream().distinct().toList(), profile, spend, profiles, List.copyOf(qualifications), protection,null);
    }
}
