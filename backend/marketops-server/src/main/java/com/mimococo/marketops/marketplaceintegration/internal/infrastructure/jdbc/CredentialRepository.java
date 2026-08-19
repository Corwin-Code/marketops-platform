package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialMetadata;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialScopeMode;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CredentialStoreScope;
import com.mimococo.marketops.marketplaceintegration.internal.domain.StoreScopeStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Relational access to {@code platform.credential_metadata} and its store-scope
 * rows.
 */
@Repository
public class CredentialRepository {

    private final JdbcClient jdbc;

    CredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert new credential metadata. */
    public void insert(CredentialMetadata credential) {
        jdbc.sql("""
                        INSERT INTO platform.credential_metadata (
                            id, organization_id, marketplace_account_id, code, display_name,
                            purpose_code, scope_mode, secret_reference, effective_from,
                            expires_at, replaces_credential_id, status, custodian_label,
                            last_used_at, verification_state, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :marketplaceAccountId, :code, :displayName,
                            :purposeCode, :scopeMode, :secretReference, :effectiveFrom,
                            :expiresAt, :replacesCredentialId, :status, :custodianLabel,
                            :lastUsedAt, :verificationState, :createdAt, :updatedAt, :version)
                        """)
                .param("id", credential.id())
                .param("organizationId", credential.organizationId())
                .param("marketplaceAccountId", credential.marketplaceAccountId())
                .param("code", credential.code())
                .param("displayName", credential.displayName())
                .param("purposeCode", credential.purposeCode())
                .param("scopeMode", credential.scopeMode().name())
                .param("secretReference", credential.secretReference())
                .param("effectiveFrom", Timestamp.from(credential.effectiveFrom()))
                .param("expiresAt", Timestamp.from(credential.expiresAt()))
                .param("replacesCredentialId", credential.replacesCredentialId())
                .param("status", credential.status().name())
                .param("custodianLabel", credential.custodianLabel())
                .param("lastUsedAt", credential.lastUsedAt() == null
                        ? null : Timestamp.from(credential.lastUsedAt()))
                .param("verificationState", credential.verificationState().name())
                .param("createdAt", Timestamp.from(credential.createdAt()))
                .param("updatedAt", Timestamp.from(credential.updatedAt()))
                .param("version", credential.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(CredentialMetadata credential, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE platform.credential_metadata
                        SET display_name = :displayName, scope_mode = :scopeMode,
                            status = :status, custodian_label = :custodianLabel,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", credential.displayName())
                .param("scopeMode", credential.scopeMode().name())
                .param("status", credential.status().name())
                .param("custodianLabel", credential.custodianLabel())
                .param("updatedAt", Timestamp.from(credential.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", credential.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one credential. */
    public Optional<CredentialMetadata> findById(UUID id) {
        return jdbc.sql("SELECT * FROM platform.credential_metadata WHERE id = :id")
                .param("id", id)
                .query(CredentialRepository::map)
                .optional();
    }

    /** Load one credential by organization and business code. */
    public Optional<CredentialMetadata> findByCode(UUID organizationId, String code) {
        return jdbc.sql("""
                        SELECT * FROM platform.credential_metadata
                        WHERE organization_id = :organizationId AND code = :code
                        """)
                .param("organizationId", organizationId)
                .param("code", code)
                .query(CredentialRepository::map)
                .optional();
    }

    /** List an account's credentials by code with a keyset cursor. */
    public List<CredentialMetadata> listByAccount(
            UUID marketplaceAccountId, String afterCode, int limit) {
        return jdbc.sql("""
                        SELECT * FROM platform.credential_metadata
                        WHERE marketplace_account_id = :marketplaceAccountId
                          AND (CAST(:afterCode AS text) IS NULL OR code > :afterCode)
                        ORDER BY code
                        LIMIT :pageLimit
                        """)
                .param("marketplaceAccountId", marketplaceAccountId)
                .param("afterCode", afterCode)
                .param("pageLimit", limit)
                .query(CredentialRepository::map)
                .list();
    }

    /** Load the live credential holding a secret reference, if any. */
    public Optional<CredentialMetadata> findLiveBySecretReference(String secretReference) {
        return jdbc.sql("""
                        SELECT * FROM platform.credential_metadata
                        WHERE secret_reference = :secretReference AND status <> 'REVOKED'
                        """)
                .param("secretReference", secretReference)
                .query(CredentialRepository::map)
                .optional();
    }

    /** Count non-revoked credentials of one marketplace account. */
    public long countNotRevokedByAccount(UUID marketplaceAccountId) {
        return jdbc.sql("""
                        SELECT count(*) FROM platform.credential_metadata
                        WHERE marketplace_account_id = :marketplaceAccountId
                          AND status <> 'REVOKED'
                        """)
                .param("marketplaceAccountId", marketplaceAccountId)
                .query(Long.class)
                .single();
    }

    /** Count non-revoked credentials of one organization. */
    public long countNotRevokedByOrganization(UUID organizationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM platform.credential_metadata
                        WHERE organization_id = :organizationId AND status <> 'REVOKED'
                        """)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single();
    }

    /** Count non-revoked credentials naming this credential as their predecessor. */
    public long countLiveReplacers(UUID credentialId) {
        return jdbc.sql("""
                        SELECT count(*) FROM platform.credential_metadata
                        WHERE replaces_credential_id = :credentialId AND status <> 'REVOKED'
                        """)
                .param("credentialId", credentialId)
                .query(Long.class)
                .single();
    }

    /** Insert a new store-scope row. */
    public void insertScope(CredentialStoreScope scope) {
        jdbc.sql("""
                        INSERT INTO platform.credential_store_scope (
                            id, credential_id, marketplace_account_id, store_id, status,
                            reason, created_at, updated_at, version)
                        VALUES (:id, :credentialId, :marketplaceAccountId, :storeId, :status,
                            :reason, :createdAt, :updatedAt, :version)
                        """)
                .param("id", scope.id())
                .param("credentialId", scope.credentialId())
                .param("marketplaceAccountId", scope.marketplaceAccountId())
                .param("storeId", scope.storeId())
                .param("status", scope.status().name())
                .param("reason", scope.reason())
                .param("createdAt", Timestamp.from(scope.createdAt()))
                .param("updatedAt", Timestamp.from(scope.updatedAt()))
                .param("version", scope.version())
                .update();
    }

    /** Apply a versioned scope update; false means the version was stale. */
    public boolean updateScope(CredentialStoreScope scope, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE platform.credential_store_scope
                        SET status = :status, reason = :reason,
                            updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("status", scope.status().name())
                .param("reason", scope.reason())
                .param("updatedAt", Timestamp.from(scope.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", scope.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one store-scope row. */
    public Optional<CredentialStoreScope> findScopeById(UUID id) {
        return jdbc.sql("SELECT * FROM platform.credential_store_scope WHERE id = :id")
                .param("id", id)
                .query(CredentialRepository::mapScope)
                .optional();
    }

    /** Load the active scope row of one credential and store, if any. */
    public Optional<CredentialStoreScope> findActiveScope(UUID credentialId, UUID storeId) {
        return jdbc.sql("""
                        SELECT * FROM platform.credential_store_scope
                        WHERE credential_id = :credentialId AND store_id = :storeId
                          AND status = 'ACTIVE'
                        """)
                .param("credentialId", credentialId)
                .param("storeId", storeId)
                .query(CredentialRepository::mapScope)
                .optional();
    }

    /** List a credential's scope rows, active first, then by store. */
    public List<CredentialStoreScope> listScopes(UUID credentialId) {
        return jdbc.sql("""
                        SELECT * FROM platform.credential_store_scope
                        WHERE credential_id = :credentialId
                        ORDER BY status, store_id, created_at
                        """)
                .param("credentialId", credentialId)
                .query(CredentialRepository::mapScope)
                .list();
    }

    /** Count a credential's active scope rows. */
    public long countActiveScopes(UUID credentialId) {
        return jdbc.sql("""
                        SELECT count(*) FROM platform.credential_store_scope
                        WHERE credential_id = :credentialId AND status = 'ACTIVE'
                        """)
                .param("credentialId", credentialId)
                .query(Long.class)
                .single();
    }

    /** Count active scope rows covering one store. */
    public long countActiveScopesByStore(UUID storeId) {
        return jdbc.sql("""
                        SELECT count(*) FROM platform.credential_store_scope
                        WHERE store_id = :storeId AND status = 'ACTIVE'
                        """)
                .param("storeId", storeId)
                .query(Long.class)
                .single();
    }

    private static CredentialMetadata map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp lastUsedAt = row.getTimestamp("last_used_at");
        return new CredentialMetadata(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getObject("marketplace_account_id", UUID.class),
                row.getString("code"),
                row.getString("display_name"),
                row.getString("purpose_code"),
                CredentialScopeMode.valueOf(row.getString("scope_mode")),
                row.getString("secret_reference"),
                row.getTimestamp("effective_from").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                row.getObject("replaces_credential_id", UUID.class),
                CredentialStatus.valueOf(row.getString("status")),
                row.getString("custodian_label"),
                lastUsedAt == null ? null : lastUsedAt.toInstant(),
                VerificationState.valueOf(row.getString("verification_state")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }

    private static CredentialStoreScope mapScope(ResultSet row, int rowNumber)
            throws SQLException {
        return new CredentialStoreScope(
                row.getObject("id", UUID.class),
                row.getObject("credential_id", UUID.class),
                row.getObject("marketplace_account_id", UUID.class),
                row.getObject("store_id", UUID.class),
                StoreScopeStatus.valueOf(row.getString("status")),
                row.getString("reason"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
