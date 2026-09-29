package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** A store's standing authorization to keep its master data current automatically. */
@Repository
public class MasterDataPolicyRepository {

    private final JdbcClient jdbc;

    MasterDataPolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record a new policy in force. */
    public void insert(Policy policy) {
        jdbc.sql("""
                        INSERT INTO ops.master_data_automation_policy (
                            id, organization_id, store_id, auto_confirm_mapping, auto_adopt_seller_cost,
                            cost_change_limit, status, authorized_by_user_id, authorized_at, reason,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :storeId, :autoConfirmMapping, :autoAdoptSellerCost,
                            :costChangeLimit, 'ACTIVE', :authorizedBy, :authorizedAt, :reason,
                            :authorizedAt, :authorizedAt, 0)
                        """)
                .param("id", policy.id())
                .param("organizationId", policy.organizationId())
                .param("storeId", policy.storeId())
                .param("autoConfirmMapping", policy.autoConfirmMapping())
                .param("autoAdoptSellerCost", policy.autoAdoptSellerCost())
                .param("costChangeLimit", policy.costChangeLimit())
                .param("authorizedBy", policy.authorizedByUserId())
                .param("authorizedAt", Timestamp.from(policy.authorizedAt()))
                .param("reason", policy.reason())
                .update();
    }

    /** The policy in force for a store, if any. */
    public Optional<Policy> findActive(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT * FROM ops.master_data_automation_policy
                         WHERE organization_id = :organizationId AND store_id = :storeId
                           AND status = 'ACTIVE'
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query(MasterDataPolicyRepository::map)
                .optional();
    }

    /** The policy in force for a store, whatever organization asks. */
    public Optional<Policy> findActiveForStore(UUID storeId) {
        return jdbc.sql("""
                        SELECT * FROM ops.master_data_automation_policy
                         WHERE store_id = :storeId AND status = 'ACTIVE'
                        """)
                .param("storeId", storeId)
                .query(MasterDataPolicyRepository::map)
                .optional();
    }

    /** Retire the policy in force; false when it changed since it was read. */
    public boolean retire(UUID id, UUID retiredBy, Instant at, String reason, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.master_data_automation_policy
                           SET status = 'RETIRED', retired_by_user_id = :retiredBy, retired_at = :at,
                               retirement_reason = :reason, updated_at = :at, version = version + 1
                         WHERE id = :id AND status = 'ACTIVE' AND version = :expectedVersion
                        """)
                .param("id", id)
                .param("retiredBy", retiredBy)
                .param("at", Timestamp.from(at))
                .param("reason", reason)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    private static Policy map(ResultSet rows, int rowNumber) throws SQLException {
        return new Policy(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getBoolean("auto_confirm_mapping"),
                rows.getBoolean("auto_adopt_seller_cost"),
                rows.getBigDecimal("cost_change_limit"),
                rows.getObject("authorized_by_user_id", UUID.class),
                rows.getTimestamp("authorized_at").toInstant(),
                rows.getString("reason"),
                rows.getLong("version"));
    }

    /**
     * One policy.
     *
     * @param costChangeLimit the largest relative change of a seller cost adopted without a person (0.30 = ±30 %)
     */
    public record Policy(UUID id, UUID organizationId, UUID storeId, boolean autoConfirmMapping,
                         boolean autoAdoptSellerCost, BigDecimal costChangeLimit, UUID authorizedByUserId,
                         Instant authorizedAt, String reason, long version) {
    }
}
