package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure;
import com.mimococo.marketops.advertisingefficiency.internal.domain.MaxCpc;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real calculator unit evidence plus independently specified arithmetic boundaries; no traffic forecast. */
class AdvertisingEconomicHeadroomTest {
    private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000081");
    private static final UUID VARIANT=UUID.fromString("00000000-0000-0000-0000-000000000082");
    private static final Instant AT=Instant.parse("2026-09-04T00:00:00Z");
    private static final String STAGE="CANONICAL_AD_LINKED_RETAINED_SALE";
    private final AdvertisingCaseCalculationService calculator=new AdvertisingCaseCalculationService(mock(AdvertisingEvidenceGatherer.class));

    @ParameterizedTest @CsvSource({"20,CURRENCY_MAJOR", "2000,CURRENCY_MINOR"})
    void actualObservedCohortProducesTheSameMajorCurrencySpaceForBothNativeUnits(String bid,String unit) {
        var evidence=evidence(bid,unit,500L,true);
        var scored=calculator.calculateFrom(evidence).cases().getFirst();
        var economics=AdvertisingAttributedEconomics.calculate(evidence.completedSales().orElseThrow(),
                evidence.economics(),evidence.authorities().cpaByVariant(),measure("6000"),"RUB");
        assertThat(economics.allowableSpend().value()).isEqualByComparingTo("12500");
        assertThat(scored.eligibleTraffic().value()).isEqualByComparingTo("500");
        assertThat(scored.maxCpc().ceiling().amount()).isEqualByComparingTo("25");
        assertThat(scored.contributionProfit().value()).isEqualByComparingTo("19000");
        assertThat(scored.recoverableProfit().value()).isEqualByComparingTo("2500.0000");
        assertThat(scored.recoverableProfit().sufficientForWrite()).isTrue();
    }

    @ParameterizedTest @CsvSource({"25,0", "30,1000"})
    void equalAndAboveCeilingKeepTheExistingZeroAndExcessSpendDiagnostic(String bid,String expected) {
        var scored=calculator.calculateFrom(evidence(bid,"CURRENCY_MAJOR",500L,true)).cases().getFirst();
        assertThat(scored.maxCpc().ceiling().amount()).isEqualByComparingTo("25");
        assertThat(scored.recoverableProfit().value()).isEqualByComparingTo(expected);
    }

    @ParameterizedTest @ValueSource(strings={"MISSING_TRAFFIC","ZERO_TRAFFIC","INCOMPLETE_TRAFFIC","UNKNOWN_NATIVE_UNIT","MISSING_CPA","OTHER_COHORT"})
    void missingOrUnqualifiedInputsNeverInventUnderCeilingSpace(String defect) {
        var e=evidence("20",defect.equals("UNKNOWN_NATIVE_UNIT")?"UNKNOWN":"CURRENCY_MAJOR",
                defect.equals("MISSING_TRAFFIC")?null:defect.equals("ZERO_TRAFFIC")?0L:500L,
                !defect.equals("INCOMPLETE_TRAFFIC"));
        Optional<AdvertisingEvidenceRepository.LinkedSaleAggregate> sales=e.completedSales();
        var authorities=e.authorities();
        if(defect.equals("MISSING_CPA")) authorities=new AdvertisingEvidenceGatherer.Authorities(Map.of(),authorities.freshness(),
                authorities.sustainedPeriods(),authorities.comparableBaseline(),authorities.metricValueIds(),authorities.rankContexts(),
                authorities.compensationPending(),authorities.providerIncidentOpen(),authorities.criticalSignals(),
                authorities.canonicalCompletedEventCount(),authorities.outcomePolicies());
        if(defect.equals("OTHER_COHORT")) {
            var original=sales.orElseThrow();var line=original.lines().getFirst();
            var foreign=new AdvertisingEvidenceRepository.LinkedSaleLine(line.id(),line.provenanceId(),line.productVariantId(),
                    line.platformListingVariantId(),line.affectedSetId(),line.conversionDefinitionId(),line.saleStage(),
                    line.linkageBasis(),line.units(),line.netSalesAmount(),line.currencyCode(),
                    e.windowStart().minusSeconds(1),line.periodEnd(),line.sourceTime(),line.recordedAt());
            sales=Optional.of(new AdvertisingEvidenceRepository.LinkedSaleAggregate(original.eventCount(),original.netSalesAmount(),
                    original.currencyCode(),original.distinctVariants(),original.latestEventId(),List.of(foreign)));
        }
        e=new AdvertisingEvidenceGatherer.Evidence(e.object(),e.affectedSet(),e.configuration(),e.objectFacts(),sales,e.retainedSales(),
                e.variantShares(),e.containment(),e.conversion(),e.allowableCpa(),e.writeQualification(),e.taskQualification(),e.priority(),
                e.economics(),e.variantAvailability(),e.windowStart(),e.asOf(),authorities);
        assertThat(calculator.calculateFrom(e).cases().getFirst().recoverableProfit().present()).as(defect).isFalse();
    }

