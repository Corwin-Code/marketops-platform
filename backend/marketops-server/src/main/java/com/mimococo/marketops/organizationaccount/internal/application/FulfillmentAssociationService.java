package com.mimococo.marketops.organizationaccount.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.organizationaccount.internal.domain.AssociationStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.Store;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreFulfillmentDeclaration;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreWarehouseLink;
import com.mimococo.marketops.organizationaccount.internal.domain.Warehouse;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.CoreReferenceRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreFulfillmentDeclarationRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.StoreWarehouseLinkRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance operations on store↔warehouse associations and store fulfillment
 * declarations.
 *
 * <p>Both are effective-dated: the service refuses an interval that overlaps an
 * active row of the same scope, and the relational exclusion constraint decides
 * any race the pre-check loses. Ending an open-ended row stamps the end of its
 * validity; cancelling records that the row was created in error. Both keep the
 * row.
 */
@Service
public class FulfillmentAssociationService {

    static final String LINK_ENTITY_TYPE = "store-warehouse-link";
    static final String DECLARATION_ENTITY_TYPE = "store-fulfillment-declaration";

    private final StoreWarehouseLinkRepository links;
    private final StoreFulfillmentDeclarationRepository declarations;
    private final CoreReferenceRepository references;
    private final StoreService storeService;
    private final WarehouseService warehouseService;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    FulfillmentAssociationService(StoreWarehouseLinkRepository links,
                                  StoreFulfillmentDeclarationRepository declarations,
                                  CoreReferenceRepository references,
                                  StoreService storeService,
                                  WarehouseService warehouseService,
                                  MetadataAuditRecorder auditRecorder,
                                  IdGenerator idGenerator,
                                  Clock clock) {
        this.links = links;
        this.declarations = declarations;
        this.references = references;
        this.storeService = storeService;
        this.warehouseService = warehouseService;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Create a store↔warehouse association. */
    @Transactional
    public StoreWarehouseLink createLink(String operator,
                                         UUID storeId,
                                         UUID warehouseId,
                                         String fulfillmentModeCode,
                                         Instant effectiveFrom,
                                         Instant effectiveTo,
                                         String note) {
        Store store = storeService.require(storeId);
        Warehouse warehouse = warehouseService.require(warehouseId);
        requireActive(store.status());
        requireActive(warehouse.status());
        if (!store.organizationId().equals(warehouse.organizationId())) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.CROSS_ORGANIZATION_REJECTED,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    LINK_ENTITY_TYPE, null, null);
        }
        requireMode(fulfillmentModeCode);
        requireInterval(effectiveFrom, effectiveTo);
        String validNote = MetadataFieldPolicy.optionalText("note", note);
        if (links.overlapsActive(storeId, warehouseId, fulfillmentModeCode,
                Timestamp.from(effectiveFrom), toTimestamp(effectiveTo), null)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.EFFECTIVE_RANGE_OVERLAP,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    LINK_ENTITY_TYPE, null, null);
        }

        Instant now = clock.instant();
        StoreWarehouseLink link = new StoreWarehouseLink(
                idGenerator.newId(), store.organizationId(), storeId, warehouseId,
                fulfillmentModeCode, effectiveFrom, effectiveTo,
                AssociationStatus.ACTIVE, validNote, now, now, 0L);
        links.insert(link);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.CREATE,
                LINK_ENTITY_TYPE, link.id(), null,
                new MetadataChanges()
                        .set("storeId", storeId)
                        .set("warehouseId", warehouseId)
                        .set("fulfillmentModeCode", fulfillmentModeCode)
                        .set("effectiveFrom", effectiveFrom)
                        .set("effectiveTo", effectiveTo)
                        .set("status", AssociationStatus.ACTIVE)
                        .asMap(),
                null, null));
        return link;
    }

    /** Adjust an active association's validity interval or note. */
    @Transactional
    public StoreWarehouseLink updateLink(String operator,
                                         UUID id,
                                         Instant effectiveFrom,
                                         Instant effectiveTo,
                                         String note,
                                         long expectedVersion) {
        StoreWarehouseLink current = requireLink(id);
        requireStatus(current.status() == AssociationStatus.ACTIVE, LINK_ENTITY_TYPE, current.id());
        requireInterval(effectiveFrom, effectiveTo);
        String validNote = MetadataFieldPolicy.optionalText("note", note);
        if (links.overlapsActive(current.storeId(), current.warehouseId(),
                current.fulfillmentModeCode(), Timestamp.from(effectiveFrom),
                toTimestamp(effectiveTo), current.id())) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.EFFECTIVE_RANGE_OVERLAP,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    LINK_ENTITY_TYPE, current.id(), null);
        }

        StoreWarehouseLink updated = new StoreWarehouseLink(
                current.id(), current.organizationId(), current.storeId(), current.warehouseId(),
                current.fulfillmentModeCode(), effectiveFrom, effectiveTo,
                current.status(), validNote, current.createdAt(), clock.instant(),
                expectedVersion + 1);
        OrganizationService.applyVersioned(links.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.UPDATE,
                LINK_ENTITY_TYPE, current.id(), null,
                new MetadataChanges()
                        .compare("effectiveFrom", current.effectiveFrom(), effectiveFrom)
                        .compare("effectiveTo", current.effectiveTo(), effectiveTo)
                        .compare("note", current.note(), validNote)
                        .asMap(),
                null, null));
        return updated;
    }

    /** End or cancel an association. */
    @Transactional
    public StoreWarehouseLink changeLinkStatus(String operator,
                                               UUID id,
                                               AssociationStatus target,
                                               String reason,
                                               long expectedVersion) {
        StoreWarehouseLink current = requireLink(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!current.status().canTransitionTo(target)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    LINK_ENTITY_TYPE, current.id(), null);
        }
        Instant now = clock.instant();
        Instant endedTo = current.effectiveTo();
        if (target == AssociationStatus.ENDED && endedTo == null) {
            endedTo = now;
        }

        StoreWarehouseLink updated = new StoreWarehouseLink(
                current.id(), current.organizationId(), current.storeId(), current.warehouseId(),
                current.fulfillmentModeCode(), current.effectiveFrom(), endedTo,
                target, current.note(), current.createdAt(), now, expectedVersion + 1);
        OrganizationService.applyVersioned(links.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.STATUS_CHANGE,
                LINK_ENTITY_TYPE, current.id(), null,
                new MetadataChanges()
                        .compare("status", current.status(), target)
                        .compare("effectiveTo", current.effectiveTo(), endedTo)
                        .asMap(),
                validReason, null));
        return updated;
    }

    /** Create a store fulfillment declaration. */
    @Transactional
    public StoreFulfillmentDeclaration createDeclaration(String operator,
                                                         UUID storeId,
                                                         String fulfillmentModeCode,
                                                         Instant effectiveFrom,
                                                         Instant effectiveTo) {
        Store store = storeService.require(storeId);
        requireActive(store.status());
        requireMode(fulfillmentModeCode);
        requireInterval(effectiveFrom, effectiveTo);
        if (declarations.overlapsActive(storeId, fulfillmentModeCode,
                Timestamp.from(effectiveFrom), toTimestamp(effectiveTo), null)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.EFFECTIVE_RANGE_OVERLAP,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    DECLARATION_ENTITY_TYPE, null, null);
        }

        Instant now = clock.instant();
        StoreFulfillmentDeclaration declaration = new StoreFulfillmentDeclaration(
                idGenerator.newId(), store.organizationId(), storeId, fulfillmentModeCode,
                effectiveFrom, effectiveTo, AssociationStatus.ACTIVE, now, now, 0L);
        declarations.insert(declaration);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.CREATE,
                DECLARATION_ENTITY_TYPE, declaration.id(), null,
                new MetadataChanges()
                        .set("storeId", storeId)
                        .set("fulfillmentModeCode", fulfillmentModeCode)
                        .set("effectiveFrom", effectiveFrom)
                        .set("effectiveTo", effectiveTo)
                        .set("status", AssociationStatus.ACTIVE)
                        .asMap(),
                null, null));
        return declaration;
    }

    /** End or cancel a declaration. */
    @Transactional
    public StoreFulfillmentDeclaration changeDeclarationStatus(String operator,
                                                               UUID id,
                                                               AssociationStatus target,
                                                               String reason,
                                                               long expectedVersion) {
        StoreFulfillmentDeclaration current = declarations.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        DECLARATION_ENTITY_TYPE, id, null));
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!current.status().canTransitionTo(target)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    DECLARATION_ENTITY_TYPE, current.id(), null);
        }
        Instant now = clock.instant();
        Instant endedTo = current.effectiveTo();
        if (target == AssociationStatus.ENDED && endedTo == null) {
            endedTo = now;
        }

        StoreFulfillmentDeclaration updated = new StoreFulfillmentDeclaration(
                current.id(), current.organizationId(), current.storeId(),
                current.fulfillmentModeCode(), current.effectiveFrom(), endedTo,
                target, current.createdAt(), now, expectedVersion + 1);
        OrganizationService.applyVersioned(declarations.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.STATUS_CHANGE,
                DECLARATION_ENTITY_TYPE, current.id(), null,
                new MetadataChanges()
                        .compare("status", current.status(), target)
                        .compare("effectiveTo", current.effectiveTo(), endedTo)
                        .asMap(),
                validReason, null));
        return updated;
    }

    /** List a store's associations. */
    public List<StoreWarehouseLink> listLinks(UUID storeId, int limit) {
        return links.listByStore(storeId, Math.clamp(limit, 1, 200));
    }

    /** List a store's declarations. */
    public List<StoreFulfillmentDeclaration> listDeclarations(UUID storeId, int limit) {
        return declarations.listByStore(storeId, Math.clamp(limit, 1, 200));
    }

    private StoreWarehouseLink requireLink(UUID id) {
        return links.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        LINK_ENTITY_TYPE, id, null));
    }

    private void requireMode(String fulfillmentModeCode) {
        if (fulfillmentModeCode == null || !references.modeExists(fulfillmentModeCode)) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private static void requireActive(EntityStatus status) {
        if (status != EntityStatus.ACTIVE) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
    }

    private static void requireStatus(boolean satisfied, String entityType, UUID id) {
        if (!satisfied) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(), entityType, id, null);
        }
    }

    private static void requireInterval(Instant from, Instant to) {
        if (from == null || (to != null && !from.isBefore(to))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static Timestamp toTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
