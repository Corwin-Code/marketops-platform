package com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes attributable effective-dated availability policy versions. */
@Repository
public class AvailabilityPolicyManagementRepository {

    private final JdbcClient jdbc;

    public AvailabilityPolicyManagementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ManagedPolicy publishLead(LeadDraft draft, UUID id, UUID ownerUserId, Instant at) {
        String scope = leadScope(draft);
        supersede(draft.supersedesPolicyId(), PolicyKind.LEAD_TIME, draft.organizationId(),
                scope, draft.effectiveFrom());
        int version = nextLeadVersion(draft.organizationId(), scope);
        jdbc.sql("""
                        INSERT INTO core.lead_time_safety_policy
                            (id, organization_id, scope_kind, scope_precedence,
                             product_variant_id, supplier_code, route_code, category_code,
                             lead_time_days_min, lead_time_days_max, safety_days, owner_user_id,
                             reason, evidence_reference, last_reviewed_at, effective_from,
                             effective_to, status, policy_version, fallback_of_id, created_at)
                        VALUES (:id, :organizationId, :scopeKind, :scopePrecedence,
                                :productVariantId, :supplierCode, :routeCode, :categoryCode,
                                :leadMin, :leadMax, :safetyDays, :ownerUserId,
                                :reason, :evidenceReference, :lastReviewedAt, :effectiveFrom,
                                :effectiveTo, 'ACTIVE', :version, :fallbackOfId, :createdAt)
                        """)
                .param("id", id).param("organizationId", draft.organizationId())
                .param("scopeKind", draft.scopeKind())
                .param("scopePrecedence", precedence(draft.scopeKind()))
                .param("productVariantId", draft.productVariantId())
                .param("supplierCode", draft.supplierCode()).param("routeCode", draft.routeCode())
                .param("categoryCode", draft.categoryCode()).param("leadMin", draft.leadTimeDaysMin())
                .param("leadMax", draft.leadTimeDaysMax()).param("safetyDays", draft.safetyDays())
                .param("ownerUserId", ownerUserId).param("reason", draft.reason())
                .param("evidenceReference", draft.evidenceReference())
                .param("lastReviewedAt", Timestamp.from(draft.lastReviewedAt()))
                .param("effectiveFrom", Timestamp.from(draft.effectiveFrom()))
                .param("effectiveTo", timestamp(draft.effectiveTo())).param("version", version)
                .param("fallbackOfId", draft.fallbackOfId())
                .param("createdAt", Timestamp.from(at)).update();
        return new ManagedPolicy(id, PolicyKind.LEAD_TIME, version, scope,
                draft.effectiveFrom(), draft.effectiveTo(), "ACTIVE");
    }

    public ManagedPolicy publishDemand(DemandDraft draft, UUID id, UUID ownerUserId, Instant at) {
        supersede(draft.supersedesPolicyId(), PolicyKind.DEMAND, draft.organizationId(),
                organizationScope(draft.organizationId()), draft.effectiveFrom());
        int version = nextOrganizationVersion("core.demand_observation_policy",
                draft.organizationId());
        jdbc.sql("""
                        INSERT INTO core.demand_observation_policy
                            (id, organization_id, minimum_sample_units, acceleration_ratio,
                             deceleration_ratio, outlier_share_ratio, minimum_coverage_ratio,
                             carry_forward_max_days, stock_freshness_max_minutes, owner_user_id,
                             reason, evidence_reference, effective_from, effective_to, status,
                             policy_version, created_at)
                        VALUES (:id, :organizationId, :minimumSampleUnits, :accelerationRatio,
                                :decelerationRatio, :outlierShareRatio, :minimumCoverageRatio,
                                :carryForwardMaxDays, :stockFreshnessMaxMinutes, :ownerUserId,
                                :reason, :evidenceReference, :effectiveFrom, :effectiveTo,
                                'ACTIVE', :version, :createdAt)
                        """)
                .param("id", id).param("organizationId", draft.organizationId())
                .param("minimumSampleUnits", draft.minimumSampleUnits())
                .param("accelerationRatio", draft.accelerationRatio())
                .param("decelerationRatio", draft.decelerationRatio())
                .param("outlierShareRatio", draft.outlierShareRatio())
                .param("minimumCoverageRatio", draft.minimumCoverageRatio())
                .param("carryForwardMaxDays", draft.carryForwardMaxDays())
                .param("stockFreshnessMaxMinutes", draft.stockFreshnessMaxMinutes())
                .param("ownerUserId", ownerUserId).param("reason", draft.reason())
                .param("evidenceReference", draft.evidenceReference())
                .param("effectiveFrom", Timestamp.from(draft.effectiveFrom()))
                .param("effectiveTo", timestamp(draft.effectiveTo())).param("version", version)
                .param("createdAt", Timestamp.from(at)).update();
        return organizationPolicy(id, PolicyKind.DEMAND, version, draft.organizationId(),
                draft.effectiveFrom(), draft.effectiveTo());
    }

