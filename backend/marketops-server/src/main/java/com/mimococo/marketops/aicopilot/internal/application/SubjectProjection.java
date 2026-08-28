package com.mimococo.marketops.aicopilot.internal.application;

import com.mimococo.marketops.shared.Digest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Exactly what leaves this system for one subject, and nothing else.
 *
 * <p>The projection is a list of field-path and value pairs rather than an
 * object graph, because that is the shape the allowlist is written in: every
 * value can be checked against a declared path before anything is rendered, and
 * a field nobody declared has no way to travel.
 *
 * <p>The cited references are held separately from the rendered values. They are
 * what a model's factual claim must resolve against, so a claim citing an
 * identifier that was never projected is rejected — the model cannot invent a
 * reference to something it was not shown.
 *
 * @param fields the allowlisted field paths and their rendered values, in order
 * @param projectedMetricValueIds canonical values the model was shown
 * @param projectedFindingIds deterministic findings the model was shown
 */
public record SubjectProjection(
        List<Field> fields,
        Set<UUID> projectedMetricValueIds,
        Set<UUID> projectedFindingIds) {

    public SubjectProjection {
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        projectedMetricValueIds =
                Set.copyOf(Objects.requireNonNull(projectedMetricValueIds, "metricValueIds"));
        projectedFindingIds =
                Set.copyOf(Objects.requireNonNull(projectedFindingIds, "findingIds"));
    }

    /**
     * The digest of what was sent.
     *
     * <p>The projection itself is not retained beyond the call, because it
     * contains operating data the retention policy bounds. The digest is enough
     * to prove later that a recorded answer came from a particular set of
     * values without keeping those values indefinitely.
     */
    public String requestDigest() {
        List<String> components = new ArrayList<>();
        fields.forEach(field -> {
            components.add(field.path());
            components.add(field.value());
        });
        return Digest.ofComponents(components);
    }

    /** The distinct field paths this projection carries. */
    public Set<String> paths() {
        return fields.stream().map(Field::path).collect(java.util.stream.Collectors.toSet());
    }

    /** Render the projection as the lines a prompt carries. */
    public String render() {
        StringBuilder rendered = new StringBuilder();
        for (Field field : fields) {
            rendered.append(field.path()).append('=').append(field.value()).append('\n');
        }
        return rendered.toString();
    }

    /**
     * One projected value.
     *
     * @param path the declared field path, which the allowlist must contain
     * @param value the value, already rendered as text
     */
    public record Field(String path, String value) {

        public Field {
            Objects.requireNonNull(path, "path");
            value = value == null ? "" : value;
        }
    }

    /** An empty projection, used when a subject has nothing to describe. */
    public static SubjectProjection empty() {
        return new SubjectProjection(List.of(), Set.of(), Set.of());
    }

    /** Whether the projection carries anything worth asking about. */
    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** The projection as a map, for a caller that needs to inspect one field. */
    public Map<String, List<String>> byPath() {
        Map<String, List<String>> grouped = new java.util.LinkedHashMap<>();
        fields.forEach(field -> grouped
                .computeIfAbsent(field.path(), key -> new ArrayList<>())
                .add(field.value()));
        return Map.copyOf(grouped);
    }
}
