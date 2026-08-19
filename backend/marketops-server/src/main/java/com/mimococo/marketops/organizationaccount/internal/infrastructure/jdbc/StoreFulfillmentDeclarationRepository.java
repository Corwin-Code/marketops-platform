package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import com.mimococo.marketops.organizationaccount.internal.domain.AssociationStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreFulfillmentDeclaration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code core.store_fulfillment_declaration}. */
@Repository
public class StoreFulfillmentDeclarationRepository {

    private final JdbcClient jdbc;

    StoreFulfillmentDeclarationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new declaration. */
    public void insert(StoreFulfillmentDeclaration declaration) {
        jdbc.sql("""
                        INSERT INTO core.store_fulfillment_declaration (
                            id, organization_id, store_id, fulfillment_mode_code,
                            effective_from, effective_to, status, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :storeId, :modeCode,
                            :effectiveFrom, :effectiveTo, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", declaration.id())
                .param("organizationId", declaration.organizationId())
                .param("storeId", declaration.storeId())
                .param("modeCode", declaration.fulfillmentModeCode())
                .param("effectiveFrom", Timestamp.from(declaration.effectiveFrom()))
                .param("effectiveTo",
                        declaration.effectiveTo() == null ? null : Timestamp.from(declaration.effectiveTo()))
                .param("status", declaration.status().name())
                .param("createdAt", Timestamp.from(declaration.createdAt()))
                .param("updatedAt", Timestamp.from(declaration.updatedAt()))
                .param("version", declaration.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(StoreFulfillmentDeclaration declaration, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE core.store_fulfillment_declaration
                        SET effective_from = :effectiveFrom, effective_to = :effectiveTo,
                            status = :status, updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("effectiveFrom", Timestamp.from(declaration.effectiveFrom()))
                .param("effectiveTo",
                        declaration.effectiveTo() == null ? null : Timestamp.from(declaration.effectiveTo()))
                .param("status", declaration.status().name())
                .param("updatedAt", Timestamp.from(declaration.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", declaration.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one declaration. */
    public Optional<StoreFulfillmentDeclaration> findById(UUID id) {
        return jdbc.sql("SELECT * FROM core.store_fulfillment_declaration WHERE id = :id")
                .param("id", id)
                .query(StoreFulfillmentDeclarationRepository::map)
                .optional();
    }

    /**
     * Whether another active declaration of the same store and mode overlaps the
     * candidate interval; the exclusion constraint decides races.
     */
    public boolean overlapsActive(UUID storeId,
                                  String modeCode,
                                  Timestamp from,
                                  Timestamp to,
                                  UUID excludeId) {
        Long overlapping = jdbc.sql("""
                        SELECT count(*) FROM core.store_fulfillment_declaration
                        WHERE store_id = :storeId AND fulfillment_mode_code = :modeCode
                          AND status = 'ACTIVE'
                          AND (CAST(:excludeId AS uuid) IS NULL OR id <> :excludeId)
                          AND tstzrange(effective_from, effective_to, '[)')
                              && tstzrange(:candidateFrom, :candidateTo, '[)')
                        """)
                .param("storeId", storeId)
                .param("modeCode", modeCode)
                .param("excludeId", excludeId)
                .param("candidateFrom", from)
                .param("candidateTo", to)
                .query(Long.class)
                .single();
        return overlapping > 0;
    }

    /** List a store's declarations, newest validity first. */
    public List<StoreFulfillmentDeclaration> listByStore(UUID storeId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.store_fulfillment_declaration
                        WHERE store_id = :storeId
                        ORDER BY effective_from DESC, id DESC
                        LIMIT :pageLimit
                        """)
                .param("storeId", storeId)
                .param("pageLimit", limit)
                .query(StoreFulfillmentDeclarationRepository::map)
                .list();
    }

    /** Count a store's active declarations. */
    public long countActiveByStore(UUID storeId) {
        return jdbc.sql("""
                        SELECT count(*) FROM core.store_fulfillment_declaration
                        WHERE store_id = :storeId AND status = 'ACTIVE'
                        """)
                .param("storeId", storeId)
                .query(Long.class)
                .single();
    }

    private static StoreFulfillmentDeclaration map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp effectiveTo = row.getTimestamp("effective_to");
        return new StoreFulfillmentDeclaration(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("store_id", UUID.class),
                row.getString("fulfillment_mode_code"),
                row.getTimestamp("effective_from").toInstant(),
                effectiveTo == null ? null : effectiveTo.toInstant(),
                AssociationStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
