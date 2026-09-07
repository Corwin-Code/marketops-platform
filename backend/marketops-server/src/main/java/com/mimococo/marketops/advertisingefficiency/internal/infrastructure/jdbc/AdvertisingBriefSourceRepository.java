package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import com.mimococo.marketops.shared.Digest;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What a brief reads, and nothing it writes.
 *
 * <p>Every statement in this class is a {@code SELECT}. That is not an accident
 * of the current implementation: a brief is a report about canonical authorities
 * and the moment it could write one it would become a second one. Anything that
 * looks like it needs a write here is a sign the reporting layer is being asked
 * to decide something it has no standing to decide.
 *
 * <p>Each query answers one named section of the Contract's daily or weekly
 * outline and returns the canonical rows that fell inside the period, with the
 * figure each carries and the state of that figure. A section with no rows is a
 * section with no rows — never a zero.
 */
@Repository
public class AdvertisingBriefSourceRepository {

    private final JdbcClient jdbc;

    AdvertisingBriefSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One canonical row a brief links to.
     *
     * @param subjectKind which authority it belongs to
     * @param referenceId that row's own identity
     * @param storeId the store, where the subject has one
     * @param lane the lane, where the subject has one
     * @param protectionTier the sub-tier, for a Protection case
     * @param causeCode why the case exists
     * @param valueState whether the figure exists at all
     * @param numericValue the figure, when it does
     * @param currencyCode the currency, for money
     * @param evidenceState how well established the figure is
     * @param confidenceState how much can be concluded from it
     * @param blockerCodes what stops it being acted on
     * @param observedAt when the underlying fact was observed
     */
    public record Link(String subjectKind, UUID referenceId, UUID storeId, String lane,
                       String protectionTier, String causeCode, String valueState,
                       BigDecimal numericValue, String currencyCode, String evidenceState,
                       String confidenceState, List<String> blockerCodes, Instant observedAt) {
    }

    /**
     * The canonical rows one section covers for one period.
     *
     * <p>A section this class has no query for returns nothing rather than
     * throwing. The producer emits the section anyway, with a coverage state
     * saying it found no source — which is the honest answer and is visibly
     * different from finding nothing.
     */
    public List<Link> linksFor(UUID organizationId, String sectionCode, Instant from,
                               Instant to) {
        return switch (sectionCode) {
            case "DATA_HEALTH", "DATA_REPAIR" -> casesInLane(organizationId, "DATA_REPAIR", from, to);
            case "IMMEDIATE_PROTECTION_AND_REGRESSION" ->
                    casesInLane(organizationId, "PROTECTION", from, to);
            case "QUALIFIED_OPTIMIZATION" ->
                    casesInLane(organizationId, "OPTIMIZATION", from, to);
            case "WATCH" -> casesInLane(organizationId, "WATCH", from, to);
            case "HUMAN_RESPONSIBILITY", "SYSTEM_AND_HUMAN_SLO" ->
                    sloObservations(organizationId, from, to);
            case "APPROVALS_AND_EXCEPTIONS", "GOVERNED_ACTIONS" ->
                    advertisingTasks(organizationId, from, to);
            case "EXECUTION_AND_AGGREGATE_EXPOSURE", "AGGREGATE_EXPOSURE" ->
                    reservations(organizationId, from, to);
            case "UNKNOWN_MISMATCH_AND_MANUAL_VERIFICATION", "CONFIGURATION_VERIFICATION" ->
                    manualPackets(organizationId, from, to);
            case "RECENT_OUTCOMES", "OPERATIONAL_AND_SETTLED_TRANSITIONS", "EARLY_GUARDS" ->
                    outcomes(organizationId, from, to);
            case "REGRESSION_QUARANTINE_AND_COMPENSATION", "EXCEPTIONS" ->
                    containments(organizationId, from, to);
            case "SHADOW_DECISION_REASONS" -> shadowDecisions(organizationId, from, to);
            case "POLICY_BUNDLE_MATURITY" -> bundles(organizationId);
            default -> List.of();
        };
    }

