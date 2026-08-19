package com.mimococo.marketops.organizationaccount.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.organizationaccount.CoreEntityReferenceCheck;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.LegalEntity;
import com.mimococo.marketops.organizationaccount.internal.domain.Organization;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.LegalEntityRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.MarketplaceAccountRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.WarehouseRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance operations on legal entities.
 *
 * <p>A legal entity is created under an active organization. Retirement is
 * refused while any account or warehouse of the entity is live, and while any
 * other module holds live references.
 */
@Service
public class LegalEntityService {

    static final String ENTITY_TYPE = "legal-entity";

    private final LegalEntityRepository legalEntities;
    private final MarketplaceAccountRepository accounts;
    private final WarehouseRepository warehouses;
    private final OrganizationService organizationService;
    private final List<CoreEntityReferenceCheck> referenceChecks;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    LegalEntityService(LegalEntityRepository legalEntities,
                       MarketplaceAccountRepository accounts,
                       WarehouseRepository warehouses,
                       OrganizationService organizationService,
                       List<CoreEntityReferenceCheck> referenceChecks,
                       MetadataAuditRecorder auditRecorder,
                       IdGenerator idGenerator,
                       Clock clock) {
        this.legalEntities = legalEntities;
        this.accounts = accounts;
        this.warehouses = warehouses;
        this.organizationService = organizationService;
        this.referenceChecks = referenceChecks;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Create a legal entity under an active organization. */
    @Transactional
    public LegalEntity create(String operator,
                              UUID organizationId,
                              String code,
                              String displayName,
                              String registeredName,
                              String countryCode) {
        Organization organization = organizationService.require(organizationId);
        requireActiveParent(organization.status());
        String validCode = MetadataFieldPolicy.requireCode(code);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validRegisteredName = MetadataFieldPolicy.optionalText("registeredName", registeredName);
        String validCountry = MetadataFieldPolicy.optionalCountry(countryCode);
        legalEntities.findByCode(organizationId, validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, validCode, existing.id());
        });

        Instant now = clock.instant();
        LegalEntity legalEntity = new LegalEntity(
                idGenerator.newId(), organizationId, validCode, validName,
                validRegisteredName, validCountry, EntityStatus.ACTIVE, now, now, 0L);
        legalEntities.insert(legalEntity);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.CREATE,
                ENTITY_TYPE, legalEntity.id(), legalEntity.code(),
                new MetadataChanges()
                        .set("organizationId", organizationId)
                        .set("code", validCode)
                        .set("displayName", validName)
                        .set("registeredName", validRegisteredName)
                        .set("countryCode", validCountry)
                        .set("status", EntityStatus.ACTIVE)
                        .asMap(),
                null, null));
        return legalEntity;
    }

    /** Update a legal entity's mutable attributes. */
    @Transactional
    public LegalEntity update(String operator,
                              UUID id,
                              String displayName,
                              String registeredName,
                              String countryCode,
                              long expectedVersion) {
        LegalEntity current = require(id);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validRegisteredName = MetadataFieldPolicy.optionalText("registeredName", registeredName);
        String validCountry = MetadataFieldPolicy.optionalCountry(countryCode);

        LegalEntity updated = new LegalEntity(
                current.id(), current.organizationId(), current.code(), validName,
                validRegisteredName, validCountry, current.status(),
                current.createdAt(), clock.instant(), expectedVersion + 1);
        OrganizationService.applyVersioned(legalEntities.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.UPDATE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges()
                        .compare("displayName", current.displayName(), validName)
                        .compare("registeredName", current.registeredName(), validRegisteredName)
                        .compare("countryCode", current.countryCode(), validCountry)
                        .asMap(),
                null, null));
        return updated;
    }

    /** Move a legal entity between lifecycle states. */
    @Transactional
    public LegalEntity changeStatus(String operator,
                                    UUID id,
                                    EntityStatus target,
                                    String reason,
                                    long expectedVersion) {
        LegalEntity current = require(id);
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

        LegalEntity updated = new LegalEntity(
                current.id(), current.organizationId(), current.code(), current.displayName(),
                current.registeredName(), current.countryCode(), target,
                current.createdAt(), clock.instant(), expectedVersion + 1);
        OrganizationService.applyVersioned(legalEntities.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges().compare("status", current.status(), target).asMap(),
                validReason, null));
        return updated;
    }

    /** Load one legal entity. */
    public LegalEntity require(UUID id) {
        return legalEntities.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    /** List an organization's legal entities by code with a keyset cursor. */
    public List<LegalEntity> list(UUID organizationId, String afterCode, int limit) {
        return legalEntities.list(organizationId, afterCode, Math.clamp(limit, 1, 200));
    }

    static void requireActiveParent(EntityStatus parentStatus) {
        if (parentStatus != EntityStatus.ACTIVE) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private void requireNoLiveReferences(LegalEntity legalEntity) {
        boolean referenced = accounts.countNotRetiredByLegalEntity(legalEntity.id()) > 0
                || warehouses.countNotRetiredByLegalEntity(legalEntity.id()) > 0
                || referenceChecks.stream().anyMatch(check ->
                        check.hasActiveReferences(CoreEntityType.LEGAL_ENTITY, legalEntity.id()));
        if (referenced) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.REFERENCED_ENTITY_ACTIVE,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, legalEntity.id(), legalEntity.code());
        }
    }
}
