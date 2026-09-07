package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.AdBidCommandView;
import java.math.BigDecimal;
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
 * The application's only access to the advertising command tables.
 *
 * <p>Every mutating method is a call to a {@code SECURITY DEFINER} function,
 * because the application role holds {@code SELECT} and nothing else on these
 * tables. That is not a convention this class is keeping: an {@code UPDATE}
 * written here would fail at the database, which is the point.
 */
@Repository
public class AdBidCommandRepository {

    private final JdbcClient jdbc;

    AdBidCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Everything a worker needs about a command it is about to act on. */
    public record CommandRow(
            UUID id, UUID organizationId, UUID recommendationId, UUID storeId,
            UUID adNativeObjectId, String nativeCampaignKey, String nativeObjectKey,
            String platformCode, UUID capabilityId,
            String idempotencyKey, String currencyCode, String bidUnitCode,
            String direction, String candidateBasis, String materialityRoute,
            BigDecimal priorBidAmount, BigDecimal targetBidAmount, String affectedSetDigest,
            String state, int attemptNo, int retryBudgetRemaining, long fenceToken,
            String leaseOwner, String requestedOperation, Instant approvalExpiresAt) {
    }

    public UUID create(UUID recommendationId, long expectedVersion,
            UUID reservationId, String correlationId) {
        return jdbc.sql("""
                SELECT ops.create_ad_bid_command(:recommendationId, :expectedVersion,
                        :reservationId, :correlationId)
                """)
                .param("recommendationId", recommendationId)
                .param("expectedVersion", expectedVersion)
                .param("reservationId", reservationId)
                .param("correlationId", correlationId)
                .query(UUID.class).single();
    }

    public Optional<CommandRow> row(UUID commandId) {
        return jdbc.sql(COMMAND_SELECT + " WHERE c.id = :commandId")
                .param("commandId", commandId)
                .query(AdBidCommandRepository::mapCommand)
                .optional();
    }

    public Optional<UUID> forRecommendation(UUID recommendationId) {
        return jdbc.sql("SELECT id FROM ops.ad_bid_command"
                        + " WHERE recommendation_id = :recommendationId")
                .param("recommendationId", recommendationId)
                .query(UUID.class)
                .optional();
    }

