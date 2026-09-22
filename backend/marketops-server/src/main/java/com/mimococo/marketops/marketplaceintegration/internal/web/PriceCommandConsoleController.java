package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.marketplaceintegration.PriceCommandState;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.application.KillSwitchService;
import com.mimococo.marketops.marketplaceintegration.internal.application.PriceCommandResolutionService;
import com.mimococo.marketops.marketplaceintegration.internal.application.PriceCommandService;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.KillSwitchRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.SubjectIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * What happened to a price change, and what an operator can do about it.
 *
 * <p>A command's timeline carries every attempt and every readback, because the
 * question an operator asks about an unresolved price change is never just what
 * state it is in. A command sitting in {@code UNKNOWN_REQUIRES_READBACK} is only
 * actionable alongside what was called and what the marketplace answered.
 *
 * <p>Resolving one is a step-up action. Deciding that a price change failed, or
 * that the previous price should be restored, is a decision with a real
 * commercial consequence, so it demands the same recent authentication an
 * approval does.
 */
@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/commands")
class PriceCommandConsoleController {

    private final PriceCommandService commands;
    private final PriceCommandResolutionService resolution;
    private final KillSwitchService killSwitch;
    private final BusinessAuthorization authorization;
    private final ListingIdentityDirectory identities;

    PriceCommandConsoleController(PriceCommandService commands,
                                  PriceCommandResolutionService resolution,
                                  KillSwitchService killSwitch,
                                  BusinessAuthorization authorization,
                                  ListingIdentityDirectory identities) {
        this.commands = commands;
        this.resolution = resolution;
        this.killSwitch = killSwitch;
        this.authorization = authorization;
        this.identities = identities;
    }

