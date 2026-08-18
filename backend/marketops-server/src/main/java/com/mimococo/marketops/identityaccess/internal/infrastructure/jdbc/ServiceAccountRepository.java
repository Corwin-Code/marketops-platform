package com.mimococo.marketops.identityaccess.internal.infrastructure.jdbc;

import com.mimococo.marketops.identityaccess.internal.domain.AllowedSource;
import com.mimococo.marketops.identityaccess.internal.domain.AllowedSourceStatus;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccount;
import com.mimococo.marketops.identityaccess.internal.domain.ServiceAccountStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Relational access to {@code iam.service_account} and its allowed-source
 * declarations.
 */
@Repository
public class ServiceAccountRepository {

    private final JdbcClient jdbc;

    ServiceAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new service account. */
    public void insert(ServiceAccount account) {
        jdbc.sql("""
                        INSERT INTO iam.service_account (
                            id, organization_id, code, display_name, purpose, owner_label,
                            expires_at, status, disabled_reason, last_used_at,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :code, :displayName, :purpose, :ownerLabel,
                            :expiresAt, :status, :disabledReason, :lastUsedAt,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", account.id())
                .param("organizationId", account.organizationId())
                .param("code", account.code())
                .param("displayName", account.displayName())
                .param("purpose", account.purpose())
                .param("ownerLabel", account.ownerLabel())
                .param("expiresAt", Timestamp.from(account.expiresAt()))
                .param("status", account.status().name())
                .param("disabledReason", account.disabledReason())
                .param("lastUsedAt",
                        account.lastUsedAt() == null ? null : Timestamp.from(account.lastUsedAt()))
                .param("createdAt", Timestamp.from(account.createdAt()))
                .param("updatedAt", Timestamp.from(account.updatedAt()))
                .param("version", account.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(ServiceAccount account, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE iam.service_account
                        SET display_name = :displayName, purpose = :purpose,
                            owner_label = :ownerLabel, expires_at = :expiresAt,
                            status = :status, disabled_reason = :disabledReason,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", account.displayName())
                .param("purpose", account.purpose())
                .param("ownerLabel", account.ownerLabel())
                .param("expiresAt", Timestamp.from(account.expiresAt()))
                .param("status", account.status().name())
                .param("disabledReason", account.disabledReason())
                .param("updatedAt", Timestamp.from(account.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", account.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one service account. */
    public Optional<ServiceAccount> findById(UUID id) {
        return jdbc.sql("SELECT * FROM iam.service_account WHERE id = :id")
                .param("id", id)
                .query(ServiceAccountRepository::map)
                .optional();
    }

    /** Load one service account by organization and business code. */
    public Optional<ServiceAccount> findByCode(UUID organizationId, String code) {
        return jdbc.sql("""
                        SELECT * FROM iam.service_account
                        WHERE organization_id = :organizationId AND code = :code
                        """)
                .param("organizationId", organizationId)
                .param("code", code)
                .query(ServiceAccountRepository::map)
                .optional();
    }

    /** List an organization's service accounts by code with a keyset cursor. */
    public List<ServiceAccount> list(UUID organizationId, String afterCode, int limit) {
        return jdbc.sql("""
                        SELECT * FROM iam.service_account
                        WHERE organization_id = :organizationId
                          AND (CAST(:afterCode AS text) IS NULL OR code > :afterCode)
                        ORDER BY code
                        LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("afterCode", afterCode)
                .param("pageLimit", limit)
                .query(ServiceAccountRepository::map)
                .list();
    }

    /** Count an organization's service accounts that are not revoked. */
    public long countNotRevokedByOrganization(UUID organizationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM iam.service_account
                        WHERE organization_id = :organizationId AND status <> 'REVOKED'
                        """)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single();
    }

    /** Insert a new allowed-source declaration. */
    public void insertSource(AllowedSource source) {
        jdbc.sql("""
                        INSERT INTO iam.service_account_allowed_source (
                            id, service_account_id, cidr, note, status, reason,
                            created_at, updated_at, version)
                        VALUES (:id, :serviceAccountId, :cidr, :note, :status, :reason,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", source.id())
                .param("serviceAccountId", source.serviceAccountId())
                .param("cidr", source.cidr())
                .param("note", source.note())
                .param("status", source.status().name())
                .param("reason", source.reason())
                .param("createdAt", Timestamp.from(source.createdAt()))
                .param("updatedAt", Timestamp.from(source.updatedAt()))
                .param("version", source.version())
                .update();
    }

    /** Apply a versioned source update; false means the version was stale. */
    public boolean updateSource(AllowedSource source, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE iam.service_account_allowed_source
                        SET note = :note, status = :status, reason = :reason,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("note", source.note())
                .param("status", source.status().name())
                .param("reason", source.reason())
                .param("updatedAt", Timestamp.from(source.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", source.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one allowed-source declaration. */
    public Optional<AllowedSource> findSourceById(UUID id) {
        return jdbc.sql("SELECT * FROM iam.service_account_allowed_source WHERE id = :id")
                .param("id", id)
                .query(ServiceAccountRepository::mapSource)
                .optional();
    }

    /** Load the active declaration of one source, if any. */
    public Optional<AllowedSource> findActiveSource(UUID serviceAccountId, String cidr) {
        return jdbc.sql("""
                        SELECT * FROM iam.service_account_allowed_source
                        WHERE service_account_id = :serviceAccountId AND cidr = :cidr
                          AND status = 'ACTIVE'
                        """)
                .param("serviceAccountId", serviceAccountId)
                .param("cidr", cidr)
                .query(ServiceAccountRepository::mapSource)
                .optional();
    }

    /** List a service account's declarations, active first, then by source. */
    public List<AllowedSource> listSources(UUID serviceAccountId) {
        return jdbc.sql("""
                        SELECT * FROM iam.service_account_allowed_source
                        WHERE service_account_id = :serviceAccountId
                        ORDER BY status, cidr, created_at
                        """)
                .param("serviceAccountId", serviceAccountId)
                .query(ServiceAccountRepository::mapSource)
                .list();
    }

    private static ServiceAccount map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp lastUsedAt = row.getTimestamp("last_used_at");
        return new ServiceAccount(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("purpose"),
                row.getString("owner_label"),
                row.getTimestamp("expires_at").toInstant(),
                ServiceAccountStatus.valueOf(row.getString("status")),
                row.getString("disabled_reason"),
                lastUsedAt == null ? null : lastUsedAt.toInstant(),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }

    private static AllowedSource mapSource(ResultSet row, int rowNumber) throws SQLException {
        return new AllowedSource(
                row.getObject("id", UUID.class),
                row.getObject("service_account_id", UUID.class),
                row.getString("cidr"),
                row.getString("note"),
                AllowedSourceStatus.valueOf(row.getString("status")),
                row.getString("reason"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
