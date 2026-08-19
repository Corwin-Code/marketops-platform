package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AccessMetadataDirectory;
import com.mimococo.marketops.identityaccess.ServiceAccountEvaluation;
import com.mimococo.marketops.identityaccess.internal.domain.AllowedSource;
import com.mimococo.marketops.identityaccess.internal.domain.AllowedSourceStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrant;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrantStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccount;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccountStatus;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.ScopeGrantRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.ServiceAccountRepository;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.OrganizationRef;
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
 * Maintenance operations and fail-closed evaluation for service accounts.
 *
 * <p>Every account carries a mandatory expiry and a single purpose. Revocation
 * is terminal and revokes the account's active grants in the same transaction,
 * so no grant can outlive its subject.
 */
@Service
public class ServiceAccountService implements AccessMetadataDirectory {

    static final String ENTITY_TYPE = "service-account";
    static final String SOURCE_ENTITY_TYPE = "service-account-allowed-source";

    private final ServiceAccountRepository serviceAccounts;
    private final ScopeGrantRepository grants;
    private final OrganizationDirectory organizationDirectory;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;

    ServiceAccountService(ServiceAccountRepository serviceAccounts,
                          ScopeGrantRepository grants,
                          OrganizationDirectory organizationDirectory,
                          MetadataAuditRecorder auditRecorder,
                          IdGenerator idGenerator,
                          Clock clock) {
        this.serviceAccounts = serviceAccounts;
        this.grants = grants;
        this.organizationDirectory = organizationDirectory;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Create a service account under an active organization. */
    @Transactional
    public ServiceAccount create(String operator,
                                 UUID organizationId,
                                 String code,
                                 String displayName,
                                 String purpose,
                                 String ownerLabel,
                                 Instant expiresAt) {
        OrganizationRef organization = organizationDirectory.organization(organizationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"ACTIVE".equals(organization.status())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        String validCode = MetadataFieldPolicy.requireCode(code);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validPurpose = MetadataFieldPolicy.requireText("purpose", purpose);
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", ownerLabel);
        Instant now = clock.instant();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        serviceAccounts.findByCode(organizationId, validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    ENTITY_TYPE, validCode, existing.id());
        });

        ServiceAccount account = new ServiceAccount(
                idGenerator.newId(), organizationId, validCode, validName, validPurpose,
                validOwner, expiresAt, ServiceAccountStatus.ACTIVE, null, null, now, now, 0L);
        serviceAccounts.insert(account);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.CREATE,
                ENTITY_TYPE, account.id(), account.code(),
                Map.of(
                        "organizationId", new FieldChange(null, organizationId.toString()),
                        "code", new FieldChange(null, validCode),
                        "displayName", new FieldChange(null, validName),
                        "purpose", new FieldChange(null, validPurpose),
                        "ownerLabel", new FieldChange(null, validOwner),
                        "expiresAt", new FieldChange(null, expiresAt.toString()),
                        "status", new FieldChange(null, ServiceAccountStatus.ACTIVE.name())),
                null, null));
        return account;
    }

    /** Update a service account's mutable attributes, including its expiry. */
    @Transactional
    public ServiceAccount update(String operator,
                                 UUID id,
                                 String displayName,
                                 String purpose,
                                 String ownerLabel,
                                 Instant expiresAt,
                                 long expectedVersion) {
        ServiceAccount current = require(id);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        String validPurpose = MetadataFieldPolicy.requireText("purpose", purpose);
        String validOwner = MetadataFieldPolicy.requireText("ownerLabel", ownerLabel);
        if (expiresAt == null) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }

        ServiceAccount updated = new ServiceAccount(
                current.id(), current.organizationId(), current.code(), validName,
                validPurpose, validOwner, expiresAt, current.status(),
                current.disabledReason(), current.lastUsedAt(),
                current.createdAt(), clock.instant(), expectedVersion + 1);
        applyVersioned(serviceAccounts.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.UPDATE,
                ENTITY_TYPE, current.id(), current.code(),
                compare(current, validName, validPurpose, validOwner, expiresAt),
                null, null));
        return updated;
    }

    /**
     * Move a service account between lifecycle states.
     *
     * <p>Revocation also revokes the account's active grants in this
     * transaction, each with its own journal entry.
     */
    @Transactional
    public ServiceAccount changeStatus(String operator,
                                       UUID id,
                                       ServiceAccountStatus target,
                                       String reason,
                                       long expectedVersion) {
        ServiceAccount current = require(id);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!current.status().canTransitionTo(target)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    ENTITY_TYPE, current.id(), current.code());
        }
        Instant now = clock.instant();
        String disabledReason = target == ServiceAccountStatus.ACTIVE ? null : validReason;

        ServiceAccount updated = new ServiceAccount(
                current.id(), current.organizationId(), current.code(), current.displayName(),
                current.purpose(), current.ownerLabel(), current.expiresAt(), target,
                disabledReason, current.lastUsedAt(), current.createdAt(), now,
                expectedVersion + 1);
        applyVersioned(serviceAccounts.update(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, current.id(), current.code(),
                Map.of("status", new FieldChange(current.status().name(), target.name())),
                validReason, null));

        if (target == ServiceAccountStatus.REVOKED) {
            revokeActiveGrants(operator, current, validReason, now);
        }
        return updated;
    }

    /** Declare an allowed network source for an active service account. */
    @Transactional
    public AllowedSource declareSource(String operator, UUID serviceAccountId,
                                       String cidr, String note) {
        ServiceAccount account = require(serviceAccountId);
        if (account.status() != ServiceAccountStatus.ACTIVE) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.SERVICE_ACCOUNT_INACTIVE,
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    ENTITY_TYPE, account.id(), account.code());
        }
        String validCidr = MetadataFieldPolicy.requireCidr(cidr);
        String validNote = MetadataFieldPolicy.optionalText("note", note);
        serviceAccounts.findActiveSource(serviceAccountId, validCidr).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    SOURCE_ENTITY_TYPE, validCidr, existing.id());
        });

        Instant now = clock.instant();
        AllowedSource source = new AllowedSource(
                idGenerator.newId(), serviceAccountId, validCidr, validNote,
                AllowedSourceStatus.ACTIVE, null, now, now, 0L);
        serviceAccounts.insertSource(source);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.CREATE,
                SOURCE_ENTITY_TYPE, source.id(), null,
                Map.of(
                        "serviceAccountId", new FieldChange(null, serviceAccountId.toString()),
                        "cidr", new FieldChange(null, validCidr),
                        "status", new FieldChange(null, AllowedSourceStatus.ACTIVE.name())),
                null, null));
        return source;
    }

    /** Withdraw an allowed-source declaration; re-declaring creates a new row. */
    @Transactional
    public AllowedSource changeSourceStatus(String operator,
                                            UUID serviceAccountId,
                                            UUID sourceId,
                                            AllowedSourceStatus target,
                                            String reason,
                                            long expectedVersion) {
        AllowedSource current = serviceAccounts.findSourceById(sourceId).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                        SOURCE_ENTITY_TYPE, sourceId, null));
        if (!current.serviceAccountId().equals(serviceAccountId)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    SOURCE_ENTITY_TYPE, sourceId, null);
        }
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!current.status().canTransitionTo(target)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    SOURCE_ENTITY_TYPE, current.id(), null);
        }

        AllowedSource updated = new AllowedSource(
                current.id(), current.serviceAccountId(), current.cidr(), current.note(),
                target, validReason, current.createdAt(), clock.instant(), expectedVersion + 1);
        applyVersioned(serviceAccounts.updateSource(updated, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.STATUS_CHANGE,
                SOURCE_ENTITY_TYPE, current.id(), null,
                Map.of("status", new FieldChange(current.status().name(), target.name())),
                validReason, null));
        return updated;
    }

    /** Load one service account. */
    public ServiceAccount require(UUID id) {
        return serviceAccounts.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                        ENTITY_TYPE, id, null));
    }

    /** List an organization's service accounts. */
    public List<ServiceAccount> list(UUID organizationId, String afterCode, int limit) {
        return serviceAccounts.list(organizationId, afterCode, Math.clamp(limit, 1, 200));
    }

    /** List a service account's allowed-source declarations. */
    public List<AllowedSource> listSources(UUID serviceAccountId) {
        return serviceAccounts.listSources(serviceAccountId);
    }

    @Override
    public ServiceAccountEvaluation evaluate(UUID serviceAccountId) {
        return serviceAccounts.findById(serviceAccountId)
                .map(this::evaluate)
                .orElse(ServiceAccountEvaluation.UNKNOWN);
    }

    /** Evaluate a loaded account against the clock. */
    public ServiceAccountEvaluation evaluate(ServiceAccount account) {
        return switch (account.status()) {
            case REVOKED -> ServiceAccountEvaluation.REVOKED;
            case DISABLED -> ServiceAccountEvaluation.DISABLED;
            case ACTIVE -> account.expiresAt().isAfter(clock.instant())
                    ? ServiceAccountEvaluation.ACTIVE
                    : ServiceAccountEvaluation.EXPIRED;
        };
    }

    private void revokeActiveGrants(String operator, ServiceAccount account,
                                    String reason, Instant now) {
        for (ScopeGrant grant : grants.listActiveBySubject(account.id())) {
            ScopeGrant revoked = new ScopeGrant(
                    grant.id(), grant.organizationId(), grant.serviceAccountId(),
                    grant.permissionCode(), grant.resourceType(), grant.resourceId(),
                    grant.effectiveFrom(), grant.effectiveTo(), ScopeGrantStatus.REVOKED,
                    reason, grant.createdAt(), now, grant.version() + 1);
            applyVersioned(grants.update(revoked, grant.version()));
            auditRecorder.recordChange(new MetadataAuditChange(
                    AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.REVOKE,
                    ScopeGrantService.ENTITY_TYPE, grant.id(), null,
                    Map.of("status", new FieldChange(
                            ScopeGrantStatus.ACTIVE.name(), ScopeGrantStatus.REVOKED.name())),
                    reason, null));
        }
    }

    private Map<String, FieldChange> compare(ServiceAccount current, String displayName,
                                             String purpose, String ownerLabel,
                                             Instant expiresAt) {
        var changes = new java.util.LinkedHashMap<String, FieldChange>();
        if (!current.displayName().equals(displayName)) {
            changes.put("displayName", new FieldChange(current.displayName(), displayName));
        }
        if (!current.purpose().equals(purpose)) {
            changes.put("purpose", new FieldChange(current.purpose(), purpose));
        }
        if (!current.ownerLabel().equals(ownerLabel)) {
            changes.put("ownerLabel", new FieldChange(current.ownerLabel(), ownerLabel));
        }
        if (!current.expiresAt().equals(expiresAt)) {
            changes.put("expiresAt",
                    new FieldChange(current.expiresAt().toString(), expiresAt.toString()));
        }
        return Map.copyOf(changes);
    }

    static void applyVersioned(boolean updated) {
        if (!updated) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
    }
}
