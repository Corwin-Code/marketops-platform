package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.ListingDescriptionCommandView;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWriteResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The application's only access to the description command tables.
 *
 * <p>Every mutating method is a call to a {@code SECURITY DEFINER} function,
 * because the application role holds {@code SELECT} and nothing else on these
 * tables. An {@code UPDATE} written here would fail at the database.
 */
@Repository
public class ListingDescriptionCommandRepository {

    private final JdbcClient jdbc;

    ListingDescriptionCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Everything a worker needs about a command it is about to act on. */
    public record CommandRow(
            UUID id, UUID organizationId, UUID recommendationId, UUID actionId, UUID storeId,
            UUID platformListingId, String nativeListingKey, String platformCode, UUID capabilityId,
            String idempotencyKey, String priorText, String priorTextDigest, String targetText,
            String targetTextDigest, boolean kizMarkedDeclared, String equivalenceRule,
            int lengthBoundMin, int lengthBoundMax, String affectedSetDigest, String state,
            int attemptNo, int retryBudgetRemaining, long fenceToken, String leaseOwner,
            String requestedOperation, Instant approvalExpiresAt) {
    }

    public UUID create(UUID actionId, UUID actorId, long expectedVersion, String correlationId) {
        return jdbc.sql("""
                SELECT ops.create_lc_description_command(:actionId, :actorId, :expectedVersion, :correlationId)
                """)
                .param("actionId", actionId).param("actorId", actorId)
                .param("expectedVersion", expectedVersion).param("correlationId", correlationId)
                .query(UUID.class).single();
    }

    public Optional<CommandRow> row(UUID commandId) {
        return jdbc.sql(COMMAND_SELECT + " WHERE c.id = :commandId")
                .param("commandId", commandId)
                .query(ListingDescriptionCommandRepository::mapCommand)
                .optional();
    }

    public Optional<UUID> forAction(UUID actionId) {
        return jdbc.sql("SELECT id FROM ops.lc_description_command WHERE action_id = :actionId")
                .param("actionId", actionId).query(UUID.class).optional();
    }

    public Optional<UUID> forRecommendation(UUID recommendationId) {
        return jdbc.sql("SELECT id FROM ops.lc_description_command WHERE recommendation_id = :recommendationId")
                .param("recommendationId", recommendationId).query(UUID.class).optional();
    }

