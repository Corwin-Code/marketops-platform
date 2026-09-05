package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import com.mimococo.marketops.advertisingefficiency.internal.domain.ProviderBidGrid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Resolves which governing version was in force at one instant.
 *
 * <p>Every resolver here filters {@code status IN ('ACTIVE','RETIRED')} and the
 * effective interval rather than {@code status = 'ACTIVE'} alone. A version that
 * has since been retired still explains a decision taken while it was in force,
 * and a recalculation of an old instant that silently used today's policy would
 * make the audit trail a work of fiction.
 *
 * <p>Absence is returned as an empty optional, never as a default. A missing
 * conversion definition blocks the purposes that consume it; it does not become
 * a conversion definition somebody never wrote.
 */
@Repository
public class AdvertisingPolicyRepository {

    private final JdbcClient jdbc;

    AdvertisingPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A resolved version: what it is, and which version of it. */
    public record ResolvedVersion(UUID id, int version) {
    }

    /** The conversion definition and the parts of it a calculation consumes. */
    public record ConversionDefinition(
            UUID id, int version, String saleStage, String trafficDenominatorKind,
            String linkageBasis, BigDecimal minimumLinkageCoverageRatio,
            BigDecimal minimumAffectedSetCoverageRatio, int minimumSampleEvents,
            BigDecimal maximumAttributionGapRatio, int observationWindowDays) {
    }

    /** The stage-bound economic ceiling inputs. */
    public record AllowableCpaDefinition(
            UUID id, int version, String saleStage, String currencyCode,
            String contributionBasis, BigDecimal targetContributionRetentionRatio,
            String returnLossTreatment) {
    }

    /** One purpose tier of the optimization qualification policy. */
    public record QualificationPolicy(
            UUID id, int version, String purposeTier, int eligibleObservationWindowDays,
            BigDecimal minimumSourceCoverageRatio, BigDecimal minimumAffectedSetCoverageRatio,
            long minimumTrafficDenominator, int minimumCompletedSaleEvents,
            int minimumRetainedSaleEvents, BigDecimal minimumSpendAmount, String currencyCode,
            int minimumSustainedPeriods, BigDecimal minimumRecoverableAmount,
            boolean requiresCorrectionWindowClosed, boolean requiresComparableBaseline,
            String minimumConfidenceState) {
    }

    /** The intra-tier rank weights. */
    public record PriorityWeights(
            UUID id, int version, BigDecimal profitLossWeight, BigDecimal spendExposureWeight,
            BigDecimal criticalSalesWeight, BigDecimal recoverableProfitWeight,
            BigDecimal evidenceMaturityWeight, BigDecimal ageWeight, BigDecimal confidenceWeight) {
    }

    /** The two-stage human response bounds for one lane. */
    public record HumanSlo(
            UUID id, int version, String lane, int acknowledgementMinutes, int actionMinutes,
            int escalationMinutes, boolean staffedCoverageEnabled, String staffedCoverageTimezone,
            Integer staffedCoverageStartMinute, Integer staffedCoverageEndMinute,
            int outOfCoverageVisibleFromMinutes) {
    }

    /** One purpose's freshness rule for one evidence kind. */
    public record FreshnessProfile(
            UUID id, int version, String evidenceKind, String decisionPurpose,
            Integer sourceMaxAgeMinutes, Integer acceptedFactMaxAgeMinutes,
            int expectedPublicationLagMinutes, int correctionWindowMinutes,
            boolean requiresWindowComplete, boolean requiresCorrectionWindowClosed,
            BigDecimal minimumCoverageRatio, String minimumConfidenceState,
            boolean providerIncidentBlocks, Instant effectiveTo, String authorityDigest) {
        public FreshnessProfile(UUID id,int version,String evidenceKind,String decisionPurpose,Integer sourceMaxAgeMinutes,
                Integer acceptedFactMaxAgeMinutes,int expectedPublicationLagMinutes,int correctionWindowMinutes,
                boolean requiresWindowComplete,boolean requiresCorrectionWindowClosed,BigDecimal minimumCoverageRatio,
                String minimumConfidenceState,boolean providerIncidentBlocks,Instant effectiveTo) {
            this(id,version,evidenceKind,decisionPurpose,sourceMaxAgeMinutes,acceptedFactMaxAgeMinutes,expectedPublicationLagMinutes,
                    correctionWindowMinutes,requiresWindowComplete,requiresCorrectionWindowClosed,minimumCoverageRatio,
                    minimumConfidenceState,providerIncidentBlocks,effectiveTo,null);
        }
        public FreshnessProfile(UUID id, int version, String evidenceKind, String decisionPurpose,
                Integer sourceMaxAgeMinutes, Integer acceptedFactMaxAgeMinutes, int expectedPublicationLagMinutes,
                int correctionWindowMinutes, boolean requiresWindowComplete, boolean requiresCorrectionWindowClosed,
                BigDecimal minimumCoverageRatio, String minimumConfidenceState, boolean providerIncidentBlocks) {
            this(id, version, evidenceKind, decisionPurpose, sourceMaxAgeMinutes, acceptedFactMaxAgeMinutes,
                    expectedPublicationLagMinutes, correctionWindowMinutes, requiresWindowComplete,
                    requiresCorrectionWindowClosed, minimumCoverageRatio, minimumConfidenceState, providerIncidentBlocks, null);
        }
    }

