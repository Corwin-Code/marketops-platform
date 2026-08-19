package com.mimococo.marketops.organizationaccount.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.organizationaccount.CoreEntityReferenceCheck;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.LegalEntity;
import com.mimococo.marketops.organizationaccount.internal.domain.Warehouse;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreWarehouseLinkRepository;
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
 * Maintenance operations on warehouses.
 *
 * <p>A warehouse belongs to an active legal entity and serves stores only
 * through explicit associations. Retirement is refused while an active
 * association or a live reference in another module still points at it.
 */
@Service
public class WarehouseService {

    static final String ENTITY_TYPE = "warehouse";

    private final WarehouseRepository warehouses;
    private final StoreWarehouseLinkRepository links;
    private final LegalEntityService legalEntityService;
    private final List<CoreEntityReferenceCheck> referenceChecks;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    WarehouseService(WarehouseRepository warehouses,
                     StoreWarehouseLinkRepository links,
                     LegalEntityService legalEntityService,
                     List<CoreEntityReferenceCheck> referenceChecks,
                     MetadataAuditRecorder auditRecorder,
                     IdGenerator idGenerator,
                     Clock clock) {
        this.warehouses = warehouses;
        this.links = links;
        this.legalEntityService = legalEntityService;
        this.referenceChecks = referenceChecks;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Create a warehouse under an active legal entity. */
    @Transactional
    public Warehouse create(String operator,
                            UUID legalEntityId,
                            String code,
                            String displayName,
                            String timezone) {
        LegalEntity legalEntity = legalEntityService.require(legalEntityId);
        LegalEntityService.requireActiveParent(legalEntity.status());
        String validCode = MetadataFieldPolicy.requireCode(code);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validTimezone = MetadataFieldPolicy.optionalTimezone(timezone);
        warehouses.findByCode(legalEntity.organizationId(), validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, validCode, existing.id());
        });

        Instant now = clock.instant();
        Warehouse warehouse = new Warehouse(
                idGenerator.newId(), legalEntity.organizationId(), legalEntityId,
                validCode, validName, validTimezone, EntityStatus.ACTIVE, now, now, 0L);
        warehouses.insert(warehouse);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.CREATE,
                ENTITY_TYPE, warehouse.id(), warehouse.code(),
                new MetadataChanges()
                        .set("organizationId", warehouse.organizationId())
                        .set("legalEntityId", legalEntityId)
                        .set("code", validCode)
                        .set("displayName", validName)
                        .set("timezone", validTimezone)
                        .set("status", EntityStatus.ACTIVE)
                        .asMap(),
                null, null));
        return warehouse;
    }

    /** Update a warehouse's mutable attributes. */
    @Transactional
    public Warehouse update(String operator,
                            UUID id,
                            String displayName,
                            String timezone,
                            long expectedVersion) {
        Warehouse current = require(id);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validTimezone = MetadataFieldPolicy.optionalTimezone(timezone);

        Warehouse updated = new Warehouse(
                current.id(), current.organizationId(), current.legalEntityId(),
                current.code(), validName, validTimezone, current.status(),
                current.createdAt(), clock.instant(), expectedVersion + 1);
        OrganizationService.applyVersioned(warehouses.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.UPDATE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges()
                        .compare("displayName", current.displayName(), validName)
                        .compare("timezone", current.timezone(), validTimezone)
                        .asMap(),
                null, null));
        return updated;
    }

    /** Move a warehouse between lifecycle states. */
    @Transactional
    public Warehouse changeStatus(String operator,
                                  UUID id,
                                  EntityStatus target,
                                  String reason,
                                  long expectedVersion) {
        Warehouse current = require(id);
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

        Warehouse updated = new Warehouse(
                current.id(), current.organizationId(), current.legalEntityId(),
                current.code(), current.displayName(), current.timezone(), target,
                current.createdAt(), clock.instant(), expectedVersion + 1);
        OrganizationService.applyVersioned(warehouses.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges().compare("status", current.status(), target).asMap(),
                validReason, null));
        return updated;
    }

    /** Load one warehouse. */
    public Warehouse require(UUID id) {
        return warehouses.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    /** List an organization's warehouses by code with a keyset cursor. */
    public List<Warehouse> list(UUID organizationId, String afterCode, int limit) {
        return warehouses.list(organizationId, afterCode, Math.clamp(limit, 1, 200));
    }

    private void requireNoLiveReferences(Warehouse warehouse) {
        boolean referenced = links.countActiveByWarehouse(warehouse.id()) > 0
                || referenceChecks.stream().anyMatch(check ->
                        check.hasActiveReferences(CoreEntityType.WAREHOUSE, warehouse.id()));
        if (referenced) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.REFERENCED_ENTITY_ACTIVE,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, warehouse.id(), warehouse.code());
        }
    }
}
