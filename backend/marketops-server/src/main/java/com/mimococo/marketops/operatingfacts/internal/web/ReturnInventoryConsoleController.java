package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operatingfacts.internal.application.ReturnInventoryTransitionService;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.ReturnInventoryTransitionRepository.Transition;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Attributable QC/re-entry intake for returned goods. */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/availability/returns")
class ReturnInventoryConsoleController {

    private final ReturnInventoryTransitionService service;
    private final BusinessAuthorization authorization;

    ReturnInventoryConsoleController(ReturnInventoryTransitionService service,
                                     BusinessAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping("/{returnFactId}/transitions")
    @ResponseStatus(HttpStatus.CREATED)
    Transition record(AuthenticatedActor actor, @PathVariable UUID returnFactId,
                      @Valid @RequestBody Body body) {
        var context = service.context(returnFactId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.INTERNAL_FACT_INTAKE,
                ResourceScope.productVariant(context.productVariantId()));
        if (body.warehouseId() != null) {
            authorization.require(actor, ActionScopeCode.INTERNAL_FACT_INTAKE,
                    ResourceScope.warehouse(body.warehouseId()));
        }
        return service.record(returnFactId, actor.organizationId(), actor.userId(),
                new ReturnInventoryTransitionService.Draft(body.state(), body.quantity(),
                        body.warehouseId(), body.qualityDisposition(), body.evidenceReference(),
                        body.occurredAt(), body.supersedesTransitionId()));
    }

    record Body(@NotBlank String state, @Min(1) int quantity, UUID warehouseId,
                String qualityDisposition, @NotBlank String evidenceReference,
                @NotNull Instant occurredAt, UUID supersedesTransitionId) {
    }
}
