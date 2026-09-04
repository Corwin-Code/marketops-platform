package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes the case projection.
 *
 * <p>Cases are upserted on their deduplication key, so recalculating one cause a
 * thousand times updates one row. Factors, variants and evidence are
 * append-only per calculation, so an older generation stays readable beside the
 * attempts made while it was current and is simply never joined to again.
 *
 * <p>There is no delete anywhere in this class, and none granted in the schema.
 * A case that stops applying is recalculated into a different lane, not removed:
 * a queue that could silently lose a row is a queue nobody can reconcile against.
 */
@Repository
public class AdvertisingProjectionRepository {

    private final JdbcClient jdbc;

    AdvertisingProjectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What an existing case already carried, so a sustained run can continue. */
    public record ExistingCase(UUID id, String lane, String sustainedLane,
            int sustainedCycles, Instant sustainedSince) {
    }

    public Optional<ExistingCase> findByKey(UUID organizationId, String caseKey) {
        return jdbc.sql("""
                SELECT id, lane, sustained_lane, sustained_cycles, sustained_since
                  FROM mart.ad_case
                 WHERE organization_id = :organizationId AND case_key = :caseKey
                """)
                .param("organizationId", organizationId)
                .param("caseKey", caseKey)
                .query((ResultSet rs, int index) -> new ExistingCase(
                        rs.getObject("id", UUID.class),
                        rs.getString("lane"),
                        rs.getString("sustained_lane"),
                        rs.getInt("sustained_cycles"),
                        instantOf(rs, "sustained_since")))
                .optional();
    }

    /** Every stored measure of one case, in the exact shape the table holds. */
    public record CaseRow(
            UUID id, UUID organizationId, UUID storeId, String platformCode,
            UUID adNativeObjectId, UUID affectedSetId, UUID semanticProfileId,
            int lineageGeneration, String caseKey, String lane, String protectionTier,
            String causeCode, String evidenceState, String confidenceState,
            List<String> blockerCodes,
            String contributionProfitState, BigDecimal contributionProfitAmount,
            String profitPerAdRubState, BigDecimal profitPerAdRubValue,
            String profitCurrencyCode,
            String officialSpendState, BigDecimal officialSpendAmount,
            String eligibleTrafficState, Long eligibleTrafficCount,
            String adLinkedConversionState, BigDecimal adLinkedConversionValue,
            String adLinkedConversionStage,
            String maxCpcState, BigDecimal maxCpcAmount,
            String attributionGapState, BigDecimal attributionGapRatio,
            String currentBidState, BigDecimal currentBidAmount,
            BigDecimal recoverableProfitAmount, BigDecimal rankScore,
            String policyVersionDigest, UUID bundleId, Instant asOf, Instant calculatedAt,
            String calculationKind, UUID calculationId, UUID reconciliationRunId,
            String sustainedLane, int sustainedCycles, Instant sustainedSince) {
    }

