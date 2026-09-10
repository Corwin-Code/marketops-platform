package com.mimococo.marketops.analyticsdecision.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mimococo.marketops.analyticsdecision.*;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.ListingVariantContext;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CanonicalScopeMetricServiceTest {
    final MetricQuery metrics=mock(MetricQuery.class);
    final ListingIdentityDirectory identities=mock(ListingIdentityDirectory.class);
    final OrganizationDirectory organizations=mock(OrganizationDirectory.class);
    final CanonicalScopeMetricService service=new CanonicalScopeMetricService(metrics,identities,organizations);
    final UUID org=UUID.randomUUID(),store=UUID.randomUUID(),a=UUID.randomUUID(),b=UUID.randomUUID();
    final Instant end=Instant.parse("2026-08-01T00:00:00Z"),start=end.minusSeconds(7L*86400),asOf=end.plusSeconds(86400);
    final Map<UUID,Map<MetricCode,MetricValueView>> rows=new HashMap<>();

    @BeforeEach void twoUnequalCanonicalMemberWindows() {
        when(organizations.store(store)).thenReturn(Optional.of(new StoreRef(store,org,UUID.randomUUID(),"fixture","ACTIVE")));
        for (UUID id:List.of(a,b)) {
            when(identities.variantContext(id,asOf)).thenReturn(Optional.of(identity(id,store)));
            rows.put(id,new EnumMap<>(MetricCode.class));
            when(metrics.currentValuesForPeriodAt(SubjectKind.PLATFORM_LISTING_VARIANT,id,MetricWindow.D7,start,end,asOf))
                    .thenAnswer(call->rows.get(id));
        }
        put(a,MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,"10","RUB",2,ConfidenceState.CANONICAL_CONFIRMED);
        put(b,MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,"-3","RUB",2,ConfidenceState.ESTIMATED_EXPLAINED);
        put(a,MetricCode.SETTLED_CONTRIBUTION_PROFIT,"8","RUB",2,ConfidenceState.CANONICAL_CONFIRMED);
        put(b,MetricCode.SETTLED_CONTRIBUTION_PROFIT,"-4","RUB",2,ConfidenceState.CANONICAL_CONFIRMED);
        put(a,MetricCode.RETURN_UNITS,"1",null,2,ConfidenceState.CANONICAL_CONFIRMED);
        put(a,MetricCode.COMPLETED_UNITS,"2",null,2,ConfidenceState.CANONICAL_CONFIRMED);
        put(b,MetricCode.RETURN_UNITS,"9",null,2,ConfidenceState.CANONICAL_CONFIRMED);
        put(b,MetricCode.COMPLETED_UNITS,"98",null,2,ConfidenceState.CANONICAL_CONFIRMED);
    }

    @Test void scopeUsesWholeProfitAndWeightedReturnCountsWhileRetainingEstimateEvidence() {
        var result=service.project(scope(CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL));
        assertThat(result.contributionProfit().value()).isEqualByComparingTo("7");
        assertThat(result.contributionProfit().components()).extracting(MetricValueView::confidenceState)
                .contains(ConfidenceState.ESTIMATED_EXPLAINED);
        assertThat(result.returnRate().value()).isEqualByComparingTo("0.1");
        assertThat(result.returnRate().components()).hasSize(4);
        assertThat(result.contributionProfit().currencyCode()).isEqualTo("RUB");
        assertThat(service.project(scope(CanonicalScopeMetricQuery.ProfitBasis.SETTLED)).contributionProfit().value())
                .isEqualByComparingTo("4");
        verify(metrics,never()).currentValuesCoveringAt(any(),any(),any(),any(),any(),any());
    }

    @Test void absentMemberMetricDoesNotBecomeZeroAndDoesNotEraseIndependentReturnEvidence() {
        rows.get(b).remove(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT);
        var result=service.project(scope(CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL));
        assertThat(result.contributionProfit().available()).isFalse();
        assertThat(result.contributionProfit().value()).isNull();
        assertThat(result.contributionProfit().gaps()).contains("OPERATIONAL_CONTRIBUTION_PROFIT_MEMBER_VALUE_MISSING");
        assertThat(result.returnRate().value()).isEqualByComparingTo("0.1");
    }

    @Test void mixedCurrencyOrDefinitionCannotProduceAComparableTotal() {
        put(b,MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,"-3","USD",2,ConfidenceState.CANONICAL_CONFIRMED);
        assertThat(service.project(scope(CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL)).contributionProfit().gaps())
                .contains("PROFIT_CURRENCY_UNRESOLVED");
        put(b,MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,"-3","RUB",3,ConfidenceState.CANONICAL_CONFIRMED);
        assertThat(service.project(scope(CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL)).contributionProfit().gaps())
                .contains("OPERATIONAL_CONTRIBUTION_PROFIT_DEFINITION_MISMATCH");
    }

    @Test void zeroSalesDenominatorIsNotZeroReturnRate() {
        put(a,MetricCode.COMPLETED_UNITS,"0",null,2,ConfidenceState.CANONICAL_CONFIRMED);
        put(b,MetricCode.COMPLETED_UNITS,"0",null,2,ConfidenceState.CANONICAL_CONFIRMED);
        var result=service.project(scope(CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL));
        assertThat(result.returnRate().value()).isNull();
        assertThat(result.returnRate().gaps()).contains("RETURN_DENOMINATOR_ZERO");
        assertThat(result.contributionProfit().value()).isEqualByComparingTo("7");
    }

    @Test void anotherStoreMemberIsRefusedBeforeReadingAnyNumbers() {
        when(identities.variantContext(b,asOf)).thenReturn(Optional.of(identity(b,UUID.randomUUID())));
        assertThatThrownBy(()->service.project(scope(CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL)))
                .isInstanceOf(OperationRejectedException.class);
        verifyNoInteractions(metrics);
    }

    @Test void anotherOrganizationAndDuplicateMembersCannotBorrowOrDoubleCountValues() {
        var wrongOrg=new CanonicalScopeMetricQuery.Scope(UUID.randomUUID(),store,List.of(a,b),MetricWindow.D7,start,end,asOf,
                CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL);
        assertThatThrownBy(()->service.project(wrongOrg)).isInstanceOf(OperationRejectedException.class);
        var duplicate=new CanonicalScopeMetricQuery.Scope(org,store,List.of(a,a),MetricWindow.D7,start,end,asOf,
                CanonicalScopeMetricQuery.ProfitBasis.OPERATIONAL);
        assertThatThrownBy(()->service.project(duplicate)).isInstanceOf(OperationRejectedException.class);
        verifyNoInteractions(metrics);
    }

    private CanonicalScopeMetricQuery.Scope scope(CanonicalScopeMetricQuery.ProfitBasis basis) {
        return new CanonicalScopeMetricQuery.Scope(org,store,List.of(a,b),MetricWindow.D7,start,end,asOf,basis);
    }
    private ListingVariantContext identity(UUID member,UUID storeId) {
        return new ListingVariantContext(member,UUID.randomUUID(),storeId,UUID.randomUUID(),"OZON","listing",member.toString(),
                UUID.randomUUID(),UUID.randomUUID(),false);
    }
    private void put(UUID member,MetricCode code,String number,String currency,int definition,ConfidenceState confidence) {
        rows.get(member).put(code,new MetricValueView(UUID.randomUUID(),code,definition,SubjectKind.PLATFORM_LISTING_VARIANT,
                member,MetricWindow.D7,start,end,ValueState.AVAILABLE,new BigDecimal(number),currency,confidence,
                confidence==ConfidenceState.ESTIMATED_EXPLAINED,end,0L,"a".repeat(64),asOf,List.of(UUID.randomUUID()),asOf,UUID.randomUUID()));
    }
}
