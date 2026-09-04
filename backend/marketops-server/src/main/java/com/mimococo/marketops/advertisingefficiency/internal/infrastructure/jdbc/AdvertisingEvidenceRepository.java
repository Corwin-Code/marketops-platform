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
 * Reads the facts an advertising calculation rests on, and decides nothing.
 *
 * <p>Every query here excludes superseded rows the same way: by the absence of a
 * later row pointing back at this one. That is the whole correction model — a
 * restatement is a new row with a supersession link, so "the current fact" is
 * "the fact nothing supersedes" rather than "the newest row", and a late
 * correction that arrives out of order still resolves correctly.
 *
 * <p>Nothing in this class interprets. A stale observation is returned with its
 * timestamps so the calculator can judge it against the applicable freshness
 * profile; it is not filtered out here, because filtering it here would make the
 * difference between "stale" and "absent" invisible to the only code that cares.
 */
@Repository
public class AdvertisingEvidenceRepository {

    private final JdbcClient jdbc;

    AdvertisingEvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The advertising object as the platform last showed it to us. */
    public record ObjectRow(
            UUID id, UUID organizationId, UUID storeId, String platformCode,
            UUID semanticProfileId, String nativeObjectKind, String nativeObjectKey,
            String nativeCampaignKey, String nativeObjectName, String biddingMode,
            String controlGranularityState, String lineageKey, int lineageGeneration,
            String observationState, Instant firstObservedAt, Instant lastObservedAt,
            String status) {

        /** Whether a controlled write could ever apply to this object. */
        public boolean independentlyControllable() {
            return "PROVEN_INDEPENDENT".equals(controlGranularityState);
        }
    }

    public Optional<ObjectRow> object(UUID organizationId, UUID objectId) {
        return jdbc.sql("""
                SELECT id, organization_id, store_id, platform_code, semantic_profile_id,
                       native_object_kind, native_object_key, native_campaign_key,
                       native_object_name, bidding_mode, control_granularity_state,
                       lineage_key, lineage_generation, observation_state,
                       first_observed_at, last_observed_at, status
                  FROM core.ad_native_object
                 WHERE id = :objectId AND organization_id = :organizationId
                """)
                .param("objectId", objectId)
                .param("organizationId", organizationId)
                .query(AdvertisingEvidenceRepository::mapObject)
                .optional();
    }

    /** Every advertising object in one organization, keyset-paged for the sweep. */
    public List<ObjectRow> objectsToReconcile(UUID organizationId, UUID afterId, int limit) {
        return jdbc.sql("""
                SELECT id, organization_id, store_id, platform_code, semantic_profile_id,
                       native_object_kind, native_object_key, native_campaign_key,
                       native_object_name, bidding_mode, control_granularity_state,
                       lineage_key, lineage_generation, observation_state,
                       first_observed_at, last_observed_at, status
                  FROM core.ad_native_object
                 WHERE organization_id = :organizationId AND status = 'ACTIVE'
                   AND (CAST(:afterId AS uuid) IS NULL OR id > CAST(:afterId AS uuid))
                 ORDER BY id
                 LIMIT :limit
                """)
                .param("organizationId", organizationId)
                .param("afterId", afterId)
                .param("limit", limit)
                .query(AdvertisingEvidenceRepository::mapObject)
                .list();
    }

    /** The most recently resolved affected set for one object. */
    public record AffectedSetRow(
            UUID id, String digest, List<UUID> productVariantIds,
            List<UUID> platformListingVariantIds, String resolutionState,
            List<String> unresolvedReasonCodes, Instant resolvedAt) {
    }

