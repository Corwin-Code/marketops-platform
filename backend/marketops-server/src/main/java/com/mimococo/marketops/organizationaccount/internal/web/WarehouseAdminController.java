package com.mimococo.marketops.organizationaccount.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.organizationaccount.internal.application.WarehouseService;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.Warehouse;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Maintenance commands and queries for warehouses. */
@RestController
@RequestMapping("/api/v1/admin/metadata/warehouses")
class WarehouseAdminController {

    private final WarehouseService warehouseService;

    WarehouseAdminController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    /** Create a warehouse. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Warehouse create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                     @Valid @RequestBody CreateWarehouseRequest request) {
        return warehouseService.create(operator, request.legalEntityId(), request.code(),
                request.displayName(), request.timezone());
    }

    /** Update a warehouse's mutable attributes. */
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Warehouse update(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                     @PathVariable UUID id,
                     @Valid @RequestBody UpdateWarehouseRequest request) {
        return warehouseService.update(operator, id, request.displayName(),
                request.timezone(), request.expectedVersion());
    }

    /** Move a warehouse between lifecycle states. */
    @PostMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    Warehouse changeStatus(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                           @PathVariable UUID id,
                           @Valid @RequestBody StatusChangeRequest request) {
        return warehouseService.changeStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** Load one warehouse. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Warehouse get(@PathVariable UUID id) {
        return warehouseService.require(id);
    }

    /** List an organization's warehouses. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<Warehouse> list(@RequestParam UUID organizationId,
                         @RequestParam(required = false) String afterCode,
                         @RequestParam(required = false, defaultValue = "50") int limit) {
        return warehouseService.list(organizationId, afterCode, limit);
    }

    record CreateWarehouseRequest(
            @NotNull UUID legalEntityId,
            @NotBlank String code,
            @NotBlank String displayName,
            String timezone) {
    }

    record UpdateWarehouseRequest(
            @NotBlank String displayName,
            String timezone,
            @NotNull Long expectedVersion) {
    }

    record StatusChangeRequest(
            @NotNull EntityStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}
