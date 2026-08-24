package com.mimococo.marketops.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The smallest metadata graph an acquisition needs, and the queries the control
 * plane tests read it back with.
 *
 * <p>Every identifier is fixed rather than generated. A failing concurrency test
 * is read by a person comparing two interleaved logs, and a stable identifier is
 * the difference between seeing which job blocked and seeing two random UUIDs.
 *
 * <p>The graph is seeded through ordinary SQL rather than through the
 * application, because these tests are about what the database guarantees when
 * an arbitrary client writes to it.
 */
final class IngestionControlPlaneFixture {

    static final UUID ORGANIZATION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    static final UUID LEGAL_ENTITY = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    static final UUID SERVICE_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    static final UUID SECOND_JOB = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    static final UUID CREDENTIAL = UUID.fromString("00000000-0000-0000-0000-000000000101");
    static final UUID SCOPE_GRANT = UUID.fromString("00000000-0000-0000-0000-000000000201");
    static final UUID ENDPOINT = UUID.fromString("00000000-0000-0000-0000-000000000501");
    static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-000000000301");

    /**
     * The scopes an acquisition consumes, in the ascending order the grant
     * locks their rows and records them -- the same order the epoch triggers
     * advance in, which is what keeps a writer and a grant from forming an
     * ordering cycle.
     */
    static final List<String> SCOPE_KINDS =
            List.of("JOB", "MARKETPLACE_ACCOUNT", "ORGANIZATION", "SERVICE_ACCOUNT");

    /** The declared temporal boundary kinds. */
    static final List<String> BOUNDARY_KINDS = List.of(
            "SERVICE_ACCOUNT_EXPIRY",
            "SELECTED_SCOPE_GRANT_END",
            "FUTURE_SCOPE_GRANT_START",
            "SELECTED_CREDENTIAL_EXPIRY",
            "FUTURE_CREDENTIAL_START",
            "STORE_SCOPE_BOUNDARY");

    /** SQLSTATEs the control plane raises, from the V0007 header. */
    static final String EPOCH_NOT_MONOTONIC = "MO001";
    static final String MEMBERSHIP_GUARD_INCOMPLETE = "MO002";
    static final String ROUTE_INVENTORY_INCOMPLETE = "MO004";
    static final String BOUNDARY_SET_INCOMPLETE = "MO005";
    static final String JOB_PLATFORM_IMMUTABLE = "MO006";
    static final String RUN_AUTHORITY_LOST = "MO008";
    static final String CHECKPOINT_WITHOUT_EVIDENCE = "MO009";
    static final String CONTROL_SNAPSHOT_STALE = "MO010";
    static final String CONTROL_SNAPSHOT_EXPIRED = "MO011";
    static final String SCOPE_GRANT_NOT_AUTHORITATIVE = "MO012";
    static final String CREDENTIAL_NOT_AUTHORITATIVE = "MO013";
    static final String RUN_AUTHORITY_LOST_AT_COMMIT = "MO014";

    private IngestionControlPlaneFixture() {
    }

