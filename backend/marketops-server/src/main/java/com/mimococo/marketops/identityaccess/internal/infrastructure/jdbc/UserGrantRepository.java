package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.BusinessRoleCode;
import com.mimococo.marketops.identityaccess.ResourceScopeType;
import com.mimococo.marketops.identityaccess.internal.domain.GrantStatus;
import com.mimococo.marketops.identityaccess.internal.domain.RoleAssignment;
import com.mimococo.marketops.identityaccess.internal.domain.UserScopeGrantRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Administration of role assignments and scope grants.
 *
 * <p>The five typed resource columns are written from one logical resource
 * reference. Keeping the mapping here means the relational guarantee — that a
 * grant cannot name a resource outside its own organization — is expressed once
 * and cannot be bypassed by a caller that writes the wrong column.
 */
@Repository
public class UserGrantRepository {

    private final JdbcClient jdbc;

    UserGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record a role assignment. */
    public void insertRole(RoleAssignment assignment) {
        jdbc.sql("""
                        INSERT INTO iam.user_role_assignment (
                            id, organization_id, user_id, role_code, effective_from,
                            effective_to, status, reason, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :userId, :role, :effectiveFrom,
                            :effectiveTo, :status, :reason, :createdAt, :updatedAt, :version)
                        """)
                .param("id", assignment.id())
                .param("organizationId", assignment.organizationId())
                .param("userId", assignment.userId())
                .param("role", assignment.role().name())
                .param("effectiveFrom", Timestamp.from(assignment.effectiveFrom()))
                .param("effectiveTo", timestamp(assignment.effectiveTo()))
                .param("status", assignment.status().name())
                .param("reason", assignment.reason())
                .param("createdAt", Timestamp.from(assignment.createdAt()))
                .param("updatedAt", Timestamp.from(assignment.updatedAt()))
                .param("version", assignment.version())
                .update();
    }

