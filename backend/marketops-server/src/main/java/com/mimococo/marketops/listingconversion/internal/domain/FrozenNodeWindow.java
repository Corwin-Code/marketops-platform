package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.listingconversion.ConversionMeasurementView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Exact fixed observation cohort and finite evaluation schedule, never a rolling now-minus window. */
public final class FrozenNodeWindow {
    private FrozenNodeWindow() { }
    public record Admission(Instant windowStart, Instant windowEnd, int retentionDays,
                            Instant notBefore, Instant lastEvaluation, List<String> gaps) {
        public Admission { gaps = List.copyOf(gaps); }
        public boolean admitted() { return gaps.isEmpty(); }
    }

    public static Admission assess(FrozenComparisonMethod method, Instant frozenAt, Instant latestBoundary,
                                   Instant launchedAt, ConversionMeasurementView measurement, Instant now,
                                   boolean revisesPreviouslyAdmittedSameWindow) {
        if (method == null) return new Admission(null, null, 0, null, null, List.of("FROZEN_METHOD_OR_SCHEDULE_UNQUALIFIED"));
        Instant from = frozenAt.plus(Duration.ofDays(method.windowStartDay()));
        Instant to = frozenAt.plus(Duration.ofDays(method.windowEndDay()));
        Instant first = frozenAt.plus(Duration.ofDays(method.notBeforeDays()));
        Instant last = frozenAt.plus(Duration.ofDays(method.lastDay()));
        List<String> gaps = new ArrayList<>();
        if (last.isAfter(latestBoundary)) gaps.add("NODE_EXCEEDS_FROZEN_LATEST_BOUNDARY");
        if (now.isBefore(first)) gaps.add("FORMAL_NODE_NOT_DUE");
        if ((now.isAfter(last) || now.isAfter(latestBoundary)) && !revisesPreviouslyAdmittedSameWindow)
            gaps.add("FIRST_EVALUATION_AFTER_FROZEN_BOUNDARY");
        if (launchedAt == null) gaps.add("ACTION_NOT_LAUNCHED");
        else if (launchedAt.isAfter(from)) gaps.add("ACTION_LAUNCHED_AFTER_FROZEN_WINDOW_START");
        if (measurement == null) gaps.add("MEASUREMENT_MISSING");
        else {
            if (!from.equals(measurement.windowStart()) || !to.equals(measurement.windowEnd()))
                gaps.add("MEASUREMENT_OUTSIDE_FROZEN_WINDOW");
            if (measurement.retentionWindowDays() != method.maturityDays()) gaps.add("MEASUREMENT_RETENTION_MISMATCH");
            if (!measurement.maturityReached() || measurement.sourceTime() == null
                    || measurement.sourceTime().isBefore(to.plus(Duration.ofDays(method.maturityDays()))))
                gaps.add("SOURCE_MATURITY_UNPROVEN");
            if (measurement.computedAt() == null || measurement.computedAt().isAfter(now)) gaps.add("MEASUREMENT_FROM_FUTURE");
            if (measurement.acquisitionTime()==null) gaps.add("MEASUREMENT_ACQUISITION_TIME_UNKNOWN");
            else if (measurement.acquisitionTime().isAfter(now)
                    || (measurement.computedAt()!=null && measurement.acquisitionTime().isAfter(measurement.computedAt())))
                gaps.add("MEASUREMENT_ACQUISITION_FROM_FUTURE");
        }
        return new Admission(from, to, method.maturityDays(), first, last, gaps);
    }
}