    public Optional<AffectedSetRow> affectedSet(UUID organizationId, UUID objectId) {
        return jdbc.sql("""
                SELECT id, affected_set_digest, product_variant_ids,
                       platform_listing_variant_ids, resolution_state,
                       unresolved_reason_codes, resolved_at
                  FROM core.ad_affected_set
                 WHERE organization_id = :organizationId AND ad_native_object_id = :objectId
                 ORDER BY resolved_at DESC, id DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .query((ResultSet rs, int index) -> new AffectedSetRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("affected_set_digest"),
                        uuidArray(rs, "product_variant_ids"),
                        uuidArray(rs, "platform_listing_variant_ids"),
                        rs.getString("resolution_state"),
                        textArray(rs, "unresolved_reason_codes"),
                        instantOf(rs, "resolved_at")))
                .optional();
    }

    /** The configuration observation nothing supersedes. */
    public record ConfigurationRow(
            UUID id, UUID provenanceId, UUID semanticProfileId, int lineageGeneration,
            BigDecimal observedBidAmount, String bidCurrencyCode, String bidUnitCode,
            String observedStatus, String observedBiddingMode, String evidenceGrade,
            Instant observedAt, Instant sourceTime) {
    }

    public Optional<ConfigurationRow> currentConfiguration(UUID organizationId, UUID objectId) {
        return jdbc.sql("""
                SELECT c.id, c.provenance_id, c.semantic_profile_id, c.lineage_generation,
                       c.observed_bid_amount, c.bid_currency_code, c.bid_unit_code,
                       c.observed_status, c.observed_bidding_mode, c.evidence_grade,
                       c.observed_at, c.source_time
                  FROM core.ad_object_configuration_observation c
                 WHERE c.organization_id = :organizationId
                   AND c.ad_native_object_id = :objectId
                   AND NOT EXISTS (SELECT 1 FROM core.ad_object_configuration_observation later
                                    WHERE later.supersedes_observation_id = c.id)
                 ORDER BY c.observed_at DESC, c.id DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .query((ResultSet rs, int index) -> new ConfigurationRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("provenance_id", UUID.class),
                        rs.getObject("semantic_profile_id", UUID.class),
                        rs.getInt("lineage_generation"),
                        rs.getBigDecimal("observed_bid_amount"),
                        rs.getString("bid_currency_code"),
                        rs.getString("bid_unit_code"),
                        rs.getString("observed_status"),
                        rs.getString("observed_bidding_mode"),
                        rs.getString("evidence_grade"),
                        instantOf(rs, "observed_at"),
                        instantOf(rs, "source_time")))
                .optional();
    }

    /**
     * Official spend, traffic and provider attribution over a window.
     *
     * <p>Summed across the live facts only. A measure that no live fact reports
     * comes back as {@code null} rather than zero, which is the distinction the
     * whole model turns on, so the aggregates use {@code sum} without a
     * {@code coalesce}.
     */
    public record ObjectFactAggregate(
            BigDecimal spendAmount, String currencyCode, Long impressions, Long views,
            Long clicks, Long providerAttributedOrders, BigDecimal providerAttributedRevenue,
            boolean everyWindowComplete, boolean anyCorrectionWindowOpen,
            Instant earliestSourceTime, Instant latestSourceTime, int factCount,
            UUID latestFactId) {
    }

