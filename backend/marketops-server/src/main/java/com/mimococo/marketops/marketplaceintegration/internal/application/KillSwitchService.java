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
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.KillSwitchRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PriceCommandRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
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

    private final KillSwitchRepository switches;
    private final PriceCommandRepository commands;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    KillSwitchService(KillSwitchRepository switches,
                      PriceCommandRepository commands,
                      BusinessAuthorization authorization,
                      MetadataAuditRecorder auditRecorder,
                      IdGenerator idGenerator,
                      Clock clock) {
        this.switches = switches;
        this.commands = commands;
        this.authorization = authorization;
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
     */
    @Transactional
    public UUID enable(AuthenticatedActor actor, String scopeKind, String scopeReference,
                       UUID storeId, String reason) {
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        return move(actor, scopeKind, scopeReference, storeId, reason, true);
    }

    /** Every switch movement of one organization, newest first. */
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
        authorization.require(actor, ActionScopeCode.KILL_SWITCH_OPERATE,
                storeId == null
                        ? ResourceScope.organization(actor.organizationId())
                        : ResourceScope.store(storeId));
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        Instant now = clock.instant();

        int inFlight = commands.inFlightCount(actor.organizationId(), storeId);
        switches.setFlagState(PRICE_WRITE_FLAG, scopeKind, scopeReference,
                enable ? "ENABLED" : "DISABLED", now);

        UUID eventId = idGenerator.newId();
        switches.recordEvent(eventId, actor.organizationId(), "PRICE_CHANGE", scopeKind,
                scopeReference, enable ? "ENABLE" : "DISABLE", actor.userId(), validReason,
                inFlight, now, CorrelationId.current());

        log.atWarn()
                .addKeyValue("event", "price_write_switch_moved")
                .addKeyValue("action", enable ? "ENABLE" : "DISABLE")
                .addKeyValue("scopeKind", scopeKind)
                .addKeyValue("inFlightCommandCount", inFlight)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A price write capability switch was moved");

        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, actor.userId().toString(),
                AuditAction.KILL_SWITCH, ENTITY_TYPE, eventId, PRICE_WRITE_FLAG,
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
