package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The approved content changes, the commands that carry them out and every call they made (W2,
 * V0035).
 *
 * <p>Every state change names the fence of the lease it was made under, so a worker that lost its
 * lease cannot move a command another worker now holds. Events are only ever appended.
 */
@Repository
public class ContentCommandRepository {

    private static final String COMMAND_COLUMNS = """
            command.id, command.organization_id, command.change_id, command.store_id,
            command.platform_listing_variant_id, command.idempotency_key, command.state,
            command.gate_reasons, command.capability_id, command.credential_id, command.lease_owner,
            command.lease_expires_at, command.fence, command.retry_count, command.next_action_at,
            command.native_task_key, command.task_polls, command.readbacks, command.outcome_code,
            command.outcome_detail, command.created_at, command.updated_at, command.terminal_at,
            change.native_listing_key, change.offer_key, change.prior_title, change.prior_description,
            change.prior_observed_at, change.target_title, change.target_description,
            change.title_changed, change.description_changed, change.source_invocation_id,
            change.reason, change.approved_by_user_id, change.approved_at, change.approval_expires_at
            """;

    private final JdbcClient jdbc;

    ContentCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record one approved change. */
    public void insertChange(NewChange change) {
        jdbc.sql("""
                        INSERT INTO ops.content_change
                            (id, organization_id, store_id, platform_listing_variant_id, native_listing_key,
                             offer_key, prior_title, prior_description, prior_observed_at, target_title,
                             target_description, title_changed, description_changed, source_invocation_id,
                             reason, approved_by_user_id, approved_at, approval_expires_at, created_at)
                        VALUES (:id, :organizationId, :storeId, :variantId, :nativeListingKey, :offerKey,
                                :priorTitle, :priorDescription, :priorObservedAt, :targetTitle,
                                :targetDescription, :titleChanged, :descriptionChanged, :sourceInvocationId,
                                :reason, :approvedBy, :approvedAt, :approvalExpiresAt, :approvedAt)
                        """)
                .param("id", change.id())
                .param("organizationId", change.organizationId())
                .param("storeId", change.storeId())
                .param("variantId", change.platformListingVariantId())
                .param("nativeListingKey", change.nativeListingKey())
                .param("offerKey", change.offerKey())
                .param("priorTitle", change.priorTitle())
                .param("priorDescription", change.priorDescription())
                .param("priorObservedAt", timestamp(change.priorObservedAt()))
                .param("targetTitle", change.targetTitle())
                .param("targetDescription", change.targetDescription())
                .param("titleChanged", change.titleChanged())
                .param("descriptionChanged", change.descriptionChanged())
                .param("sourceInvocationId", change.sourceInvocationId())
                .param("reason", change.reason())
                .param("approvedBy", change.approvedByUserId())
                .param("approvedAt", Timestamp.from(change.approvedAt()))
                .param("approvalExpiresAt", Timestamp.from(change.approvalExpiresAt()))
                .update();
    }

