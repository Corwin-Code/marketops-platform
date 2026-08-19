package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.LegalEntity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code core.legal_entity}. */
@Repository
public class LegalEntityRepository {

    private final JdbcClient jdbc;

    LegalEntityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new legal entity. */
    public void insert(LegalEntity legalEntity) {
        jdbc.sql("""
                        INSERT INTO core.legal_entity (
                            id, organization_id, code, display_name, registered_name,
                            country_code, status, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :code, :displayName, :registeredName,
                            :countryCode, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", legalEntity.id())
                .param("organizationId", legalEntity.organizationId())
                .param("code", legalEntity.code())
                .param("displayName", legalEntity.displayName())
                .param("registeredName", legalEntity.registeredName())
                .param("countryCode", legalEntity.countryCode())
                .param("status", legalEntity.status().name())
                .param("createdAt", Timestamp.from(legalEntity.createdAt()))
                .param("updatedAt", Timestamp.from(legalEntity.updatedAt()))
                .param("version", legalEntity.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(LegalEntity legalEntity, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE core.legal_entity
                        SET display_name = :displayName, registered_name = :registeredName,
                            country_code = :countryCode, status = :status,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", legalEntity.displayName())
                .param("registeredName", legalEntity.registeredName())
                .param("countryCode", legalEntity.countryCode())
                .param("status", legalEntity.status().name())
                .param("updatedAt", Timestamp.from(legalEntity.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", legalEntity.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one legal entity. */
    public Optional<LegalEntity> findById(UUID id) {
        return jdbc.sql("SELECT * FROM core.legal_entity WHERE id = :id")
                .param("id", id)
                .query(LegalEntityRepository::map)
                .optional();
    }

    /** Load one legal entity by organization and business code. */
    public Optional<LegalEntity> findByCode(UUID organizationId, String code) {
        return jdbc.sql("""
                        SELECT * FROM core.legal_entity
                        WHERE organization_id = :organizationId AND code = :code
                        """)
                .param("organizationId", organizationId)
                .param("code", code)
                .query(LegalEntityRepository::map)
                .optional();
    }

    /** List an organization's legal entities ordered by code. */
    public List<LegalEntity> list(UUID organizationId, String afterCode, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.legal_entity
                        WHERE organization_id = :organizationId
                          AND (CAST(:afterCode AS text) IS NULL OR code > :afterCode)
                        ORDER BY code
                        LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("afterCode", afterCode)
                .param("pageLimit", limit)
                .query(LegalEntityRepository::map)
                .list();
    }

    /** Count an organization's legal entities that are not retired. */
    public long countNotRetired(UUID organizationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM core.legal_entity
                        WHERE organization_id = :organizationId AND status <> 'RETIRED'
                        """)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single();
    }

    private static LegalEntity map(ResultSet row, int rowNumber) throws SQLException {
        return new LegalEntity(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("registered_name"),
                row.getString("country_code"),
                EntityStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
