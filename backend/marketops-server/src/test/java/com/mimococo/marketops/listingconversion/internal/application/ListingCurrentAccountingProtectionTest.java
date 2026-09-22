package com.mimococo.marketops.listingconversion.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.mimococo.marketops.analyticsdecision.*;
import com.mimococo.marketops.availabilityrisk.SupplyCoverageQuery;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.*;
import com.mimococo.marketops.productlisting.ListingScopeEvidence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/** Actual protection consumer and arithmetic; domain-owned fact queries supply synthetic qualified facts. */
class ListingCurrentAccountingProtectionTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Instant at=Instant.parse("2026-08-01T00:00:00Z"), start=at.minusSeconds(604800), reference=start.minusSeconds(604800);
    final UUID org=UUID.randomUUID(), store=UUID.randomUUID(), linkedStore=UUID.randomUUID(), listing=UUID.randomUUID();
    final UUID member=UUID.randomUUID(), linkedMember=UUID.randomUUID(), product=UUID.randomUUID(), actionId=UUID.randomUUID();
    final CanonicalScopeMetricQuery metrics=mock(CanonicalScopeMetricQuery.class);

    @ParameterizedTest
    @CsvSource({"110,110,false,PASS,PASS", "200,80,false,FAIL,PASS", "80,110,true,FAIL,UNDETERMINED"})
    void acceptedReferenceDrivesIndependentCurrentProtection(String direct,String linked,boolean missingReturns,
                                                            String profitVerdict,String returnVerdict) {
        var facts=mock(ListingFactRepository.class);
        var actions=mock(ListingActionRepository.class);
        var supply=mock(SupplyCoverageQuery.class);
        var action=mock(ListingActionRepository.ActionRow.class);
        when(action.id()).thenReturn(actionId);when(action.organizationId()).thenReturn(org);
        when(action.storeId()).thenReturn(store);when(action.listingId()).thenReturn(listing);
        when(action.affectedSetDigest()).thenReturn("a".repeat(64));
        when(actions.selectedSimulation(actionId)).thenReturn(new ListingActionRepository.SelectedSimulation(false,null));
        when(facts.identitySnapshot(listing,at)).thenReturn(new ListingScopeEvidence.Snapshot("a".repeat(64),json.valueToTree(Map.of(
                "nativeScope",Map.of("state","COMPLETE","reasonCodes",List.of()),"members",List.of(Map.of(
                  "listingVariantId",member,"openConflicts",List.of(),"mappings",List.of(Map.of("productVariantId",product,
                  "productStatus","ACTIVE","productVariantStatus","ACTIVE"))))))));
        when(supply.project(any())).thenAnswer(invocation->{
            var scenario=invocation.getArgument(0,SupplyCoverageQuery.Scenario.class);
            return new SupplyCoverageQuery.Projection(scenario,"PASS",at.plusSeconds(604800),BigDecimal.ONE,null,
                    List.of(),List.of(),Map.of(),"b".repeat(64));
        });
        when(metrics.current(any())).thenReturn(Optional.of(projection(store,member,start,direct,missingReturns)));
        when(metrics.project(any())).thenAnswer(invocation->{
            var scope=invocation.getArgument(0,CanonicalScopeMetricQuery.Scope.class);
            boolean baseline=scope.periodStart().equals(reference);
            return projection(scope.storeId(),scope.listingVariantIds().getFirst(),scope.periodStart(),
                    baseline?"100":linked, false);
        });
        var values=new HashMap<String,CalibrationRepository.Value>();
        values.put("NON_WORSENING_PROFIT_BOUND",value("NON_WORSENING_PROFIT_BOUND",BigDecimal.ZERO,"RATIO",Map.of(
                "currentAccountingComparisons",Map.of(listing.toString(),Map.of("periodStart",reference.toString(),
                "periodEnd",start.toString(),"evidenceReference","fixture://accepted-reference")))));
        values.put("NON_WORSENING_RETURN_BOUND",value("NON_WORSENING_RETURN_BOUND",BigDecimal.ZERO,"RATIO",Map.of()));
        values.put("FRESHNESS_RULE",value("FRESHNESS_RULE",null,"RULE",Map.of("businessProtection",Map.of(
                "maximumVerificationAgeSeconds",3600,"maximumPeriodEndAgeSeconds",3600))));
        values.put("CRITICAL_GROUP_RULE",value("CRITICAL_GROUP_RULE",null,"RULE",Map.of("protectionScopeBases",Map.of(
                listing.toString(),Map.of("evidenceReference","fixture://exact-scope","criticalReturnVariantIds",List.of(),
                "linkedProfitScopes",List.of(Map.of("code","RELATED","storeId",linkedStore,"listingVariantIds",List.of(linkedMember),
                "evidenceReference","fixture://linked-scope")))))));
        values.put("DEMAND_SCENARIO_SET",value("DEMAND_SCENARIO_SET",null,"RULE",Map.of("supplyScenarios",List.of(Map.of(
                "productVariantId",product,"code","UPSIDE","companyDailyFulfillmentUnits",2,"coverageDays",7,
                "evidenceReference","fixture://supply-upside")))));
        var service=new ListingBusinessProtectionService(facts,supply,json,metrics,actions,mock(EvaluationRepository.class),
                mock(ListingSimulationInputEvidence.class),mock(CalibrationService.class));
        var result=service.assess(action,new CalibrationService.Outcome(new CalibrationService.Resolved(UUID.randomUUID(),1,values),"RESOLVED"),at);
        assertThat(result.get("currentProfitVerdict")).isEqualTo(profitVerdict);
        assertThat(result.get("currentReturnVerdict")).isEqualTo(returnVerdict);
        assertThat(result.get("currentAccountingReferenceDigest")).matches("[0-9a-f]{64}");
        assertThat(result.get("financialInputState")).isEqualTo(missingReturns?"UNDETERMINED":"CANONICAL_INPUT_AVAILABLE");
    }

    private CalibrationRepository.Value value(String code,BigDecimal amount,String unit,Object evidence) {
        return new CalibrationRepository.Value(code,amount,null,json.valueToTree(evidence),unit,7,"fixture://rule");
    }
    private CanonicalScopeMetricQuery.Projection projection(UUID scopeStore,UUID subject,Instant from,String amount,boolean missingReturns) {
        Instant to=from.plusSeconds(604800);
        var profit=metric(subject,MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,amount,"RUB",from,to);
        var units=metric(subject,MetricCode.COMPLETED_UNITS,"100",null,from,to);
        var returns=metric(subject,MetricCode.RETURN_UNITS,"1",null,from,to);
        var floor=metric(subject,MetricCode.REQUIRED_PROFIT_PER_UNIT,"0","RUB",from,to);
        return new CanonicalScopeMetricQuery.Projection(new CanonicalScopeMetricQuery.Scope(org,scopeStore,List.of(subject),
                MetricWindow.D7,from,to,at,CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL),
                new CanonicalScopeMetricQuery.Observation(new BigDecimal(amount),"RUB",List.of(),List.of(profit)),
                missingReturns?new CanonicalScopeMetricQuery.Observation(null,null,List.of("RETURN_INPUT_MISSING"),List.of())
                  :new CanonicalScopeMetricQuery.Observation(new BigDecimal("0.01"),null,List.of(),List.of(returns,units)),
                Map.of(subject,new CanonicalScopeMetricQuery.UnitProfitInput(profit,units,floor)));
    }
    private MetricValueView metric(UUID subject,MetricCode code,String amount,String currency,Instant from,Instant to) {
        return new MetricValueView(UUID.randomUUID(),code,1,SubjectKind.PLATFORM_LISTING_VARIANT,subject,MetricWindow.D7,
                from,to,ValueState.AVAILABLE,new BigDecimal(amount),currency,ConfidenceState.CANONICAL_CONFIRMED,false,
                at,0L,"c".repeat(64),at,List.of(UUID.randomUUID()),at,UUID.randomUUID());
    }
}
