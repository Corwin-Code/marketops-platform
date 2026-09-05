package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Calls only the sealed manual business functions; no Provider dependency exists. */
@Repository
public class AdvertisingManualWorkflowRepository {
    private final JdbcClient jdbc;
    public AdvertisingManualWorkflowRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Scope(UUID organizationId, UUID storeId, UUID objectId, UUID affectedSetId,
            String digest, List<UUID> variants) { }
    public record Option(UUID policyId, int policyVersion, String actionKind, UUID candidateId,
            BigDecimal currentBid, BigDecimal targetBid, BigDecimal targetBudget, String targetStatus,
            String currencyCode, String bidUnitCode, String verificationMode, String apiProfileState,
            Instant validUntil) { }
    public record Transaction(int backendPid, long transactionId) { }

    public Optional<Scope> caseScope(UUID caseId) {
        return jdbc.sql("""
                SELECT c.organization_id,c.store_id,c.ad_native_object_id,a.id,a.affected_set_digest,a.product_variant_ids
                  FROM mart.ad_case c JOIN core.ad_affected_set a ON a.id=c.affected_set_id
                 WHERE c.id=:id
                """).param("id", caseId).query((rs, n) -> new Scope(rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class), rs.getObject("ad_native_object_id", UUID.class),
                        rs.getObject("id", UUID.class), rs.getString("affected_set_digest"),
                        List.of((UUID[]) rs.getArray("product_variant_ids").getArray()))).optional();
    }

    public Optional<Scope> packetScope(UUID packetId) {
        return jdbc.sql("""
                SELECT p.organization_id,p.store_id,p.ad_native_object_id,a.id,a.affected_set_digest,a.product_variant_ids
                  FROM ops.ad_manual_execution_packet p JOIN core.ad_affected_set a ON a.id=p.affected_set_id
                 WHERE p.id=:id
                """).param("id", packetId).query((rs, n) -> new Scope(rs.getObject("organization_id", UUID.class),
                        rs.getObject("store_id", UUID.class), rs.getObject("ad_native_object_id", UUID.class),
                        rs.getObject("id", UUID.class), rs.getString("affected_set_digest"),
                        List.of((UUID[]) rs.getArray("product_variant_ids").getArray()))).optional();
    }

    public List<Option> options(UUID caseId) {
        return jdbc.sql("""
                SELECT p.id,p.policy_version,p.action_kind,candidate.id candidate_id,candidate.current_bid_amount,
                       candidate.provider_normalized_amount,p.target_budget,p.target_status,p.currency_code,
                       profile.bid_unit_code,p.verification_mode,profile.verification_state,p.effective_to
                  FROM mart.ad_case c JOIN core.ad_manual_policy p ON p.organization_id=c.organization_id
                   AND p.store_id=c.store_id AND p.cause_code=c.cause_code AND p.semantic_profile_id=c.semantic_profile_id
                  JOIN platform.ad_semantic_profile profile ON profile.id=p.semantic_profile_id
                  LEFT JOIN ops.ad_bid_candidate candidate ON p.action_kind='AD_BID_CHANGE' AND candidate.case_id=c.id
                   AND candidate.candidate_basis=p.candidate_basis AND candidate.affected_set_digest=
                       (SELECT affected_set_digest FROM core.ad_affected_set WHERE id=c.affected_set_id)
                 WHERE c.id=:id AND c.superseded_at IS NULL AND p.effective_from<=statement_timestamp()
                   AND EXISTS (SELECT 1 FROM core.ad_affected_set affected WHERE affected.id=c.affected_set_id
                       AND affected.resolution_state='COMPLETE')
                   AND p.effective_to>statement_timestamp() AND profile.status='ACTIVE'
                   AND (profile.effective_to IS NULL OR profile.effective_to>statement_timestamp())
                   AND (p.action_kind<>'AD_BID_CHANGE' OR candidate.id IS NOT NULL)
                 ORDER BY p.action_kind,p.policy_version,candidate.ordinal,candidate.id LIMIT 100
                """).param("id", caseId).query((rs, n) -> new Option(rs.getObject("id", UUID.class),
                        rs.getInt("policy_version"), rs.getString("action_kind"), rs.getObject("candidate_id", UUID.class),
                        rs.getBigDecimal("current_bid_amount"), rs.getBigDecimal("provider_normalized_amount"),
                        rs.getBigDecimal("target_budget"), rs.getString("target_status"), rs.getString("currency_code"),
                        rs.getString("bid_unit_code"), rs.getString("verification_mode"), rs.getString("verification_state"),
                        rs.getTimestamp("effective_to").toInstant())).list();
    }

    public UUID publish(String content, String proof) {
        return jdbc.sql("SELECT ops.publish_ad_manual_policy(CAST(:content AS jsonb),:proof)")
                .param("content", content).param("proof", proof).query(UUID.class).single();
    }

    public UUID generate(UUID id, UUID caseId, UUID policyId, UUID candidateId) {
        return jdbc.sql("SELECT ops.generate_ad_manual_proposal(:id,:caseId,:policyId,:candidateId)")
                .param("id", id).param("caseId", caseId).param("policyId", policyId)
                .param("candidateId", candidateId).query(UUID.class).single();
    }

    public UUID select(UUID packetId, UUID proposalId, UUID baselineId, String reason, String proof) {
        return jdbc.sql("SELECT ops.select_ad_manual_packet(:packet,:proposal,:baseline,:reason,:proof)")
                .param("packet", packetId).param("proposal", proposalId).param("baseline", baselineId).param("reason", reason)
                .param("proof", proof).query(UUID.class).single();
    }

    public void decide(UUID packetId, long expectedVersion, boolean approve, String proof) {
        jdbc.sql("SELECT ops.decide_ad_manual_packet(:packet,:version,:approve,:proof)")
                .param("packet", packetId).param("version", expectedVersion).param("approve", approve)
                .param("proof", proof).query(Object.class).optional();
    }

    public void start(UUID packetId, long expectedVersion, String proof) {
        jdbc.sql("SELECT ops.start_ad_manual_execution(:packet,:version,:proof)")
                .param("packet", packetId).param("version", expectedVersion).param("proof", proof)
                .query(Object.class).optional();
    }

    public UUID observe(UUID id, UUID packetId, long expectedVersion, String kind, String observedValue,
            UUID configurationId, String proof) {
        return jdbc.sql("SELECT ops.record_ad_manual_observation(:id,:packet,:version,:kind,:value,:configuration,:proof)")
                .param("id", id).param("packet", packetId).param("version", expectedVersion).param("kind", kind)
                .param("value", observedValue).param("configuration", configurationId).param("proof", proof)
                .query(UUID.class).single();
    }

    public Transaction transaction() {
        return jdbc.sql("SELECT pg_backend_pid() backend,txid_current() transaction_id")
                .query((rs, n) -> new Transaction(rs.getInt("backend"), rs.getLong("transaction_id"))).single();
    }
}
