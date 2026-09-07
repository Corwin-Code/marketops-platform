package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import com.mimococo.marketops.advertisingefficiency.internal.domain.BidCandidate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Where a generated bid candidate is written down.
 *
 * <p>A candidate is a record of what this product was willing to ask for at one
 * instant, against one set of policy versions, from one observed bid. It is
 * never updated: the command that later names it re-checks that the observed bid
 * still matches, and a candidate that could be edited would let that check pass
 * against a number somebody changed afterwards.
 */
@Repository
public class AdvertisingCandidateRepository {

    private final JdbcClient jdbc;

    AdvertisingCandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record one candidate for one case, or return the one already recorded.
     *
     * <p>Idempotent on the case, direction and ordinal, which the schema
     * enforces. A recalculation that reaches the same conclusion re-uses its own
     * candidate rather than accumulating one per cycle, and an operator opening
     * the case twice sees one proposal.
     */
    public UUID record(UUID id, UUID organizationId, UUID caseId, UUID adNativeObjectId,
                       String affectedSetDigest, UUID targetPolicyId, int targetPolicyVersion,
                       UUID semanticProfileId, BidCandidate candidate, int ordinal,
                       java.math.BigDecimal maxCpcAmount, String maxCpcAbsenceReason,
                       String causeCode, Instant generatedAt, String correlationId) {
        Optional<UUID> existing = jdbc.sql("""
                SELECT id FROM ops.ad_bid_candidate
                 WHERE case_id = :caseId AND direction = :direction AND ordinal = :ordinal
                   AND target_policy_id=:policyId AND target_policy_version=:policyVersion
                   AND semantic_profile_id=:profileId AND affected_set_digest=:digest
                   AND current_bid_amount=:currentBid AND provider_normalized_amount=:target
                """)
                .param("caseId", caseId)
                .param("direction", candidate.direction())
                .param("ordinal", ordinal)
                .param("policyId", targetPolicyId).param("policyVersion", targetPolicyVersion)
                .param("profileId", semanticProfileId).param("digest", affectedSetDigest)
                .param("currentBid", candidate.currentBid()).param("target", candidate.providerNormalizedAmount())
                .query(UUID.class)
                .optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbc.sql("""
                INSERT INTO ops.ad_bid_candidate (
                    id, organization_id, case_id, ad_native_object_id, affected_set_digest,
                    target_policy_id, target_policy_version, semantic_profile_id, direction,
                    candidate_basis, ordinal, current_bid_amount, requested_amount,
                    provider_normalized_amount, currency_code, bid_unit_code, max_cpc_amount,
                    max_cpc_absence_reason, cause_code, generated_at, correlation_id)
                VALUES (:id, :organizationId, :caseId, :objectId, :digest, :policyId,
                    :policyVersion, :profileId, :direction, :basis, :ordinal, :currentBid,
                    :requested, :normalized, :currency, :unit, :maxCpc, :absence, :cause,
                    :generatedAt, :correlationId)
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("caseId", caseId)
                .param("objectId", adNativeObjectId)
                .param("digest", affectedSetDigest)
                .param("policyId", targetPolicyId)
                .param("policyVersion", targetPolicyVersion)
                .param("profileId", semanticProfileId)
                .param("direction", candidate.direction())
                .param("basis", candidate.candidateBasis())
                .param("ordinal", ordinal)
                .param("currentBid", candidate.currentBid())
                .param("requested", candidate.requestedAmount())
                .param("normalized", candidate.providerNormalizedAmount())
                .param("currency", candidate.currencyCode())
                .param("unit", candidate.bidUnitCode())
                .param("maxCpc", maxCpcAmount)
                .param("absence", maxCpcAbsenceReason)
                .param("cause", causeCode)
                .param("generatedAt", Timestamp.from(generatedAt))
                .param("correlationId", correlationId)
                .update();
        return id;
    }

    public boolean allowsIntermediateTarget(UUID policyId) {
        return jdbc.sql("SELECT allow_protection_intermediate_target FROM core.ad_bid_target_policy WHERE id=:id")
                .param("id", policyId).query(Boolean.class).optional().orElse(false);
    }

    /**
     * The affected set identity behind one case, if it has been resolved.
     *
     * <p>Both the row identifier and the digest, because a reservation needs the
     * first to point at and the second to compare. A set that resolved
     * incompletely is not returned: reserving against a set nobody could finish
     * enumerating would be claiming to hold variants that were never listed.
     */
    public Optional<AffectedSetRow> resolvedAffectedSet(UUID organizationId, UUID caseId) {
        return jdbc.sql("""
                SELECT affected.id, affected.affected_set_digest, affected.product_variant_ids
                  FROM mart.ad_case kase
                  JOIN core.ad_affected_set affected
                    ON affected.id = kase.affected_set_id
                   AND affected.organization_id = kase.organization_id
                 WHERE kase.id = :caseId AND kase.organization_id = :organizationId
                   AND affected.resolution_state = 'COMPLETE'
                   AND cardinality(affected.product_variant_ids) >= 1
                """)
                .param("caseId", caseId)
                .param("organizationId", organizationId)
                .query((rs, index) -> {
                    java.sql.Array variants = rs.getArray("product_variant_ids");
                    return new AffectedSetRow(rs.getObject("id", UUID.class),
                            rs.getString("affected_set_digest"),
                            java.util.List.of((UUID[]) variants.getArray()));
                })
                .optional();
    }

    /**
     * The identity of the advertising facts one decision rests on.
     *
     * <p>Read rather than computed. The approval compares itself against this
     * exact value at approval time and the write gate compares again at
     * transmission, and all three have to be the same definition — so there is
     * one, and it lives in the database.
     */
    public Optional<String> entityVersionDigest(UUID adNativeObjectId, UUID candidateId) {
        return jdbc.sql("SELECT ops.ad_entity_version_digest(:objectId, :candidateId)")
                .param("objectId", adNativeObjectId)
                .param("candidateId", candidateId)
                .query(String.class)
                .optional();
    }

    /** One resolved affected set, as a reservation needs it. */
    public record AffectedSetRow(UUID id, String digest, java.util.List<UUID> productVariantIds) {
    }
}
