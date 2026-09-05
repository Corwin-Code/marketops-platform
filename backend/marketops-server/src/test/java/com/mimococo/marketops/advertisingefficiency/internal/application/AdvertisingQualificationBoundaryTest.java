package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.*;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.analyticsdecision.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exact policy boundaries and independent canonical Completed/Retained cohorts. */
class AdvertisingQualificationBoundaryTest {
    static final UUID ID=UUID.fromString("eef76bf0-d152-4ca5-bfc1-27b2d79adcf6");
    static final Instant AT=Instant.parse("2026-09-04T00:00:00Z");
    static final AdMeasure SPEND=AdMeasure.available(new BigDecimal("100"),AdEvidenceState.CANONICAL_CONFIRMED);
    static final AdvertisingContributionProfit PROFIT=new AdvertisingContributionProfit(SPEND,SPEND,"RUB",List.of());
    static AdvertisingPolicyRepository.QualificationPolicy policy(int completed,int retained,long traffic,String spend,int days) {
        return new AdvertisingPolicyRepository.QualificationPolicy(ID,1,"OPTIMIZATION_BID_WRITE",days,BigDecimal.ONE,BigDecimal.ONE,
                traffic,completed,retained,new BigDecimal(spend),"RUB",2,BigDecimal.ONE,true,true,"CANONICAL_CONFIRMED");
    }
    static AdvertisingEvidenceGatherer.Evidence evidence(long completed,boolean comparable,boolean incident) {
        var evidence=mock(AdvertisingEvidenceGatherer.Evidence.class);
        when(evidence.asOf()).thenReturn(AT);when(evidence.windowStart()).thenReturn(AT.minusSeconds(30*86400L));
        var fact=mock(AdvertisingEvidenceRepository.ObjectFactAggregate.class);
        when(fact.currencyCode()).thenReturn("RUB");when(fact.coverageRatio()).thenReturn(BigDecimal.ONE);
        when(fact.spendAmount()).thenReturn(new BigDecimal("100"));when(fact.clicks()).thenReturn(100L);
        when(fact.everyWindowComplete()).thenReturn(true);when(fact.latestSourceTime()).thenReturn(AT);when(fact.acceptedAt()).thenReturn(AT);
        when(evidence.objectFacts()).thenReturn(Optional.of(fact));
        var line=mock(AdvertisingEvidenceRepository.LinkedSaleLine.class);
        when(line.saleStage()).thenReturn("CANONICAL_AD_LINKED_RETAINED_SALE");when(line.productVariantId()).thenReturn(ID);
        when(line.sourceTime()).thenReturn(AT);when(line.recordedAt()).thenReturn(AT);
        var selectedRetained=mock(AdvertisingEvidenceRepository.LinkedSaleAggregate.class);
        when(selectedRetained.lines()).thenReturn(List.of(line));when(selectedRetained.eventCount()).thenReturn(10L);
        when(evidence.completedSales()).thenReturn(Optional.of(selectedRetained));
        when(evidence.retainedSales()).thenReturn(Optional.of(selectedRetained));
        var set=mock(AdvertisingEvidenceRepository.AffectedSetRow.class);
        when(set.resolutionState()).thenReturn("COMPLETE");when(set.resolvedAt()).thenReturn(AT);when(evidence.affectedSet()).thenReturn(Optional.of(set));
        var metric=mock(MetricValueView.class);when(metric.available()).thenReturn(true);
        when(metric.confidenceState()).thenReturn(ConfidenceState.CANONICAL_CONFIRMED);when(metric.computedAt()).thenReturn(AT);
        when(evidence.economics()).thenReturn(Map.of(ID,new AdvertisingEvidenceGatherer.VariantEconomics(SPEND,SPEND,SPEND,SPEND,"RUB",List.of(metric))));
        Map<String,AdvertisingPolicyRepository.FreshnessProfile> freshness=new HashMap<>();
        for(String kind:List.of("OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC","AD_LINKED_SALE_EVENT","COST_AND_FEE","AFFECTED_SET")) {
            freshness.put("OPTIMIZATION_BID_WRITE:"+kind,new AdvertisingPolicyRepository.FreshnessProfile(ID,1,kind,"OPTIMIZATION_BID_WRITE",60,60,0,0,true,true,BigDecimal.ONE,"CANONICAL_CONFIRMED",true));
        }
        when(evidence.authorities()).thenReturn(new AdvertisingEvidenceGatherer.Authorities(Map.of(),freshness,Map.of(ID,1),comparable,List.of(ID),Map.of(),false,incident,
                AdvertisingEvidenceRepository.CriticalSignals.absent(),completed));
        return evidence;
    }
    static AdLinkedConversion conversion() {
        return AdLinkedConversion.writeGrade(SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE,10,100,BigDecimal.ONE,BigDecimal.ONE,true,true,
                10,BigDecimal.ONE,BigDecimal.ONE,AdEvidenceState.CANONICAL_CONFIRMED);
    }
    @Test void aRetainedConversionStillUsesTheIndependentlyMeasuredCompletedCount() {
        assertThat(AdvertisingCaseCalculationService.qualificationConditions(evidence(20,true,false),policy(20,10,100,"100",30),conversion(),SPEND,PROFIT)).isTrue();
        assertThat(AdvertisingCaseCalculationService.qualificationConditions(evidence(19,true,false),policy(20,10,100,"100",30),conversion(),SPEND,PROFIT)).isFalse();
        assertThat(AdvertisingCaseCalculationService.qualificationConditions(evidence(0,true,false),policy(1,10,100,"100",30),conversion(),SPEND,PROFIT)).isFalse();
    }
    @Test void everyPublishedSampleSpendAndWindowBoundaryMustIndependentlyPass() {
        var evidence=evidence(20,true,false);
        for(var policy:List.of(policy(21,10,100,"100",30),policy(20,11,100,"100",30),policy(20,10,101,"100",30),
                policy(20,10,100,"100.0001",30),policy(20,10,100,"100",29))) {
            assertThat(AdvertisingCaseCalculationService.qualificationConditions(evidence,policy,conversion(),SPEND,PROFIT)).as(policy.toString()).isFalse();
        }
    }
    @Test void goodSamplesCannotOverrideMissingComparableHistoryOrAProviderIncident() {
        assertThat(AdvertisingCaseCalculationService.qualificationConditions(evidence(20,false,false),policy(20,10,100,"100",30),conversion(),SPEND,PROFIT)).isFalse();
        assertThat(AdvertisingCaseCalculationService.qualificationConditions(evidence(20,true,true),policy(20,10,100,"100",30),conversion(),SPEND,PROFIT)).isFalse();
    }
}
