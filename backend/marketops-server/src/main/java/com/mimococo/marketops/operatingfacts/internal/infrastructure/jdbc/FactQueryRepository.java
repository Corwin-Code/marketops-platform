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

    /** The most recent price observation strictly before an exclusive instant. */
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
                           AND price.observed_at < :asOf
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
     * The price observation whose buyer price (the promotion price when there is one, the selling
     * price otherwise) was lowest from an inclusive instant to an exclusive one, among those in the
     * currency of the newest observation before that end; the newest of equal lows.
     */
    public Optional<PriceRow> lowestBuyerPrice(UUID listingVariantId, Instant from, Instant asOf) {
        return jdbc.sql("""
                        SELECT price.id, price.observed_at, price.currency_code,
                               price.list_price, price.selling_price, price.discount_price,
                               price.promotion_active, price.provenance_id,
                               provenance.source_time
                          FROM core.listing_price_observation AS price
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = price.provenance_id
                         WHERE price.platform_listing_variant_id = :listingVariantId
                           AND price.observed_at >= :from AND price.observed_at < :asOf
                           AND coalesce(price.discount_price, price.selling_price) > 0
                           AND price.currency_code = (
                               SELECT newest.currency_code
                                 FROM core.listing_price_observation AS newest
                                WHERE newest.platform_listing_variant_id = :listingVariantId
                                  AND newest.observed_at < :asOf
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_price_observation", "newest")
                        + """
                                ORDER BY newest.observed_at DESC, newest.id DESC
                                LIMIT 1)
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_price_observation", "price")
                        + """
                         ORDER BY coalesce(price.discount_price, price.selling_price),
                                  price.observed_at DESC, price.id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
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
     * The most recent price before an exclusive instant with its competitor
     * price, seller cost and tariffs.
     */
    public Optional<PriceTermsRow> latestPriceTerms(UUID listingVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT price.id, price.observed_at, price.currency_code,
                               coalesce(price.discount_price, price.selling_price) AS buyer_price,
                               price.platform_competitor_min_price, price.platform_competitor_currency_code,
                               price.price_index_native, price.seller_cost_price,
                               price.sales_commission_percent_fbs, price.sales_commission_percent_fbo,
                               price.fbs_first_mile_max, price.fbs_direct_flow_max, price.fbs_last_mile,
                               price.fbo_direct_flow_max, price.fbo_last_mile, price.acquiring_max,
                               price.vat_rate, price.provenance_id, provenance.source_time
                          FROM core.listing_price_observation AS price
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = price.provenance_id
                         WHERE price.platform_listing_variant_id = :listingVariantId
                           AND price.observed_at < :asOf
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_price_observation", "price")
                        + """
                         ORDER BY price.observed_at DESC, price.id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new PriceTermsRow(
                        rows.getObject("id", UUID.class),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getString("currency_code"),
                        rows.getBigDecimal("buyer_price"),
                        rows.getBigDecimal("platform_competitor_min_price"),
                        rows.getString("platform_competitor_currency_code"),
                        rows.getString("price_index_native"),
                        rows.getBigDecimal("seller_cost_price"),
                        rows.getBigDecimal("sales_commission_percent_fbs"),
                        rows.getBigDecimal("sales_commission_percent_fbo"),
                        rows.getBigDecimal("fbs_first_mile_max"),
                        rows.getBigDecimal("fbs_direct_flow_max"),
                        rows.getBigDecimal("fbs_last_mile"),
                        rows.getBigDecimal("fbo_direct_flow_max"),
                        rows.getBigDecimal("fbo_last_mile"),
                        rows.getBigDecimal("acquiring_max"),
                        rows.getBigDecimal("vat_rate"),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .optional();
    }

    /**
     * The newest search period of a listing variant that ended at or before an
     * instant and after the oldest end still accepted.
     */
    public Optional<SearchRow> latestSearchDemand(UUID listingVariantId, Instant asOf, Instant oldestEnd) {
        return jdbc.sql("""
                        SELECT search.search_users, search.period_start, search.period_end,
                               search.provenance_id, provenance.source_time
                          FROM core.listing_search_observation AS search
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = search.provenance_id
                         WHERE search.platform_listing_variant_id = :listingVariantId
                           AND search.period_end <= :asOf
                           AND search.period_end > :oldestEnd
                         ORDER BY search.period_end DESC, search.period_start DESC, search.id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .param("oldestEnd", Timestamp.from(oldestEnd))
                .query((rows, rowNumber) -> new SearchRow(
                        rows.getLong("search_users"),
                        rows.getTimestamp("period_start").toInstant(),
                        rows.getTimestamp("period_end").toInstant(),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .optional();
    }

    /**
     * The most searched terms of a listing variant's newest search period ending at or before an
     * instant, the same ranking the store diagnosis shows.
     */
    public List<SearchTermRow> topSearchTerms(UUID listingVariantId, Instant asOf, int limit) {
        return jdbc.sql("""
                        WITH latest AS (
                            SELECT period_start, period_end
                              FROM core.listing_search_term_observation
                             WHERE platform_listing_variant_id = :listingVariantId AND period_end <= :asOf
                             ORDER BY period_end DESC, period_start DESC
                             LIMIT 1
                        )
                        SELECT term.search_term, term.search_users, term.ordered_count,
                               term.period_start, term.period_end
                          FROM core.listing_search_term_observation AS term
                          JOIN latest
                            ON latest.period_start = term.period_start AND latest.period_end = term.period_end
                         WHERE term.platform_listing_variant_id = :listingVariantId
                         ORDER BY term.search_users DESC, term.search_term
                         LIMIT :termLimit
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .param("termLimit", limit)
                .query((rows, rowNumber) -> new SearchTermRow(
                        rows.getString("search_term"),
                        rows.getLong("search_users"),
                        (Long) rows.getObject("ordered_count"),
                        rows.getTimestamp("period_start").toInstant(),
                        rows.getTimestamp("period_end").toInstant()))
                .list();
    }

    /** One search term of a period. */
    public record SearchTermRow(String term, long searchUsers, Long orderedUnits, Instant periodStart,
                                Instant periodEnd) {
    }

    /** The newest content rating of a listing variant before an exclusive instant. */
    public Optional<ContentRow> latestContentRating(UUID listingVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT content.content_rating, content.observed_at, content.provenance_id,
                               provenance.source_time
                          FROM core.listing_content_observation AS content
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = content.provenance_id
                         WHERE content.platform_listing_variant_id = :listingVariantId
                           AND content.observed_at < :asOf
                         ORDER BY content.observed_at DESC, content.id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new ContentRow(
                        rows.getBigDecimal("content_rating"),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .optional();
    }

    /**
     * The most recent availability per fulfillment mode before an exclusive instant.
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
                           AND stock.observed_at < :asOf
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

    /** Units ordered per UTC day of one listing variant inside a window, oldest first (P10). */
    public List<DayOrdersRow> dailyOrderedUnits(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT CAST(traffic.period_start AT TIME ZONE 'UTC' AS date) AS day,
                               sum(traffic.ordered_units) AS ordered_units
                          FROM core.listing_traffic_observation AS traffic
                         WHERE traffic.platform_listing_variant_id = :listingVariantId
                           AND traffic.period_start >= :from
                           AND traffic.period_end <= :to
                           AND traffic.ordered_units IS NOT NULL
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_traffic_observation", "traffic")
                        + """
                         GROUP BY 1
                         ORDER BY 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new DayOrdersRow(rows.getObject("day", java.time.LocalDate.class),
                        rows.getLong("ordered_units")))
                .list();
    }

    /**
     * Every price snapshot of one listing variant inside a window, oldest first, with the buyer price
     * (promotion price, else selling price, else list price) and whether a seller promotion ran: the
     * promotion price stated and different from the selling price, as the price write gate tests it.
     */
    public List<PricePointRow> pricePoints(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT price.observed_at, price.currency_code,
                               coalesce(price.discount_price, price.selling_price, price.list_price) AS buyer_price,
                               (price.discount_price IS NOT NULL AND price.selling_price IS NOT NULL
                                AND price.discount_price <> price.selling_price) AS seller_promotion,
                               price.price_index_native
                          FROM core.listing_price_observation AS price
                         WHERE price.platform_listing_variant_id = :listingVariantId
                           AND price.observed_at >= :from
                           AND price.observed_at < :to
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_price_observation", "price")
                        + """
                         ORDER BY price.observed_at, price.id
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new PricePointRow(
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getString("currency_code"),
                        rows.getBigDecimal("buyer_price"),
                        rows.getBoolean("seller_promotion"),
                        rows.getString("price_index_native")))
                .list();
    }

    /**
     * Units available per stock snapshot of one listing variant inside a window, summed over the
     * fulfillment modes the snapshot reported, oldest first.
     */
    public List<StockPointRow> stockPoints(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT stock.observed_at,
                               CAST(sum(stock.available_quantity) AS integer) AS available_units
                          FROM core.listing_stock_observation AS stock
                         WHERE stock.platform_listing_variant_id = :listingVariantId
                           AND stock.observed_at >= :from
                           AND stock.observed_at < :to
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_stock_observation", "stock")
                        + """
                         GROUP BY stock.observed_at
                         ORDER BY stock.observed_at
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new StockPointRow(rows.getTimestamp("observed_at").toInstant(),
                        integerOrNull(rows, "available_units")))
                .list();
    }

    /** Every sellability statement about one listing variant inside a window, oldest first. */
    public List<SellablePointRow> sellabilityPoints(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT health.observed_at, health.sellable
                          FROM core.listing_health_observation AS health
                         WHERE health.platform_listing_variant_id = :listingVariantId
                           AND health.observed_at >= :from
                           AND health.observed_at < :to
                           AND health.sellable IS NOT NULL
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_health_observation", "health")
                        + """
                         ORDER BY health.observed_at, health.id
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new SellablePointRow(rows.getTimestamp("observed_at").toInstant(),
                        rows.getString("sellable")))
                .list();
    }

    /** The search periods of one listing variant lying wholly inside a window, oldest first. */
    public List<SearchRow> searchDemandWithin(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT search.search_users, search.period_start, search.period_end,
                               search.provenance_id, provenance.source_time
                          FROM core.listing_search_observation AS search
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = search.provenance_id
                         WHERE search.platform_listing_variant_id = :listingVariantId
                           AND search.period_start >= :from
                           AND search.period_end <= :to
                         ORDER BY search.period_end, search.period_start, search.id
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new SearchRow(
                        rows.getLong("search_users"),
                        rows.getTimestamp("period_start").toInstant(),
                        rows.getTimestamp("period_end").toInstant(),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .list();
    }

    /**
     * The UTC days inside a window on which any listing of the store has a daily order record, oldest
     * first; the same notion of coverage the store diagnosis counts its order window by.
     */
    public List<java.time.LocalDate> storeOrderDays(UUID storeId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT DISTINCT CAST(traffic.period_start AT TIME ZONE 'UTC' AS date) AS day
                          FROM core.listing_traffic_observation AS traffic
                          JOIN core.platform_listing_variant AS variant
                            ON variant.id = traffic.platform_listing_variant_id
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                         WHERE listing.store_id = :storeId
                           AND traffic.period_start >= :from
                           AND traffic.period_end <= :to
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_traffic_observation", "traffic")
                        + """
                         ORDER BY 1
                        """)
                .param("storeId", storeId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> rows.getObject("day", java.time.LocalDate.class))
                .list();
    }

    /** The store's daily order facts over a window, summed across its listings (P10). */
    public com.mimococo.marketops.operatingfacts.StoreOrderTotals storeOrders(UUID storeId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT count(DISTINCT CAST(traffic.period_start AT TIME ZONE 'UTC' AS date)) AS days,
                               sum(traffic.ordered_units) AS units,
                               count(DISTINCT traffic.platform_listing_variant_id)
                                   FILTER (WHERE traffic.ordered_units > 0) AS listings
                          FROM core.listing_traffic_observation AS traffic
                          JOIN core.platform_listing_variant AS variant
                            ON variant.id = traffic.platform_listing_variant_id
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                         WHERE listing.store_id = :storeId
                           AND traffic.period_start >= :from
                           AND traffic.period_end <= :to
                        """
                        + NOT_SUPERSEDED.formatted("core.listing_traffic_observation", "traffic"))
                .param("storeId", storeId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> {
                    int days = rows.getInt("days");
                    return days == 0
                            ? new com.mimococo.marketops.operatingfacts.StoreOrderTotals(0, null, null)
                            : new com.mimococo.marketops.operatingfacts.StoreOrderTotals(days,
                                    rows.getObject("units") == null ? 0L : rows.getLong("units"),
                                    rows.getInt("listings"));
                })
                .single();
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

    /** Latest non-superseded authoritative coverage snapshot for one exact report window. */
    public Optional<ReturnQualityEvidenceRow> returnQualityEvidence(
            UUID listingVariantId, Instant from, Instant to, Instant asOf) {
        return jdbc.sql("""
                        SELECT report.id, report.completed_coverage,
                               report.retained_coverage, report.return_coverage,
                               report.qc_coverage, report.completed_source_updated_at,
                               report.retained_source_updated_at,
                               report.return_source_updated_at, report.qc_source_updated_at,
                               report.evidence_reference, report.accepted_at
                          FROM ledger.return_quality_evidence_snapshot AS report
                         WHERE report.platform_listing_variant_id = :listingVariantId
                           AND report.report_window_start <= :from
                           AND report.report_window_end >= :to
                           AND report.accepted_at <= :asOf
                           AND NOT EXISTS (
                               SELECT 1
                                 FROM ledger.return_quality_evidence_snapshot AS successor
                                WHERE successor.supersedes_snapshot_id = report.id
                                  AND successor.accepted_at <= :asOf)
                         ORDER BY report.accepted_at DESC, report.id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new ReturnQualityEvidenceRow(
                        rows.getObject("id", UUID.class),
                        rows.getString("completed_coverage"),
                        rows.getString("retained_coverage"),
                        rows.getString("return_coverage"),
                        rows.getString("qc_coverage"),
                        instantOrNull(rows, "completed_source_updated_at"),
                        instantOrNull(rows, "retained_source_updated_at"),
                        instantOrNull(rows, "return_source_updated_at"),
                        instantOrNull(rows, "qc_source_updated_at"),
                        rows.getString("evidence_reference"),
                        instantOrNull(rows, "accepted_at")))
                .optional();
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

    /** Retained purchase-cost intervals intersecting the period, with explicit knowledge-time fencing. */
    public List<com.mimococo.marketops.operatingfacts.CostPeriodSnapshot> purchaseCosts(UUID organizationId,
            UUID productVariantId, Instant periodStart, Instant periodEnd, Instant asOf) {
        if (organizationId == null || productVariantId == null || periodStart == null || periodEnd == null
                || asOf == null || !periodStart.isBefore(periodEnd)) return List.of();
        return jdbc.sql("""
                SELECT c.id,c.unit_cost,c.currency_code,c.effective_from,c.effective_to,c.provenance_id
                  FROM core.cost_version c JOIN core.fact_provenance p
                    ON p.id=c.provenance_id AND p.organization_id=c.organization_id
                 WHERE c.organization_id=:org AND c.product_variant_id=:variant AND c.cost_kind='PURCHASE'
                   AND c.status IN ('ACTIVE','ENDED') AND c.effective_from<:end
                   AND (c.effective_to IS NULL OR c.effective_to>:start)
                   AND c.created_at<=:at AND p.ingestion_time<=:at AND p.source_time<=:at
                 ORDER BY c.effective_from,c.id
                """).param("org",organizationId).param("variant",productVariantId)
                .param("start",Timestamp.from(periodStart)).param("end",Timestamp.from(periodEnd)).param("at",Timestamp.from(asOf))
                .query((r,n)->new com.mimococo.marketops.operatingfacts.CostPeriodSnapshot(
                    new com.mimococo.marketops.operatingfacts.CostSnapshot(r.getObject("id",UUID.class),
                        com.mimococo.marketops.shared.Money.of(r.getBigDecimal("unit_cost"),r.getString("currency_code")),
                        r.getTimestamp("effective_from").toInstant(),r.getObject("provenance_id",UUID.class)),
                    instantOrNull(r,"effective_to"))).list();
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
                           AND cost.effective_from < :asOf
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
    public List<FinanceInputRow> promotionRevenueInputs(UUID organizationId,UUID storeId,UUID listingId,
            String promotionKind,String nativePromotionKey,String termsDigest,Instant periodStart,Instant periodEnd,Instant asOf) {
        if (periodStart==null || periodEnd==null || asOf==null || !periodStart.isBefore(periodEnd))
            throw com.mimococo.marketops.shared.OperationRejectedException.of(com.mimococo.marketops.shared.ErrorCode.VALIDATION_FAILED);
        return jdbc.sql("""
                SELECT f.id,f.input_code,f.value_kind,f.rate_value,f.amount_value,f.currency_code,f.effective_from,f.provenance_id
                FROM core.finance_input_version f JOIN core.fact_provenance p ON p.id=f.provenance_id
                WHERE f.organization_id=:org AND p.organization_id=:org AND f.store_ref_id=:store
                  AND f.scope_kind='PROMOTION' AND f.promotion_listing_ref_id=:listing AND f.promotion_terms_digest=:digest
                  AND f.input_code IN ('PROMOTION_BUYER_PAYMENT_PER_UNIT','PROMOTION_SELLER_REVENUE_PER_UNIT','PROMOTION_PLATFORM_COMPENSATION_PER_UNIT')
                  AND f.promotion_kind=:kind AND f.native_promotion_key=:key AND f.status IN ('ACTIVE','ENDED')
                  AND f.value_kind='AMOUNT' AND f.effective_from<=:start AND f.effective_to>=:end
                  AND f.created_at<=:at AND p.ingestion_time<=:at AND p.source_time<=:at
                ORDER BY f.input_code
                """).param("org",organizationId).param("store",storeId).param("listing",listingId).param("digest",termsDigest)
                .param("kind",promotionKind).param("key",nativePromotionKey).param("start",Timestamp.from(periodStart))
                .param("end",Timestamp.from(periodEnd)).param("at",Timestamp.from(asOf))
                .query((rs,n)->new FinanceInputRow(rs.getObject("id",UUID.class),rs.getString("input_code"),rs.getString("value_kind"),
                        rs.getBigDecimal("rate_value"),rs.getBigDecimal("amount_value"),rs.getString("currency_code"),
                        rs.getTimestamp("effective_from").toInstant(),rs.getObject("provenance_id",UUID.class))).list();
    }

    public Optional<FinanceInputRow> promotionFixedFee(UUID organizationId,UUID storeId,String promotionKind,
            String nativePromotionKey,Instant periodStart,Instant periodEnd,Instant asOf) {
        if (periodStart==null || periodEnd==null || asOf==null || !periodStart.isBefore(periodEnd))
            throw com.mimococo.marketops.shared.OperationRejectedException.of(com.mimococo.marketops.shared.ErrorCode.VALIDATION_FAILED);
        return jdbc.sql("""
                SELECT f.id,f.input_code,f.value_kind,f.rate_value,f.amount_value,f.currency_code,f.effective_from,f.provenance_id
                FROM core.finance_input_version f JOIN core.fact_provenance p ON p.id=f.provenance_id
                WHERE f.organization_id=:org AND p.organization_id=:org AND f.store_ref_id=:store
                  AND f.scope_kind='PROMOTION' AND f.input_code='PROMOTION_FIXED_FEE'
                  AND f.promotion_kind=:kind AND f.native_promotion_key=:key
                  AND f.status IN ('ACTIVE','ENDED') AND f.value_kind='AMOUNT'
                  AND f.effective_from<=:start AND f.effective_to>=:end
                  AND f.created_at<=:at AND p.ingestion_time<=:at AND p.source_time<=:at
                """).param("org",organizationId).param("store",storeId).param("kind",promotionKind).param("key",nativePromotionKey)
                .param("start",Timestamp.from(periodStart)).param("end",Timestamp.from(periodEnd)).param("at",Timestamp.from(asOf))
                .query((rs,n)->new FinanceInputRow(rs.getObject("id",UUID.class),rs.getString("input_code"),rs.getString("value_kind"),
                        rs.getBigDecimal("rate_value"),rs.getBigDecimal("amount_value"),rs.getString("currency_code"),
                        rs.getTimestamp("effective_from").toInstant(),rs.getObject("provenance_id",UUID.class))).optional();
    }

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
                           AND input.effective_from < :asOf
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

    /** The most recent internal stock snapshot before an exclusive instant. */
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
                                 AND snapshot.observed_at < :asOf
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

    /** Completed units per day across a window, oldest first. */
    public List<DailySaleRow> dailyCompletedUnits(UUID listingVariantId, Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT CAST(date_trunc('day', sale.occurred_at AT TIME ZONE 'UTC')
                                    AS date) AS sale_day,
                               sum(sale.quantity) AS completed_units
                          FROM ledger.sales_fact AS sale
                         WHERE sale.platform_listing_variant_id = :listingVariantId
                           AND sale.sale_stage = 'COMPLETED'
                           AND sale.occurred_at >= :from
                           AND sale.occurred_at < :to
                           AND NOT EXISTS (
                               SELECT 1 FROM ledger.sales_fact AS newer
                                WHERE newer.supersedes_fact_id = sale.id)
                         GROUP BY 1
                         ORDER BY 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new DailySaleRow(
                        rows.getObject("sale_day", java.time.LocalDate.class),
                        rows.getLong("completed_units")))
                .list();
    }

    /** The most recent sellability statement before an exclusive instant. */
    public Optional<SellabilityRow> latestSellability(UUID listingVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT health.observed_at, health.sellable,
                               health.blocked_reason_native, health.provenance_id
                          FROM core.listing_health_observation AS health
                         WHERE health.platform_listing_variant_id = :listingVariantId
                           AND health.observed_at < :asOf
                           AND NOT EXISTS (
                               SELECT 1 FROM core.listing_health_observation AS newer
                                WHERE newer.supersedes_fact_id = health.id)
                         ORDER BY health.observed_at DESC, health.id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new SellabilityRow(
                        instantOrNull(rows, "observed_at"),
                        rows.getString("sellable"),
                        rows.getString("blocked_reason_native"),
                        rows.getObject("provenance_id", UUID.class)))
                .optional();
    }

    /**
     * The latest snapshot of each warehouse before an exclusive instant.
     *
     * <p>Deliberately unsummed. Deciding whether a platform view is the same
     * goods as a warehouse holds is impossible once the warehouses have been
     * added together.
     */
    public List<WarehouseStockRow> internalStockByWarehouse(UUID productVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (snapshot.warehouse_id)
                               snapshot.warehouse_id, snapshot.quantity_on_hand,
                               snapshot.quantity_reserved, snapshot.quantity_quality_locked,
                               snapshot.quantity_damaged, snapshot.quantity_written_off,
                               snapshot.sellable, snapshot.observed_at,
                               snapshot.provenance_id
                          FROM core.internal_stock_snapshot AS snapshot
                         WHERE snapshot.product_variant_id = :productVariantId
                           AND snapshot.observed_at < :asOf
                         ORDER BY snapshot.warehouse_id, snapshot.observed_at DESC,
                                  snapshot.id DESC
                        """)
                .param("productVariantId", productVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new WarehouseStockRow(
                        rows.getObject("warehouse_id", UUID.class),
                        rows.getInt("quantity_on_hand"),
                        integerOrNull(rows, "quantity_reserved"),
                        integerOrNull(rows, "quantity_quality_locked"),
                        integerOrNull(rows, "quantity_damaged"),
                        integerOrNull(rows, "quantity_written_off"),
                        rows.getString("sellable"),
                        instantOrNull(rows, "observed_at"),
                        rows.getObject("provenance_id", UUID.class)))
                .list();
    }

    /**
     * Stock and sellability observations across a window, oldest first.
     *
     * <p>Both kinds arrive in one ordered stream because the caller has to
     * replay them together: a listing becomes unsellable at one instant and
     * runs out at another, and only the merged sequence says which periods a
     * sale was actually possible in.
     */
    public List<AvailabilityRow> availabilityObservations(UUID listingVariantId,
                                                          String fulfillmentModeCode,
                                                          Instant from,
                                                          Instant to) {
        return jdbc.sql("""
                        SELECT accepted.observed_at, accepted.available_quantity,
                               accepted.sellable
                          FROM (
                        SELECT CAST(:from AS timestamptz) AS observed_at,
                               seed.available_quantity,
                               CAST(NULL AS text) AS sellable,
                               0 AS event_order
                          FROM LATERAL (
                               SELECT stock.available_quantity
                                 FROM core.listing_stock_observation AS stock
                                WHERE stock.platform_listing_variant_id = :listingVariantId
                                  AND stock.fulfillment_mode_code = :fulfillmentModeCode
                                  AND stock.observed_at < :from
                                  AND NOT EXISTS (
                                      SELECT 1 FROM core.listing_stock_observation AS newer
                                       WHERE newer.supersedes_fact_id = stock.id)
                                ORDER BY stock.observed_at DESC, stock.id DESC
                                LIMIT 1
                          ) AS seed
                        UNION ALL
                        SELECT CAST(:from AS timestamptz) AS observed_at,
                               CAST(NULL AS integer) AS available_quantity,
                               seed.sellable,
                               1 AS event_order
                          FROM LATERAL (
                               SELECT health.sellable
                                 FROM core.listing_health_observation AS health
                                WHERE health.platform_listing_variant_id = :listingVariantId
                                  AND health.observed_at < :from
                                  AND NOT EXISTS (
                                      SELECT 1 FROM core.listing_health_observation AS newer
                                       WHERE newer.supersedes_fact_id = health.id)
                                ORDER BY health.observed_at DESC, health.id DESC
                                LIMIT 1
                          ) AS seed
                        UNION ALL
                        SELECT stock.observed_at,
                               stock.available_quantity,
                               CAST(NULL AS text) AS sellable,
                               0 AS event_order
                          FROM core.listing_stock_observation AS stock
                         WHERE stock.platform_listing_variant_id = :listingVariantId
                           AND stock.fulfillment_mode_code = :fulfillmentModeCode
                           AND stock.observed_at >= :from
                           AND stock.observed_at < :to
                           AND NOT EXISTS (
                               SELECT 1 FROM core.listing_stock_observation AS newer
                                WHERE newer.supersedes_fact_id = stock.id)
                         UNION ALL
                        SELECT health.observed_at,
                               CAST(NULL AS integer) AS available_quantity,
                               health.sellable,
                               1 AS event_order
                          FROM core.listing_health_observation AS health
                         WHERE health.platform_listing_variant_id = :listingVariantId
                           AND health.observed_at >= :from
                           AND health.observed_at < :to
                           AND NOT EXISTS (
                               SELECT 1 FROM core.listing_health_observation AS newer
                                WHERE newer.supersedes_fact_id = health.id)
                          ) AS accepted
                         ORDER BY accepted.observed_at, accepted.event_order
                        """)
                .param("listingVariantId", listingVariantId)
                .param("fulfillmentModeCode", fulfillmentModeCode)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rows, rowNumber) -> new AvailabilityRow(
                        instantOrNull(rows, "observed_at"),
                        integerOrNull(rows, "available_quantity"),
                        rows.getString("sellable")))
                .list();
    }

    /**
     * Facts accepted at or after an instant, oldest first.
     *
     * <p>One provenance row can back several facts, so the union carries the
     * subject each kind resolves to. A caller recalculating a variant needs the
     * subject, not the provenance.
     */
    public List<AcceptedFactRow> factsAcceptedAfter(
            com.mimococo.marketops.operatingfacts.AcceptedFactCursor cursor, int limit) {
        return jdbc.sql("""
                        SELECT accepted.* FROM (
                            SELECT provenance.id AS provenance_id,
                                   provenance.organization_id,
                                   stock.platform_listing_variant_id,
                                   CAST(NULL AS uuid) AS product_variant_id,
                                   'STOCK_OR_SELLABILITY' AS trigger_class,
                                   provenance.ingestion_time, provenance.source_time,
                                   'LISTING_STOCK|' || stock.id
                                       AS item_key
                              FROM core.fact_provenance AS provenance
                              JOIN core.listing_stock_observation AS stock
                                ON stock.provenance_id = provenance.id
                             UNION ALL
                            SELECT provenance.id, provenance.organization_id,
                                   health.platform_listing_variant_id, CAST(NULL AS uuid),
                                   'STOCK_OR_SELLABILITY',
                                   provenance.ingestion_time, provenance.source_time,
                                   'LISTING_HEALTH|' || health.id
                              FROM core.fact_provenance AS provenance
                              JOIN core.listing_health_observation AS health
                                ON health.provenance_id = provenance.id
                             UNION ALL
                            SELECT provenance.id, provenance.organization_id,
                                   sale.platform_listing_variant_id, CAST(NULL AS uuid),
                                   'SALES_EVIDENCE',
                                   provenance.ingestion_time, provenance.source_time,
                                   'SALES|' || sale.id
                              FROM core.fact_provenance AS provenance
                              JOIN ledger.sales_fact AS sale
                                ON sale.provenance_id = provenance.id
                             UNION ALL
                            SELECT provenance.id, provenance.organization_id,
                                   returned.platform_listing_variant_id, CAST(NULL AS uuid),
                                   'RETURN_EVIDENCE',
                                   provenance.ingestion_time, provenance.source_time,
                                   'RETURN|' || returned.id
                              FROM core.fact_provenance AS provenance
                              JOIN ledger.return_fact AS returned
                                ON returned.provenance_id = provenance.id
                             UNION ALL
                            SELECT transition.id, transition.organization_id,
                                   CAST(NULL AS uuid), transition.product_variant_id,
                                   'RETURN_EVIDENCE', transition.recorded_at,
                                   transition.occurred_at,
                                   'RETURN_TRANSITION|' || transition.id
                              FROM ledger.return_inventory_transition AS transition
                             UNION ALL
                            SELECT provenance.id, provenance.organization_id,
                                   CAST(NULL AS uuid), snapshot.product_variant_id,
                                   'STOCK_OR_SELLABILITY',
                                   provenance.ingestion_time, provenance.source_time,
                                   'INTERNAL_STOCK|' || snapshot.id
                              FROM core.fact_provenance AS provenance
                              JOIN core.internal_stock_snapshot AS snapshot
                                ON snapshot.provenance_id = provenance.id
                        ) AS accepted
                         WHERE (accepted.ingestion_time, accepted.provenance_id,
                                accepted.item_key)
                               > (:cursorTime, :cursorProvenanceId, :cursorItemKey)
                         ORDER BY accepted.ingestion_time, accepted.provenance_id
                                , accepted.item_key
                         LIMIT :limit
                        """)
                .param("cursorTime", Timestamp.from(cursor.ingestionTime()))
                .param("cursorProvenanceId", cursor.provenanceId())
                .param("cursorItemKey", cursor.itemKey())
                .param("limit", limit)
                .query((rows, rowNumber) -> new AcceptedFactRow(
                        rows.getObject("provenance_id", UUID.class),
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getString("trigger_class"),
                        rows.getString("item_key"),
                        instantOrNull(rows, "ingestion_time"),
                        instantOrNull(rows, "source_time")))
                .list();
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

    /** The newest price with its competitor price, seller cost and tariffs. */
    public record PriceTermsRow(
            UUID id, Instant observedAt, String currencyCode, BigDecimal buyerPrice,
            BigDecimal platformCompetitorMinPrice, String platformCompetitorCurrencyCode,
            String priceIndexNative, BigDecimal sellerCostPrice,
            BigDecimal salesCommissionPercentFbs, BigDecimal salesCommissionPercentFbo,
            BigDecimal fbsFirstMileMax, BigDecimal fbsDirectFlowMax, BigDecimal fbsLastMile,
            BigDecimal fboDirectFlowMax, BigDecimal fboLastMile, BigDecimal acquiringMax,
            BigDecimal vatRate, UUID provenanceId, Instant sourceTime) {
    }

    /** One search period of a listing variant. */
    public record SearchRow(long searchUsers, Instant periodStart, Instant periodEnd, UUID provenanceId,
                            Instant sourceTime) {
    }

    /** One content rating of a listing variant. */
    public record ContentRow(BigDecimal rating, Instant observedAt, UUID provenanceId, Instant sourceTime) {
    }

    /** The newest catalog snapshot of a listing card before an exclusive instant. */
    public Optional<CatalogRow> latestCatalogObservation(UUID listingVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT catalog.observed_at, catalog.description_category_key, catalog.type_key,
                               catalog.image_count, catalog.attribute_keys, catalog.provenance_id,
                               provenance.source_time
                          FROM core.listing_catalog_observation AS catalog
                          JOIN core.fact_provenance AS provenance ON provenance.id = catalog.provenance_id
                         WHERE catalog.platform_listing_variant_id = :listingVariantId
                           AND catalog.observed_at < :asOf
                         ORDER BY catalog.observed_at DESC, catalog.id DESC
                         LIMIT 1
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new CatalogRow(
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getString("description_category_key"),
                        rows.getString("type_key"),
                        (Integer) rows.getObject("image_count"),
                        textArray(rows, "attribute_keys"),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .optional();
    }

    /** The newest recorded values of the named attributes of a listing before an exclusive instant. */
    public List<AttributeRow> latestAttributes(UUID listingVariantId, Instant asOf, List<String> attributeKeys) {
        if (attributeKeys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT DISTINCT ON (attribute.attribute_key)
                               attribute.attribute_key, attribute.content_role, attribute.value_texts,
                               attribute.value_count, attribute.values_length, attribute.observed_at,
                               attribute.provenance_id, provenance.source_time
                          FROM core.listing_attribute_observation AS attribute
                          JOIN core.fact_provenance AS provenance ON provenance.id = attribute.provenance_id
                         WHERE attribute.platform_listing_variant_id = :listingVariantId
                           AND attribute.observed_at < :asOf
                           AND attribute.attribute_key = ANY(CAST(:attributeKeys AS text[]))
                         ORDER BY attribute.attribute_key, attribute.observed_at DESC, attribute.id DESC
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .param("attributeKeys", attributeKeys.toArray(String[]::new))
                .query((rows, rowNumber) -> new AttributeRow(
                        rows.getString("attribute_key"),
                        rows.getString("content_role"),
                        textArray(rows, "value_texts"),
                        rows.getInt("value_count"),
                        rows.getInt("values_length"),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .list();
    }

    /** The newest recorded groups of a listing's content rating before an exclusive instant. */
    public List<ContentGroupRow> latestContentGroups(UUID listingVariantId, Instant asOf) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (grp.group_key)
                               grp.group_key, grp.group_name, grp.group_rating, grp.group_weight,
                               grp.improve_at_least, grp.condition_keys, grp.condition_texts, grp.condition_met,
                               grp.condition_points, grp.improve_attribute_keys, grp.improve_attribute_names,
                               grp.observed_at, grp.provenance_id, provenance.source_time
                          FROM core.listing_content_group_observation AS grp
                          JOIN core.fact_provenance AS provenance ON provenance.id = grp.provenance_id
                         WHERE grp.platform_listing_variant_id = :listingVariantId
                           AND grp.observed_at < :asOf
                         ORDER BY grp.group_key, grp.observed_at DESC, grp.id DESC
                        """)
                .param("listingVariantId", listingVariantId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new ContentGroupRow(
                        rows.getString("group_key"),
                        rows.getString("group_name"),
                        rows.getBigDecimal("group_rating"),
                        rows.getBigDecimal("group_weight"),
                        (Integer) rows.getObject("improve_at_least"),
                        textArray(rows, "condition_keys"),
                        textArray(rows, "condition_texts"),
                        java.util.Arrays.asList((Boolean[]) rows.getArray("condition_met").getArray()),
                        java.util.Arrays.asList((BigDecimal[]) rows.getArray("condition_points").getArray()),
                        textArray(rows, "improve_attribute_keys"),
                        textArray(rows, "improve_attribute_names"),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .list();
    }

    /** The promotions the store's newest promotion snapshot before an exclusive instant described. */
    /**
     * How long the newest promotion snapshot stays current. It is taken daily; an answer with no
     * promotion writes nothing, so an older snapshot is not taken for today's promotions.
     */
    private static final java.time.Duration PROMOTION_SNAPSHOT_LIFETIME = java.time.Duration.ofHours(48);

    public List<PromotionRow> currentPromotions(UUID storeId, Instant asOf) {
        return jdbc.sql("""
                        WITH newest AS (
                            SELECT max(observation.observed_at) AS observed_at
                              FROM core.promotion_observation AS observation
                              JOIN core.platform_promotion AS promotion
                                ON promotion.id = observation.platform_promotion_id
                             WHERE promotion.store_id = :storeId AND observation.observed_at < :asOf
                        )
                        SELECT observation.platform_promotion_id, promotion.native_promotion_key,
                               observation.observed_at, observation.title, observation.promotion_kind,
                               observation.description, observation.starts_at, observation.ends_at,
                               observation.freezes_at, observation.candidate_count, observation.participant_count,
                               observation.banned_count, observation.participating, observation.voucher,
                               observation.targeted, observation.discount_kind, observation.discount_value,
                               observation.provenance_id, provenance.source_time
                          FROM core.promotion_observation AS observation
                          JOIN core.platform_promotion AS promotion
                            ON promotion.id = observation.platform_promotion_id
                          JOIN newest ON newest.observed_at = observation.observed_at
                          JOIN core.fact_provenance AS provenance ON provenance.id = observation.provenance_id
                         WHERE promotion.store_id = :storeId
                           AND newest.observed_at >= :freshFrom
                           AND (observation.ends_at IS NULL OR observation.ends_at > :asOf)
                         ORDER BY observation.starts_at NULLS LAST, promotion.native_promotion_key
                        """)
                .param("storeId", storeId)
                .param("asOf", Timestamp.from(asOf))
                .param("freshFrom", Timestamp.from(asOf.minus(PROMOTION_SNAPSHOT_LIFETIME)))
                .query((rows, rowNumber) -> new PromotionRow(
                        rows.getObject("platform_promotion_id", UUID.class),
                        rows.getString("native_promotion_key"),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getString("title"),
                        rows.getString("promotion_kind"),
                        rows.getString("description"),
                        instantOrNull(rows, "starts_at"),
                        instantOrNull(rows, "ends_at"),
                        instantOrNull(rows, "freezes_at"),
                        (Integer) rows.getObject("candidate_count"),
                        (Integer) rows.getObject("participant_count"),
                        (Integer) rows.getObject("banned_count"),
                        (Boolean) rows.getObject("participating"),
                        (Boolean) rows.getObject("voucher"),
                        (Boolean) rows.getObject("targeted"),
                        rows.getString("discount_kind"),
                        rows.getBigDecimal("discount_value"),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .list();
    }

    /**
     * The store's promotions whose newest observation before an exclusive instant says they ended
     * from an inclusive instant up to that one, each as that observation described it; newest end
     * first.
     */
    public List<PromotionRow> endedPromotions(UUID storeId, Instant from, Instant asOf) {
        return jdbc.sql("""
                        SELECT ended.*
                          FROM (SELECT DISTINCT ON (observation.platform_promotion_id)
                                       observation.platform_promotion_id, promotion.native_promotion_key,
                                       observation.observed_at, observation.title, observation.promotion_kind,
                                       observation.description, observation.starts_at, observation.ends_at,
                                       observation.freezes_at, observation.candidate_count,
                                       observation.participant_count, observation.banned_count,
                                       observation.participating, observation.voucher, observation.targeted,
                                       observation.discount_kind, observation.discount_value,
                                       observation.provenance_id, provenance.source_time
                                  FROM core.promotion_observation AS observation
                                  JOIN core.platform_promotion AS promotion
                                    ON promotion.id = observation.platform_promotion_id
                                  JOIN core.fact_provenance AS provenance ON provenance.id = observation.provenance_id
                                 WHERE promotion.store_id = :storeId AND observation.observed_at < :asOf
                                 ORDER BY observation.platform_promotion_id, observation.observed_at DESC) AS ended
                         WHERE ended.ends_at >= :from AND ended.ends_at < :asOf
                         ORDER BY ended.ends_at DESC, ended.native_promotion_key
                        """)
                .param("storeId", storeId)
                .param("from", Timestamp.from(from))
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new PromotionRow(
                        rows.getObject("platform_promotion_id", UUID.class),
                        rows.getString("native_promotion_key"),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getString("title"),
                        rows.getString("promotion_kind"),
                        rows.getString("description"),
                        instantOrNull(rows, "starts_at"),
                        instantOrNull(rows, "ends_at"),
                        instantOrNull(rows, "freezes_at"),
                        (Integer) rows.getObject("candidate_count"),
                        (Integer) rows.getObject("participant_count"),
                        (Integer) rows.getObject("banned_count"),
                        (Boolean) rows.getObject("participating"),
                        (Boolean) rows.getObject("voucher"),
                        (Boolean) rows.getObject("targeted"),
                        rows.getString("discount_kind"),
                        rows.getBigDecimal("discount_value"),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .list();
    }

    /**
     * The candidates and participants of the store's promotions, from the newest answer of each
     * promotion and membership read after the promotion's own snapshot. An answer read before it
     * describes a list that may have changed, and an empty answer leaves no rows, so an older
     * row is never taken for a current one.
     */
    public List<PromotionItemRow> currentPromotionItems(UUID storeId, Instant asOf) {
        return jdbc.sql("""
                        WITH snapshot AS (
                            SELECT observation.platform_promotion_id, max(observation.observed_at) AS observed_at
                              FROM core.promotion_observation AS observation
                              JOIN core.platform_promotion AS promotion
                                ON promotion.id = observation.platform_promotion_id
                             WHERE promotion.store_id = :storeId AND observation.observed_at < :asOf
                             GROUP BY observation.platform_promotion_id
                        ), newest AS (
                            SELECT item.platform_promotion_id, item.membership, max(item.observed_at) AS observed_at
                              FROM core.promotion_item_observation AS item
                              JOIN snapshot ON snapshot.platform_promotion_id = item.platform_promotion_id
                             WHERE item.observed_at >= snapshot.observed_at AND item.observed_at < :asOf
                             GROUP BY item.platform_promotion_id, item.membership
                        )
                        SELECT item.platform_promotion_id, item.platform_listing_variant_id, item.membership,
                               item.observed_at, item.currency_code, item.price, item.action_price,
                               item.max_action_price, item.recommended_action_price, item.above_recommended,
                               item.current_boost, item.min_boost, item.max_boost, item.price_for_min_boost,
                               item.price_for_max_boost, item.min_stock, item.recommended_stock, item.stock,
                               item.add_mode, item.quarantined, item.provenance_id, provenance.source_time
                          FROM core.promotion_item_observation AS item
                          JOIN newest
                            ON newest.platform_promotion_id = item.platform_promotion_id
                           AND newest.membership = item.membership
                           AND newest.observed_at = item.observed_at
                          JOIN core.fact_provenance AS provenance ON provenance.id = item.provenance_id
                         ORDER BY item.platform_promotion_id, item.membership, item.platform_listing_variant_id
                        """)
                .param("storeId", storeId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new PromotionItemRow(
                        rows.getObject("platform_promotion_id", UUID.class),
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        rows.getString("membership"),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getString("currency_code"),
                        rows.getBigDecimal("price"),
                        rows.getBigDecimal("action_price"),
                        rows.getBigDecimal("max_action_price"),
                        rows.getBigDecimal("recommended_action_price"),
                        (Boolean) rows.getObject("above_recommended"),
                        rows.getBigDecimal("current_boost"),
                        rows.getBigDecimal("min_boost"),
                        rows.getBigDecimal("max_boost"),
                        rows.getBigDecimal("price_for_min_boost"),
                        rows.getBigDecimal("price_for_max_boost"),
                        (Integer) rows.getObject("min_stock"),
                        (Integer) rows.getObject("recommended_stock"),
                        (Integer) rows.getObject("stock"),
                        rows.getString("add_mode"),
                        (Boolean) rows.getObject("quarantined"),
                        rows.getObject("provenance_id", UUID.class),
                        instantOrNull(rows, "source_time")))
                .list();
    }

    /** One promotion as the newest snapshot described it. */
    public record PromotionRow(UUID promotionId, String nativePromotionKey, Instant observedAt, String title,
                               String promotionKind, String description, Instant startsAt, Instant endsAt,
                               Instant freezesAt, Integer candidateCount, Integer participantCount,
                               Integer bannedCount, Boolean participating, Boolean voucher, Boolean targeted,
                               String discountKind, BigDecimal discountValue, UUID provenanceId, Instant sourceTime) {
    }

    /** One product of a promotion from the newest answer of its membership. */
    public record PromotionItemRow(UUID promotionId, UUID listingVariantId, String membership, Instant observedAt,
                                   String currencyCode, BigDecimal price, BigDecimal actionPrice,
                                   BigDecimal maxActionPrice, BigDecimal recommendedActionPrice,
                                   Boolean aboveRecommended, BigDecimal currentBoost, BigDecimal minBoost,
                                   BigDecimal maxBoost, BigDecimal priceForMinBoost, BigDecimal priceForMaxBoost,
                                   Integer minStock, Integer recommendedStock, Integer stock, String addMode,
                                   Boolean quarantined, UUID provenanceId, Instant sourceTime) {
    }

    /** A text array column, its null elements kept at their positions. */
    private static List<String> textArray(ResultSet rows, String column) throws SQLException {
        java.sql.Array array = rows.getArray(column);
        return array == null ? List.of() : java.util.Arrays.asList((String[]) array.getArray());
    }

    /** One catalog snapshot of a listing card. */
    public record CatalogRow(Instant observedAt, String descriptionCategoryKey, String typeKey, Integer imageCount,
                             List<String> attributeKeys, UUID provenanceId, Instant sourceTime) {
    }

    /** The newest values of one attribute of a listing. */
    public record AttributeRow(String attributeKey, String contentRole, List<String> valueTexts, int valueCount,
                               int valuesLength, Instant observedAt, UUID provenanceId, Instant sourceTime) {
    }

    /** The newest state of one group of a listing's content rating. */
    public record ContentGroupRow(String groupKey, String groupName, BigDecimal rating, BigDecimal weight,
                                  Integer improveAtLeast, List<String> conditionKeys, List<String> conditionTexts,
                                  List<Boolean> conditionMet, List<BigDecimal> conditionPoints,
                                  List<String> improveAttributeKeys, List<String> improveAttributeNames,
                                  Instant observedAt, UUID provenanceId, Instant sourceTime) {
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

    /** One explicit completed/retained/return/QC report-coverage assertion. */
    public record ReturnQualityEvidenceRow(
            UUID id, String completedCoverage, String retainedCoverage,
            String returnCoverage, String qcCoverage,
            Instant completedSourceUpdatedAt, Instant retainedSourceUpdatedAt,
            Instant returnSourceUpdatedAt, Instant qcSourceUpdatedAt,
            String evidenceReference, Instant acceptedAt) {
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

    /** Completed units on one day. */
    public record DayOrdersRow(java.time.LocalDate day, long orderedUnits) {
    }

    public record PricePointRow(Instant observedAt, String currencyCode, BigDecimal buyerPrice,
                                boolean sellerPromotion, String priceIndexNative) {
    }

    public record StockPointRow(Instant observedAt, Integer availableUnits) {
    }

    public record SellablePointRow(Instant observedAt, String sellable) {
    }

    public record DailySaleRow(java.time.LocalDate day, long completedUnits) {
    }

    /** The latest sellability statement about a listing variant. */
    public record SellabilityRow(
            Instant observedAt, String sellable, String blockedReason, UUID provenanceId) {
    }

    /** The latest snapshot of one warehouse. */
    public record WarehouseStockRow(
            UUID warehouseId, int quantityOnHand, Integer quantityReserved,
            Integer quantityQualityLocked, Integer quantityDamaged,
            Integer quantityWrittenOff, String sellable,
            Instant observedAt, UUID provenanceId) {
    }

    /** One stock or sellability observation inside a window. */
    public record AvailabilityRow(Instant observedAt, Integer availableQuantity, String sellable) {
    }

    /** One accepted fact, with the subject it makes stale. */
    public record AcceptedFactRow(
            UUID provenanceId, UUID organizationId, UUID platformListingVariantId,
            UUID productVariantId, String triggerClass, String itemKey,
            Instant ingestionTime, Instant sourceTime) {
    }

    /** Internal stock summed across warehouses. */
    public record InternalStockRow(
            int quantityOnHand, Integer quantityReserved, Instant observedAt,
            UUID provenanceId) {
    }
}
