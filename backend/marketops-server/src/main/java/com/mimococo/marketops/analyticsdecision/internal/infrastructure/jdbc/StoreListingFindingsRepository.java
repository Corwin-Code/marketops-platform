package com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What the newest completed calculation run said about a store's listings: the
 * findings it triggered and selected metric values, for the store diagnosis.
 *
 * <p>Values and findings are stored once per distinct input, so a run that
 * re-evaluates an unchanged listing writes nothing new: a run's values are
 * found through the evaluations it recorded, and its findings as the newest
 * finding of every rule over the run's period for the listings it evaluated.
 */
@Repository
public class StoreListingFindingsRepository {

    private final JdbcClient jdbc;

    StoreListingFindingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The newest run of the store over the window that completed. */
    public Optional<RunRow> latestRun(UUID organizationId, UUID storeId, String windowCode) {
        return jdbc.sql("""
                        SELECT id, period_start, period_end, completed_at, subject_count
                          FROM mart.calculation_run
                         WHERE organization_id = :organizationId AND scope_kind = 'STORE'
                           AND store_ref_id = :storeId AND window_code = :windowCode
                           AND state = 'SUCCEEDED'
                         ORDER BY completed_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("windowCode", windowCode)
                .query((rows, rowNumber) -> new RunRow(
                        rows.getObject("id", UUID.class),
                        rows.getTimestamp("period_start").toInstant(),
                        rows.getTimestamp("period_end").toInstant(),
                        instant(rows.getTimestamp("completed_at")),
                        (Integer) rows.getObject("subject_count")))
                .optional();
    }

    /** Where the store's newest completed run over the window ended, whoever started it. */
    public Optional<Instant> latestPeriodEnd(UUID storeId, String windowCode) {
        Timestamp end = jdbc.sql("""
                        SELECT max(period_end)
                          FROM mart.calculation_run
                         WHERE scope_kind = 'STORE' AND store_ref_id = :storeId
                           AND window_code = :windowCode AND state = 'SUCCEEDED'
                        """)
                .param("storeId", storeId)
                .param("windowCode", windowCode)
                .query(Timestamp.class)
                .optional()
                .orElse(null);
        return Optional.ofNullable(end).map(Timestamp::toInstant);
    }

    /** Every finding that stands after the run and triggered, in rule order. */
    public List<FindingRow> triggeredFindings(RunRow run, String windowCode) {
        return jdbc.sql("""
                        SELECT current.id, current.subject_id, current.rule_code, current.severity,
                               current.detail
                          FROM (SELECT DISTINCT ON (finding.subject_id, finding.rule_code)
                                       finding.id, finding.subject_id, finding.rule_code,
                                       finding.outcome, finding.severity,
                                       finding.detail::text AS detail, rule.ordinal
                                  FROM mart.diagnosis_finding AS finding
                                  JOIN mart.diagnosis_rule AS rule
                                    ON rule.rule_code = finding.rule_code
                                   AND rule.rule_version = finding.rule_version
                                 WHERE finding.subject_kind = 'PLATFORM_LISTING_VARIANT'
                                   AND finding.window_code = :windowCode
                                   AND finding.period_start = :periodStart
                                   AND finding.period_end = :periodEnd
                                   AND finding.subject_id IN (
                                       SELECT value.subject_id
                                         FROM mart.metric_value_evaluation AS evaluation
                                         JOIN mart.metric_value AS value
                                           ON value.id = evaluation.metric_value_id
                                        WHERE evaluation.calculation_run_id = :runId
                                          AND value.subject_kind = 'PLATFORM_LISTING_VARIANT')
                                 ORDER BY finding.subject_id, finding.rule_code,
                                          finding.evaluated_at DESC, finding.id DESC) AS current
                         WHERE current.outcome = 'TRIGGERED'
                         ORDER BY current.subject_id, current.ordinal
                        """)
                .param("runId", run.id())
                .param("windowCode", windowCode)
                .param("periodStart", Timestamp.from(run.periodStart()))
                .param("periodEnd", Timestamp.from(run.periodEnd()))
                .query((rows, rowNumber) -> new FindingRow(
                        rows.getObject("id", UUID.class),
                        rows.getObject("subject_id", UUID.class),
                        rows.getString("rule_code"),
                        rows.getString("severity"),
                        rows.getString("detail")))
                .list();
    }

    /** The values of the named metrics the run evaluated for every listing, new or unchanged. */
    public List<MetricRow> metricValues(UUID runId, Collection<String> metricCodes) {
        return jdbc.sql("""
                        SELECT value.id, value.subject_id, value.metric_code, value.value_state,
                               value.numeric_value, value.currency_code, value.confidence_state
                          FROM mart.metric_value_evaluation AS evaluation
                          JOIN mart.metric_value AS value ON value.id = evaluation.metric_value_id
                         WHERE evaluation.calculation_run_id = :runId
                           AND value.subject_kind = 'PLATFORM_LISTING_VARIANT'
                           AND value.metric_code IN (:metricCodes)
                        """)
                .param("runId", runId)
                .param("metricCodes", metricCodes)
                .query((rows, rowNumber) -> new MetricRow(
                        rows.getObject("id", UUID.class),
                        rows.getObject("subject_id", UUID.class),
                        rows.getString("metric_code"),
                        rows.getString("value_state"),
                        rows.getBigDecimal("numeric_value"),
                        rows.getString("currency_code"),
                        rows.getString("confidence_state")))
                .list();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** One completed run. */
    public record RunRow(UUID id, Instant periodStart, Instant periodEnd, Instant completedAt,
                         Integer subjectCount) {
    }

    /** One triggered finding; {@code detail} is the stored JSON object. */
    public record FindingRow(UUID id, UUID subjectId, String ruleCode, String severity, String detail) {
    }

    /** One metric value. */
    public record MetricRow(UUID valueId, UUID subjectId, String metricCode, String valueState,
                            BigDecimal numericValue, String currencyCode, String confidenceState) {
    }
}
