package com.mimococo.marketops.analyticsdecision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Published read access to canonical metric values.
 *
 * <p>Every consumer — the console, the guardrails, the model projection — reads
 * through this contract, so the same number reaches all of them with the same
 * confidence and the same evidence attached. There is no second calculator, and
 * no consumer computes a metric of its own from raw facts.
 *
 * <p>Current means most recently computed. A late return or a settlement
 * adjustment produces a new value beside the old one rather than over it, so
 * asking for the current value and asking what was true last week are different
 * questions with different answers.
 */
public interface MetricQuery {

    /** The current value of one metric for one subject and window. */
    Optional<MetricValueView> current(MetricCode metricCode,
                                      SubjectKind subjectKind,
                                      UUID subjectId,
                                      MetricWindow window);

    /** The current value of every metric for one subject and window. */
    Map<MetricCode, MetricValueView> currentValues(SubjectKind subjectKind,
                                                   UUID subjectId,
                                                   MetricWindow window);

    /** The latest value of every metric no later than one captured decision instant. */
    Map<MetricCode, MetricValueView> currentValuesAt(SubjectKind subjectKind,
                                                     UUID subjectId,
                                                     MetricWindow window,
                                                     Instant at);

    /** Latest values whose canonical business window covers the complete consumed cohort. */
    Map<MetricCode, MetricValueView> currentValuesCoveringAt(SubjectKind subjectKind,
                                                            UUID subjectId,
                                                            MetricWindow window,
                                                            Instant cohortFrom,
                                                            Instant cohortTo,
                                                            Instant at);

    /**
     * Every stored value of one metric for one subject, newest first.
     *
     * <p>This is how an operator sees that a figure moved because the facts
     * changed rather than because somebody changed the definition.
     */
    List<MetricValueView> history(MetricCode metricCode,
                                  SubjectKind subjectKind,
                                  UUID subjectId,
                                  MetricWindow window,
                                  int limit);
}
