package com.mimococo.marketops.operationsworkflow.internal.web;

import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.ResourceScope;
import org.springframework.web.bind.annotation.GetMapping;
import com.mimococo.marketops.marketplaceintegration.AdBidCommandGateway;
import com.mimococo.marketops.marketplaceintegration.AdBidCommandView;
import com.mimococo.marketops.marketplaceintegration.AdBidCompensationAuthority;
import com.mimococo.marketops.operationsworkflow.AdvertisingDisclosurePolicy;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@com.mimococo.marketops.shared.ConsoleApi
@RequestMapping("/api/v1/console/workflow/ad-bid-compensations")
class AdBidCompensationController {
    private final AdBidCompensationAuthority compensation;
    private final AdBidCommandGateway commands;
    private final AdvertisingDisclosurePolicy disclosure;
    private final BusinessAuthorization authorization;

    AdBidCompensationController(AdBidCompensationAuthority compensation,
            AdBidCommandGateway commands, AdvertisingDisclosurePolicy disclosure, BusinessAuthorization authorization) {
        this.compensation = compensation;
        this.commands = commands;
        this.disclosure = disclosure;
        this.authorization = authorization;
    }

    @GetMapping("/commands/{commandId}")
    AdBidCompensationAuthority.Context context(AuthenticatedActor actor,@PathVariable UUID commandId) {
        return requireNativeScope(actor,commandId,ActionScopeCode.ADVERTISING_VIEW);
    }

    @PostMapping("/commands/{commandId}/preview")
    Preview preview(AuthenticatedActor actor, @PathVariable UUID commandId,
            @Valid @RequestBody PreviewRequest request) {
        requireNativeScope(actor,commandId,ActionScopeCode.ADVERTISING_TASK_ACT);
        AdBidCommandView command = commands.command(commandId).orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        UUID previewId = compensation.preview(commandId, request.compensationBundleId());
        return new Preview(previewId, commandId, request.compensationBundleId(),
                command.targetBidAmount(), command.priorBidAmount(), command.currencyCode(),
                command.bidUnitCode(), command.affectedSetDigest());
    }

    @PostMapping("/{previewId}/endorsement")
    Map<String, String> endorse(AuthenticatedActor actor, @PathVariable UUID previewId) {
        requireDisclosure(actor, compensation.commandForPreview(previewId));
        compensation.endorse(previewId);
        return Map.of("state", "ENDORSED");
    }

    @PostMapping("/{previewId}/approval")
    Map<String, String> approve(AuthenticatedActor actor, @PathVariable UUID previewId) {
        requireDisclosure(actor, compensation.commandForPreview(previewId));
        compensation.approve(previewId);
        return Map.of("state", "COMPENSATION_PENDING");
    }

    private AdBidCompensationAuthority.Context requireNativeScope(AuthenticatedActor actor,UUID commandId,ActionScopeCode action) {
        var context=compensation.context(commandId,actor.userId());
        if(!actor.organizationId().equals(context.organizationId())) throw OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND);
        authorization.require(actor,action,ResourceScope.store(context.storeId()));
        for(UUID variant:context.productVariantIds()) authorization.require(actor,action,ResourceScope.productVariant(variant));
        return context;
    }

    private AdBidCommandView requireDisclosure(AuthenticatedActor actor, UUID commandId) {
        AdBidCommandView command = commands.command(commandId).orElseThrow(
                () -> OperationRejectedException.of(ErrorCode.VALIDATION_FAILED));
        disclosure.requireDecisionEvidence(actor, command.adNativeObjectId(), command.affectedSetDigest());
        return command;
    }

    record PreviewRequest(@NotNull UUID compensationBundleId) { }
    record Preview(UUID previewId, UUID commandId, UUID compensationBundleId,
            BigDecimal currentOwnerBid, BigDecimal exactPriorBid, String currencyCode,
            String bidUnitCode, String affectedSetDigest) { }
}
