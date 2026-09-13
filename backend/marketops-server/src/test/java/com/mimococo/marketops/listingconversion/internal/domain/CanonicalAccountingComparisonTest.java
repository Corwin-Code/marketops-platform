package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import com.mimococo.marketops.analyticsdecision.*;
import com.mimococo.marketops.listingconversion.ProtectionVerdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalAccountingComparisonTest {
    final UUID member=UUID.randomUUID();
    final Instant at=Instant.parse("2026-08-01T00:00:00Z");
    @Test void profitProtectsLossMakingAndZeroReferenceWithoutDividingByTheReference() {
        assertThat(CanonicalAccountingComparison.profit(profit("-100"),profit("-110"),new BigDecimal("0.1")))
                .isEqualTo(ProtectionVerdict.PASS);
        assertThat(CanonicalAccountingComparison.profit(profit("-100"),profit("-111"),new BigDecimal("0.1")))
                .isEqualTo(ProtectionVerdict.FAIL);
        assertThat(CanonicalAccountingComparison.profit(profit("0"),profit("-0.0001"),new BigDecimal("0.1")))
                .isEqualTo(ProtectionVerdict.FAIL);
    }
    @Test void equalDisplayedReturnRatesCannotHideAnExactBoundaryFailure() {
        var old=returns("1","3");
        var current=returns("1000000000000000001","3000000000000000000");
        assertThat(old.value()).isEqualByComparingTo(current.value());
        assertThat(CanonicalAccountingComparison.returns(old,current,new BigDecimal("0.0000000000000000001")))
                .isEqualTo(ProtectionVerdict.FAIL);
        assertThat(CanonicalAccountingComparison.returns(old,returns("2","6"),BigDecimal.ZERO))
                .isEqualTo(ProtectionVerdict.PASS);
    }
    @Test void unitProfitConsumesTheCanonicalFloorAndSamePeriodCompletedUnitsWithoutAveragingVariants() {
        var profit=value(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,"100","RUB");
        var units=value(MetricCode.COMPLETED_UNITS,"10",null);
        assertThat(CanonicalAccountingComparison.unitProfit(new CanonicalScopeMetricQuery.UnitProfitInput(profit,units,
                value(MetricCode.REQUIRED_PROFIT_PER_UNIT,"10","RUB")),at)).isEqualTo(ProtectionVerdict.PASS);
        assertThat(CanonicalAccountingComparison.unitProfit(new CanonicalScopeMetricQuery.UnitProfitInput(profit,units,
                value(MetricCode.REQUIRED_PROFIT_PER_UNIT,"10.0001","RUB")),at)).isEqualTo(ProtectionVerdict.FAIL);
        assertThat(CanonicalAccountingComparison.unitProfit(new CanonicalScopeMetricQuery.UnitProfitInput(profit,units,
                value(MetricCode.REQUIRED_PROFIT_PER_UNIT,"10","USD")),at)).isEqualTo(ProtectionVerdict.UNDETERMINED);
        assertThat(CanonicalAccountingComparison.unitProfit(new CanonicalScopeMetricQuery.UnitProfitInput(profit,null,
                value(MetricCode.REQUIRED_PROFIT_PER_UNIT,"10","RUB")),at)).isEqualTo(ProtectionVerdict.UNDETERMINED);
    }

    @Test void settledProfitRequiresSettledUnitsInsteadOfBorrowingTheCompletedDenominator() {
        var profit=value(MetricCode.SETTLED_CONTRIBUTION_PROFIT,"30","RUB");
        var floor=value(MetricCode.REQUIRED_PROFIT_PER_UNIT,"10","RUB");
        assertThat(CanonicalAccountingComparison.unitProfit(new CanonicalScopeMetricQuery.UnitProfitInput(profit,
                value(MetricCode.SETTLED_UNITS,"3",null),floor),at)).isEqualTo(ProtectionVerdict.PASS);
        assertThat(CanonicalAccountingComparison.unitProfit(new CanonicalScopeMetricQuery.UnitProfitInput(profit,
                value(MetricCode.COMPLETED_UNITS,"3",null),floor),at)).isEqualTo(ProtectionVerdict.UNDETERMINED);
    }

    private CanonicalScopeMetricQuery.Observation profit(String amount) {
        return new CanonicalScopeMetricQuery.Observation(new BigDecimal(amount),"RUB",List.of(),
                List.of(value(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,amount,"RUB")));
    }
    private CanonicalScopeMetricQuery.Observation returns(String returned,String completed) {
        return new CanonicalScopeMetricQuery.Observation(new BigDecimal("0.333333333333333333"),null,List.of(),
                List.of(value(MetricCode.RETURN_UNITS,returned,null),value(MetricCode.COMPLETED_UNITS,completed,null)));
    }
    private MetricValueView value(MetricCode code,String amount,String currency) {
        return new MetricValueView(UUID.randomUUID(),code,1,SubjectKind.PLATFORM_LISTING_VARIANT,member,MetricWindow.D7,
                at.minusSeconds(604800),at,ValueState.AVAILABLE,new BigDecimal(amount),currency,
                ConfidenceState.CANONICAL_CONFIRMED,false,at,0L,"a".repeat(64),at,List.of(UUID.randomUUID()),at,UUID.randomUUID());
    }
}
