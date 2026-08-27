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
import com.mimococo.marketops.marketplaceintegration.PriceCommandState;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an operator can do about a command that stopped moving on its own.
 *
 * <p>Four actions, and none of them is a way around a rule. Taking a command
 * over records that a person owns it. Reading back asks the marketplace again
 * and records what it answered. Restoring is authorized only while the platform
 * still holds what this command wrote, which the database checks. Closing it as
 * failed names a reason and ends it.
 *
 * <p>There is deliberately no action that marks a command succeeded by
 * assertion. A success claim rests on a readback that observed the intended
 * value, and an operator who believes the price is right can produce that
 * readback rather than assert it.
 *
 * <p>All four are step-up actions. Deciding the fate of a change that may
 * already have altered a real price is a consequential act, so it demands the
 * same recent authentication an approval does.
 */
@Service
public class PriceCommandResolutionService {

    static final String ENTITY_TYPE = "price-command";

    private final PriceCommandRepository commands;
    private final PriceCommandWorker worker;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder auditRecorder;
    private final Clock clock;

    PriceCommandResolutionService(PriceCommandRepository commands,
                                  PriceCommandWorker worker,
                                  BusinessAuthorization authorization,
                                  MetadataAuditRecorder auditRecorder,
                                  Clock clock) {
        this.commands = commands;
        this.worker = worker;
        this.authorization = authorization;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
    }

    /** Record that a person owns an unresolved command. */
    @Transactional
    public void takeOver(AuthenticatedActor actor, UUID commandId, String reason) {
        PriceCommandRepository.CommandRow command = require(actor, commandId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        commands.transition(commandId, command.fenceToken(), command.leaseOwner(),
                PriceCommandState.MANUAL_RESOLUTION.name(), null, null, null);
        record(actor, commandId, command.state(), PriceCommandState.MANUAL_RESOLUTION,
                validReason);
    }

    /**
     * Ask the marketplace again what it holds.
     *
     * <p>This is the only way out of an unknown result that does not involve a
     * person deciding by hand, and it is why there is no transition from unknown
     * back to executing: the write is never repeated, only observed.
     */
    @Transactional
    public PriceCommandView readback(AuthenticatedActor actor, UUID commandId, String reason) {
        PriceCommandRepository.CommandRow command = require(actor, commandId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (command.state() != PriceCommandState.UNKNOWN_REQUIRES_READBACK) {
            throw OperationRejectedException.of(ErrorCode.COMMAND_STATE_INVALID);
        }
        commands.transition(commandId, command.fenceToken(), command.leaseOwner(),
                PriceCommandState.READBACK_PENDING.name(), null, null, null);
        record(actor, commandId, command.state(), PriceCommandState.READBACK_PENDING,
                validReason);
        worker.advance(commandId);
        return commands.find(commandId).orElseThrow();
    }

    /**
     * Authorize restoring the previous price, and perform it.
     *
     * <p>The database refuses the authorization unless the latest readback still
     * observes what this command wrote. If anything else moved the price since,
     * restoring would overwrite a change nobody here decided, which is not
     * compensation.
     */
    @Transactional
    public PriceCommandView compensate(AuthenticatedActor actor, UUID commandId,
                                       String reason) {
        PriceCommandRepository.CommandRow command = require(actor, commandId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        commands.transition(commandId, command.fenceToken(), command.leaseOwner(),
                PriceCommandState.COMPENSATION_PENDING.name(), null, null, null);
        record(actor, commandId, command.state(), PriceCommandState.COMPENSATION_PENDING,
                validReason);
        worker.compensate(commandId);
        return commands.find(commandId).orElseThrow();
    }

    /** Close a command that will not be completed. */
    @Transactional
    public void closeAsFailed(AuthenticatedActor actor, UUID commandId, String reason) {
        PriceCommandRepository.CommandRow command = require(actor, commandId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        commands.transition(commandId, command.fenceToken(), command.leaseOwner(),
                PriceCommandState.FAILED_FINAL.name(), "closed_by_operator", null, null);
        record(actor, commandId, command.state(), PriceCommandState.FAILED_FINAL, validReason);
    }

    /**
     * The command must exist, be in this operator's scope, and be theirs to act
     * on right now.
     */
    private PriceCommandRepository.CommandRow require(AuthenticatedActor actor,
                                                      UUID commandId) {
        PriceCommandRepository.CommandRow command = commands.row(commandId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.require(actor, ActionScopeCode.COMMAND_RESOLVE,
                ResourceScope.store(command.storeId()));
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        return command;
    }

    private void record(AuthenticatedActor actor, UUID commandId, PriceCommandState from,
                        PriceCommandState to, String reason) {
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, actor.userId().toString(),
                AuditAction.COMMAND_TRANSITION, ENTITY_TYPE, commandId, null,
                Map.of("state", new FieldChange(from.name(), to.name())),
                reason, null));
    }
}
