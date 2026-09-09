package com.mimococo.marketops.listingconversion.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.AuthenticatedInvocationIssuer;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.BatchView;
import com.mimococo.marketops.listingconversion.ContainmentCauseClass;
import com.mimococo.marketops.listingconversion.ContainmentView;
import com.mimococo.marketops.listingconversion.LateAssociationView;
import com.mimococo.marketops.listingconversion.RecalculationClass;
import com.mimococo.marketops.listingconversion.internal.domain.IsolationScope;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.GovernanceRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingActionRepository;
import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.ListingFactRepository;
import com.mimococo.marketops.operationsworkflow.ListingActionIntake;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batches, containment, result isolation and late association.
 *
 * <p>A stop consumes a one-use invocation proof and is recorded by the database
 * function that also contains every live action in scope; reenablement needs a
 * repair attestation and a business consent from two different people.
 */
@Service
public class GovernanceService {

    private final GovernanceRepository governance;
    private final ListingActionRepository actions;
    private final ListingFactRepository facts;
    private final ListingActionIntake intake;
    private final BusinessAuthorization authorization;
    private final AuthenticatedInvocationIssuer issuer;
    private final JdbcClient jdbc;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    GovernanceService(GovernanceRepository governance, ListingActionRepository actions, ListingFactRepository facts,
                      ListingActionIntake intake, BusinessAuthorization authorization, AuthenticatedInvocationIssuer issuer,
                      JdbcClient jdbc, MetadataAuditRecorder audit, IdGenerator ids, Clock clock) {
        this.governance = governance;
        this.actions = actions;
        this.facts = facts;
        this.intake = intake;
        this.authorization = authorization;
        this.issuer = issuer;
        this.jdbc = jdbc;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ batches

    @Transactional
    public BatchView createBatch(AuthenticatedActor actor, UUID storeId, String code) {
        authorization.require(actor, ActionScopeCode.LISTING_ACTION_PREPARE, ResourceScope.store(storeId));
        UUID id = ids.newId();
        governance.insertBatch(id, actor.organizationId(), storeId, MetadataFieldPolicy.requireCode(code), actor.userId(), clock.instant());
        recordAudit(actor, "lc-batch", id, AuditAction.CREATE, Map.of("code", new FieldChange(null, code)), null);
        return governance.batch(id).orElseThrow();
    }

    @Transactional
    public BatchView addMember(AuthenticatedActor actor, UUID batchId, UUID actionId) {
        BatchView batch = requireBatch(actor, batchId, ActionScopeCode.LISTING_ACTION_PREPARE);
        ListingActionRepository.ActionRow action = actions.action(actionId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        if (!"OPEN".equals(batch.state()) || !action.storeId().equals(batch.storeId())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        governance.insertMember(ids.newId(), actor.organizationId(), batchId, actionId, "ACTIVE", actor.userId(), clock.instant());
        recordAudit(actor, "lc-batch", batchId, AuditAction.UPDATE, Map.of("member", new FieldChange(null, actionId.toString())), null);
        return governance.batch(batchId).orElseThrow();
    }

    @Transactional
    public BatchView removeMember(AuthenticatedActor actor, UUID batchId, UUID actionId, String reason) {
        requireBatch(actor, batchId, ActionScopeCode.LISTING_ACTION_PREPARE);
        governance.insertMember(ids.newId(), actor.organizationId(), batchId, actionId, "REMOVED", actor.userId(), clock.instant());
        recordAudit(actor, "lc-batch", batchId, AuditAction.UPDATE, Map.of("member", new FieldChange(actionId.toString(), null)),
                MetadataFieldPolicy.requireText("reason", reason));
        return governance.batch(batchId).orElseThrow();
    }

    @Transactional
    public BatchView closeBatch(AuthenticatedActor actor, UUID batchId) {
        BatchView batch = requireBatch(actor, batchId, ActionScopeCode.LISTING_ACTION_PREPARE);
        if (!governance.closeBatch(batchId, batch.version(), clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        return governance.batch(batchId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<BatchView> batches(UUID organizationId, List<UUID> storeIds, int limit) {
        return governance.batches(organizationId, storeIds, limit);
    }

    @Transactional(readOnly = true)
    public BatchView batch(AuthenticatedActor actor, UUID batchId) {
        return requireBatch(actor, batchId, ActionScopeCode.LISTING_CONVERSION_VIEW);
    }

    // ------------------------------------------------------------------ containment

    public record StopRequest(String scopeKind, UUID platformListingId, UUID storeId, String platformCode, UUID batchId,
                              ContainmentCauseClass causeClass, String causeOwnerRoleCode, String reason, String evidenceReference) {
    }

    @Transactional
    public ContainmentView stop(AuthenticatedActor actor, StopRequest request) {
        UUID storeId = request.storeId();
        if (storeId == null && request.platformListingId() != null) {
            storeId = facts.listing(request.platformListingId()).map(ListingFactRepository.ListingContext::storeId).orElse(null);
        }
        if (storeId == null && request.batchId() != null) {
            storeId = governance.batch(request.batchId()).map(BatchView::storeId).orElse(null);
        }
        authorization.require(actor, ActionScopeCode.LISTING_CONTAINMENT_STOP,
                storeId == null ? ResourceScope.organization(actor.organizationId()) : ResourceScope.store(storeId));
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        UUID id = ids.newId();
        String proof = proof("LISTING_CONTAINMENT_STOP", id, id);
        governance.recordContainment(id, actor.userId(), actor.organizationId(), proof,
                MetadataFieldPolicy.requireText("scopeKind", request.scopeKind()), request.platformListingId(), request.storeId(),
                request.platformCode(), request.batchId(), request.causeClass(),
                MetadataFieldPolicy.requireText("causeOwnerRoleCode", request.causeOwnerRoleCode()),
                MetadataFieldPolicy.requireText("reason", request.reason()),
                MetadataFieldPolicy.requireText("evidenceReference", request.evidenceReference()));
        recordAudit(actor, "lc-containment", id, AuditAction.STATUS_CHANGE, Map.of("scopeKind", new FieldChange(null, request.scopeKind()),
                "causeClass", new FieldChange(null, request.causeClass().name())), request.reason());
        return governance.containment(id).orElseThrow();
    }

    @Transactional
    public ContainmentView attest(AuthenticatedActor actor, UUID containmentId, String kind, String evidenceReference) {
        ContainmentView containment = governance.containment(containmentId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        boolean repair = "REPAIR_ATTESTATION".equals(kind);
        if (!repair && !"BUSINESS_CONSENT".equals(kind)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        ActionScopeCode scope = repair ? ActionScopeCode.LISTING_CONTAINMENT_ATTEST : ActionScopeCode.LISTING_CONTAINMENT_CONSENT;
        authorization.require(actor, scope, containment.storeId() == null
                ? ResourceScope.organization(actor.organizationId()) : ResourceScope.store(containment.storeId()));
        if (!actor.stepUpSatisfiedAt(clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.STEP_UP_REQUIRED);
        }
        UUID id = ids.newId();
        String proof = proof(repair ? "LISTING_CONTAINMENT_ATTEST" : "LISTING_CONTAINMENT_CONSENT", containmentId, containmentId);
        governance.attest(id, containmentId, actor.userId(), proof, kind, MetadataFieldPolicy.requireText("evidenceReference", evidenceReference));
        recordAudit(actor, "lc-containment", containmentId, AuditAction.VERIFICATION_CHANGE, Map.of("attestation", new FieldChange(null, kind)), null);
        return governance.containment(containmentId).orElseThrow();
    }

    @Transactional
    public ContainmentView reenable(AuthenticatedActor actor, UUID containmentId) {
        ContainmentView containment = governance.containment(containmentId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.require(actor, ActionScopeCode.LISTING_CONTAINMENT_CONSENT, containment.storeId() == null
                ? ResourceScope.organization(actor.organizationId()) : ResourceScope.store(containment.storeId()));
        governance.reenable(containmentId, actor.userId());
        recordAudit(actor, "lc-containment", containmentId, AuditAction.STATUS_CHANGE, Map.of("state", new FieldChange("ACTIVE", "REENABLED")), null);
        return governance.containment(containmentId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ContainmentView> containments(UUID organizationId, boolean activeOnly, int limit) {
        return governance.containments(organizationId, activeOnly, limit);
    }

    @Transactional
    public void recordDependency(AuthenticatedActor actor, UUID fromListing, UUID toListing, String kind, String proofReference) {
        authorization.require(actor, ActionScopeCode.LISTING_ACTION_PREPARE, ResourceScope.organization(actor.organizationId()));
        governance.insertDependency(ids.newId(), actor.organizationId(), fromListing, toListing,
                MetadataFieldPolicy.requireText("dependencyKind", kind), MetadataFieldPolicy.requireText("proofReference", proofReference),
                actor.userId(), clock.instant());
    }

    @Transactional(readOnly = true)
    public Set<UUID> isolationScope(UUID organizationId, UUID failingListingId) {
        return IsolationScope.widen(failingListingId, governance.provenDependencies(organizationId));
    }

    // ------------------------------------------------------------------ late association

    public record LateAssociationRequest(UUID observationId, UUID actionId, String associationKind, Instant operationTime,
                                         Instant reportTime, String authorityGap, String forwardDisposition) {
    }

    @Transactional
    public LateAssociationView recordLateAssociation(AuthenticatedActor actor, UUID listingId, LateAssociationRequest request) {
        ListingFactRepository.ListingContext listing = facts.listing(listingId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.require(actor, ActionScopeCode.LISTING_MANUAL_VERIFY, ResourceScope.store(listing.storeId()));
        String state = switch (request.associationKind()) {
            case "LAWFUL_LATE_REPORT" -> "LINKED";
            case "UNAUTHORISED_DEVIATION" -> "OPEN";
            case "UNRESOLVED_CHANGE" -> "UNDER_VERIFICATION";
            default -> throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        };
        Instant now = clock.instant();
        UUID id = ids.newId();
        governance.insertLateAssociation(id, listing.organizationId(), listingId, request.observationId(), request.actionId(),
                request.associationKind(), request.operationTime(), request.reportTime(), request.authorityGap(),
                request.forwardDisposition(), state, actor.userId(), now);
        if (request.actionId() != null) {
            actions.action(request.actionId()).ifPresent(action -> intake.recordTaskAction(actor, action.recommendationId(),
                    "LATE_ASSOCIATION_RECORDED", "lc-late-association:" + id, request.associationKind()));
        }
        governance.enqueue(ids.newId(), listing.organizationId(), listingId, RecalculationClass.RISK, "late-association:" + id,
                request.operationTime(), now);
        recordAudit(actor, "lc-late-association", id, AuditAction.CREATE, Map.of("kind", new FieldChange(null, request.associationKind())), null);
        return governance.lateAssociation(id).orElseThrow();
    }

    @Transactional
    public LateAssociationView closeLateAssociation(AuthenticatedActor actor, UUID id, UUID verificationId) {
        LateAssociationView association = governance.lateAssociation(id)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        ListingFactRepository.ListingContext listing = facts.listing(association.platformListingId()).orElseThrow();
        authorization.require(actor, ActionScopeCode.LISTING_MANUAL_VERIFY, ResourceScope.store(listing.storeId()));
        if (verificationId == null) {
            // An unresolved change is never closed by resubmission or by matching
            // text; only an independent verification closes it.
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (!governance.closeLateAssociation(id, verificationId, governance.lateAssociationVersion(id), clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        return governance.lateAssociation(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<LateAssociationView> lateAssociations(UUID listingId) {
        return governance.lateAssociations(listingId);
    }

    // ------------------------------------------------------------------ helpers

    private BatchView requireBatch(AuthenticatedActor actor, UUID batchId, ActionScopeCode scope) {
        BatchView batch = governance.batch(batchId).orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.require(actor, scope, ResourceScope.store(batch.storeId()));
        return batch;
    }

    private String proof(String purpose, UUID target, UUID version) {
        long[] context = jdbc.sql("SELECT pg_backend_pid(), txid_current()")
                .query((rs, row) -> new long[] {rs.getInt(1), rs.getLong(2)}).single();
        return issuer.issueControl(purpose, target, version, Math.toIntExact(context[0]), context[1]);
    }

    private void recordAudit(AuthenticatedActor actor, String entityType, UUID entityId, AuditAction action,
                             Map<String, FieldChange> changes, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION, actor.userId().toString(), action,
                entityType, entityId, null, changes, reason, null));
    }
}
