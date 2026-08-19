package com.mimococo.marketops.organizationaccount.internal.infrastructure.jdbc;

import com.mimococo.marketops.organizationaccount.internal.domain.EntityStatus;
import com.mimococo.marketops.organizationaccount.internal.domain.MarketplaceAccount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code core.marketplace_account}. */
@Repository
public class MarketplaceAccountRepository {

    private final JdbcClient jdbc;

    MarketplaceAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new marketplace account. */
    public void insert(MarketplaceAccount account) {
        jdbc.sql("""
                        INSERT INTO core.marketplace_account (
                            id, organization_id, legal_entity_id, platform_code, code,
                            display_name, native_account_key, status, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :legalEntityId, :platformCode, :code,
                            :displayName, :nativeAccountKey, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", account.id())
                .param("organizationId", account.organizationId())
                .param("legalEntityId", account.legalEntityId())
                .param("platformCode", account.platformCode())
                .param("code", account.code())
                .param("displayName", account.displayName())
                .param("nativeAccountKey", account.nativeAccountKey())
                .param("status", account.status().name())
                .param("createdAt", Timestamp.from(account.createdAt()))
                .param("updatedAt", Timestamp.from(account.updatedAt()))
                .param("version", account.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(MarketplaceAccount account, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE core.marketplace_account
                        SET display_name = :displayName, native_account_key = :nativeAccountKey,
                            status = :status, updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", account.displayName())
                .param("nativeAccountKey", account.nativeAccountKey())
                .param("status", account.status().name())
                .param("updatedAt", Timestamp.from(account.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", account.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one account. */
    public Optional<MarketplaceAccount> findById(UUID id) {
        return jdbc.sql("SELECT * FROM core.marketplace_account WHERE id = :id")
                .param("id", id)
                .query(MarketplaceAccountRepository::map)
                .optional();
    }

    /** Load one account by organization and business code. */
    public Optional<MarketplaceAccount> findByCode(UUID organizationId, String code) {
        return jdbc.sql("""
                        SELECT * FROM core.marketplace_account
                        WHERE organization_id = :organizationId AND code = :code
                        """)
                .param("organizationId", organizationId)
                .param("code", code)
                .query(MarketplaceAccountRepository::map)
                .optional();
    }

    /** Load the live account registered under a platform-native key, if any. */
    public Optional<MarketplaceAccount> findLiveByNativeKey(String platformCode, String nativeKey) {
        return jdbc.sql("""
                        SELECT * FROM core.marketplace_account
                        WHERE platform_code = :platformCode
                          AND native_account_key = :nativeKey
                          AND status <> 'RETIRED'
                        """)
                .param("platformCode", platformCode)
                .param("nativeKey", nativeKey)
                .query(MarketplaceAccountRepository::map)
                .optional();
    }

    /** List an organization's accounts ordered by code. */
    public List<MarketplaceAccount> list(UUID organizationId, String afterCode, int limit) {
        return jdbc.sql("""
                        SELECT * FROM core.marketplace_account
                        WHERE organization_id = :organizationId
                          AND (CAST(:afterCode AS text) IS NULL OR code > :afterCode)
                        ORDER BY code
                        LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("afterCode", afterCode)
                .param("pageLimit", limit)
                .query(MarketplaceAccountRepository::map)
                .list();
    }

    /** Count a legal entity's accounts that are not retired. */
    public long countNotRetiredByLegalEntity(UUID legalEntityId) {
        return jdbc.sql("""
                        SELECT count(*) FROM core.marketplace_account
                        WHERE legal_entity_id = :legalEntityId AND status <> 'RETIRED'
                        """)
                .param("legalEntityId", legalEntityId)
                .query(Long.class)
                .single();
    }

    private static MarketplaceAccount map(ResultSet row, int rowNumber) throws SQLException {
        return new MarketplaceAccount(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("legal_entity_id", UUID.class),
                row.getString("platform_code"),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("native_account_key"),
                EntityStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
