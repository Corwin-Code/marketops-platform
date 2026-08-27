package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The write-capability switches and the journal of who moved them.
 *
 * <p>The switch is a flag row; the journal is a separate append-only record. The
 * separation matters because the flag answers what is true now and the journal
 * answers who made it true, and an incident review needs both. A single table
 * that carried the current state and its history would make one of the two
 * editable.
 *
 * <p>A missing flag is off. The write gate requires an explicitly enabled flag
 * at both the global and the capability scope, so an unconfigured scope blocks
 * rather than defaulting open, and creating a flag row is itself the deliberate
 * act of turning something on.
 */
@Repository
public class KillSwitchRepository {

    private final JdbcClient jdbc;

    KillSwitchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Set one switch, creating it if this scope has never had one.
     *
     * <p>The scope reference decides which column carries it, which is the same
     * shape the flag table's own constraint enforces, so a malformed scope is
     * refused by the database rather than stored as an unreachable row.
     */
    public void setFlagState(String flagCode, String scopeKind, String scopeReference,
                             String state, Instant at) {
        int updated = jdbc.sql("""
                        UPDATE platform.feature_flag
                        SET state = :state, updated_at = :at, version = version + 1
                        WHERE flag_code = :flagCode AND scope_kind = :scopeKind
                          AND status = 'ACTIVE'
                          AND scope_key = :scopeKind || ':'
                              || coalesce(platform_code, '')
                              || ':' || coalesce(CAST(marketplace_account_id AS text), '')
                              || ':' || coalesce(CAST(store_id AS text), '')
                              || ':' || coalesce(CAST(capability_id AS text), '')
                          AND ((scope_kind = 'GLOBAL')
                            OR (scope_kind = 'PLATFORM' AND platform_code = :scopeReference)
                            OR (scope_kind = 'MARKETPLACE_ACCOUNT'
                                AND marketplace_account_id = CAST(:scopeReference AS uuid))
                            OR (scope_kind = 'STORE'
                                AND store_id = CAST(:scopeReference AS uuid))
                            OR (scope_kind = 'CAPABILITY'
                                AND capability_id = CAST(:scopeReference AS uuid)))
                        """)
                .param("state", state)
                .param("at", Timestamp.from(at))
                .param("flagCode", flagCode)
                .param("scopeKind", scopeKind)
                .param("scopeReference", scopeReference)
                .update();
        if (updated > 0) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO platform.feature_flag (
                            id, flag_code, flag_kind, scope_kind, platform_code,
                            marketplace_account_id, store_id, capability_id, state, reason,
                            status, created_at, updated_at, version)
                        VALUES (gen_random_uuid(), :flagCode, 'WRITE_CAPABILITY', :scopeKind,
                            CASE WHEN :scopeKind = 'PLATFORM' THEN :scopeReference END,
                            CASE WHEN :scopeKind = 'MARKETPLACE_ACCOUNT'
                                 THEN CAST(:scopeReference AS uuid) END,
                            CASE WHEN :scopeKind = 'STORE'
                                 THEN CAST(:scopeReference AS uuid) END,
                            CASE WHEN :scopeKind = 'CAPABILITY'
                                 THEN CAST(:scopeReference AS uuid) END,
                            :state, 'created when the switch was first moved', 'ACTIVE',
                            :at, :at, 0)
                        """)
                .param("flagCode", flagCode)
                .param("scopeKind", scopeKind)
                .param("scopeReference", scopeReference)
                .param("state", state)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Record who moved a switch, when and why. */
    public void recordEvent(UUID id, UUID organizationId, String capabilityCode,
                            String scopeKind, String scopeReference, String action,
                            UUID actorUserId, String reason, int inFlightCommandCount,
                            Instant occurredAt, String correlationId) {
        jdbc.sql("""
                        INSERT INTO ops.kill_switch_event (
                            id, organization_id, capability_code, scope_kind, scope_reference,
                            action, actor_user_id, reason, in_flight_command_count,
                            occurred_at, correlation_id)
                        VALUES (:id, :organizationId, :capabilityCode, :scopeKind,
                            :scopeReference, :action, :actorUserId, :reason,
                            :inFlightCommandCount, :occurredAt, :correlationId)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("capabilityCode", capabilityCode)
                .param("scopeKind", scopeKind)
                .param("scopeReference", scopeReference)
                .param("action", action)
                .param("actorUserId", actorUserId)
                .param("reason", reason)
                .param("inFlightCommandCount", inFlightCommandCount)
                .param("occurredAt", Timestamp.from(occurredAt))
                .param("correlationId", correlationId)
                .update();
    }

