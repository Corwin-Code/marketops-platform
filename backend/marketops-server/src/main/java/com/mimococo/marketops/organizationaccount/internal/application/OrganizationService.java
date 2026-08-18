package com.mimococo.marketops.organizationaccount.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.organizationaccount.CoreEntityReferenceCheck;
import com.mimococo.marketops.organizationaccount.CoreEntityType;
import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.Organization;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.LegalEntityRepository;
import com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc.OrganizationRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance operations on organizations.
 *
 * <p>Every mutation validates its input, applies the lifecycle rules, persists
 * under optimistic concurrency, and journals a change record in the same
 * transaction — a mutation that cannot be journaled does not happen.
 */
@Service
public class OrganizationService {

    static final String ENTITY_TYPE = "organization";

    private final OrganizationRepository organizations;
    private final LegalEntityRepository legalEntities;
    private final List<CoreEntityReferenceCheck> referenceChecks;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    OrganizationService(OrganizationRepository organizations,
                        LegalEntityRepository legalEntities,
                        List<CoreEntityReferenceCheck> referenceChecks,
                        MetadataAuditRecorder auditRecorder,
                        IdGenerator idGenerator,
                        Clock clock) {
        this.organizations = organizations;
        this.legalEntities = legalEntities;
        this.referenceChecks = referenceChecks;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Create an organization. */
    @Transactional
    public Organization create(String operator,
                               String code,
                               String displayName,
                               String defaultTimezone,
                               String defaultCurrencyCode) {
        String validCode = MetadataFieldPolicy.requireCode(code);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validTimezone = MetadataFieldPolicy.optionalTimezone(defaultTimezone);
        String validCurrency = MetadataFieldPolicy.optionalCurrency(defaultCurrencyCode);
        organizations.findByCode(validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, validCode, existing.id());
        });

        Instant now = clock.instant();
        Organization organization = new Organization(
                idGenerator.newId(), validCode, validName, validTimezone, validCurrency,
                EntityStatus.ACTIVE, now, now, 0L);
        organizations.insert(organization);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.CREATE,
                ENTITY_TYPE, organization.id(), organization.code(),
                new MetadataChanges()
                        .set("code", validCode)
                        .set("displayName", validName)
                        .set("defaultTimezone", validTimezone)
                        .set("defaultCurrencyCode", validCurrency)
                        .set("status", EntityStatus.ACTIVE)
                        .asMap(),
                null, null));
        return organization;
    }

    /** Update an organization's mutable attributes. */
    @Transactional
    public Organization update(String operator,
                               UUID id,
                               String displayName,
                               String defaultTimezone,
                               String defaultCurrencyCode,
                               long expectedVersion) {
        Organization current = require(id);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validTimezone = MetadataFieldPolicy.optionalTimezone(defaultTimezone);
        String validCurrency = MetadataFieldPolicy.optionalCurrency(defaultCurrencyCode);

        Organization updated = new Organization(
                current.id(), current.code(), validName, validTimezone, validCurrency,
                current.status(), current.createdAt(), clock.instant(), expectedVersion + 1);
        applyVersioned(organizations.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.UPDATE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges()
                        .compare("displayName", current.displayName(), validName)
                        .compare("defaultTimezone", current.defaultTimezone(), validTimezone)
                        .compare("defaultCurrencyCode", current.defaultCurrencyCode(), validCurrency)
                        .asMap(),
                null, null));
        return updated;
    }

    /** Move an organization between lifecycle states. */
    @Transactional
    public Organization changeStatus(String operator,
                                     UUID id,
                                     EntityStatus target,
                                     String reason,
                                     long expectedVersion) {
        Organization current = require(id);
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

        Organization updated = new Organization(
                current.id(), current.code(), current.displayName(),
                current.defaultTimezone(), current.defaultCurrencyCode(),
                target, current.createdAt(), clock.instant(), expectedVersion + 1);
        applyVersioned(organizations.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.ORGANIZATION_ACCOUNT, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, current.id(), current.code(),
                new MetadataChanges().compare("status", current.status(), target).asMap(),
                validReason, null));
        return updated;
    }

    /** Load one organization. */
    public Organization require(UUID id) {
        return organizations.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    /** Load one organization if it exists. */
    public Optional<Organization> find(UUID id) {
        return organizations.findById(id);
    }

    /** List organizations by code with a keyset cursor. */
    public List<Organization> list(String afterCode, int limit) {
        return organizations.list(afterCode, Math.clamp(limit, 1, 200));
    }

    private void requireNoLiveReferences(Organization organization) {
        if (legalEntities.countNotRetired(organization.id()) > 0
                || referencedElsewhere(organization.id())) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.REFERENCED_ENTITY_ACTIVE,
                    AuditSourceDomain.ORGANIZATION_ACCOUNT.dbValue(),
                    ENTITY_TYPE, organization.id(), organization.code());
        }
    }

    private boolean referencedElsewhere(UUID id) {
        return referenceChecks.stream().anyMatch(check ->
                check.hasActiveReferences(CoreEntityType.ORGANIZATION, id));
    }

    static void applyVersioned(boolean updated) {
        if (!updated) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
    }
}