    /** Create the one command that carries a change out. */
    public void insertCommand(UUID id, NewChange change, Instant at) {
        jdbc.sql("""
                        INSERT INTO ops.content_command
                            (id, organization_id, change_id, store_id, platform_listing_variant_id,
                             idempotency_key, state, next_action_at, created_at, updated_at)
                        VALUES (:id, :organizationId, :changeId, :storeId, :variantId, :idempotencyKey,
                                'PENDING', :at, :at, :at)
                        """)
                .param("id", id)
                .param("organizationId", change.organizationId())
                .param("changeId", change.id())
                .param("storeId", change.storeId())
                .param("variantId", change.platformListingVariantId())
                .param("idempotencyKey", "cc-" + change.id())
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Append one event; the sequence is the next number for the command. */
    public void appendEvent(NewEvent event) {
        jdbc.sql("""
                        INSERT INTO ops.content_command_event
                            (id, organization_id, command_id, sequence, kind, fence, state_after, http_status,
                             outcome, native_task_key, task_status, observed_title, observed_description,
                             title_match, description_match, detail, raw_observation_id, actor_user_id,
                             recorded_at)
                        SELECT :id, command.organization_id, command.id,
                               coalesce((SELECT max(sequence) FROM ops.content_command_event
                                          WHERE command_id = command.id), 0) + 1,
                               :kind, :fence, :stateAfter, :httpStatus, :outcome, :taskKey, :taskStatus,
                               :observedTitle, :observedDescription, :titleMatch, :descriptionMatch,
                               :detail, :rawObservationId, :actorUserId, :at
                          FROM ops.content_command AS command
                         WHERE command.id = :commandId
                        """)
                .param("id", event.id())
                .param("commandId", event.commandId())
                .param("kind", event.kind())
                .param("fence", event.fence())
                .param("stateAfter", event.stateAfter())
                .param("httpStatus", event.httpStatus())
                .param("outcome", event.outcome())
                .param("taskKey", event.nativeTaskKey())
                .param("taskStatus", event.taskStatus())
                .param("observedTitle", event.observedTitle())
                .param("observedDescription", event.observedDescription())
                .param("titleMatch", event.titleMatch())
                .param("descriptionMatch", event.descriptionMatch())
                .param("detail", event.detail() == null || event.detail().length() <= 4000 ? event.detail()
                        : event.detail().substring(0, 4000))
                .param("rawObservationId", event.rawObservationId())
                .param("actorUserId", event.actorUserId())
                .param("at", Timestamp.from(event.at()))
                .update();
    }

    /** Commands due for a worker now, oldest due first. */
    public List<UUID> claimable(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id FROM ops.content_command
                         WHERE terminal_at IS NULL
                           AND state IN ('PENDING', 'AWAITING_TASK', 'AWAITING_READBACK', 'UNKNOWN_REQUIRES_READBACK')
                           AND next_action_at <= :now
                           AND (lease_owner IS NULL OR lease_expires_at < :now)
                         ORDER BY next_action_at, id
                         LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * Take a command for one worker; empty when another worker holds it or it finished.
     *
     * @return the new fence
     */
    public Optional<Long> lease(UUID commandId, String worker, Instant now, int seconds) {
        return jdbc.sql("""
                        UPDATE ops.content_command
                           SET lease_owner = :worker, lease_expires_at = :until, fence = fence + 1,
                               updated_at = :now, version = version + 1
                         WHERE id = :id AND terminal_at IS NULL
                           AND (lease_owner IS NULL OR lease_expires_at < :now)
                        RETURNING fence
                        """)
                .param("id", commandId)
                .param("worker", worker)
                .param("now", Timestamp.from(now))
                .param("until", Timestamp.from(now.plusSeconds(seconds)))
                .query(Long.class)
                .optional();
    }

    /**
     * Move a command under the lease it is held with, and release the lease.
     *
     * @return whether the move happened; false when the lease was lost
     */
    public boolean move(UUID commandId, long fence, String worker, Move move, Instant now) {
        boolean terminal = TERMINAL.contains(move.state());
        int updated = jdbc.sql("""
                        UPDATE ops.content_command
                           SET state = :state, next_action_at = :nextActionAt,
                               native_task_key = coalesce(:taskKey, native_task_key),
                               capability_id = coalesce(:capabilityId, capability_id),
                               credential_id = coalesce(:credentialId, credential_id),
                               retry_count = retry_count + :retryIncrement,
                               task_polls = task_polls + :pollIncrement,
                               readbacks = CASE WHEN :resetReadbacks THEN 0 ELSE readbacks + :readbackIncrement END,
                               gate_reasons = :gateReasons,
                               outcome_code = coalesce(:outcomeCode, outcome_code),
                               outcome_detail = coalesce(:outcomeDetail, outcome_detail),
                               terminal_at = CASE WHEN :terminal THEN CAST(:now AS timestamptz) ELSE NULL END,
                               lease_owner = NULL, lease_expires_at = NULL,
                               updated_at = :now, version = version + 1
                         WHERE id = :id AND fence = :fence AND lease_owner = :worker AND terminal_at IS NULL
                        """)
                .param("id", commandId)
                .param("fence", fence)
                .param("worker", worker)
                .param("state", move.state())
                .param("nextActionAt", Timestamp.from(move.nextActionAt() == null ? now : move.nextActionAt()))
                .param("taskKey", move.nativeTaskKey())
                .param("capabilityId", move.capabilityId())
                .param("credentialId", move.credentialId())
                .param("retryIncrement", move.retryIncrement())
                .param("pollIncrement", move.pollIncrement())
                .param("readbackIncrement", move.readbackIncrement())
                .param("resetReadbacks", move.resetReadbacks())
                .param("gateReasons", move.gateReasons().toArray(String[]::new))
                .param("outcomeCode", move.outcomeCode())
                .param("outcomeDetail", move.outcomeDetail())
                .param("terminal", terminal)
                .param("now", Timestamp.from(now))
                .update();
        return updated == 1;
    }

    /**
     * Move a command a person decided about, outside any worker lease: a fresh readback or a close.
     * Refused while a worker holds the command.
     */
    public boolean resolve(UUID commandId, String expectedState, Move move, Instant now) {
        boolean terminal = TERMINAL.contains(move.state());
        int updated = jdbc.sql("""
                        UPDATE ops.content_command
                           SET state = :state, next_action_at = :nextActionAt,
                               readbacks = CASE WHEN :resetReadbacks THEN 0 ELSE readbacks END,
                               outcome_code = coalesce(:outcomeCode, outcome_code),
                               outcome_detail = coalesce(:outcomeDetail, outcome_detail),
                               terminal_at = CASE WHEN :terminal THEN CAST(:now AS timestamptz) ELSE NULL END,
                               updated_at = :now, version = version + 1
                         WHERE id = :id AND state = :expectedState AND terminal_at IS NULL
                           AND (lease_owner IS NULL OR lease_expires_at < :now)
                        """)
                .param("id", commandId)
                .param("expectedState", expectedState)
                .param("state", move.state())
                .param("nextActionAt", Timestamp.from(move.nextActionAt() == null ? now : move.nextActionAt()))
                .param("resetReadbacks", move.resetReadbacks())
                .param("outcomeCode", move.outcomeCode())
                .param("outcomeDetail", move.outcomeDetail())
                .param("terminal", terminal)
                .param("now", Timestamp.from(now))
                .update();
        return updated == 1;
    }

    /**
     * Hand back commands whose worker stopped holding them. One whose last apply began without an
     * answer may have changed the card: it is only read from now on, never written again.
     */
    public int recoverExpiredLeases(Instant now) {
        int unknown = jdbc.sql("""
                        UPDATE ops.content_command AS command
                           SET state = 'UNKNOWN_REQUIRES_READBACK', next_action_at = :now, readbacks = 0,
                               outcome_code = 'apply_answer_lost_with_worker',
                               lease_owner = NULL, lease_expires_at = NULL, updated_at = :now,
                               version = version + 1
                         WHERE command.terminal_at IS NULL AND command.lease_owner IS NOT NULL
                           AND command.lease_expires_at < :now
                           AND (SELECT event.kind FROM ops.content_command_event AS event
                                 WHERE event.command_id = command.id AND event.kind IN ('APPLY_STARTED', 'APPLY')
                                 ORDER BY event.sequence DESC LIMIT 1) = 'APPLY_STARTED'
                        """)
                .param("now", Timestamp.from(now))
                .update();
        int released = jdbc.sql("""
                        UPDATE ops.content_command
                           SET lease_owner = NULL, lease_expires_at = NULL, updated_at = :now, version = version + 1
                         WHERE terminal_at IS NULL AND lease_owner IS NOT NULL AND lease_expires_at < :now
                        """)
                .param("now", Timestamp.from(now))
                .update();
        return unknown + released;
    }

    /**
     * The newest sign that a write left under a command: its last APPLY_STARTED or APPLY event. A
     * write that began may have changed the card, whatever state the command row still shows.
     */
    public Optional<ApplyTrace> lastApply(UUID commandId) {
        return jdbc.sql("""
                        SELECT kind, outcome, native_task_key FROM ops.content_command_event
                         WHERE command_id = :id AND kind IN ('APPLY_STARTED', 'APPLY')
                         ORDER BY sequence DESC LIMIT 1
                        """)
                .param("id", commandId)
                .query((rows, rowNumber) -> new ApplyTrace(rows.getString("kind"), rows.getString("outcome"),
                        rows.getString("native_task_key")))
                .optional();
    }

    /** Why the command may not write now; empty when it may. */
    public List<String> gateReasons(UUID commandId) {
        String[] reasons = jdbc.sql("SELECT ops.evaluate_content_write_gate(:id)")
                .param("id", commandId)
                .query((rows, rowNumber) -> textArray(rows.getArray(1)))
                .single();
        return List.of(reasons);
    }

    /** The content capability registered for the store's marketplace, when there is one. */
    public Optional<StoreCapability> storeCapability(UUID storeId) {
        return jdbc.sql("""
                        SELECT capability.id, capability.verification_state, capability.status,
                               subject.availability,
                               (SELECT max(evidence.valid_until) FROM platform.registry_verification_case AS evidence
                                 WHERE evidence.capability_id = capability.id AND evidence.state = 'APPROVED') AS evidence_until
                          FROM core.store AS store
                          JOIN core.marketplace_account AS account ON account.id = store.marketplace_account_id
                          JOIN platform.platform_capability AS capability
                            ON capability.platform_code = account.platform_code
                           AND capability.capability_code = 'listing-content-change'
                          LEFT JOIN platform.capability_subject_status AS subject
                            ON subject.capability_id = capability.id AND subject.store_id = store.id
                         WHERE store.id = :storeId
                        """)
                .param("storeId", storeId)
                .query((rows, rowNumber) -> new StoreCapability(rows.getObject("id", UUID.class),
                        rows.getString("verification_state"), rows.getString("status"),
                        rows.getString("availability"), instant(rows, "evidence_until")))
                .optional();
    }

    /** The marketplace a store sells on. */
    public Optional<String> storePlatform(UUID storeId) {
        return jdbc.sql("""
                        SELECT account.platform_code FROM core.store AS store
                          JOIN core.marketplace_account AS account ON account.id = store.marketplace_account_id
                         WHERE store.id = :storeId
                        """)
                .param("storeId", storeId)
                .query(String.class)
                .optional();
    }

    /** The seller article and the marketplace product key of a listing variant. */
    public Optional<ListingKeys> listingKeys(UUID organizationId, UUID platformListingVariantId) {
        return jdbc.sql("""
                        SELECT listing.store_id, listing.native_listing_key, variant.native_sku_key,
                               listing.status AS listing_status, variant.status AS variant_status
                          FROM core.platform_listing_variant AS variant
                          JOIN core.platform_listing AS listing ON listing.id = variant.platform_listing_id
                         WHERE variant.id = :variantId AND variant.organization_id = :organizationId
                        """)
                .param("variantId", platformListingVariantId)
                .param("organizationId", organizationId)
                .query((rows, rowNumber) -> new ListingKeys(rows.getObject("store_id", UUID.class),
                        rows.getString("native_listing_key"), rows.getString("native_sku_key"),
                        rows.getString("listing_status"), rows.getString("variant_status")))
                .optional();
    }

    /** Whether a listing already has a command that has not finished. */
    public Optional<UUID> liveCommandFor(UUID platformListingVariantId) {
        return jdbc.sql("""
                        SELECT id FROM ops.content_command
                         WHERE platform_listing_variant_id = :variantId AND terminal_at IS NULL
                        """)
                .param("variantId", platformListingVariantId)
                .query(UUID.class)
                .optional();
    }

    /** Commands of one organization still moving, in one store or all of them. */
    public int inFlightCount(UUID organizationId, UUID storeId) {
        return jdbc.sql("""
                        SELECT count(*) FROM ops.content_command
                         WHERE organization_id = :organizationId AND terminal_at IS NULL
                           AND (CAST(:storeId AS uuid) IS NULL OR store_id = :storeId)
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .query(Integer.class)
                .single();
    }

    /** One command with its change. */
    public Optional<CommandRow> row(UUID commandId) {
        return jdbc.sql("SELECT " + COMMAND_COLUMNS + """
                          FROM ops.content_command AS command
                          JOIN ops.content_change AS change ON change.id = command.change_id
                         WHERE command.id = :id
                        """)
                .param("id", commandId)
                .query(ContentCommandRepository::mapRow)
                .optional();
    }

    /** One organization's command, when it is theirs. */
    public Optional<CommandRow> row(UUID organizationId, UUID commandId) {
        return row(commandId).filter(row -> row.organizationId().equals(organizationId));
    }

    /** The newest command of one listing variant. */
    public Optional<CommandRow> latestForListing(UUID organizationId, UUID platformListingVariantId) {
        return jdbc.sql("SELECT " + COMMAND_COLUMNS + """
                          FROM ops.content_command AS command
                          JOIN ops.content_change AS change ON change.id = command.change_id
                         WHERE command.organization_id = :organizationId
                           AND command.platform_listing_variant_id = :variantId
                         ORDER BY command.created_at DESC
                         LIMIT 1
                        """)
                .param("organizationId", organizationId)
                .param("variantId", platformListingVariantId)
                .query(ContentCommandRepository::mapRow)
                .optional();
    }

    /** The newest commands of a store, newest first. */
    public List<CommandRow> forStore(UUID organizationId, UUID storeId, int limit) {
        return jdbc.sql("SELECT " + COMMAND_COLUMNS + """
                          FROM ops.content_command AS command
                          JOIN ops.content_change AS change ON change.id = command.change_id
                         WHERE command.organization_id = :organizationId AND command.store_id = :storeId
                         ORDER BY command.created_at DESC
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("storeId", storeId)
                .param("limit", Math.clamp(limit, 1, 200))
                .query(ContentCommandRepository::mapRow)
                .list();
    }

    /** Commands of a store that succeeded, newest first: the content changes that took effect. */
    public List<CommandRow> succeeded(UUID storeId, int limit) {
        return jdbc.sql("SELECT " + COMMAND_COLUMNS + """
                          FROM ops.content_command AS command
                          JOIN ops.content_change AS change ON change.id = command.change_id
                         WHERE command.store_id = :storeId AND command.state = 'SUCCEEDED'
                         ORDER BY command.terminal_at DESC
                         LIMIT :limit
                        """)
                .param("storeId", storeId)
                .param("limit", Math.clamp(limit, 1, 500))
                .query(ContentCommandRepository::mapRow)
                .list();
    }

    /** Every event of a command, in order. */
    public List<EventRow> events(UUID commandId) {
        return jdbc.sql("""
                        SELECT id, sequence, kind, fence, state_after, http_status, outcome, native_task_key,
                               task_status, observed_title, observed_description, title_match,
                               description_match, detail, actor_user_id, recorded_at
                          FROM ops.content_command_event
                         WHERE command_id = :id
                         ORDER BY sequence
                        """)
                .param("id", commandId)
                .query((rows, rowNumber) -> new EventRow(rows.getObject("id", UUID.class),
                        rows.getInt("sequence"), rows.getString("kind"), longOrNull(rows, "fence"),
                        rows.getString("state_after"), intOrNull(rows, "http_status"), rows.getString("outcome"),
                        rows.getString("native_task_key"), rows.getString("task_status"),
                        rows.getString("observed_title"), rows.getString("observed_description"),
                        rows.getString("title_match"), rows.getString("description_match"),
                        rows.getString("detail"), rows.getObject("actor_user_id", UUID.class),
                        rows.getTimestamp("recorded_at").toInstant()))
                .list();
    }

    private static CommandRow mapRow(ResultSet rows, int rowNumber) throws SQLException {
        return new CommandRow(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("change_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getObject("platform_listing_variant_id", UUID.class),
                rows.getString("idempotency_key"),
                rows.getString("state"),
                List.of(textArray(rows.getArray("gate_reasons"))),
                rows.getObject("capability_id", UUID.class),
                rows.getObject("credential_id", UUID.class),
                rows.getString("lease_owner"),
                instant(rows, "lease_expires_at"),
                rows.getLong("fence"),
                rows.getInt("retry_count"),
                instant(rows, "next_action_at"),
                rows.getString("native_task_key"),
                rows.getInt("task_polls"),
                rows.getInt("readbacks"),
                rows.getString("outcome_code"),
                rows.getString("outcome_detail"),
                instant(rows, "created_at"),
                instant(rows, "updated_at"),
                instant(rows, "terminal_at"),
                rows.getString("native_listing_key"),
                rows.getString("offer_key"),
                rows.getString("prior_title"),
                rows.getString("prior_description"),
                instant(rows, "prior_observed_at"),
                rows.getString("target_title"),
                rows.getString("target_description"),
                rows.getBoolean("title_changed"),
                rows.getBoolean("description_changed"),
                rows.getObject("source_invocation_id", UUID.class),
                rows.getString("reason"),
                rows.getObject("approved_by_user_id", UUID.class),
                instant(rows, "approved_at"),
                instant(rows, "approval_expires_at"));
    }

    private static String[] textArray(Array array) throws SQLException {
        if (array == null) {
            return new String[0];
        }
        Object values = array.getArray();
        return values instanceof String[] strings ? Arrays.copyOf(strings, strings.length) : new String[0];
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer intOrNull(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** The states a command ends in. */
    public static final java.util.Set<String> TERMINAL =
            java.util.Set.of("SUCCEEDED", "FAILED_BEFORE_WRITE", "FAILED", "CANCELLED", "CLOSED");

    /** A change to record. */
    public record NewChange(UUID id, UUID organizationId, UUID storeId, UUID platformListingVariantId,
                            String nativeListingKey, String offerKey, String priorTitle,
                            String priorDescription, Instant priorObservedAt, String targetTitle,
                            String targetDescription, boolean titleChanged, boolean descriptionChanged,
                            UUID sourceInvocationId, String reason, UUID approvedByUserId,
                            Instant approvedAt, Instant approvalExpiresAt) {
    }

    /** An event to append. */
    public record NewEvent(UUID id, UUID commandId, String kind, Long fence, String stateAfter,
                           Integer httpStatus, String outcome, String nativeTaskKey, String taskStatus,
                           String observedTitle, String observedDescription, String titleMatch,
                           String descriptionMatch, String detail, UUID rawObservationId,
                           UUID actorUserId, Instant at) {
    }

    /**
     * Where a command goes next, and what it learned on the way.
     *
     * @param state the next state
     * @param nextActionAt when a worker should next look at it, or {@code null} for now
     */
    public record Move(String state, Instant nextActionAt, String nativeTaskKey, UUID capabilityId,
                       UUID credentialId, int retryIncrement, int pollIncrement, int readbackIncrement,
                       boolean resetReadbacks, List<String> gateReasons, String outcomeCode,
                       String outcomeDetail) {

        public Move {
            gateReasons = gateReasons == null ? List.of() : List.copyOf(gateReasons);
        }

        /** A plain move to a state at an instant. */
        public static Move to(String state, Instant nextActionAt) {
            return new Move(state, nextActionAt, null, null, null, 0, 0, 0, false, List.of(), null, null);
        }

        public Move outcome(String code, String detail) {
            return new Move(state, nextActionAt, nativeTaskKey, capabilityId, credentialId, retryIncrement,
                    pollIncrement, readbackIncrement, resetReadbacks, gateReasons, code, detail);
        }

        public Move taskKey(String key) {
            return new Move(state, nextActionAt, key, capabilityId, credentialId, retryIncrement,
                    pollIncrement, readbackIncrement, resetReadbacks, gateReasons, outcomeCode, outcomeDetail);
        }

        public Move resolved(UUID capability, UUID credential) {
            return new Move(state, nextActionAt, nativeTaskKey, capability, credential, retryIncrement,
                    pollIncrement, readbackIncrement, resetReadbacks, gateReasons, outcomeCode, outcomeDetail);
        }

        public Move counted(int retries, int polls, int readbacks) {
            return new Move(state, nextActionAt, nativeTaskKey, capabilityId, credentialId, retries, polls,
                    readbacks, resetReadbacks, gateReasons, outcomeCode, outcomeDetail);
        }

        public Move freshReadbacks() {
            return new Move(state, nextActionAt, nativeTaskKey, capabilityId, credentialId, retryIncrement,
                    pollIncrement, readbackIncrement, true, gateReasons, outcomeCode, outcomeDetail);
        }

        public Move gate(List<String> reasons) {
            return new Move(state, nextActionAt, nativeTaskKey, capabilityId, credentialId, retryIncrement,
                    pollIncrement, readbackIncrement, resetReadbacks, reasons, outcomeCode, outcomeDetail);
        }
    }

    /** One command with the change it carries out. */
    public record CommandRow(UUID id, UUID organizationId, UUID changeId, UUID storeId,
                             UUID platformListingVariantId, String idempotencyKey, String state,
                             List<String> gateReasons, UUID capabilityId, UUID credentialId, String leaseOwner,
                             Instant leaseExpiresAt, long fence, int retryCount, Instant nextActionAt,
                             String nativeTaskKey, int taskPolls, int readbacks, String outcomeCode,
                             String outcomeDetail, Instant createdAt, Instant updatedAt, Instant terminalAt,
                             String nativeListingKey, String offerKey, String priorTitle,
                             String priorDescription, Instant priorObservedAt, String targetTitle,
                             String targetDescription, boolean titleChanged, boolean descriptionChanged,
                             UUID sourceInvocationId, String reason, UUID approvedByUserId, Instant approvedAt,
                             Instant approvalExpiresAt) {
    }

    /** One recorded call or move. */
    public record EventRow(UUID id, int sequence, String kind, Long fence, String stateAfter, Integer httpStatus,
                           String outcome, String nativeTaskKey, String taskStatus, String observedTitle,
                           String observedDescription, String titleMatch, String descriptionMatch,
                           String detail, UUID actorUserId, Instant recordedAt) {
    }

    /**
     * The newest apply-related event of a command.
     *
     * @param kind APPLY_STARTED, or APPLY once an answer was recorded
     * @param outcome how the answer was read, or {@code null} for APPLY_STARTED
     * @param nativeTaskKey the task the platform opened, when the answer named one
     */
    public record ApplyTrace(String kind, String outcome, String nativeTaskKey) {
    }

    /** The content capability of a store's marketplace and where it stands. */
    public record StoreCapability(UUID capabilityId, String verificationState, String status,
                                  String storeAvailability, Instant evidenceValidUntil) {
    }

    /** How a listing variant is addressed on its marketplace. */
    public record ListingKeys(UUID storeId, String nativeListingKey, String offerKey, String listingStatus,
                              String variantStatus) {
    }
}