    @ParameterizedTest @CsvSource({
            "25,20,500,12500,2500.0000", // the observed-cohort positive
            "25,20,500,11000,1000.0000", // the unrounded allowable total is the tighter cap
            "25,20,500,9999,0.0000",    // cap below the observed bid footprint is no opportunity
            "25,20,500,13000,2500.0000",// a larger total cannot escape N * MaxCpc
            "1.0001,1,1,1.00009,0.0000",
            "1.0001,1,1,1.00019,0.0001",
            "2,1,1,1.12349,0.1234"})
    void bothCapsAndOneFinalMoneyFloorHaveIndependentNumericOracles(String c,String b,String n,String a,String expected) {
        var result=AdvertisingCaseCalculationService.recoverableProfitOf(ceiling(c),measure(b),measure("6000"),measure(n),measure(a));
        assertThat(result.value()).isEqualByComparingTo(expected);
        assertThat(result.value().scale()).isEqualTo(4);
    }

    @ParameterizedTest @ValueSource(strings={"BID","SPEND","TRAFFIC","ALLOWABLE","CEILING"})
    void aPresentEstimatedInputCannotBePromotedByTheNewBranch(String axis) {
        var estimated=AdMeasure.available(new BigDecimal(axis.equals("TRAFFIC")?"500":axis.equals("ALLOWABLE")?"12500":"20"),
                AdEvidenceState.PROVISIONAL_OR_ESTIMATED);
        var result=AdvertisingCaseCalculationService.recoverableProfitOf(axis.equals("CEILING")
                        ? new MaxCpc(SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE,Money.of(new BigDecimal("25"),"RUB"),AdEvidenceState.PROVISIONAL_OR_ESTIMATED,MaxCpc.Absence.NONE):ceiling("25"),
                axis.equals("BID")?estimated:measure("20"),axis.equals("SPEND")?estimated:measure("6000"),
                axis.equals("TRAFFIC")?estimated:measure("500"),axis.equals("ALLOWABLE")?estimated:measure("12500"));
        assertThat(result.present()).isFalse();
    }

    @Test void operationalInputRetainsItsExistingGradeInsteadOfBecomingCanonical() {
        var result=AdvertisingCaseCalculationService.recoverableProfitOf(ceiling("25"),
                AdMeasure.available(new BigDecimal("20"),AdEvidenceState.OPERATIONAL),measure("6000"),measure("500"),measure("12500"));
        assertThat(result.value()).isEqualByComparingTo("2500");
        assertThat(result.evidenceState()).isEqualTo(AdEvidenceState.OPERATIONAL);
    }

    @ParameterizedTest @CsvSource({"20,USD", "25,USD", "30,USD", "20,NULL"})
    void actualCalculatorDoesNotTreatForeignOrMissingBidCurrencyAsTheCohortCurrency(String bid,String nativeCurrency) {
        var original=evidence(bid,"CURRENCY_MAJOR",500L,true);
        var positive=calculator.calculateFrom(original).cases().getFirst();
        assertThat(positive.recoverableProfit().present()).isTrue();
        var changed=withConfiguration(original,nativeCurrency.equals("NULL")?null:nativeCurrency,"OFFICIAL_API_READBACK");
        var refused=calculator.calculateFrom(changed).cases().getFirst();
        assertThat(refused.maxCpc()).isEqualTo(positive.maxCpc());
        assertThat(refused.maxCpc().ceiling().currencyCode()).isEqualTo("RUB");
        assertThat(refused.contributionProfit()).isEqualTo(positive.contributionProfit());
        assertThat(refused.recoverableProfit().present()).isFalse();
    }

    @ParameterizedTest @CsvSource({"25,0", "30,1000"})
    void actualCalculatorPreservesDiagnosticNumbersButNotCanonicalGradeForAnEstimatedBid(String bid,String amount) {
        var original=evidence(bid,"CURRENCY_MAJOR",500L,true);
        var estimated=withConfiguration(original,"RUB","EXECUTOR_SELF_REPORT");
        var result=calculator.calculateFrom(estimated);
        var value=result.cases().getFirst().recoverableProfit();
        assertThat(value.value()).isEqualByComparingTo(amount);
        assertThat(value.evidenceState()).isEqualTo(AdEvidenceState.PROVISIONAL_OR_ESTIMATED);
        assertThat(value.sufficientForWrite()).isFalse();
    }

