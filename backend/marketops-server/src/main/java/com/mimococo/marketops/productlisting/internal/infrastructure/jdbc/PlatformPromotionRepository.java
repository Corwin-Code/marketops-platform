package com.mimococo.marketops.productlisting.internal.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The identity of a store's marketplace promotions, as its promotion snapshots recorded them.
 *
 * <p>A promotion is current while the store's newest snapshot names it and it has not ended; an
 * older snapshot's promotion keeps its row and simply stops being current.
 */
@Repository
public class PlatformPromotionRepository {

    private final JdbcClient jdbc;

    PlatformPromotionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record that a snapshot named a promotion, and return its identifier. An older snapshot
     * arriving late moves nothing forward.
     */
    public UUID upsert(UUID id, UUID organizationId, UUID storeId, String nativePromotionKey, Instant endsAt,
                       Instant observedAt, Instant now) {
        return jdbc.sql("""
                        INSERT INTO core.platform_promotion (
                            id, organization_id, store_id, native_promotion_key, ends_at,
                            first_observed_at, last_observed_at, created_at, updated_at)
                        VALUES (:id, :organizationId, :storeId, :nativePromotionKey, :endsAt,
                            :observedAt, :observedAt, :now, :now)
                        ON CONFLICT (store_id, native_promotion_key) DO UPDATE
                        SET ends_at = CASE WHEN EXCLUDED.last_observed_at >= core.platform_promotion.last_observed_at
                                           THEN EXCLUDED.ends_at ELSE core.platform_promotion.ends_at END,
                            first_observed_at = LEAST(core.platform_promotion.first_observed_at,
                                                      EXCLUDED.first_observed_at),
                            last_observed_at = GREATEST(core.platform_promotion.last_observed_at,
                                                        EXCLUDED.last_observed_at),
                            updated_at = EXCLUDED.updated_at
                        RETURNING id
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("nativePromotionKey", nativePromotionKey)
                .param("endsAt", endsAt == null ? null : Timestamp.from(endsAt))
                .param("observedAt", Timestamp.from(observedAt))
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .single();
    }

    /** The identifier of a recorded promotion of a store. */
    public Optional<UUID> find(UUID storeId, String nativePromotionKey) {
        return jdbc.sql("""
                        SELECT id FROM core.platform_promotion
                         WHERE store_id = :storeId AND native_promotion_key = :nativePromotionKey
                        """)
                .param("storeId", storeId)
                .param("nativePromotionKey", nativePromotionKey)
                .query(UUID.class)
                .optional();
    }

    /** One batch of the keys of a store's current promotions, in text order. */
    public List<String> currentKeys(UUID storeId, Instant now, int offset, int limit) {
        return jdbc.sql(CURRENT + " ORDER BY promotion.native_promotion_key OFFSET :offset LIMIT :limit")
                .param("storeId", storeId)
                .param("now", Timestamp.from(now))
                .param("freshFrom", Timestamp.from(now.minus(SNAPSHOT_LIFETIME)))
                .param("offset", offset)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /** How many promotions of a store are current. */
    public int currentKeyCount(UUID storeId, Instant now) {
        return jdbc.sql("SELECT count(*) FROM (" + CURRENT + ") AS current_keys")
                .param("storeId", storeId)
                .param("now", Timestamp.from(now))
                .param("freshFrom", Timestamp.from(now.minus(SNAPSHOT_LIFETIME)))
                .query(Integer.class)
                .single();
    }

    /**
     * How long the newest promotion snapshot stays current. It is taken daily; an answer with no
     * promotion writes nothing, so an older snapshot names no current promotion.
     */
    private static final java.time.Duration SNAPSHOT_LIFETIME = java.time.Duration.ofHours(48);

    /** The promotions the store's newest snapshot named that have not ended. */
    private static final String CURRENT = """
            SELECT promotion.native_promotion_key
              FROM core.platform_promotion AS promotion
             WHERE promotion.store_id = :storeId
               AND promotion.last_observed_at = (SELECT max(newest.last_observed_at)
                                                   FROM core.platform_promotion AS newest
                                                  WHERE newest.store_id = :storeId)
               AND promotion.last_observed_at >= :freshFrom
               AND (promotion.ends_at IS NULL OR promotion.ends_at > :now)
            """;
}
