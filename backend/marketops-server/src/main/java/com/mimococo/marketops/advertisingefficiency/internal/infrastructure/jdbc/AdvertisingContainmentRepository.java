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
        jdbc.sql("""
                INSERT INTO ops.ad_containment (
                    id, organization_id, containment_kind, scope_kind, platform_code,
                    marketplace_account_id, store_id, ad_native_object_id, affected_set_digest,
                    capability_code, authority_version_reference, cause_class, reason,
                    evidence_reference, activated_by_user_id, activated_by_trigger,
                    activated_at, state, correlation_id, created_at, updated_at)
                VALUES (:id, :organizationId, :kind, :scopeKind, :platformCode, :accountId,
                    :storeId, :objectId, :digest, :capabilityCode, :authorityVersion,
                    :causeClass, :reason, :evidenceReference, :activatedByUserId,
                    :activatedByTrigger, clock_timestamp(), 'ACTIVE', :correlationId,
                    clock_timestamp(), clock_timestamp())
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("kind", containmentKind)
                .param("scopeKind", scopeKind)
                .param("platformCode", platformCode)
                .param("accountId", marketplaceAccountId)
                .param("storeId", storeId)
                .param("objectId", adNativeObjectId)
                .param("digest", affectedSetDigest)
                .param("capabilityCode", capabilityCode)
                .param("authorityVersion", authorityVersionReference)
                .param("causeClass", causeClass)
                .param("reason", reason)
                .param("evidenceReference", evidenceReference)
                .param("activatedByUserId", activatedByUserId)
                .param("activatedByTrigger", activatedByTrigger)
                .param("correlationId", correlationId)
                .update();
        return id;
    }

    /**
     * Record that one reenablement condition now holds, and who observed it.
     *
     * <p>Conditions are recorded one at a time and separately from lifting, so
     * the row-level check that refuses a lift with an outstanding condition has
     * something independent to check. Setting a condition on a containment that
     * has already been lifted changes nothing.
     */
    public boolean observeReenablementCondition(UUID containmentId, String condition,
                                                boolean holds) {
        if (!REENABLEMENT_CONDITIONS.contains(condition)) {
            throw new IllegalArgumentException("unknown reenablement condition " + condition);
        }
        return jdbc.sql("""
                UPDATE ops.ad_containment
                   SET root_cause_classified = CASE WHEN :condition = 'ROOT_CAUSE_CLASSIFIED'
                                                    THEN :holds ELSE root_cause_classified END,
                       unknowns_resolved = CASE WHEN :condition = 'UNKNOWNS_RESOLVED'
                                                THEN :holds ELSE unknowns_resolved END,
                       authorities_replaced = CASE WHEN :condition = 'AUTHORITIES_REPLACED'
                                                   THEN :holds ELSE authorities_replaced END,
                       results_reconciled = CASE WHEN :condition = 'RESULTS_RECONCILED'
                                                 THEN :holds ELSE results_reconciled END,
                       capability_evidence_current =
                           CASE WHEN :condition = 'CAPABILITY_EVIDENCE_CURRENT'
                                THEN :holds ELSE capability_evidence_current END,
                       security_attestation_present =
                           CASE WHEN :condition = 'SECURITY_ATTESTATION_PRESENT'
                                THEN :holds ELSE security_attestation_present END,
                       state = CASE WHEN state = 'ACTIVE' THEN 'REENABLEMENT_REVIEW'
                                    ELSE state END,
                       updated_at = clock_timestamp(), version = version + 1
                 WHERE id = :id AND state <> 'REENABLED'
                """)
                .param("id", containmentId)
                .param("condition", condition)
                .param("holds", holds)
                .update() == 1;
    }

    /**
     * Lift a containment, if two different people and every condition say so.
     *
     * <p>The endorser and the approver are written in the same statement that
     * lifts, and the table refuses the row unless they differ from each other
     * and from whoever activated it. So a single person cannot lift their own
     * stop by any sequence of calls, rather than merely being discouraged from
     * it by this method.
     */
    public boolean reenable(UUID containmentId, UUID endorsedByUserId, UUID approvedByUserId,
                            String reenabledScopeJson) {
        return jdbc.sql("""
                UPDATE ops.ad_containment
                   SET state = 'REENABLED', endorsed_by_user_id = :endorsedBy,
                       approved_by_user_id = :approvedBy,
                       reenabled_scope = CAST(:scope AS jsonb),
                       reenabled_at = clock_timestamp(),
                       updated_at = clock_timestamp(), version = version + 1
                 WHERE id = :id AND state <> 'REENABLED'
                """)
                .param("id", containmentId)
                .param("endorsedBy", endorsedByUserId)
                .param("approvedBy", approvedByUserId)
                .param("scope", reenabledScopeJson)
                .update() == 1;
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
