package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

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
 * The aggregate reads every canonical metric is computed from.
 *
 * <p>Aggregation happens in the database because summing is a deterministic
 * relational operation and moving thousands of rows into the application to add
 * them up would make a metric run proportional to history rather than to the
 * window it asked about. Business judgement stays out of these queries: they sum
 * and group, and every decision about what a sum means is made by the caller.
 *
 * <p>Every aggregate is grouped by currency rather than summed across it. A
 * total that mixed currencies would be a confident number that means nothing, so
 * the caller receives one row per currency and refuses when there is more than
 * one.
 *
 * <p>Superseded rows are excluded everywhere. A correction is written as a new
 * row naming the one it replaces, so counting both would double the fact the
 * correction exists to fix.
 */
@Repository
public class FactQueryRepository {

    /**
     * Excludes any row that a later row supersedes.
     *
     * <p>Written once and reused, because a query that forgot it would silently
     * count a correction and the fact it corrects as two separate events.
     */
    private static final String NOT_SUPERSEDED = """
             AND NOT EXISTS (
                 SELECT 1 FROM %1$s AS superseding
                  WHERE superseding.supersedes_fact_id = %2$s.id)
            """;

    private final JdbcClient jdbc;

    FactQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The most recent price observation at or before an instant. */
    public Optional<PriceRow> latestPrice(UUID listingVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT price.id, price.observed_at, price.currency_code,
                               price.list_price, price.selling_price, price.discount_price,
                               price.promotion_active, price.provenance_id,
                               provenance.source_time
                          FROM core.listing_price_observation AS price
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = price.provenance_id
                         WHERE price.platform_listing_variant_id = :listingVariantId
                           AND price.observed_at <= :asOf
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_price_observation", "price")
                        + """
                         ORDER BY price.observed_at DESC, price.id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new PriceRow(
                        rows.getObject("id", UUID.class),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getString("currency_code"),
                        rows.getBigDecimal("list_price"),
                        rows.getBigDecimal("selling_price"),
                        rows.getBigDecimal("discount_price"),
                        rows.getString("promotion_active"),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .optional();
    }

    /**
     * The most recent availability per fulfillment mode at or before an instant.
     *
     * <p>Each mode is answered from its own latest observation, because a source
     * that reports one mode more often than another must not make the other look
     * stale or absent.
     */
    public List<StockRow> latestStockByMode(UUID listingVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (stock.fulfillment_mode_code)
                               stock.fulfillment_mode_code, stock.available_quantity,
                               stock.reserved_quantity, stock.observed_at,
                               stock.provenance_id, provenance.source_time
                          FROM core.listing_stock_observation AS stock
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = stock.provenance_id
                         WHERE stock.platform_listing_variant_id = :listingVariantId
                           AND stock.observed_at <= :asOf
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_stock_observation", "stock")
                        + """
                         ORDER BY stock.fulfillment_mode_code, stock.observed_at DESC,
                                  stock.id DESC
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new StockRow(
                        rows.getString("fulfillment_mode_code"),
                        integerOrNull(rows, "available_quantity"),
                        integerOrNull(rows, "reserved_quantity"),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .list();
    }

    /**
     * Funnel measures summed over a window.
     *
     * <p>Each measure is summed independently so a period that reported clicks
     * but not impressions contributes what it has. A measure no contributing row
     * reported stays null, which the caller reads as NOT_AVAILABLE.
     */
    public Optional<TrafficRow> traffic(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT sum(traffic.impressions) AS impressions,
                               sum(traffic.clicks) AS clicks,
                               sum(traffic.visits) AS visits,
                               sum(traffic.add_to_cart) AS add_to_cart,
                               sum(traffic.ordered_units) AS ordered_units,
                               array_agg(DISTINCT traffic.provenance_id) AS provenance_ids,
                               min(provenance.source_time) AS oldest_source_time
                          FROM core.listing_traffic_observation AS traffic
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = traffic.provenance_id
                         WHERE traffic.platform_listing_variant_id = :listingVariantId
                           AND traffic.period_start >= :from
                           AND traffic.period_end <= :to
                        """
                        + NOT_SUPERSEDED.formatted(
                                "core.listing_traffic_observation", "traffic")
                        + """
                        HAVING count(*) > 0
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new TrafficRow(
                        longOrNull(rows, "impressions"),
                        longOrNull(rows, "clicks"),
                        longOrNull(rows, "visits"),
                        longOrNull(rows, "add_to_cart"),
                        longOrNull(rows, "ordered_units"),
                        uuidArray(rows, "provenance_ids"),
                        instantOrNull(rows, "oldest_source_time")))
                .optional();
    }

