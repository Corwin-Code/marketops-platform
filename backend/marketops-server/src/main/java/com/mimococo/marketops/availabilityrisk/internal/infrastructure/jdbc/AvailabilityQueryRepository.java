package com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc;

import com.mimococo.marketops.availabilityrisk.AvailabilityRankFactorView;
import com.mimococo.marketops.availabilityrisk.DemandWindowView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the availability queue for the console.
 *
 * <p>Every query is organization-scoped in SQL and, for channel children, store
 * scoped as well. The controller authorizes first; this narrows anyway, because
 * a read path that depends on a caller having remembered to filter is one
 * refactor away from leaking.
 *
 * <p>Detail rows are read at each child's newest calculation. The older
 * generations remain for review but never appear on a live card.
 */
@Repository
public class AvailabilityQueryRepository {

    private final JdbcClient jdbc;

    public AvailabilityQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cards visible to a scope, most urgent first.
     *
     * <p>A card is visible when its company child is in the organization, or
     * when any of its channel children sits on a permitted store. A company
     * risk is about goods rather than a shop, so scoping it away because the
     * viewer holds only one store would hide the shortage that explains the
     * empty shelf they can see.
     */
    public List<CardRow> queue(UUID organizationId, UUID[] permittedStoreIds,
                               UUID[] permittedProductVariantIds, String laneFilter,
                               int limit, int offset) {
        return jdbc.sql("""
                        SELECT card.id, card.product_variant_id, variant.sku_code,
                               variant.display_name, visible.lane,
                               CASE WHEN visible.lane = 'HEALTHY' THEN NULL
                                    ELSE visible.child_id END AS triggering_child_id,
                               visible.rank_score, card.policy_version_digest, card.as_of,
                               card.calculated_at
                          FROM mart.availability_risk_card AS card
                          JOIN core.product_variant AS variant
                            ON variant.id = card.product_variant_id
                           AND variant.organization_id = card.organization_id
                          JOIN LATERAL (
                               SELECT child.id AS child_id, child.lane,
                                      (CASE child.lane
                                         WHEN 'CRITICAL' THEN 300000
                                         WHEN 'HIGH' THEN 200000
                                         WHEN 'REVIEW' THEN 200000
                                         WHEN 'UNRESOLVED' THEN 200000
                                         WHEN 'WATCH' THEN 100000 ELSE 0 END
                                       + LEAST(99999, GREATEST(0,
                                           coalesce(sum(factor.contribution), 0)))) AS rank_score
                                 FROM mart.availability_risk_child AS child
                                 LEFT JOIN mart.availability_risk_factor AS factor
                                   ON factor.calculation_id = child.calculation_id
                                  AND factor.organization_id = child.organization_id
                                WHERE child.card_id = card.id
                                  AND child.organization_id = card.organization_id
                                  AND (child.child_kind = 'COMPANY'
                                       OR child.store_id = ANY (:permittedStoreIds))
                                GROUP BY child.id, child.lane
                                ORDER BY rank_score DESC, child.id
                                LIMIT 1
                          ) AS visible ON true
                         WHERE card.organization_id = :organizationId
                           AND card.product_variant_id = ANY (:permittedProductVariantIds)
                           AND (CAST(:laneFilter AS text) IS NULL OR visible.lane = :laneFilter)
                         ORDER BY visible.rank_score DESC, variant.sku_code
                         LIMIT :limit OFFSET :offset
                        """)
                .param("organizationId", organizationId)
                .param("permittedStoreIds", permittedStoreIds)
                .param("permittedProductVariantIds", permittedProductVariantIds)
                .param("laneFilter", laneFilter)
                .param("limit", limit)
                .param("offset", offset)
                .query(AvailabilityQueryRepository::mapCard)
                .list();
    }

