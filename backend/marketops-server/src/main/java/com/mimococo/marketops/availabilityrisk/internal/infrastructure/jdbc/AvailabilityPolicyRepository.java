package com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc;

import com.mimococo.marketops.availabilityrisk.internal.domain.DemandPolicySettings;
import com.mimococo.marketops.availabilityrisk.internal.domain.LeadTimeResolution;
import com.mimococo.marketops.availabilityrisk.internal.domain.SupplyDistinctness;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the published policy versions a calculation runs under.
 *
 * <p>Resolution is exact scoped fallback done in SQL, ordered by the stored
 * precedence, so the winner is the most specific version in force at the
 * instant asked about. Nothing here invents a value: when no row is in force
 * the caller receives a blocked resolution and reports it.
 */
@Repository
public class AvailabilityPolicyRepository {

    private final JdbcClient jdbc;

    public AvailabilityPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The lead-time and safety policy in force for one variant.
     *
     * <p>The supplier, route and category a variant belongs to are attributes
     * of the procurement arrangement rather than of the catalogue, so a scope
     * that names them matches only when the caller supplies them. An absent
     * arrangement therefore falls through to the organization default, and an
     * absent default blocks.
     */
    public LeadTimeResolution resolveLeadTime(UUID organizationId, UUID productVariantId,
                                              String supplierCode, String routeCode,
                                              String categoryCode, Instant asOf) {
        Optional<LeadTimeRow> resolved = jdbc.sql("""
                        SELECT policy.id, policy.policy_version, policy.scope_kind,
                               policy.lead_time_days_max, policy.safety_days
                          FROM core.lead_time_safety_policy AS policy
                         WHERE policy.organization_id = :organizationId
                           AND policy.status = 'ACTIVE'
                           AND policy.effective_from <= :asOf
                           AND (policy.effective_to IS NULL OR policy.effective_to > :asOf)
                           AND (
                                 (policy.scope_kind = 'VARIANT_SUPPLIER_ROUTE'
                                  AND policy.product_variant_id = :productVariantId
                                  AND policy.supplier_code IS NOT DISTINCT FROM :supplierCode
                                  AND policy.route_code IS NOT DISTINCT FROM :routeCode)
                              OR (policy.scope_kind = 'SUPPLIER'
                                  AND policy.supplier_code IS NOT DISTINCT FROM :supplierCode)
                              OR (policy.scope_kind = 'PRODUCT_CATEGORY'
                                  AND policy.category_code IS NOT DISTINCT FROM :categoryCode)
                              OR policy.scope_kind = 'ORGANIZATION')
                         ORDER BY policy.scope_precedence, policy.effective_from DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("productVariantId", productVariantId)
                .param("supplierCode", supplierCode)
                .param("routeCode", routeCode)
                .param("categoryCode", categoryCode)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new LeadTimeRow(
                        rows.getObject("id", UUID.class),
                        rows.getInt("policy_version"),
                        rows.getString("scope_kind"),
                        rows.getInt("lead_time_days_max"),
                        rows.getInt("safety_days")))
                .optional();

        return resolved
                .map(row -> LeadTimeResolution.resolved(row.id(), row.policyVersion(),
                        row.scopeKind(), row.leadTimeDaysMax(), row.safetyDays()))
                .orElseGet(() -> LeadTimeResolution.blocked(
                        "no active lead-time and safety version is in force for any scope"));
    }

    /** The demand-observation policy in force, or empty when none is. */
    public Optional<DemandPolicySettings> resolveDemandPolicy(UUID organizationId, Instant asOf) {
        return jdbc.sql("""
                        SELECT policy.id, policy.policy_version, policy.minimum_sample_units,
                               policy.acceleration_ratio, policy.deceleration_ratio,
                               policy.outlier_share_ratio, policy.minimum_coverage_ratio,
                               policy.carry_forward_max_days, policy.stock_freshness_max_minutes
                          FROM core.demand_observation_policy AS policy
                         WHERE policy.organization_id = :organizationId
                           AND policy.status = 'ACTIVE'
                           AND policy.effective_from <= :asOf
                           AND (policy.effective_to IS NULL OR policy.effective_to > :asOf)
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new DemandPolicySettings(
                        rows.getObject("id", UUID.class),
                        rows.getInt("policy_version"),
                        rows.getInt("minimum_sample_units"),
                        rows.getBigDecimal("acceleration_ratio"),
                        rows.getBigDecimal("deceleration_ratio"),
                        rows.getBigDecimal("outlier_share_ratio"),
                        rows.getBigDecimal("minimum_coverage_ratio"),
                        Duration.ofDays(rows.getInt("carry_forward_max_days")),
                        Duration.ofMinutes(rows.getInt("stock_freshness_max_minutes"))))
                .optional();
    }

