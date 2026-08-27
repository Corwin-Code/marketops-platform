package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.PriceCommandState;
import com.mimococo.marketops.marketplaceintegration.PriceCommandView;
import java.math.BigDecimal;
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
 * The command outbox, read directly and moved only through the database's own
 * functions.
 *
 * <p>Every state change here calls a function rather than issuing an update.
 * The application role holds no UPDATE privilege on the command row at all, so
 * the lease, the fence token, the reviewed transition set, the rule that success
 * requires a matching readback and the rule that a restore requires the platform
 * to still hold what this command wrote are properties of the database. An
 * arbitrary SQL client connecting as this role cannot bypass any of them, which
 * is a stronger guarantee than a well-written caller.
 */
@Repository
public class PriceCommandRepository {

    private final JdbcClient jdbc;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    PriceCommandRepository(JdbcClient jdbc, tools.jackson.databind.ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Create a command in its pending state. */
    public UUID create(UUID recommendationId, long expectedVersion, UUID actorId,
                       String correlationId) {
        return jdbc.sql("SELECT ops.create_price_command(:recommendation, :version, :actor, :correlation)")
                .param("recommendation", recommendationId)
                .param("version", expectedVersion)
                .param("actor", actorId)
                .param("correlation", correlationId)
                .query(UUID.class).single();
    }

    /**
     * Claim a command for one worker.
     *
     * <p>The write gate is evaluated inside the same transaction that takes the
     * claim, so a switch thrown while a worker is deciding cannot be missed.
     *
     * @return the fence token the worker must present on every transition
     */
    public long lease(UUID commandId, String leaseOwner, int leaseSeconds) {
        return jdbc.sql("SELECT ops.lease_price_command(:commandId, :owner, :seconds)")
                .param("commandId", commandId)
                .param("owner", leaseOwner)
                .param("seconds", leaseSeconds)
                .query(Long.class)
                .single();
    }

    public void requestReadback(UUID commandId, long fence) {
        jdbc.sql("SELECT ops.request_price_readback(:id, :fence)")
                .param("id", commandId).param("fence", fence).query(Boolean.class).single();
    }

    public long leaseReadback(UUID commandId, String owner, int seconds) {
        return jdbc.sql("SELECT ops.lease_price_readback(:id, :owner, :seconds)")
                .param("id", commandId).param("owner", owner).param("seconds", seconds)
                .query(Long.class).single();
    }

    /**
     * Move a command between states.
     *
     * @return the state the command now stands in
     */
    public String transition(UUID commandId, long fenceToken, String leaseOwner,
                             String toState, String failureCode, Integer retryDelaySeconds,
                             UUID evidenceId) {
        return jdbc.sql("""
                        SELECT ops.transition_price_command(
                            :commandId, :fenceToken, :leaseOwner, :toState, :failureCode,
                            :retryDelaySeconds, :evidenceId)
                        """)
                .param("commandId", commandId)
                .param("fenceToken", fenceToken)
                .param("leaseOwner", leaseOwner)
                .param("toState", toState)
                .param("failureCode", failureCode)
                .param("retryDelaySeconds", retryDelaySeconds)
                .param("evidenceId", evidenceId)
                .query(String.class)
                .single();
    }

    /**
     * The verified price-change capability of one marketplace.
     *
     * <p>Verification, active status and the absence of a deprecation are all
     * required. A capability that was verified once and has since been deprecated
     * is not one a write may go through, and returning it would move the refusal
     * from here to the moment of the call.
     */
    public Optional<UUID> priceChangeCapability(String platformCode) {
        return jdbc.sql("""
                        SELECT id FROM platform.platform_capability
                         WHERE platform_code = :platformCode
                           AND capability_code = 'price-change'
                           AND read_write_class = 'WRITE'
                           AND status = 'ACTIVE'
                           AND verification_state = 'VERIFIED'
                           AND deprecated_at IS NULL
                        """)
                .param("platformCode", platformCode)
                .query(UUID.class)
                .optional();
    }

    /** Claim a command an operator authorised a restore for. */
    public long leaseCompensation(UUID commandId, String leaseOwner, int leaseSeconds) {
        return jdbc.sql("SELECT ops.lease_price_compensation(:commandId, :owner, :seconds)")
                .param("commandId", commandId)
                .param("owner", leaseOwner)
                .param("seconds", leaseSeconds)
                .query(Long.class)
                .single();
    }

    /**
     * Close every command whose retry budget is spent.
     *
     * <p>A waiting command with nothing left to spend would otherwise sit in the
     * queue forever: the claim query skips it, and nothing else moves it. Closing
     * it as failed is the honest end, and the edge from waiting to a terminal
     * failure is already in the reviewed transition set.
     */
    public int failExhaustedRetries() {
        List<UUID> exhausted = jdbc.sql("""
                        SELECT id FROM ops.price_command
                         WHERE state = 'RETRY_WAIT' AND retry_budget_remaining = 0
                        """)
                .query(UUID.class)
                .list();
        exhausted.forEach(commandId -> transition(commandId, 0L, null, "FAILED_FINAL",
                "retry_budget_exhausted", null, null));
        return exhausted.size();
    }

    /** What the previous attempt was doing, so a retry resumes rather than repeats. */
    public Optional<String> latestAttemptPurpose(UUID commandId) {
        return jdbc.sql("""
                        SELECT purpose FROM ops.price_command_attempt
                         WHERE command_id = :commandId
                         ORDER BY started_at DESC, attempt_no DESC
                         LIMIT 1
                        """)
                .param("commandId", commandId)
                .query(String.class)
                .optional();
    }

    /** Why the write gate is currently closed for a command, if it is. */
    public List<String> gateReasons(UUID commandId) {
        String[] reasons = jdbc.sql("SELECT ops.evaluate_price_write_gate(:commandId)")
                .param("commandId", commandId)
                .query((rows, rowNumber) -> (String[]) rows.getArray(1).getArray())
                .single();
        return List.of(reasons);
    }

    /** Hand back every command whose worker stopped holding it. */
    public int recoverExpiredLeases() {
        return jdbc.sql("SELECT ops.recover_expired_price_command_leases()")
                .query(Integer.class)
                .single();
    }

    /** Commands ready to be worked on now, oldest first. */
    public List<UUID> claimable(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM ops.price_command
                         WHERE (state IN ('PENDING', 'RETRY_WAIT')
                           AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                           AND retry_budget_remaining > 0)
                           OR (state = 'UNKNOWN_REQUIRES_READBACK' AND requested_operation = 'READBACK')
                           OR (state = 'COMPENSATION_PENDING' AND lease_owner IS NULL)
                         ORDER BY created_at
                         LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** Record that a call is being made. */
    public void openAttempt(UUID id, UUID commandId, String purpose, long fenceToken,
                            String leaseOwner, String requestDigest, String correlationId) {
        jdbc.sql("SELECT ops.open_price_command_attempt(:id, :command, :purpose, :fence, :owner, :digest, :correlation)")
                .param("id", id).param("command", commandId).param("purpose", purpose)
                .param("fence", fenceToken).param("owner", leaseOwner).param("digest", requestDigest)
                .param("correlation", correlationId).query(UUID.class).single();
    }

    public com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult completeAttempt(UUID id, long fenceToken, String leaseOwner,
            com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult result,
            UUID contentId, String requestDigest) {
        var response = result.response();
        jdbc.sql("""
                SELECT ops.complete_price_command_attempt(:id, :fence, :owner, :outcome, :status,
                    :task, :error, :content, :body, :httpStatus, CAST(:headers AS jsonb), :evidence, :digest, :complete)
                """)
                .param("id", id).param("fence", fenceToken).param("owner", leaseOwner)
                .param("outcome", result.outcome().name()).param("status", result.nativeStatus())
                .param("task", result.nativeTaskKey()).param("error", result.errorCode())
                .param("content", contentId).param("body", result.body())
                .param("httpStatus", response == null ? null : response.httpStatus())
                .param("headers", response == null ? null : objectMapper.writeValueAsString(response.headers()))
                .param("evidence", response == null ? null : response.evidenceClass())
                .param("complete", response == null || response.complete())
                .param("digest", requestDigest).query(UUID.class).optional().orElse(null);
        return jdbc.sql("""
                SELECT a.outcome_class,a.native_status,a.native_task_key,a.error_code,a.completed_at,
                       evidence.observed_price,evidence.observed_currency
                  FROM ops.price_command_attempt a
                  LEFT JOIN raw.price_response_observation evidence ON evidence.id=a.raw_observation_id
                 WHERE a.id=:id AND a.outcome_class <> 'IN_FLIGHT'
                """).param("id",id).query((row,index) ->
                    new com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult(
                        com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult.Outcome.valueOf(row.getString("outcome_class")),
                        row.getString("native_status"),row.getString("native_task_key"),
                        row.getBigDecimal("observed_price"),row.getString("observed_currency"),
                        result.body(),row.getTimestamp("completed_at").toInstant(),row.getString("error_code"),response)).single();
    }

    /** The database derives the comparison from the exact custodied response. */
    public String insertReadback(UUID id, UUID commandId, UUID attemptId, long fence,
                                 String owner, String correlation) {
        return jdbc.sql("SELECT ops.record_price_command_readback(:id, :command, :attempt, :fence, :owner, :correlation)")
                .param("id", id).param("command", commandId).param("attempt", attemptId)
                .param("fence", fence).param("owner", owner).param("correlation", correlation)
                .query(String.class).single();
    }

    public Optional<String> restoreVersion(UUID commandId, long fence) {
        return jdbc.sql("""
                SELECT evidence.version_token FROM ops.price_command_readback r
                JOIN ops.price_command_attempt a ON a.id = r.attempt_id
                JOIN raw.price_response_observation evidence ON evidence.id = r.raw_observation_id
                WHERE r.command_id = :command AND a.fence_token = :fence
                    AND r.match_state = 'MATCHES_TARGET' AND evidence.version_token IS NOT NULL
                ORDER BY r.observed_at DESC LIMIT 1
                """).param("command", commandId).param("fence", fence).query(String.class).optional();
    }

    public boolean conditionalRestoreAvailable(UUID capabilityId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM platform.capability_operation
                    WHERE capability_id = :capability AND operation = 'RESTORE'
                        AND conditional_write_header IS NOT NULL
                        AND status = 'ACTIVE' AND verification_state = 'VERIFIED')
                """).param("capability", capabilityId).query(Boolean.class).single();
    }

    /** The most recent asynchronous handle a platform gave for this command. */
    public Optional<String> latestTaskKey(UUID commandId) {
        return jdbc.sql("""
                        SELECT native_task_key FROM ops.price_command_attempt
                         WHERE command_id = :commandId AND native_task_key IS NOT NULL
                         ORDER BY started_at DESC, attempt_no DESC
                         LIMIT 1
                        """)
                .param("commandId", commandId)
                .query(String.class)
                .optional();
    }

    /** One command without its attempts, for a worker that only needs the row. */
    public Optional<CommandRow> row(UUID commandId) {
        return jdbc.sql("""
                        SELECT id, organization_id, recommendation_id, store_id,
                               platform_listing_variant_id, platform_code, capability_id,
                               idempotency_key, currency_code, prior_price, target_price,
                               state, attempt_no, retry_budget_remaining, fence_token,
                               lease_owner
                          FROM ops.price_command WHERE id = :commandId
                        """)
                .param("commandId", commandId)
                .query(PriceCommandRepository::mapRow)
                .optional();
    }

    /** One command with everything that happened to it. */
    public Optional<PriceCommandView> find(UUID commandId) {
        return jdbc.sql(SELECT_COMMAND + " WHERE id = :commandId")
                .param("commandId", commandId)
                .query(PriceCommandRepository::map)
                .optional()
                .map(this::withHistory);
    }

    /** The command created for one proposal, if there is one. */
    public Optional<PriceCommandView> forRecommendation(UUID recommendationId) {
        return jdbc.sql(SELECT_COMMAND + " WHERE recommendation_id = :recommendationId")
                .param("recommendationId", recommendationId)
                .query(PriceCommandRepository::map)
                .optional()
                .map(this::withHistory);
    }

    /** Commands of one store that a person has to look at. */
    public List<PriceCommandView> needingOperator(UUID storeId, int limit) {
        return jdbc.sql(SELECT_COMMAND + """
                         WHERE store_id = :storeId
                           AND state IN ('UNKNOWN_REQUIRES_READBACK', 'READBACK_MISMATCH',
                                         'MANUAL_RESOLUTION', 'COMPENSATION_FAILED')
                         ORDER BY created_at
                         LIMIT :limit
                        """)
                .param("storeId", storeId)
                .param("limit", limit)
                .query(PriceCommandRepository::map)
                .list()
                .stream()
                .map(this::withHistory)
                .toList();
    }

    /**
     * The total proportional movement this product applied since an instant.
     *
     * <p>Only commands that actually reached a marketplace count. A command that
     * was refused or failed before any attempt changed nothing, and letting it
     * consume the day's allowance would block real work for no reason.
     */
    public BigDecimal cumulativeChangeRate(UUID platformListingVariantId, Instant since) {
        return jdbc.sql("""
                        SELECT coalesce(sum(abs(
                                   (command.target_price - command.prior_price)
                                   / nullif(command.prior_price, 0))), 0)
                          FROM ops.price_command AS command
                         WHERE command.platform_listing_variant_id = :variantId
                           AND command.terminal_at >= :since
                           AND command.state IN ('SUCCEEDED', 'COMPENSATED')
                        """)
                .param("variantId", platformListingVariantId)
                .param("since", Timestamp.from(since))
                .query(BigDecimal.class)
                .single();
    }

    /** When this product last changed the price of one listing variant. */
    public Optional<Instant> lastChangeAt(UUID platformListingVariantId) {
        return jdbc.sql("""
                        SELECT max(terminal_at) FROM ops.price_command
                         WHERE platform_listing_variant_id = :variantId
                           AND state IN ('SUCCEEDED', 'COMPENSATED')
                        """)
                .param("variantId", platformListingVariantId)
                .query(Timestamp.class)
                .optional()
                .map(Timestamp::toInstant);
    }

    /** How many commands of one scope are still in flight. */
    public int inFlightCount(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT count(*) FROM ops.price_command
                         WHERE organization_id = :organizationId
                           AND (CAST(:storeId AS uuid) IS NULL OR store_id = :storeId)
                           AND state NOT IN ('SUCCEEDED', 'FAILED_FINAL', 'COMPENSATED',
                                             'COMPENSATION_FAILED')
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query(Integer.class)
                .single();
    }

    private PriceCommandView withHistory(PriceCommandView command) {
        List<PriceCommandView.Attempt> attempts = jdbc.sql("""
                        SELECT id, attempt_no, purpose, started_at, completed_at,
                               outcome_class, native_status, native_task_key,
                               raw_observation_id, error_code
                          FROM ops.price_command_attempt
                         WHERE command_id = :commandId
                         ORDER BY started_at, attempt_no
                        """)
                .param("commandId", command.id())
                .query((rows, rowNumber) -> {
                    Timestamp completed = rows.getTimestamp("completed_at");
                    return new PriceCommandView.Attempt(
                            rows.getObject("id", UUID.class),
                            rows.getInt("attempt_no"),
                            rows.getString("purpose"),
                            rows.getTimestamp("started_at").toInstant(),
                            completed == null ? null : completed.toInstant(),
                            rows.getString("outcome_class"),
                            rows.getString("native_status"),
                            rows.getString("native_task_key"),
                            rows.getObject("raw_observation_id", UUID.class),
                            rows.getString("error_code"));
                })
                .list();
        List<PriceCommandView.Readback> readbacks = jdbc.sql("""
                        SELECT id, observed_at, observed_price, currency_code, match_state,
                               raw_observation_id
                          FROM ops.price_command_readback
                         WHERE command_id = :commandId
                         ORDER BY observed_at
                        """)
                .param("commandId", command.id())
                .query((rows, rowNumber) -> new PriceCommandView.Readback(
                        rows.getObject("id", UUID.class),
                        rows.getTimestamp("observed_at").toInstant(),
                        rows.getBigDecimal("observed_price"),
                        rows.getString("currency_code"),
                        rows.getString("match_state"),
                        rows.getObject("raw_observation_id", UUID.class)))
                .list();
        return new PriceCommandView(command.id(), command.recommendationId(),
                command.storeId(), command.platformListingVariantId(), command.platformCode(),
                command.idempotencyKey(), command.currencyCode(), command.priorPrice(),
                command.targetPrice(), command.state(), command.attemptNo(),
                command.retryBudgetRemaining(), command.failureCode(), command.leaseOwner(),
                command.leaseExpiresAt(), command.nextAttemptAt(), command.createdAt(),
                command.terminalAt(), attempts, readbacks);
    }

    private static final String SELECT_COMMAND = """
            SELECT id, recommendation_id, store_id, platform_listing_variant_id,
                   platform_code, idempotency_key, currency_code, prior_price, target_price,
                   state, attempt_no, retry_budget_remaining, failure_code, lease_owner,
                   lease_expires_at, next_attempt_at, created_at, terminal_at
              FROM ops.price_command
            """;

    private static PriceCommandView map(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp leaseExpires = rows.getTimestamp("lease_expires_at");
        Timestamp nextAttempt = rows.getTimestamp("next_attempt_at");
        Timestamp terminal = rows.getTimestamp("terminal_at");
        return new PriceCommandView(
                rows.getObject("id", UUID.class),
                rows.getObject("recommendation_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                rows.getString("platform_code"),
                rows.getString("idempotency_key"),
                rows.getString("currency_code"),
                rows.getBigDecimal("prior_price"),
                rows.getBigDecimal("target_price"),
                PriceCommandState.valueOf(rows.getString("state")),
                rows.getInt("attempt_no"),
                rows.getInt("retry_budget_remaining"),
                rows.getString("failure_code"),
                rows.getString("lease_owner"),
                leaseExpires == null ? null : leaseExpires.toInstant(),
                nextAttempt == null ? null : nextAttempt.toInstant(),
                rows.getTimestamp("created_at").toInstant(),
                terminal == null ? null : terminal.toInstant(),
                List.of(), List.of());
    }

    private static CommandRow mapRow(ResultSet rows, int rowNumber) throws SQLException {
        return new CommandRow(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("recommendation_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                rows.getString("platform_code"),
                rows.getObject("capability_id", UUID.class),
                rows.getString("idempotency_key"),
                rows.getString("currency_code"),
                rows.getBigDecimal("prior_price"),
                rows.getBigDecimal("target_price"),
                PriceCommandState.valueOf(rows.getString("state")),
                rows.getInt("attempt_no"),
                rows.getInt("retry_budget_remaining"),
                rows.getLong("fence_token"),
                rows.getString("lease_owner"));
    }

    /**
     * One command as a worker needs it.
     *
     * @param id the command
     * @param organizationId owning organization
     * @param recommendationId the proposal it executes
     * @param storeId store the listing sits on
     * @param platformListingVariantId the listing variant it changes
     * @param platformCode marketplace it targets
     * @param capabilityId the write capability being used
     * @param idempotencyKey identity a platform retry must not duplicate
     * @param currencyCode currency of both prices
     * @param priorPrice the price held before
     * @param targetPrice the price intended
     * @param state where it stands
     * @param attemptNo how many attempts have been made
     * @param retryBudgetRemaining how many retriable failures may still be absorbed
     * @param fenceToken the token a transition must present
     * @param leaseOwner the worker holding it, or {@code null}
     */
    public record CommandRow(UUID id, UUID organizationId, UUID recommendationId, UUID storeId,
                             UUID platformListingVariantId, String platformCode,
                             UUID capabilityId, String idempotencyKey, String currencyCode,
                             BigDecimal priorPrice, BigDecimal targetPrice,
                             PriceCommandState state, int attemptNo, int retryBudgetRemaining,
                             long fenceToken, String leaseOwner) {
    }
}