    /** One card by internal variant. */
    public java.util.Optional<CardRow> card(UUID organizationId, UUID productVariantId,
                                            UUID[] permittedStoreIds,
                                            UUID[] permittedProductVariantIds) {
        return jdbc.sql("""
                        SELECT card.id, card.product_variant_id, variant.sku_code,
                               variant.display_name, visible.lane,
                               CASE WHEN visible.lane = 'HEALTHY' THEN NULL
                                    ELSE visible.child_id END AS triggering_child_id,
                               visible.rank_score, card.policy_version_digest, card.as_of,
                               card.calculated_at
                          FROM mart.availability_risk_card AS card
                          JOIN core.product_variant AS variant
                            ON variant.id = card.product_variant_id
                           AND variant.organization_id = card.organization_id
                          JOIN LATERAL (
                               SELECT child.id AS child_id, child.lane,
                                      (CASE child.lane
                                         WHEN 'CRITICAL' THEN 300000
                                         WHEN 'HIGH' THEN 200000
                                         WHEN 'REVIEW' THEN 200000
                                         WHEN 'UNRESOLVED' THEN 200000
                                         WHEN 'WATCH' THEN 100000 ELSE 0 END
                                       + LEAST(99999, GREATEST(0,
                                           coalesce(sum(factor.contribution), 0)))) AS rank_score
                                 FROM mart.availability_risk_child AS child
                                 LEFT JOIN mart.availability_risk_factor AS factor
                                   ON factor.calculation_id = child.calculation_id
                                  AND factor.organization_id = child.organization_id
                                WHERE child.card_id = card.id
                                  AND child.organization_id = card.organization_id
                                  AND (child.child_kind = 'COMPANY'
                                       OR child.store_id = ANY (:permittedStoreIds))
                                GROUP BY child.id, child.lane
                                ORDER BY rank_score DESC, child.id
                                LIMIT 1
                          ) AS visible ON true
                         WHERE card.organization_id = :organizationId
                           AND card.product_variant_id = :productVariantId
                           AND card.product_variant_id = ANY (:permittedProductVariantIds)
                        """)
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .param("permittedStoreIds", permittedStoreIds)
                .param("permittedProductVariantIds", permittedProductVariantIds)
                .query(AvailabilityQueryRepository::mapCard)
                .optional();
    }

    /**
     * Children of the given cards.
     *
     * <p>A {@code null} scope means the caller has already authorized the exact
     * card and wants all of it. The queue path always passes a concrete scope;
     * only the single-card path, which authorized the variant itself, does not.
     */
    public List<ChildRow> children(UUID organizationId, UUID[] cardIds, UUID[] permittedStoreIds) {
        return jdbc.sql("""
                        SELECT child.id, child.card_id, child.child_kind, account.platform_code,
                               child.store_id, child.platform_listing_variant_id,
                               child.fulfillment_mode_code, child.lane, child.evidence_state,
                               child.confidence_state, child.cause_code, child.available_units,
                               child.daily_demand_rate, child.days_of_cover,
                               child.coverage_horizon_days, child.projected_stockout_at,
                               child.profit_lane, child.profit_at_risk_amount,
                               child.profit_at_risk_currency, child.demand_selection_reason,
                               child.conservative_proof, child.blocker_codes,
                               child.calculation_id, child.calculated_at
                          FROM mart.availability_risk_child AS child
                          LEFT JOIN core.store AS store
                            ON store.id = child.store_id
                           AND store.organization_id = child.organization_id
                          LEFT JOIN core.marketplace_account AS account
                            ON account.id = store.marketplace_account_id
                           AND account.organization_id = store.organization_id
                         WHERE child.organization_id = :organizationId
                           AND child.card_id = ANY (:cardIds)
                           AND (child.child_kind = 'COMPANY'
                                OR child.store_id = ANY (:permittedStoreIds))
                         ORDER BY child.child_kind, child.fulfillment_mode_code, child.id
                        """)
                .param("organizationId", organizationId)
                .param("cardIds", cardIds)
                .param("permittedStoreIds", permittedStoreIds)
                .query(AvailabilityQueryRepository::mapChild)
                .list();
    }

    /** The rank factors of the given calculations. */
    public List<FactorRow> factors(UUID organizationId, UUID[] calculationIds) {
        return jdbc.sql("""
                        SELECT calculation_id, factor_code, factor_value, factor_weight,
                               contribution, display_note
                          FROM mart.availability_risk_factor
                         WHERE organization_id = :organizationId
                           AND calculation_id = ANY (:calculationIds)
                         ORDER BY factor_code
                        """)
                .param("organizationId", organizationId)
                .param("calculationIds", calculationIds)
                .query((rows, rowNumber) -> new FactorRow(
                        rows.getObject("calculation_id", UUID.class),
                        new AvailabilityRankFactorView(rows.getString("factor_code"),
                                rows.getBigDecimal("factor_value"),
                                rows.getBigDecimal("factor_weight"),
                                rows.getBigDecimal("contribution"),
                                rows.getString("display_note"))))
                .list();
    }

