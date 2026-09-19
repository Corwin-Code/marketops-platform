package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.listingconversion.RatioState;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The conversion ratio is |S| / |V| and nothing else: visits de-duplicated by
 * key, retained purchases counted once per visit, and no value at all before
 * maturity or outside an eligible path.
 */
class VisitConversionTest {

    @Test
    void sourceCountsKeepBothChannelsUnknownVisitsAndDistinctPurchaseVisits() {
        var visits=List.of(visit("a","NO","ADVERTISING"),visit("a","NO","ADVERTISING"),
                visit("b","YES","ORGANIC"),visit("c","YES","ORGANIC"),visit("d","YES","UNKNOWN"));
        var counts=VisitConversion.sourceCounts(visits,List.of("a","a","b","d","outside-window"));
        assertThat(counts.get("ADVERTISING")).isEqualTo(new VisitConversion.SourceCount(1,1));
        assertThat(counts.get("ORGANIC")).isEqualTo(new VisitConversion.SourceCount(2,1));
        assertThat(counts.get("UNKNOWN")).isEqualTo(new VisitConversion.SourceCount(1,1));
    }

    private static VisitConversion.Visit visit(String key, String sellable, String channel) {
        return new VisitConversion.Visit(key, sellable, channel);
    }

    @Test
    @DisplayName("TC-LC-M01 the ratio is retained-purchase visits over distinct visits, truncated at six places")
    void ratioIsRetainedOverDistinctVisits() {
        List<VisitConversion.Visit> visits = List.of(
                visit("v1", "YES", "ADVERTISING"), visit("v2", "NO", "ORGANIC"),
                visit("v3", "YES", "ORGANIC"), visit("v1", "YES", "ADVERTISING"));

        VisitConversion.Result result = VisitConversion.compute(visits, Set.of("v1", "v9"), true, true);

        assertThat(result.visitCount()).isEqualTo(3);
        assertThat(result.retainedPurchaseVisitCount()).isEqualTo(1);
        assertThat(result.ratio()).isEqualByComparingTo("0.333333");
        assertThat(result.state()).isEqualTo(RatioState.DEFINED);
        assertThat(result.sourceStratified()).isTrue();
        assertThat(result.sellableSplit()).containsEntry("YES", "0.500000")
                .containsEntry("NO", "0.000000").containsEntry("UNKNOWN", "UNDEFINED");
    }

    @Test
    @DisplayName("TC-LC-M02 before maturity the ratio has no value, and the counts are still reported")
    void immatureWindowHasNoValue() {
        VisitConversion.Result result = VisitConversion.compute(
                List.of(visit("v1", "YES", "ORGANIC")), Set.of("v1"), false, true);

        assertThat(result.state()).isEqualTo(RatioState.NOT_AVAILABLE);
        assertThat(result.ratio()).isNull();
        assertThat(result.visitCount()).isEqualTo(1);
        assertThat(result.retainedPurchaseVisitCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-LC-M03 an ineligible measurement has no value even when mature")
    void ineligibleMeasurementHasNoValue() {
        VisitConversion.Result result = VisitConversion.compute(
                List.of(visit("v1", "YES", "ORGANIC")), Set.of("v1"), true, false);

        assertThat(result.state()).isEqualTo(RatioState.NOT_AVAILABLE);
        assertThat(result.ratio()).isNull();
    }

    @Test
    @DisplayName("TC-LC-M04 zero visits is UNDEFINED, never zero and never an error")
    void zeroVisitsIsUndefined() {
        VisitConversion.Result result = VisitConversion.compute(List.of(), Set.of("v1"), true, true);

        assertThat(result.state()).isEqualTo(RatioState.UNDEFINED);
        assertThat(result.ratio()).isNull();
        assertThat(result.visitCount()).isZero();
    }

    @Test
    @DisplayName("TC-LC-M05 one visit with an unknown source channel breaks stratification")
    void unknownChannelBreaksStratification() {
        VisitConversion.Result result = VisitConversion.compute(
                List.of(visit("v1", "YES", "ORGANIC"), visit("v2", null, null)), Set.of(), true, true);

        assertThat(result.sourceStratified()).isFalse();
        assertThat(result.sellableSplit()).containsEntry("UNKNOWN", "0.000000");
    }
}
