package com.mimococo.marketops.listingconversion.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.listingconversion.BatchView;
import com.mimococo.marketops.listingconversion.ContainmentCauseClass;
import com.mimococo.marketops.listingconversion.ContainmentView;
import com.mimococo.marketops.listingconversion.LateAssociationView;
import com.mimococo.marketops.listingconversion.RecalculationQueueView;
import com.mimococo.marketops.listingconversion.internal.application.GovernanceService;
import com.mimococo.marketops.listingconversion.internal.application.ListingScopeAuthorization;
import com.mimococo.marketops.listingconversion.internal.application.RecalculationService;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bounded batches, containment, isolation dependencies, late association and
 * the recalculation queue.
 *
 * <p>Stopping is the fastest thing here and needs the least: one holder of the
 * stop scope, one reason, one scope. Re-enabling needs two different people
 * and two kinds of attestation, and the database refuses anything less.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/listing/governance")
class ListingGovernanceConsoleController {

    private final GovernanceService governance;
    private final RecalculationService recalculation;
    private final ListingScopeAuthorization listings;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;

    ListingGovernanceConsoleController(GovernanceService governance, RecalculationService recalculation,
                                       ListingScopeAuthorization listings, BusinessAuthorization authorization,
                                       MetadataAuditRecorder audit) {
        this.governance = governance;
        this.recalculation = recalculation;
        this.listings = listings;
        this.authorization = authorization;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ batches

    @GetMapping(value = "/batches", produces = MediaType.APPLICATION_JSON_VALUE)
    List<BatchView> batches(AuthenticatedActor actor, @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        List<UUID> stores = authorization.permittedStoreIds(actor, ActionScopeCode.LISTING_CONVERSION_VIEW);
        List<BatchView> result = governance.batches(actor.organizationId(), stores, limit);
        auditRead(actor, "lc-batch", actor.organizationId(), "batches");
        return result;
    }

    @GetMapping(value = "/batches/{batchId}", produces = MediaType.APPLICATION_JSON_VALUE)
    BatchView batch(AuthenticatedActor actor, @PathVariable UUID batchId) {
        BatchView result = governance.batch(actor, batchId);
        auditRead(actor, "lc-batch", batchId, "batch");
        return result;
    }

    @PostMapping(value = "/batches", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    BatchView createBatch(AuthenticatedActor actor, @Valid @RequestBody BatchRequest request) {
        return governance.createBatch(actor, request.storeId(), request.code());
    }

    @PostMapping(value = "/batches/{batchId}/members", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    BatchView addMember(AuthenticatedActor actor, @PathVariable UUID batchId, @Valid @RequestBody MemberRequest request) {
        return governance.addMember(actor, batchId, request.actionId());
    }

    @PostMapping(value = "/batches/{batchId}/members/{actionId}/remove", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    BatchView removeMember(AuthenticatedActor actor, @PathVariable UUID batchId, @PathVariable UUID actionId,
                           @Valid @RequestBody ReasonRequest request) {
        return governance.removeMember(actor, batchId, actionId, request.reason());
    }

    @PostMapping(value = "/batches/{batchId}/close", produces = MediaType.APPLICATION_JSON_VALUE)
    BatchView closeBatch(AuthenticatedActor actor, @PathVariable UUID batchId) {
        return governance.closeBatch(actor, batchId);
    }

    // ------------------------------------------------------------------ containment

    @GetMapping(value = "/containments", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ContainmentView> containments(AuthenticatedActor actor,
                                       @RequestParam(defaultValue = "true") boolean activeOnly,
                                       @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        authorization.require(actor, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScope.organization(actor.organizationId()));
        List<ContainmentView> result = governance.containments(actor.organizationId(), activeOnly, limit);
        auditRead(actor, "lc-containment", actor.organizationId(), "containments");
        return result;
    }

    @PostMapping(value = "/containments/stop", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ContainmentView stop(AuthenticatedActor actor, @Valid @RequestBody StopRequest request) {
        return governance.stop(actor, new GovernanceService.StopRequest(request.scopeKind(),
                request.platformListingId(), request.storeId(), request.platformCode(), request.batchId(),
                request.causeClass(), request.causeOwnerRoleCode(), request.reason(), request.evidenceReference()));
    }

    @PostMapping(value = "/containments/{containmentId}/attest", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ContainmentView attest(AuthenticatedActor actor, @PathVariable UUID containmentId,
                           @Valid @RequestBody AttestRequest request) {
        return governance.attest(actor, containmentId, request.kind(), request.evidenceReference());
    }

    @PostMapping(value = "/containments/{containmentId}/reenable", produces = MediaType.APPLICATION_JSON_VALUE)
    ContainmentView reenable(AuthenticatedActor actor, @PathVariable UUID containmentId) {
        return governance.reenable(actor, containmentId);
    }

    // ------------------------------------------------------------------ isolation

    @PostMapping(value = "/dependencies", consumes = MediaType.APPLICATION_JSON_VALUE)
    Map<String, String> recordDependency(AuthenticatedActor actor, @Valid @RequestBody DependencyRequest request) {
        governance.recordDependency(actor, request.fromListingId(), request.toListingId(), request.kind(),
                request.proofReference());
        return Map.of("state", "RECORDED");
    }

    @GetMapping(value = "/isolation-scope/{listingId}", produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> isolationScope(AuthenticatedActor actor, @PathVariable UUID listingId) {
        listings.require(actor, listingId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        Set<UUID> scope = governance.isolationScope(actor.organizationId(), listingId);
        auditRead(actor, "lc-isolation-scope", listingId, "isolation-scope");
        return Map.of("failingListingId", listingId, "listingIds", scope.stream().sorted().toList());
    }

    // ------------------------------------------------------------------ late association

    @GetMapping(value = "/listings/{listingId}/late-associations", produces = MediaType.APPLICATION_JSON_VALUE)
    List<LateAssociationView> lateAssociations(AuthenticatedActor actor, @PathVariable UUID listingId) {
        listings.require(actor, listingId, ActionScopeCode.LISTING_CONVERSION_VIEW);
        List<LateAssociationView> result = governance.lateAssociations(listingId);
        auditRead(actor, "lc-late-association", listingId, "late-associations");
        return result;
    }

    @PostMapping(value = "/listings/{listingId}/late-associations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    LateAssociationView recordLateAssociation(AuthenticatedActor actor, @PathVariable UUID listingId,
                                              @Valid @RequestBody LateAssociationRequest request) {
        return governance.recordLateAssociation(actor, listingId, new GovernanceService.LateAssociationRequest(
                request.observationId(), request.actionId(), request.associationKind(), request.operationTime(),
                request.reportTime(), request.authorityGap(), request.forwardDisposition()));
    }

    @PostMapping(value = "/late-associations/{associationId}/close", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    LateAssociationView closeLateAssociation(AuthenticatedActor actor, @PathVariable UUID associationId,
                                             @RequestBody CloseAssociationRequest request) {
        return governance.closeLateAssociation(actor, associationId, request.verificationId());
    }

    // ------------------------------------------------------------------ recalculation

    @GetMapping(value = "/recalculation-queue", produces = MediaType.APPLICATION_JSON_VALUE)
    List<RecalculationQueueView> recalculationQueue(AuthenticatedActor actor,
                                                    @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        authorization.require(actor, ActionScopeCode.LISTING_CONVERSION_VIEW,
                ResourceScope.organization(actor.organizationId()));
        List<RecalculationQueueView> result = recalculation.queue(actor.organizationId(), limit);
        auditRead(actor, "lc-recalculation-queue", actor.organizationId(), "recalculation-queue");
        return result;
    }

    private void auditRead(AuthenticatedActor actor, String entityType, UUID entityId, String reason) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.LISTING_CONVERSION,
                actor.userId().toString(), AuditAction.READ, entityType, entityId, null, Map.of(), reason, null));
    }

    record BatchRequest(@NotNull UUID storeId, @NotBlank String code) {
    }

    record MemberRequest(@NotNull UUID actionId) {
    }

    record ReasonRequest(@NotBlank String reason) {
    }

    record StopRequest(@NotBlank String scopeKind, UUID platformListingId, UUID storeId, String platformCode,
                       UUID batchId, @NotNull ContainmentCauseClass causeClass, @NotBlank String causeOwnerRoleCode,
                       @NotBlank String reason, String evidenceReference) {
    }

    record AttestRequest(@NotBlank String kind, @NotBlank String evidenceReference) {
    }

    record DependencyRequest(@NotNull UUID fromListingId, @NotNull UUID toListingId, @NotBlank String kind,
                             @NotBlank String proofReference) {
    }

    record LateAssociationRequest(UUID observationId, UUID actionId, @NotBlank String associationKind,
                                  @NotNull Instant operationTime, @NotNull Instant reportTime, String authorityGap,
                                  @NotBlank String forwardDisposition) {
    }

    record CloseAssociationRequest(UUID verificationId) {
    }
}
