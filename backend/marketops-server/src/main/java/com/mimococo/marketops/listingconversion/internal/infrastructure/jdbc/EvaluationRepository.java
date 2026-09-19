package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.listingconversion.EvaluationView;
import com.mimococo.marketops.listingconversion.NodeVerdict;
import com.mimococo.marketops.listingconversion.ProtectionVerdict;
import com.mimococo.marketops.listingconversion.SimulationView;
import com.mimococo.marketops.shared.JsonValues;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Frozen plans, node results, revisions and simulations. Results are append-only. */
@Repository
public class EvaluationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    EvaluationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record PlanRow(UUID id, UUID organizationId, UUID actionId, UUID calibrationPackageId, int calibrationVersion,
                          JsonNode versionCoverage, Instant latestBoundary, JsonNode formalNodes, JsonNode stopRule,
                          JsonNode criticalGroups, String comparisonBasis, int crossPeriodWindowDays, String planDigest,
                          Instant frozenAt) {
    }

    public Optional<PlanRow> plan(UUID actionId) {
        return jdbc.sql("""
                SELECT id, organization_id, action_id, calibration_package_id, calibration_version, version_coverage::text AS coverage,
                       latest_boundary, formal_nodes::text AS nodes, stop_rule::text AS stop, critical_groups::text AS groups,
                       comparison_basis, cross_period_window_days, plan_digest, frozen_at
                  FROM ops.lc_evaluation_plan WHERE action_id = :action
                """).param("action", actionId)
                .query((rs, n) -> new PlanRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getObject("action_id", UUID.class), rs.getObject("calibration_package_id", UUID.class),
                        rs.getInt("calibration_version"), JsonValues.read(json, rs.getString("coverage")),
                        ListingFactRepository.instant(rs, "latest_boundary"), JsonValues.read(json, rs.getString("nodes")),
                        JsonValues.read(json, rs.getString("stop")), JsonValues.read(json, rs.getString("groups")),
                        rs.getString("comparison_basis"), rs.getInt("cross_period_window_days"), rs.getString("plan_digest"),
                        ListingFactRepository.instant(rs, "frozen_at")))
                .optional();
    }

    public int nextRevision(UUID planId, String nodeCode, String stage) {
        return jdbc.sql("SELECT coalesce(max(revision_no), -1) + 1 FROM ops.lc_node_result WHERE plan_id = :plan AND node_code = :node AND stage = :stage")
                .param("plan", planId).param("node", nodeCode).param("stage", stage).query(Integer.class).single();
    }

    /** Serialize all result revisions for a plan in the surrounding transaction. */
    public void lockPlan(UUID planId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('lc-node-result:' || CAST(:plan AS text), 0))")
                .param("plan", planId).query((rs,n)->true).single();
    }

    public boolean previouslyAdmittedWindow(UUID planId, String nodeCode, String stage,
                                            Instant from, Instant to, int retentionDays) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.lc_node_result r
                  JOIN mart.lc_conversion_measurement m ON m.id=r.measurement_id
                  WHERE r.plan_id=:plan AND r.node_code=:node AND r.stage=:stage
                    AND r.evaluation_evidence->'nodeWindowQualified'='true'::jsonb
                    AND r.evaluation_evidence->'withinInitialEvaluationPeriod'='true'::jsonb
                    AND m.window_start=:from AND m.window_end=:to AND m.retention_window_days=:retention)
                """).param("plan",planId).param("node",nodeCode).param("stage",stage)
                .param("from",Timestamp.from(from)).param("to",Timestamp.from(to)).param("retention",retentionDays)
                .query(Boolean.class).single();
    }

    public Optional<UUID> latestResult(UUID planId, String nodeCode, String stage) {
        return jdbc.sql("SELECT id FROM ops.lc_node_result WHERE plan_id = :plan AND node_code = :node AND stage = :stage ORDER BY revision_no DESC LIMIT 1")
                .param("plan", planId).param("node", nodeCode).param("stage", stage).query(UUID.class).optional();
    }

    public record RecalculationTarget(UUID actionId, String nodeCode, String stage, UUID measurementId) { }

    /** Existing formal results whose exact measurement definition was refreshed by this queue item. */
    public List<RecalculationTarget> recalculationTargets(UUID listingId, List<UUID> measurementIds) {
        if (measurementIds.isEmpty()) return List.of();
        return jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (r.plan_id,r.node_code,r.stage)
                    p.action_id,r.node_code,r.stage,r.measurement_id
                  FROM ops.lc_node_result r
                  JOIN ops.lc_evaluation_plan p ON p.id=r.plan_id
                  JOIN ops.lc_action a ON a.id=p.action_id
                  WHERE a.platform_listing_id=:listing
                  ORDER BY r.plan_id,r.node_code,r.stage,r.revision_no DESC,r.evaluated_at DESC,r.id DESC
                ), refreshed AS (
                  SELECT DISTINCT ON (m.window_start,m.window_end,m.retention_window_days,m.evidence_path)
                    m.*,lineage.coverage_id,
                    lineage.inputs-'displayObservationAsOf' AS stable_inputs
                  FROM mart.lc_conversion_measurement m
                  JOIN mart.lc_measurement_lineage lineage ON lineage.measurement_id=m.id
                  WHERE m.platform_listing_id=:listing AND m.id=ANY(CAST(:measurements AS uuid[]))
                  ORDER BY m.window_start,m.window_end,m.retention_window_days,m.evidence_path,
                    m.computed_at DESC,m.id DESC
                )
                SELECT latest.action_id,latest.node_code,latest.stage,refreshed.id measurement_id
                FROM latest
                JOIN mart.lc_conversion_measurement prior ON prior.id=latest.measurement_id
                JOIN mart.lc_measurement_lineage prior_lineage ON prior_lineage.measurement_id=prior.id
                JOIN refreshed ON refreshed.window_start=prior.window_start
                  AND refreshed.window_end=prior.window_end
                  AND refreshed.retention_window_days=prior.retention_window_days
                  AND refreshed.evidence_path=prior.evidence_path
                WHERE (refreshed.coverage_id,refreshed.stable_inputs,refreshed.definition_version,
                       refreshed.path_qualified,refreshed.qualification_reason_codes,
                       refreshed.visit_count,refreshed.retained_purchase_visit_count,
                       refreshed.primary_ratio,refreshed.ratio_state,refreshed.maturity_reached,
                       refreshed.source_stratified,refreshed.sellable_split,
                       refreshed.excluded_transition_days,refreshed.source_time,refreshed.acquisition_time)
                  IS DISTINCT FROM
                      (prior_lineage.coverage_id,prior_lineage.inputs-'displayObservationAsOf',prior.definition_version,
                       prior.path_qualified,prior.qualification_reason_codes,
                       prior.visit_count,prior.retained_purchase_visit_count,
                       prior.primary_ratio,prior.ratio_state,prior.maturity_reached,
                       prior.source_stratified,prior.sellable_split,
                       prior.excluded_transition_days,prior.source_time,prior.acquisition_time)
                ORDER BY latest.action_id,latest.node_code,latest.stage
                """).param("listing",listingId).param("measurements",measurementIds.toArray(UUID[]::new))
                .query((rs,n)->new RecalculationTarget(rs.getObject("action_id",UUID.class),
                        rs.getString("node_code"),rs.getString("stage"),
                        rs.getObject("measurement_id",UUID.class))).list();
    }

    /** Called under the plan lock: retries cannot append the same retained conclusion again. */
    public boolean latestResultMatches(UUID planId, String nodeCode, String stage, UUID measurementId,
                                       BigDecimal ratio, BigDecimal bound, BigDecimal threshold, NodeVerdict verdict,
                                       Map<String,String> vector, ProtectionVerdict protection, boolean stop,
                                       boolean maturity, Instant sourceTime, Map<String,Object> evidence) {
        return jdbc.sql("""
                SELECT coalesce((SELECT
                    measurement_id IS NOT DISTINCT FROM CAST(:measurement AS uuid)
                    AND primary_ratio IS NOT DISTINCT FROM CAST(:ratio AS numeric)
                    AND conservative_bound IS NOT DISTINCT FROM CAST(:bound AS numeric)
                    AND accepted_threshold IS NOT DISTINCT FROM CAST(:threshold AS numeric)
                    AND verdict=:verdict AND protection_vector=CAST(:vector AS jsonb)
                    AND protection_verdict=:protection AND stop_triggered=:stop AND maturity_reached=:maturity
                    AND source_time IS NOT DISTINCT FROM CAST(:source AS timestamptz)
                    AND evaluation_evidence=CAST(:evidence AS jsonb)
                  FROM ops.lc_node_result WHERE plan_id=:plan AND node_code=:node AND stage=:stage
                  ORDER BY revision_no DESC LIMIT 1),false)
                """).param("plan",planId).param("node",nodeCode).param("stage",stage)
                .param("measurement",measurementId).param("ratio",ratio).param("bound",bound)
                .param("threshold",threshold).param("verdict",verdict.name())
                .param("vector",json.writeValueAsString(vector)).param("protection",protection.name())
                .param("stop",stop).param("maturity",maturity).param("source",ListingFactRepository.ts(sourceTime))
                .param("evidence",json.writeValueAsString(evidence)).query(Boolean.class).single();
    }

    public void insertResult(UUID id, UUID organizationId, UUID planId, String nodeCode, String stage, int revision,
                             UUID measurementId, UUID runId, BigDecimal ratio, BigDecimal bound, BigDecimal threshold,
                             NodeVerdict verdict, Map<String, String> vector, ProtectionVerdict protection, boolean stop,
                             boolean maturity, Instant sourceTime, Instant now, Map<String,Object> evidence) {
        jdbc.sql("""
                INSERT INTO ops.lc_node_result (id, organization_id, plan_id, node_code, stage, revision_no, measurement_id,
                    calculation_run_id, primary_ratio, conservative_bound, accepted_threshold, verdict, protection_vector,
                    protection_verdict, stop_triggered, maturity_reached, source_time, evaluated_at, evaluation_evidence)
                VALUES (:id, :org, :plan, :node, :stage, :revision, :measurement, :run, :ratio, :bound, :threshold, :verdict,
                    CAST(:vector AS jsonb), :protection, :stop, :maturity, :source, :now, CAST(:evidence AS jsonb))
                """).param("id", id).param("org", organizationId).param("plan", planId).param("node", nodeCode).param("stage", stage)
                .param("revision", revision).param("measurement", measurementId).param("run", runId).param("ratio", ratio)
                .param("bound", bound).param("threshold", threshold).param("verdict", verdict.name())
                .param("vector", json.writeValueAsString(vector)).param("protection", protection.name()).param("stop", stop)
                .param("maturity", maturity).param("source", ListingFactRepository.ts(sourceTime)).param("now", Timestamp.from(now))
                .param("evidence",json.writeValueAsString(evidence))
                .update();
    }

    public void insertRevision(UUID id, UUID organizationId, UUID planId, UUID originalId, UUID revisedId, String reason,
                               String reference, Instant now) {
        jdbc.sql("""
                INSERT INTO ops.lc_outcome_revision (id, organization_id, plan_id, original_result_id, revised_result_id,
                    revision_reason, late_fact_reference, recorded_at)
                VALUES (:id, :org, :plan, :original, :revised, :reason, :reference, :now)
                """).param("id", id).param("org", organizationId).param("plan", planId).param("original", originalId)
                .param("revised", revisedId).param("reason", reason).param("reference", reference).param("now", Timestamp.from(now))
                .update();
    }

    public List<EvaluationView.NodeResult> results(UUID planId) {
        return jdbc.sql("""
                SELECT id, node_code, stage, revision_no, primary_ratio, conservative_bound, accepted_threshold, verdict,
                       protection_vector::text AS vector, protection_verdict, stop_triggered, evaluated_at,
                       evaluation_evidence::text AS evidence,evaluation_evidence#>>'{futility,state}' AS futility_state,
                       (SELECT p.stop_rule = '{}'::jsonb FROM ops.lc_evaluation_plan p WHERE p.id = plan_id) AS no_stop_rule
                  FROM ops.lc_node_result WHERE plan_id = :plan ORDER BY evaluated_at
                """).param("plan", planId)
                .query((rs, n) -> new EvaluationView.NodeResult(rs.getObject("id", UUID.class), rs.getString("node_code"),
                        rs.getString("stage"), rs.getInt("revision_no"), rs.getBigDecimal("primary_ratio"),
                        rs.getBigDecimal("conservative_bound"), rs.getBigDecimal("accepted_threshold"),
                        NodeVerdict.valueOf(rs.getString("verdict")), ListingActionRepository.stringMap(rs.getString("vector")),
                        ProtectionVerdict.valueOf(rs.getString("protection_verdict")),
                        rs.getBoolean("stop_triggered") ? "STOP" : rs.getBoolean("no_stop_rule") ? "NOT_CONFIGURED"
                                : "QUALIFIED_NOT_TRIGGERED".equals(rs.getString("futility_state")) ? "CONTINUE" : "UNDETERMINED",
                        ListingFactRepository.instant(rs, "evaluated_at"),
                        rs.getString("evidence")==null?Map.of():jsonToObjectMap(json.readTree(rs.getString("evidence")))) )
                .list();
    }

    public List<EvaluationView.Revision> revisions(UUID planId) {
        return jdbc.sql("""
                SELECT id, original_result_id, revised_result_id, revision_reason, late_fact_reference, recorded_at
                  FROM ops.lc_outcome_revision WHERE plan_id = :plan ORDER BY recorded_at
                """).param("plan", planId)
                .query((rs, n) -> new EvaluationView.Revision(rs.getObject("id", UUID.class),
                        rs.getObject("original_result_id", UUID.class), rs.getObject("revised_result_id", UUID.class),
                        rs.getString("revision_reason"), rs.getString("late_fact_reference"),
                        ListingFactRepository.instant(rs, "recorded_at")))
                .list();
    }

    public void insertSimulation(UUID id, UUID organizationId, UUID candidateId, List<Map<String, Object>> scenarios,
                                 List<Map<String, Object>> results, BigDecimal inverseMinimum,
                                 String inverseState, Instant now, List<UUID> evidenceScope, String modelVersion,
                                 Map<String, Object> snapshot, Boolean conditionalComparison) {
        jdbc.sql("""
                INSERT INTO ops.lc_simulation (id, organization_id, candidate_id, scenario_set, inputs_digest,
                    results, inverse_minimum_quantity, inverse_state, computed_at, evidence_product_variant_ids,
                    model_version, input_snapshot, conditional_scenarios_passed)
                VALUES (:id, :org, :candidate, CAST(:scenarios AS jsonb),
                    encode(sha256(convert_to(CAST(:snapshot AS jsonb)::text,'UTF8')),'hex'), CAST(:results AS jsonb), :inverse,
                    :state, :now, :scope, :model, CAST(:snapshot AS jsonb), :comparison)
                """).param("id", id).param("org", organizationId).param("candidate", candidateId)
                .param("scenarios", json.writeValueAsString(scenarios))
                .param("results", json.writeValueAsString(results)).param("inverse", inverseMinimum).param("state", inverseState)
                .param("now", Timestamp.from(now)).param("model", modelVersion)
                .param("snapshot", json.writeValueAsString(snapshot)).param("comparison", conditionalComparison)
                .param("scope", evidenceScope == null || evidenceScope.isEmpty() ? null : evidenceScope.toArray(UUID[]::new)).update();
    }

    public List<UUID> simulationEvidenceScope(UUID id) {
        return jdbc.sql("SELECT unnest(evidence_product_variant_ids) FROM ops.lc_simulation WHERE id = :id")
                .param("id", id).query(UUID.class).list();
    }

    public List<SimulationView> simulations(UUID candidateId) {
        return jdbc.sql("""
                SELECT id, candidate_id, results::text AS results, inverse_minimum_quantity, inverse_state, inputs_digest, computed_at,
                       model_version, input_snapshot::text AS input_snapshot, conditional_scenarios_passed
                  FROM ops.lc_simulation WHERE candidate_id = :candidate ORDER BY computed_at DESC
                """).param("candidate", candidateId).query(this::mapSimulation).list();
    }

    public java.util.Optional<SimulationView> simulation(UUID candidateId,UUID simulationId) {
        return jdbc.sql("""
                SELECT id,candidate_id,results::text AS results,inverse_minimum_quantity,inverse_state,inputs_digest,computed_at,
                       model_version,input_snapshot::text AS input_snapshot,conditional_scenarios_passed
                  FROM ops.lc_simulation WHERE candidate_id=:candidate AND id=:id
                """).param("candidate",candidateId).param("id",simulationId).query(this::mapSimulation).optional();
    }

    private SimulationView mapSimulation(ResultSet rs, int n) throws SQLException {
        List<SimulationView.Scenario> scenarios = new ArrayList<>();
        for (JsonNode node : JsonValues.read(json, rs.getString("results"))) {
            List<String> missing = new ArrayList<>();
            node.path("missingInputs").forEach(item -> missing.add(item.asText()));
            scenarios.add(new SimulationView.Scenario(node.path("code").asText(), node.path("state").asText(),
                    decimal(node.path("quantity")), decimal(node.path("netRevenue")), decimal(node.path("contributionProfit")),
                    missing));
        }
        JsonNode snapshot = rs.getString("input_snapshot") == null
                ? null : JsonValues.read(json, rs.getString("input_snapshot"));
        String qualification = snapshot != null
                && "QUALIFIED_CONDITIONAL_ECONOMICS".equals(snapshot.path("qualificationState").asText())
                ? "QUALIFIED_CONDITIONAL_ECONOMICS" : "UNQUALIFIED";
        return new SimulationView(rs.getObject("id", UUID.class), rs.getObject("candidate_id", UUID.class), scenarios,
                rs.getBigDecimal("inverse_minimum_quantity"), rs.getString("inverse_state"), rs.getString("inputs_digest"),
                ListingFactRepository.instant(rs, "computed_at"), rs.getString("model_version"),
                snapshot, rs.getObject("conditional_scenarios_passed", Boolean.class), qualification);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : new BigDecimal(node.asText());
    }

    static Map<String, String> jsonToStringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.properties().forEach(entry -> out.put(entry.getKey(),
                    entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString()));
        }
        return out;
    }

    private static Map<String,Object> jsonToObjectMap(JsonNode node) {
        Map<String,Object> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> values.put(entry.getKey(),entry.getValue().deepCopy()));
        return values;
    }
}