    public Optional<ObjectFactAggregate> objectFacts(
            UUID organizationId, UUID objectId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT sum(f.spend_amount) AS spend_amount,
                       min(f.currency_code) AS currency_code,
                       sum(f.impressions) AS impressions,
                       sum(f.views) AS views,
                       sum(f.clicks) AS clicks,
                       sum(f.provider_attributed_orders) AS provider_orders,
                       sum(f.provider_attributed_revenue) AS provider_revenue,
                       bool_and(f.report_window_complete) AS every_window_complete,
                       bool_or(f.correction_window_open) AS any_correction_open,
                       min(f.source_time) AS earliest_source_time,
                       max(f.source_time) AS latest_source_time,
                       count(*) AS fact_count,
                       (SELECT latest.id FROM ledger.ad_object_fact latest
                         WHERE latest.ad_native_object_id = :objectId
                           AND latest.organization_id = :organizationId
                         ORDER BY latest.recorded_at DESC, latest.id DESC LIMIT 1) AS latest_fact_id
                  FROM ledger.ad_object_fact f
                 WHERE f.organization_id = :organizationId
                   AND f.ad_native_object_id = :objectId
                   AND f.period_start >= :from AND f.period_end <= :to
                   AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                                    WHERE later.supersedes_fact_id = f.id)
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("from", ts(from))
                .param("to", ts(to))
                .query((ResultSet rs, int index) -> new ObjectFactAggregate(
                        rs.getBigDecimal("spend_amount"),
                        rs.getString("currency_code"),
                        longOf(rs, "impressions"),
                        longOf(rs, "views"),
                        longOf(rs, "clicks"),
                        longOf(rs, "provider_orders"),
                        rs.getBigDecimal("provider_revenue"),
                        rs.getObject("every_window_complete") != null
                                && rs.getBoolean("every_window_complete"),
                        rs.getObject("any_correction_open") != null
                                && rs.getBoolean("any_correction_open"),
                        instantOf(rs, "earliest_source_time"),
                        instantOf(rs, "latest_source_time"),
                        rs.getInt("fact_count"),
                        rs.getObject("latest_fact_id", UUID.class)))
                .optional()
                .filter(aggregate -> aggregate.factCount() > 0);
    }

    /** Deterministically linked sale events at one stage over a window. */
    public record LinkedSaleAggregate(
            long eventCount, BigDecimal netSalesAmount, String currencyCode,
            long distinctVariants, UUID latestEventId) {
    }

    public Optional<LinkedSaleAggregate> linkedSales(
            UUID organizationId, UUID objectId, String saleStage, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT coalesce(sum(e.event_count), 0) AS event_count,
                       sum(e.net_sales_amount) AS net_sales_amount,
                       min(e.currency_code) AS currency_code,
                       count(DISTINCT e.platform_listing_variant_id) AS distinct_variants,
                       (SELECT latest.id FROM ledger.ad_linked_sale_event latest
                         WHERE latest.ad_native_object_id = :objectId
                           AND latest.organization_id = :organizationId
                           AND latest.sale_stage = :saleStage
                         ORDER BY latest.recorded_at DESC, latest.id DESC LIMIT 1) AS latest_event_id
                  FROM ledger.ad_linked_sale_event e
                 WHERE e.organization_id = :organizationId
                   AND e.ad_native_object_id = :objectId
                   AND e.sale_stage = :saleStage
                   AND e.occurred_at >= :from AND e.occurred_at < :to
                   AND NOT EXISTS (SELECT 1 FROM ledger.ad_linked_sale_event later
                                    WHERE later.supersedes_event_id = e.id)
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("saleStage", saleStage)
                .param("from", ts(from))
                .param("to", ts(to))
                .query((ResultSet rs, int index) -> new LinkedSaleAggregate(
                        java.util.Objects.requireNonNullElse(longOf(rs, "event_count"), 0L),
                        rs.getBigDecimal("net_sales_amount"),
                        rs.getString("currency_code"),
                        rs.getLong("distinct_variants"),
                        rs.getObject("latest_event_id", UUID.class)))
                .optional()
                .filter(aggregate -> aggregate.eventCount() > 0 || aggregate.latestEventId() != null);
    }

    /** One variant's observed or allocated share, with the basis stated. */
    public record VariantShareRow(
            UUID productVariantId, UUID platformListingVariantId, String skuCode,
            String displayName, String basis, String confidenceState, BigDecimal spendAmount,
            Long clicks, String currencyCode) {
    }

    public List<VariantShareRow> variantShares(
            UUID organizationId, UUID objectId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT v.id AS product_variant_id, a.platform_listing_variant_id,
                       v.sku_code, v.display_name,
                       min(a.basis) AS basis,
                       min(a.confidence_state) AS confidence_state,
                       sum(a.allocated_spend_amount) AS spend_amount,
                       sum(a.allocated_clicks) AS clicks,
                       min(a.currency_code) AS currency_code
                  FROM ledger.ad_object_listing_allocation a
                  JOIN ledger.ad_object_fact f ON f.id = a.ad_object_fact_id
                  JOIN core.listing_mapping m
                    ON m.platform_listing_variant_id = a.platform_listing_variant_id
                   AND m.organization_id = a.organization_id
                   AND m.status = 'ACTIVE'
                  JOIN core.product_variant v
                    ON v.id = m.product_variant_id AND v.organization_id = a.organization_id
                 WHERE a.organization_id = :organizationId
                   AND f.ad_native_object_id = :objectId
                   AND f.period_start >= :from AND f.period_end <= :to
                   AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                                    WHERE later.supersedes_fact_id = f.id)
                 GROUP BY v.id, a.platform_listing_variant_id, v.sku_code, v.display_name
                 ORDER BY v.sku_code
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("from", ts(from))
                .param("to", ts(to))
                .query((ResultSet rs, int index) -> new VariantShareRow(
                        rs.getObject("product_variant_id", UUID.class),
                        rs.getObject("platform_listing_variant_id", UUID.class),
                        rs.getString("sku_code"),
                        rs.getString("display_name"),
                        rs.getString("basis"),
                        rs.getString("confidence_state"),
                        rs.getBigDecimal("spend_amount"),
                        longOf(rs, "clicks"),
                        rs.getString("currency_code")))
                .list();
    }

    /** Whether a live reservation, containment or unresolved command touches this object. */
    public record ContainmentRow(
            boolean reservationHeldElsewhere, List<String> containmentKinds,
            boolean unresolvedCommandOpen) {
    }

    public ContainmentRow containment(UUID organizationId, UUID objectId, String affectedDigest) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM ops.ad_action_reservation r
                                WHERE r.organization_id = :organizationId
                                  AND r.state = 'ACTIVE'
                                  AND r.ad_native_object_id <> :objectId
                                  AND r.affected_set_digest = :affectedDigest) AS held_elsewhere,
                       (SELECT coalesce(array_agg(DISTINCT c.containment_kind), '{}')
                          FROM ops.ad_containment c
                         WHERE c.organization_id = :organizationId
                           AND c.state <> 'REENABLED'
                           AND (c.ad_native_object_id = :objectId
                                OR c.affected_set_digest = :affectedDigest)) AS containment_kinds,
                       EXISTS (SELECT 1 FROM ops.ad_bid_command cmd
                                WHERE cmd.organization_id = :organizationId
                                  AND cmd.ad_native_object_id = :objectId
                                  AND cmd.state IN ('UNKNOWN_REQUIRES_READBACK',
                                                    'READBACK_MISMATCH',
                                                    'LATER_CHANGE_OR_MISMATCH_INVESTIGATION',
                                                    'MANUAL_RESOLUTION')) AS unresolved_command
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("affectedDigest", affectedDigest)
                .query((ResultSet rs, int index) -> new ContainmentRow(
                        rs.getBoolean("held_elsewhere"),
                        textArray(rs, "containment_kinds"),
                        rs.getBoolean("unresolved_command")))
                .single();
    }

    private static ObjectRow mapObject(ResultSet rs, int index) throws SQLException {
        return new ObjectRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("platform_code"),
                rs.getObject("semantic_profile_id", UUID.class),
                rs.getString("native_object_kind"),
                rs.getString("native_object_key"),
                rs.getString("native_campaign_key"),
                rs.getString("native_object_name"),
                rs.getString("bidding_mode"),
                rs.getString("control_granularity_state"),
                rs.getString("lineage_key"),
                rs.getInt("lineage_generation"),
                rs.getString("observation_state"),
                instantOf(rs, "first_observed_at"),
                instantOf(rs, "last_observed_at"),
                rs.getString("status"));
    }

    private static List<UUID> uuidArray(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((UUID[]) array.getArray());
    }

    private static List<String> textArray(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
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

    /**
     * Read a possibly-summed integer column.
     *
     * <p>PostgreSQL widens {@code sum(bigint)} to {@code numeric}, so a column
     * that is a {@code bigint} in the table arrives as a {@link java.math.BigDecimal}
     * through an aggregate. Reading it as a {@code Long} works until somebody
     * adds a {@code sum}, which is exactly the kind of failure that surfaces in
     * production rather than in review.
     */
    private static Long longOf(ResultSet rs, String column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.longValueExact();
    }
}
