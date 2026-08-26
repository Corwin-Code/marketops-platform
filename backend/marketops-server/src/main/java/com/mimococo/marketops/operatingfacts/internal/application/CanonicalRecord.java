package com.mimococo.marketops.operatingfacts.internal.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One source record after its declared fields have been read and typed.
 *
 * <p>Every accessor returns an empty result rather than a substituted value when
 * the field is absent, which is what carries "the source did not say" all the
 * way through to a metric that has to report NOT_AVAILABLE.
 *
 * @param values the canonical fields the declaration resolved, by name
 */
public record CanonicalRecord(Map<String, Object> values) {

    public CanonicalRecord {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    /** A text field, when the record carries it. */
    public Optional<String> text(String field) {
        return value(field, String.class);
    }

    /** A required text field. */
    public String requiredText(String field) {
        return text(field).orElseThrow(() -> missing(field));
    }

    /** A whole-number field, when the record carries it. */
    public Optional<Long> integer(String field) {
        return value(field, Long.class);
    }

    /** A required whole-number field. */
    public long requiredInteger(String field) {
        return integer(field).orElseThrow(() -> missing(field));
    }

    /** An exact decimal field, when the record carries it. */
    public Optional<BigDecimal> decimal(String field) {
        return value(field, BigDecimal.class);
    }

    /** A required exact decimal field. */
    public BigDecimal requiredDecimal(String field) {
        return decimal(field).orElseThrow(() -> missing(field));
    }

    /** An instant field, when the record carries it. */
    public Optional<Instant> instant(String field) {
        return value(field, Instant.class);
    }

    /** A required instant field. */
    public Instant requiredInstant(String field) {
        return instant(field).orElseThrow(() -> missing(field));
    }

    /**
     * A three-valued flag, as the fact tables store it.
     *
     * <p>A boolean the source did not send becomes {@code UNKNOWN} rather than
     * {@code NO}. "The marketplace did not say whether a promotion is running"
     * and "the marketplace said no promotion is running" are different facts,
     * and a guardrail that confused them would authorise a change during a
     * promotion.
     */
    public String triState(String field) {
        return value(field, Boolean.class)
                .map(flag -> flag ? "YES" : "NO")
                .orElse("UNKNOWN");
    }

    private <T> Optional<T> value(String field, Class<T> type) {
        Object raw = values.get(field);
        return type.isInstance(raw) ? Optional.of(type.cast(raw)) : Optional.empty();
    }

    private static IllegalStateException missing(String field) {
        return new IllegalStateException("the record has no value for " + field);
    }
}
