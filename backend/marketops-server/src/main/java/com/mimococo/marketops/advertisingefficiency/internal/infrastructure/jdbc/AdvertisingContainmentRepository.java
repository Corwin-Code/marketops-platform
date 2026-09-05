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

    /**
     * Throw a containment.
     *
     * <p>Activation is attributable to exactly one of a person or a
     * deterministic trigger; the schema refuses both and refuses neither. AI
     * inference is neither, and can therefore activate nothing.
     */
    public UUID activate(UUID id, UUID organizationId, String containmentKind, String scopeKind,
                         String platformCode, UUID marketplaceAccountId, UUID storeId,
                         UUID adNativeObjectId, String affectedSetDigest, String capabilityCode,
                         String authorityVersionReference, String causeClass, String reason,
                         String evidenceReference, UUID activatedByUserId,
                         String activatedByTrigger, String correlationId) {
        throw new IllegalStateException("authenticated containment control invocation required");
    }

    public boolean observeReenablementCondition(UUID containmentId, String condition, boolean holds) {
        throw new IllegalStateException("independent recovery evidence attestation required");
    }

    public boolean reenable(UUID containmentId, UUID endorsedByUserId, UUID approvedByUserId,
            String reenabledScopeJson) {
        throw new IllegalStateException("new scoped Owner recovery invocation required");
    }

    /** Every containment for one organization, newest first. */
    public List<com.mimococo.marketops.advertisingefficiency.AdvertisingContainment> list(
            UUID organizationId, boolean holdingOnly, int limit) {
        return jdbc.sql("""
                SELECT id, containment_kind, scope_kind, cause_class, reason,
                       evidence_reference, activated_by_user_id, activated_by_trigger,
                       activated_at, state, root_cause_classified, unknowns_resolved,
                       authorities_replaced, results_reconciled, capability_evidence_current,
                       security_attestation_present, endorsed_by_user_id, approved_by_user_id,
                       reenabled_at
                  FROM ops.ad_containment
                 WHERE organization_id = :organizationId
                   AND (:holdingOnly = false OR state <> 'REENABLED')
                 ORDER BY activated_at DESC, id
                 LIMIT :limit
                """)
                .param("organizationId", organizationId)
                .param("holdingOnly", holdingOnly)
                .param("limit", limit)
                .query(AdvertisingContainmentRepository::mapContainment)
                .list();
    }

    /** One live reservation that stands in the way, and which lane holds it. */
    public record Blocking(UUID reservationId, String lane, String interventionKind) {
    }

    /**
     * The six things that must hold before anything restarts.
     *
     * <p>The attestation is in the list even though it is only required for a
     * technical or security cause, because recording it for a business-harm stop
     * is harmless and leaving it out of the vocabulary would mean a caller had
     * no way to record it at all.
     */
    public List<com.mimococo.marketops.advertisingefficiency.AdvertisingContainment> listForIds(
            UUID organizationId, List<UUID> ids, boolean holdingOnly, int limit) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT * FROM ops.ad_containment
                 WHERE organization_id = :organizationId AND id = ANY(:ids)
                   AND (:holdingOnly = false OR state <> 'REENABLED')
                 ORDER BY activated_at DESC, id LIMIT :limit
                """).param("organizationId", organizationId).param("ids", ids.toArray(new UUID[0]))
                .param("holdingOnly", holdingOnly).param("limit", limit)
                .query(AdvertisingContainmentRepository::mapContainment).list();
    }

    private static final java.util.Set<String> REENABLEMENT_CONDITIONS = java.util.Set.of(
            "ROOT_CAUSE_CLASSIFIED", "UNKNOWNS_RESOLVED", "AUTHORITIES_REPLACED",
            "RESULTS_RECONCILED", "CAPABILITY_EVIDENCE_CURRENT",
            "SECURITY_ATTESTATION_PRESENT");

    private static com.mimococo.marketops.advertisingefficiency.AdvertisingContainment
            mapContainment(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        List<String> outstanding = new java.util.ArrayList<>();
        if (!rs.getBoolean("root_cause_classified")) {
            outstanding.add("ROOT_CAUSE_CLASSIFIED");
        }
        if (!rs.getBoolean("unknowns_resolved")) {
            outstanding.add("UNKNOWNS_RESOLVED");
        }
        if (!rs.getBoolean("authorities_replaced")) {
            outstanding.add("AUTHORITIES_REPLACED");
        }
        if (!rs.getBoolean("results_reconciled")) {
            outstanding.add("RESULTS_RECONCILED");
        }
        if (!rs.getBoolean("capability_evidence_current")) {
            outstanding.add("CAPABILITY_EVIDENCE_CURRENT");
        }
        String causeClass = rs.getString("cause_class");
        if (!rs.getBoolean("security_attestation_present")
                && List.of("EXECUTION_INTEGRITY", "PROVIDER_OR_READBACK_DEFECT",
                        "CREDENTIAL_OR_SECURITY").contains(causeClass)) {
            outstanding.add("SECURITY_ATTESTATION_PRESENT");
        }
        java.sql.Timestamp reenabled = rs.getTimestamp("reenabled_at");
        return new com.mimococo.marketops.advertisingefficiency.AdvertisingContainment(
                rs.getObject("id", UUID.class),
                rs.getString("containment_kind"),
                rs.getString("scope_kind"),
                causeClass,
                rs.getString("reason"),
                rs.getString("evidence_reference"),
                rs.getObject("activated_by_user_id", UUID.class),
                rs.getString("activated_by_trigger"),
                rs.getTimestamp("activated_at").toInstant(),
                rs.getString("state"),
                List.copyOf(outstanding),
                rs.getObject("endorsed_by_user_id", UUID.class),
                rs.getObject("approved_by_user_id", UUID.class),
                reenabled == null ? null : reenabled.toInstant());
    }

    /**
     * Reservations over a set of stores, most recently taken first.
     *
     * <p>A read, and only a read. Nothing decides anything from this: the write
     * gate re-derives every reservation fact inside the database at the moment a
     * write is attempted, so a console that read a stale list cannot let
     * anything through.
     */
    public List<com.mimococo.marketops.advertisingefficiency.AdvertisingReservationView>
            reservations(UUID organizationId, List<UUID> permittedStoreIds, boolean holdingOnly,
                    int limit) {
        return jdbc.sql("""
                SELECT id, ad_native_object_id, store_id, affected_set_digest,
                       product_variant_ids, intervention_kind, intervention_reference_id,
                       direction, lane, state, configuration_resolved,
                       unknown_or_mismatch_open, early_observation_complete, regression_open,
                       reserved_at, released_at, release_reason
                  FROM ops.ad_action_reservation
                 WHERE organization_id = :organizationId
                   AND store_id = ANY (CAST(:permittedStoreIds AS uuid[]))
                   AND (:holdingOnly = false OR state = 'ACTIVE')
                 ORDER BY reserved_at DESC, id
                 LIMIT :limit
                """)
                .param("organizationId", organizationId)
                .param("permittedStoreIds", uuidArrayLiteral(permittedStoreIds))
                .param("holdingOnly", holdingOnly)
                .param("limit", limit)
                .query(AdvertisingContainmentRepository::mapReservation)
                .list();
    }

    /** How many reservations currently hold, which is the envelope's first axis. */
    public long activeInterventionCount(UUID organizationId) {
        return jdbc.sql("""
                SELECT count(*) FROM ops.ad_action_reservation
                 WHERE organization_id = :organizationId AND state = 'ACTIVE'
                """)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single();
    }

    /**
     * The envelope in force and what is consumed against each of its axes.
     *
     * <p>The envelope is selected the same way the write gate selects it — the
     * narrowest scope first, then the most recent — so the console reports the
     * envelope that would actually apply rather than whichever one happened to
     * be written last. Retired envelopes are still selectable, because the gate
     * treats a retired envelope as binding rather than as absent.
     *
     * <p>The three consumption figures are counted here rather than read from
     * anywhere, and each is counted independently. There is no place where one
     * axis's slack is added to another's, in this method or in the gate.
     */
    public com.mimococo.marketops.advertisingefficiency.AdvertisingExposureView exposure(
            UUID organizationId) {
        long active = activeInterventionCount(organizationId);
        long unresolved = jdbc.sql("""
                SELECT count(*) FROM ops.ad_bid_command
                 WHERE organization_id = :organizationId
                   AND state IN ('UNKNOWN_REQUIRES_READBACK', 'READBACK_MISMATCH',
                                 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION', 'MANUAL_RESOLUTION')
                """)
                .param("organizationId", organizationId)
                .query(Long.class)
                .single();
        return jdbc.sql("""
                SELECT e.id, e.policy_version, e.scope_kind, e.currency_code,
                       e.max_active_interventions, e.reserved_recovery_headroom_count,
                       e.max_unresolved_transmitted_writes,
                       e.max_cumulative_bid_change_amount, e.cumulative_window_hours,
                       e.effective_from, e.status,
                       (SELECT coalesce(sum(abs(c.target_bid_amount - c.prior_bid_amount)), 0)
                          FROM ops.ad_bid_command c
                         WHERE c.organization_id = e.organization_id
                           AND c.created_at > statement_timestamp()
                               - make_interval(hours => e.cumulative_window_hours))
                           AS cumulative_bid_change_amount
                  FROM core.ad_exposure_envelope e
                 WHERE e.organization_id = :organizationId
                   AND e.status IN ('ACTIVE', 'RETIRED')
                   AND e.effective_from <= statement_timestamp()
                   AND (e.effective_to IS NULL OR e.effective_to > statement_timestamp())
                 ORDER BY CASE e.scope_kind WHEN 'STORE' THEN 1 WHEN 'PLATFORM' THEN 2 ELSE 3 END,
                          e.effective_from DESC
                 LIMIT 1
                """)
                .param("organizationId", organizationId)
                .query((java.sql.ResultSet rs, int index) ->
                        new com.mimococo.marketops.advertisingefficiency.AdvertisingExposureView(
                                rs.getObject("id", UUID.class),
                                rs.getInt("policy_version"),
                                rs.getString("scope_kind"),
                                rs.getString("currency_code"),
                                active,
                                rs.getInt("max_active_interventions"),
                                rs.getInt("reserved_recovery_headroom_count"),
                                unresolved,
                                rs.getInt("max_unresolved_transmitted_writes"),
                                rs.getBigDecimal("cumulative_bid_change_amount"),
                                rs.getBigDecimal("max_cumulative_bid_change_amount"),
                                rs.getInt("cumulative_window_hours"),
                                rs.getTimestamp("effective_from").toInstant(),
                                rs.getString("status")))
                .optional()
                // No envelope is not an empty envelope. Nothing may be written
                // at all, and the consumption figures are still reported so an
                // operator can see what is standing while none resolves.
                .orElseGet(() -> com.mimococo.marketops.advertisingefficiency
                        .AdvertisingExposureView.unresolved(active, unresolved));
    }

    private static com.mimococo.marketops.advertisingefficiency.AdvertisingReservationView
            mapReservation(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        Array variants = rs.getArray("product_variant_ids");
        java.sql.Timestamp released = rs.getTimestamp("released_at");
        return new com.mimococo.marketops.advertisingefficiency.AdvertisingReservationView(
                rs.getObject("id", UUID.class),
                rs.getObject("ad_native_object_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getString("affected_set_digest"),
                variants == null ? List.of() : List.of((UUID[]) variants.getArray()),
                rs.getString("intervention_kind"),
                rs.getObject("intervention_reference_id", UUID.class),
                rs.getString("direction"),
                rs.getString("lane"),
                rs.getString("state"),
                rs.getBoolean("configuration_resolved"),
                rs.getBoolean("unknown_or_mismatch_open"),
                rs.getBoolean("early_observation_complete"),
                rs.getBoolean("regression_open"),
                rs.getTimestamp("reserved_at").toInstant(),
                released == null ? null : released.toInstant(),
                rs.getString("release_reason"));
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
