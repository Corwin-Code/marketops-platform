package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.internal.domain.*;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingOutcomeRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository;
import com.mimococo.marketops.shared.IdGenerator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Frozen evidence, non-compensating axes, individual sales guards and revisions. */
class AdvertisingOutcomeServiceTest {
    static final UUID ID=UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    static final UUID HERO=UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3302");
    static final Instant LANDED=Instant.parse("2026-08-01T00:00:00Z");
    static final Instant NOW=Instant.parse("2026-10-04T00:00:00Z");
    static final AdvertisingOutcomePlanningService.Policy POLICY=new AdvertisingOutcomePlanningService.Policy(ID,1,24,720,30,
            new BigDecimal("10"),new BigDecimal("0.1"),new BigDecimal("0.05"),BigDecimal.ONE,100,true,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,4,"HALF_UP",true,"KEEP_PROTECTION_OPEN");
    static final AdvertisingPolicyRepository.FreshnessProfile FRESHNESS=new AdvertisingPolicyRepository.FreshnessProfile(
            ID,1,"COMPANY_COMPLETED_SALE","EARLY_COMPLETED_SALES_OUTCOME",1440,1440,0,0,true,true,BigDecimal.ONE,"CANONICAL_CONFIRMED",true);
    static AdMeasure amount(String value) { return value==null?AdMeasure.notAvailable(AdEvidenceState.UNKNOWN):AdMeasure.available(new BigDecimal(value),AdEvidenceState.CANONICAL_CONFIRMED); }
    static AdvertisingOutcomeEvidenceService.Snapshot snapshot(String stage,String profit,String perRub,String total,String hero) {
        return new AdvertisingOutcomeEvidenceService.Snapshot(stage,LANDED.minusSeconds(86400),LANDED,
                new AdvertisingContributionProfit(amount(profit),amount(perRub),"RUB",List.of()),amount(total),
                List.of(new AdvertisingOutcomeEvidenceService.UnitSales(new AdvertisingOutcomeEvidenceService.Unit(HERO,HERO,ID,ID),amount(hero))),
                1000L,BigDecimal.ONE,"same-context",List.of(ID),List.of(),FRESHNESS,amount("100"),profiles(stage),List.of(),new AdvertisingOutcomeEvidenceService.ProtectionEvidence(true,true,true,true),"PROVEN_ADVERTISING_LOSS");
    }
    static java.util.Map<String,AdvertisingPolicyRepository.FreshnessProfile> profiles(String stage) {
        return AdvertisingOutcomeFreshness.kinds(stage).stream().collect(java.util.stream.Collectors.toMap(kind->kind,kind->FRESHNESS));
    }
    static AdvertisingOutcomeRepository.DueRow due(String stage) {
        return new AdvertisingOutcomeRepository.DueRow(ID,ID,ID,"OZON",ID,"a".repeat(64),"PROTECTION_DECREASE",LANDED,
                ID,1,"DUAL_AXIS","FROZEN_PRE_ACTION",30,24,720,null,null,100,BigDecimal.ONE,"PROVEN_ADVERTISING_LOSS",stage,
                stage.endsWith("_REVISED")?HERO:null,stage.endsWith("_REVISED")?2:null);
    }
    @Test void completedSalesOnlyProvesEarlySafetyEvenWhenProfitLooksHealthy() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("OPERATIONAL","100","1","1000","100"),
                snapshot("OPERATIONAL","200","2","1000","100"),POLICY,true);
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.UNCHANGED);
        assertThat(result.dualAxis().healthy()).isFalse();
        assertThat(result.dualAxis().reasonCode()).isEqualTo("EARLY_SAFETY_ONLY_NOT_EFFICIENCY_SUCCESS");
    }
    @Test void totalGrowthNeverOffsetsCriticalUnitCollapse() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("OPERATIONAL","100","1","1000","100"),
                snapshot("OPERATIONAL","200","2","2000","50"),POLICY,true);
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.REGRESSED);
        assertThat(result.critical().getFirst().state()).isEqualTo("REGRESSED");
    }
    @Test void missingCriticalUnitIsUnknownDespiteImprovingTotalAndProfit() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("OPERATIONAL","100","1","1000","100"),
                snapshot("OPERATIONAL","200","2","2000",null),POLICY,true);
        assertThat(result.dualAxis().outcome()).isEqualTo(DualAxisVerdict.Outcome.UNRESOLVED);
        assertThat(result.critical().getFirst().state()).isEqualTo("UNKNOWN");
    }
    @Test void provenCriticalFailureSurvivesUnknownCompanyTotal() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("OPERATIONAL","100","1","1000","100"),
                snapshot("OPERATIONAL",null,null,null,"50"),POLICY,true);
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.REGRESSED);
        assertThat(result.sales().evidenceComplete()).isFalse();
    }
    @Test void improvedRatioCannotOffsetWorseAbsoluteProfit() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("SETTLED","100","1","1000","100"),
                snapshot("SETTLED","50","2","1000","100"),POLICY,true);
        assertThat(result.dualAxis().outcome()).isEqualTo(DualAxisVerdict.Outcome.REGRESSION);
    }
    @Test void lossReductionDoesNotCloseResponsibility() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("SETTLED","-100","-2","1000","100"),
                snapshot("SETTLED","-50","-1","1000","100"),POLICY,true);
        assertThat(result.dualAxis().outcome()).isEqualTo(DualAxisVerdict.Outcome.IMPROVED_NOT_HEALTHY);
        assertThat(result.dualAxis().closesResponsibility()).isFalse();
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.UNCHANGED);
    }
    @Test void zeroSpendRatioStaysUnresolved() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("SETTLED","100","1","1000","100"),
                snapshot("SETTLED","200",null,"1000","100"),POLICY,true);
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.INDETERMINATE);
    }
    @Test void notDueNeverPassesCriticalUnit() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("RETAINED","100","1","1000","100"),
                snapshot("RETAINED","200","2","1000","100"),POLICY,false);
        assertThat(result.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.NOT_YET_EVALUABLE);
        assertThat(result.critical().getFirst().state()).isEqualTo("NOT_DUE");
    }
    @Test void retainedIsExactlyThirtyDaysAndDistinctFromFinancialStage() {
        assertThat(java.time.Duration.between(due("RETAINED").windowStartsAt(),due("RETAINED").windowEndsAt("RETAINED")).toDays()).isEqualTo(30);
        assertThat(OutcomeEvaluation.Stage.valueOf("RETAINED")).isNotEqualTo(OutcomeEvaluation.Stage.SETTLED);
    }
    @Test void healthySuccessRequiresBothAxesAndEverySalesTerm() {
        var result=AdvertisingOutcomeAssessment.evaluate(snapshot("SETTLED","100","1","1000","100"),
                snapshot("SETTLED","200","1","1000","100"),POLICY,true);
        assertThat(result.dualAxis().healthy()).isTrue();
    }
    @Test void usesFrozenBaselineWithoutReadingItAgainAndAppendsRevision() {
        var repo=mock(AdvertisingOutcomeRepository.class);
        var evidence=mock(AdvertisingOutcomeEvidenceService.class);
        when(evidence.bindOriginalIdentity(any(),any(),any(),any(),any())).thenAnswer(invocation->invocation.getArgument(2));
        var ids=mock(IdGenerator.class);
        var json=JsonMapper.builder().build();
        when(ids.newId()).thenReturn(ID);
        var baseline=snapshot("SETTLED","100","1","1000","100");
        when(repo.frozenBaseline(ID,"SETTLED")).thenReturn(Optional.of(new AdvertisingOutcomeRepository.FrozenBaseline(
                ID,ID,json.writeValueAsString(POLICY),json.writeValueAsString(baseline))));
        when(evidence.snapshot(eq(ID),eq(ID),eq(ID),eq("SETTLED"),anyList(),any(),any(),eq(NOW),anyMap()))
                .thenReturn(snapshot("SETTLED","200","2","1000","100"));
        var result=new AdvertisingOutcomeService(repo,evidence,json,ids,mock(com.mimococo.marketops.operationsworkflow.AdvertisingOutcomeReviewIntake.class)).evaluate(due("SETTLED_REVISED"),NOW).orElseThrow();
        assertThat(result.revisionNo()).isEqualTo(3);
        verify(evidence,times(1)).snapshot(eq(ID),eq(ID),eq(ID),eq("SETTLED"),anyList(),eq(due("SETTLED").windowStartsAt()),eq(due("SETTLED").windowEndsAt("SETTLED")),eq(NOW),anyMap());
        verify(repo).record(eq(ID),any(),eq("SETTLED_REVISED"),eq(3),eq(HERO),contains("restated"),any(),any(),any(),any(),any(),any(),any(),any(),anyString(),any());
        verify(repo,never()).reopenAfterRegression(any(),any(),any(),any());
    }
    @Test void missingFrozenBaselineDoesNotManufacturePostActionBaseline() {
        var repo=mock(AdvertisingOutcomeRepository.class);var evidence=mock(AdvertisingOutcomeEvidenceService.class);
        when(repo.frozenBaseline(ID,"OPERATIONAL")).thenReturn(Optional.empty());
        assertThat(new AdvertisingOutcomeService(repo,evidence,JsonMapper.builder().build(),mock(IdGenerator.class),mock(com.mimococo.marketops.operationsworkflow.AdvertisingOutcomeReviewIntake.class)).evaluate(due("OPERATIONAL"),NOW)).isEmpty();
        verifyNoInteractions(evidence);
    }
    @Test void stoppedNewExposureDoesNotImplyCompanyPreservationOrEfficiencySuccess() {
        var before=snapshot("OPERATIONAL","-100","-1","1000","100");
        var sample=snapshot("OPERATIONAL",null,null,null,null);
        var after=new AdvertisingOutcomeEvidenceService.Snapshot(sample.stage(),sample.from(),sample.to(),sample.profit(),sample.companySales(),
                sample.units(),sample.traffic(),sample.coverage(),sample.confounderDigest(),sample.evidenceIds(),sample.blockers(),FRESHNESS,amount("0"),null,List.of(),new AdvertisingOutcomeEvidenceService.ProtectionEvidence(true,true,false,false),"PROVEN_ADVERTISING_LOSS");
        var result=AdvertisingOutcomeAssessment.evaluate(before,after,POLICY,true);
        assertThat(result.sales().preserved()).isFalse();assertThat(result.dualAxis().healthy()).isFalse();
        assertThat(AdvertisingOutcomeAssessment.businessOutcome("PROVEN_ADVERTISING_LOSS",true,before,after,POLICY,result,true))
                .isEqualTo("VERIFIED_AD_EXPOSURE_STOPPED");
    }
    @Test void originalEconomicRiskCanClearWithoutAFalsePrimaryEfficiencyClaim() {
        var before=snapshot("RETAINED","-1","-0.01","1000","100");
        var after=snapshot("RETAINED","0","0","1000","100");
        var result=AdvertisingOutcomeAssessment.evaluate(before,after,POLICY,true);
        assertThat(result.dualAxis().healthy()).isFalse();
        assertThat(AdvertisingOutcomeAssessment.businessOutcome("PROVEN_ADVERTISING_LOSS",true,before,after,POLICY,result,true)).isEqualTo("VERIFIED_AD_RISK_CLEARED");
    }
    @Test void independentlyPublishedNonWorseningBandAndMaterialBoundaryAreBothApplied() {
        var allowed=DualAxisVerdict.evaluate(amount("100"),amount("95"),amount("1"),amount("1.1"),new BigDecimal("10"),new BigDecimal("0.1"),
                new BigDecimal("5"),BigDecimal.ZERO,4,"HALF_UP",true,true,true);
        assertThat(allowed.healthy()).isTrue();
        var exclusive=DualAxisVerdict.evaluate(amount("100"),amount("100"),amount("1"),amount("1.1"),new BigDecimal("10"),new BigDecimal("0.1"),
                new BigDecimal("5"),BigDecimal.ZERO,4,"HALF_UP",false,true,true);
        assertThat(exclusive.outcome()).isEqualTo(DualAxisVerdict.Outcome.NO_MATERIAL_IMPROVEMENT);
        var outside=DualAxisVerdict.evaluate(amount("100"),amount("94.9999"),amount("1"),amount("9"),new BigDecimal("10"),new BigDecimal("0.1"),
                new BigDecimal("5"),BigDecimal.ZERO,4,"HALF_UP",true,true,true);
        assertThat(outside.outcome()).isEqualTo(DualAxisVerdict.Outcome.REGRESSION);
    }

    @Test void aFinalConfounderNeverSilencesIndependentEarlySalesRegression() {
        var original=snapshot("OPERATIONAL","100","1","1000","100");
        var value=snapshot("OPERATIONAL","200","2","1000","50");
        var changed=new AdvertisingOutcomeEvidenceService.Snapshot(value.stage(),value.from(),value.to(),value.profit(),value.companySales(),
                value.units(),value.traffic(),value.coverage(),"different-price",value.evidenceIds(),List.of("CONFOUNDER_PRICE_CHANGED"),FRESHNESS,value.officialSpend());
        assertThat(AdvertisingOutcomeAssessment.evaluate(original,changed,POLICY,true).evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.REGRESSED);
        var retainedBefore=snapshot("RETAINED","100","1","1000","100");
        var retainedAfter=new AdvertisingOutcomeEvidenceService.Snapshot("RETAINED",changed.from(),changed.to(),changed.profit(),changed.companySales(),
                original.units(),changed.traffic(),changed.coverage(),changed.confounderDigest(),changed.evidenceIds(),changed.blockers(),FRESHNESS,changed.officialSpend());
        var finalResult=AdvertisingOutcomeAssessment.evaluate(retainedBefore,retainedAfter,POLICY,true);
        assertThat(finalResult.evaluation().verdict()).isEqualTo(OutcomeEvaluation.Verdict.INDETERMINATE);
        assertThat(finalResult.evaluation().unresolvedReasons()).contains("CONFOUNDER_CHANGED_OR_UNRESOLVED");
    }
    @Test void settledContradictionAppendsSeparateFinancialVerdictAndReopensTheActionLineage() {
        verifyStageTransition("200","2","50","2",OutcomeEvaluation.Verdict.IMPROVED,OutcomeEvaluation.Verdict.REGRESSED,true);
    }
    @Test void favorableSettlementUpgradesOperationalNoImprovementWithoutReplacingItsHistory() {
        verifyStageTransition("100","1","200","2",OutcomeEvaluation.Verdict.UNCHANGED,OutcomeEvaluation.Verdict.IMPROVED,false);
    }
    private void verifyStageTransition(String retainedProfit,String retainedRatio,String settledProfit,String settledRatio,
            OutcomeEvaluation.Verdict retainedVerdict,OutcomeEvaluation.Verdict settledVerdict,boolean regression) {
        var repo=mock(AdvertisingOutcomeRepository.class);var evidence=mock(AdvertisingOutcomeEvidenceService.class);
        when(evidence.bindOriginalIdentity(any(),any(),any(),any(),any())).thenAnswer(invocation->invocation.getArgument(2));
        var ids=mock(IdGenerator.class);when(ids.newId()).thenReturn(ID,HERO,UUID.randomUUID());
        var json=JsonMapper.builder().build();
        for(String stage:List.of("RETAINED","SETTLED")) when(repo.frozenBaseline(ID,stage)).thenReturn(Optional.of(new AdvertisingOutcomeRepository.FrozenBaseline(
                ID,ID,json.writeValueAsString(POLICY),json.writeValueAsString(snapshot(stage,"100","1","1000","100")))));
        when(evidence.snapshot(eq(ID),eq(ID),eq(ID),eq("RETAINED"),anyList(),any(),any(),eq(NOW),anyMap()))
                .thenReturn(snapshot("RETAINED",retainedProfit,retainedRatio,"1000","100"));
        when(evidence.snapshot(eq(ID),eq(ID),eq(ID),eq("SETTLED"),anyList(),any(),any(),eq(NOW),anyMap()))
                .thenReturn(snapshot("SETTLED",settledProfit,settledRatio,"1000","100"));
        var service=new AdvertisingOutcomeService(repo,evidence,json,ids,mock(com.mimococo.marketops.operationsworkflow.AdvertisingOutcomeReviewIntake.class));
        var retained=service.evaluate(due("RETAINED"),NOW).orElseThrow();
        var settled=service.evaluate(due("SETTLED"),NOW).orElseThrow();
        assertThat(retained.evaluation().verdict()).isEqualTo(retainedVerdict);
        assertThat(settled.evaluation().verdict()).isEqualTo(settledVerdict);
        assertThat(retained.observationId()).isNotEqualTo(settled.observationId());
        var evaluation=org.mockito.ArgumentCaptor.forClass(OutcomeEvaluation.class);
        verify(repo,times(2)).record(any(),any(),anyString(),eq(1),isNull(),isNull(),any(),any(),any(),any(),any(),any(),evaluation.capture(),eq(NOW),anyString(),any());
        assertThat(evaluation.getAllValues()).extracting(OutcomeEvaluation::stage).containsExactly(OutcomeEvaluation.Stage.RETAINED,OutcomeEvaluation.Stage.SETTLED);
        if(regression) verify(repo).reopenAfterRegression(any(),eq(settled.observationId()),eq("OPS_LEAD"),any());
        else verify(repo,never()).reopenAfterRegression(any(),any(),any(),any());
    }

}
