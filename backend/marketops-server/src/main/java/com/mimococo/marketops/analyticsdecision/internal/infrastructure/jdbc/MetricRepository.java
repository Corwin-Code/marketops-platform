package com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Append-only writing and current-value reading of canonical metrics.
 *
 * <p>Insertion is idempotent on the reproducibility key: identical inputs land
 * on the row that already exists, so a recomputation that changed nothing writes
 * nothing. A recomputation after late data has a different input digest and
 * therefore writes a new row beside the old one, which is how a corrected figure
 * appears without erasing the one somebody acted on.
 *
 * <p>Current means most recently computed. The read below orders by computation
 * time rather than by period, because two values for the same period differ
 * precisely when the second one knows something the first did not.
 */
@Repository
public class MetricRepository {

    private final JdbcClient jdbc;

    MetricRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Open a calculation run. */
    public void openRun(UUID id, UUID organizationId, String triggerKind, String scopeKind,
                        UUID storeId, String windowCode, Instant periodStart, Instant periodEnd,
                        String definitionSetDigest, UUID requestedByUserId, Instant startedAt,
                        String correlationId) {
        jdbc.sql("""
                        INSERT INTO mart.calculation_run (
                            id, organization_id, trigger_kind, scope_kind, store_ref_id,
                            window_code, period_start, period_end, definition_set_digest,
                            state, requested_by_user_id, started_at, correlation_id)
                        VALUES (:id, :organizationId, :triggerKind, :scopeKind, :storeId,
                            :windowCode, :periodStart, :periodEnd, :definitionSetDigest,
                            'RUNNING', :requestedByUserId, :startedAt, :correlationId)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("triggerKind", triggerKind)
                .param("scopeKind", scopeKind)
                .param("storeId", storeId)
                .param("windowCode", windowCode)
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("definitionSetDigest", definitionSetDigest)
                .param("requestedByUserId", requestedByUserId)
                .param("startedAt", Timestamp.from(startedAt))
                .param("correlationId", correlationId)
                .update();
    }

    /** Close a calculation run. */
    public void closeRun(UUID id, String state, int subjectCount, int valueCount,
                         String failureCode, Instant completedAt) {
        jdbc.sql("""
                        UPDATE mart.calculation_run
                        SET state = :state, subject_count = :subjectCount,
                            value_count = :valueCount, failure_code = :failureCode,
                            completed_at = :completedAt
                        WHERE id = :id AND state = 'RUNNING'
                        """)
                .param("state", state)
                .param("subjectCount", subjectCount)
                .param("valueCount", valueCount)
                .param("failureCode", failureCode)
                .param("completedAt", Timestamp.from(completedAt))
                .param("id", id)
                .update();
    }

    /**
     * Store one computed value, or leave the identical existing one alone.
     *
     * <p>The identifier is read back rather than assumed, because the insert may
     * have been absorbed by a row an earlier run produced from the same inputs
     * and the caller needs the identifier that actually exists.
     */
    public UUID recordValue(UUID id, UUID organizationId, UUID calculationRunId,
                            MetricCode metricCode, int definitionVersion,
                            SubjectKind subjectKind, UUID subjectId, MetricWindow window,
                            Instant periodStart, Instant periodEnd, ValueState valueState,
                            BigDecimal numericValue, String currencyCode,
                            ConfidenceState confidenceState, boolean estimated,
                            Instant oldestSourceTime, Long freshnessSeconds,
                            String inputDigest, Instant computedAt) {
        jdbc.sql("""
                        INSERT INTO mart.metric_value (
                            id, organization_id, calculation_run_id, metric_code,
                            definition_version, subject_kind, subject_id, window_code,
                            period_start, period_end, value_state, numeric_value,
                            currency_code, confidence_state, estimated, oldest_source_time,
                            freshness_seconds, input_digest, computed_at)
                        VALUES (:id, :organizationId, :calculationRunId, :metricCode,
                            :definitionVersion, :subjectKind, :subjectId, :windowCode,
                            :periodStart, :periodEnd, :valueState, :numericValue,
                            :currencyCode, :confidenceState, :estimated, :oldestSourceTime,
                            :freshnessSeconds, :inputDigest, :computedAt)
                        ON CONFLICT (metric_code, definition_version, subject_kind, subject_id,
                                     window_code, period_start, period_end, input_digest)
                        DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("calculationRunId", calculationRunId)
                .param("metricCode", metricCode.name())
                .param("definitionVersion", definitionVersion)
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("windowCode", window.name())
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("valueState", valueState.name())
                .param("numericValue", numericValue)
                .param("currencyCode", currencyCode)
                .param("confidenceState", confidenceState.name())
                .param("estimated", estimated)
                .param("oldestSourceTime",
                        oldestSourceTime == null ? null : Timestamp.from(oldestSourceTime))
                .param("freshnessSeconds", freshnessSeconds)
                .param("inputDigest", inputDigest)
                .param("computedAt", Timestamp.from(computedAt))
                .update();

        return jdbc.sql("""
                        SELECT id FROM mart.metric_value
                         WHERE metric_code = :metricCode
                           AND definition_version = :definitionVersion
                           AND subject_kind = :subjectKind AND subject_id = :subjectId
                           AND window_code = :windowCode
                           AND period_start = :periodStart AND period_end = :periodEnd
                           AND input_digest = :inputDigest
                        """)
                .param("metricCode", metricCode.name())
                .param("definitionVersion", definitionVersion)
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("windowCode", window.name())
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("inputDigest", inputDigest)
                .query(UUID.class)
                .single();
    }

    /** Record one thing a value was derived from. */
    public void recordInput(UUID id, UUID metricValueId, String referenceKind,
                            UUID referenceId) {
        jdbc.sql("""
                        INSERT INTO mart.metric_input_reference (
                            id, metric_value_id, reference_kind, reference_id)
                        VALUES (:id, :metricValueId, :referenceKind, :referenceId)
                        ON CONFLICT (metric_value_id, reference_kind, reference_id) DO NOTHING
                        """)
                .param("id", id)
                .param("metricValueId", metricValueId)
                .param("referenceKind", referenceKind)
                .param("referenceId", referenceId)
                .update();
    }

    /** The current value of one metric for one subject and window. */
    public Optional<MetricValueView> currentValue(MetricCode metricCode,
                                                  SubjectKind subjectKind,
                                                  UUID subjectId,
                                                  MetricWindow window) {
        return jdbc.sql(currentValueSql() + " AND value.metric_code = :metricCode"
                        + " ORDER BY value.computed_at DESC, value.id DESC LIMIT 1")
                .param("metricCode", metricCode.name())
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("windowCode", window.name())
                .query(MetricRepository::mapValue)
                .optional();
    }

    /**
     * The current value of every metric for one subject and window.
     *
     * <p>One query rather than one per metric: a subject page reads two dozen
     * metrics, and a round trip each would make the page's latency a function of
     * how many metrics the product happens to define.
     */
    public Map<MetricCode, MetricValueView> currentValues(SubjectKind subjectKind,
                                                          UUID subjectId,
                                                          MetricWindow window) {
        List<MetricValueView> latest = jdbc.sql("""
                        SELECT DISTINCT ON (value.metric_code)
                               value.id, value.metric_code, value.definition_version,
                               value.subject_kind, value.subject_id, value.window_code,
                               value.period_start, value.period_end, value.value_state,
                               value.numeric_value, value.currency_code,
                               value.confidence_state, value.estimated,
                               value.oldest_source_time, value.freshness_seconds,
                               value.input_digest, value.computed_at
                          FROM mart.metric_value AS value
                         WHERE value.subject_kind = :subjectKind
                           AND value.subject_id = :subjectId
                           AND value.window_code = :windowCode
                         ORDER BY value.metric_code, value.computed_at DESC, value.id DESC
                        """)
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("windowCode", window.name())
                .query(MetricRepository::mapValue)
                .list();

        Map<MetricCode, MetricValueView> values = new EnumMap<>(MetricCode.class);
        latest.forEach(value -> values.put(value.metricCode(), value));
        return Map.copyOf(values);
    }

    /** Every stored value of one metric for one subject, newest first. */
    public List<MetricValueView> history(MetricCode metricCode, SubjectKind subjectKind,
                                         UUID subjectId, MetricWindow window, int limit) {
        return jdbc.sql(currentValueSql() + " AND value.metric_code = :metricCode"
                        + " ORDER BY value.computed_at DESC, value.id DESC LIMIT :pageLimit")
                .param("metricCode", metricCode.name())
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("windowCode", window.name())
                .param("pageLimit", limit)
                .query(MetricRepository::mapValue)
                .list();
    }

    /** The provenance a stored value cites. */
    public List<UUID> inputsOf(UUID metricValueId) {
        return jdbc.sql("""
                        SELECT reference_id FROM mart.metric_input_reference
                         WHERE metric_value_id = :metricValueId
                         ORDER BY reference_kind, reference_id
                        """)
                .param("metricValueId", metricValueId)
                .query(UUID.class)
                .list();
    }

    /** Whether a stored metric value exists, used to reject an invented citation. */
    public boolean valueExists(UUID metricValueId) {
        return Boolean.TRUE.equals(jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM mart.metric_value WHERE id = :id)")
                .param("id", metricValueId)
                .query(Boolean.class)
                .single());
    }

