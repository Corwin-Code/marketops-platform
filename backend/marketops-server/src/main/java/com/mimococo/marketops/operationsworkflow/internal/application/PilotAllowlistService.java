package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.AllowlistRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deciding exactly which entities a real platform write may touch.
 *
 * <p>Adding an entry widens real commercial exposure, so it demands the same
 * recent authentication a price approval does and is recorded with the person,
 * the window and the reason. Removing one narrows exposure and is never gated:
 * the direction towards closed must always be available.
 */
@Service
public class PilotAllowlistService {

    static final String ENTITY_TYPE = "pilot-allowlist-entry";

    private final AllowlistRepository entries;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    PilotAllowlistService(AllowlistRepository entries,
                          BusinessAuthorization authorization,
                          MetadataAuditRecorder auditRecorder,
                          IdGenerator idGenerator,
                          Clock clock) {
        this.entries = entries;
        this.authorization = authorization;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** The kinds of write an entry may allow: price changes, and title and description changes (W2). */
    private static final java.util.Set<String> ACTION_KINDS = java.util.Set.of("PRICE_CHANGE", "LISTING_CONTENT_CHANGE");

    /** Put one store, or one listing variant within it, on the list for price changes. */
    @Transactional
    public UUID grant(AuthenticatedActor actor, String platformCode, UUID storeId,
                      UUID platformListingVariantId, Instant validFrom, Instant validUntil,
                      String reason) {
        return grant(actor, "PRICE_CHANGE", platformCode, storeId, platformListingVariantId, validFrom, validUntil,
                reason);
    }

    /** Put one store, or one listing variant within it, on the list for one kind of write. */
    @Transactional
    public UUID grant(AuthenticatedActor actor, String actionKind, String platformCode, UUID storeId,
                      UUID platformListingVariantId, Instant validFrom, Instant validUntil,
                      String reason) {
        if (actionKind == null || !ACTION_KINDS.contains(actionKind)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.store(storeId));
        Instant now = clock.instant();
        if (!actor.stepUpSatisfiedAt(now)) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (validFrom == null || validUntil == null || !validFrom.isBefore(validUntil)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        UUID id = idGenerator.newId();
        entries.insert(id, actor.organizationId(), actionKind, platformCode, storeId,
                platformListingVariantId, validFrom, validUntil, actor.userId(), validReason,
                now);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.GRANT, ENTITY_TYPE, id, null,
                Map.of(
                        "actionKind", new FieldChange(null, actionKind),
                        "storeId", new FieldChange(null, storeId.toString()),
                        "platformListingVariantId", new FieldChange(null,
                                platformListingVariantId == null
                                        ? "ENTIRE_STORE" : platformListingVariantId.toString()),
                        "validUntil", new FieldChange(null, validUntil.toString())),
                validReason, null));
        return id;
    }

    /** Take an entry off the list. The direction to closed is never gated. */
    @Transactional
    public void revoke(AuthenticatedActor actor, UUID id, String revokedReason,
                       long expectedVersion) {
        authorization.require(actor, ActionScopeCode.COMMERCIAL_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
        String reason = MetadataFieldPolicy.requireText("revokedReason", revokedReason);
        if (!entries.revoke(id, reason, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, actor.userId().toString(),
                AuditAction.REVOKE, ENTITY_TYPE, id, null,
                Map.of("status", new FieldChange("ACTIVE", "REVOKED")),
                reason, null));
    }

    /** Whether one listing variant is covered now. */
    @Transactional(readOnly = true)
    public boolean covers(UUID storeId, UUID platformListingVariantId) {
        return entries.covers(storeId, platformListingVariantId, clock.instant());
    }

    /** Every entry of one organization, newest first. */
    @Transactional(readOnly = true)
    public List<AllowlistRepository.AllowlistRow> list(UUID organizationId) {
        return entries.list(organizationId);
    }
}
