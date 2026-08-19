package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.Store;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code core.store}. */
@Repository
public class StoreRepository {

    private final JdbcClient jdbc;

    StoreRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new store. */
    public void insert(Store store) {
        jdbc.sql("""
                        INSERT INTO core.store (
                            id, organization_id, marketplace_account_id, code, display_name,
                            native_store_key, timezone, currency_code, status,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :accountId, :code, :displayName,
                            :nativeStoreKey, :timezone, :currencyCode, :status,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", store.id())
                .param("organizationId", store.organizationId())
                .param("accountId", store.marketplaceAccountId())
                .param("code", store.code())
                .param("displayName", store.displayName())
                .param("nativeStoreKey", store.nativeStoreKey())
                .param("timezone", store.timezone())
                .param("currencyCode", store.currencyCode())
                .param("status", store.status().name())
                .param("createdAt", Timestamp.from(store.createdAt()))
                .param("updatedAt", Timestamp.from(store.updatedAt()))
                .param("version", store.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(Store store, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE core.store
                        SET display_name = :displayName, native_store_key = :nativeStoreKey,
                            timezone = :timezone, currency_code = :currencyCode,
                            status = :status, updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", store.displayName())
                .param("nativeStoreKey", store.nativeStoreKey())
                .param("timezone", store.timezone())
                .param("currencyCode", store.currencyCode())
                .param("status", store.status().name())
                .param("updatedAt", Timestamp.from(store.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", store.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one store. */
    public Optional<Store> findById(UUID id) {
        return jdbc.sql("SELECT * FROM core.store WHERE id = :id")
                .param("id", id)
                .query(StoreRepository::map)
                .optional();
    }

    /** Load one store by organization and business code. */
    public Optional<Store> findByCode(UUID organizationId, String code) {
        return jdbc.sql("""
                        SELECT * FROM core.store
                        WHERE organization_id = :organizationId AND code = :code
                        """)
                .param("organizationId", organizationId)
                .param("code", code)
                .query(StoreRepository::map)
                .optional();
    }

    /** Load the live store registered under a platform-native key, if any. */
    public Optional<Store> findLiveByNativeKey(UUID marketplaceAccountId, String nativeKey) {
        return jdbc.sql("""
                        SELECT * FROM core.store
                        WHERE marketplace_account_id = :accountId
                          AND native_store_key = :nativeKey
                          AND status <> 'RETIRED'
                        """)
                .param("accountId", marketplaceAccountId)
                .param("nativeKey", nativeKey)
                .query(StoreRepository::map)
                .optional();
    }

    /** List an organization's stores ordered by code. */
    public List<Store> list(UUID organizationId, String afterCode, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.store
                        WHERE organization_id = :organizationId
                          AND (CAST(:afterCode AS text) IS NULL OR code > :afterCode)
                        ORDER BY code
                        LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("afterCode", afterCode)
                .param("pageLimit", limit)
                .query(StoreRepository::map)
                .list();
    }

    /** Count an account's stores that are not retired. */
    public long countNotRetiredByAccount(UUID marketplaceAccountId) {
        return jdbc.sql("""
                        SELECT count(*) FROM core.store
                        WHERE marketplace_account_id = :accountId AND status <> 'RETIRED'
                        """)
                .param("accountId", marketplaceAccountId)
                .query(Long.class)
                .single();
    }

    private static Store map(ResultSet row, int rowNumber) throws SQLException {
        return new Store(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("marketplace_account_id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("native_store_key"),
                row.getString("timezone"),
                row.getString("currency_code"),
                EntityStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