    private static final String IN_FORCE =
            " AND status IN ('ACTIVE','RETIRED') AND effective_from <= :at"
                    + " AND (effective_to IS NULL OR effective_to > :at)";

    /** Resolve the stage from the exact active bundle, or one unambiguous shadow definition. */
    private static String uniqueEffectiveScope(String table) {
        String rowName=table.substring(table.lastIndexOf('.')+1);
        String columns = "scope_kind,platform_code,store_ref_id,product_variant_ref_id,semantic_profile_id,sale_stage,purpose_tier,evidence_kind,decision_purpose";
        String same = java.util.Arrays.stream(columns.split(","))
                .map(column -> "to_jsonb(other)->'" + column + "' IS NOT DISTINCT FROM to_jsonb(" + rowName + ")->'" + column + "'")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String ambiguity=" AND NOT EXISTS (SELECT 1 FROM " + table + " other WHERE other.organization_id = :organizationId"
                + " AND other.id <> " + table + ".id AND " + same
                + " AND other.status IN ('ACTIVE','RETIRED') AND other.effective_from <= :at"
                + " AND (other.effective_to IS NULL OR other.effective_to > :at)) ";
        if(table.equals("core.ad_priority_policy")) return ambiguity;
        String applicable="(preferred.scope_kind='ORGANIZATION' OR (preferred.scope_kind='PLATFORM' AND preferred.platform_code=:platformCode)"
                + " OR (preferred.scope_kind='STORE' AND preferred.store_ref_id=:storeId)";
        if(table.equals("core.ad_allowable_cpa_definition")) applicable+=" OR (preferred.scope_kind='PRODUCT_VARIANT' AND preferred.product_variant_ref_id=:productVariantId)";
        if(table.equals("core.ad_freshness_profile")) applicable+=" OR (preferred.scope_kind='SEMANTIC_PROFILE' AND preferred.semantic_profile_id=:semanticProfileId)";
        applicable+=")";
        String dimensions=java.util.Arrays.stream("sale_stage,purpose_tier,evidence_kind,decision_purpose".split(","))
                .map(column->"to_jsonb(preferred)->'"+column+"' IS NOT DISTINCT FROM to_jsonb("+rowName+")->'"+column+"'")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String rank="CASE %s.scope_kind WHEN 'PRODUCT_VARIANT' THEN 0 WHEN 'SEMANTIC_PROFILE' THEN 0 WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END";
        // A conflicted narrow scope is unresolved; it cannot expose a broader
        // policy by disappearing from the result before ORDER BY/LIMIT.
        return ambiguity+" AND NOT EXISTS(SELECT 1 FROM "+table+" preferred WHERE preferred.organization_id=:organizationId AND "
                +applicable+" AND "+dimensions+" AND "+rank.formatted("preferred")+" < "+rank.formatted(rowName)
                +" AND preferred.status IN('ACTIVE','RETIRED') AND preferred.effective_from<=:at"
                +" AND (preferred.effective_to IS NULL OR preferred.effective_to>:at)) ";
    }

