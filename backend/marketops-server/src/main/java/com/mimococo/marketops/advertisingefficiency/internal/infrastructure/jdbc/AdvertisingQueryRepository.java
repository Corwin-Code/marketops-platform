package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the advertising queue, narrowed to what the caller may actually see.
 *
 * <p>Two things here are load-bearing.
 *
 * <p>The scope is applied in SQL as well as at the controller. A caller that
 * passed a wider permitted list than it was granted still reads nothing outside
 * it, because the predicate is on the row rather than on the request.
 *
 * <p>The rank is re-derived rather than read from {@code rank_score}. The stored
 * value is the rank of the case as a whole; a viewer scoped to one store must
 * see the ordering of what they can see, and the band arithmetic here mirrors
 * {@code AdPriorityPolicy} exactly — seven bands of 100000, commercial part
 * clamped strictly below one band. The two are a hand-maintained pair, and
 * {@code AdPriorityPolicyTest} pins the constants on the Java side so a change
 * to one without the other is caught rather than shipped.
 */
@Repository
public class AdvertisingQueryRepository {

    /** Mirrors {@code AdPriorityPolicy.BAND}. */
    private static final String BAND = "100000";

    /** Mirrors {@code AdPriorityPolicy.band(lane, tier)}. */
    private static final String BAND_EXPRESSION = """
            (CASE
                WHEN c.lane = 'PROTECTION' AND c.protection_tier = 'P0' THEN 6
                WHEN c.lane = 'PROTECTION' AND c.protection_tier = 'P1' THEN 5
                WHEN c.lane = 'PROTECTION' AND c.protection_tier = 'P2' THEN 4
                WHEN c.lane = 'PROTECTION' AND c.protection_tier = 'P3' THEN 3
                WHEN c.lane = 'DATA_REPAIR' THEN 2
                WHEN c.lane = 'OPTIMIZATION' THEN 1
                ELSE 0
            END)""";

    private final JdbcClient jdbc;

    AdvertisingQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row of the queue, before its factors, variants and evidence are attached. */
    public record CaseRow(
            UUID id, UUID storeId, String platformCode, UUID adNativeObjectId,
            String nativeObjectKind, String nativeObjectKey, String nativeCampaignKey,
            String nativeObjectName, String biddingMode, String controlGranularityState,
            int lineageGeneration, String lane, String protectionTier, String causeCode,
            String evidenceState, String confidenceState, List<String> blockerCodes,
            String contributionProfitState, BigDecimal contributionProfitAmount,
            String profitPerAdRubState, BigDecimal profitPerAdRubValue, String profitCurrencyCode,
            String officialSpendState, BigDecimal officialSpendAmount,
            String eligibleTrafficState, Long eligibleTrafficCount,
            String adLinkedConversionState, BigDecimal adLinkedConversionValue,
            String adLinkedConversionStage, String maxCpcState, BigDecimal maxCpcAmount,
            String attributionGapState, BigDecimal attributionGapRatio,
            String currentBidState, BigDecimal currentBidAmount,
            BigDecimal recoverableProfitAmount, BigDecimal rankScore,
            String policyVersionDigest, String affectedSetDigest, String affectedSetResolution,
            int affectedVariantCount, Instant asOf, Instant calculatedAt,
            String sustainedLane, int sustainedCycles, Instant sustainedSince,
            UUID calculationId) {
    }

    private static final String CASE_SELECT = """
            SELECT c.id, c.store_id, c.platform_code, c.ad_native_object_id,
                   obj.native_object_kind, obj.native_object_key, obj.native_campaign_key,
                   obj.native_object_name, obj.bidding_mode, obj.control_granularity_state,
                   c.lineage_generation, c.lane, c.protection_tier, c.cause_code,
                   c.evidence_state, c.confidence_state, c.blocker_codes,
                   c.contribution_profit_state, c.contribution_profit_amount,
                   c.profit_per_ad_rub_state, c.profit_per_ad_rub_value, c.profit_currency_code,
                   c.official_spend_state, c.official_spend_amount,
                   c.eligible_traffic_state, c.eligible_traffic_count,
                   c.ad_linked_conversion_state, c.ad_linked_conversion_value,
                   c.ad_linked_conversion_stage, c.max_cpc_state, c.max_cpc_amount,
                   c.attribution_gap_state, c.attribution_gap_ratio,
                   c.current_bid_state, c.current_bid_amount, c.recoverable_profit_amount,
                   %s * %s + LEAST(%s::numeric - 1, GREATEST(0,
                       coalesce((SELECT sum(f.contribution) FROM mart.ad_case_rank_factor f
                                  WHERE f.calculation_id = c.calculation_id), 0))) AS visible_rank,
                   c.policy_version_digest, a.affected_set_digest, a.resolution_state,
                   cardinality(a.product_variant_ids) AS affected_variant_count,
                   c.as_of, c.calculated_at, c.sustained_lane, c.sustained_cycles,
                   c.sustained_since, c.calculation_id
              FROM mart.ad_case c
              JOIN core.ad_native_object obj
                ON obj.id = c.ad_native_object_id AND obj.organization_id = c.organization_id
              JOIN core.ad_affected_set a
                ON a.id = c.affected_set_id AND a.organization_id = c.organization_id
             WHERE c.organization_id = :organizationId
               AND c.store_id = ANY (:permittedStoreIds)
            """.formatted(BAND, BAND_EXPRESSION, BAND);