    /** Sales at one stage over a window, one row per currency. */
    public List<MoneyGroupRow> sales(UUID listingVariantId,
                                     String saleStage,
                                     Integer retentionWindowDays,
                                     Instant from,
                                     Instant to) {
        return jdbc.sql("""
                        SELECT sale.currency_code,
                               sum(sale.quantity) AS quantity,
                               sum(sale.gross_amount) AS gross_amount,
                               sum(sale.net_amount) AS net_amount,
                               array_agg(DISTINCT sale.provenance_id) AS provenance_ids,
                               min(provenance.source_time) AS oldest_source_time
                          FROM ledger.sales_fact AS sale
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = sale.provenance_id
                         WHERE sale.platform_listing_variant_id = :listingVariantId
                           AND sale.sale_stage = :saleStage
                           AND (CAST(:retentionWindowDays AS integer) IS NULL
                                OR sale.retention_window_days
                                       = CAST(:retentionWindowDays AS integer))
                           AND sale.occurred_at >= :from
                           AND sale.occurred_at < :to
                        """
                        + NOT_SUPERSEDED.formatted("ledger.sales_fact", "sale")
                        + """
                         GROUP BY sale.currency_code
                        """)
                .param("listingVariantId", listingVariantId)
                .param("saleStage", saleStage)
                .param("retentionWindowDays", retentionWindowDays)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new MoneyGroupRow(
                        rows.getString("currency_code"),
                        rows.getLong("quantity"),
                        rows.getBigDecimal("gross_amount"),
                        rows.getBigDecimal("net_amount"),
                        null,
                        uuidArray(rows, "provenance_ids"),
                        instantOrNull(rows, "oldest_source_time")))
                .list();
    }

    /** Returns over a window, one row per currency. */
    public List<MoneyGroupRow> returns(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT returned.currency_code,
                               sum(returned.quantity) AS quantity,
                               sum(returned.refund_amount) AS refund_amount,
                               sum(returned.loss_amount) AS loss_amount,
                               array_agg(DISTINCT returned.provenance_id) AS provenance_ids,
                               min(provenance.source_time) AS oldest_source_time
                          FROM ledger.return_fact AS returned
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = returned.provenance_id
                         WHERE returned.platform_listing_variant_id = :listingVariantId
                           AND returned.occurred_at >= :from
                           AND returned.occurred_at < :to
                        """
                        + NOT_SUPERSEDED.formatted("ledger.return_fact", "returned")
                        + """
                         GROUP BY returned.currency_code
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new MoneyGroupRow(
                        rows.getString("currency_code"),
                        rows.getLong("quantity"),
                        rows.getBigDecimal("refund_amount"),
                        rows.getBigDecimal("loss_amount"),
                        null,
                        uuidArray(rows, "provenance_ids"),
                        instantOrNull(rows, "oldest_source_time")))
                .list();
    }

    /** Returned units per internal reason category over a window. */
    public List<ReasonCountRow> returnsByReason(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT returned.reason_category, sum(returned.quantity) AS quantity
                          FROM ledger.return_fact AS returned
                         WHERE returned.platform_listing_variant_id = :listingVariantId
                           AND returned.occurred_at >= :from
                           AND returned.occurred_at < :to
                        """
                        + NOT_SUPERSEDED.formatted("ledger.return_fact", "returned")
                        + """
                         GROUP BY returned.reason_category
                         ORDER BY returned.reason_category
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new ReasonCountRow(
                        rows.getString("reason_category"), rows.getLong("quantity")))
                .list();
    }

    /** Platform charges over a window, one row per currency and category. */
    public List<FeeGroupRow> fees(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT fee.currency_code, fee.fee_category,
                               sum(fee.amount) AS amount,
                               bool_and(fee.settlement_state = 'SETTLED') AS settled_only,
                               array_agg(DISTINCT fee.provenance_id) AS provenance_ids,
                               min(provenance.source_time) AS oldest_source_time
                          FROM ledger.finance_fee_fact AS fee
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = fee.provenance_id
                         WHERE fee.platform_listing_variant_id = :listingVariantId
                           AND fee.occurred_at >= :from
                           AND fee.occurred_at < :to
                        """
                        + NOT_SUPERSEDED.formatted("ledger.finance_fee_fact", "fee")
                        + """
                         GROUP BY fee.currency_code, fee.fee_category
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new FeeGroupRow(
                        rows.getString("currency_code"),
                        rows.getString("fee_category"),
                        rows.getBigDecimal("amount"),
                        rows.getBoolean("settled_only"),
                        uuidArray(rows, "provenance_ids"),
                        instantOrNull(rows, "oldest_source_time")))
                .list();
    }

    /** Advertising spend and effect over a window, one row per currency. */
    public List<AdvertisingGroupRow> advertising(UUID listingVariantId,
                                                 Instant from,
                                                 Instant to) {
        return jdbc.sql("""
                        SELECT spend.currency_code,
                               sum(spend.spend_amount) AS spend_amount,
                               sum(spend.impressions) AS impressions,
                               sum(spend.clicks) AS clicks,
                               sum(spend.attributed_orders) AS attributed_orders,
                               sum(spend.attributed_revenue) AS attributed_revenue,
                               array_agg(DISTINCT spend.provenance_id) AS provenance_ids,
                               min(provenance.source_time) AS oldest_source_time
                          FROM ledger.ad_spend_fact AS spend
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = spend.provenance_id
                         WHERE spend.platform_listing_variant_id = :listingVariantId
                           AND spend.period_start >= :from
                           AND spend.period_end <= :to
                        """
                        + NOT_SUPERSEDED.formatted("ledger.ad_spend_fact", "spend")
                        + """
                         GROUP BY spend.currency_code
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new AdvertisingGroupRow(
                        rows.getString("currency_code"),
                        rows.getBigDecimal("spend_amount"),
                        longOrNull(rows, "impressions"),
                        longOrNull(rows, "clicks"),
                        longOrNull(rows, "attributed_orders"),
                        rows.getBigDecimal("attributed_revenue"),
                        uuidArray(rows, "provenance_ids"),
                        instantOrNull(rows, "oldest_source_time")))
                .list();
    }

    /** The purchase cost version in force at an instant. */
    public Optional<CostRow> unitCost(UUID productVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT cost.id, cost.unit_cost, cost.currency_code,
                               cost.effective_from, cost.provenance_id
                          FROM core.cost_version AS cost
                         WHERE cost.product_variant_id = :productVariantId
                           AND cost.cost_kind = 'PURCHASE'
                           AND cost.status = 'ACTIVE'
                           AND cost.effective_from <= :asOf
                           AND (cost.effective_to IS NULL OR cost.effective_to > :asOf)
                        """)
                .param("productVariantId", productVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new CostRow(
                        rows.getObject("id", UUID.class),
                        rows.getBigDecimal("unit_cost"),
                        rows.getString("currency_code"),
                        rows.getTimestamp("effective_from").toInstant(),
                        rows.getObject("provenance_id", UUID.class)))
                .optional();
    }

    /**
     * The finance input in force at an instant, most specific scope first.
     *
     * <p>The ordering is the resolution rule and is expressed here so every
     * caller resolves identically: a variant-scoped version wins over a
     * store-scoped one, which wins over an organization-scoped one.
     */
    public Optional<FinanceInputRow> financeInput(UUID organizationId,
                                                  String inputCode,
                                                  UUID storeId,
                                                  UUID productVariantId,
                                                  Instant asOf) {
        return jdbc.sql("""
                        SELECT input.id, input.input_code, input.value_kind,
                               input.rate_value, input.amount_value, input.currency_code,
                               input.effective_from, input.provenance_id,
                               CASE input.scope_kind
                                   WHEN 'PRODUCT_VARIANT' THEN 1
                                   WHEN 'STORE' THEN 2
                                   ELSE 3
                               END AS specificity
                          FROM core.finance_input_version AS input
                         WHERE input.organization_id = :organizationId
                           AND input.input_code = :inputCode
                           AND input.status = 'ACTIVE'
                           AND input.effective_from <= :asOf
                           AND (input.effective_to IS NULL OR input.effective_to > :asOf)
                           AND (input.scope_kind = 'ORGANIZATION'
                                OR (input.scope_kind = 'STORE'
                                    AND input.store_ref_id = CAST(:storeId AS uuid))
                                OR (input.scope_kind = 'PRODUCT_VARIANT'
                                    AND input.product_variant_ref_id
                                            = CAST(:productVariantId AS uuid)))
                         ORDER BY specificity, input.effective_from DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("inputCode", inputCode)
                .param("storeId", storeId)
                .param("productVariantId", productVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new FinanceInputRow(
                        rows.getObject("id", UUID.class),
                        rows.getString("input_code"),
                        rows.getString("value_kind"),
                        rows.getBigDecimal("rate_value"),
                        rows.getBigDecimal("amount_value"),
                        rows.getString("currency_code"),
                        rows.getTimestamp("effective_from").toInstant(),
                        rows.getObject("provenance_id", UUID.class)))
                .optional();
    }

    /** The most recent internal stock snapshot at or before an instant. */
    public Optional<InternalStockRow> internalStock(UUID productVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT sum(latest.quantity_on_hand) AS quantity_on_hand,
                               sum(latest.quantity_reserved) AS quantity_reserved,
                               max(latest.observed_at) AS observed_at,
                               -- The provenance of the newest contributing
                               -- snapshot, which is the one an operator asking
                               -- "where did this come from" means. PostgreSQL
                               -- has no min() over an identifier, and picking an
                               -- arbitrary one would name a source that does not
                               -- explain the number beside it.
                               (array_agg(latest.provenance_id
                                          ORDER BY latest.observed_at DESC))[1]
                                   AS provenance_id
                          FROM (
                              SELECT DISTINCT ON (snapshot.warehouse_id)
                                     snapshot.quantity_on_hand, snapshot.quantity_reserved,
                                     snapshot.observed_at, snapshot.provenance_id
                                FROM core.internal_stock_snapshot AS snapshot
                               WHERE snapshot.product_variant_id = :productVariantId
                                 AND snapshot.observed_at <= :asOf
                               ORDER BY snapshot.warehouse_id, snapshot.observed_at DESC,
                                        snapshot.id DESC
                          ) AS latest
                        HAVING count(*) > 0
                        """)
                .param("productVariantId", productVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new InternalStockRow(
                        rows.getInt("quantity_on_hand"),
                        integerOrNull(rows, "quantity_reserved"),
                        instantOrNull(rows, "observed_at"),
                        rows.getObject("provenance_id", UUID.class)))
                .optional();
    }

    /**
     * Listing variants on one store that any source reported activity for.
     *
     * <p>Deriving the subject list from facts keeps a metric run proportional to
     * what happened rather than to how many listings exist, and it means a
     * listing nobody has heard from does not produce a page of empty metrics.
     */
    public List<UUID> listingVariantsWithActivity(UUID storeId,
                                                  Instant from,
                                                  Instant to,
                                                  int limit) {
        return jdbc.sql("""
                        SELECT DISTINCT subject.platform_listing_variant_id
                          FROM (
                              SELECT sale.platform_listing_variant_id
                                FROM ledger.sales_fact AS sale
                               WHERE sale.store_id = :storeId
                                 AND sale.occurred_at >= :from AND sale.occurred_at < :to
                              UNION
                              SELECT traffic.platform_listing_variant_id
                                FROM core.listing_traffic_observation AS traffic
                                JOIN core.platform_listing_variant AS variant
                                  ON variant.id = traffic.platform_listing_variant_id
                                JOIN core.platform_listing AS listing
                                  ON listing.id = variant.platform_listing_id
                               WHERE listing.store_id = :storeId
                                 AND traffic.period_start >= :from AND traffic.period_end <= :to
                              UNION
                              SELECT price.platform_listing_variant_id
                                FROM core.listing_price_observation AS price
                                JOIN core.platform_listing_variant AS variant
                                  ON variant.id = price.platform_listing_variant_id
                                JOIN core.platform_listing AS listing
                                  ON listing.id = variant.platform_listing_id
                               WHERE listing.store_id = :storeId
                                 AND price.observed_at >= :from AND price.observed_at < :to
                          ) AS subject
                         ORDER BY subject.platform_listing_variant_id
                         LIMIT :pageLimit
                        """)
                .param("storeId", storeId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("pageLimit", limit)
                .query(UUID.class)
                .list();
    }

    private static Instant instantOrNull(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long longOrNull(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer integerOrNull(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static List<UUID> uuidArray(ResultSet rows, String column) throws SQLException {
        java.sql.Array array = rows.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object[] elements = (Object[]) array.getArray();
        return java.util.Arrays.stream(elements)
                .filter(java.util.Objects::nonNull)
                .map(element -> element instanceof UUID uuid ? uuid : UUID.fromString(element.toString()))
                .toList();
    }

    /** One observed price state. */
    public record PriceRow(
            UUID id, Instant observedAt, String currencyCode, BigDecimal listPrice,
            BigDecimal sellingPrice, BigDecimal discountPrice, String promotionActive,
            UUID provenanceId, Instant sourceTime) {
    }

    /** The latest availability of one fulfillment mode. */
    public record StockRow(
            String fulfillmentModeCode, Integer availableQuantity, Integer reservedQuantity,
            Instant observedAt, UUID provenanceId, Instant sourceTime) {
    }

    /** Funnel measures summed over a window. */
    public record TrafficRow(
            Long impressions, Long clicks, Long visits, Long addToCart, Long orderedUnits,
            List<UUID> provenanceIds, Instant oldestSourceTime) {
    }

    /** A money aggregate for one currency. */
    public record MoneyGroupRow(
            String currencyCode, long quantity, BigDecimal primaryAmount,
            BigDecimal secondaryAmount, String category,
            List<UUID> provenanceIds, Instant oldestSourceTime) {
    }

    /** Returned units for one internal reason category. */
    public record ReasonCountRow(String reasonCategory, long quantity) {
    }

    /** A charge aggregate for one currency and category. */
    public record FeeGroupRow(
            String currencyCode, String feeCategory, BigDecimal amount, boolean settledOnly,
            List<UUID> provenanceIds, Instant oldestSourceTime) {
    }

    /** An advertising aggregate for one currency. */
    public record AdvertisingGroupRow(
            String currencyCode, BigDecimal spendAmount, Long impressions, Long clicks,
            Long attributedOrders, BigDecimal attributedRevenue,
            List<UUID> provenanceIds, Instant oldestSourceTime) {
    }

    /** The purchase cost version in force. */
    public record CostRow(
            UUID id, BigDecimal unitCost, String currencyCode, Instant effectiveFrom,
            UUID provenanceId) {
    }

    /** The finance input version in force. */
    public record FinanceInputRow(
            UUID id, String inputCode, String valueKind, BigDecimal rateValue,
            BigDecimal amountValue, String currencyCode, Instant effectiveFrom,
            UUID provenanceId) {
    }

    /** Internal stock summed across warehouses. */
    public record InternalStockRow(
            int quantityOnHand, Integer quantityReserved, Instant observedAt,
            UUID provenanceId) {
    }
}
