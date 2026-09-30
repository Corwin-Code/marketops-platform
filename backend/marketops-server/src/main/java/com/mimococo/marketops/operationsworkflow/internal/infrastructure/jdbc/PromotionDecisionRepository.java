package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Promotion decisions taken by hand in the marketplace back office; appended, never changed. */
@Repository
public class PromotionDecisionRepository {

    private final JdbcClient jdbc;

    PromotionDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record one decision; nothing is written when the promotion is not one of the store's.
     *
     * @return whether the decision was recorded
     */
    public boolean insert(UUID id, UUID organizationId, UUID storeId, UUID promotionId, UUID listingVariantId,
                          String decision, BigDecimal actionPrice, String currencyCode, String note,
                          UUID decidedByUserId, Instant decidedAt) {
        return jdbc.sql("""
                        INSERT INTO ops.promotion_decision (
                            id, organization_id, store_id, platform_promotion_id, platform_listing_variant_id,
                            decision, action_price, currency_code, note, decided_by_user_id, decided_at)
                        SELECT :id, promotion.organization_id, promotion.store_id, promotion.id, :listingVariantId,
                               :decision, :actionPrice, :currencyCode, :note, :decidedByUserId, :decidedAt
                          FROM core.platform_promotion AS promotion
                         WHERE promotion.id = :promotionId
                           AND promotion.store_id = :storeId
                           AND promotion.organization_id = :organizationId
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("promotionId", promotionId)
                .param("listingVariantId", listingVariantId)
                .param("decision", decision)
                .param("actionPrice", actionPrice)
                .param("currencyCode", currencyCode)
                .param("note", note)
                .param("decidedByUserId", decidedByUserId)
                .param("decidedAt", Timestamp.from(decidedAt))
                .update() == 1;
    }

    /** The newest decision on every product of every promotion of a store. */
    public List<DecisionRow> latest(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (platform_promotion_id, platform_listing_variant_id)
                               id, platform_promotion_id, platform_listing_variant_id, decision, action_price,
                               currency_code, note, decided_by_user_id, decided_at
                          FROM ops.promotion_decision
                         WHERE organization_id = :organizationId AND store_id = :storeId
                         ORDER BY platform_promotion_id, platform_listing_variant_id, decided_at DESC, id DESC
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new DecisionRow(
                        rows.getObject("id", UUID.class),
                        rows.getObject("platform_promotion_id", UUID.class),
                        rows.getObject("platform_listing_variant_id", UUID.class),
                        rows.getString("decision"),
                        rows.getBigDecimal("action_price"),
                        rows.getString("currency_code"),
                        rows.getString("note"),
                        rows.getObject("decided_by_user_id", UUID.class),
                        rows.getTimestamp("decided_at").toInstant()))
                .list();
    }

    /** One recorded decision. */
    public record DecisionRow(UUID decisionId, UUID promotionId, UUID listingVariantId, String decision,
                              BigDecimal actionPrice, String currencyCode, String note, UUID decidedByUserId,
                              Instant decidedAt) {
    }
}
