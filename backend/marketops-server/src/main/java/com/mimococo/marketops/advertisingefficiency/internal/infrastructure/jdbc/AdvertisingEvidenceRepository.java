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
        return affectedSet(organizationId, objectId, null);
    }

    public Optional<AffectedSetRow> affectedSet(UUID organizationId, UUID objectId, Instant at) {
        return jdbc.sql("""
                SELECT id, affected_set_digest, product_variant_ids,
                       platform_listing_variant_ids, resolution_state,
                       unresolved_reason_codes, resolved_at
                  FROM core.ad_affected_set
                 WHERE organization_id = :organizationId AND ad_native_object_id = :objectId
                 AND (CAST(:at AS timestamptz) IS NULL OR resolved_at <= :at)
                 ORDER BY resolved_at DESC, id DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("at", ts(at))
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
        return currentConfiguration(organizationId, objectId, null);
    }

    public Optional<ConfigurationRow> currentConfiguration(UUID organizationId, UUID objectId, Instant at) {
        return jdbc.sql("""
                SELECT c.id, c.provenance_id, c.semantic_profile_id, c.lineage_generation,
                       c.observed_bid_amount, c.bid_currency_code, c.bid_unit_code,
                       c.observed_status, c.observed_bidding_mode, c.evidence_grade,
                       c.observed_at, c.source_time
                  FROM core.ad_object_configuration_observation c
                 WHERE c.organization_id = :organizationId
                   AND c.ad_native_object_id = :objectId
                   AND (CAST(:at AS timestamptz) IS NULL OR c.observed_at <= :at)
                   AND NOT EXISTS (SELECT 1 FROM core.ad_object_configuration_observation later
                                    WHERE later.supersedes_observation_id = c.id
                                      AND (CAST(:at AS timestamptz) IS NULL OR later.observed_at <= :at))
                 ORDER BY c.observed_at DESC, c.id DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("at", ts(at))
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
            UUID latestFactId, BigDecimal coverageRatio, Instant acceptedAt,
            Instant coveredFrom, Instant coveredTo) {
        public ObjectFactAggregate(BigDecimal spendAmount, String currencyCode, Long impressions,
                Long views, Long clicks, Long providerAttributedOrders, BigDecimal providerAttributedRevenue,
                boolean everyWindowComplete, boolean anyCorrectionWindowOpen, Instant earliestSourceTime,
                Instant latestSourceTime, int factCount, UUID latestFactId) {
            this(spendAmount, currencyCode, impressions, views, clicks, providerAttributedOrders,
                    providerAttributedRevenue, everyWindowComplete, anyCorrectionWindowOpen,
                    earliestSourceTime, latestSourceTime, factCount, latestFactId, null, null, null, null);
        }
    }

    public Optional<ObjectFactAggregate> objectFacts(
            UUID organizationId, UUID objectId, Instant from, Instant to) {
        return objectFacts(organizationId, objectId, from, to, to);
    }

    public Optional<ObjectFactAggregate> objectFacts(
            UUID organizationId, UUID objectId, Instant from, Instant to, Instant readAt) {
        return jdbc.sql("""
                SELECT CASE WHEN count(f.spend_amount) = count(*) THEN sum(f.spend_amount) END AS spend_amount,
                       CASE WHEN count(DISTINCT f.currency_code) = 1 AND count(f.currency_code) = count(*) THEN min(f.currency_code) END AS currency_code,
                       CASE WHEN count(f.impressions) = count(*) THEN sum(f.impressions) END AS impressions,
                       CASE WHEN count(f.views) = count(*) THEN sum(f.views) END AS views,
                       CASE WHEN count(f.clicks) = count(*) THEN sum(f.clicks) END AS clicks,
                       CASE WHEN count(f.provider_attributed_orders) = count(*) THEN sum(f.provider_attributed_orders) END AS provider_orders,
                       CASE WHEN count(f.provider_attributed_revenue) = count(*) THEN sum(f.provider_attributed_revenue) END AS provider_revenue,
                       bool_and(f.report_window_complete AND NOT EXISTS (
                           SELECT 1 FROM ledger.ad_object_fact overlap
                           WHERE overlap.organization_id = f.organization_id
                             AND overlap.ad_native_object_id = f.ad_native_object_id AND overlap.id <> f.id
                             AND overlap.recorded_at <= :readAt
                             AND tstzrange(overlap.period_start, overlap.period_end, '[)') && tstzrange(f.period_start, f.period_end, '[)')
                             AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact correction
                                 WHERE correction.supersedes_fact_id = overlap.id AND correction.recorded_at <= :readAt))) AS every_window_complete,
                       bool_or(f.correction_window_open) AS any_correction_open,
                       min(f.source_time) AS earliest_source_time,
                       max(f.source_time) AS latest_source_time,
                       count(*) AS fact_count,
                       (SELECT sum(extract(epoch FROM upper(part) - lower(part)))
                          FROM unnest(range_agg(tstzrange(f.period_start, f.period_end, '[)'))) part) /
                           NULLIF(extract(epoch FROM (CAST(:to AS timestamptz) - CAST(:from AS timestamptz))), 0) AS coverage_ratio,
                       max(f.recorded_at) AS accepted_at,
                       min(f.period_start) AS covered_from, max(f.period_end) AS covered_to,
                       (SELECT latest.id FROM ledger.ad_object_fact latest
                         WHERE latest.ad_native_object_id = :objectId
                           AND latest.organization_id = :organizationId
                           AND latest.period_start >= :from AND latest.period_end <= :to
                           AND latest.recorded_at <= :readAt
                           AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact correction
                               WHERE correction.supersedes_fact_id = latest.id AND correction.recorded_at <= :readAt)
                         ORDER BY latest.recorded_at DESC, latest.id DESC LIMIT 1) AS latest_fact_id
                  FROM ledger.ad_object_fact f
                 WHERE f.organization_id = :organizationId
                   AND f.ad_native_object_id = :objectId
                   AND f.period_start >= :from AND f.period_end <= :to
                   AND f.recorded_at <= :readAt
                   AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                                    WHERE later.supersedes_fact_id = f.id AND later.recorded_at <= :readAt)
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("from", ts(from))
                .param("to", ts(to))
                .param("readAt", ts(readAt))
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
                        rs.getObject("latest_fact_id", UUID.class), rs.getBigDecimal("coverage_ratio"),
                        instantOf(rs, "accepted_at"), instantOf(rs, "covered_from"), instantOf(rs, "covered_to")))
                .optional()
                .filter(aggregate -> aggregate.factCount() > 0);
    }

    /** Deterministically linked sale events at one stage over a window. */
    public record LinkedSaleLine(
            UUID id, UUID provenanceId, UUID productVariantId, UUID platformListingVariantId,
            UUID affectedSetId, UUID conversionDefinitionId, String saleStage,
            String linkageBasis, long units, BigDecimal netSalesAmount, String currencyCode,
            Instant periodStart, Instant periodEnd, Instant sourceTime, Instant recordedAt) {
    }

    public record LinkedSaleAggregate(
            long eventCount, BigDecimal netSalesAmount, String currencyCode,
            long distinctVariants, UUID latestEventId, List<LinkedSaleLine> lines) {
        public LinkedSaleAggregate {
            lines = List.copyOf(lines);
        }
        public LinkedSaleAggregate(long eventCount, BigDecimal netSalesAmount, String currencyCode,
                long distinctVariants, UUID latestEventId) {
            this(eventCount, netSalesAmount, currencyCode, distinctVariants, latestEventId, List.of());
        }
    }

    public Optional<LinkedSaleAggregate> linkedSales(
            UUID organizationId, UUID objectId, String saleStage, Instant from, Instant to) {
        return linkedSales(organizationId, objectId, saleStage, from, to, to);
    }

    public Optional<LinkedSaleAggregate> linkedSales(
            UUID organizationId, UUID objectId, String saleStage, Instant from, Instant to, Instant readAt) {
        List<LinkedSaleLine> lines = jdbc.sql("""
                SELECT e.*, (SELECT CASE WHEN count(*) = 1 THEN (array_agg(m.product_variant_id))[1] END
                    FROM core.listing_mapping m
                    WHERE m.organization_id = e.organization_id
                      AND m.platform_listing_variant_id = e.platform_listing_variant_id
                      AND m.effective_from <= e.occurred_at
                      AND (m.effective_to IS NULL OR m.effective_to > e.occurred_at)
                      AND m.status IN ('ACTIVE', 'ENDED')) AS product_variant_id
                  FROM ledger.ad_linked_sale_event e
                 WHERE e.organization_id = :organizationId AND e.ad_native_object_id = :objectId
                   AND e.sale_stage = :saleStage
                   AND e.occurred_at >= :from AND e.occurred_at < :to
                   AND e.recorded_at <= :readAt
                   AND NOT EXISTS (SELECT 1 FROM ledger.ad_linked_sale_event later
                        WHERE later.supersedes_event_id = e.id AND later.recorded_at <= :readAt)
                 ORDER BY e.platform_listing_variant_id, e.id
                """).param("organizationId", organizationId).param("objectId", objectId)
                .param("saleStage", saleStage).param("from", ts(from)).param("to", ts(to)).param("readAt", ts(readAt))
                .query((rs, index) -> new LinkedSaleLine(
                        rs.getObject("id", UUID.class), rs.getObject("provenance_id", UUID.class),
                        rs.getObject("product_variant_id", UUID.class),
                        rs.getObject("platform_listing_variant_id", UUID.class),
                        rs.getObject("affected_set_id", UUID.class),
                        rs.getObject("conversion_definition_id", UUID.class), rs.getString("sale_stage"),
                        rs.getString("linkage_basis"), rs.getLong("event_count"),
                        rs.getBigDecimal("net_sales_amount"), rs.getString("currency_code"),
                        instantOf(rs, "period_start"), instantOf(rs, "period_end"),
                        instantOf(rs, "source_time"), instantOf(rs, "recorded_at"))).list();
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        boolean completeMoney = lines.stream().allMatch(line -> line.netSalesAmount() != null
                && line.currencyCode() != null && line.productVariantId() != null);
        List<String> currencies = lines.stream().map(LinkedSaleLine::currencyCode)
                .filter(java.util.Objects::nonNull).distinct().toList();
        String currency = completeMoney && currencies.size() == 1 ? currencies.getFirst() : null;
        BigDecimal net = currency == null ? null : lines.stream().map(LinkedSaleLine::netSalesAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(new LinkedSaleAggregate(lines.stream().mapToLong(LinkedSaleLine::units).sum(),
                net, currency, lines.stream().map(LinkedSaleLine::platformListingVariantId).distinct().count(),
                lines.stream().max(java.util.Comparator.comparing(LinkedSaleLine::recordedAt)
                        .thenComparing(LinkedSaleLine::id)).orElseThrow().id(), lines));
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
                   AND f.recorded_at <= :to
                   AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact later
                                    WHERE later.supersedes_fact_id = f.id AND later.recorded_at <= :to)
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

    public Optional<LinkedSaleAggregate> settledSales(UUID organization, UUID object,
            Instant from, Instant to, Instant readAt) {
        var retained = linkedSales(organization, object, "CANONICAL_AD_LINKED_RETAINED_SALE", from, to, readAt);
        if (retained.isEmpty()) { return Optional.empty(); }
        List<LinkedSaleLine> financialLines = new java.util.ArrayList<>();
        for (var line : retained.get().lines()) {
            record Financial(long units, BigDecimal net, String currency, UUID provenance, Instant source, Instant accepted) { }
            var financial = jdbc.sql("""
                    SELECT f.quantity, f.net_amount, f.currency_code, f.provenance_id, p.source_time, p.ingestion_time
                    FROM ledger.ad_settlement_attribution a JOIN ledger.sales_fact f ON f.id=a.settled_sales_fact_id
                    JOIN core.fact_provenance p ON p.id=f.provenance_id
                    WHERE a.organization_id=:organization AND a.ad_linked_sale_event_id=:line
                      AND a.accepted_at<=:at AND p.ingestion_time<=:at
                      AND NOT EXISTS (SELECT 1 FROM ledger.sales_fact corrected JOIN core.fact_provenance cp ON cp.id=corrected.provenance_id
                          WHERE corrected.supersedes_fact_id=f.id AND cp.ingestion_time<=:at)
                    ORDER BY f.id
                    """).param("organization", organization).param("line", line.id()).param("at", ts(readAt))
                    .query((rs,index)->new Financial(rs.getLong("quantity"),rs.getBigDecimal("net_amount"),
                            rs.getString("currency_code"),rs.getObject("provenance_id",UUID.class),
                            instantOf(rs,"source_time"),instantOf(rs,"ingestion_time"))).list();
            if (financial.isEmpty() || financial.stream().mapToLong(Financial::units).sum()!=line.units()
                    || financial.stream().anyMatch(value -> !java.util.Objects.equals(line.currencyCode(),value.currency()))) {
                return Optional.empty();
            }
            for (var value : financial) {
                financialLines.add(new LinkedSaleLine(line.id(),value.provenance(),line.productVariantId(),
                        line.platformListingVariantId(),line.affectedSetId(),line.conversionDefinitionId(),
                        line.saleStage(),line.linkageBasis(),value.units(),value.net(),value.currency(),
                        line.periodStart(),line.periodEnd(),value.source(),value.accepted()));
            }
        }
        return Optional.of(new LinkedSaleAggregate(retained.get().eventCount(),financialLines.stream()
                .map(LinkedSaleLine::netSalesAmount).reduce(BigDecimal.ZERO,BigDecimal::add),
                retained.get().currencyCode(),retained.get().distinctVariants(),retained.get().latestEventId(),financialLines));
    }

    public record CompanySales(long factCount,BigDecimal amount,String currency,List<UUID> provenanceIds) { }
    /** Same-stage, point-in-time company truth; a missing amount invalidates the entire sum. */
    public CompanySales companySales(UUID organization,UUID listing,String stage,Instant from,Instant to,Instant at) {
        return jdbc.sql("""
                SELECT count(*) fact_count,
                    CASE WHEN bool_and(f.net_amount IS NOT NULL) AND count(DISTINCT f.currency_code)=1 THEN sum(f.net_amount) END amount,
                    CASE WHEN count(DISTINCT f.currency_code)=1 THEN min(f.currency_code) END currency,
                    coalesce(array_agg(DISTINCT f.provenance_id),'{}'::uuid[]) provenance_ids
                FROM ledger.sales_fact f JOIN core.fact_provenance source ON source.id=f.provenance_id
                WHERE f.organization_id=:organization AND f.platform_listing_variant_id=:listing AND f.sale_stage=:stage
                    AND (:stage<>'RETAINED' OR f.retention_window_days=30) AND f.occurred_at>=:from AND f.occurred_at<:to
                    AND source.ingestion_time<=:at AND NOT EXISTS(SELECT 1 FROM ledger.sales_fact newer
                        JOIN core.fact_provenance source_new ON source_new.id=newer.provenance_id
                        WHERE newer.supersedes_fact_id=f.id AND source_new.ingestion_time<=:at)
                """).param("organization",organization).param("listing",listing).param("stage",stage).param("from",ts(from)).param("to",ts(to)).param("at",ts(at))
                .query((rs,index)->new CompanySales(rs.getLong("fact_count"),rs.getBigDecimal("amount"),rs.getString("currency"),
                        List.of((UUID[])rs.getArray("provenance_ids").getArray()))).single();
    }

    public record SettledCompanySales(BigDecimal netAmount,String currency,boolean complete,List<UUID> evidenceIds) { }

    /** Actual financial facts matched to each retained cohort order/line, not a retention proxy. */
    public SettledCompanySales settledCompanySales(UUID organization,UUID listing,Instant from,Instant to,Instant at) {
        record Line(long retained,long settled,BigDecimal amount,String currency,UUID retainedId,List<UUID> financialIds,boolean uniqueCohort) { }
        var lines=jdbc.sql("""
                WITH retained AS(SELECT r.* FROM ledger.sales_fact r JOIN core.fact_provenance source ON source.id=r.provenance_id
                    WHERE r.organization_id=:organization AND r.platform_listing_variant_id=:listing
                      AND r.sale_stage='RETAINED' AND r.retention_window_days=30 AND r.occurred_at>=:from AND r.occurred_at<:to
                      AND source.ingestion_time<=:at
                      AND NOT EXISTS(SELECT 1 FROM ledger.sales_fact newer JOIN core.fact_provenance provenance ON provenance.id=newer.provenance_id
                        WHERE newer.supersedes_fact_id=r.id AND provenance.ingestion_time<=:at))
                SELECT r.id,r.quantity AS retained_units,r.currency_code,sum(f.quantity) AS settled_units,
                    (SELECT count(*)=1 FROM retained other WHERE other.native_order_key=r.native_order_key
                        AND other.native_line_key IS NOT DISTINCT FROM r.native_line_key) AS unique_cohort,
                    CASE WHEN count(f.id)>0 AND bool_and(f.currency_code=r.currency_code) THEN sum(f.net_amount) END AS net_amount,
                    array_remove(array_agg(f.provenance_id),NULL) AS financial_ids
                FROM retained r LEFT JOIN ledger.sales_fact f ON f.organization_id=r.organization_id
                    AND f.platform_listing_variant_id=r.platform_listing_variant_id AND f.sale_stage='SETTLED'
                    AND f.native_order_key=r.native_order_key AND f.native_line_key IS NOT DISTINCT FROM r.native_line_key
                    AND EXISTS(SELECT 1 FROM core.fact_provenance provenance WHERE provenance.id=f.provenance_id AND provenance.ingestion_time<=:at)
                    AND NOT EXISTS(SELECT 1 FROM ledger.sales_fact newer JOIN core.fact_provenance provenance ON provenance.id=newer.provenance_id
                        WHERE newer.supersedes_fact_id=f.id AND provenance.ingestion_time<=:at)
                GROUP BY r.id,r.quantity,r.currency_code,r.native_order_key,r.native_line_key
                """).param("organization",organization).param("listing",listing).param("from",ts(from)).param("to",ts(to)).param("at",ts(at))
                .query((rs,index)->new Line(rs.getLong("retained_units"),rs.getLong("settled_units"),rs.getBigDecimal("net_amount"),
                        rs.getString("currency_code"),rs.getObject("id",UUID.class),List.of((UUID[])rs.getArray("financial_ids").getArray()),rs.getBoolean("unique_cohort"))).list();
        boolean complete=!lines.isEmpty() && lines.stream().allMatch(line->line.uniqueCohort() && line.retained()>0 && line.retained()==line.settled() && line.amount()!=null)
                && lines.stream().map(Line::currency).distinct().count()==1;
        return new SettledCompanySales(complete?lines.stream().map(Line::amount).reduce(BigDecimal.ZERO,BigDecimal::add):null,
                complete?lines.getFirst().currency():null,complete,lines.stream().flatMap(line->java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(line.retainedId()),line.financialIds().stream())).distinct().sorted().toList());
    }

    public boolean settlementAttributionComplete(UUID organization,UUID object,Instant from,Instant to,Instant at) {
        return settledSales(organization,object,from,to,at).isPresent();
    }

    public record RankContext(Instant firstRaisedAt, Instant earliestDueAt, long blockedProtection,
            long downstreamVariants, long blockedWork, UUID caseId) {
        public RankContext(Instant firstRaisedAt, Instant earliestDueAt, long blockedProtection, long downstreamVariants, long blockedWork) {
            this(firstRaisedAt,earliestDueAt,blockedProtection,downstreamVariants,blockedWork,null);
        }
    }

    public java.util.Map<String, RankContext> rankContexts(UUID organization, UUID object, Instant at) {
        // Dependency counts belong to the object. A newly projected case must
        // not change UNKNOWN into a known count on an identical as-of replay.
        RankContext dependencies=jdbc.sql("""
                SELECT (SELECT count(*) FROM mart.ad_case c WHERE c.organization_id=:organization
                    AND c.ad_native_object_id=:object AND c.lane='PROTECTION' AND c.created_at<:at
                    AND (c.superseded_at IS NULL OR c.superseded_at>=:at)) AS blocked_protection,
                  coalesce((SELECT cardinality(a.product_variant_ids) FROM core.ad_affected_set a
                    WHERE a.organization_id=:organization AND a.ad_native_object_id=:object AND a.resolved_at<=:at
                    ORDER BY a.resolved_at DESC,a.id LIMIT 1),0) AS downstream_variants,
                  (SELECT count(*) FROM ops.recommendation r WHERE r.organization_id=:organization
                    AND r.subject_id=:object AND r.created_at<:at AND r.state IN ('PROPOSED','APPROVED')) AS blocked_work
                """).param("organization",organization).param("object",object).param("at",ts(at))
                .query((rs,index)->new RankContext(at,null,rs.getLong("blocked_protection"),
                    rs.getLong("downstream_variants"),rs.getLong("blocked_work"),null)).single();
        var result=new java.util.HashMap<String,RankContext>();
        result.put("__OBJECT_DEPENDENCIES__",dependencies);
        jdbc.sql("""
                SELECT c.id AS case_id,c.case_key,coalesce(r.first_raised_at,c.created_at) AS created_at
                FROM mart.ad_case c LEFT JOIN ops.ad_case_responsibility r ON r.case_id=c.id
                WHERE c.organization_id=:organization AND c.ad_native_object_id=:object
                  AND (c.superseded_at IS NULL OR c.superseded_at>=:at) AND c.created_at<=:at
                """).param("organization",organization).param("object",object).param("at",ts(at))
                .query((rs,index)->java.util.Map.entry(rs.getString("case_key"),new RankContext(instantOf(rs,"created_at"),null,
                    dependencies.blockedProtection(),dependencies.downstreamVariants(),dependencies.blockedWork(),rs.getObject("case_id",UUID.class))))
                .list().forEach(entry->result.put(entry.getKey(),entry.getValue()));
        return java.util.Map.copyOf(result);
    }

    public record CriticalSignals(boolean regressed, boolean unknown, BigDecimal exposure,
            BigDecimal headroom, BigDecimal dualAxisGap, List<UUID> observationIds, BigDecimal perRubGap) {
        public CriticalSignals(boolean regressed,boolean unknown,BigDecimal exposure,BigDecimal headroom,BigDecimal absoluteGap,List<UUID> observations) {
            this(regressed,unknown,exposure,headroom,absoluteGap,observations,null);
        }
        public static CriticalSignals absent() { return new CriticalSignals(false,false,null,null,null,List.of()); }
    }

    /** Each frozen required product/channel unit is evaluated separately. */
    public CriticalSignals criticalSignals(UUID organization, UUID object, Instant at) {
        record Row(String state, BigDecimal baseline, BigDecimal observed, BigDecimal tolerance, UUID observation) { }
        List<Row> rows=jdbc.sql("""
                SELECT CASE WHEN guard.observed_at IS NULL THEN CASE WHEN :at>=landed.at+make_interval(hours=>(baseline.plan_snapshot->>'operationalHours')::integer,
                    mins=>(baseline.plan_snapshot->>'observationStartsMinutes')::integer) THEN 'UNKNOWN' ELSE 'NOT_DUE' END
                    WHEN :at>guard.observed_at+make_interval(mins=>coalesce((stage.snapshot->'freshnessProfile'->>'acceptedFactMaxAgeMinutes')::integer,
                        (stage.snapshot->'freshnessProfile'->>'sourceMaxAgeMinutes')::integer,0)) THEN 'UNKNOWN'
                    ELSE guard.guard_state END AS state, guard.baseline_sales,guard.observed_sales,
                    (baseline.plan_snapshot->>'salesTolerance')::numeric tolerance,guard.observation_id
                FROM ops.ad_outcome_baseline baseline
                JOIN ops.ad_outcome_critical_unit unit ON unit.outcome_baseline_id=baseline.id
                JOIN LATERAL(SELECT min(readback.observed_at) at FROM ops.ad_bid_command command
                        JOIN ops.ad_bid_command_readback readback ON readback.command_id=command.id
                        WHERE command.outcome_baseline_id=baseline.id AND readback.match_state='MATCHES_TARGET' AND readback.observed_at<=:at
                    UNION ALL SELECT proof.observed_at FROM ops.ad_manual_execution_packet packet
                        JOIN ops.ad_manual_configuration_verification proof ON proof.id=packet.current_proof_id
                        WHERE packet.outcome_baseline_id=baseline.id AND proof.proves_configuration AND proof.observed_at<=:at)
                    landed ON landed.at IS NOT NULL
                LEFT JOIN LATERAL(SELECT g.* FROM ops.ad_outcome_critical_guard g WHERE g.outcome_baseline_id=baseline.id
                    AND g.product_variant_id=unit.product_variant_id AND g.listing_variant_id=unit.listing_variant_id
                    AND g.observed_at<=:at ORDER BY g.observed_at DESC LIMIT 1) guard ON true
                LEFT JOIN ops.ad_outcome_observation observation ON observation.id=guard.observation_id
                LEFT JOIN ops.ad_outcome_stage_baseline stage ON stage.outcome_baseline_id=baseline.id
                    AND stage.stage=replace(observation.outcome_stage,'_REVISED','')
                WHERE baseline.organization_id=:organization AND baseline.ad_native_object_id=:object
                """).param("organization",organization).param("object",object).param("at",ts(at))
                .query((rs,index)->new Row(rs.getString("state"),rs.getBigDecimal("baseline_sales"),rs.getBigDecimal("observed_sales"),
                        rs.getBigDecimal("tolerance"),rs.getObject("observation_id",UUID.class))).list();
        record Gap(BigDecimal absolute,BigDecimal perRub,UUID observation) { }
        var gap=jdbc.sql("""
                SELECT axes.observed_absolute_profit-axes.baseline_absolute_profit absolute_gap,
                    axes.observed_profit_per_rub-axes.baseline_profit_per_rub per_rub_gap,observation.id
                FROM mart.ad_case current_case JOIN ops.ad_outcome_baseline baseline
                  ON baseline.case_calculation_id=current_case.calculation_id
                  AND baseline.policy_version_digest=current_case.policy_version_digest
                  AND baseline.affected_set_id=current_case.affected_set_id
                JOIN ops.ad_outcome_axes axes ON axes.outcome_baseline_id=baseline.id
                JOIN ops.ad_outcome_observation observation ON observation.id=axes.observation_id
                JOIN ops.ad_outcome_stage_baseline stage ON stage.outcome_baseline_id=baseline.id AND stage.stage='RETAINED'
                WHERE current_case.organization_id=:organization AND current_case.ad_native_object_id=:object
                    AND current_case.superseded_at IS NULL AND baseline.state='COMPLETE'
                    AND observation.outcome_stage IN('RETAINED','RETAINED_REVISED') AND observation.evaluated_at<=:at
                    AND observation.window_ends_at-observation.window_starts_at=interval '30 days' AND stage.window_hours=720
                    AND observation.guard_state='SATISFIED' AND axes.dual_axis_verdict<>'UNRESOLVED'
                    AND observation.evaluated_at+make_interval(mins=>least(
                        (stage.snapshot->'freshnessProfile'->>'acceptedFactMaxAgeMinutes')::integer,
                        (stage.snapshot->'freshnessProfile'->>'sourceMaxAgeMinutes')::integer))>=:at
                    AND NOT EXISTS(SELECT 1 FROM ops.ad_outcome_observation newer WHERE newer.supersedes_observation_id=observation.id AND newer.evaluated_at<=:at)
                ORDER BY observation.evaluated_at DESC,observation.id LIMIT 1
                """).param("organization",organization).param("object",object).param("at",ts(at))
                .query((rs,index)->new Gap(rs.getBigDecimal("absolute_gap"),rs.getBigDecimal("per_rub_gap"),rs.getObject("id",UUID.class))).optional();
        boolean unknown=rows.stream().anyMatch(row->"UNKNOWN".equals(row.state()));
        BigDecimal exposure=rows.stream().anyMatch(row->row.baseline()==null)?null:
                rows.stream().map(Row::baseline).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal headroom=unknown||rows.stream().anyMatch(row->row.baseline()==null||row.observed()==null||row.tolerance()==null)?null:
                rows.stream().map(row->row.observed().subtract(row.baseline().multiply(BigDecimal.ONE.subtract(row.tolerance()))))
                        .min(BigDecimal::compareTo).orElse(null);
        return new CriticalSignals(rows.stream().anyMatch(row->"REGRESSED".equals(row.state())),unknown,exposure,headroom,gap.map(Gap::absolute).orElse(null),
                java.util.stream.Stream.concat(rows.stream().map(Row::observation),gap.stream().map(Gap::observation))
                    .filter(java.util.Objects::nonNull).distinct().sorted().toList(),gap.map(Gap::perRub).orElse(null));
    }

    public boolean providerIncidentOpen(UUID organization, UUID object, Instant at) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM platform.ad_provider_incident incident
                    JOIN core.ad_native_object object ON object.platform_code = incident.platform_code
                    WHERE object.id = :object AND object.organization_id = :organization
                      AND incident.organization_id = :organization
                      AND (incident.store_id IS NULL OR incident.store_id = object.store_id)
                      AND incident.observed_at <= :at AND incident.valid_until > :at AND incident.incident_open)
                """).param("organization", organization).param("object", object).param("at", ts(at)).query(Boolean.class).single();
    }

    public boolean compensationPending(UUID organization, UUID object) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM ops.ad_bid_command WHERE organization_id = :organization
                    AND ad_native_object_id = :object AND state = 'COMPENSATION_PENDING')
                """).param("organization", organization).param("object", object).query(Boolean.class).single();
    }

    public int sustainedPeriods(UUID organizationId, UUID objectId, UUID policyId, Instant windowStart) {
        return jdbc.sql("""
                WITH RECURSIVE chain AS (
                    SELECT period_start FROM mart.ad_qualification_period
                    WHERE organization_id = :organization AND ad_native_object_id = :object
                      AND qualification_policy_id = :policy AND period_end = :windowStart AND qualified
                    UNION ALL
                    SELECT p.period_start FROM mart.ad_qualification_period p JOIN chain c ON p.period_end = c.period_start
                    WHERE p.organization_id = :organization AND p.ad_native_object_id = :object
                      AND p.qualification_policy_id = :policy AND p.qualified
                ) SELECT count(*) FROM chain
                """).param("organization", organizationId).param("object", objectId)
                .param("policy", policyId).param("windowStart", ts(windowStart)).query(Integer.class).single();
    }

    public boolean comparableBaseline(UUID organizationId, UUID objectId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM ledger.ad_object_fact f
                    WHERE organization_id = :organization AND ad_native_object_id = :object
                      AND period_start = :priorFrom AND period_end = :priorTo
                      AND report_window_complete AND NOT correction_window_open
                      AND recorded_at <= :at
                      AND NOT EXISTS (SELECT 1 FROM ledger.ad_object_fact correction
                          WHERE correction.supersedes_fact_id = f.id AND correction.recorded_at <= :at))
                """).param("organization", organizationId).param("object", objectId)
                .param("priorFrom", ts(from.minus(java.time.Duration.between(from, to))))
                .param("priorTo", ts(from)).param("at", ts(to)).query(Boolean.class).single();
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
