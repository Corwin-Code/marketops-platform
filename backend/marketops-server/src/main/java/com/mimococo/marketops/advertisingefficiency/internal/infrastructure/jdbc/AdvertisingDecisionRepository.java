package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What an approved bid change consists of, resolved in one question.
 *
 * <p>One query rather than six, because six answers read at six instants can
 * describe a decision that never existed: a candidate from before the bid moved,
 * a reservation released while the bundle was being read. This asks once, and
 * every element it cannot resolve comes back as a null the caller must account
 * for rather than a row that quietly disappears.
 */
@Repository
public class AdvertisingDecisionRepository {

    private final JdbcClient jdbc;

    AdvertisingDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The decision scope for one recommendation, with unresolved elements null.
     *
     * <p>The bundle, reservation, approval and lease are resolved by the same
     * predicates the write gate uses. Two copies of a rule is a cost, and the
     * alternative is worse: an operator told a command may be created, by a
     * looser rule, and then told at the gate that it may not.
     */
    public Optional<DecisionRow> resolve(UUID recommendationId) {
        return jdbc.sql("""
                SELECT r.id                       AS recommendation_id,
                       r.organization_id,
                       r.store_id,
                       r.subject_id               AS ad_native_object_id,
                       r.state,
                       r.valid_until,
                       r.version,
                       candidate.id               AS candidate_id,
                       candidate.case_id,
                       kase.lane,
                       candidate.direction,
                       candidate.candidate_basis,
                       candidate.current_bid_amount,
                       candidate.provider_normalized_amount,
                       candidate.currency_code,
                       candidate.bid_unit_code,
                       object.control_granularity_state,
                       object.status              AS object_status,
                       object.platform_code,
                       configuration.observed_bid_amount,
                       bundle.id                  AS bundle_id,
                       sealed.expires_at AS scope_expires_at,
                       lease.lease_seconds,
                       lease.material_lease_seconds
                  FROM ops.recommendation r
                  LEFT JOIN ops.ad_bid_candidate candidate
                    ON candidate.organization_id = r.organization_id
                   AND candidate.id = CASE
                           WHEN ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
                           THEN (r.proposed_parameters ->> 'candidateId')::uuid END
                  LEFT JOIN mart.ad_case kase
                    ON kase.id = candidate.case_id
                   AND kase.organization_id = candidate.organization_id
                  LEFT JOIN core.ad_native_object object
                    ON object.id = r.subject_id AND object.organization_id = r.organization_id
                  LEFT JOIN LATERAL (
                        SELECT c.observed_bid_amount
                          FROM core.ad_object_configuration_observation c
                         WHERE c.ad_native_object_id = object.id
                           AND c.organization_id = object.organization_id
                           AND NOT EXISTS (
                                SELECT 1 FROM core.ad_object_configuration_observation later
                                 WHERE later.supersedes_observation_id = c.id)
                         ORDER BY c.observed_at DESC, c.id DESC LIMIT 1) configuration ON true
                  LEFT JOIN LATERAL (
                        SELECT b.id
                          FROM ops.ad_decision_policy_bundle b
                         WHERE b.organization_id = r.organization_id
                           AND b.store_id = r.store_id
                           AND b.capability_code = 'ad-bid-change'
                           AND b.direction = candidate.direction
                           AND b.candidate_basis = candidate.candidate_basis
                           AND b.native_object_kind = object.native_object_kind
                           AND b.status = 'ACTIVE'
                           AND b.validation_state = 'VALIDATED'
                           AND b.effective_from <= statement_timestamp()
                           AND (b.effective_to IS NULL
                                OR b.effective_to > statement_timestamp())
                         LIMIT 1) bundle ON true
                  LEFT JOIN ops.ad_action_authorization sealed ON sealed.recommendation_id=r.id
                  LEFT JOIN LATERAL (
                        SELECT a.scope_expires_at
                          FROM ops.approval_decision a
                         WHERE a.recommendation_id = r.id
                           AND a.decision IN ('APPROVED', 'POLICY_AUTHORIZED')
                           AND a.scope_expires_at > statement_timestamp()
                         ORDER BY a.decided_at DESC LIMIT 1) approval ON true
                  LEFT JOIN LATERAL (
                        SELECT p.lease_seconds, p.material_lease_seconds
                          FROM core.ad_approval_lease_policy p
                         WHERE p.organization_id = r.organization_id
                           AND p.direction = candidate.direction
                           AND p.status = 'ACTIVE'
                           AND (p.scope_kind='ORGANIZATION' OR p.scope_kind='PLATFORM'
                                AND p.platform_code=object.platform_code OR p.scope_kind='STORE'
                                AND p.store_ref_id=r.store_id)
                           AND p.effective_from <= statement_timestamp()
                           AND (p.effective_to IS NULL
                                OR p.effective_to > statement_timestamp())
                         ORDER BY CASE p.scope_kind WHEN 'STORE' THEN 1
                                                    WHEN 'PLATFORM' THEN 2 ELSE 3 END,
                                  p.effective_from DESC LIMIT 1) lease ON true
                 WHERE r.id = :recommendationId
                   AND r.action_kind = 'AD_BID_CHANGE'
                   AND r.subject_kind = 'AD_NATIVE_OBJECT'
                """)
                .param("recommendationId", recommendationId)
                .query(AdvertisingDecisionRepository::mapDecision)
                .optional();
    }

