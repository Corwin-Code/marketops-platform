package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.Array;
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
 * The latest marketplace signal of each kind for every observed listing of a
 * store: whether a buyer can see it, what is in stock, what it costs next to
 * the competition, how its content is rated, how many buyers searched for it
 * and how many units were ordered.
 *
 * <p>Every signal is the newest fact of its kind that no later fact superseded,
 * and carries the time the marketplace considered it true. A signal nobody has
 * observed stays absent; it is never shown as a zero.
 */
@Repository
public class StoreDiagnosisRepository {

    /** How many days of ordered units a row sums, ending at the store's latest traffic day. */
    public static final int ORDER_DAYS = 7;

    private final JdbcClient jdbc;

    StoreDiagnosisRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One row per observed listing variant of the store, ordered by title.
     *
     * @param searchWindow the search period every row reports, or {@code null} for none
     */
    public List<Row> rows(UUID organizationId, UUID storeId, SearchWindow searchWindow) {
        return jdbc.sql("""
                        WITH store_variant AS (
                            SELECT listing.id AS listing_id, variant.id AS variant_id,
                                   listing.native_listing_key, variant.native_sku_key, listing.title
                              FROM core.platform_listing AS listing
                              JOIN core.platform_listing_variant AS variant
                                ON variant.platform_listing_id = listing.id
                               AND variant.organization_id = listing.organization_id
                             WHERE listing.organization_id = :organizationId
                               AND listing.store_id = :storeId
                               AND listing.status = 'OBSERVED' AND variant.status = 'OBSERVED'
                        ), traffic_end AS (
                            SELECT max(traffic.period_end) AS period_end
                              FROM core.listing_traffic_observation AS traffic
                              JOIN store_variant ON store_variant.variant_id = traffic.platform_listing_variant_id
                        )
                        SELECT store_variant.*,
                               health.sellable, health.native_status, health.blocked_reason_native,
                               health.observed_at AS health_at,
                               stock.available, stock.reserved, stock.modes, stock.observed_at AS stock_at,
                               price.currency_code, price.list_price, price.selling_price,
                               price.discount_price, price.price_index_native,
                               price.platform_competitor_min_price, price.platform_competitor_currency_code,
                               price.external_competitor_min_price, price.external_competitor_currency_code,
                               price.observed_at AS price_at,
                               price.sales_commission_percent_fbs,
                               price.fbs_first_mile_max + price.fbs_direct_flow_max + price.fbs_last_mile
                                   AS fbs_logistics_max,
                               price.acquiring_max, price.vat_rate,
                               content.content_rating, content.observed_at AS content_at,
                               search.search_users, search.search_revenue,
                               search.currency_code AS search_currency_code,
                               orders.ordered_units, orders.days_with_records
                          FROM store_variant
                          LEFT JOIN LATERAL (
                               SELECT h.sellable, h.native_status, h.blocked_reason_native, h.observed_at
                                 FROM core.listing_health_observation AS h
                                WHERE h.platform_listing_variant_id = store_variant.variant_id
                                  AND NOT EXISTS (SELECT 1 FROM core.listing_health_observation AS newer
                                                   WHERE newer.supersedes_fact_id = h.id)
                                ORDER BY h.observed_at DESC, h.id DESC LIMIT 1
                          ) AS health ON true
                          LEFT JOIN LATERAL (
                               SELECT sum(s.available_quantity)::bigint AS available,
                                      sum(s.reserved_quantity)::bigint AS reserved,
                                      array_agg(DISTINCT s.fulfillment_mode_code ORDER BY s.fulfillment_mode_code) AS modes,
                                      max(s.observed_at) AS observed_at
                                 FROM core.listing_stock_observation AS s
                                WHERE s.platform_listing_variant_id = store_variant.variant_id
                                  AND s.observed_at = (SELECT max(latest.observed_at)
                                                         FROM core.listing_stock_observation AS latest
                                                        WHERE latest.platform_listing_variant_id = store_variant.variant_id)
                                  AND NOT EXISTS (SELECT 1 FROM core.listing_stock_observation AS newer
                                                   WHERE newer.supersedes_fact_id = s.id)
                               HAVING count(*) > 0
                          ) AS stock ON true
                          LEFT JOIN LATERAL (
                               SELECT p.*
                                 FROM core.listing_price_observation AS p
                                WHERE p.platform_listing_variant_id = store_variant.variant_id
                                  AND NOT EXISTS (SELECT 1 FROM core.listing_price_observation AS newer
                                                   WHERE newer.supersedes_fact_id = p.id)
                                ORDER BY p.observed_at DESC, p.id DESC LIMIT 1
                          ) AS price ON true
                          LEFT JOIN LATERAL (
                               SELECT c.content_rating, c.observed_at
                                 FROM core.listing_content_observation AS c
                                WHERE c.platform_listing_variant_id = store_variant.variant_id
                                ORDER BY c.observed_at DESC, c.id DESC LIMIT 1
                          ) AS content ON true
                          LEFT JOIN LATERAL (
                               SELECT q.search_users, q.search_revenue, q.currency_code
                                 FROM core.listing_search_observation AS q
                                WHERE q.platform_listing_variant_id = store_variant.variant_id
                                  AND q.period_start = :searchFrom AND q.period_end = :searchTo
                                ORDER BY q.id DESC LIMIT 1
                          ) AS search ON true
                          LEFT JOIN LATERAL (
                               SELECT sum(t.ordered_units)::bigint AS ordered_units,
                                      count(DISTINCT t.period_start) AS days_with_records
                                 FROM core.listing_traffic_observation AS t, traffic_end
                                WHERE t.platform_listing_variant_id = store_variant.variant_id
                                  AND t.period_end <= traffic_end.period_end
                                  AND t.period_start >= traffic_end.period_end - make_interval(days => :orderDays)
                                  AND NOT EXISTS (SELECT 1 FROM core.listing_traffic_observation AS newer
                                                   WHERE newer.supersedes_fact_id = t.id)
                               HAVING count(*) > 0
                          ) AS orders ON true
                         ORDER BY store_variant.title NULLS LAST, store_variant.native_listing_key
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("orderDays", ORDER_DAYS)
                .param("searchFrom", searchWindow == null ? null : Timestamp.from(searchWindow.from()))
                .param("searchTo", searchWindow == null ? null : Timestamp.from(searchWindow.to()))
                .query(StoreDiagnosisRepository::map)
                .list();
    }

    /**
     * The newest search period the store has any search fact for: the period
     * every row's search demand and terms are read from, so rows compare.
     */
    public Optional<SearchWindow> searchWindow(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT search.period_start, search.period_end
                          FROM core.listing_search_observation AS search
                          JOIN core.platform_listing_variant AS variant
                            ON variant.id = search.platform_listing_variant_id
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                         WHERE listing.organization_id = :organizationId
                           AND listing.store_id = :storeId
                         ORDER BY search.period_end DESC, search.period_start DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new SearchWindow(
                        rows.getTimestamp("period_start").toInstant(),
                        rows.getTimestamp("period_end").toInstant()))
                .optional();
    }

    /**
     * The search terms buyers used for each listing variant of the store in one
     * period, at most {@code perVariant} per variant, the most searched first.
     */
    public List<SearchTerm> searchTerms(UUID organizationId, UUID storeId, SearchWindow window,
                                        int perVariant) {
        return jdbc.sql("""
                        SELECT ranked.variant_id, ranked.search_term, ranked.search_users,
                               ranked.ordered_count
                          FROM (
                               SELECT term.platform_listing_variant_id AS variant_id, term.search_term,
                                      term.search_users, term.ordered_count,
                                      row_number() OVER (PARTITION BY term.platform_listing_variant_id
                                                         ORDER BY term.search_users DESC, term.search_term) AS position
                                 FROM core.listing_search_term_observation AS term
                                 JOIN core.platform_listing_variant AS variant
                                   ON variant.id = term.platform_listing_variant_id
                                 JOIN core.platform_listing AS listing
                                   ON listing.id = variant.platform_listing_id
                                WHERE listing.organization_id = :organizationId
                                  AND listing.store_id = :storeId
                                  AND term.period_start = :searchFrom AND term.period_end = :searchTo
                          ) AS ranked
                         WHERE ranked.position <= :perVariant
                         ORDER BY ranked.variant_id, ranked.position
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("searchFrom", Timestamp.from(window.from()))
                .param("searchTo", Timestamp.from(window.to()))
                .param("perVariant", perVariant)
                .query((rows, rowNumber) -> new SearchTerm(
                        rows.getObject("variant_id", UUID.class),
                        rows.getString("search_term"),
                        rows.getLong("search_users"),
                        (Long) rows.getObject("ordered_count")))
                .list();
    }

    /**
     * The window the order sums cover: the last {@link #ORDER_DAYS} days ending
     * at the store's latest traffic day, and how many days of it the store has
     * any record for.
     */
    public Optional<OrdersWindow> ordersWindow(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        WITH store_traffic AS (
                            SELECT traffic.period_start, traffic.period_end
                              FROM core.listing_traffic_observation AS traffic
                              JOIN core.platform_listing_variant AS variant
                                ON variant.id = traffic.platform_listing_variant_id
                              JOIN core.platform_listing AS listing
                                ON listing.id = variant.platform_listing_id
                             WHERE listing.organization_id = :organizationId
                               AND listing.store_id = :storeId
                        ), bounds AS (
                            SELECT max(period_end) AS period_end FROM store_traffic
                        )
                        SELECT bounds.period_end - make_interval(days => :orderDays) AS window_from,
                               bounds.period_end AS window_to,
                               (SELECT count(DISTINCT store_traffic.period_start) FROM store_traffic
                                 WHERE store_traffic.period_end <= bounds.period_end
                                   AND store_traffic.period_start >= bounds.period_end - make_interval(days => :orderDays))
                                   AS days_covered
                          FROM bounds
                         WHERE bounds.period_end IS NOT NULL
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("orderDays", ORDER_DAYS)
                .query((rows, rowNumber) -> new OrdersWindow(
                        rows.getTimestamp("window_from").toInstant(),
                        rows.getTimestamp("window_to").toInstant(),
                        rows.getInt("days_covered")))
                .optional();
    }

    private static Row map(ResultSet rows, int rowNumber) throws SQLException {
        Array modes = rows.getArray("modes");
        return new Row(
                rows.getObject("listing_id", UUID.class),
                rows.getObject("variant_id", UUID.class),
                rows.getString("native_listing_key"),
                rows.getString("native_sku_key"),
                rows.getString("title"),
                rows.getString("sellable"),
                rows.getString("native_status"),
                rows.getString("blocked_reason_native"),
                instant(rows, "health_at"),
                (Long) rows.getObject("available"),
                (Long) rows.getObject("reserved"),
                modes == null ? List.of() : List.of((String[]) modes.getArray()),
                instant(rows, "stock_at"),
                rows.getString("currency_code"),
                rows.getBigDecimal("list_price"),
                rows.getBigDecimal("selling_price"),
                rows.getBigDecimal("discount_price"),
                rows.getString("price_index_native"),
                rows.getBigDecimal("platform_competitor_min_price"),
                rows.getString("platform_competitor_currency_code"),
                rows.getBigDecimal("external_competitor_min_price"),
                rows.getString("external_competitor_currency_code"),
                instant(rows, "price_at"),
                rows.getBigDecimal("sales_commission_percent_fbs"),
                rows.getBigDecimal("fbs_logistics_max"),
                rows.getBigDecimal("acquiring_max"),
                rows.getBigDecimal("vat_rate"),
                rows.getBigDecimal("content_rating"),
                instant(rows, "content_at"),
                (Long) rows.getObject("search_users"),
                rows.getBigDecimal("search_revenue"),
                rows.getString("search_currency_code"),
                (Long) rows.getObject("ordered_units"),
                (Long) rows.getObject("days_with_records"));
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** The newest signals of one listing variant; every absent signal is {@code null}. */
    public record Row(
            UUID listingId, UUID variantId, String nativeListingKey, String nativeSkuKey, String title,
            String sellable, String nativeStatus, String blockedReasonNative, Instant healthAt,
            Long available, Long reserved, List<String> fulfillmentModes, Instant stockAt,
            String currencyCode, BigDecimal listPrice, BigDecimal sellingPrice, BigDecimal discountPrice,
            String priceIndexNative, BigDecimal platformCompetitorMinPrice,
            String platformCompetitorCurrencyCode, BigDecimal externalCompetitorMinPrice,
            String externalCompetitorCurrencyCode, Instant priceAt,
            BigDecimal salesCommissionPercentFbs, BigDecimal fbsLogisticsMax, BigDecimal acquiringMax,
            BigDecimal vatRate,
            BigDecimal contentRating, Instant contentAt,
            Long searchUsers, BigDecimal searchRevenue, String searchCurrencyCode,
            Long orderedUnits, Long daysWithRecords) {
    }

    /**
     * The period search demand is reported for.
     *
     * @param from inclusive start
     * @param to exclusive end
     */
    public record SearchWindow(Instant from, Instant to) {
    }

    /** One search term of one listing variant; {@code orderedCount} is {@code null} when not stated. */
    public record SearchTerm(UUID variantId, String term, long searchUsers, Long orderedCount) {
    }

    /**
     * The days the order sums cover.
     *
     * @param from inclusive start
     * @param to exclusive end: the end of the store's latest traffic day
     * @param daysCovered how many of those days the store has any traffic record for
     */
    public record OrdersWindow(Instant from, Instant to, int daysCovered) {
    }
}