    @ParameterizedTest @CsvSource({"25,0,BID", "30,1000,BID", "25,0,SPEND", "30,1000,SPEND"})
    void equalAndAboveCeilingDiagnosticsRetainTheWeakestActuallyConsumedBidOrSpend(String bid,String amount,String axis) {
        var lowBid=AdMeasure.available(new BigDecimal(bid),AdEvidenceState.PROVISIONAL_OR_ESTIMATED);
        var lowSpend=AdMeasure.available(new BigDecimal("6000"),AdEvidenceState.PROVISIONAL_OR_ESTIMATED);
        // Traffic and allowable spend are absent because this preserved diagnostic
        // does not consume them; the grade reflects its three actual inputs.
        var absent=AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);
        var value=AdvertisingCaseCalculationService.recoverableProfitOf(ceiling("25"),
                axis.equals("BID")?lowBid:measure(bid),axis.equals("SPEND")?lowSpend:measure("6000"),absent,absent);
        assertThat(value.value()).isEqualByComparingTo(amount);
        assertThat(value.evidenceState()).isEqualTo(AdEvidenceState.PROVISIONAL_OR_ESTIMATED);
        assertThat(value.sufficientForWrite()).isFalse();
    }

    private static AdvertisingEvidenceGatherer.Evidence withConfiguration(AdvertisingEvidenceGatherer.Evidence e,
            String currency,String grade) {
        var row=e.configuration().orElseThrow();
        var changed=new AdvertisingEvidenceRepository.ConfigurationRow(row.id(),row.provenanceId(),row.semanticProfileId(),
                row.lineageGeneration(),row.observedBidAmount(),currency,row.bidUnitCode(),row.observedStatus(),
                row.observedBiddingMode(),grade,row.observedAt(),row.sourceTime(),row.acceptedAt());
        return new AdvertisingEvidenceGatherer.Evidence(e.object(),e.affectedSet(),Optional.of(changed),e.objectFacts(),
                e.completedSales(),e.retainedSales(),e.variantShares(),e.containment(),e.conversion(),e.allowableCpa(),
                e.writeQualification(),e.taskQualification(),e.priority(),e.economics(),e.variantAvailability(),
                e.windowStart(),e.asOf(),e.authorities());
    }

    private static MaxCpc ceiling(String amount) {
        return new MaxCpc(SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE,Money.of(new BigDecimal(amount),"RUB"),
                AdEvidenceState.CANONICAL_CONFIRMED,MaxCpc.Absence.NONE);
    }
    private static AdMeasure measure(String amount) { return AdMeasure.available(new BigDecimal(amount),AdEvidenceState.CANONICAL_CONFIRMED); }
    private static AdvertisingEvidenceGatherer.Evidence evidence(String bid,String unit,Long traffic,boolean complete) {
        var object=new AdvertisingEvidenceRepository.ObjectRow(ID,ID,ID,"SYNTHETIC_AD",ID,"KEYWORD","object","campaign",
                "Synthetic keyword","MANUAL_BID","PROVEN_INDEPENDENT","lineage",1,"OBSERVED",AT.minusSeconds(86400),AT,"ACTIVE");
        var set=new AdvertisingEvidenceRepository.AffectedSetRow(ID,"a".repeat(64),List.of(VARIANT),List.of(ID),"COMPLETE",List.of(),AT);
        var configuration=new AdvertisingEvidenceRepository.ConfigurationRow(ID,ID,ID,1,new BigDecimal(bid),"RUB",unit,
                "RUNNING","MANUAL_BID","OFFICIAL_API_READBACK",AT,AT);
        var facts=new AdvertisingEvidenceRepository.ObjectFactAggregate(new BigDecimal("6000"),"RUB",10000L,10000L,traffic,
                50L,new BigDecimal("50000"),complete,false,AT.minusSeconds(2592000),AT,1,ID);
        var sales=new AdvertisingEvidenceRepository.LinkedSaleAggregate(50,new BigDecimal("50000"),"RUB",1,ID);
        var conversion=new AdvertisingPolicyRepository.ConversionDefinition(ID,1,STAGE,"CLICKS","DETERMINISTIC_OBJECT_LINKAGE",
                BigDecimal.ONE,BigDecimal.ONE,10,new BigDecimal("0.2"),30);
        var cpa=new AdvertisingPolicyRepository.AllowableCpaDefinition(ID,1,STAGE,"RUB","SETTLED_CONTRIBUTION",
                new BigDecimal("0.5"),"INCLUDED_IN_STAGE_CONTRIBUTION");
        var economics=new AdvertisingEvidenceGatherer.VariantEconomics(measure("500"),measure("0"),measure("0"),measure("0"),"RUB");
        return AdvertisingCalculationFixture.withLineage(new AdvertisingEvidenceGatherer.Evidence(object,Optional.of(set),Optional.of(configuration),
                Optional.of(facts),Optional.of(sales),Optional.of(sales),List.of(),
                new AdvertisingEvidenceRepository.ContainmentRow(false,List.of(),false),Optional.of(conversion),Optional.of(cpa),
                Optional.empty(),Optional.empty(),Optional.empty(),Map.of(VARIANT,economics),Map.of(),AT.minusSeconds(2592000),AT));
    }
}
