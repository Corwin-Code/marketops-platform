package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Decisions taken by hand about price suggestions; append-only, one per suggestion. */
@Repository
public class PriceDecisionRepository {

    private final JdbcClient jdbc;

    PriceDecisionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record one decision. */
    public void insert(UUID id, UUID organizationId, UUID storeId, UUID recommendationId, UUID listingVariantId,
                       String decision, BigDecimal appliedPrice, String currencyCode, String note,
                       UUID decidedByUserId, Instant decidedAt) {
        jdbc.sql("""
                        INSERT INTO ops.price_decision (
                            id, organization_id, store_id, recommendation_id, platform_listing_variant_id, decision,
                            applied_price, currency_code, note, decided_by_user_id, decided_at)
                        VALUES (:id, :organizationId, :storeId, :recommendationId, :listingVariantId, :decision,
                            :appliedPrice, :currencyCode, :note, :decidedBy, :decidedAt)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("recommendationId", recommendationId)
                .param("listingVariantId", listingVariantId)
                .param("decision", decision)
                .param("appliedPrice", appliedPrice)
                .param("currencyCode", currencyCode)
                .param("note", note)
                .param("decidedBy", decidedByUserId)
                .param("decidedAt", Timestamp.from(decidedAt))
                .update();
    }

    /** The store's decisions, newest first, optionally about one listing variant only. */
    public List<Decision> list(UUID organizationId, UUID storeId, UUID listingVariantId, int limit) {
        return jdbc.sql("""
                        SELECT decision.id, decision.recommendation_id, decision.platform_listing_variant_id,
                               decision.decision, decision.applied_price, decision.currency_code, decision.note,
                               decision.decided_at
                          FROM ops.price_decision AS decision
                         WHERE decision.organization_id = :organizationId AND decision.store_id = :storeId
                           AND (CAST(:listingVariantId AS uuid) IS NULL
                                OR decision.platform_listing_variant_id = CAST(:listingVariantId AS uuid))
                         ORDER BY decision.decided_at DESC, decision.id
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("listingVariantId", listingVariantId)
                .param("limit", limit)
                .query((rows, rowNumber) -> new Decision(rows.getObject("id", UUID.class),
                        rows.getObject("recommendation_id", UUID.class),
                        rows.getObject("platform_listing_variant_id", UUID.class), rows.getString("decision"),
                        rows.getBigDecimal("applied_price"), rows.getString("currency_code"), rows.getString("note"),
                        rows.getTimestamp("decided_at").toInstant()))
                .list();
    }

    /** When the newest decision about the listing variant was taken, if any. */
    public java.util.Optional<Instant> latestDecidedAt(UUID organizationId, UUID listingVariantId) {
        Timestamp latest = jdbc.sql("""
                        SELECT max(decision.decided_at)
                          FROM ops.price_decision AS decision
                         WHERE decision.organization_id = :organizationId
                           AND decision.platform_listing_variant_id = :listingVariantId
                        """)
                .param("organizationId", organizationId)
                .param("listingVariantId", listingVariantId)
                .query(Timestamp.class)
                .optional()
                .orElse(null);
        return java.util.Optional.ofNullable(latest).map(Timestamp::toInstant);
    }

    /** One recorded decision. */
    public record Decision(UUID id, UUID recommendationId, UUID listingVariantId, String decision,
                           BigDecimal appliedPrice, String currencyCode, String note, Instant decidedAt) {
    }
}