    /** Insert or update one case on its cause key. */
    public void upsertCase(CaseRow row) {
        jdbc.sql("""
                INSERT INTO mart.ad_case (
                    id, organization_id, store_id, platform_code, ad_native_object_id,
                    affected_set_id, semantic_profile_id, lineage_generation, case_key,
                    lane, protection_tier, cause_code, evidence_state, confidence_state,
                    blocker_codes, contribution_profit_state, contribution_profit_amount,
                    profit_per_ad_rub_state, profit_per_ad_rub_value, profit_currency_code,
                    official_spend_state, official_spend_amount,
                    eligible_traffic_state, eligible_traffic_count,
                    ad_linked_conversion_state, ad_linked_conversion_value,
                    ad_linked_conversion_stage, max_cpc_state, max_cpc_amount,
                    attribution_gap_state, attribution_gap_ratio,
                    current_bid_state, current_bid_amount, recoverable_profit_amount,
                    rank_score, policy_version_digest, bundle_id, as_of, calculated_at,
                    calculation_kind, calculation_id, reconciliation_run_id,
                    sustained_lane, sustained_cycles, sustained_since,
                    created_at, updated_at)
                VALUES (
                    :id, :organizationId, :storeId, :platformCode, :adNativeObjectId,
                    :affectedSetId, :semanticProfileId, :lineageGeneration, :caseKey,
                    :lane, :protectionTier, :causeCode, :evidenceState, :confidenceState,
                    :blockerCodes, :contributionProfitState, :contributionProfitAmount,
                    :profitPerAdRubState, :profitPerAdRubValue, :profitCurrencyCode,
                    :officialSpendState, :officialSpendAmount,
                    :eligibleTrafficState, :eligibleTrafficCount,
                    :adLinkedConversionState, :adLinkedConversionValue,
                    :adLinkedConversionStage, :maxCpcState, :maxCpcAmount,
                    :attributionGapState, :attributionGapRatio,
                    :currentBidState, :currentBidAmount, :recoverableProfitAmount,
                    :rankScore, :policyVersionDigest, :bundleId, :asOf, :calculatedAt,
                    :calculationKind, :calculationId, :reconciliationRunId,
                    :sustainedLane, :sustainedCycles, :sustainedSince,
                    :calculatedAt, :calculatedAt)
                ON CONFLICT (organization_id, case_key) DO UPDATE SET
                    store_id = EXCLUDED.store_id,
                    affected_set_id = EXCLUDED.affected_set_id,
                    semantic_profile_id = EXCLUDED.semantic_profile_id,
                    lane = EXCLUDED.lane,
                    protection_tier = EXCLUDED.protection_tier,
                    evidence_state = EXCLUDED.evidence_state,
                    confidence_state = EXCLUDED.confidence_state,
                    blocker_codes = EXCLUDED.blocker_codes,
                    contribution_profit_state = EXCLUDED.contribution_profit_state,
                    contribution_profit_amount = EXCLUDED.contribution_profit_amount,
                    profit_per_ad_rub_state = EXCLUDED.profit_per_ad_rub_state,
                    profit_per_ad_rub_value = EXCLUDED.profit_per_ad_rub_value,
                    profit_currency_code = EXCLUDED.profit_currency_code,
                    official_spend_state = EXCLUDED.official_spend_state,
                    official_spend_amount = EXCLUDED.official_spend_amount,
                    eligible_traffic_state = EXCLUDED.eligible_traffic_state,
                    eligible_traffic_count = EXCLUDED.eligible_traffic_count,
                    ad_linked_conversion_state = EXCLUDED.ad_linked_conversion_state,
                    ad_linked_conversion_value = EXCLUDED.ad_linked_conversion_value,
                    ad_linked_conversion_stage = EXCLUDED.ad_linked_conversion_stage,
                    max_cpc_state = EXCLUDED.max_cpc_state,
                    max_cpc_amount = EXCLUDED.max_cpc_amount,
                    attribution_gap_state = EXCLUDED.attribution_gap_state,
                    attribution_gap_ratio = EXCLUDED.attribution_gap_ratio,
                    current_bid_state = EXCLUDED.current_bid_state,
                    current_bid_amount = EXCLUDED.current_bid_amount,
                    recoverable_profit_amount = EXCLUDED.recoverable_profit_amount,
                    rank_score = EXCLUDED.rank_score,
                    policy_version_digest = EXCLUDED.policy_version_digest,
                    bundle_id = EXCLUDED.bundle_id,
                    as_of = EXCLUDED.as_of,
                    calculated_at = EXCLUDED.calculated_at,
                    calculation_kind = EXCLUDED.calculation_kind,
                    calculation_id = EXCLUDED.calculation_id,
                    reconciliation_run_id = EXCLUDED.reconciliation_run_id,
                    sustained_lane = EXCLUDED.sustained_lane,
                    sustained_cycles = EXCLUDED.sustained_cycles,
                    sustained_since = EXCLUDED.sustained_since,
                    updated_at = EXCLUDED.updated_at,
                    -- A cause that returns reopens its own case rather than
                    -- starting a fresh one, so a recurring problem keeps its
                    -- history instead of looking like a series of new ones.
                    superseded_at = NULL,
                    superseded_reason = NULL,
                    version = mart.ad_case.version + 1
                """)
                .param("id", row.id()).param("organizationId", row.organizationId())
                .param("storeId", row.storeId()).param("platformCode", row.platformCode())
                .param("adNativeObjectId", row.adNativeObjectId())
                .param("affectedSetId", row.affectedSetId())
                .param("semanticProfileId", row.semanticProfileId())
                .param("lineageGeneration", row.lineageGeneration())
                .param("caseKey", row.caseKey()).param("lane", row.lane())
                .param("protectionTier", row.protectionTier())
                .param("causeCode", row.causeCode())
                .param("evidenceState", row.evidenceState())
                .param("confidenceState", row.confidenceState())
                .param("blockerCodes", row.blockerCodes().toArray(new String[0]))
                .param("contributionProfitState", row.contributionProfitState())
                .param("contributionProfitAmount", row.contributionProfitAmount())
                .param("profitPerAdRubState", row.profitPerAdRubState())
                .param("profitPerAdRubValue", row.profitPerAdRubValue())
                .param("profitCurrencyCode", row.profitCurrencyCode())
                .param("officialSpendState", row.officialSpendState())
                .param("officialSpendAmount", row.officialSpendAmount())
                .param("eligibleTrafficState", row.eligibleTrafficState())
                .param("eligibleTrafficCount", row.eligibleTrafficCount())
                .param("adLinkedConversionState", row.adLinkedConversionState())
                .param("adLinkedConversionValue", row.adLinkedConversionValue())
                .param("adLinkedConversionStage", row.adLinkedConversionStage())
                .param("maxCpcState", row.maxCpcState())
                .param("maxCpcAmount", row.maxCpcAmount())
                .param("attributionGapState", row.attributionGapState())
                .param("attributionGapRatio", row.attributionGapRatio())
                .param("currentBidState", row.currentBidState())
                .param("currentBidAmount", row.currentBidAmount())
                .param("recoverableProfitAmount", row.recoverableProfitAmount())
                .param("rankScore", row.rankScore())
                .param("policyVersionDigest", row.policyVersionDigest())
                .param("bundleId", row.bundleId())
                .param("asOf", ts(row.asOf())).param("calculatedAt", ts(row.calculatedAt()))
                .param("calculationKind", row.calculationKind())
                .param("calculationId", row.calculationId())
                .param("reconciliationRunId", row.reconciliationRunId())
                .param("sustainedLane", row.sustainedLane())
                .param("sustainedCycles", row.sustainedCycles())
                .param("sustainedSince", ts(row.sustainedSince()))
                .update();
    }

