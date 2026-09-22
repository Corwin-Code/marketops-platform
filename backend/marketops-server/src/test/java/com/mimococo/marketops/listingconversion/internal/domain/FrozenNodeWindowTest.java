package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import com.mimococo.marketops.listingconversion.ConversionMeasurementView;
import com.mimococo.marketops.listingconversion.EvidencePath;
import com.mimococo.marketops.listingconversion.RatioState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenNodeWindowTest {
    final Instant frozen = Instant.parse("2026-01-01T00:00:00Z");
    final FrozenComparisonMethod method = FrozenComparisonMethodTest.method();
    Instant day(int offset) { return frozen.plusSeconds(86400L*offset); }
    ConversionMeasurementView measurement(int from, int to, int retention, int source, int computed) {
        return new ConversionMeasurementView(UUID.randomUUID(), UUID.randomUUID(), 1, day(from), day(to), retention,
                EvidencePath.DETAIL, true, List.of(), 100L, 20L, new BigDecimal("0.2"), RatioState.DEFINED,
                true, true, Map.of(), List.of(), day(source), day(computed), day(computed));
    }
    FrozenNodeWindow.Admission assess(ConversionMeasurementView measurement, int now, boolean revision) {
        return FrozenNodeWindow.assess(method, frozen, day(42), frozen, measurement, day(now), revision);
    }

    @Test void exactMatureWindowAtTheFirstAndLastPermittedInstantIsAdmitted() {
        assertThat(assess(measurement(0,14,14,28,28),28,false).admitted()).isTrue();
        assertThat(assess(measurement(0,14,14,28,28),42,false).admitted()).isTrue();
        assertThat(assess(measurement(0,14,14,28,28),28,false).windowStart()).isEqualTo(frozen);
    }
    @Test void earlyOrFirstLateEvaluationCannotManufactureAFormalNode() {
        assertThat(assess(measurement(0,14,14,28,28),27,false).gaps()).contains("FORMAL_NODE_NOT_DUE", "MEASUREMENT_FROM_FUTURE");
        assertThat(assess(measurement(0,14,14,28,28),43,false).gaps()).contains("FIRST_EVALUATION_AFTER_FROZEN_BOUNDARY");
    }
    @Test void lateRevisionReusesTheOriginalCohortWithoutMovingItsWindow() {
        assertThat(assess(measurement(0,14,14,50,50),50,true).admitted()).isTrue();
        assertThat(assess(measurement(1,15,14,50,50),50,true).gaps()).contains("MEASUREMENT_OUTSIDE_FROZEN_WINDOW");
        assertThat(assess(measurement(0,14,30,50,50),50,true).gaps()).contains("MEASUREMENT_RETENTION_MISMATCH");
    }
    @Test void wallClockOrMaturityBooleanCannotReplaceSourceCompleteness() {
        assertThat(assess(measurement(0,14,14,27,28),28,false).gaps()).contains("SOURCE_MATURITY_UNPROVEN");
        assertThat(assess(measurement(0,14,7,28,28),28,false).gaps()).contains("MEASUREMENT_RETENTION_MISMATCH");
    }
    @Test void processingTimeCannotReplaceUnknownOrLaterEvidenceAcquisition() {
        var value=measurement(0,14,14,28,28);
        var unknown=new ConversionMeasurementView(value.id(),value.platformListingId(),value.definitionVersion(),
                value.windowStart(),value.windowEnd(),value.retentionWindowDays(),value.evidencePath(),value.pathQualified(),
                value.qualificationReasonCodes(),value.visitCount(),value.retainedPurchaseVisitCount(),value.primaryRatio(),
                value.ratioState(),value.maturityReached(),value.sourceStratified(),value.sellableSplit(),
                value.excludedTransitionDays(),value.sourceTime(),null,value.computedAt());
        assertThat(assess(unknown,28,false).gaps()).contains("MEASUREMENT_ACQUISITION_TIME_UNKNOWN");
        var later=new ConversionMeasurementView(value.id(),value.platformListingId(),value.definitionVersion(),
                value.windowStart(),value.windowEnd(),value.retentionWindowDays(),value.evidencePath(),value.pathQualified(),
                value.qualificationReasonCodes(),value.visitCount(),value.retainedPurchaseVisitCount(),value.primaryRatio(),
                value.ratioState(),value.maturityReached(),value.sourceStratified(),value.sellableSplit(),
                value.excludedTransitionDays(),value.sourceTime(),day(29),value.computedAt());
        assertThat(assess(later,30,false).gaps()).contains("MEASUREMENT_ACQUISITION_FROM_FUTURE");
    }
    @Test void absentOrLateLaunchCannotBeTreatedAsTargetVersionExposure() {
        var measurement = measurement(0,14,14,28,28);
        assertThat(FrozenNodeWindow.assess(method,frozen,day(42),null,measurement,day(28),false).gaps()).contains("ACTION_NOT_LAUNCHED");
        assertThat(FrozenNodeWindow.assess(method,frozen,day(42),day(1),measurement,day(28),false).gaps()).contains("ACTION_LAUNCHED_AFTER_FROZEN_WINDOW_START");
    }
    @Test void unsupportedMethodAbsentMeasurementAndConflictingBoundaryStayExplicit() {
        assertThat(FrozenNodeWindow.assess(null,frozen,day(42),frozen,null,day(28),false).gaps()).contains("FROZEN_METHOD_OR_SCHEDULE_UNQUALIFIED");
        assertThat(assess(null,28,false).gaps()).contains("MEASUREMENT_MISSING");
        assertThat(FrozenNodeWindow.assess(method,frozen,day(40),frozen,measurement(0,14,14,28,28),day(28),false).gaps())
                .contains("NODE_EXCEEDS_FROZEN_LATEST_BOUNDARY");
    }
}
