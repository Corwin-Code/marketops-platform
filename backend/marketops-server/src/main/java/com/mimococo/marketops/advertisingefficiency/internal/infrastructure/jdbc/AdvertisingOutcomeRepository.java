package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The frozen plan, the windows it fixes, and what was written about them.
 *
 * <p>Everything read here is read as of the command rather than as of now. The
 * plan comes from the bundle the command was created under, the window is
 * measured from the moment the write was proven to have landed, and the guard
 * is asked of the database rather than computed here — because the database is
 * what will refuse a settled claim that outruns it.
 */
@Repository
public class AdvertisingOutcomeRepository {

    private final JdbcClient jdbc;

    AdvertisingOutcomeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Commands whose next outcome stage is due.
     *
     * <p>Three disjoint reasons a command appears: its operational window has
     * closed and nothing operational has been written; its settlement window has
     * closed and nothing settled has been written; or a settled view exists and
     * the facts underneath it have been restated since. The third is the late
     * adjustment, and it is why this is a query rather than a queue.
     */
    public List<DueRow> due(Instant now, int limit) {
        return jdbc.sql("""
                WITH landed AS (
                    SELECT rb.command_id, min(rb.observed_at) AS landed_at
                      FROM ops.ad_bid_command_readback rb
                     WHERE rb.match_state = 'MATCHES_TARGET'
                     GROUP BY rb.command_id)
                SELECT c.id                       AS command_id,
                       c.organization_id,
                       c.store_id,
                       c.platform_code,
                       c.ad_native_object_id,
                       c.affected_set_digest,
                       c.direction,
                       landed.landed_at,
                       plan.id                    AS policy_id,
                       plan.policy_version,
                       plan.primary_metric_code,
                       plan.comparison_basis,
                       plan.observation_starts_minutes,
                       plan.operational_window_hours,
                       plan.settlement_window_hours,
                       plan.improvement_threshold_ratio,
                       plan.regression_threshold_ratio,
                       plan.minimum_traffic_count,
                       plan.minimum_settled_coverage_ratio,
                       kase.cause_code,
                       CASE
                           WHEN NOT EXISTS (SELECT 1 FROM ops.ad_outcome_observation o
                                             WHERE o.command_id = c.id
                                               AND o.outcome_stage = 'OPERATIONAL')
                               THEN 'OPERATIONAL'
                           WHEN NOT EXISTS (SELECT 1 FROM ops.ad_outcome_observation o
                                             WHERE o.command_id = c.id
                                               AND o.outcome_stage IN ('SETTLED',
                                                                       'SETTLED_REVISED'))
                               THEN 'SETTLED'
                           ELSE 'SETTLED_REVISED' END AS next_stage,
                       (SELECT o.id FROM ops.ad_outcome_observation o
                         WHERE o.command_id = c.id
                           AND o.outcome_stage IN ('SETTLED', 'SETTLED_REVISED')
                         ORDER BY o.revision_no DESC LIMIT 1) AS latest_settled_id,
                       (SELECT o.revision_no FROM ops.ad_outcome_observation o
                         WHERE o.command_id = c.id
                           AND o.outcome_stage IN ('SETTLED', 'SETTLED_REVISED')
                         ORDER BY o.revision_no DESC LIMIT 1) AS latest_settled_revision
                  FROM ops.ad_bid_command c
                  JOIN landed ON landed.command_id = c.id
                  JOIN ops.ad_decision_policy_bundle b
                    ON b.id = c.bundle_id AND b.organization_id = c.organization_id
                  JOIN core.ad_outcome_policy plan
                    ON plan.id = b.outcome_policy_id
                   AND plan.organization_id = c.organization_id
                  JOIN ops.ad_bid_candidate cand
                    ON cand.id = c.candidate_id AND cand.organization_id = c.organization_id
                  JOIN mart.ad_case kase
                    ON kase.id = cand.case_id AND kase.organization_id = c.organization_id
                 WHERE c.direction <> 'EXACT_PRIOR_BID_COMPENSATION'
                   AND (
                     (NOT EXISTS (SELECT 1 FROM ops.ad_outcome_observation o
                                   WHERE o.command_id = c.id
                                     AND o.outcome_stage = 'OPERATIONAL')
                      AND :now >= landed.landed_at
                          + make_interval(mins => plan.observation_starts_minutes)
                          + make_interval(hours => plan.operational_window_hours))
                     OR
                     (NOT EXISTS (SELECT 1 FROM ops.ad_outcome_observation o
                                   WHERE o.command_id = c.id
                                     AND o.outcome_stage IN ('SETTLED', 'SETTLED_REVISED'))
                      AND :now >= landed.landed_at
                          + make_interval(mins => plan.observation_starts_minutes)
                          + make_interval(hours => plan.settlement_window_hours))
                     OR
                     EXISTS (
                        SELECT 1
                          FROM ops.ad_outcome_observation settled
                         WHERE settled.command_id = c.id
                           AND settled.outcome_stage IN ('SETTLED', 'SETTLED_REVISED')
                           AND settled.revision_no = (
                                SELECT max(latest.revision_no)
                                  FROM ops.ad_outcome_observation latest
                                 WHERE latest.command_id = c.id
                                   AND latest.outcome_stage IN ('SETTLED', 'SETTLED_REVISED'))
                           AND (EXISTS (
                                SELECT 1 FROM ledger.ad_object_fact f
                                 WHERE f.ad_native_object_id = c.ad_native_object_id
                                   AND f.organization_id = c.organization_id
                                   AND f.recorded_at > settled.evaluated_at
                                   AND f.period_end >= settled.window_starts_at
                                   AND f.period_start < settled.window_ends_at)
                             OR EXISTS (
                                SELECT 1 FROM ledger.ad_linked_sale_event e
                                 WHERE e.ad_native_object_id = c.ad_native_object_id
                                   AND e.organization_id = c.organization_id
                                   AND e.recorded_at > settled.evaluated_at
                                   AND e.occurred_at >= settled.window_starts_at
                                   AND e.occurred_at < settled.window_ends_at))))
                 ORDER BY landed.landed_at
                 LIMIT :limit
                """)
                .param("now", ts(now))
                .param("limit", limit)
                .query(AdvertisingOutcomeRepository::mapDue)
                .list();
    }

