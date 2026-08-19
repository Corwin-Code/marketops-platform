package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrant;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeGrantStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ScopeResourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Relational access to {@code iam.service_account_scope_grant}.
 *
 * <p>The table stores the resource in one column per resource kind; this
 * repository folds those columns into the single typed reference the domain
 * uses, and spreads it back on writes.
 */
@Repository
public class ScopeGrantRepository {

    private final JdbcClient jdbc;

    ScopeGrantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new grant. */
    public void insert(ScopeGrant grant) {
        jdbc.sql("""
                        INSERT INTO iam.service_account_scope_grant (
                            id, organization_id, service_account_id, permission_code,
                            organization_ref_id, legal_entity_ref_id, marketplace_account_ref_id,
                            store_ref_id, warehouse_ref_id,
                            effective_from, effective_to, status, reason,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :serviceAccountId, :permissionCode,
                            :organizationRefId, :legalEntityRefId, :marketplaceAccountRefId,
                            :storeRefId, :warehouseRefId,
                            :effectiveFrom, :effectiveTo, :status, :reason,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", grant.id())
                .param("organizationId", grant.organizationId())
                .param("serviceAccountId", grant.serviceAccountId())
                .param("permissionCode", grant.permissionCode())
                .param("organizationRefId", resource(grant, ScopeResourceType.ORGANIZATION))
                .param("legalEntityRefId", resource(grant, ScopeResourceType.LEGAL_ENTITY))
                .param("marketplaceAccountRefId", resource(grant, ScopeResourceType.MARKETPLACE_ACCOUNT))
                .param("storeRefId", resource(grant, ScopeResourceType.STORE))
                .param("warehouseRefId", resource(grant, ScopeResourceType.WAREHOUSE))
                .param("effectiveFrom", Timestamp.from(grant.effectiveFrom()))
                .param("effectiveTo",
                        grant.effectiveTo() == null ? null : Timestamp.from(grant.effectiveTo()))
                .param("status", grant.status().name())
                .param("reason", grant.reason())
                .param("createdAt", Timestamp.from(grant.createdAt()))
                .param("updatedAt", Timestamp.from(grant.updatedAt()))
                .param("version", grant.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(ScopeGrant grant, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE iam.service_account_scope_grant
                        SET effective_from = :effectiveFrom, effective_to = :effectiveTo,
                            status = :status, reason = :reason,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("effectiveFrom", Timestamp.from(grant.effectiveFrom()))
                .param("effectiveTo",
                        grant.effectiveTo() == null ? null : Timestamp.from(grant.effectiveTo()))
                .param("status", grant.status().name())
                .param("reason", grant.reason())
                .param("updatedAt", Timestamp.from(grant.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", grant.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one grant. */
    public Optional<ScopeGrant> findById(UUID id) {
        return jdbc.sql("SELECT * FROM iam.service_account_scope_grant WHERE id = :id")
                .param("id", id)
                .query(ScopeGrantRepository::map)
                .optional();
    }

    /** Load the active grant of one subject, permission and resource, if any. */
    public Optional<ScopeGrant> findActiveGrant(UUID serviceAccountId,
                                                String permissionCode,
                                                ScopeResourceType resourceType,
                                                UUID resourceId) {
        return jdbc.sql("""
                        SELECT * FROM iam.service_account_scope_grant
                        WHERE service_account_id = :serviceAccountId
                          AND permission_code = :permissionCode
                          AND status = 'ACTIVE'
                          AND %s = :resourceId
                        """.formatted(column(resourceType)))
                .param("serviceAccountId", serviceAccountId)
                .param("permissionCode", permissionCode)
                .param("resourceId", resourceId)
                .query(ScopeGrantRepository::map)
                .optional();
    }

    /** List a service account's grants, active first, newest validity first. */
    public List<ScopeGrant> listBySubject(UUID serviceAccountId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM iam.service_account_scope_grant
                        WHERE service_account_id = :serviceAccountId
                        ORDER BY status, effective_from DESC, id DESC
                        LIMIT :pageLimit
                        """)
                .param("serviceAccountId", serviceAccountId)
                .param("pageLimit", limit)
                .query(ScopeGrantRepository::map)
                .list();
    }

    /** List a service account's active grants. */
    public List<ScopeGrant> listActiveBySubject(UUID serviceAccountId) {
        return jdbc.sql("""
                        SELECT * FROM iam.service_account_scope_grant
                        WHERE service_account_id = :serviceAccountId AND status = 'ACTIVE'
                        """)
                .param("serviceAccountId", serviceAccountId)
                .query(ScopeGrantRepository::map)
                .list();
    }

    /** Count active grants targeting one resource. */
    public long countActiveByResource(ScopeResourceType resourceType, UUID resourceId) {
        return jdbc.sql("""
                        SELECT count(*) FROM iam.service_account_scope_grant
                        WHERE status = 'ACTIVE' AND %s = :resourceId
                        """.formatted(column(resourceType)))
                .param("resourceId", resourceId)
                .query(Long.class)
                .single();
    }

    private static UUID resource(ScopeGrant grant, ScopeResourceType type) {
        return grant.resourceType() == type ? grant.resourceId() : null;
    }

    private static String column(ScopeResourceType resourceType) {
        return switch (resourceType) {
            case ORGANIZATION -> "organization_ref_id";
            case LEGAL_ENTITY -> "legal_entity_ref_id";
            case MARKETPLACE_ACCOUNT -> "marketplace_account_ref_id";
            case STORE -> "store_ref_id";
            case WAREHOUSE -> "warehouse_ref_id";
        };
    }

    private static ScopeGrant map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp effectiveTo = row.getTimestamp("effective_to");
        ScopeResourceType resourceType;
        UUID resourceId;
        if (row.getObject("organization_ref_id", UUID.class) != null) {
            resourceType = ScopeResourceType.ORGANIZATION;
            resourceId = row.getObject("organization_ref_id", UUID.class);
        } else if (row.getObject("legal_entity_ref_id", UUID.class) != null) {
            resourceType = ScopeResourceType.LEGAL_ENTITY;
            resourceId = row.getObject("legal_entity_ref_id", UUID.class);
        } else if (row.getObject("marketplace_account_ref_id", UUID.class) != null) {
            resourceType = ScopeResourceType.MARKETPLACE_ACCOUNT;
            resourceId = row.getObject("marketplace_account_ref_id", UUID.class);
        } else if (row.getObject("store_ref_id", UUID.class) != null) {
            resourceType = ScopeResourceType.STORE;
            resourceId = row.getObject("store_ref_id", UUID.class);
        } else {
            resourceType = ScopeResourceType.WAREHOUSE;
            resourceId = row.getObject("warehouse_ref_id", UUID.class);
        }
        return new ScopeGrant(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("service_account_id", UUID.class),
                row.getString("permission_code"),
                resourceType,
                resourceId,
                row.getTimestamp("effective_from").toInstant(),
                effectiveTo == null ? null : effectiveTo.toInstant(),
                ScopeGrantStatus.valueOf(row.getString("status")),
                row.getString("reason"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