    public ManagedPolicy publishActivation(ActivationDraft draft, UUID id, UUID ownerUserId,
                                           Instant at) {
        supersede(draft.supersedesPolicyId(), PolicyKind.ACTIVATION, draft.organizationId(),
                organizationScope(draft.organizationId()), draft.effectiveFrom());
        int version = nextOrganizationVersion("core.work_activation_policy",
                draft.organizationId());
        jdbc.sql("""
                        INSERT INTO core.work_activation_policy
                            (id, organization_id, high_sustained_cycles,
                             critical_action_sla_minutes, high_action_sla_minutes,
                             blocker_action_sla_minutes, outcome_sla_minutes,
                             verification_window_minutes, owner_user_id, reason,
                             evidence_reference, effective_from, effective_to, status,
                             policy_version, created_at)
                        VALUES (:id, :organizationId, :highCycles, :criticalSla, :highSla,
                                :blockerSla, :outcomeSla, :verificationWindow, :ownerUserId,
                                :reason, :evidenceReference, :effectiveFrom, :effectiveTo,
                                'ACTIVE', :version, :createdAt)
                        """)
                .param("id", id).param("organizationId", draft.organizationId())
                .param("highCycles", draft.highSustainedCycles())
                .param("criticalSla", draft.criticalActionSlaMinutes())
                .param("highSla", draft.highActionSlaMinutes())
                .param("blockerSla", draft.blockerActionSlaMinutes())
                .param("outcomeSla", draft.outcomeSlaMinutes())
                .param("verificationWindow", draft.verificationWindowMinutes())
                .param("ownerUserId", ownerUserId).param("reason", draft.reason())
                .param("evidenceReference", draft.evidenceReference())
                .param("effectiveFrom", Timestamp.from(draft.effectiveFrom()))
                .param("effectiveTo", timestamp(draft.effectiveTo())).param("version", version)
                .param("createdAt", Timestamp.from(at)).update();
        return organizationPolicy(id, PolicyKind.ACTIVATION, version, draft.organizationId(),
                draft.effectiveFrom(), draft.effectiveTo());
    }

    public ManagedPolicy publishPriority(PriorityDraft draft, UUID id, UUID ownerUserId,
                                         Instant at) {
        supersede(draft.supersedesPolicyId(), PolicyKind.PRIORITY, draft.organizationId(),
                organizationScope(draft.organizationId()), draft.effectiveFrom());
        int version = nextOrganizationVersion("core.availability_priority_policy",
                draft.organizationId());
        jdbc.sql("""
                        INSERT INTO core.availability_priority_policy
                            (id, organization_id, policy_version, time_weight, profit_weight,
                             velocity_weight, lifecycle_weight, confidence_weight, owner_user_id,
                             reason, evidence_reference, effective_from, effective_to, status,
                             created_at)
                        VALUES (:id, :organizationId, :version, :timeWeight, :profitWeight,
                                :velocityWeight, :lifecycleWeight, :confidenceWeight,
                                :ownerUserId, :reason, :evidenceReference, :effectiveFrom,
                                :effectiveTo, 'ACTIVE', :createdAt)
                        """)
                .param("id", id).param("organizationId", draft.organizationId())
                .param("version", version).param("timeWeight", draft.timeWeight())
                .param("profitWeight", draft.profitWeight())
                .param("velocityWeight", draft.velocityWeight())
                .param("lifecycleWeight", draft.lifecycleWeight())
                .param("confidenceWeight", draft.confidenceWeight())
                .param("ownerUserId", ownerUserId).param("reason", draft.reason())
                .param("evidenceReference", draft.evidenceReference())
                .param("effectiveFrom", Timestamp.from(draft.effectiveFrom()))
                .param("effectiveTo", timestamp(draft.effectiveTo()))
                .param("createdAt", Timestamp.from(at)).update();
        return organizationPolicy(id, PolicyKind.PRIORITY, version, draft.organizationId(),
                draft.effectiveFrom(), draft.effectiveTo());
    }

