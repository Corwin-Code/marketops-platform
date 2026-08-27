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

    PriceCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Create a command in its pending state. */
    public void insert(UUID id, UUID organizationId, UUID recommendationId,
                       UUID approvalDecisionId, UUID storeId, UUID platformListingVariantId,
                       String platformCode, UUID capabilityId, String idempotencyKey,
                       String currencyCode, BigDecimal priorPrice, BigDecimal targetPrice,
                       UUID priorPriceObservationId, String entityVersionDigest,
                       int retryBudget, Instant now) {
        jdbc.sql("""
                        INSERT INTO ops.price_command (
                            id, organization_id, recommendation_id, approval_decision_id,
                            store_id, platform_listing_variant_id, platform_code,
                            capability_id, idempotency_key, currency_code, prior_price,
                            target_price, prior_price_observation_id, entity_version_digest,
                            state, attempt_no, retry_budget_remaining, fence_token,
                            next_attempt_at, created_at, updated_at)
                        VALUES (:id, :organizationId, :recommendationId, :approvalDecisionId,
                            :storeId, :platformListingVariantId, :platformCode, :capabilityId,
                            :idempotencyKey, :currencyCode, :priorPrice, :targetPrice,
                            :priorPriceObservationId, :entityVersionDigest, 'PENDING', 0,
                            :retryBudget, 1, :now, :now, :now)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("recommendationId", recommendationId)
                .param("approvalDecisionId", approvalDecisionId)
                .param("storeId", storeId)
                .param("platformListingVariantId", platformListingVariantId)
                .param("platformCode", platformCode)
                .param("capabilityId", capabilityId)
                .param("idempotencyKey", idempotencyKey)
                .param("currencyCode", currencyCode)
                .param("priorPrice", priorPrice)
                .param("targetPrice", targetPrice)
                .param("priorPriceObservationId", priorPriceObservationId)
                .param("entityVersionDigest", entityVersionDigest)
                .param("retryBudget", retryBudget)
                .param("now", Timestamp.from(now))
                .update();
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
                         WHERE state IN ('PENDING', 'RETRY_WAIT')
                           AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                           AND retry_budget_remaining > 0
                         ORDER BY created_at
                         LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** Record that a call is being made. */
    public void openAttempt(UUID id, UUID commandId, int attemptNo, String purpose,
                            long fenceToken, String leaseOwner, Instant startedAt,
                            String correlationId) {
        jdbc.sql("""
                        INSERT INTO ops.price_command_attempt (
                            id, command_id, attempt_no, purpose, fence_token, lease_owner,
                            started_at, outcome_class, correlation_id)
                        VALUES (:id, :commandId, :attemptNo, :purpose, :fenceToken,
                            :leaseOwner, :startedAt, 'IN_FLIGHT', :correlationId)
                        """)
                .param("id", id)
                .param("commandId", commandId)
                .param("attemptNo", attemptNo)
                .param("purpose", purpose)
                .param("fenceToken", fenceToken)
                .param("leaseOwner", leaseOwner)
                .param("startedAt", Timestamp.from(startedAt))
                .param("correlationId", correlationId)
                .update();
    }

    /** Record what the platform answered. Permitted exactly once per attempt. */
    public void completeAttempt(UUID id, String outcomeClass, String nativeStatus,
                                String nativeTaskKey, UUID rawObservationId, String errorCode,
                                Instant completedAt) {
        jdbc.sql("""
                        UPDATE ops.price_command_attempt
                        SET completed_at = :completedAt, outcome_class = :outcomeClass,
                            native_status = :nativeStatus, native_task_key = :nativeTaskKey,
                            raw_observation_id = :rawObservationId, error_code = :errorCode
                        WHERE id = :id
                        """)
                .param("completedAt", Timestamp.from(completedAt))
                .param("outcomeClass", outcomeClass)
                .param("nativeStatus", nativeStatus)
                .param("nativeTaskKey", nativeTaskKey)
                .param("rawObservationId", rawObservationId)
                .param("errorCode", errorCode)
                .param("id", id)
                .update();
    }

    /** Record what a later read of the platform observed. */
    public void insertReadback(UUID id, UUID commandId, UUID attemptId, Instant observedAt,
                               BigDecimal observedPrice, String currencyCode, String matchState,
                               UUID rawObservationId, String correlationId) {
        jdbc.sql("""
                        INSERT INTO ops.price_command_readback (
                            id, command_id, attempt_id, observed_at, observed_price,
                            currency_code, match_state, raw_observation_id, correlation_id)
                        VALUES (:id, :commandId, :attemptId, :observedAt, :observedPrice,
                            :currencyCode, :matchState, :rawObservationId, :correlationId)
                        """)
                .param("id", id)
                .param("commandId", commandId)
                .param("attemptId", attemptId)
                .param("observedAt", Timestamp.from(observedAt))
                .param("observedPrice", observedPrice)
                .param("currencyCode", currencyCode)
                .param("matchState", matchState)
                .param("rawObservationId", rawObservationId)
                .param("correlationId", correlationId)
                .update();
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
