package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Which credential a write against one store is made with.
 *
 * <p>The credential must belong to the store's own marketplace account and must
 * cover the store: either it is account-wide, or it carries a live store-scope
 * row for exactly this store. A credential scoped to another store would
 * authenticate perfectly well and change the wrong seller's price, so the scope
 * is part of the lookup rather than a check made afterwards.
 *
 * <p>A store with more than one live credential yields the longest-standing one,
 * because a newly created credential is the one most likely to be mid-rotation
 * and least likely to have been proven against a real call.
 *
 * <p>No secret is read here. The answer is an identifier; the value behind it is
 * resolved inside the adapter at the moment of use and cleared immediately
 * afterwards.
 */
@Repository
public class CredentialLookupRepository {

    private final JdbcClient jdbc;

    CredentialLookupRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The credential a write capability may be exercised with for one store.
     *
     * <p>The purpose comes from the capability rather than from a literal here.
     * It used to be {@code 'PRICE_WRITE'}, which was right when there was one
     * write capability and silently wrong the moment there were two: every
     * advertising command resolved a price credential, which the attempt gate
     * then refused because it demands {@code ADS_WRITE}. The command died at
     * FAILED_FINAL naming a stale lease, and the actual cause — the wrong
     * credential purpose — appeared nowhere.
     *
     * <p>{@code platform.capability_credential_purpose} already existed to make
     * that choice. Asking it here means a third write capability cannot
     * reintroduce the same bug by forgetting to add a branch.
     */
    public Optional<UUID> writeCredential(UUID storeId, UUID capabilityId, Instant at) {
        return jdbc.sql("""
                        SELECT credential.id
                          FROM platform.credential_metadata AS credential
                          JOIN core.marketplace_account AS account
                            ON account.id = credential.marketplace_account_id
                          JOIN core.store AS store
                            ON store.marketplace_account_id = account.id
                          JOIN platform.platform_capability AS capability
                            ON capability.platform_code = account.platform_code
                         WHERE store.id = :storeId
                           AND capability.id = :capabilityId
                           AND credential.purpose_code = platform.capability_credential_purpose(
                                   capability.capability_code, capability.read_write_class)
                           AND credential.status = 'ACTIVE'
                           AND credential.effective_from <= :at
                           AND credential.expires_at > :at
                           AND (credential.scope_mode = 'ACCOUNT'
                                OR EXISTS (
                                    SELECT 1
                                      FROM platform.credential_store_scope AS scope
                                     WHERE scope.credential_id = credential.id
                                       AND scope.store_id = store.id
                                       AND scope.status = 'ACTIVE'))
                         ORDER BY credential.created_at, credential.id
                         LIMIT 1
                        """)
                .param("storeId", storeId)
                .param("capabilityId", capabilityId)
                .param("at", Timestamp.from(at))
                .query(UUID.class)
                .optional();
    }
}