    public ManagedPolicy publishReturnQuality(ReturnQualityDraft draft, UUID id,
                                              UUID ownerUserId, Instant at) {
        supersede(draft.supersedesPolicyId(), PolicyKind.RETURN_QUALITY,
                draft.organizationId(), organizationScope(draft.organizationId()),
                draft.effectiveFrom());
        int version = nextOrganizationVersion("core.return_quality_policy",
                draft.organizationId());
        jdbc.sql("""
                        INSERT INTO core.return_quality_policy
                            (id, organization_id, policy_version, maximum_return_ratio,
                             minimum_retention_ratio, maximum_defect_return_ratio,
                             evidence_freshness_max_minutes, owner_user_id,
                             reason, evidence_reference, effective_from, effective_to, status,
                             created_at)
                        VALUES (:id, :organizationId, :version, :maximumReturnRatio,
                                :minimumRetentionRatio, :maximumDefectReturnRatio,
                                :evidenceFreshnessMaxMinutes, :ownerUserId,
                                :reason, :evidenceReference, :effectiveFrom, :effectiveTo,
                                'ACTIVE', :createdAt)
                        """)
                .param("id", id).param("organizationId", draft.organizationId())
                .param("version", version)
                .param("maximumReturnRatio", draft.maximumReturnRatio())
                .param("minimumRetentionRatio", draft.minimumRetentionRatio())
                .param("maximumDefectReturnRatio", draft.maximumDefectReturnRatio())
                .param("evidenceFreshnessMaxMinutes", draft.evidenceFreshnessMaxMinutes())
                .param("ownerUserId", ownerUserId).param("reason", draft.reason())
                .param("evidenceReference", draft.evidenceReference())
                .param("effectiveFrom", Timestamp.from(draft.effectiveFrom()))
                .param("effectiveTo", timestamp(draft.effectiveTo()))
                .param("createdAt", Timestamp.from(at)).update();
        return organizationPolicy(id, PolicyKind.RETURN_QUALITY, version, draft.organizationId(),
                draft.effectiveFrom(), draft.effectiveTo());
    }

    public ManagedPolicy publishOwnership(OwnershipDraft draft, UUID id, UUID ownerUserId,
                                          Instant at) {
        String scope = ownershipScope(draft.storeId(), draft.fulfillmentModeCode());
        supersede(draft.supersedesPolicyId(), PolicyKind.OWNERSHIP, draft.organizationId(),
                scope, draft.effectiveFrom());
        int version = nextOwnershipVersion(draft.organizationId(), draft.storeId(),
                draft.fulfillmentModeCode());
        jdbc.sql("""
                        INSERT INTO core.supply_ownership_declaration
                            (id, organization_id, store_id, fulfillment_mode_code, distinctness,
                             mirrored_warehouse_id, evidence_reference, declared_by_user_id,
                             reason, effective_from, effective_to, status, policy_version,
                             created_at)
                        VALUES (:id, :organizationId, :storeId, :mode, :distinctness,
                                :warehouseId, :evidenceReference, :ownerUserId, :reason,
                                :effectiveFrom, :effectiveTo, 'ACTIVE', :version, :createdAt)
                        """)
                .param("id", id).param("organizationId", draft.organizationId())
                .param("storeId", draft.storeId()).param("mode", draft.fulfillmentModeCode())
                .param("distinctness", draft.distinctness())
                .param("warehouseId", draft.mirroredWarehouseId())
                .param("evidenceReference", draft.evidenceReference())
                .param("ownerUserId", ownerUserId).param("reason", draft.reason())
                .param("effectiveFrom", Timestamp.from(draft.effectiveFrom()))
                .param("effectiveTo", timestamp(draft.effectiveTo())).param("version", version)
                .param("createdAt", Timestamp.from(at)).update();
        return new ManagedPolicy(id, PolicyKind.OWNERSHIP, version, scope,
                draft.effectiveFrom(), draft.effectiveTo(), "ACTIVE");
    }

