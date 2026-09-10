package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FixedTrafficComparisonTest {
    private static final Map<String,BigDecimal> WEIGHTS=Map.of("ADVERTISING",new BigDecimal("0.5"),"ORGANIC",new BigDecimal("0.5"));
    private static Map<String,FixedTrafficComparison.Count> counts(long ads,long adRetained,long organic,long organicRetained) {
        return Map.of("ADVERTISING",new FixedTrafficComparison.Count(ads,adRetained),
                "ORGANIC",new FixedTrafficComparison.Count(organic,organicRetained));
    }

    @Test void trafficCompositionCannotMasqueradeAsImprovement() {
        // The actual total jumps from 0.28 to 0.92 solely from source composition.
        var r=FixedTrafficComparison.compare(WEIGHTS,counts(900,180,100,100),counts(100,20,900,900),new BigDecimal("2.8"),true);
        assertThat(r.referenceStandardized()).isEqualByComparingTo("0.6");
        assertThat(r.targetStandardized()).isEqualByComparingTo("0.6");
        assertThat(r.observedDifference()).isEqualByComparingTo("0");
        assertThat(r.lowerDifference()).isNegative();
        assertThat(r.upperDifference()).isPositive();
    }

    @Test void highAbsoluteRateCanBeARealDeterioration() {
        var r=FixedTrafficComparison.compare(WEIGHTS,counts(10000,9000,10000,9000),counts(10000,8000,10000,8000),new BigDecimal("2.8"),true);
        assertThat(r.observedDifference()).isEqualByComparingTo("-0.1");
        assertThat(r.upperDifference()).isNegative();
    }

    @Test void noCriticalValueOrUnqualifiedScheduleCannotUsePointEstimateAsBound() {
        var r=FixedTrafficComparison.compare(WEIGHTS,counts(10000,100,10000,100),counts(10000,9000,10000,9000),null,true);
        assertThat(r.observedDifference()).isPositive();
        assertThat(r.lowerDifference()).isNull();
        assertThat(FixedTrafficComparison.compare(WEIGHTS,counts(100,10,100,10),counts(100,90,100,90),new BigDecimal("2"),false).lowerDifference()).isNull();
    }

    @Test void missingStratumOrInvalidWeightsCannotBeRenormalized() {
        assertThat(FixedTrafficComparison.compare(Map.of("ADVERTISING",BigDecimal.ONE),counts(100,10,100,10),counts(100,20,100,20),new BigDecimal("2"),true).observedDifference()).isNull();
        assertThat(FixedTrafficComparison.compare(WEIGHTS,counts(100,10,100,10),counts(100,20,0,0),new BigDecimal("2"),true).observedDifference()).isNull();
    }
}
