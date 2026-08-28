package com.mimococo.marketops.productlisting.internal.infrastructure.jdbc;

import com.mimococo.marketops.productlisting.internal.domain.ObservationLifecycle;
import com.mimococo.marketops.productlisting.internal.domain.PlatformListing;
import com.mimococo.marketops.productlisting.internal.domain.PlatformListingVariant;
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
 * Relational access to observed platform listings and their variants.
 *
 * <p>Both writes are idempotent on the source's own key. Re-reading a page that
 * has not changed moves the observation window and nothing else, which is what
 * makes a replay of stored evidence produce no new logical effect.
 */
@Repository
public class PlatformListingRepository {

    private final JdbcClient jdbc;

    PlatformListingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record an observation of one listing, inserting it the first time.
     *
     * <p>The conflict clause updates only what a source can restate: the title,
     * the platform's own status word and the observation window. Identity is
     * never rewritten, so a listing cannot silently become a different one.
     */
    public void observeListing(PlatformListing listing) {
        jdbc.sql("""
                        INSERT INTO core.platform_listing (
                            id, organization_id, store_id, marketplace_account_id,
                            platform_code, native_listing_key, native_product_key, title,
                            native_status, first_seen_at, last_seen_at, status,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :storeId, :accountId,
                            :platformCode, :nativeListingKey, :nativeProductKey, :title,
                            :nativeStatus, :seenAt, :seenAt, :status,
                            :seenAt, :seenAt, 0)
                        ON CONFLICT (store_id, native_listing_key) DO UPDATE
                        SET title = EXCLUDED.title,
                            native_product_key = EXCLUDED.native_product_key,
                            native_status = EXCLUDED.native_status,
                            last_seen_at = GREATEST(
                                core.platform_listing.last_seen_at, EXCLUDED.last_seen_at),
                            status = EXCLUDED.status,
                            updated_at = EXCLUDED.updated_at,
                            version = core.platform_listing.version + 1
                        """)
                .param("id", listing.id())
                .param("organizationId", listing.organizationId())
                .param("storeId", listing.storeId())
                .param("accountId", listing.marketplaceAccountId())
                .param("platformCode", listing.platformCode())
                .param("nativeListingKey", listing.nativeListingKey())
                .param("nativeProductKey", listing.nativeProductKey())
                .param("title", listing.title())
                .param("nativeStatus", listing.nativeStatus())
                .param("seenAt", Timestamp.from(listing.lastSeenAt()))
                .param("status", listing.status().name())
                .update();
    }

    /** Record an observation of one listing variant, inserting it the first time. */
    public void observeVariant(PlatformListingVariant variant) {
        jdbc.sql("""
                        INSERT INTO core.platform_listing_variant (
                            id, organization_id, platform_listing_id, native_variant_key,
                            native_sku_key, native_barcode, native_color_label,
                            native_size_label, native_status, first_seen_at, last_seen_at,
                            status, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :listingId, :nativeVariantKey,
                            :nativeSkuKey, :nativeBarcode, :nativeColorLabel,
                            :nativeSizeLabel, :nativeStatus, :seenAt, :seenAt,
                            :status, :seenAt, :seenAt, 0)
                        ON CONFLICT (platform_listing_id, native_variant_key) DO UPDATE
                        SET native_sku_key = EXCLUDED.native_sku_key,
                            native_barcode = EXCLUDED.native_barcode,
                            native_color_label = EXCLUDED.native_color_label,
                            native_size_label = EXCLUDED.native_size_label,
                            native_status = EXCLUDED.native_status,
                            last_seen_at = GREATEST(
                                core.platform_listing_variant.last_seen_at,
                                EXCLUDED.last_seen_at),
                            status = EXCLUDED.status,
                            updated_at = EXCLUDED.updated_at,
                            version = core.platform_listing_variant.version + 1
                        """)
                .param("id", variant.id())
                .param("organizationId", variant.organizationId())
                .param("listingId", variant.platformListingId())
                .param("nativeVariantKey", variant.nativeVariantKey())
                .param("nativeSkuKey", variant.nativeSkuKey())
                .param("nativeBarcode", variant.nativeBarcode())
                .param("nativeColorLabel", variant.nativeColorLabel())
                .param("nativeSizeLabel", variant.nativeSizeLabel())
                .param("nativeStatus", variant.nativeStatus())
                .param("seenAt", Timestamp.from(variant.lastSeenAt()))
                .param("status", variant.status().name())
                .update();
    }

