package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialMetadata;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialScopeMode;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialScopeUsability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStoreScope;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RotationStanding;
import com.mimococo.marketops.marketplaceintegration.internal.domain.StoreScopeStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CredentialPurposeRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.CredentialRepository;
import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance operations on credential metadata and its store scope.
 *
 * <p>Scope is declared, never inferred: the scope mode is given at creation,
 * mode changes are explicit commands, and a store-set credential whose active
 * scope rows are all withdrawn is unusable rather than account-wide. Rotation
 * is a lineage: a successor names its predecessor, both may be active at once,
 * and disabling or revoking the predecessor completes the handover.
 */
@Service
public class CredentialService {

    static final String ENTITY_TYPE = "credential";
    static final String SCOPE_ENTITY_TYPE = "credential-store-scope";

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    private final CredentialRepository credentials;
    private final CredentialPurposeRepository purposes;
    private final OrganizationDirectory organizationDirectory;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    CredentialService(CredentialRepository credentials,
                      CredentialPurposeRepository purposes,
                      OrganizationDirectory organizationDirectory,
                      MetadataAuditRecorder auditRecorder,
                      IdGenerator idGenerator,
                      Clock clock) {
        this.credentials = credentials;
        this.purposes = purposes;
        this.organizationDirectory = organizationDirectory;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * Register credential metadata; a store-set credential receives its initial
     * scope rows in the same transaction.
     */
    @Transactional
    public CredentialView create(String operator,
                                 UUID marketplaceAccountId,
                                 String code,
                                 String displayName,
                                 String purposeCode,
                                 CredentialScopeMode scopeMode,
                                 String secretReference,
                                 Instant effectiveFrom,
                                 Instant expiresAt,
                                 UUID replacesCredentialId,
                                 String custodianLabel,
                                 List<UUID> storeIds) {
        MarketplaceAccountRef account = requireActiveAccount(marketplaceAccountId);
        String validCode = MetadataFieldPolicy.requireCode(code);
        String validDisplayName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validCustodian = MetadataFieldPolicy.requireText("custodianLabel", custodianLabel);
        if (purposeCode == null || !purposes.purposeExists(purposeCode)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (scopeMode == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String validReference = MetadataFieldPolicy.requireSecretReference(secretReference);
        requireWindow(effectiveFrom, expiresAt);
        requireRotationTarget(replacesCredentialId, account.id(), purposeCode);
        Set<UUID> initialStores = requireScopeSet(scopeMode, storeIds, account.id());
        credentials.findByCode(account.organizationId(), validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, validCode, existing.id());
        });
        credentials.findLiveBySecretReference(validReference).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, existing.code(), existing.id());
        });

        Instant now = clock.instant();
        CredentialMetadata credential = new CredentialMetadata(
                idGenerator.newId(), account.organizationId(), account.id(), validCode,
                validDisplayName, purposeCode, scopeMode, validReference, effectiveFrom,
                expiresAt, replacesCredentialId, CredentialStatus.ACTIVE, validCustodian,
                null, VerificationState.UNVERIFIED, now, now, 0L);
        credentials.insert(credential);
        for (UUID storeId : initialStores) {
            credentials.insertScope(new CredentialStoreScope(
                    idGenerator.newId(), credential.id(), account.id(), storeId,
                    StoreScopeStatus.ACTIVE, null, now, now, 0L));
        }

        Map<String, FieldChange> changes = new HashMap<>(Map.of(
                "code", new FieldChange(null, validCode),
                "marketplaceAccountId", new FieldChange(null, account.id().toString()),
                "purposeCode", new FieldChange(null, purposeCode),
                "scopeMode", new FieldChange(null, scopeMode.name()),
                "secretReference", new FieldChange(null, validReference),
                "effectiveFrom", new FieldChange(null, effectiveFrom.toString()),
                "expiresAt", new FieldChange(null, expiresAt.toString()),
                "status", new FieldChange(null, CredentialStatus.ACTIVE.name())));
        if (replacesCredentialId != null) {
            changes.put("replacesCredentialId",
                    new FieldChange(null, replacesCredentialId.toString()));
        }
        if (!initialStores.isEmpty()) {
            changes.put("storeScope", new FieldChange(null, joinIds(initialStores)));
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                ENTITY_TYPE, credential.id(), validCode, changes, null, null));
        return view(credential);
    }

    /** Update a credential's non-secret descriptive metadata. */
    @Transactional
    public CredentialView update(String operator,
                                 UUID id,
                                 String displayName,
                                 String custodianLabel,
                                 long expectedVersion) {
        CredentialMetadata current = require(id);
        String validDisplayName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validCustodian = MetadataFieldPolicy.requireText("custodianLabel", custodianLabel);

        CredentialMetadata updated = withMutable(current, validDisplayName,
                current.scopeMode(), current.status(), validCustodian, expectedVersion + 1);
        applyVersioned(credentials.update(updated, expectedVersion));
        Map<String, FieldChange> changes = new HashMap<>();
        putIfChanged(changes, "displayName", current.displayName(), validDisplayName);
        putIfChanged(changes, "custodianLabel", current.custodianLabel(), validCustodian);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.UPDATE,
                ENTITY_TYPE, current.id(), current.code(), changes, null, null));
        return view(updated);
    }

    /** Apply a lifecycle transition; revocation releases the secret reference. */
    @Transactional
    public CredentialView changeStatus(String operator,
                                       UUID id,
                                       CredentialStatus targetStatus,
                                       String reason,
                                       long expectedVersion) {
        CredentialMetadata current = require(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (targetStatus == null || !current.status().canTransitionTo(targetStatus)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, current.id(), current.code());
        }

        CredentialMetadata updated = withMutable(current, current.displayName(),
                current.scopeMode(), targetStatus, current.custodianLabel(),
                expectedVersion + 1);
        applyVersioned(credentials.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.STATUS_CHANGE, ENTITY_TYPE, current.id(), current.code(),
                Map.of("status", new FieldChange(
                        current.status().name(), targetStatus.name())),
                validReason, null));
        return view(updated);
    }

    /**
     * Explicitly change a credential's scope mode.
     *
     * <p>Widening to account scope requires every scope row already withdrawn;
     * narrowing to store-set scope requires a non-empty initial store set that
     * takes effect in the same transaction.
     */
    @Transactional
    public CredentialView changeScopeMode(String operator,
                                          UUID id,
                                          CredentialScopeMode targetMode,
                                          List<UUID> storeIds,
                                          String reason,
                                          long expectedVersion) {
        CredentialMetadata current = require(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (targetMode == null || current.status() == CredentialStatus.REVOKED
                || targetMode == current.scopeMode()) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, current.id(), current.code());
        }
        if (targetMode == CredentialScopeMode.ACCOUNT
                && credentials.countActiveScopes(current.id()) > 0) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, current.id(), current.code());
        }
        Set<UUID> initialStores = requireScopeSet(
                targetMode, storeIds, current.marketplaceAccountId());

        Instant now = clock.instant();
        CredentialMetadata updated = withMutable(current, current.displayName(),
                targetMode, current.status(), current.custodianLabel(), expectedVersion + 1);
        applyVersioned(credentials.update(updated, expectedVersion));
        for (UUID storeId : initialStores) {
            credentials.insertScope(new CredentialStoreScope(
                    idGenerator.newId(), current.id(), current.marketplaceAccountId(),
                    storeId, StoreScopeStatus.ACTIVE, null, now, now, 0L));
        }
        Map<String, FieldChange> changes = new HashMap<>(Map.of("scopeMode",
                new FieldChange(current.scopeMode().name(), targetMode.name())));
        if (!initialStores.isEmpty()) {
            changes.put("storeScope", new FieldChange(null, joinIds(initialStores)));
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.UPDATE,
                ENTITY_TYPE, current.id(), current.code(), changes, validReason, null));
        return view(updated);
    }

    /** Add one store to a store-set credential's active scope. */
    @Transactional
    public CredentialStoreScope addStoreScope(String operator,
                                              UUID credentialId,
                                              UUID storeId,
                                              String reason) {
        CredentialMetadata credential = require(credentialId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (credential.status() == CredentialStatus.REVOKED
                || credential.scopeMode() != CredentialScopeMode.STORE_SET) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, credential.id(), credential.code());
        }
        requireOwnActiveStore(storeId, credential.marketplaceAccountId());
        credentials.findActiveScope(credentialId, storeId).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    SCOPE_ENTITY_TYPE, null, existing.id());
        });

        Instant now = clock.instant();
        CredentialStoreScope scope = new CredentialStoreScope(
                idGenerator.newId(), credentialId, credential.marketplaceAccountId(),
                storeId, StoreScopeStatus.ACTIVE, null, now, now, 0L);
        credentials.insertScope(scope);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                SCOPE_ENTITY_TYPE, scope.id(), null,
                Map.of(
                        "credentialId", new FieldChange(null, credentialId.toString()),
                        "storeId", new FieldChange(null, storeId.toString()),
                        "status", new FieldChange(null, StoreScopeStatus.ACTIVE.name())),
                validReason, null));
        return scope;
    }

    /**
     * Withdraw one scope row.
     *
     * <p>Withdrawing the last active row is allowed: the credential becomes
     * unusable rather than account-wide, and the resulting empty set is
     * surfaced as a warning signal.
     */
    @Transactional
    public CredentialStoreScope withdrawStoreScope(String operator,
                                                   UUID credentialId,
                                                   UUID scopeId,
                                                   String reason,
                                                   long expectedVersion) {
        CredentialStoreScope current = credentials.findScopeById(scopeId)
                .filter(scope -> scope.credentialId().equals(credentialId))
                .orElseThrow(() -> OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        SCOPE_ENTITY_TYPE, scopeId, null));
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!current.status().canTransitionTo(StoreScopeStatus.WITHDRAWN)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    SCOPE_ENTITY_TYPE, current.id(), null);
        }

        CredentialStoreScope withdrawn = new CredentialStoreScope(
                current.id(), current.credentialId(), current.marketplaceAccountId(),
                current.storeId(), StoreScopeStatus.WITHDRAWN, validReason,
                current.createdAt(), clock.instant(), expectedVersion + 1);
        applyVersioned(credentials.updateScope(withdrawn, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator,
                AuditAction.STATUS_CHANGE, SCOPE_ENTITY_TYPE, current.id(), null,
                Map.of("status", new FieldChange(
                        current.status().name(), StoreScopeStatus.WITHDRAWN.name())),
                validReason, null));
        if (credentials.countActiveScopes(credentialId) == 0) {
            log.atWarn()
                    .addKeyValue("event", "credential_scope_unusable")
                    .addKeyValue("credentialId", credentialId.toString())
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("Store-set credential has no active scope rows");
        }
        return withdrawn;
    }

    /** Load one credential with its derived state and scope rows. */
    public CredentialView view(UUID id) {
        return view(require(id));
    }

    /** List an account's credentials with derived state. */
    public List<CredentialView> listByAccount(
            UUID marketplaceAccountId, String afterCode, int limit) {
        return credentials.listByAccount(
                        marketplaceAccountId, afterCode, Math.clamp(limit, 1, 200))
                .stream()
                .map(this::view)
                .toList();
    }

    /** Count non-revoked credentials of one marketplace account. */
    public long countNotRevokedByAccount(UUID marketplaceAccountId) {
        return credentials.countNotRevokedByAccount(marketplaceAccountId);
    }

    /** Count non-revoked credentials of one organization. */
    public long countNotRevokedByOrganization(UUID organizationId) {
        return credentials.countNotRevokedByOrganization(organizationId);
    }

    /** Count active scope rows covering one store. */
    public long countActiveScopesByStore(UUID storeId) {
        return credentials.countActiveScopesByStore(storeId);
    }

    private CredentialView view(CredentialMetadata credential) {
        List<CredentialStoreScope> scopes = credentials.listScopes(credential.id());
        boolean expired = !clock.instant().isBefore(credential.expiresAt());
        CredentialScopeUsability usability;
        if (credential.scopeMode() == CredentialScopeMode.ACCOUNT) {
            usability = CredentialScopeUsability.ACCOUNT_WIDE;
        } else {
            boolean hasActiveScope = scopes.stream()
                    .anyMatch(scope -> scope.status() == StoreScopeStatus.ACTIVE);
            usability = hasActiveScope
                    ? CredentialScopeUsability.STORE_SET
                    : CredentialScopeUsability.NO_ACTIVE_STORE_SCOPE;
        }
        RotationStanding rotationStatus = credentials.countLiveReplacers(credential.id()) > 0
                ? RotationStanding.BEING_REPLACED
                : RotationStanding.STABLE;
        return new CredentialView(credential, expired, usability, rotationStatus, scopes);
    }

    private CredentialMetadata require(UUID id) {
        return credentials.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    private MarketplaceAccountRef requireActiveAccount(UUID marketplaceAccountId) {
        MarketplaceAccountRef account = organizationDirectory
                .marketplaceAccount(
                        Objects.requireNonNullElse(marketplaceAccountId, new UUID(0, 0)))
                .orElseThrow(() -> OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        ENTITY_TYPE, null, null));
        if (!"ACTIVE".equals(account.status())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        return account;
    }

    private void requireWindow(Instant effectiveFrom, Instant expiresAt) {
        if (effectiveFrom == null || expiresAt == null
                || !effectiveFrom.isBefore(expiresAt)
                || !expiresAt.isAfter(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private void requireRotationTarget(UUID replacesCredentialId,
                                       UUID marketplaceAccountId,
                                       String purposeCode) {
        if (replacesCredentialId == null) {
            return;
        }
        CredentialMetadata predecessor = credentials.findById(replacesCredentialId)
                .orElseThrow(() -> OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        ENTITY_TYPE, replacesCredentialId, null));
        if (!predecessor.marketplaceAccountId().equals(marketplaceAccountId)
                || !predecessor.purposeCode().equals(purposeCode)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (predecessor.status() == CredentialStatus.REVOKED) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, predecessor.id(), predecessor.code());
        }
    }

    private Set<UUID> requireScopeSet(CredentialScopeMode scopeMode,
                                      List<UUID> storeIds,
                                      UUID marketplaceAccountId) {
        boolean supplied = storeIds != null && !storeIds.isEmpty();
        if (scopeMode == CredentialScopeMode.ACCOUNT) {
            if (supplied) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            return Set.of();
        }
        if (!supplied) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Set<UUID> unique = new LinkedHashSet<>(storeIds);
        for (UUID storeId : unique) {
            requireOwnActiveStore(storeId, marketplaceAccountId);
        }
        return unique;
    }

    private void requireOwnActiveStore(UUID storeId, UUID marketplaceAccountId) {
        StoreRef store = organizationDirectory
                .store(Objects.requireNonNullElse(storeId, new UUID(0, 0)))
                .orElseThrow(() -> OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        SCOPE_ENTITY_TYPE, storeId, null));
        if (!store.marketplaceAccountId().equals(marketplaceAccountId)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.CROSS_ORGANIZATION_REJECTED,
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    SCOPE_ENTITY_TYPE, null, null);
        }
        if (!"ACTIVE".equals(store.status())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private CredentialMetadata withMutable(CredentialMetadata current,
                                           String displayName,
                                           CredentialScopeMode scopeMode,
                                           CredentialStatus status,
                                           String custodianLabel,
                                           long newVersion) {
        return new CredentialMetadata(
                current.id(), current.organizationId(), current.marketplaceAccountId(),
                current.code(), displayName, current.purposeCode(), scopeMode,
                current.secretReference(), current.effectiveFrom(), current.expiresAt(),
                current.replacesCredentialId(), status, custodianLabel,
                current.lastUsedAt(), current.verificationState(), current.createdAt(),
                clock.instant(), newVersion);
    }

    private static void putIfChanged(Map<String, FieldChange> changes,
                                     String field, String oldValue, String newValue) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.put(field, new FieldChange(oldValue, newValue));
        }
    }

    private static String joinIds(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    static void applyVersioned(boolean updated) {
        if (!updated) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
    }
}
