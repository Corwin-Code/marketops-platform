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
 * Buyers' discount requests as the store's answers stated them. Each request is read in its newest
 * state, and joined to the catalogue listing variant that carries its SKU when exactly one does;
 * most requests name SKUs the catalogue no longer lists. Optionally narrowed to one listing variant.
 */
@Repository
public class DiscountRequestRepository {

    /**
     * The scoped requests: the newest observation of each request of the store, with the one
     * observed listing variant carrying its SKU, narrowed to a variant when one is named.
     */
    private static final String SCOPED = """
            WITH item AS (
                SELECT variant.native_item_key, (array_agg(variant.id))[1] AS variant_id
                  FROM core.platform_listing_variant AS variant
                  JOIN core.platform_listing AS listing
                    ON listing.id = variant.platform_listing_id
                   AND listing.organization_id = variant.organization_id
                 WHERE listing.organization_id = :organizationId AND listing.store_id = :storeId
                   AND variant.native_item_key IS NOT NULL AND variant.status = 'OBSERVED'
                 GROUP BY variant.native_item_key
                HAVING count(*) = 1
            ), newest AS (
                SELECT DISTINCT ON (request.native_request_key) request.*
                  FROM core.discount_request_observation AS request
                 WHERE request.organization_id = :organizationId AND request.store_id = :storeId
                 ORDER BY request.native_request_key, request.observed_at DESC
            ), scoped AS (
                SELECT newest.*, item.variant_id
                  FROM newest
                  LEFT JOIN item ON item.native_item_key = newest.native_item_key
                 WHERE CAST(:subjectId AS uuid) IS NULL OR item.variant_id = CAST(:subjectId AS uuid)
            )
            """;

    private final JdbcClient jdbc;

    DiscountRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** When the store's newest answer was observed, or empty before the first one. */
    public Optional<Instant> observedAt(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT request.observed_at
                          FROM core.discount_request_observation AS request
                         WHERE request.organization_id = :organizationId AND request.store_id = :storeId
                         ORDER BY request.observed_at DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /**
     * Counts over the scoped requests. The median is of the discounts buyers asked for, in percent
     * of the price they saw; a new request's deadline counts only while it is ahead of {@code now}.
     */
    public Summary summary(UUID organizationId, UUID storeId, UUID subjectId, Instant now) {
        return jdbc.sql(SCOPED + """
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE scoped.status = 'NEW') AS pending,
                               count(*) FILTER (WHERE scoped.status = 'APPROVED') AS approved,
                               count(*) FILTER (WHERE scoped.status = 'DECLINED') AS declined,
                               count(DISTINCT scoped.native_item_key) AS items,
                               count(DISTINCT scoped.native_item_key)
                                   FILTER (WHERE scoped.variant_id IS NOT NULL) AS items_in_catalog,
                               count(*) FILTER (WHERE scoped.variant_id IS NOT NULL) AS requests_in_catalog,
                               round(CAST(percentile_cont(0.5) WITHIN GROUP (
                                   ORDER BY CAST(scoped.requested_discount_percent AS double precision))
                                   AS numeric), 2) AS median_discount,
                               min(scoped.requested_at) AS first_requested_at,
                               max(scoped.requested_at) AS latest_requested_at,
                               min(scoped.expires_at) FILTER (WHERE scoped.status = 'NEW'
                                   AND scoped.expires_at > :now) AS next_deadline
                          FROM scoped
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("subjectId", subjectId)
                .param("now", Timestamp.from(now))
                .query((rows, rowNumber) -> new Summary(rows.getInt("total"), rows.getInt("pending"),
                        rows.getInt("approved"), rows.getInt("declined"), rows.getInt("items"),
                        rows.getInt("items_in_catalog"), rows.getInt("requests_in_catalog"),
                        rows.getBigDecimal("median_discount"), instant(rows, "first_requested_at"),
                        instant(rows, "latest_requested_at"), instant(rows, "next_deadline")))
                .single();
    }

    /** How many scoped requests were made in each UTC month, oldest first. */
    public List<Month> months(UUID organizationId, UUID storeId, UUID subjectId) {
        return jdbc.sql(SCOPED + """
                        SELECT to_char(scoped.requested_at AT TIME ZONE 'UTC', 'YYYY-MM') AS month,
                               count(*) AS requests
                          FROM scoped
                         WHERE scoped.requested_at IS NOT NULL
                         GROUP BY 1
                         ORDER BY 1
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("subjectId", subjectId)
                .query((rows, rowNumber) -> new Month(rows.getString("month"), rows.getInt("requests")))
                .list();
    }

    /**
     * The SKUs buyers asked about, those the catalogue lists today first and then by how often, with
     * the listing variant carrying each when the catalogue has one.
     */
    public List<Item> items(UUID organizationId, UUID storeId, int limit) {
        return jdbc.sql(SCOPED + """
                        SELECT scoped.native_item_key, scoped.variant_id, count(*) AS requests,
                               count(*) FILTER (WHERE scoped.status = 'NEW') AS pending,
                               max(scoped.requested_at) AS latest_requested_at,
                               round(CAST(percentile_cont(0.5) WITHIN GROUP (
                                   ORDER BY CAST(scoped.requested_discount_percent AS double precision))
                                   AS numeric), 2) AS median_discount,
                               (array_agg(scoped.product_name ORDER BY scoped.requested_at DESC NULLS LAST))[1]
                                   AS product_name
                          FROM scoped
                         WHERE scoped.native_item_key IS NOT NULL
                         GROUP BY scoped.native_item_key, scoped.variant_id
                         ORDER BY scoped.variant_id IS NULL, count(*) DESC, max(scoped.requested_at) DESC NULLS LAST,
                                  scoped.native_item_key
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("subjectId", null)
                .param("limit", limit)
                .query((rows, rowNumber) -> new Item(rows.getString("native_item_key"),
                        rows.getObject("variant_id", UUID.class), rows.getString("product_name"),
                        rows.getInt("requests"), rows.getInt("pending"), instant(rows, "latest_requested_at"),
                        rows.getBigDecimal("median_discount")))
                .list();
    }

    /** The scoped requests in their newest state, newest request first. */
    public List<Request> requests(UUID organizationId, UUID storeId, UUID subjectId, int limit) {
        return jdbc.sql(SCOPED + """
                        SELECT scoped.native_request_key, scoped.variant_id, scoped.native_item_key,
                               scoped.product_name, scoped.status, scoped.requested_at, scoped.moderated_at,
                               scoped.expires_at, scoped.currency_code, scoped.original_price,
                               scoped.requested_price, scoped.requested_discount_percent,
                               scoped.requested_quantity, scoped.approved_price, scoped.approved_quantity,
                               scoped.auto_moderated
                          FROM scoped
                         ORDER BY scoped.requested_at DESC NULLS LAST, scoped.native_request_key DESC
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("subjectId", subjectId)
                .param("limit", limit)
                .query((rows, rowNumber) -> new Request(rows.getString("native_request_key"),
                        rows.getObject("variant_id", UUID.class), rows.getString("native_item_key"),
                        rows.getString("product_name"), rows.getString("status"), instant(rows, "requested_at"),
                        instant(rows, "moderated_at"), instant(rows, "expires_at"),
                        rows.getString("currency_code"), rows.getBigDecimal("original_price"),
                        rows.getBigDecimal("requested_price"), rows.getBigDecimal("requested_discount_percent"),
                        integer(rows, "requested_quantity"), rows.getBigDecimal("approved_price"),
                        integer(rows, "approved_quantity"), flag(rows, "auto_moderated")))
                .list();
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Boolean flag(ResultSet rows, String column) throws SQLException {
        boolean value = rows.getBoolean(column);
        return rows.wasNull() ? null : value;
    }

    private static Integer integer(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    /**
     * Counts over the scoped requests.
     *
     * @param medianDiscountPercent {@code null} without a stated discount
     * @param nextDeadline the earliest deadline of a new request still ahead, or {@code null}
     */
    public record Summary(int total, int pending, int approved, int declined, int items, int itemsInCatalog,
                          int requestsInCatalog, BigDecimal medianDiscountPercent, Instant firstRequestedAt,
                          Instant latestRequestedAt, Instant nextDeadline) {
    }

    /** How many requests one UTC month ({@code YYYY-MM}) had. */
    public record Month(String month, int requests) {
    }

    /** One SKU buyers asked about; {@code listingVariantId} is {@code null} when the catalogue lacks it. */
    public record Item(String nativeItemKey, UUID listingVariantId, String productName, int requests, int pending,
                       Instant latestRequestedAt, BigDecimal medianDiscountPercent) {
    }

    /** One request in its newest state; amounts in {@code currencyCode}. */
    public record Request(String nativeRequestKey, UUID listingVariantId, String nativeItemKey, String productName,
                          String status, Instant requestedAt, Instant moderatedAt, Instant expiresAt,
                          String currencyCode, BigDecimal originalPrice, BigDecimal requestedPrice,
                          BigDecimal requestedDiscountPercent, Integer requestedQuantity, BigDecimal approvedPrice,
                          Integer approvedQuantity, Boolean autoModerated) {
    }
}
