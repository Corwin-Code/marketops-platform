package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.sql.Array;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reservations and containment, both of which exist to stop things happening.
 *
 * <p>They live together because they answer one question from two directions.
 * A reservation says nobody else may act on these variants right now; a
 * containment says nobody may act on this scope at all until people have agreed
 * it is safe again. A caller that had one without the other could stop a race
 * and still transmit into an incident, or hold an incident and still let two
 * interventions collide.
 *
 * <p>Neither is written directly. Taking, observing and releasing a reservation
 * are database functions, and the application role has no {@code INSERT} or
 * {@code UPDATE} on the table, so a service that decided to skip the overlap
 * check would have nothing to call.
 */
@Repository
public class AdvertisingContainmentRepository {

    private final JdbcClient jdbc;

    AdvertisingContainmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Take the reservation for one intervention, or return the one it holds.
     *
     * <p>Refuses rather than returns empty when something else holds an
     * overlapping set. An overlap is not an absence: somebody needs to be told
     * which lane is holding the variants and why.
     */
    public UUID take(UUID id, UUID organizationId, UUID adNativeObjectId, UUID storeId,
                     UUID affectedSetId, String affectedSetDigest,
                     List<UUID> productVariantIds, String interventionKind,
                     UUID interventionReferenceId, String direction, String lane,
                     String correlationId) {
        return jdbc.sql("""
                SELECT ops.take_ad_action_reservation(:id, :organizationId, :objectId,
                        :storeId, :affectedSetId, :digest, CAST(:variantIds AS uuid[]),
                        :interventionKind, :referenceId, :direction, :lane, :correlationId)
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("objectId", adNativeObjectId)
                .param("storeId", storeId)
                .param("affectedSetId", affectedSetId)
                .param("digest", affectedSetDigest)
                .param("variantIds", uuidArrayLiteral(productVariantIds))
                .param("interventionKind", interventionKind)
                .param("referenceId", interventionReferenceId)
                .param("direction", direction)
                .param("lane", lane)
                .param("correlationId", correlationId)
                .query(UUID.class)
                .single();
    }

    /** Record whether one release condition currently holds. */
    public void observeCondition(UUID reservationId, String condition, boolean holds) {
        jdbc.sql("SELECT ops.observe_ad_reservation_condition(:id, :condition, :holds)")
                .param("id", reservationId)
                .param("condition", condition)
                .param("holds", holds)
                .query(Object.class)
                .optional();
    }

    /**
     * Release the reservation if all four conditions are recorded as met.
     *
     * <p>{@code false} when they are not. Not an exception: a reservation that
     * is not ready to be released is the ordinary case for most of its life, and
     * a sweep that asked about a hundred of them would otherwise have to catch a
     * hundred exceptions to learn nothing had changed.
     */
    public boolean release(UUID reservationId, String reason) {
        return Boolean.TRUE.equals(jdbc
                .sql("SELECT ops.release_ad_action_reservation(:id, :reason)")
                .param("id", reservationId)
                .param("reason", reason)
                .query(Boolean.class)
                .single());
    }

    /** Which reservation, if any, blocks this affected set. */
    public Optional<Blocking> blockingReservation(UUID organizationId,
                                                  List<UUID> productVariantIds,
                                                  UUID excludeObjectId) {
        return jdbc.sql("""
                SELECT reservation_id, lane, intervention_kind
                  FROM ops.ad_overlapping_reservation(:organizationId,
                        CAST(:variantIds AS uuid[]), :excludeObjectId)
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .param("variantIds", uuidArrayLiteral(productVariantIds))
                .param("excludeObjectId", excludeObjectId)
                .query((rs, index) -> new Blocking(rs.getObject("reservation_id", UUID.class),
                        rs.getString("lane"), rs.getString("intervention_kind")))
                .optional();
    }

    /**
     * Every containment currently covering this scope, by kind.
     *
     * <p>Empty means nothing is held. It does not mean anything is permitted:
     * the write gate asks several other questions, and this is one of them.
     */
    public List<String> activeContainment(UUID organizationId, UUID adNativeObjectId,
                                          UUID storeId, String platformCode,
                                          String capabilityCode, String affectedSetDigest) {
        return jdbc.sql("""
                SELECT ops.ad_active_containment(:organizationId, :objectId, :storeId,
                        :platformCode, :capabilityCode, :digest) AS kinds
                """)
                .param("organizationId", organizationId)
                .param("objectId", adNativeObjectId)
                .param("storeId", storeId)
                .param("platformCode", platformCode)
                .param("capabilityCode", capabilityCode)
                .param("digest", affectedSetDigest)
                .query((rs, index) -> {
                    Array kinds = rs.getArray("kinds");
                    return kinds == null ? List.<String>of() : List.of((String[]) kinds.getArray());
                })
                .single();
    }

    /** Reservations whose intervention has finished and which may now be swept. */
    public List<UUID> releasable(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id FROM ops.ad_action_reservation
                 WHERE state = 'ACTIVE'
                   AND configuration_resolved
                   AND NOT unknown_or_mismatch_open
                   AND early_observation_complete
                   AND NOT regression_open
                   AND reserved_at <= :now
                 ORDER BY reserved_at
                 LIMIT :limit
                """)
                .param("now", java.sql.Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** One live reservation that stands in the way, and which lane holds it. */
    public record Blocking(UUID reservationId, String lane, String interventionKind) {
    }

    /**
     * A uuid array as PostgreSQL reads it.
     *
     * <p>The driver will not infer an array type from a Java list here, and
     * passing the identifiers as text would let a value that is not an
     * identifier through. Every element is a {@link UUID} already, so the
     * literal cannot carry anything a uuid array may not hold.
     */
    private static String uuidArrayLiteral(List<UUID> ids) {
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(ids.get(index).toString());
        }
        return literal.append('}').toString();
    }
}
