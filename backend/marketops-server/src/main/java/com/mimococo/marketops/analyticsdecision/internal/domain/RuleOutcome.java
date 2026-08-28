package com.mimococo.marketops.analyticsdecision.internal.domain;

import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.shared.Digest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What one rule concluded, before it is stored.
 *
 * <p>All three outcomes carry the metrics the rule read, including a decline:
 * knowing which inputs a rule looked at before giving up is what turns "the
 * system said nothing" into "the system could not answer because this was
 * missing".
 *
 * <p>The detail is a small map of the comparison the rule made. It is
 * deliberately values rather than a sentence, so the console, a test and a
 * runbook can all read the same numbers without parsing prose.
 *
 * @param ruleCode which rule produced this
 * @param outcome whether the rule triggered, was clear, or declined
 * @param severity how serious a triggered finding is, or {@code null}
 * @param declineReason why a declined rule could not answer, or {@code null}
 * @param detail the comparison the rule made
 * @param readMetrics the canonical metrics the rule read
 */
public record RuleOutcome(
        String ruleCode,
        DiagnosisFindingView.Outcome outcome,
        DiagnosisFindingView.Severity severity,
        String declineReason,
        Map<String, String> detail,
        List<ComputedMetric> readMetrics) {

    public RuleOutcome {
        Objects.requireNonNull(ruleCode, "ruleCode");
        Objects.requireNonNull(outcome, "outcome");
        detail = Map.copyOf(Objects.requireNonNull(detail, "detail"));
        readMetrics = List.copyOf(Objects.requireNonNull(readMetrics, "readMetrics"));
    }

    /** The rule found the condition it looks for. */
    public static RuleOutcome triggered(String ruleCode,
                                        DiagnosisFindingView.Severity severity,
                                        Map<String, String> detail,
                                        List<ComputedMetric> readMetrics) {
        return new RuleOutcome(ruleCode, DiagnosisFindingView.Outcome.TRIGGERED, severity,
                null, detail, readMetrics);
    }

    /** The rule evaluated and found nothing. */
    public static RuleOutcome clear(String ruleCode,
                                    Map<String, String> detail,
                                    List<ComputedMetric> readMetrics) {
        return new RuleOutcome(ruleCode, DiagnosisFindingView.Outcome.CLEAR, null, null,
                detail, readMetrics);
    }

    /** The rule could not answer, and says why. */
    public static RuleOutcome declined(String ruleCode,
                                       String declineReason,
                                       Map<String, String> detail) {
        return new RuleOutcome(ruleCode, DiagnosisFindingView.Outcome.DECLINED, null,
                declineReason, detail, List.of());
    }

    /**
     * The digest that makes this finding reproducible.
     *
     * <p>It covers the rule, the subject, the window and the digests of the
     * metrics the rule read, so re-evaluating unchanged metrics lands on the
     * existing row and re-evaluating after a recomputation writes a new one.
     */
    public String inputDigest(int ruleVersion,
                              String subjectKind,
                              UUID subjectId,
                              String windowCode,
                              List<String> readMetricDigests) {
        List<String> components = new ArrayList<>();
        components.add(ruleCode);
        components.add(Integer.toString(ruleVersion));
        components.add(subjectKind);
        components.add(subjectId.toString());
        components.add(windowCode);
        components.add(outcome.name());
        components.add(severity == null ? null : severity.name());
        components.add(declineReason);
        readMetricDigests.stream().sorted().forEach(components::add);
        return Digest.ofComponents(components);
    }

    /** The metric codes this rule read, in the order it read them. */
    public List<MetricCode> readMetricCodes() {
        return readMetrics.stream().map(ComputedMetric::metricCode).toList();
    }
}