    public List<CaseRow> queue(
            UUID organizationId, List<UUID> permittedStoreIds, String laneFilter,
            int limit, int offset) {
        String sql = CASE_SELECT
                + (laneFilter == null ? "" : " AND c.lane = :lane ")
                + " ORDER BY visible_rank DESC, c.id LIMIT :limit OFFSET :offset";
        var spec = jdbc.sql(sql)
                .param("organizationId", organizationId)
                .param("permittedStoreIds", permittedStoreIds.toArray(new UUID[0]))
                .param("limit", limit)
                .param("offset", offset);
        if (laneFilter != null) {
            spec = spec.param("lane", laneFilter);
        }
        return spec.query(AdvertisingQueryRepository::mapCase).list();
    }

    public Optional<CaseRow> caseById(UUID organizationId, UUID caseId, List<UUID> permittedStoreIds) {
        return jdbc.sql(CASE_SELECT + " AND c.id = :caseId")
                .param("organizationId", organizationId)
                .param("permittedStoreIds", permittedStoreIds.toArray(new UUID[0]))
                .param("caseId", caseId)
                .query(AdvertisingQueryRepository::mapCase)
                .optional();
    }

    /** The rank factors for a set of calculations, keyed by calculation. */
    public record FactorRow(UUID calculationId, String factorCode, BigDecimal value,
            BigDecimal weight, BigDecimal contribution, String displayNote) {
    }

    public List<FactorRow> factors(UUID organizationId, List<UUID> calculationIds) {
        if (calculationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT calculation_id, factor_code, factor_value, factor_weight,
                       contribution, display_note
                  FROM mart.ad_case_rank_factor
                 WHERE organization_id = :organizationId
                   AND calculation_id = ANY (:calculationIds)
                 ORDER BY calculation_id, factor_code
                """)
                .param("organizationId", organizationId)
                .param("calculationIds", calculationIds.toArray(new UUID[0]))
                .query((ResultSet rs, int index) -> new FactorRow(
                        rs.getObject("calculation_id", UUID.class),
                        rs.getString("factor_code"),
                        rs.getBigDecimal("factor_value"),
                        rs.getBigDecimal("factor_weight"),
                        rs.getBigDecimal("contribution"),
                        rs.getString("display_note")))
                .list();
    }

    /**
     * The per-variant diagnostics, narrowed again by product scope.
     *
     * <p>A viewer permitted to see the case but not every variant in it sees the
     * case and the variants they may see. The affected-variant count on the case
     * row is deliberately the complete one, so the difference is visible rather
     * than silently presented as a smaller set.
     */
    public record VariantRow(UUID calculationId, UUID productVariantId, UUID platformListingVariantId,
            String skuCode, String displayName, String basis, String confidenceState,
            BigDecimal spendAmount, Long clicks, BigDecimal contributionProfitAmount,
            String currencyCode, String sellabilityState, String availabilityState,
            boolean criticalSalesUnit) {
    }

    public List<VariantRow> variants(
            UUID organizationId, List<UUID> calculationIds, List<UUID> permittedVariantIds) {
        if (calculationIds.isEmpty() || permittedVariantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT d.calculation_id, d.product_variant_id, d.platform_listing_variant_id,
                       v.sku_code, v.display_name, d.basis, d.confidence_state,
                       d.spend_amount, d.clicks, d.contribution_profit_amount, d.currency_code,
                       d.sellability_state, d.availability_state, d.is_critical_sales_unit
                  FROM mart.ad_case_variant_diagnostic d
                  JOIN core.product_variant v
                    ON v.id = d.product_variant_id AND v.organization_id = d.organization_id
                 WHERE d.organization_id = :organizationId
                   AND d.calculation_id = ANY (:calculationIds)
                   AND d.product_variant_id = ANY (:permittedVariantIds)
                 ORDER BY d.calculation_id, v.sku_code
                """)
                .param("organizationId", organizationId)
                .param("calculationIds", calculationIds.toArray(new UUID[0]))
                .param("permittedVariantIds", permittedVariantIds.toArray(new UUID[0]))
                .query((ResultSet rs, int index) -> new VariantRow(
                        rs.getObject("calculation_id", UUID.class),
                        rs.getObject("product_variant_id", UUID.class),
                        rs.getObject("platform_listing_variant_id", UUID.class),
                        rs.getString("sku_code"),
                        rs.getString("display_name"),
                        rs.getString("basis"),
                        rs.getString("confidence_state"),
                        rs.getBigDecimal("spend_amount"),
                        (Long) rs.getObject("clicks"),
                        rs.getBigDecimal("contribution_profit_amount"),
                        rs.getString("currency_code"),
                        rs.getString("sellability_state"),
                        rs.getString("availability_state"),
                        rs.getBoolean("is_critical_sales_unit")))
                .list();
    }

