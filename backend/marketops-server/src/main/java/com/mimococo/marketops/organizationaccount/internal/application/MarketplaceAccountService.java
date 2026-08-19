package com.mimococo.marketops.organizationaccount.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.organizationaccount.CoreEntityReferenceCheck;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.LegalEntity;
import com.mimococo.marketops.organizationaccount.internal.domain.MarketplaceAccount;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.CoreReferenceRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.MarketplaceAccountRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import com.mimococo.marketops.shared.SecretMaterialGuard;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance operations on marketplace accounts.
 *
 * <p>An account is created under an active legal entity on an active platform.
 * The platform-native key is stored opaquely; registering a native key that a
 * live account already carries is refused, and changing a recorded native key
 * requires a reason because it rebinds the account's platform identity.
 */
@Service
public class MarketplaceAccountService {

    static final String ENTITY_TYPE = "marketplace-account";

    private final MarketplaceAccountRepository accounts;
    private final StoreRepository stores;
    private final CoreReferenceRepository references;
    private final LegalEntityService legalEntityService;
    private final List<CoreEntityReferenceCheck> referenceChecks;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    MarketplaceAccountService(MarketplaceAccountRepository accounts,
                              StoreRepository stores,
                              CoreReferenceRepository references,
                              LegalEntityService legalEntityService,
                              List<CoreEntityReferenceCheck> referenceChecks,
                              MetadataAuditRecorder auditRecorder,
                              IdGenerator idGenerator,
                              Clock clock) {
        this.accounts = accounts;
        this.stores = stores;
        this.references = references;
        this.legalEntityService = legalEntityService;
        this.referenceChecks = referenceChecks;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Create a marketplace account. */
    @Transactional
    public MarketplaceAccount create(String operator,
                                     UUID legalEntityId,
                                     String platformCode,
                                     String code,
                                     String displayName,
                                     String nativeAccountKey) {
        LegalEntity legalEntity = legalEntityService.require(legalEntityId);
        LegalEntityService.requireActiveParent(legalEntity.status());
        requireActivePlatform(platformCode);
        String validCode = MetadataFieldPolicy.requireCode(code);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validNativeKey = MetadataFieldPolicy.optionalText("nativeAccountKey", nativeAccountKey);
        accounts.findByCode(legalEntity.organizationId(), validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, validCode, existing.id());
        });
        requireNativeKeyFree(platformCode, validNativeKey, null);

        Instant now = clock.instant();
        MarketplaceAccount account = new MarketplaceAccount(
                idGenerator.newId(), legalEntity.organizationId(), legalEntityId,
                platformCode, validCode, validName, validNativeKey,
                EntityStatus.ACTIVE, now, now, 0L);
        accounts.insert(account);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.CREATE,
                ENTITY_TYPE, account.id(), account.code(),
                new MetadataChanges()
                        .set("organizationId", account.organizationId())
                        .set("legalEntityId", legalEntityId)
                        .set("platformCode", platformCode)
                        .set("code", validCode)
                        .set("displayName", validName)
                        .set("nativeAccountKey", validNativeKey)
                        .set("status", EntityStatus.ACTIVE)
                        .asMap(),
                null, null));
        return account;
    }

    /** Update an account's mutable attributes; a native-key change needs a reason. */
    @Transactional
    public MarketplaceAccount update(String operator,
                                     UUID id,
                                     String displayName,
                                     String nativeAccountKey,
                                     String reason,
                                     long expectedVersion) {
        MarketplaceAccount current = require(id);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validNativeKey = MetadataFieldPolicy.optionalText("nativeAccountKey", nativeAccountKey);
        String validReason = MetadataFieldPolicy.optionalText("reason", reason);
        boolean nativeKeyChanged = !Objects.equals(current.nativeAccountKey(), validNativeKey);
        if (nativeKeyChanged) {
            if (validReason == null) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            requireNativeKeyFree(current.platformCode(), validNativeKey, current.id());
        }

        MarketplaceAccount updated = new MarketplaceAccount(
                current.id(), current.organizationId(), current.legalEntityId(),
                current.platformCode(), current.code(), validName, validNativeKey,
                current.status(), current.createdAt(), clock.instant(), expectedVersion + 1);
        OrganizationService.applyVersioned(accounts.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.UPDATE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges()
                        .compare("displayName", current.displayName(), validName)
                        .compare("nativeAccountKey", current.nativeAccountKey(), validNativeKey)
                        .asMap(),
                validReason, null));
        return updated;
    }

    /** Move an account between lifecycle states. */
    @Transactional
    public MarketplaceAccount changeStatus(String operator,
                                           UUID id,
                                           EntityStatus target,
                                           String reason,
                                           long expectedVersion) {
        MarketplaceAccount current = require(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!current.status().canTransitionTo(target)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, current.id(), current.code());
        }
        if (target == EntityStatus.RETIRED) {
            requireNoLiveReferences(current);
        }

        MarketplaceAccount updated = new MarketplaceAccount(
                current.id(), current.organizationId(), current.legalEntityId(),
                current.platformCode(), current.code(), current.displayName(),
                current.nativeAccountKey(), target,
                current.createdAt(), clock.instant(), expectedVersion + 1);
        OrganizationService.applyVersioned(accounts.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges().compare("status", current.status(), target).asMap(),
                validReason, null));
        return updated;
    }

    /** Load one account. */
    public MarketplaceAccount require(UUID id) {
        return accounts.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    /** List an organization's accounts by code with a keyset cursor. */
    public List<MarketplaceAccount> list(UUID organizationId, String afterCode, int limit) {
        return accounts.list(organizationId, afterCode, Math.clamp(limit, 1, 200));
    }

    private void requireActivePlatform(String platformCode) {
        String[] platform = references.platform(platformCode).orElseThrow(() ->
                OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"ACTIVE".equals(platform[2])) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private void requireNativeKeyFree(String platformCode, String nativeKey, UUID selfId) {
        if (nativeKey == null) {
            return;
        }
        SecretMaterialGuard.requireNonSecret("nativeAccountKey", nativeKey);
        accounts.findLiveByNativeKey(platformCode, nativeKey).ifPresent(existing -> {
            if (selfId == null || !existing.id().equals(selfId)) {
                throw OperationRejectedException.duplicate(
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        ENTITY_TYPE, existing.code(), existing.id());
            }
        });
    }

    private void requireNoLiveReferences(MarketplaceAccount account) {
        boolean referenced = stores.countNotRetiredByAccount(account.id()) > 0
                || referenceChecks.stream().anyMatch(check ->
                        check.hasActiveReferences(CoreEntityType.MARKETPLACE_ACCOUNT, account.id()));
        if (referenced) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.REFERENCED_ENTITY_ACTIVE,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, account.id(), account.code());
        }
    }
}
