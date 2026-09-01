package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.ManagedPolicy;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.PolicyKind;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityPolicyManagementRepository.PolicyScope;
import com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc.AvailabilityRecalculationRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Governed publish/supersede/retire path for every availability policy authority. */
@Service
public class AvailabilityPolicyManagementService {

    private static final int RECALCULATION_PAGE = 1_000;

    private final AvailabilityPolicyManagementRepository policies;
    private final AvailabilityRecalculationRepository recalculation;
    private final MetadataAuditRecorder audit;
    private final IdGenerator ids;
    private final Clock clock;

    public AvailabilityPolicyManagementService(AvailabilityPolicyManagementRepository policies,
            AvailabilityRecalculationRepository recalculation, MetadataAuditRecorder audit,
            IdGenerator ids, Clock clock) {
        this.policies = policies;
        this.recalculation = recalculation;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public ManagedPolicy publishLead(UUID actorUserId,
            AvailabilityPolicyManagementRepository.LeadDraft draft) {
        validateCommon(draft.reason(), draft.evidenceReference(), draft.effectiveFrom(),
                draft.effectiveTo());
        validateLead(draft);
        return publish(actorUserId, draft.organizationId(), PolicyKind.LEAD_TIME,
                () -> policies.publishLead(draft, ids.newId(), actorUserId, clock.instant()));
    }

    @Transactional
    public ManagedPolicy publishDemand(UUID actorUserId,
            AvailabilityPolicyManagementRepository.DemandDraft draft) {
        validateCommon(draft.reason(), draft.evidenceReference(), draft.effectiveFrom(),
                draft.effectiveTo());
        return publish(actorUserId, draft.organizationId(), PolicyKind.DEMAND,
                () -> policies.publishDemand(draft, ids.newId(), actorUserId, clock.instant()));
    }

    @Transactional
    public ManagedPolicy publishActivation(UUID actorUserId,
            AvailabilityPolicyManagementRepository.ActivationDraft draft) {
        validateCommon(draft.reason(), draft.evidenceReference(), draft.effectiveFrom(),
                draft.effectiveTo());
        return publish(actorUserId, draft.organizationId(), PolicyKind.ACTIVATION,
                () -> policies.publishActivation(draft, ids.newId(), actorUserId,
                        clock.instant()));
    }

    @Transactional
    public ManagedPolicy publishPriority(UUID actorUserId,
            AvailabilityPolicyManagementRepository.PriorityDraft draft) {
        validateCommon(draft.reason(), draft.evidenceReference(), draft.effectiveFrom(),
                draft.effectiveTo());
        return publish(actorUserId, draft.organizationId(), PolicyKind.PRIORITY,
                () -> policies.publishPriority(draft, ids.newId(), actorUserId, clock.instant()));
    }

    @Transactional
    public ManagedPolicy publishReturnQuality(UUID actorUserId,
            AvailabilityPolicyManagementRepository.ReturnQualityDraft draft) {
        validateCommon(draft.reason(), draft.evidenceReference(), draft.effectiveFrom(),
                draft.effectiveTo());
        return publish(actorUserId, draft.organizationId(), PolicyKind.RETURN_QUALITY,
                () -> policies.publishReturnQuality(draft, ids.newId(), actorUserId,
                        clock.instant()));
    }

    @Transactional
    public ManagedPolicy publishOwnership(UUID actorUserId,
            AvailabilityPolicyManagementRepository.OwnershipDraft draft) {
        validateCommon(draft.reason(), draft.evidenceReference(), draft.effectiveFrom(),
                draft.effectiveTo());
        boolean recognized = "MIRRORS_INTERNAL".equals(draft.distinctness())
                || "PHYSICALLY_DISTINCT".equals(draft.distinctness());
        if (!recognized || !("MIRRORS_INTERNAL".equals(draft.distinctness())
                ^ draft.mirroredWarehouseId() == null)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return publish(actorUserId, draft.organizationId(), PolicyKind.OWNERSHIP,
                () -> policies.publishOwnership(draft, ids.newId(), actorUserId, clock.instant()));
    }

    @Transactional(readOnly = true)
    public PolicyScope scope(PolicyKind kind, UUID policyId, UUID organizationId) {
        return policies.scope(kind, policyId, organizationId)
                .orElseThrow(() -> OperationRejectedException.of(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Transactional
    public ManagedPolicy retire(PolicyKind kind, UUID policyId, UUID organizationId,
                                UUID actorUserId, String reason, String evidenceReference) {
        if (blank(reason) || blank(evidenceReference)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        PolicyScope scope = scope(kind, policyId, organizationId);
        Instant at = clock.instant();
        try {
            policies.retire(scope, at);
        } catch (AvailabilityPolicyManagementRepository.PolicyWriteConflict conflict) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        changed(actorUserId, organizationId, policyId, kind, "RETIRE", at);
        recalculate(organizationId, kind, policyId, at);
        return new ManagedPolicy(policyId, kind, scope.policyVersion(), scope.scopeReference(),
                scope.effectiveFrom(), scope.effectiveFrom().isAfter(at)
                        ? scope.effectiveTo() : at,
                scope.effectiveFrom().isAfter(at) ? "CANCELLED" : "RETIRED");
    }

    private ManagedPolicy publish(UUID actorUserId, UUID organizationId, PolicyKind kind,
                                  Supplier<ManagedPolicy> write) {
        Instant at = clock.instant();
        ManagedPolicy result;
        try {
            result = write.get();
        } catch (AvailabilityPolicyManagementRepository.PolicyWriteConflict conflict) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        } catch (DataIntegrityViolationException rejected) {
            throw OperationRejectedException.of(errorFor(rejected));
        } catch (IllegalArgumentException invalid) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        changed(actorUserId, organizationId, result.id(), kind, "PUBLISH", at);
        recalculate(organizationId, kind, result.id(), at);
        return result;
    }

    private void recalculate(UUID organizationId, PolicyKind kind, UUID policyId, Instant at) {
        UUID after = null;
        while (true) {
            var variants = recalculation.variantsToReconcile(
                    organizationId, at, after, RECALCULATION_PAGE);
            if (variants.isEmpty()) {
                return;
            }
            for (UUID variantId : variants) {
                recalculation.enqueue(new AvailabilityRecalculationRepository.NewRequest(
                        ids.newId(), organizationId, variantId, trigger(kind), policyId.toString(),
                        at, at, "availability-policy:" + kind + ":" + policyId));
                after = variantId;
            }
        }
    }

    private void changed(UUID actorUserId, UUID organizationId, UUID policyId, PolicyKind kind,
                         String changeKind, Instant at) {
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.AVAILABILITY_RISK,
                actorUserId.toString(), AuditAction.POLICY_CHANGE, "availability_policy",
                policyId, null, Map.of(
                    "policyKind", new FieldChange(null, kind.name()),
                    "changeKind", new FieldChange(null, changeKind),
                    "organizationId", new FieldChange(null, organizationId.toString()),
                    "acceptedAt", new FieldChange(null, at.toString())),
                "governed availability policy "
                        + changeKind.toLowerCase(java.util.Locale.ROOT), null));
    }

    private void validateCommon(String reason, String evidenceReference, Instant effectiveFrom,
                                Instant effectiveTo) {
        Instant now = clock.instant();
        if (blank(reason) || blank(evidenceReference) || effectiveFrom == null
                || effectiveFrom.isBefore(now.minusSeconds(60))
                || (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static void validateLead(AvailabilityPolicyManagementRepository.LeadDraft draft) {
        boolean shaped = switch (draft.scopeKind()) {
            case "VARIANT_SUPPLIER_ROUTE" -> draft.productVariantId() != null
                    && !blank(draft.supplierCode()) && !blank(draft.routeCode())
                    && draft.categoryCode() == null;
            case "SUPPLIER" -> draft.productVariantId() == null
                    && !blank(draft.supplierCode()) && draft.routeCode() == null
                    && draft.categoryCode() == null;
            case "PRODUCT_CATEGORY" -> draft.productVariantId() == null
                    && draft.supplierCode() == null && draft.routeCode() == null
                    && !blank(draft.categoryCode());
            case "ORGANIZATION" -> draft.productVariantId() == null
                    && draft.supplierCode() == null && draft.routeCode() == null
                    && draft.categoryCode() == null;
            default -> false;
        };
        if (!shaped || draft.lastReviewedAt() == null
                || draft.lastReviewedAt().isAfter(draft.effectiveFrom())
                || draft.leadTimeDaysMin() < 0
                || draft.leadTimeDaysMax() < draft.leadTimeDaysMin()
                || draft.safetyDays() < 0) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static ErrorCode errorFor(DataIntegrityViolationException rejected) {
        Throwable cause = rejected.getMostSpecificCause();
        if (cause instanceof SQLException sql) {
            if ("23P01".equals(sql.getSQLState())) {
                return ErrorCode.EFFECTIVE_RANGE_OVERLAP;
            }
            if ("23505".equals(sql.getSQLState())) {
                return ErrorCode.VERSION_CONFLICT;
            }
        }
        return ErrorCode.VALIDATION_FAILED;
    }

    private static String trigger(PolicyKind kind) {
        return switch (kind) {
            case LEAD_TIME -> "LEAD_TIME_POLICY";
            case DEMAND -> "DEMAND_POLICY";
            case RETURN_QUALITY -> "RETURN_EVIDENCE";
            case OWNERSHIP -> "MAPPING_OR_OWNERSHIP";
            case ACTIVATION, PRIORITY -> "MANUAL_REQUEST";
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
