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

    /** Every finding the run triggered, in rule order. */
    public List<FindingRow> triggeredFindings(UUID runId) {
        return jdbc.sql("""
                        SELECT finding.id, finding.subject_id, finding.rule_code, finding.severity,
                               finding.detail::text AS detail
                          FROM mart.diagnosis_finding AS finding
                          JOIN mart.diagnosis_rule AS rule
                            ON rule.rule_code = finding.rule_code AND rule.rule_version = finding.rule_version
                         WHERE finding.calculation_run_id = :runId
                           AND finding.subject_kind = 'PLATFORM_LISTING_VARIANT'
                           AND finding.outcome = 'TRIGGERED'
                         ORDER BY finding.subject_id, rule.ordinal
                        """)
                .param("runId", runId)
                .query((rows, rowNumber) -> new FindingRow(
                        rows.getObject("id", UUID.class),
                        rows.getObject("subject_id", UUID.class),
                        rows.getString("rule_code"),
                        rows.getString("severity"),
                        rows.getString("detail")))
                .list();
    }

    /** The run's values of the named metrics for every listing it computed. */
    public List<MetricRow> metricValues(UUID runId, Collection<String> metricCodes) {
        return jdbc.sql("""
                        SELECT subject_id, metric_code, value_state, numeric_value, currency_code,
                               confidence_state
                          FROM mart.metric_value
                         WHERE calculation_run_id = :runId
                           AND subject_kind = 'PLATFORM_LISTING_VARIANT'
                           AND metric_code IN (:metricCodes)
                        """)
                .param("runId", runId)
                .param("metricCodes", metricCodes)
                .query((rows, rowNumber) -> new MetricRow(
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
    public record MetricRow(UUID subjectId, String metricCode, String valueState, BigDecimal numericValue,
                            String currencyCode, String confidenceState) {
    }
}