    /** The demand windows of the given calculations. */
    public List<WindowRow> demandWindows(UUID organizationId, UUID[] calculationIds) {
        return jdbc.sql("""
                        SELECT calculation_id, window_code, period_start, period_end,
                               completed_units, daily_rate, observed_days, coverage_ratio,
                               sample_sufficient, censored, censoring_reason, outlier_share,
                               eligibility
                          FROM mart.demand_window_observation
                         WHERE organization_id = :organizationId
                           AND calculation_id = ANY (:calculationIds)
                         ORDER BY window_code
                        """)
                .param("organizationId", organizationId)
                .param("calculationIds", calculationIds)
                .query((rows, rowNumber) -> new WindowRow(
                        rows.getObject("calculation_id", UUID.class),
                        new DemandWindowView(rows.getString("window_code"),
                                rows.getTimestamp("period_start").toInstant(),
                                rows.getTimestamp("period_end").toInstant(),
                                integerOrNull(rows, "completed_units"),
                                rows.getBigDecimal("daily_rate"),
                                rows.getBigDecimal("observed_days"),
                                rows.getBigDecimal("coverage_ratio"),
                                rows.getBoolean("sample_sufficient"),
                                rows.getBoolean("censored"),
                                rows.getString("censoring_reason"),
                                rows.getBigDecimal("outlier_share"),
                                rows.getString("eligibility"))))
                .list();
    }

    private static CardRow mapCard(ResultSet rows, int rowNumber) throws SQLException {
        return new CardRow(
                rows.getObject("id", UUID.class),
                rows.getObject("product_variant_id", UUID.class),
                rows.getString("sku_code"),
                rows.getString("display_name"),
                rows.getString("lane"),
                rows.getObject("triggering_child_id", UUID.class),
                rows.getBigDecimal("rank_score"),
                rows.getString("policy_version_digest"),
                rows.getTimestamp("as_of").toInstant(),
                rows.getTimestamp("calculated_at").toInstant());
    }

    private static ChildRow mapChild(ResultSet rows, int rowNumber) throws SQLException {
        java.sql.Array blockers = rows.getArray("blocker_codes");
        return new ChildRow(
                rows.getObject("id", UUID.class),
                rows.getObject("card_id", UUID.class),
                rows.getString("child_kind"),
                rows.getString("platform_code"),
                rows.getObject("store_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                rows.getString("fulfillment_mode_code"),
                rows.getString("lane"),
                rows.getString("evidence_state"),
                rows.getString("confidence_state"),
                rows.getString("cause_code"),
                integerOrNull(rows, "available_units"),
                rows.getBigDecimal("daily_demand_rate"),
                rows.getBigDecimal("days_of_cover"),
                integerOrNull(rows, "coverage_horizon_days"),
                rows.getTimestamp("projected_stockout_at") == null
                        ? null : rows.getTimestamp("projected_stockout_at").toInstant(),
                rows.getString("profit_lane"),
                rows.getBigDecimal("profit_at_risk_amount"),
                rows.getString("profit_at_risk_currency"),
                rows.getString("demand_selection_reason"),
                rows.getString("conservative_proof"),
                blockers == null ? List.of() : List.of((String[]) blockers.getArray()),
                rows.getObject("calculation_id", UUID.class),
                rows.getTimestamp("calculated_at").toInstant());
    }

    private static Integer integerOrNull(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    /** One card row. */
    public record CardRow(UUID id, UUID productVariantId, String skuCode, String displayName,
                          String lane, UUID triggeringChildId, BigDecimal rankScore,
                          String policyVersionDigest, Instant asOf, Instant calculatedAt) {
    }

    /** One child row, with its proof still as stored text. */
    public record ChildRow(UUID id, UUID cardId, String childKind, String platformCode,
                           UUID storeId, UUID platformListingVariantId,
                           String fulfillmentModeCode, String lane, String evidenceState,
                           String confidenceState, String causeCode, Integer availableUnits,
                           BigDecimal dailyDemandRate, BigDecimal daysOfCover,
                           Integer coverageHorizonDays, Instant projectedStockoutAt,
                           String profitLane, BigDecimal profitAtRiskAmount,
                           String profitAtRiskCurrency, String demandSelectionReason,
                           String conservativeProof, List<String> blockerCodes,
                           UUID calculationId, Instant calculatedAt) {
    }

    /** One rank factor, keyed by the calculation that produced it. */
    public record FactorRow(UUID calculationId, AvailabilityRankFactorView factor) {
    }

    /** One demand window, keyed by the calculation that produced it. */
    public record WindowRow(UUID calculationId, DemandWindowView window) {
    }
}
