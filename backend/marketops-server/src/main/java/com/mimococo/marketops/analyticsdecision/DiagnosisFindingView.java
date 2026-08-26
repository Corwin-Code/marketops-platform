package com.mimococo.marketops.analyticsdecision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What one deterministic rule concluded about one subject.
 *
 * <p>A declined finding is stored and returned like any other. An operator has
 * to be able to see that a rule could not answer, and why, rather than reading
 * silence as a clean result.
 *
 * @param findingId identifier of the stored finding
 * @param ruleCode which rule produced it
 * @param ruleVersion the rule version that produced it
 * @param subjectKind what the finding is about
 * @param subjectId identifier of the subject
 * @param window the observation window
 * @param outcome whether the rule triggered, was clear, or declined
 * @param severity how serious a triggered finding is, or {@code null}
 * @param declineReason why a declined rule could not answer, or {@code null}
 * @param detail the comparison the rule made, in operator-readable terms
 * @param blocksExecution whether this finding blocks a platform write
 * @param evaluatedAt when the rule ran
 * @param metricValueIds the canonical values the rule read
 */
public record DiagnosisFindingView(
        UUID findingId,
        String ruleCode,
        int ruleVersion,
        SubjectKind subjectKind,
        UUID subjectId,
        MetricWindow window,
        Outcome outcome,
        Severity severity,
        String declineReason,
        Map<String, String> detail,
        boolean blocksExecution,
        Instant evaluatedAt,
        List<UUID> metricValueIds) {

    public DiagnosisFindingView {
        detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
        metricValueIds = List.copyOf(Objects.requireNonNull(metricValueIds, "metricValueIds"));
    }

    /** What a rule concluded. */
    public enum Outcome {

        /** The condition the rule looks for is present. */
        TRIGGERED,

        /** The rule evaluated and found nothing. */
        CLEAR,

        /** The rule could not answer, and says why. */
        DECLINED
    }

    /** How serious a triggered finding is. */
    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }
}