    /** Revoke a live role assignment; false means it was already withdrawn. */
    public boolean revokeRole(UUID id, String reason, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE iam.user_role_assignment
                        SET status = 'REVOKED', reason = :reason, effective_to = :at,
                            updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion AND status = 'ACTIVE'
                        """)
                .param("reason", reason)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Load one role assignment. */
    public Optional<RoleAssignment> findRole(UUID id) {
        return jdbc.sql("SELECT * FROM iam.user_role_assignment WHERE id = :id")
                .param("id", id)
                .query(UserGrantRepository::mapRole)
                .optional();
    }

    /** List a profile's role assignments, newest interval first. */
    public List<RoleAssignment> listRoles(UUID userId) {
        return jdbc.sql("""
                        SELECT * FROM iam.user_role_assignment
                        WHERE user_id = :userId ORDER BY effective_from DESC, id
                        """)
                .param("userId", userId)
                .query(UserGrantRepository::mapRole)
                .list();
    }

    /** Record a scope grant, writing the typed column its resource kind names. */
    public void insertGrant(UserScopeGrantRecord grantRecord) {
        jdbc.sql("""
                        INSERT INTO iam.user_scope_grant (
                            id, organization_id, user_id, action_code,
                            organization_ref_id, legal_entity_ref_id,
                            marketplace_account_ref_id, store_ref_id, warehouse_ref_id,
                            effective_from, effective_to, status, reason,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :userId, :action,
                            :organizationRef, :legalEntityRef, :accountRef, :storeRef,
                            :warehouseRef, :effectiveFrom, :effectiveTo, :status, :reason,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", grantRecord.id())
                .param("organizationId", grantRecord.organizationId())
                .param("userId", grantRecord.userId())
                .param("action", grantRecord.action().name())
                .param("organizationRef", reference(grantRecord, ResourceScopeType.ORGANIZATION))
                .param("legalEntityRef", reference(grantRecord, ResourceScopeType.LEGAL_ENTITY))
                .param("accountRef", reference(grantRecord, ResourceScopeType.MARKETPLACE_ACCOUNT))
                .param("storeRef", reference(grantRecord, ResourceScopeType.STORE))
                .param("warehouseRef", reference(grantRecord, ResourceScopeType.WAREHOUSE))
                .param("effectiveFrom", Timestamp.from(grantRecord.effectiveFrom()))
                .param("effectiveTo", timestamp(grantRecord.effectiveTo()))
                .param("status", grantRecord.status().name())
                .param("reason", grantRecord.reason())
                .param("createdAt", Timestamp.from(grantRecord.createdAt()))
                .param("updatedAt", Timestamp.from(grantRecord.updatedAt()))
                .param("version", grantRecord.version())
                .update();
    }

    /** Revoke a live scope grant; false means it was already withdrawn. */
    public boolean revokeGrant(UUID id, String reason, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE iam.user_scope_grant
                        SET status = 'REVOKED', reason = :reason, effective_to = :at,
                            updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion AND status = 'ACTIVE'
                        """)
                .param("reason", reason)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Load one scope grant. */
    public Optional<UserScopeGrantRecord> findGrant(UUID id) {
        return jdbc.sql("SELECT * FROM iam.user_scope_grant WHERE id = :id")
                .param("id", id)
                .query(UserGrantRepository::mapGrant)
                .optional();
    }

    /** List a profile's scope grants, newest interval first. */
    public List<UserScopeGrantRecord> listGrants(UUID userId) {
        return jdbc.sql("""
                        SELECT * FROM iam.user_scope_grant
                        WHERE user_id = :userId ORDER BY effective_from DESC, id
                        """)
                .param("userId", userId)
                .query(UserGrantRepository::mapGrant)
                .list();
    }

    private static UUID reference(UserScopeGrantRecord grantRecord, ResourceScopeType type) {
        return grantRecord.resourceType() == type ? grantRecord.resourceId() : null;
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static RoleAssignment mapRole(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp effectiveTo = rows.getTimestamp("effective_to");
        return new RoleAssignment(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("user_id", UUID.class),
                BusinessRoleCode.valueOf(rows.getString("role_code")),
                rows.getTimestamp("effective_from").toInstant(),
                effectiveTo == null ? null : effectiveTo.toInstant(),
                GrantStatus.valueOf(rows.getString("status")),
                rows.getString("reason"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }

    private static UserScopeGrantRecord mapGrant(ResultSet rows, int rowNumber)
            throws SQLException {
        Timestamp effectiveTo = rows.getTimestamp("effective_to");
        ResourceScopeType type;
        UUID resourceId;
        if (rows.getObject("organization_ref_id", UUID.class) != null) {
            type = ResourceScopeType.ORGANIZATION;
            resourceId = rows.getObject("organization_ref_id", UUID.class);
        } else if (rows.getObject("legal_entity_ref_id", UUID.class) != null) {
            type = ResourceScopeType.LEGAL_ENTITY;
            resourceId = rows.getObject("legal_entity_ref_id", UUID.class);
        } else if (rows.getObject("marketplace_account_ref_id", UUID.class) != null) {
            type = ResourceScopeType.MARKETPLACE_ACCOUNT;
            resourceId = rows.getObject("marketplace_account_ref_id", UUID.class);
        } else if (rows.getObject("store_ref_id", UUID.class) != null) {
            type = ResourceScopeType.STORE;
            resourceId = rows.getObject("store_ref_id", UUID.class);
        } else {
            type = ResourceScopeType.WAREHOUSE;
            resourceId = rows.getObject("warehouse_ref_id", UUID.class);
        }
        return new UserScopeGrantRecord(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("user_id", UUID.class),
                ActionScopeCode.valueOf(rows.getString("action_code")),
                type,
                resourceId,
                rows.getTimestamp("effective_from").toInstant(),
                effectiveTo == null ? null : effectiveTo.toInstant(),
                GrantStatus.valueOf(rows.getString("status")),
                rows.getString("reason"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }
}
