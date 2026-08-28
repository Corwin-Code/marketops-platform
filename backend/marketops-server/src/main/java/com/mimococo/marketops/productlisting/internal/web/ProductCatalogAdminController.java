package com.mimococo.marketops.productlisting.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.productlisting.internal.application.ProductCatalogService;
import com.mimococo.marketops.productlisting.internal.domain.BarcodeType;
import com.mimococo.marketops.productlisting.internal.domain.Product;
import com.mimococo.marketops.productlisting.internal.domain.ProductBarcode;
import com.mimococo.marketops.productlisting.internal.domain.ProductVariant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintenance commands and queries for the internal product master.
 *
 * <p>The catalogue is reference data for the operating loop rather than part of
 * it, so it lives on the same loopback maintenance surface as the rest of the
 * operating-entity metadata and carries the same attribution, write switch and
 * audit obligations.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
class ProductCatalogAdminController {

    private final ProductCatalogService catalog;

    ProductCatalogAdminController(ProductCatalogService catalog) {
        this.catalog = catalog;
    }

    /** Create a product. */
    @PostMapping(value = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Product createProduct(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody CreateProductRequest request) {
        return catalog.createProduct(operator, request.organizationId(), request.code(),
                request.displayName(), request.brandLabel(), request.categoryLabel());
    }

    /** Load one product. */
    @GetMapping(value = "/products/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Product getProduct(@PathVariable UUID id) {
        return catalog.requireProduct(id);
    }

    /** Create a sellable variant. */
    @PostMapping(value = "/products/{id}/variants", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ProductVariant createVariant(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody CreateVariantRequest request) {
        return catalog.createVariant(operator, id, request.skuCode(), request.displayName(),
                request.colorLabel(), request.sizeLabel());
    }

    /** List a product's variants. */
    @GetMapping(value = "/products/{id}/variants", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ProductVariant> listVariants(@PathVariable UUID id) {
        return catalog.listVariants(id);
    }

    /** Attach a barcode to a variant. */
    @PostMapping(value = "/product-variants/{id}/barcodes",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ProductBarcode addBarcode(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody AddBarcodeRequest request) {
        return catalog.addBarcode(operator, id, request.barcodeType(), request.barcodeValue());
    }

    /** List a variant's barcodes. */
    @GetMapping(value = "/product-variants/{id}/barcodes",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<ProductBarcode> listBarcodes(@PathVariable UUID id) {
        return catalog.listBarcodes(id);
    }

    /** Retire a barcode. */
    @PostMapping(value = "/product-barcodes/{id}/retirement",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retireBarcode(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                       @PathVariable UUID id,
                       @Valid @RequestBody RetireBarcodeRequest request) {
        catalog.retireBarcode(operator, id, request.reason(), request.expectedVersion());
    }

    record CreateProductRequest(
            @NotNull UUID organizationId,
            @NotBlank String code,
            @NotBlank String displayName,
            String brandLabel,
            String categoryLabel) {
    }

    record CreateVariantRequest(
            @NotBlank String skuCode,
            @NotBlank String displayName,
            String colorLabel,
            String sizeLabel) {
    }

    record AddBarcodeRequest(
            @NotNull BarcodeType barcodeType,
            @NotBlank String barcodeValue) {
    }

    record RetireBarcodeRequest(
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}
