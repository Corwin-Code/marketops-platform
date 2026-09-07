package com.mimococo.marketops.analyticsdecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One canonical metric value, as every consumer sees it.
 *
 * <p>The view is deliberately not just a number. A caller has to be able to tell
 * an available value from an absent one, a confirmed value from an estimate, and
 * a fresh value from a stale one, because those distinctions decide whether the
 * value may drive a platform write or only an explanation.
 *
 * @param metricValueId identifier of the stored value
 * @param metricCode which metric this is
 * @param definitionVersion the definition that produced it
 * @param subjectKind what the value is about
 * @param subjectId identifier of the subject
 * @param window the observation window
 * @param periodStart first instant inside the window
 * @param periodEnd first instant after the window
 * @param valueState whether a number was produced, and why not when it was not
 * @param numericValue the number, or {@code null} unless available
 * @param currencyCode currency of a monetary value, or {@code null}
 * @param confidenceState how much weight the value can carry
 * @param estimated whether an explicit estimate contributed
 * @param oldestSourceTime the earliest contributing source time, or {@code null}
 * @param freshnessSeconds how old the freshest answer is, or {@code null}
 * @param inputDigest digest of the inputs, making the value reproducible
 * @param computedAt when the value was computed
 * @param evidenceRefs the provenance records behind the value
 * @param verifiedAt latest successful evaluation of this exact value, preserving its original computation
 * @param verificationRunId the corresponding canonical computation run
 */
public record MetricValueView(
        UUID metricValueId,
        MetricCode metricCode,
        int definitionVersion,
        SubjectKind subjectKind,
        UUID subjectId,
        MetricWindow window,
        Instant periodStart,
        Instant periodEnd,
        ValueState valueState,
        BigDecimal numericValue,
        String currencyCode,
        ConfidenceState confidenceState,
        boolean estimated,
        Instant oldestSourceTime,
        Long freshnessSeconds,
        String inputDigest,
        Instant computedAt,
        List<UUID> evidenceRefs,
        Instant verifiedAt,
        UUID verificationRunId) {

    public MetricValueView {
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs, "evidenceRefs"));
    }

    /** Original values without an additional evaluation retain their original age. */
    public MetricValueView(UUID metricValueId, MetricCode metricCode, int definitionVersion,
            SubjectKind subjectKind, UUID subjectId, MetricWindow window, Instant periodStart,
            Instant periodEnd, ValueState valueState, BigDecimal numericValue, String currencyCode,
            ConfidenceState confidenceState, boolean estimated, Instant oldestSourceTime,
            Long freshnessSeconds, String inputDigest, Instant computedAt, List<UUID> evidenceRefs) {
        this(metricValueId,metricCode,definitionVersion,subjectKind,subjectId,window,periodStart,
                periodEnd,valueState,numericValue,currencyCode,confidenceState,estimated,oldestSourceTime,
                freshnessSeconds,inputDigest,computedAt,evidenceRefs,computedAt,null);
    }

    /** Whether the value carries a number a caller may use. */
    public boolean available() {
        return valueState == ValueState.AVAILABLE;
    }
}