    /**
     * Whether a settled claim may be made for this command right now.
     *
     * <p>Asked of the database on purpose. The same predicate is a check
     * constraint on the observation row, so a service that computed a friendlier
     * answer here would simply be refused when it tried to write it.
     */
    public OutcomeEvaluation.GuardState guardState(UUID commandId, BigDecimal coverage) {
        String state = jdbc.sql("SELECT ops.ad_completed_sales_guard_state(:commandId, :coverage)")
                .param("commandId", commandId)
                .param("coverage", coverage)
                .query(String.class)
                .single();
        return OutcomeEvaluation.GuardState.valueOf(state);
    }

    /** Record one observation. Never an update: a later view is a later revision. */
    public UUID record(UUID id, DueRow due, String stage, int revisionNo,
                       UUID supersedesObservationId, String adjustmentReason,
                       Instant windowStartsAt, Instant windowEndsAt,
                       com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure
                               baseline,
                       com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure
                               observed,
                       Long observedTraffic, BigDecimal settledCoverage,
                       OutcomeEvaluation evaluation, Instant evaluatedAt, String inputDigest,
                       String correlationId) {
        jdbc.sql("""
                INSERT INTO ops.ad_outcome_observation (
                    id, organization_id, command_id, ad_native_object_id, affected_set_digest,
                    outcome_policy_id, outcome_policy_version, outcome_stage, revision_no,
                    supersedes_observation_id, adjustment_reason, window_starts_at,
                    window_ends_at, baseline_metric_state, baseline_metric_value,
                    observed_metric_state, observed_metric_value, observed_traffic_count,
                    settled_coverage_ratio, verdict, guard_state, unresolved_reason_codes,
                    evaluated_at, input_digest, correlation_id)
                VALUES (:id, :organizationId, :commandId, :objectId, :digest, :policyId,
                    :policyVersion, :stage, :revisionNo, :supersedes, :adjustmentReason,
                    :windowStartsAt, :windowEndsAt, :baselineState, :baselineValue,
                    :observedState, :observedValue, :traffic, :coverage, :verdict, :guardState,
                    CAST(:reasons AS text[]), :evaluatedAt, :inputDigest, :correlationId)
                """)
                .param("id", id)
                .param("organizationId", due.organizationId())
                .param("commandId", due.commandId())
                .param("objectId", due.adNativeObjectId())
                .param("digest", due.affectedSetDigest())
                .param("policyId", due.policyId())
                .param("policyVersion", due.policyVersion())
                .param("stage", stage)
                .param("revisionNo", revisionNo)
                .param("supersedes", supersedesObservationId)
                .param("adjustmentReason", adjustmentReason)
                .param("windowStartsAt", ts(windowStartsAt))
                .param("windowEndsAt", ts(windowEndsAt))
                .param("baselineState", baseline.valueState().name())
                .param("baselineValue", baseline.orElse(null))
                .param("observedState", observed.valueState().name())
                .param("observedValue", observed.orElse(null))
                .param("traffic", observedTraffic)
                .param("coverage", settledCoverage)
                .param("verdict", evaluation.verdict().name())
                .param("guardState", evaluation.guardState().name())
                .param("reasons", textArrayLiteral(evaluation.unresolvedReasons()))
                .param("evaluatedAt", ts(evaluatedAt))
                .param("inputDigest", inputDigest)
                .param("correlationId", correlationId)
                .update();
        return id;
    }

