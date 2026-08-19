package com.mimococo.marketops.identityaccess.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ServiceAccountEvaluation;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrant;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrantStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeResourceType;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccount;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.PermissionKindRepository;
import com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc.ScopeGrantRepository;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.OperationRejectedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance operations on scoped permission grants.
 *
 * <p>Granting is explicit and never amplifying: the subject must evaluate
 * active, the permission must exist, the resource must be an active entity of
 * the subject's own organization, and the same scope cannot be granted twice
 * while active. Nothing here derives one grant from another.
 */
@Service
public class ScopeGrantService {

    static final String ENTITY_TYPE = "scope-grant";

    private static final Logger log = LoggerFactory.getLogger(ScopeGrantService.class);

    private final ScopeGrantRepository grants;
    private final PermissionKindRepository permissions;
    private final ServiceAccountService serviceAccountService;
    private final OrganizationDirectory organizationDirectory;
    private final MetadataAuditRecorder auditRecorder;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    ScopeGrantService(ScopeGrantRepository grants,
                      PermissionKindRepository permissions,
                      ServiceAccountService serviceAccountService,
                      OrganizationDirectory organizationDirectory,
                      MetadataAuditRecorder auditRecorder,
                      IdGenerator idGenerator,
                      Clock clock,
                      MeterRegistry meterRegistry) {
        this.grants = grants;
        this.permissions = permissions;
        this.serviceAccountService = serviceAccountService;
        this.organizationDirectory = organizationDirectory;
        this.auditRecorder = auditRecorder;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    /** Grant one permission kind on one resource to a service account. */
    @Transactional
    public ScopeGrant grant(String operator,
                            UUID serviceAccountId,
                            String permissionCode,
                            ScopeResourceType resourceType,
                            UUID resourceId,
                            Instant effectiveFrom,
                            Instant effectiveTo,
                            String reason) {
        ServiceAccount subject = serviceAccountService.require(serviceAccountId);
        ServiceAccountEvaluation evaluation = serviceAccountService.evaluate(subject);
        if (evaluation != ServiceAccountEvaluation.ACTIVE) {
            log.atWarn()
                    .addKeyValue("event", "service_account_rejected")
                    .addKeyValue("serviceAccountId", subject.id().toString())
                    .addKeyValue("evaluation", evaluation.name())
                    .addKeyValue("correlationId", CorrelationId.current())
                    .log("Grant refused for an inactive service account");
            meterRegistry.counter("marketops.serviceaccount.rejections",
                    "state", evaluation.name()).increment();
            throw OperationRejectedException.forEntity(
                    ErrorCode.SERVICE_ACCOUNT_INACTIVE,
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    ServiceAccountService.ENTITY_TYPE, subject.id(), subject.code());
        }
        if (permissionCode == null || !permissions.permissionExists(permissionCode)) {
            throw OperationRejectedException.of(ErrorCode.UNKNOWN_SCOPE);
        }
        if (resourceType == null || resourceId == null) {
            throw OperationRejectedException.of(ErrorCode.UNKNOWN_SCOPE);
        }
        ResolvedResource resource = resolve(resourceType, resourceId);
        if (!resource.organizationId().equals(subject.organizationId())) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.CROSS_ORGANIZATION_REJECTED,
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    ENTITY_TYPE, null, null);
        }
        if (!"ACTIVE".equals(resource.status())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        requireInterval(effectiveFrom, effectiveTo);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        grants.findActiveGrant(serviceAccountId, permissionCode, resourceType, resourceId)
                .ifPresent(existing -> {
                    throw OperationRejectedException.duplicate(
                            AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                            ENTITY_TYPE, permissionCode, existing.id());
                });

        Instant now = clock.instant();
        ScopeGrant grant = new ScopeGrant(
                idGenerator.newId(), subject.organizationId(), serviceAccountId,
                permissionCode, resourceType, resourceId, effectiveFrom, effectiveTo,
                ScopeGrantStatus.ACTIVE, validReason, now, now, 0L);
        grants.insert(grant);
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.GRANT,
                ENTITY_TYPE, grant.id(), null,
                Map.of(
                        "serviceAccountId", new FieldChange(null, serviceAccountId.toString()),
                        "permissionCode", new FieldChange(null, permissionCode),
                        "resourceType", new FieldChange(null, resourceType.name()),
                        "resourceId", new FieldChange(null, resourceId.toString()),
                        "effectiveFrom", new FieldChange(null, effectiveFrom.toString()),
                        "effectiveTo", new FieldChange(null,
                                effectiveTo == null ? null : effectiveTo.toString()),
                        "status", new FieldChange(null, ScopeGrantStatus.ACTIVE.name())),
                validReason, null));
        return grant;
    }

    /** Revoke a grant; a new authorization is a new grant. */
    @Transactional
    public ScopeGrant revoke(String operator, UUID id, String reason, long expectedVersion) {
        ScopeGrant current = grants.findById(id).orElseThrow(() ->
                OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                        ENTITY_TYPE, id, null));
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (!current.status().canTransitionTo(ScopeGrantStatus.REVOKED)) {
            throw OperationRejectedException.forEntity(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    AuditSourceDomain.IDENTITY_ACCESS.dbValue(),
                    ENTITY_TYPE, current.id(), null);
        }

        ScopeGrant revoked = new ScopeGrant(
                current.id(), current.organizationId(), current.serviceAccountId(),
                current.permissionCode(), current.resourceType(), current.resourceId(),
                current.effectiveFrom(), current.effectiveTo(), ScopeGrantStatus.REVOKED,
                validReason, current.createdAt(), clock.instant(), expectedVersion + 1);
        ServiceAccountService.applyVersioned(grants.update(revoked, expectedVersion));
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.IDENTITY_ACCESS, operator, AuditAction.REVOKE,
                ENTITY_TYPE, current.id(), null,
                Map.of("status", new FieldChange(
                        current.status().name(), ScopeGrantStatus.REVOKED.name())),
                validReason, null));
        return revoked;
    }

    /** List a service account's grants. */
    public List<ScopeGrant> listBySubject(UUID serviceAccountId, int limit) {
        return grants.listBySubject(serviceAccountId, Math.clamp(limit, 1, 200));
    }

    /** Count active grants targeting one resource, for retirement vetoes. */
    public long countActiveByResource(ScopeResourceType resourceType, UUID resourceId) {
        return grants.countActiveByResource(resourceType, resourceId);
    }

    private ResolvedResource resolve(ScopeResourceType resourceType, UUID resourceId) {
        Optional<ResolvedResource> resolved = switch (resourceType) {
            case ORGANIZATION -> organizationDirectory.organization(resourceId)
                    .map(ref -> new ResolvedResource(ref.id(), ref.status()));
            case LEGAL_ENTITY -> organizationDirectory.legalEntity(resourceId)
                    .map(ref -> new ResolvedResource(ref.organizationId(), ref.status()));
            case MARKETPLACE_ACCOUNT -> organizationDirectory.marketplaceAccount(resourceId)
                    .map(ref -> new ResolvedResource(ref.organizationId(), ref.status()));
            case STORE -> organizationDirectory.store(resourceId)
                    .map(ref -> new ResolvedResource(ref.organizationId(), ref.status()));
            case WAREHOUSE -> organizationDirectory.warehouse(resourceId)
                    .map(ref -> new ResolvedResource(ref.organizationId(), ref.status()));
        };
        return resolved.orElseThrow(() ->
                OperationRejectedException.of(ErrorCode.UNKNOWN_SCOPE));
    }

    private static void requireInterval(Instant from, Instant to) {
        if (from == null || (to != null && !from.isBefore(to))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private record ResolvedResource(UUID organizationId, String status) {
    }
}