    /** Every switch movement of one organization, newest first. */
    public List<SwitchEventRow> history(UUID organizationId, int limit) {
        return jdbc.sql("""
                        SELECT id, capability_code, scope_kind, scope_reference, action,
                               actor_user_id, reason, in_flight_command_count, occurred_at
                          FROM ops.kill_switch_event
                         WHERE organization_id = :organizationId
                         ORDER BY occurred_at DESC
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("limit", limit)
                .query(KillSwitchRepository::mapEvent)
                .list();
    }

    /** Which price-write switches exist and what state they are in. */
    public List<FlagRow> priceWriteFlags() {
        return jdbc.sql("""
                        SELECT id, scope_kind, platform_code, marketplace_account_id, store_id,
                               capability_id, state, status, updated_at
                          FROM platform.feature_flag
                         WHERE flag_code = 'price-change-write'
                         ORDER BY scope_kind
                        """)
                .query(KillSwitchRepository::mapFlag)
                .list();
    }

    private static SwitchEventRow mapEvent(ResultSet rows, int rowNumber) throws SQLException {
        return new SwitchEventRow(
                rows.getObject("id", UUID.class),
                rows.getString("capability_code"),
                rows.getString("scope_kind"),
                rows.getString("scope_reference"),
                rows.getString("action"),
                rows.getObject("actor_user_id", UUID.class),
                rows.getString("reason"),
                rows.getInt("in_flight_command_count"),
                rows.getTimestamp("occurred_at").toInstant());
    }

    private static FlagRow mapFlag(ResultSet rows, int rowNumber) throws SQLException {
        return new FlagRow(
                rows.getObject("id", UUID.class),
                rows.getString("scope_kind"),
                rows.getString("platform_code"),
                rows.getObject("marketplace_account_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getObject("capability_id", UUID.class),
                rows.getString("state"),
                rows.getString("status"),
                rows.getTimestamp("updated_at").toInstant());
    }

    /**
     * One recorded switch movement.
     *
     * @param id the event
     * @param capabilityCode which capability was affected
     * @param scopeKind what scope was moved
     * @param scopeReference the scope's identifier, or {@code null} when global
     * @param action whether it was turned off or on
     * @param actorUserId who moved it
     * @param reason why
     * @param inFlightCommandCount how many commands were still moving at the time
     * @param occurredAt when
     */
    public record SwitchEventRow(UUID id, String capabilityCode, String scopeKind,
                                 String scopeReference, String action, UUID actorUserId,
                                 String reason, int inFlightCommandCount, Instant occurredAt) {
    }

    /**
     * One switch and its current state.
     *
     * @param id the flag
     * @param scopeKind what it governs
     * @param platformCode marketplace, when scoped to one
     * @param marketplaceAccountId account, when scoped to one
     * @param storeId store, when scoped to one
     * @param capabilityId capability, when scoped to one
     * @param state whether writes are allowed at this scope
     * @param status whether the flag itself is live
     * @param updatedAt when it last moved
     */
    public record FlagRow(UUID id, String scopeKind, String platformCode,
                          UUID marketplaceAccountId, UUID storeId, UUID capabilityId,
                          String state, String status, Instant updatedAt) {
    }
}
