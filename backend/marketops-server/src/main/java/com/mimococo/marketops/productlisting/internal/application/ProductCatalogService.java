package com.mimococo.marketops.productlisting.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.productlisting.internal.domain.BarcodeStatus;
import com.mimococo.marketops.productlisting.internal.domain.BarcodeType;
import com.mimococo.marketops.productlisting.internal.domain.EntityLifecycle;
import com.mimococo.marketops.productlisting.internal.domain.Product;
import com.mimococo.marketops.productlisting.internal.domain.ProductBarcode;
import com.mimococo.marketops.productlisting.internal.domain.ProductVariant;
import com.mimococo.marketops.productlisting.internal.infrastructure.jdbc.ProductRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance of the internal product master.
 *
 * <p>The catalogue is the internal side of every mapping and the thing cost and
 * profit attach to, so its identity rules are strict: a SKU code is unique
 * inside the organization and a live barcode identifies exactly one variant.
 * The barcode rule is enforced here as well as relationally, because a
 * duplicate would not merely be a constraint violation — it would make the
 * mapping matcher's strongest signal ambiguous.
 */
@Service
public class ProductCatalogService {

    static final String PRODUCT_ENTITY_TYPE = "product";
    static final String VARIANT_ENTITY_TYPE = "product-variant";
    static final String BARCODE_ENTITY_TYPE = "product-barcode";

    /** Barcode shape: bounded, printable and free of separators. */
    private static final Pattern BARCODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    private final ProductRepository products;
    private final OrganizationDirectory organizationDirectory;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    ProductCatalogService(ProductRepository products,
                          OrganizationDirectory organizationDirectory,
                          MetadataAuditRecorder auditRecorder,
                          IdGenerator idGenerator,
                          Clock clock) {
        this.products = products;
        this.organizationDirectory = organizationDirectory;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Create a product. */
    @Transactional
    public Product createProduct(String operator,
                                 UUID organizationId,
                                 String code,
                                 String displayName,
                                 String brandLabel,
                                 String categoryLabel) {
        organizationDirectory.organization(organizationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        String validCode = MetadataFieldPolicy.requireRegistryCode(code);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validBrand = optionalText("brandLabel", brandLabel);
        String validCategory = optionalText("categoryLabel", categoryLabel);
        products.findProductByCode(organizationId, validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.PRODUCT_LISTING.dbValue(),
                    PRODUCT_ENTITY_TYPE, validCode, existing.id());
        });

        Instant now = clock.instant();
        Product product = new Product(idGenerator.newId(), organizationId, validCode, validName,
                validBrand, validCategory, EntityLifecycle.ACTIVE, now, now, 0L);
        products.insertProduct(product);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.PRODUCT_LISTING, operator, AuditAction.CREATE,
                PRODUCT_ENTITY_TYPE, product.id(), validCode,
                Map.of(
                        "organizationId", new FieldChange(null, organizationId.toString()),
                        "code", new FieldChange(null, validCode),
                        "displayName", new FieldChange(null, validName)),
                null, null));
        return product;
    }

    /** Create a sellable variant under a product. */
    @Transactional
    public ProductVariant createVariant(String operator,
                                        UUID productId,
                                        String skuCode,
                                        String displayName,
                                        String colorLabel,
                                        String sizeLabel) {
        Product product = products.findProduct(productId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (product.status() != EntityLifecycle.ACTIVE) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        String validSku = MetadataFieldPolicy.requireRegistryCode(skuCode);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validColor = optionalText("colorLabel", colorLabel);
        String validSize = optionalText("sizeLabel", sizeLabel);
        products.findVariantBySku(product.organizationId(), validSku).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.PRODUCT_LISTING.dbValue(),
                    VARIANT_ENTITY_TYPE, validSku, existing.id());
        });

        Instant now = clock.instant();
        ProductVariant variant = new ProductVariant(idGenerator.newId(), product.organizationId(),
                productId, validSku, validName, validColor, validSize, EntityLifecycle.ACTIVE,
                now, now, 0L);
        products.insertVariant(variant);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.PRODUCT_LISTING, operator, AuditAction.CREATE,
                VARIANT_ENTITY_TYPE, variant.id(), validSku,
                Map.of(
                        "productId", new FieldChange(null, productId.toString()),
                        "skuCode", new FieldChange(null, validSku),
                        "displayName", new FieldChange(null, validName)),
                null, null));
        return variant;
    }

    /**
     * Attach a barcode to a variant.
     *
     * <p>A live duplicate is refused before it reaches the database. The
     * relational index would refuse it too, but a stable error code is what an
     * operator can act on, and the message must not leave them guessing which
     * of two products already owns the value.
     */
    @Transactional
    public ProductBarcode addBarcode(String operator,
                                     UUID productVariantId,
                                     BarcodeType barcodeType,
                                     String barcodeValue) {
        ProductVariant variant = products.findVariant(productVariantId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (barcodeValue == null || !BARCODE.matcher(barcodeValue).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        List<UUID> existing =
                products.liveVariantsForBarcode(variant.organizationId(), barcodeValue);
        if (!existing.isEmpty()) {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.PRODUCT_LISTING.dbValue(),
                    BARCODE_ENTITY_TYPE, null, existing.getFirst());
        }

        Instant now = clock.instant();
        ProductBarcode barcode = new ProductBarcode(idGenerator.newId(), variant.organizationId(),
                productVariantId, barcodeType, barcodeValue, BarcodeStatus.ACTIVE, now, now, 0L);
        products.insertBarcode(barcode);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.PRODUCT_LISTING, operator, AuditAction.CREATE,
                BARCODE_ENTITY_TYPE, barcode.id(), null,
                Map.of(
                        "productVariantId", new FieldChange(null, productVariantId.toString()),
                        "barcodeType", new FieldChange(null, barcodeType.name())),
                null, null));
        return barcode;
    }

    /** Retire a barcode, releasing its value for a legitimate re-registration. */
    @Transactional
    public void retireBarcode(String operator, UUID barcodeId, String reason,
                              long expectedVersion) {
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!products.retireBarcode(barcodeId, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.PRODUCT_LISTING, operator, AuditAction.STATUS_CHANGE,
                BARCODE_ENTITY_TYPE, barcodeId, null,
                Map.of("status", new FieldChange(BarcodeStatus.ACTIVE.name(),
                        BarcodeStatus.RETIRED.name())),
                validReason, null));
    }

    /** Load one product. */
    @Transactional(readOnly = true)
    public Product requireProduct(UUID id) {
        return products.findProduct(id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** Load one variant. */
    @Transactional(readOnly = true)
    public ProductVariant requireVariant(UUID id) {
        return products.findVariant(id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** List a product's variants. */
    @Transactional(readOnly = true)
    public List<ProductVariant> listVariants(UUID productId) {
        return products.listVariants(productId);
    }

    /** List a variant's barcodes. */
    @Transactional(readOnly = true)
    public List<ProductBarcode> listBarcodes(UUID productVariantId) {
        return products.listBarcodes(productVariantId);
    }

    private static String optionalText(String fieldName, String value) {
        return value == null ? null : MetadataFieldPolicy.requireText(fieldName, value);
    }
}