    private static String currentValueSql() {
        return """
                SELECT value.id, value.metric_code, value.definition_version,
                       value.subject_kind, value.subject_id, value.window_code,
                       value.period_start, value.period_end, value.value_state,
                       value.numeric_value, value.currency_code, value.confidence_state,
                       value.estimated, value.oldest_source_time, value.freshness_seconds,
                       value.input_digest, value.computed_at
                  FROM mart.metric_value AS value
                 WHERE value.subject_kind = :subjectKind
                   AND value.subject_id = :subjectId
                   AND value.window_code = :windowCode
                """;
    }

    private static MetricValueView mapValue(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp oldestSource = rows.getTimestamp("oldest_source_time");
        long freshness = rows.getLong("freshness_seconds");
        Long freshnessSeconds = rows.wasNull() ? null : freshness;
        return new MetricValueView(
                rows.getObject("id", UUID.class),
                MetricCode.valueOf(rows.getString("metric_code")),
                rows.getInt("definition_version"),
                SubjectKind.valueOf(rows.getString("subject_kind")),
                rows.getObject("subject_id", UUID.class),
                MetricWindow.valueOf(rows.getString("window_code")),
                rows.getTimestamp("period_start").toInstant(),
                rows.getTimestamp("period_end").toInstant(),
                ValueState.valueOf(rows.getString("value_state")),
                rows.getBigDecimal("numeric_value"),
                rows.getString("currency_code"),
                ConfidenceState.valueOf(rows.getString("confidence_state")),
                rows.getBoolean("estimated"),
                oldestSource == null ? null : oldestSource.toInstant(),
                freshnessSeconds,
                rows.getString("input_digest"),
                rows.getTimestamp("computed_at").toInstant(),
                new ArrayList<>());
    }
}