    /**
     * Whether more than one bundle claims this scope.
     *
     * <p>A second complete active bundle for one scope is not a tie to be broken.
     * Two of them mean two different sets of policy could have produced the same
     * decision, and which one applied would depend on the ordering of a query.
     */
    public boolean bundleIsAmbiguous(UUID recommendationId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT count(*) > 1
                  FROM ops.recommendation r
                  JOIN ops.ad_bid_candidate candidate
                    ON candidate.organization_id = r.organization_id
                   AND candidate.id = CASE
                           WHEN ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
                           THEN (r.proposed_parameters ->> 'candidateId')::uuid END
                  JOIN core.ad_native_object object
                    ON object.id = r.subject_id AND object.organization_id = r.organization_id
                  JOIN ops.ad_decision_policy_bundle b
                    ON b.organization_id = r.organization_id
                   AND b.store_id = r.store_id
                   AND b.capability_code = 'ad-bid-change'
                   AND b.direction = candidate.direction
                   AND b.candidate_basis = candidate.candidate_basis
                   AND b.native_object_kind = object.native_object_kind
                   AND b.status = 'ACTIVE'
                   AND b.validation_state = 'VALIDATED'
                   AND b.effective_from <= statement_timestamp()
                   AND (b.effective_to IS NULL OR b.effective_to > statement_timestamp())
                 WHERE r.id = :recommendationId
                """)
                .param("recommendationId", recommendationId)
                .query(Boolean.class)
                .single());
    }

    /**
     * What the advertising calculation says about one proposed bid change.
     *
     * <p>Read from the case that produced the candidate rather than recomputed.
     * A second calculation at preview time could disagree with the one the case
     * was built from, and then the operator, the guardrail and the gate would be
     * looking at three answers.
     */
    public Optional<ProjectionRow> projection(UUID recommendationId) {
        return jdbc.sql("""
                SELECT r.id                        AS recommendation_id,
                       r.organization_id,
                       r.store_id,
                       r.subject_id                AS ad_native_object_id,
                       r.entity_version_digest,
                       kase.id                     AS case_id,
                       kase.lane,
                       kase.protection_tier,
                       kase.cause_code,
                       kase.evidence_state,
                       kase.confidence_state,
                       kase.blocker_codes,
                       kase.max_cpc_state,
                       kase.max_cpc_amount,
                       kase.attribution_gap_ratio,
                       candidate.direction,
                       candidate.candidate_basis,
                       candidate.current_bid_amount,
                       candidate.provider_normalized_amount,
                       candidate.currency_code,
                       candidate.bid_unit_code,
                       candidate.affected_set_digest,
                       affected.variant_count,
                       configuration.observed_bid_amount,
                       bundle.id             AS bundle_id,
                       bundle.bundle_version
                  FROM ops.recommendation r
                  LEFT JOIN ops.ad_bid_candidate candidate
                    ON candidate.organization_id = r.organization_id
                   AND candidate.id = CASE
                           WHEN ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
                           THEN (r.proposed_parameters ->> 'candidateId')::uuid END
                  LEFT JOIN mart.ad_case kase
                    ON kase.id = candidate.case_id
                   AND kase.organization_id = candidate.organization_id
                  LEFT JOIN LATERAL (
                        SELECT cardinality(a.product_variant_ids) AS variant_count
                          FROM core.ad_affected_set a
                         WHERE a.organization_id = r.organization_id
                           AND a.affected_set_digest = candidate.affected_set_digest
                         ORDER BY a.resolved_at DESC LIMIT 1) affected ON true
                  LEFT JOIN core.ad_native_object object
                    ON object.id = r.subject_id AND object.organization_id = r.organization_id
                  LEFT JOIN LATERAL (
                        SELECT c.observed_bid_amount
                          FROM core.ad_object_configuration_observation c
                         WHERE c.ad_native_object_id = object.id
                           AND c.organization_id = object.organization_id
                           AND NOT EXISTS (
                                SELECT 1 FROM core.ad_object_configuration_observation later
                                 WHERE later.supersedes_observation_id = c.id)
                         ORDER BY c.observed_at DESC, c.id DESC LIMIT 1) configuration ON true
                  LEFT JOIN LATERAL (
                        SELECT b.id, b.bundle_version
                          FROM ops.ad_decision_policy_bundle b
                         WHERE b.organization_id = r.organization_id
                           AND b.store_id = r.store_id
                           AND b.capability_code = 'ad-bid-change'
                           AND b.direction = candidate.direction
                           AND b.candidate_basis = candidate.candidate_basis
                           AND b.native_object_kind = object.native_object_kind
                           AND b.status = 'ACTIVE'
                           AND b.validation_state = 'VALIDATED'
                           AND b.effective_from <= statement_timestamp()
                           AND (b.effective_to IS NULL
                                OR b.effective_to > statement_timestamp())
                         LIMIT 1) bundle ON true
                 WHERE r.id = :recommendationId
                   AND r.action_kind = 'AD_BID_CHANGE'
                   AND r.subject_kind = 'AD_NATIVE_OBJECT'
                """)
                .param("recommendationId", recommendationId)
                .query(AdvertisingDecisionRepository::mapProjection)
                .optional();
    }

    /**
     * Aggregate exposure axes with no headroom left, by name.
     *
     * <p>Each axis is asked independently, in the same order and with the same
     * predicates the write gate uses. No axis lends slack to another, so an
     * organization at its unresolved-write limit is stopped even with plenty of
     * cumulative-change budget.
     */
    public List<String> exhaustedExposureAxes(UUID organizationId, String direction) {
        return jdbc.sql("""
                WITH envelope AS (
                    SELECT e.*
                      FROM core.ad_exposure_envelope e
                     WHERE e.organization_id = :organizationId
                       AND e.status IN ('ACTIVE', 'RETIRED')
                       AND e.effective_from <= statement_timestamp()
                       AND (e.effective_to IS NULL OR e.effective_to > statement_timestamp())
                     ORDER BY CASE e.scope_kind WHEN 'STORE' THEN 1
                                                WHEN 'PLATFORM' THEN 2 ELSE 3 END,
                              e.effective_from DESC
                     LIMIT 1)
                SELECT axis FROM (
                    SELECT 'AGGREGATE_ENVELOPE_UNRESOLVED' AS axis, ordinality
                      FROM (SELECT 1 AS ordinality) o
                     WHERE NOT EXISTS (SELECT 1 FROM envelope)
                    UNION ALL
                    SELECT 'ACTIVE_INTERVENTIONS', 2 FROM envelope e
                     WHERE (SELECT count(*) FROM ops.ad_action_reservation res
                             WHERE res.organization_id = :organizationId
                               AND res.state = 'ACTIVE')
                           > CASE WHEN :direction = 'EXACT_PRIOR_BID_COMPENSATION'
                                  THEN e.max_active_interventions
                                  ELSE e.max_active_interventions
                                       - e.reserved_recovery_headroom_count END
                    UNION ALL
                    SELECT 'UNRESOLVED_TRANSMITTED_WRITES', 3 FROM envelope e
                     WHERE (SELECT count(*) FROM ops.ad_bid_command other
                             WHERE other.organization_id = :organizationId
                               AND other.state IN ('UNKNOWN_REQUIRES_READBACK',
                                   'READBACK_MISMATCH',
                                   'LATER_CHANGE_OR_MISMATCH_INVESTIGATION',
                                   'MANUAL_RESOLUTION'))
                           > e.max_unresolved_transmitted_writes
                    UNION ALL
                    SELECT 'CUMULATIVE_BID_CHANGE', 4 FROM envelope e
                     WHERE (SELECT coalesce(sum(abs(other.target_bid_amount
                                                    - other.prior_bid_amount)), 0)
                              FROM ops.ad_bid_command other
                             WHERE other.organization_id = :organizationId
                               AND other.created_at > statement_timestamp()
                                   - make_interval(hours => e.cumulative_window_hours))
                           >= e.max_cumulative_bid_change_amount) axes
                 ORDER BY ordinality
                """)
                .param("organizationId", organizationId)
                .param("direction", direction)
                .query(String.class)
                .list();
    }

    /** An amount without its exact candidate, scope and Policy cannot classify materiality. */
    public String materialityRoute(UUID organizationId, java.math.BigDecimal changeAmount) {
        return "MATERIALITY_UNRESOLVED";
    }

    public String materialityRouteForRecommendation(UUID recommendationId) {
        return jdbc.sql("""
                SELECT CASE WHEN bundle.id IS NULL OR materiality.id IS NULL
                            THEN 'MATERIALITY_UNRESOLVED'
                            ELSE ops.ad_materiality_assessment(bundle.id,candidate.id)->>'route' END
                  FROM ops.recommendation r
                  JOIN ops.ad_bid_candidate candidate
                    ON candidate.id=CASE WHEN ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
                         THEN (r.proposed_parameters->>'candidateId')::uuid END
                  LEFT JOIN ops.ad_candidate_selection selection ON selection.recommendation_id=r.id
                  LEFT JOIN LATERAL (
                    SELECT b.* FROM ops.ad_decision_policy_bundle b
                    WHERE b.status='ACTIVE' AND b.validation_state='VALIDATED'
                      AND b.effective_from<=statement_timestamp() AND (b.effective_to IS NULL OR b.effective_to>statement_timestamp())
                      AND b.organization_id=r.organization_id AND b.store_id=r.store_id
                      AND b.semantic_profile_id=candidate.semantic_profile_id AND b.target_policy_id=candidate.target_policy_id
                      AND b.direction=candidate.direction AND b.candidate_basis=candidate.candidate_basis
                      AND (selection.id IS NULL OR b.id=selection.bundle_id AND b.bundle_version=selection.bundle_version)
                      AND (selection.id IS NOT NULL OR (SELECT count(*) FROM ops.ad_decision_policy_bundle sibling
                        WHERE sibling.organization_id=r.organization_id AND sibling.store_id=r.store_id
                          AND sibling.semantic_profile_id=candidate.semantic_profile_id AND sibling.target_policy_id=candidate.target_policy_id
                          AND sibling.direction=candidate.direction AND sibling.candidate_basis=candidate.candidate_basis
                          AND sibling.status='ACTIVE' AND sibling.validation_state='VALIDATED'
                          AND sibling.effective_from<=statement_timestamp()
                          AND (sibling.effective_to IS NULL OR sibling.effective_to>statement_timestamp()))=1)
                  ) bundle ON true
                  LEFT JOIN core.ad_materiality_policy materiality ON materiality.id=bundle.materiality_policy_id
                    AND materiality.status='ACTIVE' AND materiality.effective_from<=statement_timestamp()
                    AND (materiality.effective_to IS NULL OR materiality.effective_to>statement_timestamp())
                 WHERE r.id=:recommendationId
                """).param("recommendationId", recommendationId).query(String.class)
                .optional().orElse("MATERIALITY_UNRESOLVED");
    }

    /** Read the same committed cross-domain authority as reservation and transmission. */
    public List<String> isolationFailures(UUID recommendationId, Instant at) {
        return jdbc.sql("""
                SELECT unnest(ops.ad_action_isolation_failures(c.affected_set_id,baseline.id,:at))
                FROM ops.recommendation r JOIN ops.ad_bid_candidate candidate
                  ON candidate.id=CASE WHEN ops.ad_bid_parameter_contract_is_valid(r.proposed_parameters)
                     THEN (r.proposed_parameters->>'candidateId')::uuid END
                JOIN mart.ad_case c ON c.id=candidate.case_id
                LEFT JOIN ops.ad_candidate_selection selected ON selected.recommendation_id=r.id
                LEFT JOIN LATERAL (SELECT b.id FROM ops.ad_outcome_baseline b
                  WHERE b.candidate_id=candidate.id
                    AND (selected.id IS NULL OR b.id=selected.outcome_baseline_id)
                    AND b.case_calculation_id=c.calculation_id AND b.policy_version_digest=c.policy_version_digest
                    AND b.prepared_at<=:at AND b.valid_until>:at
                  ORDER BY b.prepared_at DESC,b.id DESC LIMIT 1) baseline ON true
                WHERE r.id=:recommendation AND r.action_kind='AD_BID_CHANGE'
                """).param("recommendation",recommendationId).param("at",Timestamp.from(at))
                .query(String.class).list();
    }

    /** One case's view of a proposed bid change, with unresolved parts null. */
    public record ProjectionRow(
            UUID recommendationId, UUID organizationId, UUID storeId, UUID adNativeObjectId,
            String entityVersionDigest, UUID caseId, String lane, String protectionTier,
            String causeCode, String evidenceState, String confidenceState,
            List<String> blockerCodes, String maxCpcState, BigDecimal maxCpcAmount,
            BigDecimal attributionGapRatio, String direction, String candidateBasis,
            BigDecimal currentBidAmount, BigDecimal targetBidAmount, String currencyCode,
            String bidUnitCode, String affectedSetDigest, Integer affectedVariantCount,
            BigDecimal observedBidAmount, UUID bundleId, Integer bundleVersion) {
    }

    private static ProjectionRow mapProjection(ResultSet rs, int index) throws SQLException {
        java.sql.Array blockers = rs.getArray("blocker_codes");
        return new ProjectionRow(
                rs.getObject("recommendation_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getObject("ad_native_object_id", UUID.class),
                rs.getString("entity_version_digest"),
                rs.getObject("case_id", UUID.class),
                rs.getString("lane"),
                rs.getString("protection_tier"),
                rs.getString("cause_code"),
                rs.getString("evidence_state"),
                rs.getString("confidence_state"),
                blockers == null ? List.of() : List.of((String[]) blockers.getArray()),
                rs.getString("max_cpc_state"),
                rs.getBigDecimal("max_cpc_amount"),
                rs.getBigDecimal("attribution_gap_ratio"),
                rs.getString("direction"),
                rs.getString("candidate_basis"),
                rs.getBigDecimal("current_bid_amount"),
                rs.getBigDecimal("provider_normalized_amount"),
                rs.getString("currency_code"),
                rs.getString("bid_unit_code"),
                rs.getString("affected_set_digest"),
                integerOf(rs, "variant_count"),
                rs.getBigDecimal("observed_bid_amount"),
                rs.getObject("bundle_id", UUID.class),
                integerOf(rs, "bundle_version"));
    }

    /**
     * One approved bid change, with every element that could not be resolved
     * present as {@code null}.
     */
    public record DecisionRow(
            UUID recommendationId, UUID organizationId, UUID storeId, UUID adNativeObjectId,
            String recommendationState, Instant validUntil, long version,
            UUID candidateId, UUID caseId, String lane, String direction, String candidateBasis,
            BigDecimal currentBidAmount, BigDecimal targetBidAmount,
            String currencyCode, String bidUnitCode,
            String controlGranularityState, String objectStatus, String platformCode,
            BigDecimal observedBidAmount, UUID bundleId,
            Instant approvalExpiresAt, Integer leaseSeconds, Integer materialLeaseSeconds) {
    }

    private static DecisionRow mapDecision(ResultSet rs, int index) throws SQLException {
        return new DecisionRow(
                rs.getObject("recommendation_id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getObject("ad_native_object_id", UUID.class),
                rs.getString("state"),
                instantOf(rs, "valid_until"),
                rs.getLong("version"),
                rs.getObject("candidate_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getString("lane"),
                rs.getString("direction"),
                rs.getString("candidate_basis"),
                rs.getBigDecimal("current_bid_amount"),
                rs.getBigDecimal("provider_normalized_amount"),
                rs.getString("currency_code"),
                rs.getString("bid_unit_code"),
                rs.getString("control_granularity_state"),
                rs.getString("object_status"),
                rs.getString("platform_code"),
                rs.getBigDecimal("observed_bid_amount"),
                rs.getObject("bundle_id", UUID.class),
                instantOf(rs, "scope_expires_at"),
                integerOf(rs, "lease_seconds"),
                integerOf(rs, "material_lease_seconds"));
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer integerOf(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
