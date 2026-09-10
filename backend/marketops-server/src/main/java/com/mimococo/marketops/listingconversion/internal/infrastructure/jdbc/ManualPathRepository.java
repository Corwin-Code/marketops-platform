package com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc;

import com.mimococo.marketops.listingconversion.ManualPacketView;
import com.mimococo.marketops.listingconversion.PromotionEngagementView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Manual packets, executor reports, independent verifications and promotion engagements. */
@Repository
public class ManualPathRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ManualPathRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertPacket(UUID id, UUID organizationId, UUID actionId, UUID launchId, UUID executorUserId,
                             UUID issuedByUserId, Instant issuedAt, Instant expiresAt, String nativeListingKey,
                             String affectedSetDigest, String targetText) {
        jdbc.sql("""
                INSERT INTO ops.lc_manual_packet (id, organization_id, action_id, launch_id, executor_user_id, issued_by_user_id,
                    issued_at, expires_at, native_listing_key, affected_set_digest, target_text, execution_path, state,
                    updated_at, version)
                VALUES (:id, :org, :action, :launch, :executor, :issuer, :issued, :expires, :key, :digest, :text, 'MANUAL',
                    'ISSUED', :issued, 0)
                """).param("id", id).param("org", organizationId).param("action", actionId).param("launch", launchId)
                .param("executor", executorUserId).param("issuer", issuedByUserId).param("issued", Timestamp.from(issuedAt))
                .param("expires", Timestamp.from(expiresAt)).param("key", nativeListingKey).param("digest", affectedSetDigest)
                .param("text", targetText).update();
    }

    public Optional<ManualPacketView> packet(UUID id) {
        return jdbc.sql(PACKET_SELECT + " WHERE p.id = :id").param("id", id).query(this::mapPacket).optional();
    }

    public List<ManualPacketView> packetsForAction(UUID actionId) {
        return jdbc.sql(PACKET_SELECT + " WHERE p.action_id = :action ORDER BY p.issued_at DESC").param("action", actionId)
                .query(this::mapPacket).list();
    }

    public List<ManualPacketView> packetsForExecutor(UUID organizationId, UUID executorUserId, int limit) {
        return jdbc.sql(PACKET_SELECT + """
                 WHERE p.organization_id = :org AND p.executor_user_id = :executor ORDER BY p.issued_at DESC LIMIT :limit
                """).param("org", organizationId).param("executor", executorUserId).param("limit", limit)
                .query(this::mapPacket).list();
    }

    public boolean movePacket(UUID id, String to, long expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE ops.lc_manual_packet SET state = :to, updated_at = :now, version = version + 1
                 WHERE id = :id AND version = :version
                """).param("id", id).param("to", to).param("version", expectedVersion).param("now", Timestamp.from(now))
                .update() == 1;
    }

    public void insertReport(UUID id, UUID organizationId, UUID packetId, UUID reporterUserId, Instant operationTime,
                             Instant reportedAt, String reportState, String note) {
        jdbc.sql("""
                INSERT INTO ops.lc_manual_report (id, organization_id, packet_id, reporter_user_id, operation_time, reported_at,
                    report_state, note)
                VALUES (:id, :org, :packet, :reporter, :operation, :reported, :state, :note)
                """).param("id", id).param("org", organizationId).param("packet", packetId).param("reporter", reporterUserId)
                .param("operation", Timestamp.from(operationTime)).param("reported", Timestamp.from(reportedAt))
                .param("state", reportState).param("note", note).update();
    }

    public void insertVerification(UUID id, UUID organizationId, UUID packetId, UUID verifierUserId, String basis,
                                   String managementMatch, UUID managementObservationId, UUID displayObservationId,
                                   String displayState, Instant verifiedAt, String note) {
        jdbc.sql("""
                INSERT INTO ops.lc_manual_verification (id, organization_id, packet_id, verifier_user_id, verification_basis,
                    management_match, management_observation_id, display_observation_id, display_state, verified_at, note)
                VALUES (:id, :org, :packet, :verifier, :basis, :match, :management, :display, :displayState, :verified, :note)
                """).param("id", id).param("org", organizationId).param("packet", packetId).param("verifier", verifierUserId)
                .param("basis", basis).param("match", managementMatch).param("management", managementObservationId)
                .param("display", displayObservationId).param("displayState", displayState)
                .param("verified", Timestamp.from(verifiedAt)).param("note", note).update();
    }

    private static final String PACKET_SELECT = """
            SELECT p.id, p.action_id, p.launch_id, p.executor_user_id, p.issued_by_user_id, p.issued_at, p.expires_at,
                   p.native_listing_key, p.affected_set_digest, p.target_text, p.state, p.version
              FROM ops.lc_manual_packet p
            """;

    private ManualPacketView mapPacket(ResultSet rs, int n) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<ManualPacketView.Report> reports = jdbc.sql("""
                SELECT id, reporter_user_id, operation_time, reported_at, report_state, note FROM ops.lc_manual_report
                 WHERE packet_id = :packet ORDER BY reported_at
                """).param("packet", id)
                .query((row, m) -> new ManualPacketView.Report(row.getObject("id", UUID.class),
                        row.getObject("reporter_user_id", UUID.class), ListingFactRepository.instant(row, "operation_time"),
                        ListingFactRepository.instant(row, "reported_at"), row.getString("report_state"), row.getString("note")))
                .list();
        List<ManualPacketView.Verification> verifications = jdbc.sql("""
                SELECT id, verifier_user_id, verification_basis, management_match, management_observation_id,
                       display_observation_id, display_state, verified_at, note, observation_binding::text AS binding
                  FROM ops.lc_manual_verification WHERE packet_id = :packet ORDER BY verified_at
                """).param("packet", id)
                .query((row, m) -> new ManualPacketView.Verification(row.getObject("id", UUID.class),
                        row.getObject("verifier_user_id", UUID.class), row.getString("verification_basis"),
                        row.getString("management_match"), row.getObject("management_observation_id", UUID.class),
                        row.getObject("display_observation_id", UUID.class), row.getString("display_state"),
                        ListingFactRepository.instant(row, "verified_at"), row.getString("note"),observationBinding(row.getString("binding"))))
                .list();
        return new ManualPacketView(id, rs.getObject("action_id", UUID.class), rs.getObject("launch_id", UUID.class),
                rs.getObject("executor_user_id", UUID.class), rs.getObject("issued_by_user_id", UUID.class),
                ListingFactRepository.instant(rs, "issued_at"), ListingFactRepository.instant(rs, "expires_at"),
                rs.getString("native_listing_key"), rs.getString("affected_set_digest"), rs.getString("target_text"),
                rs.getString("state"), reports, verifications, rs.getLong("version"));
    }

    private Map<String,Object> observationBinding(String body) {
        Map<String,Object> binding=new java.util.LinkedHashMap<>();
        if (body!=null) json.readTree(body).properties().forEach(entry->binding.put(entry.getKey(),entry.getValue().deepCopy()));
        return binding;
    }

    // ------------------------------------------------------------------ engagements

    public void insertEngagement(UUID id, UUID organizationId, UUID storeId, UUID listingId, UUID actionId, String kind,
                                 String nativeKey, Map<String, String> terms, boolean priceFreeze, boolean autoParticipation,
                                 String termsEvidence, boolean adopted, Map<String, String> obligations, Instant now) {
        jdbc.sql("""
                INSERT INTO ops.lc_promotion_engagement (id, organization_id, store_id, platform_listing_id, action_id,
                    engagement_kind, native_promotion_key, terms, price_freeze, auto_participation, terms_evidence_reference,
                    adopted, obligations, state, created_at, updated_at, version)
                VALUES (:id, :org, :store, :listing, :action, :kind, :key, CAST(:terms AS jsonb), :freeze, :auto, :evidence,
                    :adopted, CAST(:obligations AS jsonb), 'ACTIVE', :now, :now, 0)
                """).param("id", id).param("org", organizationId).param("store", storeId).param("listing", listingId)
                .param("action", actionId).param("kind", kind).param("key", nativeKey)
                .param("terms", json.writeValueAsString(terms)).param("freeze", priceFreeze).param("auto", autoParticipation)
                .param("evidence", termsEvidence).param("adopted", adopted)
                .param("obligations", json.writeValueAsString(obligations)).param("now", Timestamp.from(now)).update();
    }

    public Optional<PromotionEngagementView> engagement(UUID id) {
        return jdbc.sql(ENGAGEMENT_SELECT + " WHERE e.id = :id").param("id", id).query(this::mapEngagement).optional();
    }

    public List<PromotionEngagementView> engagements(UUID listingId) {
        return jdbc.sql(ENGAGEMENT_SELECT + " WHERE e.platform_listing_id = :listing ORDER BY e.created_at DESC")
                .param("listing", listingId).query(this::mapEngagement).list();
    }

    public void authorizeExit(UUID engagementId, UUID actorId, String proof, String reasonCode) {
        jdbc.sql("SELECT ops.authorize_lc_promotion_exit(:id, :actor, :proof, :reason)")
                .param("id", engagementId).param("actor", actorId).param("proof", proof).param("reason", reasonCode)
                .query(Object.class).optional();
    }

    public boolean release(UUID engagementId, String toState, Instant at, long expectedVersion) {
        String column = "STOPPED".equals(toState) ? "new_transactions_stopped_at" : "obligations_cleared_at";
        return jdbc.sql("UPDATE ops.lc_promotion_engagement SET state = :to, " + column + " = :at, updated_at = :at, "
                + "version = version + 1 WHERE id = :id AND version = :version")
                .param("to", toState).param("at", Timestamp.from(at)).param("id", engagementId).param("version", expectedVersion)
                .update() == 1;
    }

    public boolean updateObligations(UUID engagementId, Map<String, String> obligations, Instant at, long expectedVersion) {
        return jdbc.sql("""
                UPDATE ops.lc_promotion_engagement SET obligations = CAST(:obligations AS jsonb), updated_at = :at,
                    version = version + 1 WHERE id = :id AND version = :version
                """).param("obligations", json.writeValueAsString(obligations)).param("at", Timestamp.from(at))
                .param("id", engagementId).param("version", expectedVersion).update() == 1;
    }

    private static final String ENGAGEMENT_SELECT = """
            SELECT e.id, e.store_id, e.platform_listing_id, e.action_id, e.engagement_kind, e.native_promotion_key,
                   e.terms::text AS terms, e.price_freeze, e.auto_participation, e.terms_evidence_reference, e.adopted,
                   e.obligations::text AS obligations, e.exit_reason_code, e.exit_authorized_by_user_id, e.exit_authorized_at,
                   e.new_transactions_stopped_at, e.obligations_cleared_at, e.state, e.version
              FROM ops.lc_promotion_engagement e
            """;

    private PromotionEngagementView mapEngagement(ResultSet rs, int n) throws SQLException {
        return new PromotionEngagementView(rs.getObject("id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getObject("platform_listing_id", UUID.class), rs.getObject("action_id", UUID.class),
                rs.getString("engagement_kind"), rs.getString("native_promotion_key"),
                ListingActionRepository.stringMap(rs.getString("terms")), rs.getBoolean("price_freeze"),
                rs.getBoolean("auto_participation"), rs.getString("terms_evidence_reference"), rs.getBoolean("adopted"),
                ListingActionRepository.stringMap(rs.getString("obligations")), rs.getString("exit_reason_code"),
                rs.getObject("exit_authorized_by_user_id", UUID.class),
                ListingFactRepository.instant(rs, "exit_authorized_at"),
                ListingFactRepository.instant(rs, "new_transactions_stopped_at"),
                ListingFactRepository.instant(rs, "obligations_cleared_at"), rs.getString("state"), rs.getLong("version"));
    }
}
