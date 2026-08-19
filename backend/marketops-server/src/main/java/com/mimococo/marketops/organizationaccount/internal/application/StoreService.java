package com.mimococo.marketops.organizationaccount.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.organizationaccount.CoreEntityReferenceCheck;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.MarketplaceAccount;
import com.mimococo.marketops.organizationaccount.internal.domain.Store;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreFulfillmentDeclarationRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreWarehouseLinkRepository;
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
 * Maintenance operations on stores.
 *
 * <p>A store is created under an active marketplace account. Its timezone and
 * currency are validated operator inputs that remain unknown while absent.
 * Retirement is refused while an active association, declaration, or a live
 * reference in another module still points at the store.
 */
@Service
public class StoreService {

    static final String ENTITY_TYPE = "store";

    private final StoreRepository stores;
    private final StoreWarehouseLinkRepository links;
    private final StoreFulfillmentDeclarationRepository declarations;
    private final MarketplaceAccountService accountService;
    private final List<CoreEntityReferenceCheck> referenceChecks;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    StoreService(StoreRepository stores,
                 StoreWarehouseLinkRepository links,
                 StoreFulfillmentDeclarationRepository declarations,
                 MarketplaceAccountService accountService,
                 List<CoreEntityReferenceCheck> referenceChecks,
                 MetadataAuditRecorder auditRecorder,
                 IdGenerator idGenerator,
                 Clock clock) {
        this.stores = stores;
        this.links = links;
        this.declarations = declarations;
        this.accountService = accountService;
        this.referenceChecks = referenceChecks;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Create a store under an active account. */
    @Transactional
    public Store create(String operator,
                        UUID marketplaceAccountId,
                        String code,
                        String displayName,
                        String nativeStoreKey,
                        String timezone,
                        String currencyCode) {
        MarketplaceAccount account = accountService.require(marketplaceAccountId);
        LegalEntityService.requireActiveParent(account.status());
        String validCode = MetadataFieldPolicy.requireCode(code);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validNativeKey = MetadataFieldPolicy.optionalText("nativeStoreKey", nativeStoreKey);
        String validTimezone = MetadataFieldPolicy.optionalTimezone(timezone);
        String validCurrency = MetadataFieldPolicy.optionalCurrency(currencyCode);
        stores.findByCode(account.organizationId(), validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, validCode, existing.id());
        });
        requireNativeKeyFree(marketplaceAccountId, validNativeKey, null);

        Instant now = clock.instant();
        Store store = new Store(
                idGenerator.newId(), account.organizationId(), marketplaceAccountId,
                validCode, validName, validNativeKey, validTimezone, validCurrency,
                EntityStatus.ACTIVE, now, now, 0L);
        stores.insert(store);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.CREATE,
                ENTITY_TYPE, store.id(), store.code(),
                new MetadataChanges()
                        .set("organizationId", store.organizationId())
                        .set("marketplaceAccountId", marketplaceAccountId)
                        .set("code", validCode)
                        .set("displayName", validName)
                        .set("nativeStoreKey", validNativeKey)
                        .set("timezone", validTimezone)
                        .set("currencyCode", validCurrency)
                        .set("status", EntityStatus.ACTIVE)
                        .asMap(),
                null, null));
        return store;
    }

    /** Update a store's mutable attributes; a native-key change needs a reason. */
    @Transactional
    public Store update(String operator,
                        UUID id,
                        String displayName,
                        String nativeStoreKey,
                        String timezone,
                        String currencyCode,
                        String reason,
                        long expectedVersion) {
        Store current = require(id);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validNativeKey = MetadataFieldPolicy.optionalText("nativeStoreKey", nativeStoreKey);
        String validTimezone = MetadataFieldPolicy.optionalTimezone(timezone);
        String validCurrency = MetadataFieldPolicy.optionalCurrency(currencyCode);
        String validReason = MetadataFieldPolicy.optionalText("reason", reason);
        boolean nativeKeyChanged = !Objects.equals(current.nativeStoreKey(), validNativeKey);
        if (nativeKeyChanged) {
            if (validReason == null) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
            requireNativeKeyFree(current.marketplaceAccountId(), validNativeKey, current.id());
        }

        Store updated = new Store(
                current.id(), current.organizationId(), current.marketplaceAccountId(),
                current.code(), validName, validNativeKey, validTimezone, validCurrency,
                current.status(), current.createdAt(), clock.instant(), expectedVersion + 1);
        OrganizationService.applyVersioned(stores.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.UPDATE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges()
                        .compare("displayName", current.displayName(), validName)
                        .compare("nativeStoreKey", current.nativeStoreKey(), validNativeKey)
                        .compare("timezone", current.timezone(), validTimezone)
                        .compare("currencyCode", current.currencyCode(), validCurrency)
                        .asMap(),
                validReason, null));
        return updated;
    }

    /** Move a store between lifecycle states. */
    @Transactional
    public Store changeStatus(String operator,
                              UUID id,
                              EntityStatus target,
                              String reason,
                              long expectedVersion) {
        Store current = require(id);
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

        Store updated = new Store(
                current.id(), current.organizationId(), current.marketplaceAccountId(),
                current.code(), current.displayName(), current.nativeStoreKey(),
                current.timezone(), current.currencyCode(), target,
                current.createdAt(), clock.instant(), expectedVersion + 1);
        OrganizationService.applyVersioned(stores.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges().compare("status", current.status(), target).asMap(),
                validReason, null));
        return updated;
    }

    /** Load one store. */
    public Store require(UUID id) {
        return stores.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    /** List an organization's stores by code with a keyset cursor. */
    public List<Store> list(UUID organizationId, String afterCode, int limit) {
        return stores.list(organizationId, afterCode, Math.clamp(limit, 1, 200));
    }

    private void requireNativeKeyFree(UUID accountId, String nativeKey, UUID selfId) {
        if (nativeKey == null) {
            return;
        }
        SecretMaterialGuard.requireNonSecret("nativeStoreKey", nativeKey);
        stores.findLiveByNativeKey(accountId, nativeKey).ifPresent(existing -> {
            if (selfId == null || !existing.id().equals(selfId)) {
                throw OperationRejectedException.duplicate(
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        ENTITY_TYPE, existing.code(), existing.id());
            }
        });
    }

    private void requireNoLiveReferences(Store store) {
        boolean referenced = links.countActiveByStore(store.id()) > 0
                || declarations.countActiveByStore(store.id()) > 0
                || referenceChecks.stream().anyMatch(check ->
                        check.hasActiveReferences(CoreEntityType.STORE, store.id()));
        if (referenced) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.REFERENCED_ENTITY_ACTIVE,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, store.id(), store.code());
        }
    }
}