    /** Locate a policy before authorization or retirement. */
    public Optional<PolicyScope> scope(PolicyKind kind, UUID id, UUID organizationId) {
        String sql = switch (kind) {
            case LEAD_TIME -> """
                    SELECT organization_id, product_variant_id, CAST(NULL AS uuid) AS store_id,
                           scope_key AS scope_reference, effective_from, status
                           , policy_version, effective_to
                      FROM core.lead_time_safety_policy
                     WHERE id = :id AND organization_id = :organizationId
                    """;
            case OWNERSHIP -> """
                    SELECT organization_id, CAST(NULL AS uuid) AS product_variant_id, store_id,
                           store_id || '|' || fulfillment_mode_code AS scope_reference,
                           effective_from, status
                           , policy_version, effective_to
                      FROM core.supply_ownership_declaration
                     WHERE id = :id AND organization_id = :organizationId
                    """;
            case DEMAND -> organizationScopeSql("core.demand_observation_policy");
            case ACTIVATION -> organizationScopeSql("core.work_activation_policy");
            case PRIORITY -> organizationScopeSql("core.availability_priority_policy");
            case RETURN_QUALITY -> organizationScopeSql("core.return_quality_policy");
        };
        return jdbc.sql(sql).param("id", id).param("organizationId", organizationId)
                .query((rows, number) -> new PolicyScope(kind,
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getObject("store_id", UUID.class), rows.getString("scope_reference"),
                        rows.getTimestamp("effective_from").toInstant(),
                        timestampOrNull(rows.getTimestamp("effective_to")),
                        rows.getInt("policy_version"), rows.getString("status"), id))
                .optional();
    }

    /** End a current version, or cancel one that never became effective. */
    public void retire(PolicyScope scope, Instant at) {
        String table = table(scope.kind());
        int changed;
        if (scope.effectiveFrom().isAfter(at)) {
            changed = jdbc.sql("UPDATE " + table
                            + " SET status = 'CANCELLED'"
                            + " WHERE id = :id AND organization_id = :organizationId"
                            + " AND status = 'ACTIVE'")
                    .param("id", scope.policyId()).param("organizationId", scope.organizationId())
                    .update();
        } else {
            changed = jdbc.sql("UPDATE " + table
                            + " SET status = 'RETIRED', effective_to = :at"
                            + " WHERE id = :id AND organization_id = :organizationId"
                            + " AND status = 'ACTIVE'"
                            + " AND (effective_to IS NULL OR effective_to > :at)")
                    .param("id", scope.policyId()).param("organizationId", scope.organizationId())
                    .param("at", Timestamp.from(at)).update();
        }
        if (changed != 1) {
            throw new PolicyWriteConflict();
        }
    }

    private void supersede(UUID priorId, PolicyKind kind, UUID organizationId,
                           String expectedScope, Instant effectiveFrom) {
        if (priorId == null) {
            return;
        }
        PolicyScope prior = scope(kind, priorId, organizationId)
                .orElseThrow(PolicyWriteConflict::new);
        if (!prior.scopeReference().equals(expectedScope)
                || !prior.effectiveFrom().isBefore(effectiveFrom)
                || !"ACTIVE".equals(prior.status())) {
            throw new PolicyWriteConflict();
        }
        retire(prior, effectiveFrom);
    }

    private int nextLeadVersion(UUID organizationId, String scope) {
        return jdbc.sql("""
                        SELECT coalesce(max(policy_version), 0) + 1
                          FROM core.lead_time_safety_policy
                         WHERE organization_id = :organizationId AND scope_key = :scope
                        """).param("organizationId", organizationId).param("scope", scope)
                .query(Integer.class).single();
    }

    private int nextOwnershipVersion(UUID organizationId, UUID storeId, String mode) {
        return jdbc.sql("""
                        SELECT coalesce(max(policy_version), 0) + 1
                          FROM core.supply_ownership_declaration
                         WHERE organization_id = :organizationId AND store_id = :storeId
                           AND fulfillment_mode_code = :mode
                        """).param("organizationId", organizationId).param("storeId", storeId)
                .param("mode", mode).query(Integer.class).single();
    }

    private int nextOrganizationVersion(String table, UUID organizationId) {
        return jdbc.sql("SELECT coalesce(max(policy_version), 0) + 1 FROM " + table
                        + " WHERE organization_id = :organizationId")
                .param("organizationId", organizationId).query(Integer.class).single();
    }

    private static String leadScope(LeadDraft draft) {
        return draft.scopeKind() + "|" + value(draft.productVariantId()) + "|"
                + value(draft.supplierCode()) + "|" + value(draft.routeCode()) + "|"
                + value(draft.categoryCode());
    }

    private static int precedence(String scopeKind) {
        return switch (scopeKind) {
            case "VARIANT_SUPPLIER_ROUTE" -> 1;
            case "SUPPLIER", "PRODUCT_CATEGORY" -> 2;
            case "ORGANIZATION" -> 3;
            default -> throw new IllegalArgumentException("unknown lead-time scope");
        };
    }

    private static String ownershipScope(UUID storeId, String mode) {
        return storeId + "|" + mode;
    }