    /** The work-activation policy in force, or empty when none is. */
    public Optional<ActivationRow> resolveActivationPolicy(UUID organizationId, Instant asOf) {
        return jdbc.sql("""
                        SELECT policy.id, policy.policy_version, policy.high_sustained_cycles,
                               policy.critical_action_sla_minutes, policy.high_action_sla_minutes,
                               policy.blocker_action_sla_minutes, policy.outcome_sla_minutes,
                               policy.verification_window_minutes
                          FROM core.work_activation_policy AS policy
                         WHERE policy.organization_id = :organizationId
                           AND policy.status = 'ACTIVE'
                           AND policy.effective_from <= :asOf
                           AND (policy.effective_to IS NULL OR policy.effective_to > :asOf)
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new ActivationRow(
                        rows.getObject("id", UUID.class),
                        rows.getInt("policy_version"),
                        rows.getInt("high_sustained_cycles"),
                        rows.getInt("critical_action_sla_minutes"),
                        rows.getInt("high_action_sla_minutes"),
                        rows.getInt("blocker_action_sla_minutes"),
                        rows.getInt("outcome_sla_minutes"),
                        rows.getInt("verification_window_minutes")))
                .optional();
    }

    /**
     * How each store and mode's platform stock relates to internal stock.
     *
     * <p>A store and mode with no declaration is deliberately absent from the
     * result rather than defaulted. The caller turns absence into
     * {@link SupplyDistinctness#UNDECLARED}, which cannot produce a safe
     * company answer.
     */
    public List<OwnershipRow> ownershipDeclarations(UUID organizationId, Instant asOf) {
        return jdbc.sql("""
                        SELECT declaration.store_id, declaration.fulfillment_mode_code,
                               declaration.distinctness, declaration.mirrored_warehouse_id
                          FROM core.supply_ownership_declaration AS declaration
                         WHERE declaration.organization_id = :organizationId
                           AND declaration.status = 'ACTIVE'
                           AND declaration.effective_from <= :asOf
                           AND (declaration.effective_to IS NULL OR declaration.effective_to > :asOf)
                        """)
                .param("organizationId", organizationId)
                .param("asOf", Timestamp.from(asOf))
                .query((rows, rowNumber) -> new OwnershipRow(
                        rows.getObject("store_id", UUID.class),
                        rows.getString("fulfillment_mode_code"),
                        SupplyDistinctness.valueOf(rows.getString("distinctness")),
                        rows.getObject("mirrored_warehouse_id", UUID.class)))
                .list();
    }

    /** A resolved lead-time row. */
    public record LeadTimeRow(UUID id, int policyVersion, String scopeKind,
                              int leadTimeDaysMax, int safetyDays) {
    }

    /** A resolved work-activation row. */
    public record ActivationRow(UUID id, int policyVersion, int highSustainedCycles,
                                int criticalActionSlaMinutes, int highActionSlaMinutes,
                                int blockerActionSlaMinutes, int outcomeSlaMinutes,
                                int verificationWindowMinutes) {
    }

    /** One store and mode's declared relationship to internal stock. */
    public record OwnershipRow(UUID storeId, String fulfillmentModeCode,
                               SupplyDistinctness distinctness, UUID mirroredWarehouseId) {
    }
}
