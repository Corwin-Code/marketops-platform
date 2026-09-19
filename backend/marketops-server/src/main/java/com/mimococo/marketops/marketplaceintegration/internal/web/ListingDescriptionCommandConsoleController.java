package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.marketplaceintegration.ListingDescriptionCommandGateway;
import com.mimococo.marketops.marketplaceintegration.ListingDescriptionCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.application.KillSwitchService;
import com.mimococo.marketops.marketplaceintegration.internal.application.ListingDescriptionResolutionService;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.KillSwitchRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What happened to a description change, and what an operator can do about it.
 *
 * <p>The timeline carries every attempt and readback as digests and states; the
 * full Russian text stays on the action where it was reviewed.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/listing-description-commands")
class ListingDescriptionCommandConsoleController {

    private final ListingDescriptionCommandGateway commands;
    private final ListingDescriptionResolutionService resolution;
    private final KillSwitchService killSwitch;
    private final BusinessAuthorization authorization;

    ListingDescriptionCommandConsoleController(ListingDescriptionCommandGateway commands,
                                               ListingDescriptionResolutionService resolution,
                                               KillSwitchService killSwitch,
                                               BusinessAuthorization authorization) {
        this.commands = commands;
        this.resolution = resolution;
        this.killSwitch = killSwitch;
        this.authorization = authorization;
    }

    @GetMapping(value = "/{commandId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ListingDescriptionCommandView command(AuthenticatedActor actor, @PathVariable UUID commandId) {
        ListingDescriptionCommandView command = require(commandId);
        authorization.require(actor, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScope.store(command.storeId()));
        return command;
    }

    @GetMapping(value = "/actions/{actionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ListingDescriptionCommandView forAction(AuthenticatedActor actor, @PathVariable UUID actionId) {
        ListingDescriptionCommandView command = commands.forAction(actionId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.require(actor, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScope.store(command.storeId()));
        return command;
    }

    @GetMapping(value = "/{commandId}/gate", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, List<String>> gate(AuthenticatedActor actor, @PathVariable UUID commandId) {
        ListingDescriptionCommandView command = require(commandId);
        authorization.require(actor, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScope.store(command.storeId()));
        return Map.of("reasons", commands.gateReasons(commandId));
    }

    @PostMapping(value = "/{commandId}/take-over", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> takeOver(AuthenticatedActor actor, @PathVariable UUID commandId,
                                 @Valid @RequestBody ReasonRequest request) {
        resolution.takeOver(actor, commandId, request.reason());
        return Map.of("state", "MANUAL_RESOLUTION");
    }

    @PostMapping(value = "/{commandId}/readback", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListingDescriptionCommandView readback(AuthenticatedActor actor, @PathVariable UUID commandId,
                                           @Valid @RequestBody ReasonRequest request) {
        return resolution.readback(actor, commandId, request.reason());
    }

    @PostMapping(value = "/{commandId}/compensation", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ListingDescriptionCommandView compensate(AuthenticatedActor actor, @PathVariable UUID commandId,
                                             @Valid @RequestBody ReasonRequest request) {
        return resolution.compensate(actor, commandId, request.reason());
    }

    @PostMapping(value = "/{commandId}/failure", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> closeAsFailed(AuthenticatedActor actor, @PathVariable UUID commandId,
                                      @Valid @RequestBody ReasonRequest request) {
        resolution.closeAsFailed(actor, commandId, request.reason());
        return Map.of("state", "FAILED_FINAL");
    }

    @GetMapping(value = "/kill-switch", produces = MediaType.APPLICATION_JSON_VALUE)
    List<KillSwitchRepository.FlagRow> flags(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return killSwitch.listingDescriptionFlags();
    }

    @PostMapping(value = "/kill-switch/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> disable(AuthenticatedActor actor, @Valid @RequestBody SwitchRequest request) {
        return Map.of("eventId", killSwitch.disableListingDescriptionWrite(actor, request.scopeKind(),
                request.scopeReference(), request.storeId(), request.reason()));
    }

    @PostMapping(value = "/kill-switch/enable", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> enable(AuthenticatedActor actor, @Valid @RequestBody SwitchRequest request) {
        return Map.of("eventId", killSwitch.enableListingDescriptionWrite(actor, request.scopeKind(),
                request.scopeReference(), request.storeId(), request.reason()));
    }

    private ListingDescriptionCommandView require(UUID commandId) {
        return commands.command(commandId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    record ReasonRequest(@NotBlank String reason) {
    }

    record SwitchRequest(@NotBlank String scopeKind, String scopeReference, UUID storeId,
                         @NotBlank String reason) {
    }
}