    /**
     * Reopen the lineage a settled regression came from.
     *
     * <p>Idempotent, and refused by the database for anything that is not a
     * guarded settled regression. The containment it writes is what the lane
     * resolver reads on the next calculation, so the case that appears is
     * produced by the same authority every other case is.
     */
    public UUID reopenAfterRegression(UUID containmentId, UUID observationId,
                                      String accountableRoleCode, String correlationId) {
        return jdbc.sql("""
                SELECT ops.reopen_ad_lineage_after_regression(:containmentId, :observationId,
                        :accountableRole, :correlationId)
                """)
                .param("containmentId", containmentId)
                .param("observationId", observationId)
                .param("accountableRole", accountableRoleCode)
                .param("correlationId", correlationId)
                .query(UUID.class)
                .single();
    }

    /** Every observation about one command, oldest first. */
    public List<ObservationRow> forCommand(UUID commandId) {
        return jdbc.sql("""
                SELECT id, command_id, outcome_stage, revision_no, supersedes_observation_id,
                       adjustment_reason, window_starts_at, window_ends_at,
                       baseline_metric_state, baseline_metric_value, observed_metric_state,
                       observed_metric_value, observed_traffic_count, settled_coverage_ratio,
                       verdict, guard_state, unresolved_reason_codes, evaluated_at
                  FROM ops.ad_outcome_observation
                 WHERE command_id = :commandId
                 ORDER BY evaluated_at, revision_no
                """)
                .param("commandId", commandId)
                .query(AdvertisingOutcomeRepository::mapObservation)
                .list();
    }

    /** One command's next due stage, and the frozen plan that judges it. */
    public record DueRow(
            UUID commandId, UUID organizationId, UUID storeId, String platformCode,
            UUID adNativeObjectId, String affectedSetDigest, String direction,
            Instant landedAt, UUID policyId, int policyVersion, String primaryMetricCode,
            String comparisonBasis, int observationStartsMinutes, int operationalWindowHours,
            int settlementWindowHours, BigDecimal improvementThresholdRatio,
            BigDecimal regressionThresholdRatio, long minimumTrafficCount,
            BigDecimal minimumSettledCoverageRatio, String causeCode, String nextStage,
            UUID latestSettledId, Integer latestSettledRevision) {

        /** When the observation window opens: after the change has had time to reach. */
        public Instant windowStartsAt() {
            return landedAt.plus(java.time.Duration.ofMinutes(observationStartsMinutes));
        }

        /** When the window for one stage closes. */
        public Instant windowEndsAt(String stage) {
            int hours = "OPERATIONAL".equals(stage)
                    ? operationalWindowHours : settlementWindowHours;
            return windowStartsAt().plus(java.time.Duration.ofHours(hours));
        }
    }

    /** One recorded observation. */
    public record ObservationRow(
            UUID id, UUID commandId, String outcomeStage, int revisionNo,
            UUID supersedesObservationId, String adjustmentReason, Instant windowStartsAt,
            Instant windowEndsAt, String baselineMetricState, BigDecimal baselineMetricValue,
            String observedMetricState, BigDecimal observedMetricValue,
            Long observedTrafficCount, BigDecimal settledCoverageRatio, String verdict,
            String guardState, List<String> unresolvedReasonCodes, Instant evaluatedAt) {
    }

    private static DueRow mapDue(ResultSet rs, int index) throws SQLException {
        return new DueRow(
                rs.getObject("command_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("platform_code"),
                rs.getObject("ad_native_object_id", UUID.class),
                rs.getString("affected_set_digest"),
                rs.getString("direction"),
                rs.getTimestamp("landed_at").toInstant(),
                rs.getObject("policy_id", UUID.class),
                rs.getInt("policy_version"),
                rs.getString("primary_metric_code"),
                rs.getString("comparison_basis"),
                rs.getInt("observation_starts_minutes"),
                rs.getInt("operational_window_hours"),
                rs.getInt("settlement_window_hours"),
                rs.getBigDecimal("improvement_threshold_ratio"),
                rs.getBigDecimal("regression_threshold_ratio"),
                rs.getLong("minimum_traffic_count"),
                rs.getBigDecimal("minimum_settled_coverage_ratio"),
                rs.getString("cause_code"),
                rs.getString("next_stage"),
                rs.getObject("latest_settled_id", UUID.class),
                integerOrNull(rs, "latest_settled_revision"));
    }

    private static ObservationRow mapObservation(ResultSet rs, int index) throws SQLException {
        Array reasons = rs.getArray("unresolved_reason_codes");
        return new ObservationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("command_id", UUID.class),
                rs.getString("outcome_stage"),
                rs.getInt("revision_no"),
                rs.getObject("supersedes_observation_id", UUID.class),
                rs.getString("adjustment_reason"),
                rs.getTimestamp("window_starts_at").toInstant(),
                rs.getTimestamp("window_ends_at").toInstant(),
                rs.getString("baseline_metric_state"),
                rs.getBigDecimal("baseline_metric_value"),
                rs.getString("observed_metric_state"),
                rs.getBigDecimal("observed_metric_value"),
                longOrNull(rs, "observed_traffic_count"),
                rs.getBigDecimal("settled_coverage_ratio"),
                rs.getString("verdict"),
                rs.getString("guard_state"),
                reasons == null ? List.of() : List.of((String[]) reasons.getArray()),
                rs.getTimestamp("evaluated_at").toInstant());
    }

