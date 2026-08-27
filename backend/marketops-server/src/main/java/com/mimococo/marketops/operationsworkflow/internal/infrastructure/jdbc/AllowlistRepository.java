package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The exact entities a real platform write may touch.
 *
 * <p>The list is positive. An entity that is not on it is not eligible, so
 * widening real exposure is always a deliberate, attributed act with a stated
 * window rather than a side effect of some other change.
 *
 * <p>Nothing here grants a write on its own. The write gate reads this list as
 * one of several conditions, so removing an entity closes the door immediately
 * while adding one only makes a write possible if every other condition also
 * holds.
 */
@Repository
public class AllowlistRepository {

    private final JdbcClient jdbc;

    AllowlistRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Put one store, or one listing variant within it, on the list. */
    public void insert(UUID id, UUID organizationId, String platformCode, UUID storeId,
                       UUID platformListingVariantId, Instant validFrom, Instant validUntil,
                       UUID grantedByUserId, String reason, Instant now) {
        jdbc.sql("""
                        INSERT INTO ops.pilot_allowlist_entry (
                            id, organization_id, capability_code, platform_code, store_id,
                            platform_listing_variant_id, valid_from, valid_until, status,
                            granted_by_user_id, reason, created_at, updated_at, version)
                        VALUES (:id, :organizationId, 'PRICE_CHANGE', :platformCode, :storeId,
                            :platformListingVariantId, :validFrom, :validUntil, 'ACTIVE',
                            :grantedByUserId, :reason, :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("platformCode", platformCode)
                .param("storeId", storeId)
                .param("platformListingVariantId", platformListingVariantId)
                .param("validFrom", Timestamp.from(validFrom))
                .param("validUntil", Timestamp.from(validUntil))
                .param("grantedByUserId", grantedByUserId)
                .param("reason", reason)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Take an entry off the list. */
    public boolean revoke(UUID id, String revokedReason, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.pilot_allowlist_entry
                        SET status = 'REVOKED', revoked_reason = :revokedReason,
                            updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion AND status = 'ACTIVE'
                        """)
                .param("revokedReason", revokedReason)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Whether one listing variant is covered at one instant. */
    public boolean covers(UUID storeId, UUID platformListingVariantId, Instant at) {
        return !jdbc.sql("""
                        SELECT id FROM ops.pilot_allowlist_entry
                         WHERE capability_code = 'PRICE_CHANGE' AND status = 'ACTIVE'
                           AND store_id = :storeId
                           AND (platform_listing_variant_id IS NULL
                                OR platform_listing_variant_id = :platformListingVariantId)
                           AND valid_from <= :at AND valid_until > :at
                         LIMIT 1
                        """)
                .param("storeId", storeId)
                .param("platformListingVariantId", platformListingVariantId)
                .param("at", Timestamp.from(at))
                .query(UUID.class)
                .list()
                .isEmpty();
    }

    /** Every entry of one organization, newest first. */
    public List<AllowlistRow> list(UUID organizationId) {
        return jdbc.sql("""
                        SELECT id, platform_code, store_id, platform_listing_variant_id,
                               valid_from, valid_until, status, reason, revoked_reason, version
                          FROM ops.pilot_allowlist_entry
                         WHERE organization_id = :organizationId
                         ORDER BY created_at DESC
                        """)
                .param("organizationId", organizationId)
                .query(AllowlistRepository::map)
                .list();
    }

    private static AllowlistRow map(ResultSet rows, int rowNumber) throws SQLException {
        return new AllowlistRow(
                rows.getObject("id", UUID.class),
                rows.getString("platform_code"),
                rows.getObject("store_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                rows.getTimestamp("valid_from").toInstant(),
                rows.getTimestamp("valid_until").toInstant(),
                rows.getString("status"),
                rows.getString("reason"),
                rows.getString("revoked_reason"),
                rows.getLong("version"));
    }

    /**
     * One allowlist entry.
     *
     * @param id the entry
     * @param platformCode marketplace it covers
     * @param storeId store it covers
     * @param platformListingVariantId listing variant it covers, or {@code null} for the store
     * @param validFrom when it starts
     * @param validUntil when it stops
     * @param status whether it is live
     * @param reason why it was granted
     * @param revokedReason why it was withdrawn, or {@code null}
     * @param version optimistic-lock version
     */
    public record AllowlistRow(UUID id, String platformCode, UUID storeId,
                               UUID platformListingVariantId, Instant validFrom,
                               Instant validUntil, String status, String reason,
                               String revokedReason, long version) {
    }
}