    /**
     * Seed one organization, account, store, subject, credential, scope grant,
     * job, leased run and checkpoint.
     *
     * <p>Safe to call repeatedly against the shared container: every insert is
     * idempotent on its primary key, so a test that needs the graph does not
     * have to know whether another test already created it.
     */
    static void seed(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO core.organization
                    (id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', 'acme', 'Acme', 'ACTIVE', now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(ORGANIZATION));
        execute(connection, """
                INSERT INTO core.legal_entity
                    (id, organization_id, code, display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', 'acme-ru', 'Acme RU', 'ACTIVE', now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(LEGAL_ENTITY, ORGANIZATION));
        execute(connection, """
                INSERT INTO core.marketplace_account
                    (id, organization_id, legal_entity_id, platform_code, code,
                     display_name, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', 'acme-ozon', 'Acme Ozon',
                        'ACTIVE', now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(ACCOUNT, ORGANIZATION, LEGAL_ENTITY));
        execute(connection, """
                INSERT INTO core.store
                    (id, organization_id, marketplace_account_id, code, display_name,
                     status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'acme-store', 'Acme Store',
                        'ACTIVE', now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(STORE, ORGANIZATION, ACCOUNT));
        execute(connection, """
                INSERT INTO iam.service_account
                    (id, organization_id, code, display_name, purpose, owner_label,
                     status, expires_at, created_at, updated_at)
                VALUES ('%s', '%s', 'acme-worker', 'Acme Worker', 'INGESTION',
                        'platform-team', 'ACTIVE', now() + interval '30 days', now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(SERVICE_ACCOUNT, ORGANIZATION));
        execute(connection, """
                INSERT INTO iam.service_account_scope_grant
                    (id, organization_id, service_account_id, permission_code,
                     organization_ref_id, effective_from, effective_to, status,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'READ', '%s',
                        now() - interval '1 day', now() + interval '5 days',
                        'ACTIVE', now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(SCOPE_GRANT, ORGANIZATION, SERVICE_ACCOUNT, ORGANIZATION));
        execute(connection, """
                INSERT INTO platform.credential_metadata
                    (id, organization_id, marketplace_account_id, code, display_name,
                     purpose_code, scope_mode, secret_reference, effective_from,
                     expires_at, status, custodian_label, verification_state,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'acme-read', 'Acme read', 'READ', 'ACCOUNT',
                        'secret-ref://vault/acme/read',
                        now() - interval '1 day', now() + interval '10 days',
                        'ACTIVE', 'platform-team', 'UNVERIFIED', now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(CREDENTIAL, ORGANIZATION, ACCOUNT));
        execute(connection, """
                INSERT INTO platform.platform_endpoint
                    (id, platform_code, endpoint_code, api_version, read_write_class,
                     pagination_model, idempotency_support, verification_state,
                     owner_label, contract_test_status, status, created_at, updated_at)
                VALUES ('%s', 'OZON', 'orders.list', 'v3', 'READ', 'CURSOR', 'UNKNOWN',
                        'UNVERIFIED', 'platform-team', 'NOT_IMPLEMENTED', 'ACTIVE',
                        now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(ENDPOINT));
        execute(connection, """
                INSERT INTO platform.ingestion_job
                    (id, organization_id, marketplace_account_id, platform_code,
                     service_account_id, job_code, display_name, status,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', '%s', 'ozon-orders', 'Ozon orders',
                        'ACTIVE', now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(JOB, ORGANIZATION, ACCOUNT, SERVICE_ACCOUNT));
        execute(connection, """
                INSERT INTO ops.ingestion_run
                    (id, job_id, state, fence_token, lease_owner, lease_expires_at,
                     attempt_no, last_call_seq, created_at, updated_at)
                VALUES ('%s', '%s', 'LEASED', 1, 'worker-a', now() + interval '5 minutes',
                        1, 0, now(), now())
                ON CONFLICT DO NOTHING
                """.formatted(RUN, JOB));
        execute(connection, """
                INSERT INTO ops.ingestion_checkpoint
                    (job_id, strategy, position_value, checkpoint_version, updated_at)
                VALUES ('%s', 'CURSOR', NULL, 0, now())
                ON CONFLICT DO NOTHING
                """.formatted(JOB));
    }

    /** Remove everything {@link #seed} created, youngest reference first. */
    static void reset(Connection connection) throws SQLException {
        for (String table : List.of(
                "ops.authorization_decision_evidence",
                "raw.raw_acquisition_observation",
                "raw.raw_logical_unit",
                "raw.raw_content",
                "ops.ingestion_checkpoint",
                "ops.ingestion_run",
                "platform.feature_flag",
                "platform.ingestion_job",
                "platform.platform_endpoint",
                "platform.credential_store_scope",
                "platform.credential_metadata",
                "iam.service_account_scope_grant",
                "iam.service_account_allowed_source",
                "iam.service_account",
                "core.store_fulfillment_declaration",
                "core.store_warehouse_link",
                "core.store",
                "core.warehouse",
                "core.marketplace_account",
                "core.legal_entity",
                "core.organization")) {
            execute(connection, "DELETE FROM " + table);
        }
        // Job scopes disappear with their jobs; the remaining scopes are
        // recreated by the next seed and must not carry a stale epoch.
        execute(connection, "DELETE FROM platform.control_epoch");
    }

    /** The current epoch of one scope, or {@code -1} when the row is absent. */
    static long epochOf(Connection connection, String scopeKind, UUID scopeId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT epoch FROM platform.control_epoch"
                        + " WHERE scope_kind = ? AND scope_id = ?")) {
            statement.setString(1, scopeKind);
            statement.setObject(2, scopeId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1L;
            }
        }
    }

    /** The generation of one platform's membership guard, or {@code -1}. */
    static long guardGenerationOf(Connection connection, String platformCode)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT generation FROM platform.control_epoch_membership_guard"
                        + " WHERE guard_kind = 'PLATFORM_JOB_SET' AND platform_code = ?")) {
            statement.setString(1, platformCode);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1L;
            }
        }
    }

    /**
     * A call to {@code platform.grant_call_authority} for the seeded run,
     * naming the seeded scope grant and Credential. Everything else the grant
     * consumes -- identities, epochs, the boundary relation -- is derived by
     * the function from committed rows, so there is nothing here a test could
     * forge even if it wanted to.
     */
    static String grant(long fenceToken, String leaseOwner, String correlationId) {
        return grant(fenceToken, leaseOwner, SCOPE_GRANT, CREDENTIAL, correlationId);
    }

    /** The fixture grant with an explicit scope grant and Credential selection. */
    static String grant(long fenceToken, String leaseOwner,
            UUID scopeGrantId, UUID credentialId, String correlationId) {
        return """
                SELECT platform.grant_call_authority(
                    '%s', %d, '%s', '%s', '%s', interval '30 seconds', '%s')
                """.formatted(RUN, fenceToken, leaseOwner,
                scopeGrantId, credentialId, correlationId);
    }

    /** The stored epoch of {@code scopeKind}, as a scalar subquery. */
    static String storedEpoch(String scopeKind, UUID scopeId) {
        return "(SELECT epoch FROM platform.control_epoch WHERE scope_kind = '%s'"
                .formatted(scopeKind) + " AND scope_id = '%s')".formatted(scopeId);
    }

    /** Every value of the first column produced by {@code sql}. */
    static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
