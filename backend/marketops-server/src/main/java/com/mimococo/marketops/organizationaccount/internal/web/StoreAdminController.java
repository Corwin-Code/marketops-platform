package com.mimococo.marketops.organizationaccount.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.organizationaccount.internal.application.StoreService;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.Store;
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

/** Maintenance commands and queries for stores. */
@RestController
@RequestMapping("/api/v1/admin/metadata/stores")
class StoreAdminController {

    private final StoreService storeService;

    StoreAdminController(StoreService storeService) {
        this.storeService = storeService;
    }

    /** Create a store. */
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Store create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                 @Valid @RequestBody CreateStoreRequest request) {
        return storeService.create(operator, request.marketplaceAccountId(), request.code(),
                request.displayName(), request.nativeStoreKey(), request.timezone(),
                request.currencyCode());
    }

    /** Update a store; changing the native key requires a reason. */
    @PutMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Store update(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                 @PathVariable UUID id,
                 @Valid @RequestBody UpdateStoreRequest request) {
        return storeService.update(operator, id, request.displayName(),
                request.nativeStoreKey(), request.timezone(), request.currencyCode(),
                request.reason(), request.expectedVersion());
    }

    /** Move a store between lifecycle states. */
    @PostMapping(value = "/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    Store changeStatus(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                       @PathVariable UUID id,
                       @Valid @RequestBody StatusChangeRequest request) {
        return storeService.changeStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion());
    }

    /** Load one store. */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    Store get(@PathVariable UUID id) {
        return storeService.require(id);
    }

    /** List an organization's stores. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    List<Store> list(@RequestParam UUID organizationId,
                     @RequestParam(required = false) String afterCode,
                     @RequestParam(required = false, defaultValue = "50") int limit) {
        return storeService.list(organizationId, afterCode, limit);
    }

    record CreateStoreRequest(
            @NotNull UUID marketplaceAccountId,
            @NotBlank String code,
            @NotBlank String displayName,
            String nativeStoreKey,
            String timezone,
            String currencyCode) {
    }

    record UpdateStoreRequest(
            @NotBlank String displayName,
            String nativeStoreKey,
            String timezone,
            String currencyCode,
            String reason,
            @NotNull Long expectedVersion) {
    }

    record StatusChangeRequest(
            @NotNull EntityStatus target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }
}
