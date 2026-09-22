package com.mimococo.marketops.listingconversion.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.analyticsdecision.*;
import com.mimococo.marketops.listingconversion.SimulationAssumptions;
import com.mimococo.marketops.listingconversion.internal.domain.PromotionSimulator;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ListingSimulationInputEvidenceTest {
    final PriceEconomicsQuery query = mock(PriceEconomicsQuery.class);
    final com.mimococo.marketops.operatingfacts.OperatingFactQuery facts = mock(com.mimococo.marketops.operatingfacts.OperatingFactQuery.class);
    final ListingSimulationInputEvidence service = new ListingSimulationInputEvidence(query, facts);
    final Instant at = Instant.parse("2026-09-01T00:00:00Z");
    final UUID organization = UUID.randomUUID(), store = UUID.randomUUID(), account = UUID.randomUUID();
    final ListingFactRepository.ListingContext listing = new ListingFactRepository.ListingContext(
            UUID.randomUUID(), organization, store, account, "OZON", "123", "ACTIVE");
    final SimulationAssumptions context = new SimulationAssumptions(at, at.plusSeconds(86400),
            Map.of(), "Finite declared assumptions");

    private PromotionSimulator.Inputs inputs(String fee) {
        Money zero = Money.zero("RUB");
        return new PromotionSimulator.Inputs(new BigDecimal("100"), BigDecimal.ZERO, false,
                BigDecimal.TEN, List.of(new PromotionSimulator.FeeStep(BigDecimal.ZERO, new BigDecimal(fee))),
                true, "RUB", new PromotionSimulator.Expenses(zero, zero, zero, zero));
    }

    private void profileUntil(Instant expires) {
        Map<FeeFamily,PriceEconomicsProfile.Applicability> coverage = new EnumMap<>(FeeFamily.class);
        for (FeeFamily family : FeeFamily.values())
            coverage.put(family, PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE);
        var profile = new PriceEconomicsProfile(UUID.randomUUID(), 2, organization, "OZON", account,
                store, "FBO", "RUB", at.minusSeconds(60), null,
                PriceEconomicsProfile.VerificationState.ENGINEERING_VERIFIED, at.minusSeconds(30),
                expires, "fixture://fee-profile", BigDecimal.ONE, new BigDecimal("1000"), coverage, List.of());
        when(query.activeFulfillmentModes(store, at)).thenReturn(List.of("FBO"));
        when(query.resolveProfile(organization, "OZON", account, store, "FBO", at))
                .thenReturn(new PriceEconomicsResolution(PriceEconomicsResolution.Status.AVAILABLE, profile, "exact"));
    }

    @Test
    void exactActivityFixedFeeMatchesOnceAndDoesNotBecomeAMissingFeeZero() {
        var declaration=new com.mimococo.marketops.listingconversion.PromotionTerms("OFFICIAL_PROMOTION_PARTICIPATION",
                "activity-1",Map.of("scope","single accepted activity"),false,false,"fixture://terms",Map.of("fee","fixed"));
        var declared=new SimulationAssumptions(at,context.periodEnd(),Map.of(),"Finite fixed-fee scenario",declaration);
        UUID inputId=UUID.randomUUID();
        when(facts.promotionFixedFee(organization,store,declaration.engagementKind(),"activity-1",at,context.periodEnd(),at))
                .thenReturn(Optional.of(new com.mimococo.marketops.operatingfacts.FinanceInputSnapshot(inputId,
                        "PROMOTION_FIXED_FEE",null,Money.of(new BigDecimal("600"),"RUB"),at,UUID.randomUUID())));
        var base=inputs("0");var zero=Money.zero("RUB");
        var withFee=new PromotionSimulator.Inputs(base.listPrice(),base.sellerDiscountRate(),base.discountAlreadyInNetRevenue(),
                base.unitCost(),base.stepFees(),true,"RUB",new PromotionSimulator.Expenses(Money.of(new BigDecimal("600"),"RUB"),zero,zero,zero));
        assertThat(service.fixedFee(listing,withFee,declared,at)).containsEntry("state","ACTIVITY_FIXED_FEE_MATCH")
                .containsEntry("financeInputVersionId",inputId);
        assertThat(service.fixedFee(listing,base,declared,at).get("gaps")).isEqualTo(List.of("FIXED_FEE_INPUT_MISMATCH"));
        assertThat(service.fixedFee(listing,base,context,at).get("gaps")).isEqualTo(List.of("FIXED_FEE_ACTIVITY_UNBOUND"));
        verify(facts,never()).financeInput(any(),any(),any(),any(),any());
    }

    @Test
    void buyerPaymentAndSellerRevenueStayDistinctAndCompensationCannotBeAssumed() {
        var declaration=new com.mimococo.marketops.listingconversion.PromotionTerms("OFFICIAL_PROMOTION_PARTICIPATION",
                "activity-1",Map.of("scope","single accepted activity"),false,false,"fixture://terms",Map.of("fee","fixed"));
        var declared=new SimulationAssumptions(at,context.periodEnd(),Map.of(),"Separate commercial amounts",declaration);
        String digest="a".repeat(64);
        var sources=new HashMap<String,com.mimococo.marketops.operatingfacts.FinanceInputSnapshot>();
        Map<String,String> values=Map.of("PROMOTION_BUYER_PAYMENT_PER_UNIT","80","PROMOTION_SELLER_REVENUE_PER_UNIT","100",
                "PROMOTION_PLATFORM_COMPENSATION_PER_UNIT","20");
        values.forEach((code,amount)->sources.put(code,new com.mimococo.marketops.operatingfacts.FinanceInputSnapshot(
                UUID.randomUUID(),code,null,Money.of(new BigDecimal(amount),"RUB"),at,UUID.randomUUID())));
        when(facts.promotionRevenueInputs(organization,store,listing.id(),declaration.engagementKind(),"activity-1",digest,
                at,context.periodEnd(),at)).thenAnswer(ignored->Map.copyOf(sources));
        var revenue=service.revenue(listing,inputs("8"),declared,digest,at);
        assertThat(revenue.get("state")).isEqualTo("COMMERCIAL_REVENUE_MATCH");
        var coverage=new EnumMap<FeeFamily,PriceEconomicsProfile.Applicability>(FeeFamily.class);
        for (var family:FeeFamily.values()) coverage.put(family,PriceEconomicsProfile.Applicability.VERIFIED_NOT_APPLICABLE);
        coverage.put(FeeFamily.COMMISSION,PriceEconomicsProfile.Applicability.REQUIRED);
        var component=new PriceEconomicsProfile.Component(UUID.randomUUID(),"COMMISSION",FeeFamily.COMMISSION,
                PriceEconomicsProfile.ComponentKind.PERCENTAGE,null,new BigDecimal("0.10"),null,null,
                "fixture://buyer-payment-commission",PriceEconomicsProfile.PriceBasis.BUYER_PAYMENT);
        var profile=new PriceEconomicsProfile(UUID.randomUUID(),2,organization,"OZON",account,store,"FBO","RUB",
                at.minusSeconds(60),context.periodEnd(),PriceEconomicsProfile.VerificationState.ENGINEERING_VERIFIED,
                at.minusSeconds(30),context.periodEnd(),"fixture://commercial-fees",BigDecimal.ONE,
                new BigDecimal("1000"),coverage,List.of(component));
        when(query.activeFulfillmentModes(store,at)).thenReturn(List.of("FBO"));
        when(query.resolveProfile(organization,"OZON",account,store,"FBO",at))
                .thenReturn(new PriceEconomicsResolution(PriceEconomicsResolution.Status.AVAILABLE,profile,"exact"));
        assertThat(service.read(listing,inputs("8"),declared,at,revenue).get("state"))
                .isEqualTo("VARIABLE_FEES_MATCH_PROFILE");
        assertThat(service.read(listing,inputs("10"),declared,at,revenue).get("gaps"))
                .isEqualTo(List.of("PLATFORM_FEE_PROFILE_MISMATCH"));
        sources.remove("PROMOTION_PLATFORM_COMPENSATION_PER_UNIT");
        assertThat(service.revenue(listing,inputs("0"),declared,digest,at).get("gaps"))
                .isEqualTo(List.of("PROMOTION_PLATFORM_COMPENSATION_PER_UNIT:SOURCE_UNQUALIFIED"));
        var missingRevenue=service.revenue(listing,inputs("8"),declared,digest,at);
        assertThat(service.read(listing,inputs("8"),declared,at,missingRevenue).get("state")).isEqualTo("UNQUALIFIED");
        sources.put("PROMOTION_PLATFORM_COMPENSATION_PER_UNIT",new com.mimococo.marketops.operatingfacts.FinanceInputSnapshot(
                UUID.randomUUID(),"PROMOTION_PLATFORM_COMPENSATION_PER_UNIT",null,Money.of(new BigDecimal("19"),"RUB"),at,UUID.randomUUID()));
        assertThat(service.revenue(listing,inputs("0"),declared,digest,at).get("gaps"))
                .isEqualTo(List.of("BUYER_SELLER_COMPENSATION_INCONSISTENT"));
    }

    @Test
    void aCurrentlyValidProfileCannotQualifyAnUncoveredSimulationPeriod() {
        profileUntil(at.plusSeconds(3600));
        var evidence = service.read(listing, inputs("0"), context, at);
        assertThat(evidence.get("state")).isEqualTo("UNQUALIFIED");
        assertThat(evidence.get("gaps")).isEqualTo(List.of("FEE_PROFILE_PERIOD_UNCOVERED"));
    }

    @Test
    void explicitInapplicabilityMatchesZeroButCallerKnownFlagCannotHideMismatch() {
        profileUntil(context.periodEnd());
        var matching = service.read(listing, inputs("0"), context, at);
        assertThat(matching.get("state")).isEqualTo("VARIABLE_FEES_MATCH_PROFILE");
        assertThat(matching).containsKeys("profileId", "profileVersion", "componentIds", "familyCoverage");
        var mismatch = service.read(listing, inputs("1"), context, at);
        assertThat(mismatch.get("state")).isEqualTo("UNQUALIFIED");
        assertThat(mismatch.get("gaps")).isEqualTo(List.of("PLATFORM_FEE_PROFILE_MISMATCH"));
    }

    @Test
    void everyMemberAndKnownFutureCostIntervalMustFitTheDeclaredBound() {
        UUID cheap=UUID.randomUUID(),expensive=UUID.randomUUID();
        Instant middle=at.plusSeconds(43200);
        when(facts.purchaseCosts(organization,cheap,at,context.periodEnd(),at))
                .thenReturn(List.of(costInterval("10",at,null)));
        when(facts.purchaseCosts(organization,expensive,at,context.periodEnd(),at))
                .thenReturn(List.of(costInterval("10",at,middle),costInterval("20",middle,null)));
        var base=inputs("0");
        assertThat(service.periodCosts(organization,List.of(cheap,expensive),base,context,at).get("gaps"))
                .isEqualTo(List.of("UNIT_COST_BOUND_UNDERESTIMATED:"+expensive));
        var conservative=new PromotionSimulator.Inputs(base.listPrice(),base.sellerDiscountRate(),base.discountAlreadyInNetRevenue(),
                new BigDecimal("20"),base.stepFees(),base.feesKnown(),base.currencyCode(),base.expenses());
        assertThat(service.periodCosts(organization,List.of(cheap,expensive),conservative,context,at).get("state"))
                .isEqualTo("PERIOD_MEMBER_COST_BOUND");
        when(facts.purchaseCosts(organization,expensive,at,context.periodEnd(),at))
                .thenReturn(List.of(costInterval("10",at,middle),costInterval("20",middle.plusSeconds(1),null)));
        assertThat(service.periodCosts(organization,List.of(cheap,expensive),conservative,context,at).get("gaps"))
                .isEqualTo(List.of("UNIT_COST_PERIOD_GAP:"+expensive));
        when(facts.purchaseCosts(organization,expensive,at,context.periodEnd(),at))
                .thenReturn(List.of(costInterval("10",at,middle.plusSeconds(1)),costInterval("20",middle,null)));
        assertThat(service.periodCosts(organization,List.of(cheap,expensive),conservative,context,at).get("gaps"))
                .isEqualTo(List.of("UNIT_COST_PERIOD_AMBIGUOUS:"+expensive));
        verify(facts,never()).unitCost(any(),any());
    }

    private com.mimococo.marketops.operatingfacts.CostPeriodSnapshot costInterval(String amount,Instant from,Instant until) {
        return new com.mimococo.marketops.operatingfacts.CostPeriodSnapshot(
                new com.mimococo.marketops.operatingfacts.CostSnapshot(UUID.randomUUID(),Money.of(new BigDecimal(amount),"RUB"),
                        from,UUID.randomUUID()),until);
    }

    @Test
    void emptyScopeOrMissingPeriodsCannotQualifyCostByVacuousAgreement() {
        assertThat(service.periodCosts(organization,List.of(),inputs("0"),context,at).get("state")).isEqualTo("UNQUALIFIED");
        verifyNoInteractions(facts);
        UUID missing=UUID.randomUUID();
        assertThat(service.periodCosts(organization,List.of(missing),inputs("0"),context,at).get("gaps"))
                .isEqualTo(List.of("UNIT_COST_PERIOD_GAP:"+missing));
    }

    @Test
    void malformedStoredMaterialIsDistinctFromAValidCalculationWithUnknownInputs() {
        var mapper=tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        var declaration=new com.mimococo.marketops.listingconversion.PromotionTerms(
                "OFFICIAL_PROMOTION_PARTICIPATION","activity-1",Map.of("scope","exact"),
                false,false,"fixture://terms",Map.of("fixedFee","600 RUB"));
        var declaredContext=new SimulationAssumptions(at,context.periodEnd(),Map.of(),
                "Finite declared assumptions",declaration);
        var original=mapper.valueToTree(Map.of("inputs",ListingSimulationInputEvidence.inputSnapshot(inputs("0")),
                "context",declaredContext,"scenarios",List.of(
                new PromotionSimulator.Scenario("DOWNSIDE",null,true,true))));
        assertThat(original.path("inputs").path("expenses").path("fixedPromotionFee").has("negative")).isFalse();
        assertThat(original.path("inputs").path("expenses").path("fixedPromotionFee").has("positive")).isFalse();
        var decoded=ListingSimulationInputEvidence.declaredMaterial(original,mapper);
        assertThat(decoded).isPresent();
        assertThat(decoded.orElseThrow().context().commercialDeclaration()).isEqualTo(declaration);
        assertThat(decoded.orElseThrow().scenarios().getFirst().quantity()).isNull();
        var missingFlag=original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode)missingFlag.path("scenarios").get(0)).remove("conservative");
        assertThat(ListingSimulationInputEvidence.declaredMaterial(missingFlag,mapper)).isEmpty();
        var corruptTerms=original.deepCopy();
        ((tools.jackson.databind.node.ObjectNode)corruptTerms.path("inputs")).put("sellerDiscountRate",2);
        assertThat(ListingSimulationInputEvidence.declaredMaterial(corruptTerms,mapper)).isEmpty();
        assertThat(ListingSimulationInputEvidence.declaredMaterial(mapper.createObjectNode(),mapper)).isEmpty();
        verifyNoInteractions(query,facts);
    }

    @Test
    void multipleFulfillmentModesNeverSelectTheFavorableProfile() {
        when(query.activeFulfillmentModes(store, at)).thenReturn(List.of("FBO", "FBS"));
        var evidence = service.read(listing, inputs("0"), context, at);
        assertThat(evidence.get("state")).isEqualTo("UNQUALIFIED");
        verify(query, never()).resolveProfile(any(), any(), any(), any(), any(), any());
    }

    @Test
    void overallQualificationIsAConjunctionAndCannotBorrowAConditionalBoolean() {
        var qualified=Map.<String,Object>of("state","VARIABLE_FEES_MATCH_PROFILE");
        var fixed=Map.<String,Object>of("state","ACTIVITY_FIXED_FEE_MATCH");
        var revenue=Map.<String,Object>of("state","COMMERCIAL_REVENUE_MATCH");
        var costs=Map.<String,Object>of("state","PERIOD_MEMBER_COST_BOUND");
        var demand=Map.<String,Object>of("state","ACCEPTED_NECESSARY_SCENARIOS_MATCH");
        var profit=Map.<String,Object>of("state","ACCEPTED_PROFIT_REFERENCE_BOUND");
        var complete=new tools.jackson.databind.ObjectMapper().valueToTree(Map.of("coverage","QUALIFIED_COMPLETE"));
        assertThat(ListingSimulationInputEvidence.qualification(qualified,fixed,revenue,costs,demand,profit,
                complete,true,true,true)).isEqualTo(ListingSimulationInputEvidence.QUALIFIED);
        assertThat(ListingSimulationInputEvidence.qualification(qualified,fixed,revenue,costs,demand,profit,
                complete,true,true,null)).isEqualTo("UNQUALIFIED");
        assertThat(ListingSimulationInputEvidence.qualification(qualified,fixed,revenue,costs,demand,profit,
                new tools.jackson.databind.ObjectMapper().valueToTree(Map.of("coverage","KNOWN_RECORDS_ONLY")),
                true,true,true)).isEqualTo("UNQUALIFIED");
    }
}