    /**
     * Commands a worker may claim now.
     *
     * <p>Three disjoint reasons: ordinary work waiting, a readback an operator
     * authorised, and a compensation nobody holds. A command in any other state
     * is either finished or waiting on a person.
     */
    public List<UUID> claimable(Instant now, int limit) {
        return jdbc.sql("""
                SELECT id FROM ops.ad_bid_command
                 WHERE (state IN ('PENDING', 'RETRY_WAIT')
                        AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                        AND retry_budget_remaining > 0)
                    OR (state = 'UNKNOWN_REQUIRES_READBACK' AND requested_operation = 'READBACK')
                    OR (state IN ('COMPENSATION_PENDING','PLATFORM_PENDING') AND lease_owner IS NULL
                        AND (next_attempt_at IS NULL OR next_attempt_at<=:now))
                 ORDER BY created_at
                 LIMIT :limit
                """)
                .param("now", ts(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    public long lease(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_ad_bid_command(:commandId, :owner, :seconds)")
                .param("commandId", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    public long leaseReadback(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_ad_bid_readback(:commandId, :owner, :seconds)")
                .param("commandId", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    public long leaseStatus(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_ad_bid_status(:id,:owner,:seconds)")
                .param("id", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    public void deferObservation(UUID commandId, long fence, String owner, int seconds) {
        jdbc.sql("SELECT ops.defer_ad_bid_observation(:id,:fence,:owner,:seconds)")
                .param("id", commandId).param("fence", fence).param("owner", owner)
                .param("seconds", seconds).query(Object.class).optional();
    }

    public boolean retryIsProven(UUID commandId) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT ops.ad_bid_retry_is_proven(:id)")
                .param("id", commandId).query(Boolean.class).single());
    }

    public Optional<String> nativeTaskKey(UUID commandId) {
        return jdbc.sql("""
                SELECT native_task_key FROM ops.ad_bid_command_attempt
                 WHERE command_id=:id AND native_task_key IS NOT NULL
                 ORDER BY attempt_no DESC LIMIT 1
                """).param("id", commandId).query(String.class).optional();
    }

    public boolean restoreAlreadyAttempted(UUID commandId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM ops.ad_bid_command_attempt
                 WHERE command_id=:id AND purpose='RESTORE')
                """).param("id", commandId).query(Boolean.class).single());
    }

    public long leaseCompensation(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_ad_bid_compensation(:commandId, :owner, :seconds)")
                .param("commandId", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    public String transition(UUID commandId, long fence, String owner, String toState,
            String failureCode, Integer retryDelaySeconds, UUID evidenceId) {
        return jdbc.sql("""
                SELECT ops.transition_ad_bid_command(:commandId, :fence, :owner, :toState,
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
                SELECT ops.open_ad_bid_command_attempt(:attemptId, :commandId, :purpose, :fence,
                        :owner, :requestDigest, :correlationId)
                """)
                .param("attemptId", attemptId).param("commandId", commandId)
                .param("purpose", purpose).param("fence", fence).param("owner", owner)
                .param("requestDigest", requestDigest).param("correlationId", correlationId)
                .query(UUID.class).single();
    }

    public void requestReadback(UUID commandId, long fence) {
        jdbc.sql("SELECT ops.request_ad_bid_readback(:commandId, :fence)")
                .param("commandId", commandId).param("fence", fence)
                .query(Object.class).optional();
    }

    public int recoverExpiredLeases() {
        return jdbc.sql("SELECT ops.recover_expired_ad_bid_command_leases()")
                .query(Integer.class).single();
    }

    /**
     * Complete one attempt from the exact bytes, and take the answer back.
     *
     * <p>The classification the adapter proposed is discarded here in favour of
     * the one the database derives from the frozen operation shape. What comes
     * back is the outcome that was actually recorded, so the caller routes on
     * what is durable rather than on what it hoped.
     */
    public com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult completeAttempt(
            UUID attemptId, long fence, String owner,
            com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult result,
            UUID contentId, String requestDigest) {
        var response = result.response();
        jdbc.sql("""
                SELECT ops.complete_ad_bid_command_attempt(:attemptId, :fence, :owner, :outcome,
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
                .query(UUID.class)
                .optional();

        String resolved = jdbc.sql("""
                SELECT outcome_class FROM ops.ad_bid_command_attempt WHERE id = :attemptId
                """)
                .param("attemptId", attemptId)
                .query(String.class)
                .single();
        String errorCode = jdbc.sql("""
                SELECT error_code FROM ops.ad_bid_command_attempt WHERE id = :attemptId
                """)
                .param("attemptId", attemptId)
                .query(String.class)
                .optional()
                .orElse(null);
        return new com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult(
                com.mimococo.marketops.marketplaceintegration.port.AdBidWriteResult.Outcome
                        .valueOf(resolved),
                result.nativeStatus(), result.nativeTaskKey(), result.observedBid(),
                result.observedCurrency(), result.observedUnit(), null, result.completedAt(),
                errorCode, response);
    }

    /**
     * Record one readback and take back the match state the database derived.
     *
     * <p>The caller never supplies the match: a caller that could name it could
     * name success.
     */
    public String transitionReadback(UUID readbackId, UUID commandId, long fence, String owner) {
        UUID attemptId = jdbc.sql("""
                SELECT id FROM ops.ad_bid_command_attempt
                 WHERE command_id = :commandId AND purpose = 'READBACK' AND fence_token = :fence
                 ORDER BY attempt_no DESC LIMIT 1
                """)
                .param("commandId", commandId).param("fence", fence)
                .query(UUID.class)
                .single();
        return jdbc.sql("""
                SELECT ops.record_ad_bid_command_readback(:readbackId, :commandId, :attemptId,
                        :fence, :owner, :correlationId)
                """)
                .param("readbackId", readbackId).param("commandId", commandId)
                .param("attemptId", attemptId).param("fence", fence).param("owner", owner)
                .param("correlationId", com.mimococo.marketops.shared.CorrelationId.current())
                .query(String.class)
                .single();
    }

    /** Bounded header serialisation; the allowlist was already applied upstream. */
    private static String headersJson(java.util.Map<String, String> headers) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (var entry : headers.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escapeJson(entry.getKey())).append('"')
                    .append(':')
                    .append('"').append(escapeJson(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** The gate's own answer, for the operator surface and for the workflow. */
    public List<String> gateReasons(UUID commandId) {
        String joined = jdbc.sql("""
                SELECT array_to_string(ops.evaluate_ad_bid_write_gate(:commandId), ',')
                """)
                .param("commandId", commandId)
                .query(String.class)
                .single();
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.split(","));
    }

    /** Commands that will not progress without a person. */
    public List<UUID> needingAttention(UUID storeId, int limit) {
        return jdbc.sql("""
                SELECT id FROM ops.ad_bid_command
                 WHERE store_id = :storeId
                   AND state IN ('UNKNOWN_REQUIRES_READBACK', 'READBACK_MISMATCH',
                                 'LATER_CHANGE_OR_MISMATCH_INVESTIGATION', 'MANUAL_RESOLUTION',
                                 'COMPENSATION_FAILED')
                 ORDER BY updated_at DESC
                 LIMIT :limit
                """)
                .param("storeId", storeId).param("limit", limit)
                .query(UUID.class).list();
    }

    /** The command with its attempts and readbacks, for a person to read. */
    public Optional<AdBidCommandView> view(UUID commandId) {
        Optional<CommandRow> row = row(commandId);
        if (row.isEmpty()) {
            return Optional.empty();
        }
        CommandRow command = row.get();
        List<AdBidCommandView.Attempt> attempts = jdbc.sql("""
                SELECT id, attempt_no, purpose, outcome_class, native_status, error_code,
                       started_at, completed_at
                  FROM ops.ad_bid_command_attempt
                 WHERE command_id = :commandId ORDER BY attempt_no
                """)
                .param("commandId", commandId)
                .query((ResultSet rs, int index) -> new AdBidCommandView.Attempt(
                        rs.getObject("id", UUID.class), rs.getInt("attempt_no"),
                        rs.getString("purpose"), rs.getString("outcome_class"),
                        rs.getString("native_status"), rs.getString("error_code"),
                        instantOf(rs, "started_at"), instantOf(rs, "completed_at")))
                .list();
        List<AdBidCommandView.Readback> readbacks = jdbc.sql("""
                SELECT id, match_state, observed_bid, currency_code, bid_unit_code, observed_at
                  FROM ops.ad_bid_command_readback
                 WHERE command_id = :commandId ORDER BY observed_at
                """)
                .param("commandId", commandId)
                .query((ResultSet rs, int index) -> new AdBidCommandView.Readback(
                        rs.getObject("id", UUID.class), rs.getString("match_state"),
                        rs.getBigDecimal("observed_bid"), rs.getString("currency_code"),
                        rs.getString("bid_unit_code"),instantOf(rs, "observed_at")))
                .list();
        return Optional.of(toView(command, attempts, readbacks));
    }

    private AdBidCommandView toView(CommandRow command,
            List<AdBidCommandView.Attempt> attempts,
            List<AdBidCommandView.Readback> readbacks) {
        return jdbc.sql("""
                SELECT failure_code, created_at, updated_at, terminal_at
                  FROM ops.ad_bid_command WHERE id = :commandId
                """)
                .param("commandId", command.id())
                .query((ResultSet rs, int index) -> new AdBidCommandView(
                        command.id(), command.recommendationId(), command.storeId(),
                        command.adNativeObjectId(), command.platformCode(), command.direction(),
                        command.candidateBasis(), command.materialityRoute(), command.state(),
                        command.currencyCode(), command.bidUnitCode(), command.priorBidAmount(),
                        command.targetBidAmount(), command.affectedSetDigest(),
                        command.attemptNo(), command.retryBudgetRemaining(),
                        rs.getString("failure_code"), command.approvalExpiresAt(),
                        instantOf(rs, "created_at"), instantOf(rs, "updated_at"),
                        instantOf(rs, "terminal_at"), attempts, readbacks))
                .single();
    }

    /** The purpose the last attempt served, so a resumed lease continues it. */
    public Optional<String> latestAttemptPurpose(UUID commandId) {
        return jdbc.sql("""
                SELECT purpose FROM ops.ad_bid_command_attempt
                 WHERE command_id = :commandId ORDER BY attempt_no DESC LIMIT 1
                """)
                .param("commandId", commandId)
                .query(String.class)
                .optional();
    }

    /** The version token the latest readback observed, for a conditional restore. */
    public Optional<String> restoreVersionToken(UUID commandId) {
        return jdbc.sql("""
                SELECT obs.version_token
                  FROM ops.ad_bid_command_readback rb
                  JOIN raw.ad_bid_response_observation obs ON obs.id = rb.raw_observation_id
                 WHERE rb.command_id = :commandId
                 ORDER BY rb.observed_at DESC LIMIT 1
                """)
                .param("commandId", commandId)
                .query(String.class)
                .optional();
    }

    private static final String COMMAND_SELECT = """
            SELECT c.id, c.organization_id, c.recommendation_id, c.store_id,
                   c.ad_native_object_id, object.native_campaign_key, object.native_object_key,
                   c.platform_code, c.capability_id, c.idempotency_key,
                   c.currency_code, c.bid_unit_code, c.direction, c.candidate_basis,
                   c.materiality_route, c.prior_bid_amount, c.target_bid_amount,
                   c.affected_set_digest, c.state, c.attempt_no, c.retry_budget_remaining,
                   c.fence_token, c.lease_owner, c.requested_operation, c.approval_expires_at
              FROM ops.ad_bid_command c
              JOIN core.ad_native_object object
                ON object.id = c.ad_native_object_id
               AND object.organization_id = c.organization_id
            """;

    private static CommandRow mapCommand(ResultSet rs, int index) throws SQLException {
        return new CommandRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("recommendation_id", UUID.class),
                rs.getObject("store_id", UUID.class),
                rs.getObject("ad_native_object_id", UUID.class),
                rs.getString("native_campaign_key"),
                rs.getString("native_object_key"),
                rs.getString("platform_code"),
                rs.getObject("capability_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("currency_code"),
                rs.getString("bid_unit_code"),
                rs.getString("direction"),
                rs.getString("candidate_basis"),
                rs.getString("materiality_route"),
                rs.getBigDecimal("prior_bid_amount"),
                rs.getBigDecimal("target_bid_amount"),
                rs.getString("affected_set_digest"),
                rs.getString("state"),
                rs.getInt("attempt_no"),
                rs.getInt("retry_budget_remaining"),
                rs.getLong("fence_token"),
                rs.getString("lease_owner"),
                rs.getString("requested_operation"),
                instantOf(rs, "approval_expires_at"));
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static SqlParameterValue ts(Instant instant) {
        return new SqlParameterValue(Types.TIMESTAMP,
                instant == null ? null : Timestamp.from(instant));
    }
}