    private static String organizationScope(UUID organizationId) {
        return "ORGANIZATION|" + organizationId;
    }

    private static String organizationScopeSql(String table) {
        return "SELECT organization_id, CAST(NULL AS uuid) AS product_variant_id,"
                + " CAST(NULL AS uuid) AS store_id, 'ORGANIZATION|' || organization_id"
                + " AS scope_reference, effective_from, effective_to, policy_version, status FROM "
                + table
                + " WHERE id = :id AND organization_id = :organizationId";
    }

    private static String table(PolicyKind kind) {
        return switch (kind) {
            case LEAD_TIME -> "core.lead_time_safety_policy";
            case DEMAND -> "core.demand_observation_policy";
            case ACTIVATION -> "core.work_activation_policy";
            case PRIORITY -> "core.availability_priority_policy";
            case RETURN_QUALITY -> "core.return_quality_policy";
            case OWNERSHIP -> "core.supply_ownership_declaration";
        };
    }

    private static ManagedPolicy organizationPolicy(UUID id, PolicyKind kind, int version,
            UUID organizationId, Instant effectiveFrom, Instant effectiveTo) {
        return new ManagedPolicy(id, kind, version, organizationScope(organizationId),
                effectiveFrom, effectiveTo, "ACTIVE");
    }

    private static Object timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant timestampOrNull(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String value(Object value) {
        return value == null ? "-" : value.toString();
    }

    public enum PolicyKind { LEAD_TIME, DEMAND, ACTIVATION, PRIORITY, RETURN_QUALITY, OWNERSHIP }

    public record ManagedPolicy(UUID id, PolicyKind kind, int version, String scopeReference,
                                Instant effectiveFrom, Instant effectiveTo, String status) {
    }

    public record PolicyScope(PolicyKind kind, UUID organizationId, UUID productVariantId,
                              UUID storeId, String scopeReference, Instant effectiveFrom,
                              Instant effectiveTo, int policyVersion, String status,
                              UUID policyId) {
    }

    public record LeadDraft(UUID organizationId, String scopeKind, UUID productVariantId,
                            String supplierCode, String routeCode, String categoryCode,
                            int leadTimeDaysMin, int leadTimeDaysMax, int safetyDays,
                            String reason, String evidenceReference, Instant lastReviewedAt,
                            Instant effectiveFrom, Instant effectiveTo, UUID fallbackOfId,
                            UUID supersedesPolicyId) {
    }

    public record DemandDraft(UUID organizationId, int minimumSampleUnits,
                              BigDecimal accelerationRatio, BigDecimal decelerationRatio,
                              BigDecimal outlierShareRatio, BigDecimal minimumCoverageRatio,
                              int carryForwardMaxDays, int stockFreshnessMaxMinutes,
                              String reason, String evidenceReference, Instant effectiveFrom,
                              Instant effectiveTo, UUID supersedesPolicyId) {
    }

    public record ActivationDraft(UUID organizationId, int highSustainedCycles,
                                  int criticalActionSlaMinutes, int highActionSlaMinutes,
                                  int blockerActionSlaMinutes, int outcomeSlaMinutes,
                                  int verificationWindowMinutes, String reason,
                                  String evidenceReference, Instant effectiveFrom,
                                  Instant effectiveTo, UUID supersedesPolicyId) {
    }

    public record PriorityDraft(UUID organizationId, BigDecimal timeWeight,
                                BigDecimal profitWeight, BigDecimal velocityWeight,
                                BigDecimal lifecycleWeight, BigDecimal confidenceWeight,
                                String reason, String evidenceReference, Instant effectiveFrom,
                                Instant effectiveTo, UUID supersedesPolicyId) {
    }

    public record ReturnQualityDraft(UUID organizationId, BigDecimal maximumReturnRatio,
                                     BigDecimal minimumRetentionRatio,
                                     BigDecimal maximumDefectReturnRatio,
                                     int evidenceFreshnessMaxMinutes, String reason,
                                     String evidenceReference, Instant effectiveFrom,
                                     Instant effectiveTo, UUID supersedesPolicyId) {
    }

    public record OwnershipDraft(UUID organizationId, UUID storeId,
                                 String fulfillmentModeCode, String distinctness,
                                 UUID mirroredWarehouseId, String reason,
                                 String evidenceReference, Instant effectiveFrom,
                                 Instant effectiveTo, UUID supersedesPolicyId) {
    }

    /** Internal signal translated to stable refusal semantics by the service. */
    public static final class PolicyWriteConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
