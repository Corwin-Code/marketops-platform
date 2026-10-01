package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.marketplaceintegration.ContentCommandView;
import com.mimococo.marketops.marketplaceintegration.ContentWriteStatus;
import com.mimococo.marketops.marketplaceintegration.internal.application.ContentCommandService;
import com.mimococo.marketops.marketplaceintegration.internal.application.KillSwitchService;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.KillSwitchRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * What became of a listing title and description change, and what a person may do about one the
 * worker could not finish (W2): read the card again, or close the command. Both are step-up
 * actions, like resolving a price command. Changes are submitted through the content change
 * console, where the Owner confirms them.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/content-commands")
class ContentCommandConsoleController {

    private final ContentCommandService commands;
    private final KillSwitchService killSwitch;
    private final BusinessAuthorization authorization;

    ContentCommandConsoleController(ContentCommandService commands, KillSwitchService killSwitch,
                                    BusinessAuthorization authorization) {
        this.commands = commands;
        this.killSwitch = killSwitch;
        this.authorization = authorization;
    }

    /** The newest content commands of a store. */
    @GetMapping(value = "/stores/{storeId}", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ContentCommandView> forStore(AuthenticatedActor actor, @PathVariable UUID storeId,
                                      @RequestParam(required = false, defaultValue = "50") int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        return commands.forStore(actor.organizationId(), storeId, limit);
    }

    /** One command with every call it made. */
    @GetMapping(value = "/{commandId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ContentCommandView one(AuthenticatedActor actor, @PathVariable UUID commandId) {
        ContentCommandView command = require(actor, commandId);
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(command.storeId()));
        return command;
    }

    /** Why a command may not write now; empty when it may. */
    @GetMapping(value = "/{commandId}/gate", produces = MediaType.APPLICATION_JSON_VALUE)
    List<String> gate(AuthenticatedActor actor, @PathVariable UUID commandId) {
        ContentCommandView command = require(actor, commandId);
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(command.storeId()));
        return commands.gate(actor.organizationId(), commandId);
    }

    /** Read the card again: for a command that is unknown or did not match. */
    @PostMapping(value = "/{commandId}/readback", produces = MediaType.APPLICATION_JSON_VALUE)
    ContentCommandView readback(AuthenticatedActor actor, @PathVariable UUID commandId) {
        ContentCommandView command = require(actor, commandId);
        authorization.require(actor, ActionScopeCode.COMMAND_RESOLVE, ResourceScope.store(command.storeId()));
        return commands.requestReadback(actor.organizationId(), commandId, actor.userId());
    }

    /** Close a command: withdrawn before any write, or taken over by a person after one. */
    @PostMapping(value = "/{commandId}/closure", produces = MediaType.APPLICATION_JSON_VALUE)
    ContentCommandView close(AuthenticatedActor actor, @PathVariable UUID commandId,
                             @Valid @RequestBody ReasonRequest request) {
        ContentCommandView command = require(actor, commandId);
        authorization.require(actor, ActionScopeCode.COMMAND_RESOLVE, ResourceScope.store(command.storeId()));
        return commands.close(actor.organizationId(), commandId, actor.userId(), request.reason());
    }

    /** How far a store is from taking content writes. */
    @GetMapping(value = "/stores/{storeId}/write-status", produces = MediaType.APPLICATION_JSON_VALUE)
    ContentWriteStatus writeStatus(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        return commands.writeStatus(storeId);
    }

    /** Which content-write switches exist and what state they are in. */
    @GetMapping(value = "/kill-switch", produces = MediaType.APPLICATION_JSON_VALUE)
    List<KillSwitchRepository.FlagRow> switches(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return killSwitch.contentWriteFlags();
    }

    /** Stop new content writes at one scope. */
    @PostMapping(value = "/kill-switch/disable", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SwitchMoved disable(AuthenticatedActor actor, @Valid @RequestBody SwitchRequest request) {
        return new SwitchMoved(killSwitch.disableContentWrite(actor, request.scopeKind(), request.scopeReference(),
                request.storeId(), request.reason()));
    }

    /** Allow content writes at one scope again. */
    @PostMapping(value = "/kill-switch/enable", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SwitchMoved enable(AuthenticatedActor actor, @Valid @RequestBody SwitchRequest request) {
        return new SwitchMoved(killSwitch.enableContentWrite(actor, request.scopeKind(), request.scopeReference(),
                request.storeId(), request.reason()));
    }

    private ContentCommandView require(AuthenticatedActor actor, UUID commandId) {
        return commands.find(actor.organizationId(), commandId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    record SwitchMoved(UUID eventId) {
    }

    record ReasonRequest(@NotBlank String reason) {
    }

    record SwitchRequest(@NotBlank String scopeKind, String scopeReference, UUID storeId,
                         @NotBlank String reason) {
    }
}
