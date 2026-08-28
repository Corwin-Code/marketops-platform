package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.DiagnosisQuery;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricQuery;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.PrioritySubjectView;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.DiagnosisRepository;
import com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc.MetricRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Published reading of canonical metrics, findings and the daily work list.
 *
 * <p>Evidence references are attached on read rather than stored twice. A value
 * and the provenance it cites are separate rows, and joining them here keeps the
 * evidence panel showing what the value actually used instead of a copy that can
 * drift from it.
 *
 * <p>The priority score is derived here from the finding counts and the money
 * the subject moved, so the ordering is explainable from data an operator can
 * see. A queue whose order nobody can account for is a queue people stop
 * trusting, and then stop using.
 */
@Service
public class AnalyticsQueryService implements MetricQuery, DiagnosisQuery {

    /** What one critical finding contributes to a subject's score. */
    private static final BigDecimal CRITICAL_WEIGHT = new BigDecimal("100");

    /** What one warning finding contributes. */
    private static final BigDecimal WARNING_WEIGHT = new BigDecimal("25");

    /** What one rule that could not answer contributes. */
    private static final BigDecimal DECLINED_WEIGHT = new BigDecimal("10");

    /** The largest score the money term may add, keeping findings dominant. */
    private static final BigDecimal MONEY_WEIGHT_CEILING = new BigDecimal("50");

    private final MetricRepository metrics;
    private final DiagnosisRepository diagnoses;

    AnalyticsQueryService(MetricRepository metrics, DiagnosisRepository diagnoses) {
        this.metrics = metrics;
        this.diagnoses = diagnoses;
    }

    /** Explicitly bounded evidence edges for an authorized subject and exact value version. */
    @Transactional(readOnly = true, timeout = 5)
    public InputPage inputs(UUID subjectId, UUID metricValueId) {
        if (!metrics.valueBelongsTo(metricValueId, subjectId)) {
            throw com.mimococo.marketops.shared.OperationRejectedException.of(
                    com.mimococo.marketops.shared.ErrorCode.RESOURCE_NOT_FOUND);
        }
        var rows = metrics.typedInputsOf(metricValueId);
        return new InputPage(metricValueId, rows.stream().limit(200).toList(), rows.size() > 200);
    }

    /** Truncation is visible; complete large graphs use the asynchronous export. */
    public record InputPage(UUID metricValueId, List<MetricRepository.InputReference> references,
                            boolean truncated) { }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public Optional<MetricValueView> current(MetricCode metricCode, SubjectKind subjectKind,
                                             UUID subjectId, MetricWindow window) {
        return metrics.currentValue(metricCode, subjectKind, subjectId, window)
                .map(this::withEvidence);
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public Map<MetricCode, MetricValueView> currentValues(SubjectKind subjectKind,
                                                          UUID subjectId,
                                                          MetricWindow window) {
        Map<MetricCode, MetricValueView> values =
                metrics.currentValues(subjectKind, subjectId, window);
        Map<MetricCode, MetricValueView> withEvidence =
                new java.util.EnumMap<>(MetricCode.class);
        values.forEach((code, value) -> withEvidence.put(code, withEvidence(value)));
        return Map.copyOf(withEvidence);
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public List<MetricValueView> history(MetricCode metricCode, SubjectKind subjectKind,
                                         UUID subjectId, MetricWindow window, int limit) {
        return metrics.history(metricCode, subjectKind, subjectId, window,
                        Math.clamp(limit, 1, 200))
                .stream()
                .map(this::withEvidence)
                .toList();
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public List<DiagnosisFindingView> currentFindings(SubjectKind subjectKind, UUID subjectId,
                                                      MetricWindow window) {
        return diagnoses.currentFindings(subjectKind, subjectId, window).stream()
                .map(this::withInputs)
                .toList();
    }

    @Override
    @Transactional(readOnly = true, timeout = 5)
    public List<PrioritySubjectView> priorityQueue(UUID storeId, MetricWindow window,
                                                   int limit) {
        List<PrioritySubjectView> queue = new ArrayList<>();
        for (DiagnosisRepository.PriorityRow row
                : diagnoses.priorityQueue(storeId, window, Math.clamp(limit, 1, 500))) {
            Optional<MetricValueView> netSales = metrics.currentValue(
                    MetricCode.COMPLETED_NET_SALES, SubjectKind.PLATFORM_LISTING_VARIANT,
                    row.subjectId(), window);
            Optional<MetricValueView> profit = metrics.currentValue(
                    MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                    SubjectKind.PLATFORM_LISTING_VARIANT, row.subjectId(), window);
            queue.add(new PrioritySubjectView(
                    SubjectKind.PLATFORM_LISTING_VARIANT,
                    row.subjectId(),
                    storeId,
                    score(row, netSales.orElse(null)),
                    row.criticalCount(),
                    row.warningCount(),
                    row.declinedCount(),
                    netSales.filter(MetricValueView::available)
                            .map(MetricValueView::numericValue).orElse(null),
                    profit.filter(MetricValueView::available)
                            .map(MetricValueView::numericValue).orElse(null),
                    netSales.map(MetricValueView::currencyCode).orElse(null),
                    row.blockingRuleCodes()));
        }
        return List.copyOf(queue);
    }

    /**
     * How far up the list a subject belongs.
     *
     * <p>Findings dominate and money breaks ties. The money term is capped so a
     * high-turnover listing with nothing wrong cannot outrank a small one that
     * is losing money on every sale, which is the ordering an operator expects
     * and the one the product is for.
     */
    private static BigDecimal score(DiagnosisRepository.PriorityRow row,
                                    MetricValueView netSales) {
        BigDecimal findingScore = CRITICAL_WEIGHT.multiply(BigDecimal.valueOf(row.criticalCount()))
                .add(WARNING_WEIGHT.multiply(BigDecimal.valueOf(row.warningCount())))
                .add(DECLINED_WEIGHT.multiply(BigDecimal.valueOf(row.declinedCount())));
        if (netSales == null || !netSales.available()
                || netSales.numericValue().signum() <= 0) {
            return findingScore;
        }
        // A logarithm keeps a listing that sold a hundred times more from
        // scoring a hundred times higher; the ceiling keeps the money term from
        // ever overtaking a critical finding.
        double magnitude = Math.log10(netSales.numericValue().doubleValue() + 1.0);
        BigDecimal moneyScore = BigDecimal.valueOf(magnitude)
                .setScale(4, java.math.RoundingMode.HALF_UP);
        return findingScore.add(moneyScore.min(MONEY_WEIGHT_CEILING));
    }

    private MetricValueView withEvidence(MetricValueView value) {
        return new MetricValueView(value.metricValueId(), value.metricCode(),
                value.definitionVersion(), value.subjectKind(), value.subjectId(),
                value.window(), value.periodStart(), value.periodEnd(), value.valueState(),
                value.numericValue(), value.currencyCode(), value.confidenceState(),
                value.estimated(), value.oldestSourceTime(), value.freshnessSeconds(),
                value.inputDigest(), value.computedAt(),
                metrics.inputsOf(value.metricValueId()));
    }

    private DiagnosisFindingView withInputs(DiagnosisFindingView finding) {
        return new DiagnosisFindingView(finding.findingId(), finding.ruleCode(),
                finding.ruleVersion(), finding.subjectKind(), finding.subjectId(),
                finding.window(), finding.outcome(), finding.severity(),
                finding.declineReason(), finding.detail(), finding.blocksExecution(),
                finding.evaluatedAt(), diagnoses.findingInputs(finding.findingId()));
    }
}
