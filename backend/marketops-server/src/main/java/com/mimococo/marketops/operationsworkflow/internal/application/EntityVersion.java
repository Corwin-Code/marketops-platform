package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.shared.Digest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The identity of the facts a decision was made about.
 *
 * <p>An approval authorizes one exact proposal built from one exact set of
 * values. Recording a digest of those values lets the write gate ask, at the
 * moment of the write, whether anything has changed since — which is the
 * difference between "somebody approved this price" and "somebody approved this
 * price when the cost was what it is now".
 *
 * <p>The digest is taken over each metric's own reproducibility digest rather
 * than over its number. Two values that carry the same number from different
 * inputs are different facts, and a recomputation that changed nothing produces
 * the same digest, so the gate does not refuse work for no reason.
 */
final class EntityVersion {

    private EntityVersion() {
    }

    /**
     * Digest the canonical values a case rests on.
     *
     * <p>Ordering is by metric name so the digest does not depend on the order a
     * map happened to iterate in.
     */
    static String of(Map<MetricCode, MetricValueView> metrics) {
        Map<String, MetricValueView> ordered = new TreeMap<>();
        metrics.forEach((code, value) -> ordered.put(code.name(), value));
        List<String> components = new ArrayList<>();
        ordered.forEach((name, value) -> {
            components.add(name);
            components.add(value.valueState().name());
            components.add(value.inputDigest());
        });
        return Digest.ofComponents(components);
    }
}
