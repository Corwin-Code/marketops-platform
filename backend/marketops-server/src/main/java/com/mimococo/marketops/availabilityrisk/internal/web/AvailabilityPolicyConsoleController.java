package com.mimococo.marketops.availabilityrisk.internal.web;

import com.mimococo.marketops.availabilityrisk.internal.application.AvailabilityPolicyManagementService;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.ManagedPolicy;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.PolicyKind;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.PolicyScope;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.shared.ConsoleApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Product/procurement console API for effective-dated availability policy authority. */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/availability/policies")
class AvailabilityPolicyConsoleController {

    private final AvailabilityPolicyManagementService service;
    private final BusinessAuthorization authorization;

    AvailabilityPolicyConsoleController(AvailabilityPolicyManagementService service,
                                        BusinessAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
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
                        body.effectiveFrom(), body.effectiveTo(), body.supersedesPolicyId()));
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
                        body.reason(), body.evidenceReference(), body.effectiveFrom(),
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
                      UUID supersedesPolicyId) {
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
}
