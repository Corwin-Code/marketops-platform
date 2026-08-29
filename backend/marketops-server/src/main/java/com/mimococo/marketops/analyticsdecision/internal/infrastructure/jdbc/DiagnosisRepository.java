package com.mimococo.marketops.analyticsdecision.internal.infrastructure.jdbc;

import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only writing and current reading of deterministic findings.
 *
 * <p>Findings are keyed by the digest of the metrics the rule read, so
 * re-evaluating unchanged metrics writes nothing and re-evaluating after a
 * recomputation writes a new finding beside the old one. An operator can
 * therefore see that a rule stopped triggering because the facts changed rather
 * than because somebody edited a threshold.
 */
@Repository
public class DiagnosisRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    DiagnosisRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Store one finding, or leave the identical existing one alone. */
    public UUID recordFinding(UUID id, UUID organizationId, UUID calculationRunId,
                              String ruleCode, int ruleVersion, SubjectKind subjectKind,
                              UUID subjectId, MetricWindow window, Instant periodStart,
                              Instant periodEnd, DiagnosisFindingView.Outcome outcome,
                              DiagnosisFindingView.Severity severity, String declineReason,
                              Map<String, String> detail, String inputDigest,
                              Instant evaluatedAt) {
        jdbc.sql("""
                        INSERT INTO mart.diagnosis_finding (
                            id, organization_id, calculation_run_id, rule_code, rule_version,
                            subject_kind, subject_id, window_code, period_start, period_end,
                            outcome, severity, decline_reason, detail, input_digest,
                            evaluated_at)
                        VALUES (:id, :organizationId, :calculationRunId, :ruleCode,
                            :ruleVersion, :subjectKind, :subjectId, :windowCode, :periodStart,
                            :periodEnd, :outcome, :severity, :declineReason,
                            CAST(:detail AS jsonb), :inputDigest, :evaluatedAt)
                        ON CONFLICT (rule_code, rule_version, subject_kind, subject_id,
                                     window_code, period_start, period_end, input_digest)
                        DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("calculationRunId", calculationRunId)
                .param("ruleCode", ruleCode)
                .param("ruleVersion", ruleVersion)
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("windowCode", window.name())
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("outcome", outcome.name())
                .param("severity", severity == null ? null : severity.name())
                .param("declineReason", declineReason)
                .param("detail", objectMapper.writeValueAsString(detail))
                .param("inputDigest", inputDigest)
                .param("evaluatedAt", Timestamp.from(evaluatedAt))
                .update();

        return jdbc.sql("""
                        SELECT id FROM mart.diagnosis_finding
                         WHERE rule_code = :ruleCode AND rule_version = :ruleVersion
                           AND subject_kind = :subjectKind AND subject_id = :subjectId
                           AND window_code = :windowCode
                           AND period_start = :periodStart AND period_end = :periodEnd
                           AND input_digest = :inputDigest
                        """)
                .param("ruleCode", ruleCode)
                .param("ruleVersion", ruleVersion)
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("windowCode", window.name())
                .param("periodStart", Timestamp.from(periodStart))
                .param("periodEnd", Timestamp.from(periodEnd))
                .param("inputDigest", inputDigest)
                .query(UUID.class)
                .single();
    }

    /** Record one canonical value a finding read. */
    public void recordFindingInput(UUID id, UUID findingId, UUID metricValueId, String role) {
        jdbc.sql("""
                        INSERT INTO mart.diagnosis_finding_input (
                            id, finding_id, metric_value_id, role)
                        VALUES (:id, :findingId, :metricValueId, :role)
                        ON CONFLICT (finding_id, metric_value_id) DO NOTHING
                        """)
                .param("id", id)
                .param("findingId", findingId)
                .param("metricValueId", metricValueId)
                .param("role", role)
                .update();
    }

    /** The current findings for one subject and window, in rule order. */
    public List<DiagnosisFindingView> currentFindings(SubjectKind subjectKind,
                                                      UUID subjectId,
                                                      MetricWindow window) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (rule.ordinal)
                               finding.id, finding.rule_code, finding.rule_version,
                               finding.subject_kind, finding.subject_id, finding.window_code,
                               finding.outcome, finding.severity, finding.decline_reason,
                               CAST(finding.detail AS text) AS detail, finding.evaluated_at,
                               (finding.outcome = 'TRIGGERED' AND rule.blocks_execution)
                                   AS blocks_execution,
                               rule.ordinal
                          FROM mart.diagnosis_finding AS finding
                          JOIN mart.diagnosis_rule AS rule
                            ON rule.rule_code = finding.rule_code
                           AND rule.rule_version = finding.rule_version
                         WHERE finding.subject_kind = :subjectKind
                           AND finding.subject_id = :subjectId
                           AND finding.window_code = :windowCode
                         ORDER BY rule.ordinal, finding.evaluated_at DESC, finding.id DESC
                        """)
                .param("subjectKind", subjectKind.name())
                .param("subjectId", subjectId)
                .param("windowCode", window.name())
                .query(this::mapFinding)
                .list();
    }

    /**
     * The subjects a store should look at first.
     *
     * <p>The score is computed here so the ordering is one relational operation
     * rather than a page of findings fetched and sorted in memory. Critical
     * findings dominate, warnings contribute, and declined rules count too:
     * a subject nobody can diagnose is a subject worth looking at.
     */
    public List<PriorityRow> priorityQueue(UUID storeId, MetricWindow window, int limit) {
        return jdbc.sql("""
                        WITH latest AS (
                            SELECT DISTINCT ON (finding.subject_id, finding.rule_code)
                                   finding.subject_id, finding.rule_code, finding.outcome,
                                   finding.severity, rule.blocks_execution
                              FROM mart.diagnosis_finding AS finding
                              JOIN mart.diagnosis_rule AS rule
                                ON rule.rule_code = finding.rule_code
                               AND rule.rule_version = finding.rule_version
                              JOIN core.platform_listing_variant AS variant
                                ON variant.id = finding.subject_id
                              JOIN core.platform_listing AS listing
                                ON listing.id = variant.platform_listing_id
                             WHERE listing.store_id = :storeId
                               AND finding.window_code = :windowCode
                               AND finding.subject_kind = 'PLATFORM_LISTING_VARIANT'
                             ORDER BY finding.subject_id, finding.rule_code,
                                      finding.evaluated_at DESC, finding.id DESC
                        )
                        SELECT latest.subject_id,
                               count(*) FILTER (
                                   WHERE latest.outcome = 'TRIGGERED'
                                     AND latest.severity = 'CRITICAL') AS critical_count,
                               count(*) FILTER (
                                   WHERE latest.outcome = 'TRIGGERED'
                                     AND latest.severity = 'WARNING') AS warning_count,
                               count(*) FILTER (WHERE latest.outcome = 'DECLINED')
                                   AS declined_count,
                               array_remove(array_agg(
                                   CASE WHEN latest.outcome = 'TRIGGERED'
                                             AND latest.blocks_execution
                                        THEN latest.rule_code END), NULL) AS blocking_rules
                          FROM latest
                         GROUP BY latest.subject_id
                         ORDER BY critical_count DESC, warning_count DESC,
                                  declined_count DESC, latest.subject_id
                         LIMIT :pageLimit
                        """)
                .param("storeId", storeId)
                .param("windowCode", window.name())
                .param("pageLimit", limit)
                .query((rows, rowNumber) -> new PriorityRow(
                        rows.getObject("subject_id", UUID.class),
                        rows.getInt("critical_count"),
                        rows.getInt("warning_count"),
                        rows.getInt("declined_count"),
                        textArray(rows, "blocking_rules")))
                .list();
    }

    /** Whether a stored finding exists, used to reject an invented citation. */
    public boolean findingExists(UUID findingId) {
        return Boolean.TRUE.equals(jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM mart.diagnosis_finding WHERE id = :id)")
                .param("id", findingId)
                .query(Boolean.class)
                .single());
    }

    /** The canonical values one finding read. */
    public List<UUID> findingInputs(UUID findingId) {
        return jdbc.sql("""
                        SELECT metric_value_id FROM mart.diagnosis_finding_input
                         WHERE finding_id = :findingId ORDER BY metric_value_id
                        """)
                .param("findingId", findingId)
                .query(UUID.class)
                .list();
    }

    private DiagnosisFindingView mapFinding(ResultSet rows, int rowNumber) throws SQLException {
        String severity = rows.getString("severity");
        Map<String, String> detail = new LinkedHashMap<>();
        objectMapper.readTree(rows.getString("detail")).properties()
                .forEach(entry -> detail.put(entry.getKey(), entry.getValue().asString()));
        return new DiagnosisFindingView(
                rows.getObject("id", UUID.class),
                rows.getString("rule_code"),
                rows.getInt("rule_version"),
                SubjectKind.valueOf(rows.getString("subject_kind")),
                rows.getObject("subject_id", UUID.class),
                MetricWindow.valueOf(rows.getString("window_code")),
                DiagnosisFindingView.Outcome.valueOf(rows.getString("outcome")),
                severity == null ? null : DiagnosisFindingView.Severity.valueOf(severity),
                rows.getString("decline_reason"),
                Map.copyOf(detail),
                rows.getBoolean("blocks_execution"),
                rows.getTimestamp("evaluated_at").toInstant(),
                new ArrayList<>());
    }

    private static List<String> textArray(ResultSet rows, String column) throws SQLException {
        java.sql.Array array = rows.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] elements = (Object[]) array.getArray();
        return java.util.Arrays.stream(elements)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * One subject's finding counts, before money is attached.
     *
     * @param subjectId the listing variant
     * @param criticalCount how many rules triggered at critical severity
     * @param warningCount how many triggered at warning severity
     * @param declinedCount how many rules could not answer
     * @param blockingRuleCodes triggered rules that block a platform write
     */
    public record PriorityRow(
            UUID subjectId, int criticalCount, int warningCount, int declinedCount,
            List<String> blockingRuleCodes) {
    }
}
