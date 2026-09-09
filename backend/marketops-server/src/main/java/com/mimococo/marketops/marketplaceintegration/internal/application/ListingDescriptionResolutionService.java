package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.marketplaceintegration.ListingDescriptionCommandState;
import com.mimococo.marketops.marketplaceintegration.ListingDescriptionCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ListingDescriptionCommandRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an operator can do about a description command that will not progress.
 *
 * <p>Resolving one is a step-up action. A readback is the only way out of an
 * unknown result that does not involve a person deciding by hand; a restore is
 * authorised only while the text the platform holds is the one this command
 * wrote and only when a complete prior text was captured, and the database
 * refuses the rest.
 */
@Service
public class ListingDescriptionResolutionService {

    static final String ENTITY_TYPE = "lc-description-command";

    private final ListingDescriptionCommandRepository commands;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder auditRecorder;
    private final Clock clock;

    ListingDescriptionResolutionService(ListingDescriptionCommandRepository commands,
                                        BusinessAuthorization authorization,
                                        MetadataAuditRecorder auditRecorder,
                                        Clock clock) {
        this.commands = commands;
        this.authorization = authorization;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    @Transactional
    public void takeOver(AuthenticatedActor actor, UUID commandId, String reason) {
        ListingDescriptionCommandRepository.CommandRow command = require(actor, commandId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        commands.transition(commandId, command.fenceToken(), command.leaseOwner(),
                ListingDescriptionCommandState.MANUAL_RESOLUTION.name(), null, null, null);
        record(actor, commandId, command.state(), ListingDescriptionCommandState.MANUAL_RESOLUTION, validReason);
    }

    @Transactional
    public ListingDescriptionCommandView readback(AuthenticatedActor actor, UUID commandId, String reason) {
        ListingDescriptionCommandRepository.CommandRow command = require(actor, commandId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!ListingDescriptionCommandState.UNKNOWN_REQUIRES_READBACK.name().equals(command.state())) {
            throw OperationRejectedException.of(ErrorCode.COMMAND_STATE_INVALID);
        }
        commands.requestReadback(commandId, command.fenceToken());
        record(actor, commandId, command.state(), ListingDescriptionCommandState.READBACK_PENDING, validReason);
        return commands.view(commandId).orElseThrow();
    }

    @Transactional
    public ListingDescriptionCommandView compensate(AuthenticatedActor actor, UUID commandId, String reason) {
        ListingDescriptionCommandRepository.CommandRow command = require(actor, commandId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (command.priorText() == null) {
            // An empty or missing prior value never becomes a space, a placeholder
            // or a full import. There is nothing precise to restore.
            throw OperationRejectedException.of(ErrorCode.RESTORE_UNSUPPORTED);
        }
        commands.transition(commandId, command.fenceToken(), command.leaseOwner(),
                ListingDescriptionCommandState.COMPENSATION_PENDING.name(), null, null, null);
        record(actor, commandId, command.state(), ListingDescriptionCommandState.COMPENSATION_PENDING, validReason);
        return commands.view(commandId).orElseThrow();
    }

    @Transactional
    public void closeAsFailed(AuthenticatedActor actor, UUID commandId, String reason) {
        ListingDescriptionCommandRepository.CommandRow command = require(actor, commandId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        commands.transition(commandId, command.fenceToken(), command.leaseOwner(),
                ListingDescriptionCommandState.FAILED_FINAL.name(), "closed_by_operator", null, null);
        record(actor, commandId, command.state(), ListingDescriptionCommandState.FAILED_FINAL, validReason);
    }

    private ListingDescriptionCommandRepository.CommandRow require(AuthenticatedActor actor, UUID commandId) {
        ListingDescriptionCommandRepository.CommandRow command = commands.row(commandId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.require(actor, ActionScopeCode.COMMAND_RESOLVE, ResourceScope.store(command.storeId()));
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        return command;
    }

    private void record(AuthenticatedActor actor, UUID commandId, String from,
                        ListingDescriptionCommandState to, String reason) {
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, actor.userId().toString(),
                AuditAction.COMMAND_TRANSITION, ENTITY_TYPE, commandId, null,
                Map.of("state", new FieldChange(from, to.name())), reason, null));
    }
}