    /** One command with its attempts and readbacks. */
    @GetMapping(value = "/{commandId}", produces = MediaType.APPLICATION_JSON_VALUE)
    CommandResponse command(AuthenticatedActor actor, @PathVariable UUID commandId) {
        PriceCommandView command = require(commandId);
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(command.storeId()));
        return named(actor, command);
    }

    /** Recover the existing command after reloading a recommendation; this never creates one. */
    @GetMapping(value = "/recommendations/{recommendationId}", produces = MediaType.APPLICATION_JSON_VALUE)
    CommandResponse forRecommendation(AuthenticatedActor actor, @PathVariable UUID recommendationId) {
        authorization.requireOwned(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                new com.mimococo.marketops.identityaccess.OwnedResource(
                        com.mimococo.marketops.identityaccess.OwnedResource.Kind.RECOMMENDATION, recommendationId));
        return named(actor, commands.forRecommendation(recommendationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND)));
    }

    /** Why the write gate is currently closed for a command, if it is. */
    @GetMapping(value = "/{commandId}/gate", produces = MediaType.APPLICATION_JSON_VALUE)
    GateStatus gate(AuthenticatedActor actor, @PathVariable UUID commandId) {
        PriceCommandView command = require(commandId);
        authorization.require(actor, ActionScopeCode.EVIDENCE_VIEW,
                ResourceScope.store(command.storeId()));
        List<String> reasons = commands.gateReasons(commandId);
        return new GateStatus(reasons.isEmpty(), reasons);
    }

    /** Commands of one store that a person has to look at. */
    @GetMapping(value = "/stores/{storeId}/needing-attention",
            produces = MediaType.APPLICATION_JSON_VALUE)
    List<CommandResponse> needingOperator(AuthenticatedActor actor,
                                          @PathVariable UUID storeId,
                                          @RequestParam(required = false, defaultValue = "50")
                                          int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.store(storeId));
        List<PriceCommandView> found = commands.needingOperator(storeId, limit);
        // One identity read for the whole page; the order stays the service's own.
        Map<UUID, SubjectIdentity> names = identities.identities(actor.organizationId(),
                found.stream().map(PriceCommandView::platformListingVariantId).toList());
        return found.stream()
                .map(command -> CommandResponse.of(command,
                        names.get(command.platformListingVariantId())))
                .toList();
    }

    /** Take an unresolved command out of automatic handling. */
    @PostMapping(value = "/{commandId}/manual-resolution",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void takeOver(AuthenticatedActor actor, @PathVariable UUID commandId,
                  @Valid @RequestBody ReasonRequest request) {
        resolution.takeOver(actor, commandId, request.reason());
    }

    /** Ask the marketplace again what it holds. */
    @PostMapping(value = "/{commandId}/readback", produces = MediaType.APPLICATION_JSON_VALUE)
    CommandResponse readback(AuthenticatedActor actor, @PathVariable UUID commandId,
                             @Valid @RequestBody ReasonRequest request) {
        return named(actor, resolution.readback(actor, commandId, request.reason()));
    }

    /** Authorize restoring the previous price, and perform it. */
    @PostMapping(value = "/{commandId}/compensation",
            produces = MediaType.APPLICATION_JSON_VALUE)
    CommandResponse compensate(AuthenticatedActor actor, @PathVariable UUID commandId,
                               @Valid @RequestBody ReasonRequest request) {
        return named(actor, resolution.compensate(actor, commandId, request.reason()));
    }

    /** Close a command that will not be completed. */
    @PostMapping(value = "/{commandId}/closure", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(AuthenticatedActor actor, @PathVariable UUID commandId,
               @Valid @RequestBody ReasonRequest request) {
        resolution.closeAsFailed(actor, commandId, request.reason());
    }

    /** Stop new writes at one scope. */
    @PostMapping(value = "/kill-switch/disable", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SwitchMoved disableWrites(AuthenticatedActor actor,
                              @Valid @RequestBody SwitchRequest request) {
        return new SwitchMoved(killSwitch.disable(actor, request.scopeKind(),
                request.scopeReference(), request.storeId(), request.reason()));
    }

    /** Allow writes at one scope again. */
    @PostMapping(value = "/kill-switch/enable", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SwitchMoved enableWrites(AuthenticatedActor actor,
                             @Valid @RequestBody SwitchRequest request) {
        return new SwitchMoved(killSwitch.enable(actor, request.scopeKind(),
                request.scopeReference(), request.storeId(), request.reason()));
    }

    /** Which price-write switches exist and what state they are in. */
    @GetMapping(value = "/kill-switch", produces = MediaType.APPLICATION_JSON_VALUE)
    List<KillSwitchRepository.FlagRow> switches(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return killSwitch.currentFlags();
    }

    /** Every switch movement, newest first. */
    @GetMapping(value = "/kill-switch/history", produces = MediaType.APPLICATION_JSON_VALUE)
    List<KillSwitchRepository.SwitchEventRow> switchHistory(
            AuthenticatedActor actor,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW,
                ResourceScope.organization(actor.organizationId()));
        return killSwitch.history(actor.organizationId(), limit);
    }

    private CommandResponse named(AuthenticatedActor actor, PriceCommandView command) {
        UUID subject = command.platformListingVariantId();
        return CommandResponse.of(command, subject == null ? null
                : identities.identities(actor.organizationId(), List.of(subject)).get(subject));
    }

    private PriceCommandView require(UUID commandId) {
        return commands.find(commandId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * Whether a command may currently leave the system.
     *
     * @param open whether nothing blocks it
     * @param blockingReasons every condition that does, empty when open
     */
    record GateStatus(boolean open, List<String> blockingReasons) {
    }

    /**
     * One price command as the console receives it: every member of the
     * published view, unchanged, plus the subject it changes and its names.
     *
     * <p>{@code subjectId} is the listing variant the command writes to. The
     * database copies it from the command's recommendation subject when the
     * command is created, so it is the recommendation's subject by
     * construction; it is repeated under this name so the console can link
     * back to the subject without knowing the command's internal column.
     *
     * @param subjectId the listing variant the command changes
     * @param identity what an operator calls it, or {@code null}
     */
    record CommandResponse(
            UUID id,
            UUID recommendationId,
            UUID storeId,
            UUID platformListingVariantId,
            String platformCode,
            String fulfillmentModeCode,
            String idempotencyKey,
            String currencyCode,
            BigDecimal priorPrice,
            BigDecimal targetPrice,
            PriceCommandState state,
            int attemptNo,
            int retryBudgetRemaining,
            String failureCode,
            String leaseOwner,
            Instant leaseExpiresAt,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant terminalAt,
            List<PriceCommandView.Attempt> attempts,
            List<PriceCommandView.Readback> readbacks,
            UUID subjectId,
            SubjectIdentity identity) {

        static CommandResponse of(PriceCommandView view, SubjectIdentity identity) {
            return new CommandResponse(view.id(), view.recommendationId(), view.storeId(),
                    view.platformListingVariantId(), view.platformCode(),
                    view.fulfillmentModeCode(), view.idempotencyKey(), view.currencyCode(),
                    view.priorPrice(), view.targetPrice(), view.state(), view.attemptNo(),
                    view.retryBudgetRemaining(), view.failureCode(), view.leaseOwner(),
                    view.leaseExpiresAt(), view.nextAttemptAt(), view.createdAt(),
                    view.terminalAt(), view.attempts(), view.readbacks(),
                    view.platformListingVariantId(), identity);
        }
    }

    /** What a switch movement recorded. */
    record SwitchMoved(UUID eventId) {
    }

    record ReasonRequest(@NotBlank String reason) {
    }

    record SwitchRequest(@NotBlank String scopeKind, String scopeReference, UUID storeId,
                         @NotBlank String reason) {
    }
}