    /** Append one visible rank factor. */
    public void insertFactor(UUID id, UUID caseId, UUID organizationId, UUID calculationId,
            String factorCode, BigDecimal value, BigDecimal weight, BigDecimal contribution,
            String displayNote) {
        jdbc.sql("""
                INSERT INTO mart.ad_case_rank_factor (
                    id, case_id, organization_id, calculation_id, factor_code,
                    factor_value, factor_weight, contribution, display_note)
                VALUES (:id, :caseId, :organizationId, :calculationId, :factorCode,
                    :value, :weight, :contribution, :displayNote)
                ON CONFLICT (calculation_id, factor_code) DO NOTHING
                """)
                .param("id", id).param("caseId", caseId).param("organizationId", organizationId)
                .param("calculationId", calculationId).param("factorCode", factorCode)
                .param("value", value).param("weight", weight)
                .param("contribution", contribution).param("displayNote", displayNote)
                .update();
    }

    /** Append one per-variant diagnostic. */
    public void insertVariant(UUID id, UUID caseId, UUID organizationId, UUID calculationId,
            UUID productVariantId, UUID platformListingVariantId, String basis,
            String confidenceState, BigDecimal spendAmount, Long clicks,
            BigDecimal contributionProfitAmount, String currencyCode, String sellabilityState,
            String availabilityState, boolean criticalSalesUnit, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO mart.ad_case_variant_diagnostic (
                    id, case_id, organization_id, calculation_id, product_variant_id,
                    platform_listing_variant_id, basis, confidence_state, spend_amount,
                    clicks, contribution_profit_amount, currency_code, sellability_state,
                    availability_state, is_critical_sales_unit, created_at)
                VALUES (:id, :caseId, :organizationId, :calculationId, :productVariantId,
                    :platformListingVariantId, :basis, :confidenceState, :spendAmount,
                    :clicks, :contributionProfitAmount, :currencyCode, :sellabilityState,
                    :availabilityState, :criticalSalesUnit, :createdAt)
                ON CONFLICT (calculation_id, product_variant_id) DO NOTHING
                """)
                .param("id", id).param("caseId", caseId).param("organizationId", organizationId)
                .param("calculationId", calculationId).param("productVariantId", productVariantId)
                .param("platformListingVariantId", platformListingVariantId)
                .param("basis", basis).param("confidenceState", confidenceState)
                .param("spendAmount", spendAmount).param("clicks", clicks)
                .param("contributionProfitAmount", contributionProfitAmount)
                .param("currencyCode", currencyCode).param("sellabilityState", sellabilityState)
                .param("availabilityState", availabilityState)
                .param("criticalSalesUnit", criticalSalesUnit).param("createdAt", ts(createdAt))
                .update();
    }

    /** Append one evidence reference. Exactly one identifier column is populated. */
    public void insertEvidence(UUID id, UUID caseId, UUID organizationId, UUID calculationId,
            String evidenceRole, UUID provenanceId, UUID metricValueId, UUID policyReferenceId,
            UUID adObjectFactId, UUID adLinkedSaleEventId, UUID configurationObservationId,
            Instant observedAt, String note) {
        jdbc.sql("""
                INSERT INTO mart.ad_case_evidence (
                    id, case_id, organization_id, calculation_id, evidence_role,
                    provenance_id, metric_value_id, policy_reference_id, ad_object_fact_id,
                    ad_linked_sale_event_id, configuration_observation_id, observed_at, note)
                VALUES (:id, :caseId, :organizationId, :calculationId, :evidenceRole,
                    :provenanceId, :metricValueId, :policyReferenceId, :adObjectFactId,
                    :adLinkedSaleEventId, :configurationObservationId, :observedAt, :note)
                """)
                .param("id", id).param("caseId", caseId).param("organizationId", organizationId)
                .param("calculationId", calculationId).param("evidenceRole", evidenceRole)
                .param("provenanceId", provenanceId).param("metricValueId", metricValueId)
                .param("policyReferenceId", policyReferenceId)
                .param("adObjectFactId", adObjectFactId)
                .param("adLinkedSaleEventId", adLinkedSaleEventId)
                .param("configurationObservationId", configurationObservationId)
                .param("observedAt", ts(observedAt)).param("note", note)
                .update();
    }

    /**
     * Retire every live case for this object whose cause this calculation no
     * longer produces.
     *
     * <p>Called with the exact case keys the calculation did produce. Anything
     * else for the same object was true once and is not now, so it stops being
     * work while staying readable and staying linked to whatever outcome lineage
     * points at it.
     */
    public int supersedeCasesOtherThan(
            UUID organizationId, UUID adNativeObjectId, List<String> liveCaseKeys, Instant at) {
        return jdbc.sql("""
                UPDATE mart.ad_case
                   SET superseded_at = :at, superseded_reason = 'CAUSE_NO_LONGER_CALCULATED',
                       updated_at = :at, version = version + 1
                 WHERE organization_id = :organizationId
                   AND ad_native_object_id = :adNativeObjectId
                   AND superseded_at IS NULL
                   AND NOT (case_key = ANY (:liveCaseKeys))
                """)
                .param("organizationId", organizationId)
                .param("adNativeObjectId", adNativeObjectId)
                .param("liveCaseKeys", liveCaseKeys.toArray(new String[0]))
                .param("at", ts(at))
                .update();
    }

    /** The lane a case currently sits in, for detecting a change during a sweep. */
    public Optional<String> currentLane(UUID organizationId, String caseKey) {
        return jdbc.sql("SELECT lane FROM mart.ad_case"
                        + " WHERE organization_id = :organizationId AND case_key = :caseKey")
                .param("organizationId", organizationId)
                .param("caseKey", caseKey)
                .query(String.class)
                .optional();
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

    /**
     * Read a timestamp the driver will hand over.
     *
     * <p>This driver refuses {@code getObject(column, Instant.class)} against a
     * {@code timestamptz}, so every read goes through {@link java.sql.Timestamp}
     * exactly as the rest of this codebase does. Null stays null rather than
     * becoming the epoch, because an absent observation time and an observation
     * at the dawn of time are different facts.
     */
    private static java.time.Instant instantOf(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
