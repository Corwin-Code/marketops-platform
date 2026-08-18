package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import com.mimococo.marketops.organizationaccount.internal.domain.AssociationStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.StoreWarehouseLink;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code core.store_warehouse_link}. */
@Repository
public class StoreWarehouseLinkRepository {

    private final JdbcClient jdbc;

    StoreWarehouseLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new association. */
    public void insert(StoreWarehouseLink link) {
        jdbc.sql("""
                        INSERT INTO core.store_warehouse_link (
                            id, organization_id, store_id, warehouse_id, fulfillment_mode_code,
                            effective_from, effective_to, status, note,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :storeId, :warehouseId, :modeCode,
                            :effectiveFrom, :effectiveTo, :status, :note,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", link.id())
                .param("organizationId", link.organizationId())
                .param("storeId", link.storeId())
                .param("warehouseId", link.warehouseId())
                .param("modeCode", link.fulfillmentModeCode())
                .param("effectiveFrom", Timestamp.from(link.effectiveFrom()))
                .param("effectiveTo", link.effectiveTo() == null ? null : Timestamp.from(link.effectiveTo()))
                .param("status", link.status().name())
                .param("note", link.note())
                .param("createdAt", Timestamp.from(link.createdAt()))
                .param("updatedAt", Timestamp.from(link.updatedAt()))
                .param("version", link.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(StoreWarehouseLink link, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE core.store_warehouse_link
                        SET effective_from = :effectiveFrom, effective_to = :effectiveTo,
                            status = :status, note = :note,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("effectiveFrom", Timestamp.from(link.effectiveFrom()))
                .param("effectiveTo", link.effectiveTo() == null ? null : Timestamp.from(link.effectiveTo()))
                .param("status", link.status().name())
                .param("note", link.note())
                .param("updatedAt", Timestamp.from(link.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", link.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one association. */
    public Optional<StoreWarehouseLink> findById(UUID id) {
        return jdbc.sql("SELECT * FROM core.store_warehouse_link WHERE id = :id")
                .param("id", id)
                .query(StoreWarehouseLinkRepository::map)
                .optional();
    }

    /**
     * Whether another active association of the same store, warehouse and mode
     * overlaps the candidate interval. The database exclusion constraint decides
     * races; this look-ahead exists to answer with the stable error code.
     */
    public boolean overlapsActive(UUID storeId,
                                  UUID warehouseId,
                                  String modeCode,
                                  Timestamp from,
                                  Timestamp to,
                                  UUID excludeId) {
        Long overlapping = jdbc.sql("""
                        SELECT count(*) FROM core.store_warehouse_link
                        WHERE store_id = :storeId AND warehouse_id = :warehouseId
                          AND fulfillment_mode_code = :modeCode AND status = 'ACTIVE'
                          AND (CAST(:excludeId AS uuid) IS NULL OR id <> :excludeId)
                          AND tstzrange(effective_from, effective_to, '[)')
                              && tstzrange(:candidateFrom, :candidateTo, '[)')
                        """)
                .param("storeId", storeId)
                .param("warehouseId", warehouseId)
                .param("modeCode", modeCode)
                .param("excludeId", excludeId)
                .param("candidateFrom", from)
                .param("candidateTo", to)
                .query(Long.class)
                .single();
        return overlapping > 0;
    }

    /** List a store's associations, newest validity first. */
    public List<StoreWarehouseLink> listByStore(UUID storeId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.store_warehouse_link
                        WHERE store_id = :storeId
                        ORDER BY effective_from DESC, id DESC
                        LIMIT :pageLimit
                        """)
                .param("storeId", storeId)
                .param("pageLimit", limit)
                .query(StoreWarehouseLinkRepository::map)
                .list();
    }

    /** Count active associations touching a store. */
    public long countActiveByStore(UUID storeId) {
        return jdbc.sql("""
                        SELECT count(*) FROM core.store_warehouse_link
                        WHERE store_id = :storeId AND status = 'ACTIVE'
                        """)
                .param("storeId", storeId)
                .query(Long.class)
                .single();
    }

    /** Count active associations touching a warehouse. */
    public long countActiveByWarehouse(UUID warehouseId) {
        return jdbc.sql("""
                        SELECT count(*) FROM core.store_warehouse_link
                        WHERE warehouse_id = :warehouseId AND status = 'ACTIVE'
                        """)
                .param("warehouseId", warehouseId)
                .query(Long.class)
                .single();
    }

    private static StoreWarehouseLink map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp effectiveTo = row.getTimestamp("effective_to");
        return new StoreWarehouseLink(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("store_id", UUID.class),
                row.getObject("warehouse_id", UUID.class),
                row.getString("fulfillment_mode_code"),
                row.getTimestamp("effective_from").toInstant(),
                effectiveTo == null ? null : effectiveTo.toInstant(),
                AssociationStatus.valueOf(row.getString("status")),
                row.getString("note"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
