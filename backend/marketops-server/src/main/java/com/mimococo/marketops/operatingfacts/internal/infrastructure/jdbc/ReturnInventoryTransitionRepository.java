package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only returned-inventory transport/QC/re-entry ledger. */
@Repository
public class ReturnInventoryTransitionRepository {

    private final JdbcClient jdbc;

    public ReturnInventoryTransitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ReturnContext> context(UUID returnFactId, UUID organizationId) {
        return jdbc.sql("""
                        SELECT returned.organization_id, returned.store_id, returned.return_kind,
                               returned.quantity, mapping.product_variant_id
                          FROM ledger.return_fact returned
                          LEFT JOIN LATERAL (
                              SELECT one.product_variant_id
                                FROM core.listing_mapping one
                               WHERE one.organization_id = returned.organization_id
                                 AND one.platform_listing_variant_id =
                                     returned.platform_listing_variant_id
                                 AND one.status = 'ACTIVE'
                                 AND one.effective_from <= returned.occurred_at
                                 AND (one.effective_to IS NULL
                                      OR one.effective_to > returned.occurred_at)
                               ORDER BY one.effective_from DESC LIMIT 1
                          ) mapping ON true
                         WHERE returned.id = :returnFactId
                           AND returned.organization_id = :organizationId
                        """)
                .param("returnFactId", returnFactId).param("organizationId", organizationId)
                .query((rows, number) -> new ReturnContext(returnFactId,
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("store_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getString("return_kind"), rows.getInt("quantity")))
                .optional();
    }

    public Optional<Transition> latest(UUID returnFactId, UUID organizationId) {
        return jdbc.sql("""
                        SELECT transition.*
                          FROM ledger.return_inventory_transition transition
                         WHERE transition.return_fact_id = :returnFactId
                           AND transition.organization_id = :organizationId
                           AND NOT EXISTS (
                               SELECT 1 FROM ledger.return_inventory_transition successor
                                WHERE successor.supersedes_transition_id = transition.id)
                         LIMIT 1
                        """)
                .param("returnFactId", returnFactId).param("organizationId", organizationId)
                .query((rows, number) -> new Transition(rows.getObject("id", UUID.class),
                        rows.getObject("return_fact_id", UUID.class),
                        rows.getObject("organization_id", UUID.class),
                        rows.getObject("product_variant_id", UUID.class),
                        rows.getObject("warehouse_id", UUID.class), rows.getString("state"),
                        rows.getInt("quantity"), rows.getString("quality_disposition"),
                        rows.getString("evidence_reference"),
                        rows.getObject("actor_user_id", UUID.class),
                        rows.getTimestamp("occurred_at").toInstant(),
                        rows.getTimestamp("recorded_at").toInstant(),
                        rows.getObject("supersedes_transition_id", UUID.class)))
                .optional();
    }

    public void insert(Transition transition) {
        jdbc.sql("""
                        INSERT INTO ledger.return_inventory_transition
                            (id, organization_id, return_fact_id, product_variant_id,
                             warehouse_id, state, quantity, quality_disposition,
                             evidence_reference, actor_user_id, occurred_at, recorded_at,
                             supersedes_transition_id)
                        VALUES (:id, :organizationId, :returnFactId, :productVariantId,
                                :warehouseId, :state, :quantity, :qualityDisposition,
                                :evidenceReference, :actorUserId, :occurredAt, :recordedAt,
                                :supersedesTransitionId)
                        """)
                .param("id", transition.id())
                .param("organizationId", transition.organizationId())
                .param("returnFactId", transition.returnFactId())
                .param("productVariantId", transition.productVariantId())
                .param("warehouseId", transition.warehouseId()).param("state", transition.state())
                .param("quantity", transition.quantity())
                .param("qualityDisposition", transition.qualityDisposition())
                .param("evidenceReference", transition.evidenceReference())
                .param("actorUserId", transition.actorUserId())
                .param("occurredAt", Timestamp.from(transition.occurredAt()))
                .param("recordedAt", Timestamp.from(transition.recordedAt()))
                .param("supersedesTransitionId", transition.supersedesTransitionId())
                .update();
    }

    public record ReturnContext(UUID returnFactId, UUID organizationId, UUID storeId,
                                UUID productVariantId, String returnKind, int quantity) {
    }

    public record Transition(UUID id, UUID returnFactId, UUID organizationId,
                             UUID productVariantId, UUID warehouseId, String state, int quantity,
                             String qualityDisposition, String evidenceReference,
                             UUID actorUserId, Instant occurredAt, Instant recordedAt,
                             UUID supersedesTransitionId) {
    }
}
