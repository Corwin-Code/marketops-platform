package com.mimococo.marketops.analyticsdecision.internal.domain;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.shared.Digest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One metric the engine computed, before it is stored.
 *
 * <p>The value and its trustworthiness are produced together, because they come
 * from the same inputs. A number without the state that says whether it may be
 * acted on is a number somebody will act on regardless.
 *
 * <p>The digest covers the inputs, never the result. Recomputing from identical
 * inputs therefore lands on the row that already exists, and a recomputation
 * after a late return lands on a new one — which is exactly the behaviour the
 * append-only value table needs to stay both reproducible and correctable.
 *
 * @param metricCode which metric this is
 * @param valueState whether a number was produced, and why not when it was not
 * @param numericValue the number, or {@code null} unless available
 * @param currencyCode currency of a monetary value, or {@code null}
 * @param confidenceState how much weight the value can carry
 * @param oldestSourceTime earliest contributing source time, or {@code null}
 * @param inputs the exact inputs the value was derived from
 */
public record ComputedMetric(
        MetricCode metricCode,
        ValueState valueState,
        BigDecimal numericValue,
        String currencyCode,
        ConfidenceState confidenceState,
        Instant oldestSourceTime,
        List<MetricInput> inputs) {

    public ComputedMetric {
        Objects.requireNonNull(metricCode, "metricCode");
        Objects.requireNonNull(valueState, "valueState");
        Objects.requireNonNull(confidenceState, "confidenceState");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
    }

    /** Whether an explicit versioned estimate contributed to this value. */
    public boolean estimated() {
        return confidenceState == ConfidenceState.ESTIMATED_EXPLAINED;
    }

    /**
     * The digest that makes this value reproducible.
     *
     * <p>Inputs are sorted before hashing, so the order the engine happened to
     * collect them in cannot produce two digests for one set of facts.
     */
    public String inputDigest(int definitionVersion,
                              String subjectKind,
                              UUID subjectId,
                              String windowCode,
                              Instant periodStart,
                              Instant periodEnd) {
        List<String> components = new ArrayList<>();
        components.add(metricCode.name());
        components.add(Integer.toString(definitionVersion));
        components.add(subjectKind);
        components.add(subjectId.toString());
        components.add(windowCode);
        components.add(periodStart.toString());
        components.add(periodEnd.toString());
        inputs.stream()
                .sorted(Comparator.comparing((MetricInput input) -> input.kind().name())
                        .thenComparing(input -> input.referenceId().toString()))
                .forEach(input -> {
                    components.add(input.kind().name());
                    components.add(input.referenceId().toString());
                });
        return Digest.ofComponents(components);
    }

    /** How long after the freshest contributing source time a value was computed. */
    public Long freshnessSeconds(Instant computedAt) {
        return oldestSourceTime == null
                ? null : java.time.Duration.between(oldestSourceTime, computedAt).toSeconds();
    }
}
