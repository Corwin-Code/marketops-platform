package com.mimococo.marketops.availabilityrisk.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityPolicyManagementService;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.LeadPolicyRow;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.ManagedPolicy;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.PolicyKind;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.PolicyScope;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.AuthorizationVerdict;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.PeopleDirectory;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ConsoleApi;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Product/procurement console API for effective-dated availability policy authority. */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/availability/policies")
class AvailabilityPolicyConsoleController {

    /** The largest page the console may ask for. */
    private static final int MAX_PAGE = 200;

    private final AvailabilityPolicyManagementService service;
    private final AvailabilityPolicyManagementRepository policies;
    private final BusinessAuthorization authorization;
    private final PeopleDirectory people;
    private final MetadataAuditRecorder audit;
    private final Clock clock;

    AvailabilityPolicyConsoleController(AvailabilityPolicyManagementService service,
                                        AvailabilityPolicyManagementRepository policies,
                                        BusinessAuthorization authorization,
                                        PeopleDirectory people,
                                        MetadataAuditRecorder audit,
                                        Clock clock) {
        this.service = service;
        this.policies = policies;
        this.authorization = authorization;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The policy versions of one kind this person may read.
     *
     * <p>Only {@code LEAD_TIME} is listed here. Organization-wide versions need
     * the availability view on the organization, exactly as reading one of them
     * does; variant-route versions follow the variants the view covers. Each
     * row says whether this person may retire it, and the page says whether
     * they may publish at organization level and whether their sign-in is
     * recent enough for the step-up the management grant requires. A grant held
     * behind a stale sign-in still counts: the page asks for a fresh sign-in
     * when the person acts rather than hiding what they manage.
     */
    @GetMapping
    @Transactional
    PolicyPage list(AuthenticatedActor actor,
                    @RequestParam(defaultValue = "LEAD_TIME") PolicyKind kind,
                    @RequestParam(required = false) String status,
                    @RequestParam(defaultValue = "100") int limit,
                    @RequestParam(defaultValue = "0") int offset) {
        if (kind != PolicyKind.LEAD_TIME) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        ResourceScope organization = ResourceScope.organization(actor.organizationId());
        boolean readOrganization = authorization.evaluate(actor,
                ActionScopeCode.AVAILABILITY_VIEW, organization).permitted();
        List<UUID> readableVariants = authorization.permittedProductVariantIds(
                actor, ActionScopeCode.AVAILABILITY_VIEW);
        if (!readOrganization && readableVariants.isEmpty()) {
            throw OperationRejectedException.of(ErrorCode.RESOURCE_SCOPE_DENIED);
        }
        boolean manageOrganization = holds(actor, ActionScopeCode.SUPPLY_POLICY_MANAGE,
                organization);
        Set<UUID> manageableVariants = Set.copyOf(authorization.permittedProductVariantIds(
                actor, ActionScopeCode.SUPPLY_POLICY_MANAGE));
        int page = Math.clamp(limit, 1, MAX_PAGE);
        int skip = Math.max(0, offset);
        String stored = status == null || status.isBlank() ? null : status.strip();
        UUID[] variants = readableVariants.toArray(UUID[]::new);
        List<LeadPolicyRow> rows = policies.leadPolicies(actor.organizationId(), readOrganization,
                variants, stored, page, skip);
        long total = policies.countLeadPolicies(actor.organizationId(), readOrganization,
                variants, stored);
        Map<UUID, String> owners = people.displayNames(actor.organizationId(),
                rows.stream().map(LeadPolicyRow::ownerUserId).toList());
        List<LeadPolicyItem> items = rows.stream()
                .map(row -> LeadPolicyItem.of(row, owners.get(row.ownerUserId()),
                        row.productVariantId() == null
                                ? manageOrganization
                                : manageableVariants.contains(row.productVariantId())))
                .toList();
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.AVAILABILITY_RISK,
                actor.userId().toString(), AuditAction.READ, "availability_policy_list",
                actor.organizationId(), null, Map.of(), "lead-time policy list", null));
        return new PolicyPage(items, total, skip, page, manageOrganization,
                !manageableVariants.isEmpty(), actor.stepUpSatisfiedAt(clock.instant()),
                actor.stepUpValidUntil());
    }

    @PostMapping("/lead-time")
    ManagedPolicy publishLead(AuthenticatedActor actor, @Valid @RequestBody LeadBody body) {
        authorization.require(actor, ActionScopeCode.SUPPLY_POLICY_MANAGE,
                body.productVariantId() == null
                        ? ResourceScope.organization(actor.organizationId())
                        : ResourceScope.productVariant(body.productVariantId()));
        return service.publishLead(actor.userId(), new AvailabilityPolicyManagementRepository.LeadDraft(
                actor.organizationId(), body.scopeKind(), body.productVariantId(),
                body.supplierCode(), body.routeCode(), body.categoryCode(),
                body.leadTimeDaysMin(), body.leadTimeDaysMax(), body.safetyDays(), body.reason(),
                body.evidenceReference(), body.lastReviewedAt(), body.effectiveFrom(),
                body.effectiveTo(), body.fallbackOfId(), body.supersedesPolicyId()));
    }

    @PostMapping("/demand")
    ManagedPolicy publishDemand(AuthenticatedActor actor, @Valid @RequestBody DemandBody body) {
        requireOrganization(actor);
        return service.publishDemand(actor.userId(),
                new AvailabilityPolicyManagementRepository.DemandDraft(actor.organizationId(),
                        body.minimumSampleUnits(), body.accelerationRatio(),
                        body.decelerationRatio(), body.outlierShareRatio(),
                        body.minimumCoverageRatio(), body.carryForwardMaxDays(),
                        body.stockFreshnessMaxMinutes(), body.reason(), body.evidenceReference(),
                        body.effectiveFrom(), body.effectiveTo(), body.supersedesPolicyId(),
                        body.demandSource() == null ? "COMPLETED_SALES" : body.demandSource()));
    }

    @PostMapping("/activation")
    ManagedPolicy publishActivation(AuthenticatedActor actor,
                                    @Valid @RequestBody ActivationBody body) {
        requireOrganization(actor);
        return service.publishActivation(actor.userId(),
                new AvailabilityPolicyManagementRepository.ActivationDraft(actor.organizationId(),
                        body.highSustainedCycles(), body.criticalActionSlaMinutes(),
                        body.highActionSlaMinutes(), body.blockerActionSlaMinutes(),
                        body.outcomeSlaMinutes(), body.verificationWindowMinutes(), body.reason(),
                        body.evidenceReference(), body.effectiveFrom(), body.effectiveTo(),
                        body.supersedesPolicyId()));
    }

    @PostMapping("/priority")
    ManagedPolicy publishPriority(AuthenticatedActor actor,
                                  @Valid @RequestBody PriorityBody body) {
        requireOrganization(actor);
        return service.publishPriority(actor.userId(),
                new AvailabilityPolicyManagementRepository.PriorityDraft(actor.organizationId(),
                        body.timeWeight(), body.profitWeight(), body.velocityWeight(),
                        body.lifecycleWeight(), body.confidenceWeight(), body.reason(),
                        body.evidenceReference(), body.effectiveFrom(), body.effectiveTo(),
                        body.supersedesPolicyId()));
    }

    @PostMapping("/return-quality")
    ManagedPolicy publishReturnQuality(AuthenticatedActor actor,
                                       @Valid @RequestBody ReturnQualityBody body) {
        requireOrganization(actor);
        return service.publishReturnQuality(actor.userId(),
                new AvailabilityPolicyManagementRepository.ReturnQualityDraft(
                        actor.organizationId(), body.maximumReturnRatio(),
                        body.minimumRetentionRatio(), body.maximumDefectReturnRatio(),
                        body.evidenceFreshnessMaxMinutes(), body.reason(),
                        body.evidenceReference(), body.effectiveFrom(),
                        body.effectiveTo(), body.supersedesPolicyId()));
    }

    @PostMapping("/ownership")
    ManagedPolicy publishOwnership(AuthenticatedActor actor,
                                   @Valid @RequestBody OwnershipBody body) {
        authorization.require(actor, ActionScopeCode.SUPPLY_POLICY_MANAGE,
                ResourceScope.store(body.storeId()));
        return service.publishOwnership(actor.userId(),
                new AvailabilityPolicyManagementRepository.OwnershipDraft(actor.organizationId(),
                        body.storeId(), body.fulfillmentModeCode(), body.distinctness(),
                        body.mirroredWarehouseId(), body.reason(), body.evidenceReference(),
                        body.effectiveFrom(), body.effectiveTo(), body.supersedesPolicyId()));
    }

    @GetMapping("/{kind}/{policyId}")
    PolicyScope one(AuthenticatedActor actor, @PathVariable PolicyKind kind,
                    @PathVariable UUID policyId) {
        PolicyScope scope = service.scope(kind, policyId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.AVAILABILITY_VIEW, resource(scope));
        return scope;
    }

    @PostMapping("/{kind}/{policyId}/retire")
    ManagedPolicy retire(AuthenticatedActor actor, @PathVariable PolicyKind kind,
                         @PathVariable UUID policyId, @Valid @RequestBody RetireBody body) {
        PolicyScope scope = service.scope(kind, policyId, actor.organizationId());
        authorization.require(actor, ActionScopeCode.SUPPLY_POLICY_MANAGE, resource(scope));
        return service.retire(kind, policyId, actor.organizationId(), actor.userId(),
                body.reason(), body.evidenceReference());
    }

    private void requireOrganization(AuthenticatedActor actor) {
        authorization.require(actor, ActionScopeCode.SUPPLY_POLICY_MANAGE,
                ResourceScope.organization(actor.organizationId()));
    }

    /** Holding the grant, even when a fresh sign-in is still needed before using it. */
    private boolean holds(AuthenticatedActor actor, ActionScopeCode action, ResourceScope scope) {
        AuthorizationVerdict verdict = authorization.evaluate(actor, action, scope);
        return verdict == AuthorizationVerdict.PERMITTED
                || verdict == AuthorizationVerdict.STEP_UP_REQUIRED;
    }

    private static ResourceScope resource(PolicyScope scope) {
        if (scope.productVariantId() != null) {
            return ResourceScope.productVariant(scope.productVariantId());
        }
        if (scope.storeId() != null) {
            return ResourceScope.store(scope.storeId());
        }
        return ResourceScope.organization(scope.organizationId());
    }

    record LeadBody(@NotBlank String scopeKind, UUID productVariantId, String supplierCode,
                    String routeCode, String categoryCode, @Min(0) int leadTimeDaysMin,
                    @Min(0) int leadTimeDaysMax, @Min(0) int safetyDays,
                    @NotBlank String reason, @NotBlank String evidenceReference,
                    @NotNull Instant lastReviewedAt, @NotNull Instant effectiveFrom,
                    Instant effectiveTo, UUID fallbackOfId, UUID supersedesPolicyId) {
    }

    record DemandBody(@Min(1) int minimumSampleUnits,
                      @NotNull @DecimalMin("1.0001") BigDecimal accelerationRatio,
                      @NotNull @DecimalMin("0.0001") @DecimalMax("0.9999")
                      BigDecimal decelerationRatio,
                      @NotNull @DecimalMin("0.0001") @DecimalMax("1")
                      BigDecimal outlierShareRatio,
                      @NotNull @DecimalMin("0.0001") @DecimalMax("1")
                      BigDecimal minimumCoverageRatio,
                      @Min(0) @Max(365) int carryForwardMaxDays,
                      @Min(1) @Max(43200) int stockFreshnessMaxMinutes,
                      @NotBlank String reason, @NotBlank String evidenceReference,
                      @NotNull Instant effectiveFrom, Instant effectiveTo,
                      UUID supersedesPolicyId,
                      @Pattern(regexp = "COMPLETED_SALES|ORDERED_UNITS") String demandSource) {
    }

    record ActivationBody(@Min(1) int highSustainedCycles,
                          @Min(1) int criticalActionSlaMinutes,
                          @Min(1) int highActionSlaMinutes,
                          @Min(1) int blockerActionSlaMinutes,
                          @Min(1) int outcomeSlaMinutes,
                          @Min(1) int verificationWindowMinutes,
                          @NotBlank String reason, @NotBlank String evidenceReference,
                          @NotNull Instant effectiveFrom, Instant effectiveTo,
                          UUID supersedesPolicyId) {
    }

    record PriorityBody(@NotNull @DecimalMin("0") BigDecimal timeWeight,
                        @NotNull @DecimalMin("0") BigDecimal profitWeight,
                        @NotNull @DecimalMin("0") BigDecimal velocityWeight,
                        @NotNull @DecimalMin("0") BigDecimal lifecycleWeight,
                        @NotNull @DecimalMax("0") BigDecimal confidenceWeight,
                        @NotBlank String reason, @NotBlank String evidenceReference,
                        @NotNull Instant effectiveFrom, Instant effectiveTo,
                        UUID supersedesPolicyId) {
    }

    record ReturnQualityBody(@NotNull @DecimalMin("0") @DecimalMax("1")
                             BigDecimal maximumReturnRatio,
                             @NotNull @DecimalMin("0") @DecimalMax("1")
                             BigDecimal minimumRetentionRatio,
                             @NotNull @DecimalMin("0") @DecimalMax("1")
                             BigDecimal maximumDefectReturnRatio,
                             @jakarta.validation.constraints.Min(1)
                             @jakarta.validation.constraints.Max(10080)
                             int evidenceFreshnessMaxMinutes,
                             @NotBlank String reason, @NotBlank String evidenceReference,
                             @NotNull Instant effectiveFrom, Instant effectiveTo,
                             UUID supersedesPolicyId) {
    }

    record OwnershipBody(@NotNull UUID storeId, @NotBlank String fulfillmentModeCode,
                         @NotBlank String distinctness, UUID mirroredWarehouseId,
                         @NotBlank String reason, @NotBlank String evidenceReference,
                         @NotNull Instant effectiveFrom, Instant effectiveTo,
                         UUID supersedesPolicyId) {
    }

    record RetireBody(@NotBlank String reason, @NotBlank String evidenceReference) {
    }

    /**
     * One page of policy versions and what this person may do with them.
     *
     * @param canPublishOrganization whether they hold the management grant organization-wide
     * @param canPublishVariant whether they hold it on at least one variant
     * @param stepUpSatisfied whether their sign-in is recent enough to act now
     * @param stepUpValidUntil until when it stays recent enough
     */
    record PolicyPage(List<LeadPolicyItem> items, long total, int offset, int limit,
                      boolean canPublishOrganization, boolean canPublishVariant,
                      boolean stepUpSatisfied, Instant stepUpValidUntil) {
    }

    /**
     * One lead-time and safety version, with its scope spelled out rather than
     * packed into a key.
     *
     * @param ownerName who published it, or {@code null}
     * @param canManage whether this person holds the grant to retire it
     */
    record LeadPolicyItem(UUID id, PolicyKind kind, int version, String scopeKind,
                          String scopeKey, UUID productVariantId, String skuCode,
                          String displayName, String supplierCode, String routeCode,
                          String categoryCode, int leadTimeDaysMin, int leadTimeDaysMax,
                          int safetyDays, String reason, String evidenceReference,
                          Instant lastReviewedAt, Instant effectiveFrom, Instant effectiveTo,
                          String status, String lifecycle, UUID fallbackOfId,
                          Instant createdAt, UUID ownerUserId, String ownerName,
                          boolean canManage) {

        static LeadPolicyItem of(LeadPolicyRow row, String ownerName, boolean canManage) {
            return new LeadPolicyItem(row.id(), PolicyKind.LEAD_TIME, row.version(),
                    row.scopeKind(), row.scopeKey(), row.productVariantId(), row.skuCode(),
                    row.displayName(), row.supplierCode(), row.routeCode(), row.categoryCode(),
                    row.leadTimeDaysMin(), row.leadTimeDaysMax(), row.safetyDays(), row.reason(),
                    row.evidenceReference(), row.lastReviewedAt(), row.effectiveFrom(),
                    row.effectiveTo(), row.status(), row.lifecycle(), row.fallbackOfId(),
                    row.createdAt(), row.ownerUserId(), ownerName, canManage);
        }
    }
}
