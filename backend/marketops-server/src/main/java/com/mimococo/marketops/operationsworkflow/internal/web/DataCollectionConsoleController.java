package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.application.ScheduledCollectionService;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scheduled collection of one store: its state for anybody who may read the diagnosis, and the
 * Owner's standing authorization to put it in force or retire it.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/stores")
class DataCollectionConsoleController {

    private final ScheduledCollectionService collection;
    private final BusinessAuthorization authorization;

    DataCollectionConsoleController(ScheduledCollectionService collection, BusinessAuthorization authorization) {
        this.collection = collection;
        this.authorization = authorization;
    }

    /** The policy, every scheduled job's state, the kept calculations and the newest records. */
    @GetMapping(value = "/{storeId}/data-collection", produces = MediaType.APPLICATION_JSON_VALUE)
    ScheduledCollectionService.Status status(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        return collection.status(actor.organizationId(), storeId);
    }

    /** Put scheduled collection in force for the store; the next pass collects what is due. */
    @PostMapping(value = "/{storeId}/data-collection/policy", produces = MediaType.APPLICATION_JSON_VALUE)
    ScheduledCollectionService.Status enable(AuthenticatedActor actor, @PathVariable UUID storeId,
                                             @Valid @RequestBody EnableRequest request) {
        authorization.require(actor, ActionScopeCode.DATA_COLLECTION_MANAGE, ResourceScope.store(storeId));
        collection.enable(actor, storeId, request.reason());
        return collection.status(actor.organizationId(), storeId);
    }

    /** Take scheduled collection out of force; every run and fact stays in place. */
    @PostMapping(value = "/{storeId}/data-collection/policy/retirement",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ScheduledCollectionService.Status retire(AuthenticatedActor actor, @PathVariable UUID storeId,
                                             @Valid @RequestBody RetireRequest request) {
        authorization.require(actor, ActionScopeCode.DATA_COLLECTION_MANAGE, ResourceScope.store(storeId));
        collection.retire(actor, storeId, request.reason(), request.expectedVersion());
        return collection.status(actor.organizationId(), storeId);
    }

    record EnableRequest(@NotBlank String reason) {
    }

    record RetireRequest(@NotBlank String reason, @NotNull Long expectedVersion) {
    }
}