    /** Load one listing. */
    public Optional<PlatformListing> findListing(UUID id) {
        return jdbc.sql("SELECT * FROM core.platform_listing WHERE id = :id")
                .param("id", id)
                .query(PlatformListingRepository::mapListing)
                .optional();
    }

    /** Load a listing by the marketplace's own key. */
    public Optional<PlatformListing> findListingByNativeKey(UUID storeId, String nativeListingKey) {
        return jdbc.sql("""
                        SELECT * FROM core.platform_listing
                        WHERE store_id = :storeId AND native_listing_key = :nativeListingKey
                        """)
                .param("storeId", storeId)
                .param("nativeListingKey", nativeListingKey)
                .query(PlatformListingRepository::mapListing)
                .optional();
    }

    /** Load one listing variant. */
    public Optional<PlatformListingVariant> findVariant(UUID id) {
        return jdbc.sql("SELECT * FROM core.platform_listing_variant WHERE id = :id")
                .param("id", id)
                .query(PlatformListingRepository::mapVariant)
                .optional();
    }

    /** Load a listing variant by the marketplace's own key. */
    public Optional<PlatformListingVariant> findVariantByNativeKey(UUID listingId,
                                                                   String nativeVariantKey) {
        return jdbc.sql("""
                        SELECT * FROM core.platform_listing_variant
                        WHERE platform_listing_id = :listingId
                          AND native_variant_key = :nativeVariantKey
                        """)
                .param("listingId", listingId)
                .param("nativeVariantKey", nativeVariantKey)
                .query(PlatformListingRepository::mapVariant)
                .optional();
    }

    /** List a listing's variants. */
    public List<PlatformListingVariant> listVariants(UUID listingId) {
        return jdbc.sql("""
                        SELECT * FROM core.platform_listing_variant
                        WHERE platform_listing_id = :listingId ORDER BY native_variant_key
                        """)
                .param("listingId", listingId)
                .query(PlatformListingRepository::mapVariant)
                .list();
    }

    /**
     * Listing variants on one store that no confirmed mapping covers.
     *
     * <p>This is the queue the matcher works through and the surface an operator
     * sees as unmapped inventory. It is a query rather than a stored flag so it
     * cannot drift from the mapping table it describes.
     */
    public List<PlatformListingVariant> listUnmappedVariants(UUID storeId, Instant at, int limit) {
        return jdbc.sql("""
                        SELECT variant.* FROM core.platform_listing_variant AS variant
                          JOIN core.platform_listing AS listing
                            ON listing.id = variant.platform_listing_id
                         WHERE listing.store_id = :storeId
                           AND variant.status = 'OBSERVED'
                           AND NOT EXISTS (
                               SELECT 1 FROM core.listing_mapping AS mapping
                                WHERE mapping.platform_listing_variant_id = variant.id
                                  AND mapping.status = 'ACTIVE'
                                  AND mapping.effective_from <= :at
                                  AND (mapping.effective_to IS NULL
                                       OR mapping.effective_to > :at))
                         ORDER BY variant.first_seen_at, variant.id
                         LIMIT :pageLimit
                        """)
                .param("storeId", storeId)
                .param("at", Timestamp.from(at))
                .param("pageLimit", limit)
                .query(PlatformListingRepository::mapVariant)
                .list();
    }

    private static PlatformListing mapListing(ResultSet rows, int rowNumber) throws SQLException {
        return new PlatformListing(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getObject("marketplace_account_id", UUID.class),
                rows.getString("platform_code"),
                rows.getString("native_listing_key"),
                rows.getString("native_product_key"),
                rows.getString("title"),
                rows.getString("native_status"),
                rows.getTimestamp("first_seen_at").toInstant(),
                rows.getTimestamp("last_seen_at").toInstant(),
                ObservationLifecycle.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }

    private static PlatformListingVariant mapVariant(ResultSet rows, int rowNumber)
            throws SQLException {
        return new PlatformListingVariant(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("platform_listing_id", UUID.class),
                rows.getString("native_variant_key"),
                rows.getString("native_sku_key"),
                rows.getString("native_barcode"),
                rows.getString("native_color_label"),
                rows.getString("native_size_label"),
                rows.getString("native_status"),
                rows.getTimestamp("first_seen_at").toInstant(),
                rows.getTimestamp("last_seen_at").toInstant(),
                ObservationLifecycle.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }
}
