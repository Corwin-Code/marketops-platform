package com.mimococo.marketops.listingconversion.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.analyticsdecision.*;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ListingOutcomeMetricEvidenceTest {
    @Test void canonicalValuesRetainExactStageAndScopeButEstimatesNeverBecomeQualifiedInputs() {
        var metrics=mock(CanonicalScopeMetricQuery.class);
        var actions=mock(ListingActionRepository.class);
        var action=mock(ListingActionRepository.ActionRow.class);
        UUID actionId=UUID.randomUUID(), organization=UUID.randomUUID(),store=UUID.randomUUID(),member=UUID.randomUUID();
        Instant from=Instant.parse("2026-08-01T00:00:00Z"),to=from.plusSeconds(14L*86400),at=to.plusSeconds(14L*86400);
        when(action.id()).thenReturn(actionId);
        when(action.organizationId()).thenReturn(organization);
        when(action.storeId()).thenReturn(store);
        when(actions.frozenDirectListingVariants(actionId)).thenReturn(List.of(member));
        var value=new MetricValueView(UUID.randomUUID(),MetricCode.SETTLED_CONTRIBUTION_PROFIT,2,
                SubjectKind.PLATFORM_LISTING_VARIANT,member,MetricWindow.D14,from,to,ValueState.AVAILABLE,
                new BigDecimal("100"),"RUB",ConfidenceState.CANONICAL_CONFIRMED,true,from,0L,"a".repeat(64),at,
                List.of(UUID.randomUUID()),at,UUID.randomUUID());
        when(metrics.project(any())).thenAnswer(call->{
            var scope=call.getArgument(0,CanonicalScopeMetricQuery.Scope.class);
            return new CanonicalScopeMetricQuery.Projection(scope,
                    new CanonicalScopeMetricQuery.Observation(value.numericValue(),"RUB",List.of(),List.of(value)),
                    new CanonicalScopeMetricQuery.Observation(null,null,List.of("RETURN_DENOMINATOR_ZERO"),List.of()));
        });
        var result=new ListingOutcomeMetricEvidence(metrics,actions).read(action,from,to,14,"SETTLED",at);
        var requested=ArgumentCaptor.forClass(CanonicalScopeMetricQuery.Scope.class);
        verify(metrics).project(requested.capture());
        assertThat(requested.getValue()).isEqualTo(new CanonicalScopeMetricQuery.Scope(organization,store,
                List.of(member),MetricWindow.D14,from,to,at,CanonicalScopeMetricQuery.ProfitBasis.SETTLED));
        assertThat(result.gaps()).contains("DIRECT_PROFIT:CANONICAL_CONFIDENCE_UNQUALIFIED",
                "OVERALL_RETURN:RETURN_DENOMINATOR_ZERO");
        assertThat(result.references()).containsEntry("profitBasis","SETTLED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CanonicalScopeMetricQuery.ProfitBasis.class)
    void frozenIndependentScopesUseTheSameNodePeriodAndTheirOwnMembers(CanonicalScopeMetricQuery.ProfitBasis basis) {
        var metrics=mock(CanonicalScopeMetricQuery.class);
        var actions=mock(ListingActionRepository.class);
        var action=mock(ListingActionRepository.ActionRow.class);
        UUID actionId=UUID.randomUUID(),org=UUID.randomUUID(),store=UUID.randomUUID(),linkedStore=UUID.randomUUID();
        UUID direct=UUID.randomUUID(),linked=UUID.randomUUID();
        Instant from=Instant.parse("2026-08-01T00:00:00Z"),to=from.plusSeconds(14L*86400),at=to.plusSeconds(86400);
        when(action.id()).thenReturn(actionId);
        when(action.organizationId()).thenReturn(org);
        when(action.storeId()).thenReturn(store);
        when(actions.frozenDirectListingVariants(actionId)).thenReturn(List.of(direct));
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        when(actions.frozenProtectionScopeBasis(actionId)).thenReturn(java.util.Optional.of(json.valueToTree(java.util.Map.of(
                "evidenceReference","evidence://fixture/frozen-scope", "linkedProfitScopes",List.of(java.util.Map.of(
                    "code","RELATED", "storeId",linkedStore.toString(),"listingVariantIds",List.of(linked.toString()),
                    "evidenceReference","evidence://fixture/related")),
                "criticalReturnVariantIds",List.of(direct.toString())))));
        when(metrics.project(any())).thenAnswer(call->{
            var scope=call.getArgument(0,CanonicalScopeMetricQuery.Scope.class);
            var unavailable=new CanonicalScopeMetricQuery.Observation(null,null,List.of("FIXTURE_MISSING_SOURCE"),List.of());
            return new CanonicalScopeMetricQuery.Projection(scope,unavailable,unavailable);
        });
        var result=new ListingOutcomeMetricEvidence(metrics,actions).read(action,from,to,14,basis.name(),at);
        var requests=ArgumentCaptor.forClass(CanonicalScopeMetricQuery.Scope.class);
        verify(metrics,times(3)).project(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(scope->{
            assertThat(scope.organizationId()).isEqualTo(org);
            assertThat(scope.periodStart()).isEqualTo(from);
            assertThat(scope.periodEnd()).isEqualTo(to);
            assertThat(scope.asOf()).isEqualTo(at);
            assertThat(scope.profitBasis()).isEqualTo(basis);
        });
        assertThat(requests.getAllValues().get(1).storeId()).isEqualTo(linkedStore);
        assertThat(requests.getAllValues().get(1).listingVariantIds()).containsExactly(linked);
        assertThat(requests.getAllValues().get(2).listingVariantIds()).containsExactly(direct);
        assertThat(result.gaps()).contains("LINKED_PROFIT:RELATED:FIXTURE_MISSING_SOURCE",
                "CRITICAL_RETURN:"+direct+":FIXTURE_MISSING_SOURCE");
        verify(metrics,never()).current(any());
    }

    @Test void anIndependentProfitFailureIsRetainedWhenReturnEvidenceIsMissing() {
        var metrics=mock(CanonicalScopeMetricQuery.class);
        var actions=mock(ListingActionRepository.class);
        var action=mock(ListingActionRepository.ActionRow.class);
        UUID actionId=UUID.randomUUID(),org=UUID.randomUUID(),store=UUID.randomUUID(),member=UUID.randomUUID();
        Instant from=Instant.parse("2026-08-01T00:00:00Z"),to=from.plusSeconds(14L*86400),at=to.plusSeconds(14L*86400);
        when(action.id()).thenReturn(actionId);when(action.organizationId()).thenReturn(org);when(action.storeId()).thenReturn(store);
        when(actions.frozenDirectListingVariants(actionId)).thenReturn(List.of(member));
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        when(actions.frozenProtectionScopeBasis(actionId)).thenReturn(java.util.Optional.of(json.valueToTree(java.util.Map.of(
                "evidenceReference","evidence://fixture/no-additional-scope","linkedProfitScopes",List.of(),"criticalReturnVariantIds",List.of()))));
        when(actions.frozenCalibrationDependencies(actionId)).thenReturn(java.util.Optional.of(json.valueToTree(java.util.Map.of(
                "values",java.util.Map.of("NON_WORSENING_PROFIT_BOUND",java.util.Map.of("unit","RATIO","numeric",BigDecimal.ZERO),
                    "NON_WORSENING_RETURN_BOUND",java.util.Map.of("unit","RATIO","numeric",BigDecimal.ZERO))))));
        when(metrics.project(any())).thenAnswer(call->{
            var scope=call.getArgument(0,CanonicalScopeMetricQuery.Scope.class);
            BigDecimal profit=scope.periodStart().equals(from)?BigDecimal.ZERO:new BigDecimal("100");
            var value=new MetricValueView(UUID.randomUUID(),MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,1,
                    SubjectKind.PLATFORM_LISTING_VARIANT,member,MetricWindow.D14,scope.periodStart(),scope.periodEnd(),ValueState.AVAILABLE,
                    profit,"RUB",ConfidenceState.CANONICAL_CONFIRMED,false,scope.periodStart(),0L,"a".repeat(64),at,
                    List.of(UUID.randomUUID()),at,UUID.randomUUID());
            return new CanonicalScopeMetricQuery.Projection(scope,
                    new CanonicalScopeMetricQuery.Observation(profit,"RUB",List.of(),List.of(value)),
                    new CanonicalScopeMetricQuery.Observation(null,null,List.of("MISSING_RETURNS"),List.of()));
        });
        var reader=new ListingOutcomeMetricEvidence(metrics,actions);
        var target=reader.read(action,from,to,14,"OPERATIONAL",at);
        var node=json.valueToTree(java.util.Map.of("protectionComparison",java.util.Map.of(
                "method","CANONICAL_ACCOUNTING_CHANGE_V1","qualificationRef","evidence://fixture/accepted-method",
                "referencePeriodStart",from.minusSeconds(14L*86400).toString(),"referencePeriodEnd",from.toString())));
        var result=reader.compare(action,target,from,to,14,"OPERATIONAL",at,from,node,true);
        assertThat(result.verdicts()).containsEntry("DIRECT_CONTRIBUTION_PROFIT",com.mimococo.marketops.listingconversion.ProtectionVerdict.FAIL)
                .containsEntry("OVERALL_RETURN_RATE",com.mimococo.marketops.listingconversion.ProtectionVerdict.UNDETERMINED)
                .containsEntry("LINKED_SCOPE_PROFIT",com.mimococo.marketops.listingconversion.ProtectionVerdict.PASS);
        assertThat(reader.compare(action,target,from,to,14,"OPERATIONAL",at,from,node,false).verdicts()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void aLaterLowerFloorCannotReplaceTheFloorAvailableAtFreeze(boolean hadFrozenFloor) {
        var metrics=mock(CanonicalScopeMetricQuery.class);var actions=mock(ListingActionRepository.class);
        var action=mock(ListingActionRepository.ActionRow.class);
        UUID actionId=UUID.randomUUID(),org=UUID.randomUUID(),store=UUID.randomUUID(),member=UUID.randomUUID();
        UUID frozenFloorId=UUID.randomUUID();
        Instant from=Instant.parse("2026-08-01T00:00:00Z"),to=from.plusSeconds(14L*86400),at=to.plusSeconds(14L*86400);
        when(action.id()).thenReturn(actionId);when(action.organizationId()).thenReturn(org);when(action.storeId()).thenReturn(store);
        when(actions.frozenDirectListingVariants(actionId)).thenReturn(List.of(member));
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        when(actions.frozenProtectionScopeBasis(actionId)).thenReturn(java.util.Optional.of(json.valueToTree(java.util.Map.of(
                "evidenceReference","evidence://fixture/scope","linkedProfitScopes",List.of(),"criticalReturnVariantIds",List.of()))));
        when(actions.frozenCalibrationDependencies(actionId)).thenReturn(java.util.Optional.of(json.valueToTree(java.util.Map.of(
                "values",java.util.Map.of("NON_WORSENING_PROFIT_BOUND",java.util.Map.of("unit","RATIO","numeric",BigDecimal.ZERO),
                    "NON_WORSENING_RETURN_BOUND",java.util.Map.of("unit","RATIO","numeric",BigDecimal.ZERO))))));
        when(metrics.project(any())).thenAnswer(call->{
            var scope=call.getArgument(0,CanonicalScopeMetricQuery.Scope.class);
            java.util.function.BiFunction<MetricCode,String,MetricValueView> value=(code,amount)->new MetricValueView(
                    code==MetricCode.REQUIRED_PROFIT_PER_UNIT && scope.asOf().equals(from)?frozenFloorId:UUID.randomUUID(),
                    code,1,SubjectKind.PLATFORM_LISTING_VARIANT,member,MetricWindow.D14,scope.periodStart(),scope.periodEnd(),
                    ValueState.AVAILABLE,new BigDecimal(amount),code==MetricCode.COMPLETED_UNITS?null:"RUB",
                    ConfidenceState.CANONICAL_CONFIRMED,false,scope.periodStart(),0L,"a".repeat(64),scope.asOf(),
                    List.of(UUID.randomUUID()),scope.asOf(),UUID.randomUUID());
            var profit=value.apply(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,"5");
            var floor=scope.asOf().equals(from) && !hadFrozenFloor?null:
                    value.apply(MetricCode.REQUIRED_PROFIT_PER_UNIT,scope.asOf().equals(from)?"10":"0");
            return new CanonicalScopeMetricQuery.Projection(scope,
                    new CanonicalScopeMetricQuery.Observation(profit.numericValue(),"RUB",List.of(),List.of(profit)),
                    new CanonicalScopeMetricQuery.Observation(null,null,List.of("MISSING_RETURNS"),List.of()),
                    java.util.Map.of(member,new CanonicalScopeMetricQuery.UnitProfitInput(profit,
                            value.apply(MetricCode.COMPLETED_UNITS,"1"),floor)));
        });
        var node=json.valueToTree(java.util.Map.of("protectionComparison",java.util.Map.of(
                "method","CANONICAL_ACCOUNTING_CHANGE_V1","qualificationRef","evidence://fixture/method",
                "referencePeriodStart",from.minusSeconds(14L*86400).toString(),"referencePeriodEnd",from.toString())));
        var reader=new ListingOutcomeMetricEvidence(metrics,actions);
        var result=reader.compare(action,reader.read(action,from,to,14,"OPERATIONAL",at),from,to,14,"OPERATIONAL",at,from,node,true);
        assertThat(result.verdicts()).containsEntry("UNIT_PROFIT_FLOOR",hadFrozenFloor
                ?com.mimococo.marketops.listingconversion.ProtectionVerdict.FAIL
                :com.mimococo.marketops.listingconversion.ProtectionVerdict.UNDETERMINED);
        assertThat(result.evidence()).containsEntry("unitFloorFrozenAt",from.toString());
        if (hadFrozenFloor) assertThat(json.writeValueAsString(result.evidence().get("frozenUnitFloorInputs")))
                .contains(frozenFloorId.toString());
    }

    @Test void futureWindowAndMissingFrozenMembershipCannotQueryAnInventedScope() {
        var metrics=mock(CanonicalScopeMetricQuery.class);
        var actions=mock(ListingActionRepository.class);
        var action=mock(ListingActionRepository.ActionRow.class);
        var reader=new ListingOutcomeMetricEvidence(metrics,actions);
        Instant at=Instant.parse("2026-09-01T00:00:00Z");
        assertThat(reader.read(action,at,at.plusSeconds(1),14,"OPERATIONAL",at).gaps())
                .containsExactly("PROTECTION_METRIC_PERIOD_UNQUALIFIED");
        assertThat(reader.read(action,at.minusSeconds(14L*86400),at,14,"OPERATIONAL",at).gaps())
                .containsExactly("PROTECTION_DIRECT_SCOPE_UNQUALIFIED");
        verifyNoInteractions(metrics);
    }
}