    /** Live cases in one lane whose latest calculation fell inside the period. */
    private List<Link> casesInLane(UUID organizationId, String lane, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, store_id, lane, protection_tier, cause_code, evidence_state,
                       confidence_state, blocker_codes, contribution_profit_state,
                       contribution_profit_amount, profit_currency_code, calculated_at
                  FROM mart.ad_case
                 WHERE organization_id = :organizationId AND lane = :lane
                   AND superseded_at IS NULL
                   AND calculated_at >= :from AND calculated_at < :to
                 ORDER BY rank_score DESC, id
                """)
                .param("organizationId", organizationId)
                .param("lane", lane)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int index) -> new Link("AD_CASE",
                        rs.getObject("id", UUID.class),
                        rs.getObject("store_id", UUID.class),
                        rs.getString("lane"),
                        rs.getString("protection_tier"),
                        rs.getString("cause_code"),
                        rs.getString("contribution_profit_state"),
                        rs.getBigDecimal("contribution_profit_amount"),
                        rs.getString("profit_currency_code"),
                        rs.getString("evidence_state"),
                        rs.getString("confidence_state"),
                        strings(rs, "blocker_codes"),
                        rs.getTimestamp("calculated_at").toInstant()))
                .list();
    }

    /**
     * Cases with a blocker, whatever their lane.
     *
     * <p>The shadow section is about decisions this product declined to turn
     * into work, so it reads the cases carrying a refusal rather than the ones
     * carrying a proposal.
     */
    private List<Link> shadowDecisions(UUID organizationId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, store_id, lane, protection_tier, cause_code, evidence_state,
                       confidence_state, blocker_codes, calculated_at
                  FROM mart.ad_case
                 WHERE organization_id = :organizationId
                   AND superseded_at IS NULL
                   AND cardinality(blocker_codes) > 0
                   AND calculated_at >= :from AND calculated_at < :to
                 ORDER BY rank_score DESC, id
                """)
                .param("organizationId", organizationId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int index) -> new Link("AD_CASE",
                        rs.getObject("id", UUID.class),
                        rs.getObject("store_id", UUID.class),
                        rs.getString("lane"), rs.getString("protection_tier"),
                        rs.getString("cause_code"), "NOT_AVAILABLE", null, null,
                        rs.getString("evidence_state"), rs.getString("confidence_state"),
                        strings(rs, "blocker_codes"),
                        rs.getTimestamp("calculated_at").toInstant()))
                .list();
    }

    private List<Link> sloObservations(UUID organizationId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, lane, internal_latency_ms, breached, calculated_at
                  FROM ops.ad_slo_observation
                 WHERE organization_id = :organizationId
                   AND calculated_at >= :from AND calculated_at < :to
                 ORDER BY internal_latency_ms DESC, id
                 LIMIT 200
                """)
                .param("organizationId", organizationId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int index) -> new Link("SLO_OBSERVATION",
                        rs.getObject("id", UUID.class), null, rs.getString("lane"), null,
                        rs.getBoolean("breached") ? "SLO_BREACHED" : null,
                        "AVAILABLE", BigDecimal.valueOf(rs.getLong("internal_latency_ms")),
                        null, null, null, List.of(),
                        rs.getTimestamp("calculated_at").toInstant()))
                .list();
    }

    /** Tasks raised from an advertising recommendation inside the period. */
    private List<Link> advertisingTasks(UUID organizationId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT task.id, task.state, task.created_at
                  FROM ops.work_task task
                  JOIN ops.recommendation rec ON rec.id = task.recommendation_id
                 WHERE task.organization_id = :organizationId
                   AND rec.action_kind = 'AD_BID_CHANGE'
                   AND task.created_at >= :from AND task.created_at < :to
                 ORDER BY task.created_at, task.id
                """)
                .param("organizationId", organizationId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int index) -> new Link("WORK_TASK",
                        rs.getObject("id", UUID.class), null, null, null,
                        rs.getString("state"), "NOT_AVAILABLE", null, null, null, null,
                        List.of(), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private List<Link> reservations(UUID organizationId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, store_id, lane, intervention_kind, state, reserved_at
                  FROM ops.ad_action_reservation
                 WHERE organization_id = :organizationId
                   AND reserved_at >= :from AND reserved_at < :to
                 ORDER BY reserved_at, id
                """)
                .param("organizationId", organizationId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int index) -> new Link("RESERVATION",
                        rs.getObject("id", UUID.class),
                        rs.getObject("store_id", UUID.class),
                        rs.getString("lane"), null, rs.getString("intervention_kind"),
                        "NOT_AVAILABLE", null, null, null, rs.getString("state"), List.of(),
                        rs.getTimestamp("reserved_at").toInstant()))
                .list();
    }

    private List<Link> manualPackets(UUID organizationId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, store_id, action_kind, state, blocker_codes, issued_at
                  FROM ops.ad_manual_execution_packet
                 WHERE organization_id = :organizationId
                   AND issued_at >= :from AND issued_at < :to
                 ORDER BY issued_at, id
                """)
                .param("organizationId", organizationId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int index) -> new Link("MANUAL_PACKET",
                        rs.getObject("id", UUID.class),
                        rs.getObject("store_id", UUID.class), null, null,
                        rs.getString("action_kind"), "NOT_AVAILABLE", null, null, null,
                        rs.getString("state"), strings(rs, "blocker_codes"),
                        rs.getTimestamp("issued_at").toInstant()))
                .list();
    }

    private List<Link> outcomes(UUID organizationId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, outcome_stage, verdict, guard_state, observed_metric_state,
                       observed_metric_value, unresolved_reason_codes, evaluated_at
                  FROM ops.ad_outcome_observation
                 WHERE organization_id = :organizationId
                   AND evaluated_at >= :from AND evaluated_at < :to
                 ORDER BY evaluated_at, revision_no, id
                """)
                .param("organizationId", organizationId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int index) -> new Link("OUTCOME_OBSERVATION",
                        rs.getObject("id", UUID.class), null, rs.getString("outcome_stage"),
                        rs.getString("guard_state"), rs.getString("verdict"),
                        rs.getString("observed_metric_state"),
                        rs.getBigDecimal("observed_metric_value"), null, null, null,
                        strings(rs, "unresolved_reason_codes"),
                        rs.getTimestamp("evaluated_at").toInstant()))
                .list();
    }

    private List<Link> containments(UUID organizationId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, containment_kind, scope_kind, cause_class, state, activated_at
                  FROM ops.ad_containment
                 WHERE organization_id = :organizationId
                   AND activated_at >= :from AND activated_at < :to
                 ORDER BY activated_at, id
                """)
                .param("organizationId", organizationId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((ResultSet rs, int index) -> new Link("CONTAINMENT",
                        rs.getObject("id", UUID.class), null, rs.getString("scope_kind"), null,
                        rs.getString("cause_class"), "NOT_AVAILABLE", null, null,
                        rs.getString("containment_kind"), rs.getString("state"), List.of(),
                        rs.getTimestamp("activated_at").toInstant()))
                .list();
    }

    /**
     * Every decision bundle, whatever its state.
     *
     * <p>Not filtered by the period: bundle maturity is a statement about now,
     * and a week in which nothing was published is exactly the week a reader
     * needs to see that the bundles are all still drafts.
     */
    private List<Link> bundles(UUID organizationId) {
        return jdbc.sql("""
                SELECT id, store_id, direction, candidate_basis, status, validation_state,
                       created_at
                  FROM ops.ad_decision_policy_bundle
                 WHERE organization_id = :organizationId
                 ORDER BY created_at, id
                """)
                .param("organizationId", organizationId)
                .query((ResultSet rs, int index) -> new Link("DECISION_BUNDLE",
                        rs.getObject("id", UUID.class),
                        rs.getObject("store_id", UUID.class),
                        rs.getString("direction"), null, rs.getString("candidate_basis"),
                        "NOT_AVAILABLE", null, null, rs.getString("validation_state"),
                        rs.getString("status"), List.of(),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    /**
     * The digest of the policy versions live at the moment of reading.
     *
     * <p>Frozen onto the publication so a policy retired tomorrow cannot change
     * what a report said it was calculated under.
     */
    public String policyVersionDigest(UUID organizationId) {
        List<String> digests = jdbc.sql("""
                SELECT DISTINCT policy_version_digest FROM mart.ad_case
                 WHERE organization_id = :organizationId AND superseded_at IS NULL
                 ORDER BY 1
                """)
                .param("organizationId", organizationId)
                .query(String.class)
                .list();
        return Digest.ofComponents(digests.isEmpty() ? List.of("NO_LIVE_CASE") : digests);
    }

    /** Which authority versions were in force, as the publication records them. */
    public String bundleVersionSnapshot(UUID organizationId) {
        List<String> rows = jdbc.sql("""
                SELECT '{"bundleId":"' || id || '","bundleVersion":' || bundle_version
                       || ',"status":"' || status || '","validationState":"'
                       || validation_state || '"}'
                  FROM ops.ad_decision_policy_bundle
                 WHERE organization_id = :organizationId
                 ORDER BY created_at, id
                """)
                .param("organizationId", organizationId)
                .query(String.class)
                .list();
        return "[" + String.join(",", rows) + "]";
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
