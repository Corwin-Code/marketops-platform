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
 * How a store's marketplace listings stand against the internal catalogue: the
 * mapping in force, the proposals and conflicts still open, the unit cost the
 * seller entered at the marketplace and the purchase cost in force internally.
 *
 * <p>Read side of the master-data review. Listing identity and mapping belong
 * to the product-listing module; they are read here as rows, the same way the
 * store diagnosis reads them, and nothing is written through this class.
 */
@Repository
public class StoreMasterDataRepository {

    private final JdbcClient jdbc;

    StoreMasterDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row per observed listing variant of the store, ordered by title and offer. */
    public List<Row> rows(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        WITH store_variant AS (
                            SELECT listing.id AS listing_id, variant.id AS variant_id,
                                   listing.native_listing_key, variant.native_sku_key,
                                   variant.native_item_key, variant.native_barcode, listing.title
                              FROM core.platform_listing AS listing
                              JOIN core.platform_listing_variant AS variant
                                ON variant.platform_listing_id = listing.id
                               AND variant.organization_id = listing.organization_id
                             WHERE listing.organization_id = :organizationId
                               AND listing.store_id = :storeId
                               AND listing.status = 'OBSERVED' AND variant.status = 'OBSERVED'
                        )
                        SELECT store_variant.*,
                               mapping.product_variant_id AS mapped_variant_id,
                               mapping.effective_from AS mapped_from,
                               internal_variant.sku_code AS mapped_sku_code,
                               internal_variant.display_name AS mapped_variant_name,
                               internal_variant.status AS mapped_variant_status,
                               product.display_name AS mapped_product_name,
                               price.id AS price_observation_id, price.seller_cost_price,
                               price.currency_code AS price_currency_code,
                               price.observed_at AS price_observed_at,
                               cost.unit_cost, cost.currency_code AS cost_currency_code,
                               cost.effective_from AS cost_effective_from,
                               provenance.source_kind AS cost_source_kind
                          FROM store_variant
                          LEFT JOIN core.listing_mapping AS mapping
                            ON mapping.platform_listing_variant_id = store_variant.variant_id
                           AND mapping.status = 'ACTIVE' AND mapping.effective_to IS NULL
                          LEFT JOIN core.product_variant AS internal_variant
                            ON internal_variant.id = mapping.product_variant_id
                          LEFT JOIN core.product AS product
                            ON product.id = internal_variant.product_id
                          LEFT JOIN LATERAL (
                               SELECT p.id, p.seller_cost_price, p.currency_code, p.observed_at
                                 FROM core.listing_price_observation AS p
                                WHERE p.platform_listing_variant_id = store_variant.variant_id
                                  AND NOT EXISTS (SELECT 1 FROM core.listing_price_observation AS newer
                                                   WHERE newer.supersedes_fact_id = p.id)
                                ORDER BY p.observed_at DESC, p.id DESC LIMIT 1
                          ) AS price ON true
                          LEFT JOIN LATERAL (
                               SELECT c.unit_cost, c.currency_code, c.effective_from, c.provenance_id
                                 FROM core.cost_version AS c
                                WHERE c.product_variant_id = mapping.product_variant_id
                                  AND c.cost_kind = 'PURCHASE' AND c.status = 'ACTIVE'
                                  AND c.effective_to IS NULL
                                ORDER BY c.effective_from DESC LIMIT 1
                          ) AS cost ON true
                          LEFT JOIN core.fact_provenance AS provenance
                            ON provenance.id = cost.provenance_id
                         ORDER BY store_variant.title NULLS LAST, store_variant.native_sku_key,
                                  store_variant.native_listing_key
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query(StoreMasterDataRepository::mapRow)
                .list();
    }

