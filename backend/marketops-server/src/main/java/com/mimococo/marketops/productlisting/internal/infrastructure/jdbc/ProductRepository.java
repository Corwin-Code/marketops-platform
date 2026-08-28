package com.mimococo.marketops.productlisting.internal.infrastructure.jdbc;

import com.mimococo.marketops.productlisting.internal.domain.BarcodeStatus;
import com.mimococo.marketops.productlisting.internal.domain.BarcodeType;
import com.mimococo.marketops.productlisting.internal.domain.EntityLifecycle;
import com.mimococo.marketops.productlisting.internal.domain.Product;
import com.mimococo.marketops.productlisting.internal.domain.ProductBarcode;
import com.mimococo.marketops.productlisting.internal.domain.ProductVariant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Relational access to the internal product master, its variants and their
 * barcodes.
 *
 * <p>The three tables form one aggregate for persistence: a variant without its
 * product and a barcode without its variant are not meaningful, and keeping
 * their access together is what lets the mapping matcher read a variant and its
 * identifiers in one place.
 */
@Repository
public class ProductRepository {

    private final JdbcClient jdbc;

    ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a product. */
    public void insertProduct(Product product) {
        jdbc.sql("""
                        INSERT INTO core.product (
                            id, organization_id, code, display_name, brand_label,
                            category_label, status, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :code, :displayName, :brandLabel,
                            :categoryLabel, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", product.id())
                .param("organizationId", product.organizationId())
                .param("code", product.code())
                .param("displayName", product.displayName())
                .param("brandLabel", product.brandLabel())
                .param("categoryLabel", product.categoryLabel())
                .param("status", product.status().name())
                .param("createdAt", Timestamp.from(product.createdAt()))
                .param("updatedAt", Timestamp.from(product.updatedAt()))
                .param("version", product.version())
                .update();
    }

    /** Apply a versioned product update; false means the version was stale. */
    public boolean updateProduct(Product product, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE core.product
                        SET display_name = :displayName, brand_label = :brandLabel,
                            category_label = :categoryLabel, status = :status,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", product.displayName())
                .param("brandLabel", product.brandLabel())
                .param("categoryLabel", product.categoryLabel())
                .param("status", product.status().name())
                .param("updatedAt", Timestamp.from(product.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", product.id())
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Load one product. */
    public Optional<Product> findProduct(UUID id) {
        return jdbc.sql("SELECT * FROM core.product WHERE id = :id")
                .param("id", id)
                .query(ProductRepository::mapProduct)
                .optional();
    }

    /** Load a product by its business code. */
    public Optional<Product> findProductByCode(UUID organizationId, String code) {
        return jdbc.sql("""
                        SELECT * FROM core.product
                        WHERE organization_id = :organizationId AND code = :code
                        """)
                .param("organizationId", organizationId)
                .param("code", code)
                .query(ProductRepository::mapProduct)
                .optional();
    }

    /** Insert a variant. */
    public void insertVariant(ProductVariant variant) {
        jdbc.sql("""
                        INSERT INTO core.product_variant (
                            id, organization_id, product_id, sku_code, display_name,
                            color_label, size_label, status, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :productId, :skuCode, :displayName,
                            :colorLabel, :sizeLabel, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", variant.id())
                .param("organizationId", variant.organizationId())
                .param("productId", variant.productId())
                .param("skuCode", variant.skuCode())
                .param("displayName", variant.displayName())
                .param("colorLabel", variant.colorLabel())
                .param("sizeLabel", variant.sizeLabel())
                .param("status", variant.status().name())
                .param("createdAt", Timestamp.from(variant.createdAt()))
                .param("updatedAt", Timestamp.from(variant.updatedAt()))
                .param("version", variant.version())
                .update();
    }

    /** Apply a versioned variant update; false means the version was stale. */
    public boolean updateVariant(ProductVariant variant, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE core.product_variant
                        SET display_name = :displayName, color_label = :colorLabel,
                            size_label = :sizeLabel, status = :status,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", variant.displayName())
                .param("colorLabel", variant.colorLabel())
                .param("sizeLabel", variant.sizeLabel())
                .param("status", variant.status().name())
                .param("updatedAt", Timestamp.from(variant.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", variant.id())
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Load one variant. */
    public Optional<ProductVariant> findVariant(UUID id) {
        return jdbc.sql("SELECT * FROM core.product_variant WHERE id = :id")
                .param("id", id)
                .query(ProductRepository::mapVariant)
                .optional();
    }

    /** Load a variant by its internal SKU code. */
    public Optional<ProductVariant> findVariantBySku(UUID organizationId, String skuCode) {
        return jdbc.sql("""
                        SELECT * FROM core.product_variant
                        WHERE organization_id = :organizationId AND sku_code = :skuCode
                        """)
                .param("organizationId", organizationId)
                .param("skuCode", skuCode)
                .query(ProductRepository::mapVariant)
                .optional();
    }

    /** List a product's variants ordered by SKU code. */
    public List<ProductVariant> listVariants(UUID productId) {
        return jdbc.sql("""
                        SELECT * FROM core.product_variant
                        WHERE product_id = :productId ORDER BY sku_code
                        """)
                .param("productId", productId)
                .query(ProductRepository::mapVariant)
                .list();
    }

    /** Insert a barcode. */
    public void insertBarcode(ProductBarcode barcode) {
        jdbc.sql("""
                        INSERT INTO core.product_barcode (
                            id, organization_id, product_variant_id, barcode_type,
                            barcode_value, status, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :variantId, :barcodeType,
                            :barcodeValue, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", barcode.id())
                .param("organizationId", barcode.organizationId())
                .param("variantId", barcode.productVariantId())
                .param("barcodeType", barcode.barcodeType().name())
                .param("barcodeValue", barcode.barcodeValue())
                .param("status", barcode.status().name())
                .param("createdAt", Timestamp.from(barcode.createdAt()))
                .param("updatedAt", Timestamp.from(barcode.updatedAt()))
                .param("version", barcode.version())
                .update();
    }

    /** Retire a barcode; false means the version was stale. */
    public boolean retireBarcode(UUID id, java.time.Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE core.product_barcode
                        SET status = 'RETIRED', updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /**
     * The live internal variants carrying one barcode value.
     *
     * <p>More than one answer is possible in a catalogue that has not been
     * cleaned, and the matcher must see that rather than take the first row:
     * a duplicate barcode is a conflict for a person, not a tie to break.
     */
    public List<UUID> liveVariantsForBarcode(UUID organizationId, String barcodeValue) {
        return jdbc.sql("""
                        SELECT product_variant_id FROM core.product_barcode
                        WHERE organization_id = :organizationId
                          AND barcode_value = :barcodeValue
                          AND status = 'ACTIVE'
                        ORDER BY product_variant_id
                        """)
                .param("organizationId", organizationId)
                .param("barcodeValue", barcodeValue)
                .query(UUID.class)
                .list();
    }

    /** List a variant's barcodes. */
    public List<ProductBarcode> listBarcodes(UUID productVariantId) {
        return jdbc.sql("""
                        SELECT * FROM core.product_barcode
                        WHERE product_variant_id = :variantId ORDER BY barcode_value
                        """)
                .param("variantId", productVariantId)
                .query(ProductRepository::mapBarcode)
                .list();
    }

    private static Product mapProduct(ResultSet rows, int rowNumber) throws SQLException {
        return new Product(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getString("code"),
                rows.getString("display_name"),
                rows.getString("brand_label"),
                rows.getString("category_label"),
                EntityLifecycle.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }

    private static ProductVariant mapVariant(ResultSet rows, int rowNumber) throws SQLException {
        return new ProductVariant(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("product_id", UUID.class),
                rows.getString("sku_code"),
                rows.getString("display_name"),
                rows.getString("color_label"),
                rows.getString("size_label"),
                EntityLifecycle.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }

    private static ProductBarcode mapBarcode(ResultSet rows, int rowNumber) throws SQLException {
        return new ProductBarcode(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("product_variant_id", UUID.class),
                BarcodeType.valueOf(rows.getString("barcode_type")),
                rows.getString("barcode_value"),
                BarcodeStatus.valueOf(rows.getString("status")),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }
}
