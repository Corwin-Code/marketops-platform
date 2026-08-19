package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.Organization;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code core.organization}. */
@Repository
public class OrganizationRepository {

    private final JdbcClient jdbc;

    OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new organization. */
    public void insert(Organization organization) {
        jdbc.sql("""
                        INSERT INTO core.organization (
                            id, code, display_name, default_timezone, default_currency_code,
                            status, created_at, updated_at, version)
                        VALUES (:id, :code, :displayName, :timezone, :currency,
                            :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", organization.id())
                .param("code", organization.code())
                .param("displayName", organization.displayName())
                .param("timezone", organization.defaultTimezone())
                .param("currency", organization.defaultCurrencyCode())
                .param("status", organization.status().name())
                .param("createdAt", Timestamp.from(organization.createdAt()))
                .param("updatedAt", Timestamp.from(organization.updatedAt()))
                .param("version", organization.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(Organization organization, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE core.organization
                        SET display_name = :displayName, default_timezone = :timezone,
                            default_currency_code = :currency, status = :status,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", organization.displayName())
                .param("timezone", organization.defaultTimezone())
                .param("currency", organization.defaultCurrencyCode())
                .param("status", organization.status().name())
                .param("updatedAt", Timestamp.from(organization.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", organization.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one organization. */
    public Optional<Organization> findById(UUID id) {
        return jdbc.sql("SELECT * FROM core.organization WHERE id = :id")
                .param("id", id)
                .query(OrganizationRepository::map)
                .optional();
    }

    /** Load one organization by its business code. */
    public Optional<Organization> findByCode(String code) {
        return jdbc.sql("SELECT * FROM core.organization WHERE code = :code")
                .param("code", code)
                .query(OrganizationRepository::map)
                .optional();
    }

    /** List organizations ordered by code, starting after {@code afterCode}. */
    public List<Organization> list(String afterCode, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.organization
                        WHERE (CAST(:afterCode AS text) IS NULL OR code > :afterCode)
                        ORDER BY code
                        LIMIT :pageLimit
                        """)
                .param("afterCode", afterCode)
                .param("pageLimit", limit)
                .query(OrganizationRepository::map)
                .list();
    }

    private static Organization map(ResultSet row, int rowNumber) throws SQLException {
        return new Organization(
                row.getObject("id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("default_timezone"),
                row.getString("default_currency_code"),
                EntityStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