    /** The open mapping proposals for the store's listing variants, strongest first. */
    public List<Candidate> openCandidates(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT candidate.id, candidate.version, candidate.platform_listing_variant_id,
                               candidate.product_variant_id, candidate.match_method, candidate.confidence,
                               internal_variant.sku_code, internal_variant.display_name AS variant_name,
                               product.display_name AS product_name
                          FROM core.listing_mapping_candidate AS candidate
                          JOIN core.platform_listing_variant AS variant
                            ON variant.id = candidate.platform_listing_variant_id
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                          JOIN core.product_variant AS internal_variant
                            ON internal_variant.id = candidate.product_variant_id
                          JOIN core.product AS product
                            ON product.id = internal_variant.product_id
                         WHERE candidate.organization_id = :organizationId
                           AND listing.store_id = :storeId
                           AND candidate.state = 'PROPOSED'
                         ORDER BY candidate.platform_listing_variant_id, candidate.confidence DESC,
                                  candidate.created_at
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new Candidate(
                        rows.getObject("id", UUID.class),
                        rows.getLong("version"),
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getString("match_method"),
                        rows.getBigDecimal("confidence"),
                        rows.getString("sku_code"),
                        rows.getString("variant_name"),
                        rows.getString("product_name")))
                .list();
    }

    /** The open mapping conflicts for the store's listing variants, newest first. */
    public List<Conflict> openConflicts(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT conflict.id, conflict.version, conflict.platform_listing_variant_id,
                               conflict.conflict_kind, conflict.detail, conflict.detected_at
                          FROM core.mapping_conflict AS conflict
                          JOIN core.platform_listing_variant AS variant
                            ON variant.id = conflict.platform_listing_variant_id
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                         WHERE conflict.organization_id = :organizationId
                           AND listing.store_id = :storeId
                           AND conflict.state = 'OPEN'
                         ORDER BY conflict.detected_at DESC
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new Conflict(
                        rows.getObject("id", UUID.class),
                        rows.getLong("version"),
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        rows.getString("conflict_kind"),
                        rows.getString("detail"),
                        rows.getTimestamp("detected_at").toInstant()))
                .list();
    }

    /**
     * What adopting one marketplace cost statement rests on, locked for the
     * adoption: the listing variant belongs to the store, is mapped without an
     * open conflict, and the price observation is its newest one.
     */
    public Optional<AdoptionContext> adoptionContext(UUID organizationId, UUID storeId,
                                                     UUID listingVariantId, UUID priceObservationId) {
        return jdbc.sql("""
                        SELECT mapping.product_variant_id, internal_variant.sku_code,
                               internal_variant.status AS variant_status,
                               price.seller_cost_price, price.currency_code, price.observed_at,
                               provenance.raw_observation_id,
                               NOT EXISTS (SELECT 1 FROM core.listing_price_observation AS later
                                            WHERE later.platform_listing_variant_id = price.platform_listing_variant_id
                                              AND (later.observed_at, later.id) > (price.observed_at, price.id)
                                              AND NOT EXISTS (SELECT 1 FROM core.listing_price_observation AS newer
                                                               WHERE newer.supersedes_fact_id = later.id))
                                   AS newest,
                               EXISTS (SELECT 1 FROM core.mapping_conflict AS conflict
                                        WHERE conflict.platform_listing_variant_id = mapping.platform_listing_variant_id
                                          AND conflict.state = 'OPEN') AS conflict_open
                          FROM core.platform_listing_variant AS variant
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                          JOIN core.listing_mapping AS mapping
                            ON mapping.platform_listing_variant_id = variant.id
                           AND mapping.status = 'ACTIVE' AND mapping.effective_to IS NULL
                          JOIN core.product_variant AS internal_variant
                            ON internal_variant.id = mapping.product_variant_id
                          JOIN core.listing_price_observation AS price
                            ON price.id = :priceObservationId
                           AND price.platform_listing_variant_id = variant.id
                          JOIN core.fact_provenance AS provenance
                            ON provenance.id = price.provenance_id
                         WHERE variant.id = :listingVariantId
                           AND variant.organization_id = :organizationId
                           AND listing.store_id = :storeId
                           FOR UPDATE OF mapping
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("listingVariantId", listingVariantId)
                .param("priceObservationId", priceObservationId)
                .query((rows, rowNumber) -> new AdoptionContext(
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getString("sku_code"),
                        rows.getString("variant_status"),
                        rows.getBigDecimal("seller_cost_price"),
                        rows.getString("currency_code"),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getObject("raw_observation_id", UUID.class),
                        rows.getBoolean("newest"),
                        rows.getBoolean("conflict_open")))
                .optional();
    }

    /** The purchase cost in force for an internal variant, locked for succession. */
    public Optional<CurrentCost> currentPurchaseCost(UUID productVariantId) {
        return jdbc.sql("""
                        SELECT unit_cost, currency_code, effective_from
                          FROM core.cost_version
                         WHERE product_variant_id = :productVariantId
                           AND cost_kind = 'PURCHASE' AND status = 'ACTIVE' AND effective_to IS NULL
                           FOR UPDATE
                        """)
                .param("productVariantId", productVariantId)
                .query((rows, rowNumber) -> new CurrentCost(
                        rows.getBigDecimal("unit_cost"),
                        rows.getString("currency_code"),
                        rows.getTimestamp("effective_from").toInstant()))
                .optional();
    }

    private static Row mapRow(ResultSet rows, int rowNumber) throws SQLException {
        return new Row(
                rows.getObject("listing_id", UUID.class),
                rows.getObject("variant_id", UUID.class),
                rows.getString("native_listing_key"),
                rows.getString("native_sku_key"),
                rows.getString("native_item_key"),
                rows.getString("native_barcode"),
                rows.getString("title"),
                rows.getObject("mapped_variant_id", UUID.class),
                instant(rows, "mapped_from"),
                rows.getString("mapped_sku_code"),
                rows.getString("mapped_variant_name"),
                rows.getString("mapped_variant_status"),
                rows.getString("mapped_product_name"),
                rows.getObject("price_observation_id", UUID.class),
                rows.getBigDecimal("seller_cost_price"),
                rows.getString("price_currency_code"),
                instant(rows, "price_observed_at"),
                rows.getBigDecimal("unit_cost"),
                rows.getString("cost_currency_code"),
                instant(rows, "cost_effective_from"),
                rows.getString("cost_source_kind"));
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** One listing variant against the internal catalogue; every absent part is {@code null}. */
    public record Row(
            UUID listingId, UUID listingVariantId, String nativeListingKey, String nativeSkuKey,
            String nativeItemKey, String nativeBarcode, String title,
            UUID mappedVariantId, Instant mappedFrom, String mappedSkuCode, String mappedVariantName,
            String mappedVariantStatus, String mappedProductName,
            UUID priceObservationId, BigDecimal sellerCostPrice, String priceCurrencyCode,
            Instant priceObservedAt,
            BigDecimal unitCost, String costCurrencyCode, Instant costEffectiveFrom,
            String costSourceKind) {
    }

    /** One open mapping proposal, with the internal variant it points at. */
    public record Candidate(UUID id, long version, UUID listingVariantId, UUID productVariantId,
                            String matchMethod, BigDecimal confidence, String skuCode,
                            String variantName, String productName) {
    }

    /** One open mapping conflict. */
    public record Conflict(UUID id, long version, UUID listingVariantId, String kind, String detail,
                           Instant detectedAt) {
    }

    /**
     * What an adoption rests on.
     *
     * @param newest whether no later price observation of the listing variant exists
     * @param conflictOpen whether the listing variant has an open mapping conflict
     */
    public record AdoptionContext(UUID productVariantId, String skuCode, String variantStatus,
                                  BigDecimal sellerCostPrice, String currencyCode, Instant observedAt,
                                  UUID rawObservationId, boolean newest, boolean conflictOpen) {
    }

    /** The purchase cost in force. */
    public record CurrentCost(BigDecimal unitCost, String currencyCode, Instant effectiveFrom) {
    }
}
