package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import com.mimococo.marketops.advertisingefficiency.ManualExecutionPacketView;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Manual Shadow, which is a record and never a route.
 *
 * <p>Nothing in this class can cause a marketplace call. There is no command, no
 * outbox row, no lease and no adapter reachable from anything written here, and
 * the packet's action vocabulary — which includes the budget and status changes
 * this product deliberately does not write — is exactly why that separation has
 * to be structural rather than a matter of nobody having written the code yet.
 *
 * <p>What it does record is who asked, who did it, and who checked. The schema
 * refuses a self-report that claims to prove a configuration and refuses an
 * "independent" verification by the person who made the change, so those two
 * mistakes are not available to any service.
 */
@Repository
public class AdvertisingManualPacketRepository {

    private final JdbcClient jdbc;

    AdvertisingManualPacketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Issue one packet for one case. */
    public UUID issue(UUID id, UUID organizationId, UUID caseId, UUID adNativeObjectId,
                      UUID storeId, String platformCode, UUID affectedSetId,
                      String affectedSetDigest, UUID semanticProfileId, String actionKind,
                      UUID observedConfigurationId, String intendedStateJson, String reason,
                      String evidenceReference, UUID guardrailEvaluationId,
                      List<String> blockerCodes, UUID makerUserId, String expectedImpactJson,
                      String verificationPlanJson, Instant issuedAt, Instant expiresAt,
                      String correlationId) {
        jdbc.sql("""
                INSERT INTO ops.ad_manual_execution_packet (
                    id, organization_id, case_id, ad_native_object_id, store_id, platform_code,
                    affected_set_id, affected_set_digest, semantic_profile_id, action_kind,
                    observed_configuration_id, intended_state, reason, evidence_reference,
                    guardrail_evaluation_id, blocker_codes, maker_user_id, expected_impact,
                    verification_plan, state, issued_at, expires_at, correlation_id,
                    created_at, updated_at)
                VALUES (:id, :organizationId, :caseId, :objectId, :storeId, :platformCode,
                    :affectedSetId, :digest, :profileId, :actionKind, :configurationId,
                    CAST(:intendedState AS jsonb), :reason, :evidenceReference,
                    :guardrailEvaluationId, CAST(:blockerCodes AS text[]), :makerUserId,
                    CAST(:expectedImpact AS jsonb), CAST(:verificationPlan AS jsonb),
                    'MANUAL_PACKET_ISSUED', :issuedAt, :expiresAt, :correlationId,
                    clock_timestamp(), clock_timestamp())
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("caseId", caseId)
                .param("objectId", adNativeObjectId)
                .param("storeId", storeId)
                .param("platformCode", platformCode)
                .param("affectedSetId", affectedSetId)
                .param("digest", affectedSetDigest)
                .param("profileId", semanticProfileId)
                .param("actionKind", actionKind)
                .param("configurationId", observedConfigurationId)
                .param("intendedState", intendedStateJson)
                .param("reason", reason)
                .param("evidenceReference", evidenceReference)
                .param("guardrailEvaluationId", guardrailEvaluationId)
                .param("blockerCodes", textArrayLiteral(blockerCodes))
                .param("makerUserId", makerUserId)
                .param("expectedImpact", expectedImpactJson)
                .param("verificationPlan", verificationPlanJson)
                .param("issuedAt", Timestamp.from(issuedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .param("correlationId", correlationId)
                .update();
        return id;
    }

    /**
     * Record one observation about whether the change landed.
     *
     * <p>The state the packet moves to is derived from what the observation
     * actually proves rather than passed in. An executor's report moves it to
     * ACTION_REPORTED_CONFIGURATION_UNVERIFIED and no further, however
     * confidently it was written.
     */
    public UUID recordVerification(UUID id, UUID organizationId, UUID packetId,
                                   String evidenceGrade, UUID executorUserId,
                                   UUID verifierUserId, String observedFieldPath,
                                   String observedValue, Instant observedAt,
                                   String evidenceReference, String conflictState,
                                   String correlationId) {
        boolean proves = "NONE".equals(conflictState)
                && List.of("OFFICIAL_API_READBACK", "OFFICIAL_CONFIGURATION_EXPORT",
                        "INDEPENDENT_MANUAL_VERIFICATION").contains(evidenceGrade);
        jdbc.sql("""
                INSERT INTO ops.ad_manual_configuration_verification (
                    id, organization_id, packet_id, evidence_grade, executor_user_id,
                    verifier_user_id, observed_field_path, observed_value, observed_at,
                    evidence_reference, conflict_state, proves_configuration, recorded_at,
                    correlation_id)
                VALUES (:id, :organizationId, :packetId, :grade, :executor, :verifier,
                    :fieldPath, :value, :observedAt, :evidenceReference, :conflictState,
                    :proves, clock_timestamp(), :correlationId)
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("packetId", packetId)
                .param("grade", evidenceGrade)
                .param("executor", executorUserId)
                .param("verifier", verifierUserId)
                .param("fieldPath", observedFieldPath)
                .param("value", observedValue)
                .param("observedAt", Timestamp.from(observedAt))
                .param("evidenceReference", evidenceReference)
                .param("conflictState", conflictState)
                .param("proves", proves)
                .param("correlationId", correlationId)
                .update();

        jdbc.sql("""
                UPDATE ops.ad_manual_execution_packet
                   SET state = CASE
                           WHEN :proves THEN 'MANUAL_CONFIGURATION_VERIFIED'
                           WHEN :conflicted THEN 'MANUAL_EXECUTION_UNCERTAIN'
                           WHEN state = 'MANUAL_PACKET_ISSUED'
                               THEN 'ACTION_REPORTED_CONFIGURATION_UNVERIFIED'
                           ELSE state END,
                       updated_at = clock_timestamp(), version = version + 1
                 WHERE id = :packetId AND state NOT IN ('MANUAL_PACKET_REVOKED',
                       'MANUAL_CONFIGURATION_VERIFIED', 'MANUAL_PACKET_EXPIRED')
                """)
                .param("packetId", packetId)
                .param("proves", proves)
                .param("conflicted", !"NONE".equals(conflictState))
                .update();
        return id;
    }

    /** Withdraw a packet nobody should act on any more. */
    public boolean revoke(UUID packetId, String reason) {
        return jdbc.sql("""
                UPDATE ops.ad_manual_execution_packet
                   SET state = 'MANUAL_PACKET_REVOKED', revoked_at = clock_timestamp(),
                       revoked_reason = :reason, updated_at = clock_timestamp(),
                       version = version + 1
                 WHERE id = :packetId AND state = 'MANUAL_PACKET_ISSUED'
                """)
                .param("packetId", packetId)
                .param("reason", reason)
                .update() == 1;
    }

    /**
     * Mark every issued packet whose window has closed.
     *
     * <p>Expiry is a sweep rather than a read-time judgement, so a packet that
     * has run out looks the same to everybody who reads it. A packet that quietly
     * expired only in the eye of whoever asked would be an instruction two people
     * disagree about.
     */
    public int expire(Instant now) {
        return jdbc.sql("""
                UPDATE ops.ad_manual_execution_packet
                   SET state = 'MANUAL_PACKET_EXPIRED', updated_at = clock_timestamp(),
                       version = version + 1
                 WHERE state = 'MANUAL_PACKET_ISSUED' AND expires_at <= :now
                """)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** One packet with everything observed about it. */
    public Optional<ManualExecutionPacketView> packet(UUID packetId) {
        Optional<ManualExecutionPacketView> found = jdbc.sql(PACKET_SELECT
                        + " WHERE packet.id = :packetId")
                .param("packetId", packetId)
                .query(AdvertisingManualPacketRepository::mapPacket)
                .optional();
        return found.map(packet -> withVerifications(packet, verifications(packetId)));
    }

    /** Every packet for one advertising object, newest first. */
    public List<ManualExecutionPacketView> forObject(UUID organizationId, UUID objectId,
                                                     int limit) {
        return jdbc.sql(PACKET_SELECT + """
                 WHERE packet.organization_id = :organizationId
                   AND packet.ad_native_object_id = :objectId
                 ORDER BY packet.issued_at DESC, packet.id
                 LIMIT :limit
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("limit", limit)
                .query(AdvertisingManualPacketRepository::mapPacket)
                .list()
                .stream()
                .map(packet -> withVerifications(packet, verifications(packet.id())))
                .toList();
    }

    /**
     * Every packet for one object, narrowed to the caller's stores.
     *
     * <p>The packet carries the store it was issued for, so the narrowing
     * happens in SQL as well as in the caller.
     */
    public List<ManualExecutionPacketView> forObject(UUID organizationId, UUID objectId,
                                                     List<UUID> permittedStoreIds, int limit) {
        return jdbc.sql(PACKET_SELECT + """
                 WHERE packet.organization_id = :organizationId
                   AND packet.ad_native_object_id = :objectId
                   AND packet.store_id = ANY (CAST(:permittedStoreIds AS uuid[]))
                 ORDER BY packet.issued_at DESC, packet.id
                 LIMIT :limit
                """)
                .param("organizationId", organizationId)
                .param("objectId", objectId)
                .param("permittedStoreIds", uuidArrayLiteral(permittedStoreIds))
                .param("limit", limit)
                .query(AdvertisingManualPacketRepository::mapPacket)
                .list()
                .stream()
                .map(packet -> withVerifications(packet, verifications(packet.id())))
                .toList();
    }

    /**
     * A uuid array as PostgreSQL reads it.
     *
     * <p>Every element is already a {@link UUID}, so the literal cannot carry
     * anything a uuid array may not hold.
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

    private List<ManualExecutionPacketView.Verification> verifications(UUID packetId) {
        return jdbc.sql("""
                SELECT id, evidence_grade, executor_user_id, verifier_user_id,
                       observed_field_path, observed_value, conflict_state,
                       proves_configuration, observed_at
                  FROM ops.ad_manual_configuration_verification
                 WHERE packet_id = :packetId
                 ORDER BY observed_at DESC, id
                """)
                .param("packetId", packetId)
                .query((ResultSet rs, int index) ->
                        new ManualExecutionPacketView.Verification(
                                rs.getObject("id", UUID.class),
                                rs.getString("evidence_grade"),
                                rs.getObject("executor_user_id", UUID.class),
                                rs.getObject("verifier_user_id", UUID.class),
                                rs.getString("observed_field_path"),
                                rs.getString("observed_value"),
                                rs.getString("conflict_state"),
                                rs.getBoolean("proves_configuration"),
                                rs.getTimestamp("observed_at").toInstant()))
                .list();
    }

    private static ManualExecutionPacketView withVerifications(
            ManualExecutionPacketView packet,
            List<ManualExecutionPacketView.Verification> verifications) {
        return new ManualExecutionPacketView(packet.id(), packet.caseId(),
                packet.adNativeObjectId(), packet.actionKind(), packet.intendedState(),
                packet.reason(), packet.evidenceReference(), packet.blockerCodes(),
                packet.makerUserId(), packet.endorserUserId(), packet.approverUserId(),
                packet.state(), packet.issuedAt(), packet.expiresAt(), verifications);
    }

    private static final String PACKET_SELECT = """
            SELECT packet.id, packet.case_id, packet.ad_native_object_id, packet.action_kind,
                   packet.intended_state::text AS intended_state, packet.reason,
                   packet.evidence_reference, packet.blocker_codes, packet.maker_user_id,
                   packet.endorser_user_id, packet.approver_user_id, packet.state,
                   packet.issued_at, packet.expires_at
              FROM ops.ad_manual_execution_packet packet
            """;

    private static ManualExecutionPacketView mapPacket(ResultSet rs, int index)
            throws SQLException {
        Array blockers = rs.getArray("blocker_codes");
        return new ManualExecutionPacketView(
                rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getObject("ad_native_object_id", UUID.class),
                rs.getString("action_kind"),
                rs.getString("intended_state"),
                rs.getString("reason"),
                rs.getString("evidence_reference"),
                blockers == null ? List.of() : List.of((String[]) blockers.getArray()),
                rs.getObject("maker_user_id", UUID.class),
                rs.getObject("endorser_user_id", UUID.class),
                rs.getObject("approver_user_id", UUID.class),
                rs.getString("state"),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                List.of());
    }

    /** A text array as PostgreSQL reads it, with no element able to close the literal. */
    private static String textArrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder literal = new StringBuilder("{");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append('"')
                    .append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return literal.append('}').toString();
    }
}
