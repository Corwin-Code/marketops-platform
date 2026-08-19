package com.mimococo.marketops.organizationaccount.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.organizationaccount.internal.application.FulfillmentAssociationService;
import com.mimococo.marketops.organizationaccount.internal.domain.AssociationStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreFulfillmentDeclaration;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreWarehouseLink;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintenance commands and queries for store↔warehouse associations and store
 * fulfillment declarations.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
class FulfillmentAssociationAdminController {

    private final FulfillmentAssociationService associationService;

    FulfillmentAssociationAdminController(FulfillmentAssociationService associationService) {
        this.associationService = associationService;
    }

    /** Create a store↔warehouse association. */
    @PostMapping(value = "/store-warehouse-links", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    StoreWarehouseLink createLink(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody CreateLinkRequest request) {
        return associationService.createLink(operator, request.storeId(), request.warehouseId(),
                request.fulfillmentModeCode(), request.effectiveFrom(), request.effectiveTo(),
                request.note());
    }

    /** Adjust an active association's validity interval or note. */
    @PutMapping(value = "/store-warehouse-links/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    StoreWarehouseLink updateLink(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLinkRequest request) {
        return associationService.updateLink(operator, id, request.effectiveFrom(),
                request.effectiveTo(), request.note(), request.expectedVersion());
    }

    /** End or cancel an association. */
    @PostMapping(value = "/store-warehouse-links/{id}/status",
            produces = MediaType.APPLICATION_JSON_VALUE)
    StoreWarehouseLink changeLinkStatus(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody AssociationStatusChangeRequest request) {
        return associationService.changeLinkStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** List a store's associations. */
    @GetMapping(value = "/store-warehouse-links", produces = MediaType.APPLICATION_JSON_VALUE)
    List<StoreWarehouseLink> listLinks(
            @RequestParam UUID storeId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return associationService.listLinks(storeId, limit);
    }

    /** Create a store fulfillment declaration. */
    @PostMapping(value = "/store-fulfillment-declarations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    StoreFulfillmentDeclaration createDeclaration(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @Valid @RequestBody CreateDeclarationRequest request) {
        return associationService.createDeclaration(operator, request.storeId(),
                request.fulfillmentModeCode(), request.effectiveFrom(), request.effectiveTo());
    }

    /** End or cancel a declaration. */
    @PostMapping(value = "/store-fulfillment-declarations/{id}/status",
            produces = MediaType.APPLICATION_JSON_VALUE)
    StoreFulfillmentDeclaration changeDeclarationStatus(
            @RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
            @PathVariable UUID id,
            @Valid @RequestBody AssociationStatusChangeRequest request) {
        return associationService.changeDeclarationStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** List a store's declarations. */
    @GetMapping(value = "/store-fulfillment-declarations",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<StoreFulfillmentDeclaration> listDeclarations(
            @RequestParam UUID storeId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return associationService.listDeclarations(storeId, limit);
    }

    record CreateLinkRequest(
            @NotNull UUID storeId,
            @NotNull UUID warehouseId,
            @NotBlank String fulfillmentModeCode,
            @NotNull Instant effectiveFrom,
            Instant effectiveTo,
            String note) {
    }

    record UpdateLinkRequest(
            @NotNull Instant effectiveFrom,
            Instant effectiveTo,
            String note,
            @NotNull Long expectedVersion) {
    }

    record CreateDeclarationRequest(
            @NotNull UUID storeId,
            @NotBlank String fulfillmentModeCode,
            @NotNull Instant effectiveFrom,
            Instant effectiveTo) {
    }

    record AssociationStatusChangeRequest(
            @NotNull AssociationStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}