    private static Integer integerOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static SqlParameterValue ts(Instant instant) {
        return new SqlParameterValue(Types.TIMESTAMP,
                instant == null ? null : Timestamp.from(instant));
    }

    private static String textArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append('"').append(values.get(index)
                    .replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return literal.append('}').toString();
    }

    /** The window aggregates one stage needs, read the same way for both windows. */
    public Optional<WindowRow> window(UUID organizationId, UUID objectId, String saleStage,
                                      Instant from, Instant to) {
        return jdbc.sql("""
                SELECT (SELECT sum(f.spend_amount) FROM ledger.ad_object_fact f
                         WHERE f.organization_id = :organizationId
                           AND f.ad_native_object_id = :objectId
                           AND f.period_start >= :from AND f.period_end <= :to
                           AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                                            WHERE later.supersedes_fact_id = f.id)) AS spend,
                       (SELECT sum(f.clicks) FROM ledger.ad_object_fact f
                         WHERE f.organization_id = :organizationId
                           AND f.ad_native_object_id = :objectId
                           AND f.period_start >= :from AND f.period_end <= :to
                           AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                                            WHERE later.supersedes_fact_id = f.id)) AS clicks,
                       (SELECT bool_and(f.report_window_complete)
                          FROM ledger.ad_object_fact f
                         WHERE f.organization_id = :organizationId
                           AND f.ad_native_object_id = :objectId
                           AND f.period_start >= :from AND f.period_end <= :to
                           AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                                            WHERE later.supersedes_fact_id = f.id))
                           AS windows_complete,
                       (SELECT bool_or(f.correction_window_open)
                          FROM ledger.ad_object_fact f
                         WHERE f.organization_id = :organizationId
                           AND f.ad_native_object_id = :objectId
                           AND f.period_start >= :from AND f.period_end <= :to
                           AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                                            WHERE later.supersedes_fact_id = f.id))
                           AS correction_open,
                       (SELECT sum(e.net_sales_amount) FROM ledger.ad_linked_sale_event e
                         WHERE e.organization_id = :organizationId
                           AND e.ad_native_object_id = :objectId
                           AND e.sale_stage = :saleStage
                           AND e.occurred_at >= :from AND e.occurred_at < :to
                           AND NOT EXISTS (SELECT 1 FROM ledger.ad_linked_sale_event later
                                            WHERE later.supersedes_event_id = e.id))
                           AS net_sales,
                       (SELECT sum(e.event_count) FROM ledger.ad_linked_sale_event e
                         WHERE e.organization_id = :organizationId
                           AND e.ad_native_object_id = :objectId
                           AND e.sale_stage = :saleStage
                           AND e.occurred_at >= :from AND e.occurred_at < :to
                           AND NOT EXISTS (SELECT 1 FROM ledger.ad_linked_sale_event later
                                            WHERE later.supersedes_event_id = e.id))
                           AS sale_events
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("saleStage", saleStage)
                .param("from", ts(from))
                .param("to", ts(to))
                .query((ResultSet rs, int index) -> new WindowRow(
                        rs.getBigDecimal("spend"),
                        longOrNull(rs, "clicks"),
                        rs.getBigDecimal("net_sales"),
                        longOrNull(rs, "sale_events"),
                        rs.getObject("windows_complete") != null
                                && rs.getBoolean("windows_complete"),
                        rs.getObject("correction_open") != null
                                && rs.getBoolean("correction_open")))
                .optional();
    }

    /** One window's official facts and linked sales. */
    public record WindowRow(
            BigDecimal spendAmount, Long clicks, BigDecimal netSalesAmount, Long saleEvents,
            boolean everyWindowComplete, boolean anyCorrectionOpen) {
    }
}
