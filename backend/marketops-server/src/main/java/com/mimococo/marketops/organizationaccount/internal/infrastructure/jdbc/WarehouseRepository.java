package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.Warehouse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code core.warehouse}. */
@Repository
public class WarehouseRepository {

    private final JdbcClient jdbc;

    WarehouseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new warehouse. */
    public void insert(Warehouse warehouse) {
        jdbc.sql("""
                        INSERT INTO core.warehouse (
                            id, organization_id, legal_entity_id, code, display_name,
                            timezone, status, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :legalEntityId, :code, :displayName,
                            :timezone, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", warehouse.id())
                .param("organizationId", warehouse.organizationId())
                .param("legalEntityId", warehouse.legalEntityId())
                .param("code", warehouse.code())
                .param("displayName", warehouse.displayName())
                .param("timezone", warehouse.timezone())
                .param("status", warehouse.status().name())
                .param("createdAt", Timestamp.from(warehouse.createdAt()))
                .param("updatedAt", Timestamp.from(warehouse.updatedAt()))
                .param("version", warehouse.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(Warehouse warehouse, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE core.warehouse
                        SET display_name = :displayName, timezone = :timezone,
                            status = :status, updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", warehouse.displayName())
                .param("timezone", warehouse.timezone())
                .param("status", warehouse.status().name())
                .param("updatedAt", Timestamp.from(warehouse.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", warehouse.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one warehouse. */
    public Optional<Warehouse> findById(UUID id) {
        return jdbc.sql("SELECT * FROM core.warehouse WHERE id = :id")
                .param("id", id)
                .query(WarehouseRepository::map)
                .optional();
    }

    /** Load one warehouse by organization and business code. */
    public Optional<Warehouse> findByCode(UUID organizationId, String code) {
        return jdbc.sql("""
                        SELECT * FROM core.warehouse
                        WHERE organization_id = :organizationId AND code = :code
                        """)
                .param("organizationId", organizationId)
                .param("code", code)
                .query(WarehouseRepository::map)
                .optional();
    }

    /** List an organization's warehouses ordered by code. */
    public List<Warehouse> list(UUID organizationId, String afterCode, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.warehouse
                        WHERE organization_id = :organizationId
                          AND (CAST(:afterCode AS text) IS NULL OR code > :afterCode)
                        ORDER BY code
                        LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("afterCode", afterCode)
                .param("pageLimit", limit)
                .query(WarehouseRepository::map)
                .list();
    }

    /** Count a legal entity's warehouses that are not retired. */
    public long countNotRetiredByLegalEntity(UUID legalEntityId) {
        return jdbc.sql("""
                        SELECT count(*) FROM core.warehouse
                        WHERE legal_entity_id = :legalEntityId AND status <> 'RETIRED'
                        """)
                .param("legalEntityId", legalEntityId)
                .query(Long.class)
                .single();
    }

    private static Warehouse map(ResultSet row, int rowNumber) throws SQLException {
        return new Warehouse(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("timezone"),
                EntityStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