    public Optional<ConversionDefinition> resolveObjectConversion(UUID organizationId,
            String platformCode, UUID storeId, UUID semanticProfileId, String objectKind, Instant at) {
        var candidates = jdbc.sql("""
                WITH bundles AS (
                    SELECT DISTINCT conversion_definition_id AS id
                    FROM ops.ad_decision_policy_bundle
                    WHERE organization_id = :organizationId AND store_id = :storeId
                      AND semantic_profile_id = :semanticProfileId AND native_object_kind = :objectKind
                      AND status = 'ACTIVE' AND validation_state = 'VALIDATED'
                      AND effective_from <= :at AND (effective_to IS NULL OR effective_to > :at)
                ), definitions AS (
                    SELECT d.*, dense_rank() OVER (ORDER BY CASE scope_kind
                        WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END) AS precedence
                    FROM core.ad_conversion_definition d
                    WHERE organization_id = :organizationId
                      AND (scope_kind = 'ORGANIZATION' OR (scope_kind = 'PLATFORM' AND platform_code = :platformCode)
                        OR (scope_kind = 'STORE' AND store_ref_id = :storeId))
                      AND status IN ('ACTIVE','RETIRED') AND effective_from <= :at
                      AND (effective_to IS NULL OR effective_to > :at)
                )
                SELECT * FROM definitions d
                WHERE (EXISTS (SELECT 1 FROM bundles) AND d.id IN (SELECT id FROM bundles))
                   OR (NOT EXISTS (SELECT 1 FROM bundles) AND precedence = 1)
                LIMIT 2
                """).param("organizationId", organizationId).param("storeId", storeId)
                .param("platformCode", platformCode).param("semanticProfileId", semanticProfileId)
                .param("objectKind", objectKind).param("at", ts(at))
                .query((rs, index) -> new ConversionDefinition(rs.getObject("id", UUID.class),
                        rs.getInt("definition_version"), rs.getString("sale_stage"),
                        rs.getString("traffic_denominator_kind"), rs.getString("linkage_basis"),
                        rs.getBigDecimal("minimum_linkage_coverage_ratio"),
                        rs.getBigDecimal("minimum_affected_set_coverage_ratio"),
                        rs.getInt("minimum_sample_events"), rs.getBigDecimal("maximum_attribution_gap_ratio"),
                        rs.getInt("observation_window_days"))).list();
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    public Optional<ConversionDefinition> resolveConversion(
            UUID organizationId, String platformCode, UUID storeId, String saleStage, Instant at) {
        return jdbc.sql("""
                SELECT id, definition_version, sale_stage, traffic_denominator_kind, linkage_basis,
                       minimum_linkage_coverage_ratio, minimum_affected_set_coverage_ratio,
                       minimum_sample_events, maximum_attribution_gap_ratio, observation_window_days
                  FROM core.ad_conversion_definition
                 WHERE organization_id = :organizationId AND sale_stage = :saleStage
                   AND (scope_kind = 'ORGANIZATION'
                        OR (scope_kind = 'PLATFORM' AND platform_code = :platformCode)
                        OR (scope_kind = 'STORE' AND store_ref_id = :storeId))
                """ + IN_FORCE + uniqueEffectiveScope("core.ad_conversion_definition") + """
                 ORDER BY CASE scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
                          effective_from DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("platformCode", platformCode)
                .param("storeId", storeId)
                .param("saleStage", saleStage)
                .param("at", ts(at))
                .query((ResultSet rs, int index) -> new ConversionDefinition(
                        rs.getObject("id", UUID.class), rs.getInt("definition_version"),
                        rs.getString("sale_stage"), rs.getString("traffic_denominator_kind"),
                        rs.getString("linkage_basis"),
                        rs.getBigDecimal("minimum_linkage_coverage_ratio"),
                        rs.getBigDecimal("minimum_affected_set_coverage_ratio"),
                        rs.getInt("minimum_sample_events"),
                        rs.getBigDecimal("maximum_attribution_gap_ratio"),
                        rs.getInt("observation_window_days")))
                .optional();
    }

    public Optional<AllowableCpaDefinition> resolveAllowableCpa(
            UUID organizationId, String platformCode, UUID storeId, UUID productVariantId,
            String saleStage, Instant at) {
        return jdbc.sql("""
                SELECT id, definition_version, sale_stage, currency_code, contribution_basis,
                       target_contribution_retention_ratio, return_loss_treatment
                  FROM core.ad_allowable_cpa_definition
                 WHERE organization_id = :organizationId AND sale_stage = :saleStage
                   AND (scope_kind = 'ORGANIZATION'
                        OR (scope_kind = 'PLATFORM' AND platform_code = :platformCode)
                        OR (scope_kind = 'STORE' AND store_ref_id = :storeId)
                        OR (scope_kind = 'PRODUCT_VARIANT'
                            AND product_variant_ref_id = :productVariantId))
                """ + IN_FORCE + uniqueEffectiveScope("core.ad_allowable_cpa_definition") + """
                 ORDER BY CASE scope_kind WHEN 'PRODUCT_VARIANT' THEN 1 WHEN 'STORE' THEN 2
                              WHEN 'PLATFORM' THEN 3 ELSE 4 END,
                          effective_from DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("platformCode", platformCode)
                .param("storeId", storeId)
                .param("productVariantId", productVariantId)
                .param("saleStage", saleStage)
                .param("at", ts(at))
                .query((ResultSet rs, int index) -> new AllowableCpaDefinition(
                        rs.getObject("id", UUID.class), rs.getInt("definition_version"),
                        rs.getString("sale_stage"), rs.getString("currency_code"),
                        rs.getString("contribution_basis"),
                        rs.getBigDecimal("target_contribution_retention_ratio"),
                        rs.getString("return_loss_treatment")))
                .optional();
    }

    public Optional<QualificationPolicy> resolveQualification(
            UUID organizationId, String platformCode, UUID storeId, String purposeTier, Instant at) {
        return jdbc.sql("""
                SELECT id, policy_version, purpose_tier, eligible_observation_window_days,
                       minimum_source_coverage_ratio, minimum_affected_set_coverage_ratio,
                       minimum_traffic_denominator, minimum_completed_sale_events,
                       minimum_retained_sale_events, minimum_spend_amount, currency_code,
                       minimum_sustained_periods, minimum_recoverable_amount,
                       requires_correction_window_closed, requires_comparable_baseline,
                       minimum_confidence_state
                  FROM core.ad_optimization_qualification_policy
                 WHERE organization_id = :organizationId AND purpose_tier = :purposeTier
                   AND (scope_kind = 'ORGANIZATION'
                        OR (scope_kind = 'PLATFORM' AND platform_code = :platformCode)
                        OR (scope_kind = 'STORE' AND store_ref_id = :storeId))
                """ + IN_FORCE + uniqueEffectiveScope("core.ad_optimization_qualification_policy") + """
                 ORDER BY CASE scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
                          effective_from DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("platformCode", platformCode)
                .param("storeId", storeId)
                .param("purposeTier", purposeTier)
                .param("at", ts(at))
                .query((ResultSet rs, int index) -> new QualificationPolicy(
                        rs.getObject("id", UUID.class), rs.getInt("policy_version"),
                        rs.getString("purpose_tier"), rs.getInt("eligible_observation_window_days"),
                        rs.getBigDecimal("minimum_source_coverage_ratio"),
                        rs.getBigDecimal("minimum_affected_set_coverage_ratio"),
                        rs.getLong("minimum_traffic_denominator"),
                        rs.getInt("minimum_completed_sale_events"),
                        rs.getInt("minimum_retained_sale_events"),
                        rs.getBigDecimal("minimum_spend_amount"), rs.getString("currency_code"),
                        rs.getInt("minimum_sustained_periods"),
                        rs.getBigDecimal("minimum_recoverable_amount"),
                        rs.getBoolean("requires_correction_window_closed"),
                        rs.getBoolean("requires_comparable_baseline"),
                        rs.getString("minimum_confidence_state")))
                .optional();
    }

    public Optional<PriorityWeights> resolvePriority(UUID organizationId, Instant at) {
        return jdbc.sql("""
                SELECT id, policy_version, profit_loss_weight, spend_exposure_weight,
                       critical_sales_weight, recoverable_profit_weight,
                       evidence_maturity_weight, age_weight, confidence_weight
                  FROM core.ad_priority_policy
                 WHERE organization_id = :organizationId
                """ + IN_FORCE + uniqueEffectiveScope("core.ad_priority_policy") + """
                 ORDER BY effective_from DESC LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("at", ts(at))
                .query((ResultSet rs, int index) -> new PriorityWeights(
                        rs.getObject("id", UUID.class), rs.getInt("policy_version"),
                        rs.getBigDecimal("profit_loss_weight"),
                        rs.getBigDecimal("spend_exposure_weight"),
                        rs.getBigDecimal("critical_sales_weight"),
                        rs.getBigDecimal("recoverable_profit_weight"),
                        rs.getBigDecimal("evidence_maturity_weight"),
                        rs.getBigDecimal("age_weight"),
                        rs.getBigDecimal("confidence_weight")))
                .optional();
    }

    public Optional<HumanSlo> resolveHumanSlo(UUID organizationId, String lane, Instant at) {
        return jdbc.sql("""
                SELECT id, policy_version, lane, acknowledgement_minutes, action_minutes,
                       escalation_minutes, staffed_coverage_enabled, staffed_coverage_timezone,
                       staffed_coverage_start_minute, staffed_coverage_end_minute,
                       out_of_coverage_visible_from_minutes
                  FROM core.ad_human_slo_profile
                 WHERE organization_id = :organizationId AND lane = :lane
                """ + IN_FORCE + """
                 ORDER BY effective_from DESC LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("lane", lane)
                .param("at", ts(at))
                .query((ResultSet rs, int index) -> new HumanSlo(
                        rs.getObject("id", UUID.class), rs.getInt("policy_version"),
                        rs.getString("lane"), rs.getInt("acknowledgement_minutes"),
                        rs.getInt("action_minutes"), rs.getInt("escalation_minutes"),
                        rs.getBoolean("staffed_coverage_enabled"),
                        rs.getString("staffed_coverage_timezone"),
                        (Integer) rs.getObject("staffed_coverage_start_minute"),
                        (Integer) rs.getObject("staffed_coverage_end_minute"),
                        rs.getInt("out_of_coverage_visible_from_minutes")))
                .optional();
    }

    public Optional<FreshnessProfile> resolveFreshness(UUID organizationId, String evidenceKind,
            String decisionPurpose, String platformCode, UUID storeId, Instant at) {
        return resolveFreshness(organizationId, evidenceKind, decisionPurpose, platformCode,
                storeId, null, at);
    }

    public Optional<FreshnessProfile> resolveFreshness(
            UUID organizationId, String evidenceKind, String decisionPurpose,
            String platformCode, UUID storeId, UUID semanticProfileId, Instant at) {
        return jdbc.sql("""
                SELECT id, profile_version, evidence_kind, decision_purpose,
                       source_max_age_minutes, accepted_fact_max_age_minutes,
                       expected_publication_lag_minutes, correction_window_minutes,
                       requires_window_complete, requires_correction_window_closed,
                       minimum_coverage_ratio, minimum_confidence_state, provider_incident_blocks, effective_to, ops.ad_outcome_freshness_snapshot(id)->>'authorityDigest' AS authority_digest
                  FROM core.ad_freshness_profile
                 WHERE organization_id = :organizationId
                   AND evidence_kind = :evidenceKind AND decision_purpose = :decisionPurpose
                   AND (scope_kind = 'ORGANIZATION'
                        OR (scope_kind = 'PLATFORM' AND platform_code = :platformCode)
                        OR (scope_kind = 'STORE' AND store_ref_id = :storeId)
                        OR (scope_kind = 'SEMANTIC_PROFILE' AND semantic_profile_id = :semanticProfileId))
                """ + IN_FORCE + uniqueEffectiveScope("core.ad_freshness_profile") + """
                 ORDER BY CASE scope_kind WHEN 'SEMANTIC_PROFILE' THEN 0 WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
                          effective_from DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("evidenceKind", evidenceKind)
                .param("decisionPurpose", decisionPurpose)
                .param("platformCode", platformCode)
                .param("storeId", storeId)
                .param("semanticProfileId", semanticProfileId)
                .param("at", ts(at))
                .query((ResultSet rs, int index) -> new FreshnessProfile(
                        rs.getObject("id", UUID.class), rs.getInt("profile_version"),
                        rs.getString("evidence_kind"), rs.getString("decision_purpose"),
                        (Integer) rs.getObject("source_max_age_minutes"),
                        (Integer) rs.getObject("accepted_fact_max_age_minutes"),
                        rs.getInt("expected_publication_lag_minutes"),
                        rs.getInt("correction_window_minutes"),
                        rs.getBoolean("requires_window_complete"),
                        rs.getBoolean("requires_correction_window_closed"),
                        rs.getBigDecimal("minimum_coverage_ratio"),
                        rs.getString("minimum_confidence_state"),
                        rs.getBoolean("provider_incident_blocks"), rs.getTimestamp("effective_to") == null
                                ? null : rs.getTimestamp("effective_to").toInstant(), rs.getString("authority_digest")))
                .optional();
    }

    /**
     * The bounds one decision may move a bid within, for this exact combination.
     *
     * <p>Scoped by store, then platform, then organization, like every other
     * policy here. A store that has been given a tighter step than the rest of
     * the organization keeps it, and an absent policy resolves to nothing rather
     * than to a default — there is no bound this code would be entitled to
     * invent for a real advertising auction.
     */
    public Optional<TargetPolicy> resolveBidTargetPolicy(
            UUID organizationId, String platformCode, UUID storeId, String nativeObjectKind,
            String direction, String candidateBasis, Instant at) {
        return jdbc.sql("""
                SELECT id, policy_version, candidate_count, max_relative_change_ratio,
                       max_absolute_change_amount, currency_code, ceiling_headroom_ratio,
                       cause_bound_step_enabled, cause_bound_step_ratio, cause_bound_causes
                  FROM core.ad_bid_target_policy
                 WHERE organization_id = :organizationId
                   AND native_object_kind = :nativeObjectKind
                   AND direction = :direction
                   AND candidate_basis = :candidateBasis
                   AND (scope_kind = 'ORGANIZATION'
                        OR (scope_kind = 'PLATFORM' AND platform_code = :platformCode)
                        OR (scope_kind = 'STORE' AND store_ref_id = :storeId))
                """ + IN_FORCE + """
                 ORDER BY CASE scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
                          effective_from DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("platformCode", platformCode)
                .param("storeId", storeId)
                .param("nativeObjectKind", nativeObjectKind)
                .param("direction", direction)
                .param("candidateBasis", candidateBasis)
                .param("at", ts(at))
                .query((ResultSet rs, int index) -> {
                    java.sql.Array causes = rs.getArray("cause_bound_causes");
                    return new TargetPolicy(
                            rs.getObject("id", UUID.class), rs.getInt("policy_version"),
                            rs.getInt("candidate_count"),
                            rs.getBigDecimal("max_relative_change_ratio"),
                            rs.getBigDecimal("max_absolute_change_amount"),
                            rs.getString("currency_code"),
                            rs.getBigDecimal("ceiling_headroom_ratio"),
                            rs.getBoolean("cause_bound_step_enabled"),
                            rs.getBigDecimal("cause_bound_step_ratio"),
                            causes == null ? java.util.List.<String>of()
                                    : java.util.List.of((String[]) causes.getArray()));
                })
                .optional();
    }

    /**
     * The platform's own bid arithmetic for one advertising object.
     *
     * <p>Read from the object's own semantic profile rather than from the
     * platform, because two object kinds on one marketplace can count in
     * different units. The verification state travels with it, so the caller
     * cannot use an unverified description of a real auction by accident.
     */
    public Optional<ObjectBidContext> resolveBidGrid(UUID adNativeObjectId) {
        return jdbc.sql("""
                SELECT object.native_object_kind, object.control_granularity_state,
                       profile.bid_unit_code, profile.bid_currency_code, profile.bid_precision,
                       profile.bid_step, profile.bid_minimum, profile.bid_maximum,
                       profile.bid_field_present, profile.verification_state
                  FROM core.ad_native_object object
                  JOIN platform.ad_semantic_profile profile
                    ON profile.id = object.semantic_profile_id
                 WHERE object.id = :objectId AND object.status = 'ACTIVE'
                   AND profile.status = 'ACTIVE'
                   AND (profile.effective_to IS NULL OR profile.effective_to > statement_timestamp())
                """)
                .param("objectId", adNativeObjectId)
                .query((ResultSet rs, int index) -> new ObjectBidContext(
                        rs.getString("native_object_kind"),
                        rs.getString("control_granularity_state"),
                        new ProviderBidGrid(
                                rs.getString("bid_unit_code"),
                                rs.getString("bid_currency_code"),
                                integerOrNull(rs, "bid_precision"),
                                rs.getBigDecimal("bid_step"),
                                rs.getBigDecimal("bid_minimum"),
                                rs.getBigDecimal("bid_maximum"),
                                rs.getBoolean("bid_field_present"),
                                rs.getString("verification_state"))))
                .optional();
    }

    /**
     * What kind of object this is and how its platform counts bids.
     *
     * <p>Both come from the same read because both are properties of the object
     * at one instant, and asking separately would let a policy be resolved for
     * one kind while the arithmetic came from another.
     */
    public record ObjectBidContext(String nativeObjectKind, String controlGranularityState,
                                   ProviderBidGrid grid) {

        /** Whether a controlled write may ever consume this object. */
        public boolean independentlyControllable() {
            return "PROVEN_INDEPENDENT".equals(controlGranularityState);
        }
    }

    /** The bounds one decision may move a bid within. */
    public record TargetPolicy(
            UUID id, int policyVersion, int candidateCount,
            java.math.BigDecimal maxRelativeChangeRatio,
            java.math.BigDecimal maxAbsoluteChangeAmount, String currencyCode,
            java.math.BigDecimal ceilingHeadroomRatio,
            boolean causeBoundStepEnabled,
            java.math.BigDecimal causeBoundStepRatio,
            java.util.List<String> causeBoundCauses) {

        /**
         * Whether this policy lets one named cause bound a decrease on its own.
         *
         * <p>The cause has to be listed. A policy that enabled the route without
         * naming the causes would be a policy that permits a bid change for any
         * reason, which is the opposite of what a cause-bound step is.
         */
        public boolean allowsCauseBoundStep(String causeCode) {
            return causeBoundStepEnabled
                    && causeBoundStepRatio != null
                    && causeBoundStepRatio.signum() > 0
                    && causeCode != null
                    && causeBoundCauses.contains(causeCode);
        }

        /** The limits as the candidate arithmetic consumes them. */
        public com.mimococo.marketops.advertisingefficiency.internal.domain.BidStepLimits limits() {
            return new com.mimococo.marketops.advertisingefficiency.internal.domain.BidStepLimits(
                    maxRelativeChangeRatio, maxAbsoluteChangeAmount, ceilingHeadroomRatio);
        }
    }

    private static Integer integerOrNull(ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** The unique complete active decision bundle for one exact scope, if there is one. */
    public Optional<ResolvedVersion> resolveBundle(
            UUID organizationId, UUID storeId, String direction, String candidateBasis,
            String nativeObjectKind, Instant at) {
        java.util.List<ResolvedVersion> candidates = jdbc.sql("""
                SELECT id, bundle_version FROM ops.ad_decision_policy_bundle
                 WHERE organization_id = :organizationId AND store_id = :storeId
                   AND capability_code = 'ad-bid-change'
                   AND direction = :direction AND candidate_basis = :candidateBasis
                   AND native_object_kind = :nativeObjectKind
                   AND status = 'ACTIVE' AND validation_state = 'VALIDATED'
                   AND effective_from <= :at
                   AND (effective_to IS NULL OR effective_to > :at)
                 LIMIT 2
                """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("direction", direction)
                .param("candidateBasis", candidateBasis)
                .param("nativeObjectKind", nativeObjectKind)
                .param("at", ts(at))
                .query((ResultSet rs, int index) ->
                        new ResolvedVersion(rs.getObject("id", UUID.class),
                                rs.getInt("bundle_version")))
                .list();
        // Two answers is the same as none. The Contract requires a unique
        // complete active bundle, and picking one of two would be inventing the
        // authority the uniqueness rule exists to guarantee. The exclusion
        // constraint should make this unreachable; if it is ever reached, the
        // write path fails closed rather than choosing.
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    /**
     * Bind an instant the driver can type.
     *
     * <p>PostgreSQL's driver cannot infer a SQL type for {@link java.time.Instant},
     * and a bare {@code null} is worse: it has no type at all. Wrapping both in a
     * typed parameter value is what the rest of this codebase does, and doing it
     * anywhere else would produce a runtime failure that only shows up on the
     * path that happens to pass a null.
     */
    private static org.springframework.jdbc.core.SqlParameterValue ts(java.time.Instant instant) {
        return new org.springframework.jdbc.core.SqlParameterValue(
                java.sql.Types.TIMESTAMP,
                instant == null ? null : java.sql.Timestamp.from(instant));
    }
}
