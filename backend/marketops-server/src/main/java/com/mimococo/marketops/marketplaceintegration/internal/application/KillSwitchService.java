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
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentCommandRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.KillSwitchRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ListingDescriptionCommandRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.shared.ProductionWritePolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turning platform writes off, and back on.
 *
 * <p>Off is never gated beyond holding the grant. An operator who believes
 * something is going wrong must be able to stop it immediately, and a step-up
 * prompt at that moment is a delay measured in real price changes. On is gated:
 * re-enabling writes widens exposure, so it demands the same recent
 * authentication a price approval does.
 *
 * <p>Throwing a switch stops new writes; it does not reach into a command that
 * has already been claimed. That is why the number of commands still in flight
 * is recorded with the event: an operator needs to know what is still moving,
 * and a switch that silently implied "everything has stopped" would be worse
 * than one that says how much has not.
 */
@Service
public class KillSwitchService {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchService.class);

    static final String ENTITY_TYPE = "kill-switch-event";

    /** The flag every price write is gated on. */
    static final String PRICE_WRITE_FLAG = "price-change-write";

    /** The description write's own switch. Absent is off, at every scope. */
    static final String LISTING_DESCRIPTION_WRITE_FLAG = "listing-description-write";

    /** The title and description write's switch (W2, V0035). Absent is off, at every scope. */
    static final String CONTENT_WRITE_FLAG = ContentCommandService.CONTENT_WRITE_FLAG;

    private final KillSwitchRepository switches;
    private final PriceCommandRepository commands;
    private final ListingDescriptionCommandRepository descriptionCommands;
    private final ContentCommandRepository contentCommands;
    private final BusinessAuthorization authorization;
    private final ProductionWritePolicy productionWrites;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    KillSwitchService(KillSwitchRepository switches,
                      PriceCommandRepository commands,
                      ListingDescriptionCommandRepository descriptionCommands,
                      ContentCommandRepository contentCommands,
                      BusinessAuthorization authorization,
                      ProductionWritePolicy productionWrites,
                      MetadataAuditRecorder auditRecorder,
                      IdGenerator idGenerator,
                      Clock clock) {
        this.switches = switches;
        this.commands = commands;
        this.descriptionCommands = descriptionCommands;
        this.contentCommands = contentCommands;
        this.authorization = authorization;
        this.productionWrites = productionWrites;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Stop new writes at one scope. Never gated beyond the grant. */
    @Transactional
    public UUID disable(AuthenticatedActor actor, String scopeKind, String scopeReference,
                        UUID storeId, String reason) {
        return move(actor, scopeKind, scopeReference, storeId, reason, false);
    }

    /**
     * Allow writes at one scope again.
     *
     * <p>Gated on a recent authentication because it widens real commercial
     * exposure. The direction that reduces exposure is always available; the
     * direction that increases it is a decision somebody is accountable for.
     *
     * <p>Also gated on the deployment's production-write switch, like the
     * registry's own flag command: a deployment whose writes are off cannot
     * switch one on from the console either.
     */
    @Transactional
    public UUID enable(AuthenticatedActor actor, String scopeKind, String scopeReference,
                       UUID storeId, String reason) {
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (!productionWrites.productionWritesEnabled()) {
            throw OperationRejectedException.of(ErrorCode.PRODUCTION_WRITE_DISABLED);
        }
        return move(actor, scopeKind, scopeReference, storeId, reason, true);
    }

    /** Every switch movement of one organization, newest first. */
    /** Disable the description write at one scope. Disabling never needs step-up. */
    @Transactional
    public UUID disableListingDescriptionWrite(AuthenticatedActor actor, String scopeKind,
                                               String scopeReference, UUID storeId, String reason) {
        return move(actor, LISTING_DESCRIPTION_WRITE_FLAG, "LISTING_DESCRIPTION_CHANGE",
                scopeKind, scopeReference, storeId, reason, false);
    }

    /** Re-enable the description write at one scope, with step-up. */
    @Transactional
    public UUID enableListingDescriptionWrite(AuthenticatedActor actor, String scopeKind,
                                              String scopeReference, UUID storeId, String reason) {
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (!productionWrites.productionWritesEnabled()) {
            throw OperationRejectedException.of(ErrorCode.PRODUCTION_WRITE_DISABLED);
        }
        return move(actor, LISTING_DESCRIPTION_WRITE_FLAG, "LISTING_DESCRIPTION_CHANGE",
                scopeKind, scopeReference, storeId, reason, true);
    }

    /** Disable the title and description write at one scope. Disabling never needs step-up. */
    @Transactional
    public UUID disableContentWrite(AuthenticatedActor actor, String scopeKind, String scopeReference,
                                    UUID storeId, String reason) {
        return move(actor, CONTENT_WRITE_FLAG, "LISTING_CONTENT_CHANGE", scopeKind, scopeReference, storeId,
                reason, false);
    }

    /** Re-enable the title and description write at one scope, with step-up. */
    @Transactional
    public UUID enableContentWrite(AuthenticatedActor actor, String scopeKind, String scopeReference,
                                   UUID storeId, String reason) {
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        if (!productionWrites.productionWritesEnabled()) {
            throw OperationRejectedException.of(ErrorCode.PRODUCTION_WRITE_DISABLED);
        }
        return move(actor, CONTENT_WRITE_FLAG, "LISTING_CONTENT_CHANGE", scopeKind, scopeReference, storeId,
                reason, true);
    }

    /** Which title and description write switches exist and what state they are in. */
    @Transactional(readOnly = true)
    public List<KillSwitchRepository.FlagRow> contentWriteFlags() {
        return switches.flags(CONTENT_WRITE_FLAG);
    }

    @Transactional(readOnly = true)
    public List<KillSwitchRepository.FlagRow> listingDescriptionFlags() {
        return switches.flags(LISTING_DESCRIPTION_WRITE_FLAG);
    }

    @Transactional(readOnly = true)
    public List<KillSwitchRepository.SwitchEventRow> history(UUID organizationId, int limit) {
        return switches.history(organizationId, limit);
    }

    /** Which price-write switches are currently on. */
    @Transactional(readOnly = true)
    public List<KillSwitchRepository.FlagRow> currentFlags() {
        return switches.priceWriteFlags();
    }

    private UUID move(AuthenticatedActor actor, String scopeKind, String scopeReference,
                      UUID storeId, String reason, boolean enable) {
        return move(actor, PRICE_WRITE_FLAG, "PRICE_CHANGE", scopeKind, scopeReference, storeId,
                reason, enable);
    }

    private UUID move(AuthenticatedActor actor, String flagCode, String actionKind, String scopeKind,
                      String scopeReference, UUID storeId, String reason, boolean enable) {
        authorization.require(actor, ActionScopeCode.KILL_SWITCH_OPERATE,
                storeId == null
                        ? ResourceScope.organization(actor.organizationId())
                        : ResourceScope.store(storeId));
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Instant now = clock.instant();

        int inFlight = switch (actionKind) {
            case "PRICE_CHANGE" -> commands.inFlightCount(actor.organizationId(), storeId);
            case "LISTING_CONTENT_CHANGE" -> contentCommands.inFlightCount(actor.organizationId(), storeId);
            default -> descriptionCommands.inFlightCount(actor.organizationId(), storeId);
        };
        switches.setFlagState(flagCode, scopeKind, scopeReference,
                enable ? "ENABLED" : "DISABLED", now);

        UUID eventId = idGenerator.newId();
        switches.recordEvent(eventId, actor.organizationId(), actionKind, scopeKind,
                scopeReference, enable ? "ENABLE" : "DISABLE", actor.userId(), validReason,
                inFlight, now, CorrelationId.current());

        log.atWarn()
                .addKeyValue("event", switch (actionKind) {
                    case "PRICE_CHANGE" -> "price_write_switch_moved";
                    case "LISTING_CONTENT_CHANGE" -> "content_write_switch_moved";
                    default -> "listing_description_write_switch_moved";
                })
                .addKeyValue("action", enable ? "ENABLE" : "DISABLE")
                .addKeyValue("scopeKind", scopeKind)
                .addKeyValue("inFlightCommandCount", inFlight)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A write capability switch was moved");

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, actor.userId().toString(),
                AuditAction.KILL_SWITCH, ENTITY_TYPE, eventId, flagCode,
                Map.of(
                        "scopeKind", new FieldChange(null, scopeKind),
                        "state", new FieldChange(enable ? "DISABLED" : "ENABLED",
                                enable ? "ENABLED" : "DISABLED"),
                        "inFlightCommandCount", new FieldChange(null,
                                Integer.toString(inFlight))),
                validReason, null));
        return eventId;
    }
}