    /** Commands a worker may claim now: waiting work, an authorised readback, or an unheld observation. */
    public List<UUID> claimable(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id FROM ops.lc_description_command
                 WHERE NOT provider_retry_timing_unknown
                   AND (provider_not_before IS NULL OR provider_not_before <= clock_timestamp())
                   AND ((state IN ('PENDING', 'RETRY_WAIT')
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                        AND retry_budget_remaining > 0)
                    OR (state = 'UNKNOWN_REQUIRES_READBACK' AND requested_operation = 'READBACK'
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                    OR (state IN ('COMPENSATION_PENDING', 'PLATFORM_PENDING') AND lease_owner IS NULL
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now)))
                 ORDER BY created_at
                 LIMIT :limit
                """)
                .param("now", ts(now)).param("limit", limit)
                .query(UUID.class).list();
    }

    /** Durable provider timing survives leases, transitions and worker restarts. */
    public boolean providerWaitActive(UUID commandId) {
        return jdbc.sql("""
                SELECT provider_retry_timing_unknown OR coalesce(provider_not_before > clock_timestamp(),false)
                  FROM ops.lc_description_command WHERE id=:id
                """).param("id", commandId).query(Boolean.class).optional().orElse(false);
    }

    public long lease(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_lc_description_command(:commandId, :owner, :seconds)")
                .param("commandId", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    public long leaseReadback(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_lc_description_readback(:commandId, :owner, :seconds)")
                .param("commandId", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    public long leaseStatus(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_lc_description_status(:id, :owner, :seconds)")
                .param("id", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    public long leaseCompensation(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_lc_description_compensation(:commandId, :owner, :seconds)")
                .param("commandId", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    public void deferObservation(UUID commandId, long fence, String owner, int seconds) {
        jdbc.sql("SELECT ops.defer_lc_description_observation(:id, :fence, :owner, :seconds)")
                .param("id", commandId).param("fence", fence).param("owner", owner)
                .param("seconds", seconds).query(Object.class).optional();
    }

    public boolean retryIsProven(UUID commandId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT ops.lc_description_retry_is_proven(:id)")
                .param("id", commandId).query(Boolean.class).single());
    }

    public Optional<String> nativeTaskKey(UUID commandId) {
        return jdbc.sql("""
                SELECT native_task_key FROM (
                    SELECT native_task_key, outcome_class FROM ops.lc_description_command_attempt
                     WHERE command_id = :id AND purpose IN ('APPLY', 'RESTORE')
                     ORDER BY attempt_no DESC LIMIT 1
                ) latest WHERE outcome_class = 'ACCEPTED' AND native_task_key IS NOT NULL
                """).param("id", commandId).query(String.class).optional();
    }

    public boolean restoreAlreadyAttempted(UUID commandId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.lc_description_command_attempt
                 WHERE command_id = :id AND purpose = 'RESTORE')
                """).param("id", commandId).query(Boolean.class).single());
    }

    public String transition(UUID commandId, long fence, String owner, String toState,
                             String failureCode, Integer retryDelaySeconds, UUID evidenceId) {
        return jdbc.sql("""
                SELECT ops.transition_lc_description_command(:commandId, :fence, :owner, :toState,
                        :failureCode, :retryDelaySeconds, :evidenceId)
                """)
                .param("commandId", commandId).param("fence", fence).param("owner", owner)
                .param("toState", toState).param("failureCode", failureCode)
                .param("retryDelaySeconds", retryDelaySeconds).param("evidenceId", evidenceId)
                .query(String.class).single();
    }

    public UUID openAttempt(UUID attemptId, UUID commandId, String purpose, long fence,
                            String owner, String requestDigest, String correlationId) {
        return jdbc.sql("""
                SELECT ops.open_lc_description_command_attempt(:attemptId, :commandId, :purpose, :fence,
                        :owner, :requestDigest, :correlationId)
                """)
                .param("attemptId", attemptId).param("commandId", commandId)
                .param("purpose", purpose).param("fence", fence).param("owner", owner)
                .param("requestDigest", requestDigest).param("correlationId", correlationId)
                .query(UUID.class).single();
    }

    public void requestReadback(UUID commandId, long fence) {
        jdbc.sql("SELECT ops.request_lc_description_readback(:commandId, :fence)")
                .param("commandId", commandId).param("fence", fence)
                .query(Object.class).optional();
    }

    public int recoverExpiredLeases() {
        return jdbc.sql("SELECT ops.recover_expired_lc_description_leases()")
                .query(Integer.class).single();
    }

    /** Complete one attempt from the exact bytes, and take the derived answer back. */
    public DescriptionWriteResult completeAttempt(UUID attemptId, long fence, String owner,
                                                  DescriptionWriteResult result, UUID contentId,
                                                  String requestDigest) {
        var response = result.response();
        jdbc.sql("""
                SELECT ops.complete_lc_description_command_attempt(:attemptId, :fence, :owner, :outcome,
                        :nativeStatus, :task, :error, :content, :body, :httpStatus,
                        CAST(:headers AS jsonb), :evidenceClass, :requestDigest, :responseComplete)
                """)
                .param("attemptId", attemptId).param("fence", fence).param("owner", owner)
                .param("outcome", result.outcome().name())
                .param("nativeStatus", result.nativeStatus())
                .param("task", result.nativeTaskKey())
                .param("error", result.errorCode())
                .param("content", contentId)
                .param("body", result.body())
                .param("httpStatus", response == null ? null : response.httpStatus())
                .param("headers", response == null ? "{}" : headersJson(response.headers()))
                .param("evidenceClass", response == null ? null : response.evidenceClass())
                .param("requestDigest", requestDigest)
                .param("responseComplete", response != null && response.complete())
                .query(UUID.class).optional();
        String resolved = jdbc.sql("SELECT outcome_class FROM ops.lc_description_command_attempt WHERE id = :attemptId")
                .param("attemptId", attemptId).query(String.class).single();
        String errorCode = jdbc.sql("SELECT error_code FROM ops.lc_description_command_attempt WHERE id = :attemptId")
                .param("attemptId", attemptId).query(String.class).optional().orElse(null);
        String nativeTask = jdbc.sql("SELECT native_task_key FROM ops.lc_description_command_attempt WHERE id = :attemptId")
                .param("attemptId", attemptId).query(String.class).optional().orElse(null);
        return new DescriptionWriteResult(DescriptionWriteResult.Outcome.valueOf(resolved),
                result.nativeStatus(), nativeTask, null, result.completedAt(), errorCode,
                result.retryAfterSeconds(), response);
    }

    /** Record one readback and take back the match state the database derived. */
    public String transitionReadback(UUID readbackId, UUID commandId, long fence, String owner) {
        UUID attemptId = jdbc.sql("""
                SELECT id FROM ops.lc_description_command_attempt
                 WHERE command_id = :commandId AND purpose = 'READBACK' AND fence_token = :fence
                 ORDER BY attempt_no DESC LIMIT 1
                """)
                .param("commandId", commandId).param("fence", fence)
                .query(UUID.class).single();
        return jdbc.sql("""
                SELECT ops.record_lc_description_command_readback(:readbackId, :commandId, :attemptId,
                        :fence, :owner, :correlationId)
                """)
                .param("readbackId", readbackId).param("commandId", commandId)
                .param("attemptId", attemptId).param("fence", fence).param("owner", owner)
                .param("correlationId", com.mimococo.marketops.shared.CorrelationId.current())
                .query(String.class).single();
    }

    public List<String> gateReasons(UUID commandId) {
        String joined = jdbc.sql("SELECT array_to_string(ops.evaluate_lc_description_write_gate(:commandId), ',')")
                .param("commandId", commandId).query(String.class).single();
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split(","));
    }

    public boolean isExactRestoration(UUID commandId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT a.restores_command_id IS NOT NULL FROM ops.lc_description_command c
                  JOIN ops.lc_action a ON a.id=c.action_id WHERE c.id=:id
                """).param("id", commandId).query(Boolean.class).single());
    }

    public Optional<String> restoreVersionToken(UUID commandId) {
        return jdbc.sql("SELECT ops.lc_restoration_preflight_version(:id)")
                .param("id", commandId).query(String.class).optional();
    }

    /** How many commands of a store are neither terminal nor waiting on a person. */
    public int inFlightCount(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                SELECT count(*) FROM ops.lc_description_command
                 WHERE organization_id = :organizationId
                   AND (:storeId IS NULL OR store_id = :storeId)
                   AND state IN ('PENDING', 'LEASED', 'EXECUTING', 'PLATFORM_PENDING',
                                 'READBACK_PENDING', 'RETRY_WAIT', 'COMPENSATION_PENDING')
                """)
                .param("organizationId", organizationId)
                .param("storeId", new SqlParameterValue(Types.OTHER, storeId))
                .query(Integer.class).single();
    }

    public Optional<ListingDescriptionCommandView> view(UUID commandId) {
        Optional<CommandRow> row = row(commandId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        CommandRow command = row.get();
        List<ListingDescriptionCommandView.Attempt> attempts = jdbc.sql("""
                SELECT id, attempt_no, purpose, outcome_class, native_status, error_code,
                       started_at, completed_at
                  FROM ops.lc_description_command_attempt
                 WHERE command_id = :commandId ORDER BY attempt_no
                """)
                .param("commandId", commandId)
                .query((ResultSet rs, int index) -> new ListingDescriptionCommandView.Attempt(
                        rs.getObject("id", UUID.class), rs.getInt("attempt_no"),
                        rs.getString("purpose"), rs.getString("outcome_class"),
                        rs.getString("native_status"), rs.getString("error_code"),
                        instantOf(rs, "started_at"), instantOf(rs, "completed_at")))
                .list();
        List<ListingDescriptionCommandView.Readback> readbacks = jdbc.sql("""
                SELECT id, match_state, observed_text_digest, observed_kiz_marked, observed_at
                  FROM ops.lc_description_command_readback
                 WHERE command_id = :commandId ORDER BY observed_at
                """)
                .param("commandId", commandId)
                .query((ResultSet rs, int index) -> new ListingDescriptionCommandView.Readback(
                        rs.getObject("id", UUID.class), rs.getString("match_state"),
                        rs.getString("observed_text_digest"),
                        rs.getObject("observed_kiz_marked", Boolean.class), instantOf(rs, "observed_at")))
                .list();
        List<ListingDescriptionCommandView.ExecutionReceipt> receipts=jdbc.sql("""
                SELECT id,execution_state,readback_id,(evidence->>'mutationAttemptId')::uuid AS mutation_id,
                    (evidence->>'nativeStatusAttemptId')::uuid AS status_id,
                    ARRAY(SELECT jsonb_array_elements_text(evidence->'gaps')) AS gaps,
                    recorded_at,task_event_id,task_recorded_at
                  FROM ops.lc_execution_receipt WHERE command_id=:id ORDER BY recorded_at,id
                """).param("id",commandId).query((rs,n)->new ListingDescriptionCommandView.ExecutionReceipt(
                        rs.getObject("id",UUID.class),rs.getString("execution_state"),rs.getObject("readback_id",UUID.class),
                        rs.getObject("mutation_id",UUID.class),rs.getObject("status_id",UUID.class),
                        java.util.Arrays.asList((String[])rs.getArray("gaps").getArray()),instantOf(rs,"recorded_at"),
                        rs.getObject("task_event_id",UUID.class),instantOf(rs,"task_recorded_at"))).list();
        return Optional.of(jdbc.sql("""
                SELECT failure_code, created_at, updated_at, terminal_at
                  FROM ops.lc_description_command WHERE id = :commandId
                """)
                .param("commandId", commandId)
                .query((ResultSet rs, int index) -> new ListingDescriptionCommandView(
                        command.id(), command.actionId(), command.recommendationId(), command.storeId(),
                        command.platformListingId(), command.platformCode(), command.state(),
                        command.priorTextDigest(), command.targetTextDigest(),
                        command.priorText() != null, command.kizMarkedDeclared(),
                        command.equivalenceRule(), command.affectedSetDigest(), command.attemptNo(),
                        command.retryBudgetRemaining(), rs.getString("failure_code"),
                        command.approvalExpiresAt(), instantOf(rs, "created_at"),
                        instantOf(rs, "updated_at"), instantOf(rs, "terminal_at"), attempts, readbacks, receipts))
                .single());
    }

    private static String headersJson(java.util.Map<String, String> headers) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var entry : headers.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escapeJson(entry.getKey())).append('"').append(':')
                    .append('"').append(escapeJson(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final String COMMAND_SELECT = """
            SELECT c.id, c.organization_id, c.recommendation_id, c.action_id, c.store_id,
                   c.platform_listing_id, c.native_listing_key, c.platform_code, c.capability_id,
                   c.idempotency_key, c.prior_text, c.prior_text_digest, c.target_text,
                   c.target_text_digest, c.kiz_marked_declared, c.equivalence_rule,
                   c.length_bound_min, c.length_bound_max, c.affected_set_digest, c.state,
                   c.attempt_no, c.retry_budget_remaining, c.fence_token, c.lease_owner,
                   c.requested_operation, c.approval_expires_at
              FROM ops.lc_description_command c
              JOIN core.platform_listing listing
                ON listing.id = c.platform_listing_id AND listing.organization_id = c.organization_id
            """;

    private static CommandRow mapCommand(ResultSet rs, int index) throws SQLException {
        return new CommandRow(
                rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getObject("recommendation_id", UUID.class), rs.getObject("action_id", UUID.class),
                rs.getObject("store_id", UUID.class), rs.getObject("platform_listing_id", UUID.class),
                rs.getString("native_listing_key"), rs.getString("platform_code"),
                rs.getObject("capability_id", UUID.class), rs.getString("idempotency_key"),
                rs.getString("prior_text"), rs.getString("prior_text_digest"),
                rs.getString("target_text"), rs.getString("target_text_digest"),
                rs.getBoolean("kiz_marked_declared"), rs.getString("equivalence_rule"),
                rs.getInt("length_bound_min"), rs.getInt("length_bound_max"),
                rs.getString("affected_set_digest"), rs.getString("state"), rs.getInt("attempt_no"),
                rs.getInt("retry_budget_remaining"), rs.getLong("fence_token"),
                rs.getString("lease_owner"), rs.getString("requested_operation"),
                instantOf(rs, "approval_expires_at"));
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static SqlParameterValue ts(Instant instant) {
        return new SqlParameterValue(Types.TIMESTAMP, instant == null ? null : Timestamp.from(instant));
    }
}