    /** Evidence references, without their contents. */
    public record EvidenceRow(UUID calculationId, String evidenceRole, UUID referenceId,
            Instant observedAt, String note) {
    }

    public List<EvidenceRow> evidence(UUID organizationId, List<UUID> calculationIds) {
        if (calculationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT calculation_id, evidence_role,
                       coalesce(provenance_id, metric_value_id, policy_reference_id,
                                ad_object_fact_id, ad_linked_sale_event_id,
                                configuration_observation_id) AS reference_id,
                       observed_at, note
                  FROM mart.ad_case_evidence
                 WHERE organization_id = :organizationId
                   AND calculation_id = ANY (:calculationIds)
                 ORDER BY calculation_id, evidence_role
                """)
                .param("organizationId", organizationId)
                .param("calculationIds", calculationIds.toArray(new UUID[0]))
                .query((ResultSet rs, int index) -> new EvidenceRow(
                        rs.getObject("calculation_id", UUID.class),
                        rs.getString("evidence_role"),
                        rs.getObject("reference_id", UUID.class),
                        rs.getObject("observed_at", Instant.class),
                        rs.getString("note")))
                .list();
    }

    private static CaseRow mapCase(ResultSet rs, int index) throws SQLException {
        return new CaseRow(
                rs.getObject("id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("platform_code"),
                rs.getObject("ad_native_object_id", UUID.class),
                rs.getString("native_object_kind"),
                rs.getString("native_object_key"),
                rs.getString("native_campaign_key"),
                rs.getString("native_object_name"),
                rs.getString("bidding_mode"),
                rs.getString("control_granularity_state"),
                rs.getInt("lineage_generation"),
                rs.getString("lane"),
                rs.getString("protection_tier"),
                rs.getString("cause_code"),
                rs.getString("evidence_state"),
                rs.getString("confidence_state"),
                textArray(rs, "blocker_codes"),
                rs.getString("contribution_profit_state"),
                rs.getBigDecimal("contribution_profit_amount"),
                rs.getString("profit_per_ad_rub_state"),
                rs.getBigDecimal("profit_per_ad_rub_value"),
                rs.getString("profit_currency_code"),
                rs.getString("official_spend_state"),
                rs.getBigDecimal("official_spend_amount"),
                rs.getString("eligible_traffic_state"),
                (Long) rs.getObject("eligible_traffic_count"),
                rs.getString("ad_linked_conversion_state"),
                rs.getBigDecimal("ad_linked_conversion_value"),
                rs.getString("ad_linked_conversion_stage"),
                rs.getString("max_cpc_state"),
                rs.getBigDecimal("max_cpc_amount"),
                rs.getString("attribution_gap_state"),
                rs.getBigDecimal("attribution_gap_ratio"),
                rs.getString("current_bid_state"),
                rs.getBigDecimal("current_bid_amount"),
                rs.getBigDecimal("recoverable_profit_amount"),
                rs.getBigDecimal("visible_rank"),
                rs.getString("policy_version_digest"),
                rs.getString("affected_set_digest"),
                rs.getString("resolution_state"),
                rs.getInt("affected_variant_count"),
                rs.getObject("as_of", Instant.class),
                rs.getObject("calculated_at", Instant.class),
                rs.getString("sustained_lane"),
                rs.getInt("sustained_cycles"),
                rs.getObject("sustained_since", Instant.class),
                rs.getObject("calculation_id", UUID.class));
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

}
