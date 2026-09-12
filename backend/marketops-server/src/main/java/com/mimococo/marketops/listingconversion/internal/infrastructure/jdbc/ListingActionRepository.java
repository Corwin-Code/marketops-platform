package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.listingconversion.AllowanceView;
import com.mimococo.marketops.listingconversion.CandidateKind;
import com.mimococo.marketops.listingconversion.CandidateView;
import com.mimococo.marketops.listingconversion.ExecutionPath;
import com.mimococo.marketops.listingconversion.ListingActionState;
import com.mimococo.marketops.listingconversion.ListingActionView;
import com.mimococo.marketops.listingconversion.MaterialityRoute;
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

/**
 * Candidates, actions, reviews, bindings, plans and what a launch left behind.
 *
 * <p>Launch, occupation and containment rows are read here and written only by
 * their database functions, which the workflow module calls. The action row's
 * own state moves only along the reviewed edge table, which the trigger enforces.
 */
@Repository
public class ListingActionRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ListingActionRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ candidates

    public void insertCandidate(UUID id, UUID organizationId, UUID storeId, UUID listingId, UUID runId, UUID healthId,
                                CandidateKind kind, String roundKey, List<String> evidence, Map<String, String> effect,
                                UUID preparedBy, Instant now) {
        jdbc.sql("""
                INSERT INTO ops.lc_candidate (id, organization_id, store_id, platform_listing_id, calculation_run_id, health_id,
                    candidate_kind, comparison_round_key, evidence_references, expected_effect, prepared_by_user_id,
                    prepared_at, state, updated_at, version)
                VALUES (:id, :org, :store, :listing, :run, :health, :kind, :round, CAST(:evidence AS jsonb),
                    CAST(:effect AS jsonb), :user, :now, 'OPEN', :now, 0)
                """).param("id", id).param("org", organizationId).param("store", storeId).param("listing", listingId)
                .param("run", runId).param("health", healthId).param("kind", kind.name()).param("round", roundKey)
                .param("evidence", json.writeValueAsString(evidence)).param("effect", json.writeValueAsString(effect))
                .param("user", preparedBy).param("now", Timestamp.from(now)).update();
    }

    public Optional<CandidateView> candidate(UUID id) {
        return jdbc.sql(CANDIDATE_SELECT + " WHERE c.id = :id").param("id", id).query(this::mapCandidate).optional();
    }

    public List<CandidateView> candidates(UUID listingId, String roundKey) {
        return jdbc.sql(CANDIDATE_SELECT + """
                 WHERE c.platform_listing_id = :listing AND (:round IS NULL OR c.comparison_round_key = :round)
                 ORDER BY c.prepared_at DESC
                """).param("listing", listingId)
                .param("round", new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.VARCHAR, roundKey))
                .query(this::mapCandidate).list();
    }

    public boolean moveCandidate(UUID id, String from, String to, long expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE ops.lc_candidate SET state = :to, updated_at = :now, version = version + 1
                 WHERE id = :id AND state = :from AND version = :version
                """).param("id", id).param("from", from).param("to", to).param("version", expectedVersion)
                .param("now", Timestamp.from(now)).update() == 1;
    }

    private static final String CANDIDATE_SELECT = """
            SELECT c.id, c.store_id, c.platform_listing_id, c.candidate_kind, c.comparison_round_key,
                   c.evidence_references::text AS evidence, c.expected_effect::text AS effect, c.prepared_by_user_id,
                   c.prepared_at, c.state, c.version
              FROM ops.lc_candidate c
            """;

    private CandidateView mapCandidate(ResultSet rs, int n) throws SQLException {
        List<String> evidence = new ArrayList<>();
        JsonValues.read(json, rs.getString("evidence")).forEach(node -> evidence.add(node.asText()));
        return new CandidateView(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("platform_listing_id", UUID.class), CandidateKind.valueOf(rs.getString("candidate_kind")),
                rs.getString("comparison_round_key"), evidence, stringMap(rs.getString("effect")),
                rs.getObject("prepared_by_user_id", UUID.class), ListingFactRepository.instant(rs, "prepared_at"),
                rs.getString("state"), rs.getLong("version"));
    }

    // ------------------------------------------------------------------ actions

    public record ActionRow(UUID id, UUID organizationId, UUID storeId, UUID listingId, UUID candidateId,
                            UUID recommendationId, UUID affectedSetId, String affectedSetDigest, String actionKind,
                            String executionPath, UUID currentObservationId, String currentTextDigest, String targetText,
                            String targetTextDigest, Boolean kizMarkedDeclared, Boolean contentAxisMaterial,
                            Boolean exposureAxisMaterial, String materialityRoute, UUID calibrationPackageId,
                            Integer calibrationVersion, UUID authorUserId, String state, Instant createdAt,
                            Instant updatedAt, long version, UUID restoresCommandId, String promotionTermsDigest) {
    }

    /** One database clock for the persisted preparation/plan/review/binding chronology. */
    public Instant databaseNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(Timestamp.class).single().toInstant();
    }

    public void insertAction(UUID id, UUID organizationId, UUID storeId, UUID listingId, UUID candidateId,
                             UUID recommendationId, UUID affectedSetId, String affectedSetDigest, String actionKind,
                             ExecutionPath path, UUID currentObservationId, String currentTextDigest, String targetText,
                             String targetTextDigest, Boolean kizMarked, Boolean contentAxis, Boolean exposureAxis,
                             MaterialityRoute route, UUID calibrationPackageId, Integer calibrationVersion,
                             UUID authorUserId, Instant now, UUID restoresCommandId,
                             com.mimococo.marketops.listingconversion.PromotionTerms promotionTerms, Map<String,Object> materialityEvidence) {
        jdbc.sql("""
                INSERT INTO ops.lc_action (id, organization_id, store_id, platform_listing_id, candidate_id, recommendation_id,
                    affected_set_id, affected_set_digest, action_kind, execution_path, current_description_observation_id,
                    current_text_digest, target_text, target_text_digest, target_language_code, kiz_marked_declared,
                    content_axis_material, exposure_axis_material, materiality_route, calibration_package_id,
                    calibration_version, author_user_id, state, created_at, updated_at, version, restores_command_id, promotion_terms, materiality_evidence)
                VALUES (:id, :org, :store, :listing, :candidate, :recommendation, :set, :digest, :kind, :path, :observation,
                    :currentDigest, :text, :textDigest, :language, :kiz, :content, :exposure, :route, :package, :packageVersion,
                    :author, 'DRAFT', :now, :now, 0, :restores, CAST(:promotion AS jsonb), CAST(:materiality AS jsonb))
                """).param("id", id).param("org", organizationId).param("store", storeId).param("listing", listingId)
                .param("candidate", candidateId).param("recommendation", recommendationId).param("set", affectedSetId)
                .param("digest", affectedSetDigest).param("kind", actionKind).param("path", path.name())
                .param("observation", currentObservationId).param("currentDigest", currentTextDigest)
                .param("text", targetText).param("textDigest", targetTextDigest)
                .param("language", targetText == null ? null : "ru").param("kiz", kizMarked)
                .param("content", contentAxis).param("exposure", exposureAxis).param("route", route.name())
                .param("package", calibrationPackageId).param("packageVersion", calibrationVersion)
                .param("author", authorUserId).param("now", Timestamp.from(now)).param("restores",restoresCommandId)
                .param("promotion",promotionTerms==null?null:json.writeValueAsString(promotionTerms))
                .param("materiality",json.writeValueAsString(materialityEvidence)).update();
    }

    public String promotionTermsDigest(com.mimococo.marketops.listingconversion.PromotionTerms terms) {
        return jdbc.sql("SELECT ops.lc_promotion_terms_digest(CAST(:terms AS jsonb))")
                .param("terms",json.writeValueAsString(terms)).query(String.class).single();
    }

    public record FrozenPromotion(String digest,com.mimococo.marketops.listingconversion.PromotionTerms terms) { }

    public FrozenPromotion promotionTerms(UUID actionId) {
        return jdbc.sql("SELECT promotion_terms_digest,promotion_terms::text FROM ops.lc_action WHERE id=:id")
                .param("id",actionId).query((rs,n)->new FrozenPromotion(rs.getString(1),rs.getString(2)==null?null:
                    json.readValue(rs.getString(2),com.mimococo.marketops.listingconversion.PromotionTerms.class))).single();
    }

    public List<UUID> promotionEvidenceProducts(UUID actionId) {
        return jdbc.sql("""
                SELECT DISTINCT member FROM ops.lc_action a JOIN core.lc_affected_set s ON s.id=a.affected_set_id,
                  unnest(s.product_variant_ids) member WHERE a.id=:id AND s.resolution_state='COMPLETE'
                    AND s.native_scope_observation_id IS NOT NULL AND s.identity_lineage#>>'{nativeScope,state}'='COMPLETE'
                """).param("id",actionId).query(UUID.class).list();
    }

    public record RestorationSource(UUID commandId,String priorText,String appliedTextDigest) { }

    public Optional<RestorationSource> restorationSource(UUID commandId,UUID organizationId,UUID listingId) {
        return jdbc.sql("""
                SELECT c.id,c.prior_text,c.target_text_digest FROM ops.lc_description_command c
                  JOIN ops.lc_action a ON a.id=c.action_id
                 WHERE c.id=:id AND c.organization_id=:org AND c.platform_listing_id=:listing
                   AND c.state='READBACK_MATCHED' AND c.prior_text IS NOT NULL AND c.prior_text<>''
                   AND a.state IN ('VERIFIED','CONTAINED','CLOSED')
                   AND EXISTS (SELECT 1 FROM ops.lc_execution_receipt r WHERE r.command_id=c.id
                       AND r.execution_state='MANAGEMENT_VERIFIED')
                """).param("id",commandId).param("org",organizationId).param("listing",listingId)
                .query((rs,n)->new RestorationSource(rs.getObject("id",UUID.class),rs.getString("prior_text"),
                        rs.getString("target_text_digest"))).optional();
    }

    public Optional<ActionRow> action(UUID id) {
        return jdbc.sql(ACTION_SELECT + " WHERE a.id = :id").param("id", id).query(ListingActionRepository::mapAction)
                .optional();
    }

    public Optional<ActionRow> actionForRecommendation(UUID recommendationId) {
        return jdbc.sql(ACTION_SELECT + " WHERE a.recommendation_id = :id").param("id", recommendationId)
                .query(ListingActionRepository::mapAction).optional();
    }

    public List<ActionRow> actions(UUID organizationId, List<UUID> storeIds, String state, int limit) {
        if (storeIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(ACTION_SELECT + """
                 WHERE a.organization_id = :org AND a.store_id IN (:stores) AND (:state IS NULL OR a.state = :state)
                 ORDER BY a.updated_at DESC LIMIT :limit
                """).param("org", organizationId).param("stores", storeIds)
                .param("state", new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.VARCHAR, state))
                .param("limit", limit).query(ListingActionRepository::mapAction).list();
    }

    public boolean moveAction(UUID id, String to, long expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE ops.lc_action SET state = :to, updated_at = :now, version = version + 1
                 WHERE id = :id AND version = :version
                """).param("id", id).param("to", to).param("version", expectedVersion).param("now", Timestamp.from(now))
                .update() == 1;
    }

    private static final String ACTION_SELECT = """
            SELECT a.id, a.organization_id, a.store_id, a.platform_listing_id, a.candidate_id, a.recommendation_id,
                   a.affected_set_id, a.affected_set_digest, a.action_kind, a.execution_path,
                   a.current_description_observation_id, a.current_text_digest, a.target_text, a.target_text_digest,
                   a.kiz_marked_declared, a.content_axis_material, a.exposure_axis_material, a.materiality_route,
                   a.calibration_package_id, a.calibration_version, a.author_user_id, a.state, a.created_at, a.updated_at,
                   a.version, a.restores_command_id, a.promotion_terms_digest
              FROM ops.lc_action a
            """;

    private static ActionRow mapAction(ResultSet rs, int n) throws SQLException {
        return new ActionRow(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("store_id", UUID.class), rs.getObject("platform_listing_id", UUID.class),
                rs.getObject("candidate_id", UUID.class), rs.getObject("recommendation_id", UUID.class),
                rs.getObject("affected_set_id", UUID.class), rs.getString("affected_set_digest"),
                rs.getString("action_kind"), rs.getString("execution_path"),
                rs.getObject("current_description_observation_id", UUID.class), rs.getString("current_text_digest"),
                rs.getString("target_text"), rs.getString("target_text_digest"),
                rs.getObject("kiz_marked_declared", Boolean.class), rs.getObject("content_axis_material", Boolean.class),
                rs.getObject("exposure_axis_material", Boolean.class), rs.getString("materiality_route"),
                rs.getObject("calibration_package_id", UUID.class), rs.getObject("calibration_version", Integer.class),
                rs.getObject("author_user_id", UUID.class), rs.getString("state"),
                ListingFactRepository.instant(rs, "created_at"), ListingFactRepository.instant(rs, "updated_at"),
                rs.getLong("version"),rs.getObject("restores_command_id",UUID.class),rs.getString("promotion_terms_digest"));
    }

    // ------------------------------------------------------------------ reviews, bindings, plans

    public com.mimococo.marketops.listingconversion.MeaningReviewBasis meaningBasis(UUID actionId) {
        return jdbc.sql("""
                SELECT a.id,ops.lc_meaning_review_basis_digest(a.id) AS basis_digest,o.description_text,a.target_text,
                       core.lc_meaning_catalog(a.calibration_package_id,a.action_kind)::text AS conditions,
                       a.promotion_terms::text AS terms
                FROM ops.lc_action a LEFT JOIN core.lc_description_observation o ON o.id=a.current_description_observation_id
                WHERE a.id=:id
                """).param("id",actionId).query((rs,n)->{
                    var conditions=new ArrayList<com.mimococo.marketops.listingconversion.MeaningReviewBasis.Condition>();
                    if (rs.getString("conditions")!=null) for (var item:json.readTree(rs.getString("conditions"))) {
                        conditions.add(new com.mimococo.marketops.listingconversion.MeaningReviewBasis.Condition(
                                item.path("code").asText(),item.path("condition").asText(),item.path("axis").asText()));
                    }
                    return new com.mimococo.marketops.listingconversion.MeaningReviewBasis(actionId,rs.getString("basis_digest"),
                            conditions.isEmpty()?"MEANING_RULE_UNQUALIFIED":"QUALIFIED",rs.getString("description_text"),rs.getString("target_text"),
                            rs.getString("terms")==null?null:json.readValue(rs.getString("terms"),com.mimococo.marketops.listingconversion.PromotionTerms.class),conditions);
                }).single();
    }

    public Boolean meaningAxis(UUID actionId,com.mimococo.marketops.listingconversion.MeaningAssessment assessment) {
        return jdbc.sql("SELECT ops.lc_review_meaning_axis(:id,CAST(:assessment AS jsonb))")
                .param("id",actionId).param("assessment",assessment==null?null:json.writeValueAsString(assessment))
                .query((rs,n)->rs.getObject(1,Boolean.class)).optional().orElse(null);
    }

    public boolean applyReviewedClassification(ActionRow action,
            com.mimococo.marketops.listingconversion.internal.domain.MaterialityClassifier.Classification classification) {
        return jdbc.sql("""
                UPDATE ops.lc_action SET content_axis_material=:meaning,exposure_axis_material=:exposure,materiality_route=:route
                WHERE id=:id AND state='DRAFT' AND version=:version
                """).param("id",action.id()).param("version",action.version())
                .param("meaning",classification.contentAxisMaterial()).param("exposure",classification.exposureAxisMaterial())
                .param("route",classification.route().name()).update()==1;
    }

    public void insertReview(UUID id, UUID organizationId, UUID actionId, UUID reviewerUserId, String targetDigest,
                             String currentDigest, String affectedSetDigest, String factsDigest, String verdict,
                             String reason, Instant now,com.mimococo.marketops.listingconversion.MeaningAssessment assessment,
                             Map<String,Object> exposureEvidence,
                             com.mimococo.marketops.listingconversion.internal.domain.MaterialityClassifier.Classification classification) {
        jdbc.sql("""
                INSERT INTO ops.lc_action_review (id, organization_id, action_id, reviewer_user_id, attested_target_text_digest,
                    attested_current_text_digest, attested_affected_set_digest, facts_digest, verdict, reason, reviewed_at,
                    meaning_assessment,exposure_evidence,content_axis_material,exposure_axis_material,materiality_route)
                VALUES (:id, :org, :action, :reviewer, :target, :current, :set, :facts, :verdict, :reason, :now,
                    CAST(:meaning AS jsonb),CAST(:exposureEvidence AS jsonb),:meaningAxis,:exposureAxis,:route)
                """).param("id", id).param("org", organizationId).param("action", actionId).param("reviewer", reviewerUserId)
                .param("target", targetDigest).param("current", currentDigest).param("set", affectedSetDigest)
                .param("facts", factsDigest).param("verdict", verdict).param("reason", reason)
                .param("now", Timestamp.from(now)).param("meaning",assessment==null?null:json.writeValueAsString(assessment))
                .param("exposureEvidence",json.writeValueAsString(exposureEvidence))
                .param("meaningAxis",classification==null?null:classification.contentAxisMaterial())
                .param("exposureAxis",classification==null?null:classification.exposureAxisMaterial())
                .param("route",classification==null?null:classification.route().name()).update();
    }

    public List<ListingActionView.Review> reviews(UUID actionId) {
        return jdbc.sql("""
                SELECT id, reviewer_user_id, verdict, reason, reviewed_at, evaluation_plan_digest FROM ops.lc_action_review
                 WHERE action_id = :action ORDER BY reviewed_at DESC
                """).param("action", actionId)
                .query((rs, n) -> new ListingActionView.Review(rs.getObject("id", UUID.class),
                        rs.getObject("reviewer_user_id", UUID.class), rs.getString("verdict"), rs.getString("reason"),
                        ListingFactRepository.instant(rs, "reviewed_at"), rs.getString("evaluation_plan_digest")))
                .list();
    }

    /** The database's own description of what a decision on this recommendation rests on. */
    public Optional<String> authoritySnapshot(UUID recommendationId) {
        return jdbc.sql("SELECT ops.lc_authority_snapshot(:id)::text").param("id", recommendationId)
                .query(String.class).optional();
    }

    public Optional<UUID> attestingReviewer(UUID actionId) {
        return jdbc.sql("""
                SELECT reviewer_user_id FROM ops.lc_action_review WHERE action_id = :action AND verdict = 'ATTESTED'
                 ORDER BY reviewed_at DESC LIMIT 1
                """).param("action", actionId).query(UUID.class).optional();
    }

    public void insertBinding(UUID id, UUID organizationId, UUID actionId, UUID approvalDecisionId, UUID guardrailId,
                              String targetDigest, String currentDigest, String affectedSetDigest, String path,
                              Map<String, String> evidenceVersions, Map<String, String> ruleVersions,
                              UUID calibrationPackageId, int calibrationVersion, String bindingDigest, Instant boundAt,
                              Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO ops.lc_action_binding (id, organization_id, action_id, approval_decision_id, guardrail_evaluation_id,
                    target_text_digest, current_text_digest, affected_set_digest, execution_path, evidence_versions, rule_versions,
                    calibration_package_id, calibration_version, binding_digest, bound_at, expires_at, state)
                VALUES (:id, :org, :action, :approval, :guardrail, :target, :current, :set, :path, CAST(:evidence AS jsonb),
                    CAST(:rules AS jsonb), :package, :packageVersion, :digest, :bound, :expires, 'BOUND')
                """).param("id", id).param("org", organizationId).param("action", actionId).param("approval", approvalDecisionId)
                .param("guardrail", guardrailId).param("target", targetDigest).param("current", currentDigest)
                .param("set", affectedSetDigest).param("path", path).param("evidence", json.writeValueAsString(evidenceVersions))
                .param("rules", json.writeValueAsString(ruleVersions)).param("package", calibrationPackageId)
                .param("packageVersion", calibrationVersion).param("digest", bindingDigest)
                .param("bound", Timestamp.from(boundAt)).param("expires", Timestamp.from(expiresAt)).update();
    }

    public Optional<ListingActionView.Binding> binding(UUID actionId) {
        return jdbc.sql("""
                SELECT id, approval_decision_id, guardrail_evaluation_id, binding_digest, bound_at, expires_at, state,
                       inapplicable_reason, evaluation_plan_digest
                  FROM ops.lc_action_binding WHERE action_id = :action
                """).param("action", actionId)
                .query((rs, n) -> new ListingActionView.Binding(rs.getObject("id", UUID.class),
                        rs.getObject("approval_decision_id", UUID.class), rs.getObject("guardrail_evaluation_id", UUID.class),
                        rs.getString("binding_digest"), ListingFactRepository.instant(rs, "bound_at"),
                        ListingFactRepository.instant(rs, "expires_at"), rs.getString("state"),
                        rs.getString("inapplicable_reason"), rs.getString("evaluation_plan_digest")))
                .optional();
    }

    public void markBindingInapplicable(UUID actionId, String reason, Instant now) {
        jdbc.sql("""
                UPDATE ops.lc_action_binding SET state = 'INAPPLICABLE', inapplicable_reason = :reason, inapplicable_at = :now
                 WHERE action_id = :action AND state = 'BOUND'
                """).param("action", actionId).param("reason", reason).param("now", Timestamp.from(now)).update();
    }

    public List<String> bindingGaps(UUID actionId) {
        String joined = jdbc.sql("SELECT array_to_string(ops.lc_binding_gaps(:action), ',')").param("action", actionId)
                .query(String.class).single();
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split(","));
    }

    public void insertPlan(UUID id, UUID organizationId, UUID actionId, UUID calibrationPackageId, int calibrationVersion,
                           Map<String, String> coverage, Instant latestBoundary, List<Map<String, Object>> nodes,
                           Map<String, Object> stopRule, List<JsonNode> criticalGroups, String comparisonBasis,
                           int crossPeriodWindowDays, String planDigest, Instant now) {
        jdbc.sql("""
                INSERT INTO ops.lc_evaluation_plan (id, organization_id, action_id, calibration_package_id, calibration_version,
                    version_coverage, transition_handling, latest_boundary, formal_nodes, stop_rule, critical_groups,
                    comparison_basis, cross_period_window_days, plan_digest, frozen_at)
                VALUES (:id, :org, :action, :package, :packageVersion, CAST(:coverage AS jsonb), 'EXCLUDE_TRANSITION_DAYS',
                    :boundary, CAST(:nodes AS jsonb), CAST(:stop AS jsonb), CAST(:groups AS jsonb), :basis, :window, :digest, :now)
                """).param("id", id).param("org", organizationId).param("action", actionId).param("package", calibrationPackageId)
                .param("packageVersion", calibrationVersion).param("coverage", json.writeValueAsString(coverage))
                .param("boundary", Timestamp.from(latestBoundary)).param("nodes", json.writeValueAsString(nodes))
                .param("stop", json.writeValueAsString(stopRule)).param("groups", json.writeValueAsString(criticalGroups))
                .param("basis", comparisonBasis).param("window", crossPeriodWindowDays).param("digest", planDigest)
                .param("now", Timestamp.from(now)).update();
    }

    public Optional<UUID> planId(UUID actionId) {
        return jdbc.sql("SELECT id FROM ops.lc_evaluation_plan WHERE action_id = :action").param("action", actionId)
                .query(UUID.class).optional();
    }

    // ------------------------------------------------------------------ launch, occupation, allowance

    public Optional<ListingActionView.Launch> launch(UUID actionId) {
        return jdbc.sql("SELECT id, launched_by_user_id, launched_at FROM ops.lc_launch WHERE action_id = :action")
                .param("action", actionId)
                .query((rs, n) -> new ListingActionView.Launch(rs.getObject("id", UUID.class),
                        rs.getObject("launched_by_user_id", UUID.class), ListingFactRepository.instant(rs, "launched_at")))
                .optional();
    }

    public List<ListingActionView.Occupation> occupations(UUID actionId) {
        return jdbc.sql("""
                SELECT id, axis_code, requested_value, occupied_value, state, acquired_at, released_at, release_basis
                  FROM ops.lc_exposure_occupation WHERE action_id = :action ORDER BY acquired_at
                """).param("action", actionId)
                .query((rs, n) -> new ListingActionView.Occupation(rs.getObject("id", UUID.class), rs.getString("axis_code"),
                        rs.getBigDecimal("requested_value"), rs.getBigDecimal("occupied_value"), rs.getString("state"),
                        ListingFactRepository.instant(rs, "acquired_at"), ListingFactRepository.instant(rs, "released_at"),
                        rs.getString("release_basis")))
                .list();
    }

    public AllowanceView allowanceProjection(UUID actionId, Instant at) {
        String projection = jdbc.sql("SELECT ops.lc_allowance_projection(:action,:at)::text")
                .param("action",actionId).param("at",Timestamp.from(at)).query(String.class).single();
        JsonNode value = JsonValues.read(json,projection);
        List<AllowanceView.Axis> axes = new java.util.ArrayList<>();
        for (JsonNode axis : value.path("axes")) {
            axes.add(new AllowanceView.Axis(UUID.fromString(axis.path("allowanceId").asText()),
                    axis.path("axisCode").asText(),axis.path("scopeKind").asText(),
                    axis.path("limitValue").decimalValue(),axis.path("reserveValue").decimalValue(),
                    axis.path("occupiedValue").decimalValue(),
                    axis.path("requestedValue").isNull()?null:axis.path("requestedValue").decimalValue(),
                    axis.path("headroom").decimalValue(),axis.path("sufficient").asBoolean(),axis.path("unitCode").asText()));
        }
        List<String> gaps = new java.util.ArrayList<>();
        value.path("gaps").forEach(gap->gaps.add(gap.asText()));
        return new AllowanceView(UUID.fromString(value.path("platformListingId").asText()),axes,
                value.path("resolved").asBoolean(),gaps);
    }

    public record RecommendationRow(String state, long version) {
    }

    public Optional<RecommendationRow> recommendation(UUID recommendationId) {
        return jdbc.sql("SELECT state, version FROM ops.recommendation WHERE id = :id").param("id", recommendationId)
                .query((rs, n) -> new RecommendationRow(rs.getString("state"), rs.getLong("version"))).optional();
    }

    public Optional<Instant> approvalExpiry(UUID approvalDecisionId) {
        return jdbc.sql("SELECT scope_expires_at FROM ops.approval_decision WHERE id = :id").param("id", approvalDecisionId)
                .query((rs, n) -> ListingFactRepository.instant(rs, "scope_expires_at")).optional();
    }

    public boolean scopeContained(UUID organizationId, UUID listingId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT ops.lc_scope_contained(:org, :listing)")
                .param("org", organizationId).param("listing", listingId).query(Boolean.class).single());
    }

    public Optional<String> latestHealthNecessaryState(UUID listingId) {
        return jdbc.sql("""
                SELECT necessary_state FROM mart.lc_listing_health WHERE platform_listing_id = :listing
                 ORDER BY health_version DESC LIMIT 1
                """).param("listing", listingId).query(String.class).optional();
    }

    static Map<String, String> stringMap(String jsonText) {
        Map<String, String> out = new LinkedHashMap<>();
        if (jsonText == null) {
            return out;
        }
        JsonNode node = JsonValues.read(tools.jackson.databind.json.JsonMapper.builder().build(), jsonText);
        node.properties().forEach(entry -> out.put(entry.getKey(),
                entry.getValue().isValueNode() ? entry.getValue().asText() : entry.getValue().toString()));
        return out;
    }

    public static ListingActionState stateOf(String value) {
        return ListingActionState.valueOf(value);
    }
}
