package com.mimococo.marketops.database;

import static com.mimococo.marketops.database.IngestionControlPlaneFixture.ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.BOUNDARY_KINDS;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.CHECKPOINT_WITHOUT_EVIDENCE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.CONTROL_SNAPSHOT_EXPIRED;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.CONTROL_SNAPSHOT_STALE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.CREDENTIAL;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.JOB;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.ORGANIZATION;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.RUN;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.RUN_AUTHORITY_LOST;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SCOPE_GRANT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SCOPE_KINDS;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SERVICE_ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.epochOf;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.execute;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.grantUsingStoredEpochs;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.reset;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.seed;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.storedEpoch;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.strings;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The acquisition protocol end to end: a leased run turning a control snapshot
 * into a bounded call authority, a cursor that may not outrun durable evidence,
 * and Raw evidence a client cannot rewrite.
 *
 * <p>Each guarantee is exercised by attempting the write the protocol forbids
 * and asserting the exact SQLSTATE, because a refusal that arrives as the wrong
 * error is indistinguishable from an unrelated defect. The refusals matter more
 * than the successes: a grant that survives a stale epoch, an expired boundary
 * or a lost fence is a call issued under authority that no longer exists, and
 * nothing downstream can detect that after the fact.
 */
class IngestionAuthorityAndEvidenceIT extends PostgresContainerSupport {

    /** A deterministic SHA-256-shaped digest for the stored bytes. */
    private static final String CONTENT_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @BeforeEach
    void resetAndSeedFixture() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            reset(connection);
            seed(connection);
        }
    }

    @Test
    @DisplayName("TC-CTRL-400 a grant that consumes the current snapshot succeeds and is capped")
    void freshSnapshotGrantsCappedAuthority() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            String authority = single(connection, storedEpochGrant(1, "corr-ok"));

            assertThat(authority)
                    .as("the grant returns the instant the authority expires")
                    .isNotNull();
            assertThat(runState(connection)).isEqualTo("RUNNING");
            assertThat(count(connection,
                    "SELECT last_call_seq FROM ops.ingestion_run WHERE id = '" + RUN + "'"))
                    .isEqualTo(1);
            assertThat(evidenceRows(connection)).isEqualTo(1);
            assertThat(count(connection,
                    "SELECT boundary_kind_count FROM ops.authorization_decision_evidence"))
                    .isEqualTo(6);
            assertThat(strings(connection,
                    "SELECT unnest(control_epoch_scopes)"
                            + " FROM ops.authorization_decision_evidence"))
                    .containsExactlyElementsOf(SCOPE_KINDS);
            assertThat(singleBoolean(connection,
                    "SELECT call_authority_expires_at <= control_snapshot_valid_until"
                            + " FROM ops.authorization_decision_evidence"))
                    .as("the issued authority never reaches past the snapshot boundary")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("TC-CTRL-401 a stale epoch refuses the grant and leaves zero residue")
    void staleEpochRefusesTheGrant() throws SQLException {
        long subjectEpoch;
        try (Connection connection = asApplicationRole(container)) {
            subjectEpoch = epochOf(connection, "SERVICE_ACCOUNT", SERVICE_ACCOUNT);
        }

        assertRefused(CONTROL_SNAPSHOT_STALE, grantUsingStoredEpochs(1,
                storedEpoch("ORGANIZATION", ORGANIZATION),
                storedEpoch("MARKETPLACE_ACCOUNT", ACCOUNT),
                String.valueOf(subjectEpoch - 1),
                storedEpoch("JOB", JOB),
                "corr-stale"));

        try (Connection connection = asApplicationRole(container)) {
            assertThat(runState(connection)).isEqualTo("LEASED");
            assertThat(evidenceRows(connection)).isZero();
        }
    }

    @Test
    @DisplayName("TC-CTRL-402 an already-passed boundary refuses the grant and leaves zero residue")
    void expiredSnapshotRefusesTheGrant() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        UPDATE iam.service_account
                           SET expires_at = now() - interval '1 second', updated_at = now()
                         WHERE id = '%s'
                        """.formatted(SERVICE_ACCOUNT));

                Throwable failure = Assertions.catchThrowable(() ->
                        execute(connection, storedEpochGrant(1, "corr-expired")));

                assertThat(failure)
                        .as("a snapshot whose boundary has passed must not grant")
                        .isNotNull();
                assertThat(carriesSqlState(failure, CONTROL_SNAPSHOT_EXPIRED))
                        .as("expected SQLSTATE %s from: %s", CONTROL_SNAPSHOT_EXPIRED, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }

        try (Connection connection = asApplicationRole(container)) {
            assertThat(evidenceRows(connection)).isZero();
        }
    }

    @Test
    @DisplayName("TC-CTRL-403 a superseded worker's grant at the wrong fence gains nothing")
    void wrongFenceGrantChangesNoRow() throws SQLException {
        assertRefused(RUN_AUTHORITY_LOST, storedEpochGrant(2, "corr-fenced"));

        try (Connection connection = asApplicationRole(container)) {
            assertThat(runState(connection)).isEqualTo("LEASED");
            assertThat(count(connection,
                    "SELECT last_call_seq FROM ops.ingestion_run WHERE id = '" + RUN + "'"))
                    .isZero();
            assertThat(evidenceRows(connection)).isZero();
        }
    }

    @Test
    @DisplayName("TC-CTRL-404 the authority is capped by the boundary when the boundary is nearer")
    void nearBoundaryCapsTheAuthority() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            execute(connection, """
                    UPDATE iam.service_account_scope_grant
                       SET effective_to = now() + interval '5 seconds', updated_at = now()
                     WHERE id = '%s'
                    """.formatted(SCOPE_GRANT));

            String authority = single(connection, """
                    SELECT platform.grant_call_authority(
                        '%s', 1, '%s', '%s', '%s', '%s',
                        %s, %s, %s, %s,
                        platform.control_snapshot_temporal('%s', '%s', '%s', '%s', now()),
                        now(), interval '30 seconds', 'corr-capped')
                    """.formatted(
                    RUN, ORGANIZATION, ACCOUNT, SERVICE_ACCOUNT, CREDENTIAL,
                    storedEpoch("ORGANIZATION", ORGANIZATION),
                    storedEpoch("MARKETPLACE_ACCOUNT", ACCOUNT),
                    storedEpoch("SERVICE_ACCOUNT", SERVICE_ACCOUNT),
                    storedEpoch("JOB", JOB),
                    SERVICE_ACCOUNT, SCOPE_GRANT, ACCOUNT, CREDENTIAL));

            assertThat(authority).isNotNull();
            assertThat(evidenceRows(connection)).isEqualTo(1);
            assertThat(singleBoolean(connection,
                    "SELECT call_authority_expires_at = control_snapshot_valid_until"
                            + " FROM ops.authorization_decision_evidence"))
                    .as("the boundary wins over the nominal window")
                    .isTrue();
            assertThat(singleBoolean(connection,
                    "SELECT call_authority_expires_at < granted_at + interval '30 seconds'"
                            + " FROM ops.authorization_decision_evidence"))
                    .as("the authority is strictly shorter than the nominal window")
                    .isTrue();
            assertThat(single(connection,
                    "SELECT winning_boundary_kind FROM ops.authorization_decision_evidence"))
                    .isEqualTo("SELECTED_SCOPE_GRANT_END");
        }
    }

    /**
     * A client that bypasses {@code platform.grant_call_authority} and writes
     * the evidence row directly must still be unable to record a grant that
     * happened at or after its own boundary, because the constraint lives on
     * the table rather than in the function.
     */
    @Test
    @DisplayName("TC-CTRL-405 the evidence row cannot record a grant at or after its own boundary")
    void evidenceRowRejectsGrantAtItsBoundary() throws SQLException {
        String kinds = BOUNDARY_KINDS.stream()
                .map(kind -> "'" + kind + "'")
                .collect(Collectors.joining(", "));

        assertRefused(CHECK_VIOLATION, """
                INSERT INTO ops.authorization_decision_evidence
                    (id, job_id, service_account_id, marketplace_account_id, credential_id,
                     evaluated_at, granted_at, control_epoch_scopes, control_epoch_values,
                     control_snapshot_valid_until, boundary_kind_count, boundary_kind_set,
                     boundary_set_digest, winning_boundary_kind, call_authority_expires_at,
                     correlation_id)
                VALUES
                    (gen_random_uuid(), '%s', '%s', '%s', '%s',
                     now(), now() + interval '1 minute',
                     ARRAY['ORGANIZATION', 'MARKETPLACE_ACCOUNT', 'SERVICE_ACCOUNT', 'JOB'],
                     ARRAY[1, 1, 1, 1]::bigint[],
                     now() + interval '1 minute', %d, ARRAY[%s],
                     repeat('0', 64), 'SERVICE_ACCOUNT_EXPIRY',
                     now() + interval '1 minute', 'corr-bypass')
                """.formatted(JOB, SERVICE_ACCOUNT, ACCOUNT, CREDENTIAL,
                BOUNDARY_KINDS.size(), kinds));
    }

    @Test
    @DisplayName("TC-CTRL-406 a cursor may not advance without committed evidence")
    void checkpointWithoutEvidenceIsRefused() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            execute(connection, runToRunning());
        }

        assertRefused(CHECKPOINT_WITHOUT_EVIDENCE,
                acknowledge(UUID.randomUUID(), 0, "orders-page-2"));

        try (Connection connection = asApplicationRole(container)) {
            assertThat(checkpointVersion(connection)).isZero();
        }
    }

    @Test
    @DisplayName("TC-CTRL-407 with committed evidence the same acknowledgement succeeds"
            + " and the version advances by one")
    void committedEvidenceAllowsTheAcknowledgement() throws SQLException {
        UUID content = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID observation = UUID.randomUUID();

        try (Connection connection = asApplicationRole(container)) {
            execute(connection, runToRunning());
            execute(connection, rawContent(content));
            execute(connection, rawLogicalUnit(unit));
            execute(connection, rawObservation(observation, RUN, unit, content));

            assertThat(count(connection, acknowledge(observation, 0, "orders-page-2")))
                    .isEqualTo(1);
            assertThat(checkpointVersion(connection)).isEqualTo(1);
            assertThat(single(connection,
                    "SELECT position_value FROM ops.ingestion_checkpoint"
                            + " WHERE job_id = '" + JOB + "'"))
                    .isEqualTo("orders-page-2");
        }
    }

    @Test
    @DisplayName("TC-CTRL-408 a stale checkpoint version is refused,"
            + " so a superseded worker cannot rewind the cursor")
    void staleCheckpointVersionCannotRewind() throws SQLException {
        UUID content = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID observation = UUID.randomUUID();

        try (Connection connection = asApplicationRole(container)) {
            execute(connection, runToRunning());
            execute(connection, rawContent(content));
            execute(connection, rawLogicalUnit(unit));
            execute(connection, rawObservation(observation, RUN, unit, content));
            assertThat(count(connection, acknowledge(observation, 0, "orders-page-2")))
                    .isEqualTo(1);
        }

        assertRefused(RUN_AUTHORITY_LOST, acknowledge(observation, 0, "orders-page-1"));

        try (Connection connection = asApplicationRole(container)) {
            assertThat(checkpointVersion(connection)).isEqualTo(1);
            assertThat(single(connection,
                    "SELECT position_value FROM ops.ingestion_checkpoint"
                            + " WHERE job_id = '" + JOB + "'"))
                    .isEqualTo("orders-page-2");
        }
    }

    @Test
    @DisplayName("TC-CTRL-409 an observation from a different run"
            + " cannot acknowledge this run's cursor")
    void foreignObservationCannotAcknowledge() throws SQLException {
        UUID content = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID observation = UUID.randomUUID();
        UUID foreignRun = UUID.randomUUID();

        try (Connection connection = asApplicationRole(container)) {
            execute(connection, runToRunning());
            execute(connection, rawContent(content));
            execute(connection, rawLogicalUnit(unit));
            // The foreign run is a real run of a real second job. The point of
            // the case is that reality is not enough: the observation must
            // belong to the acknowledging run itself.
            execute(connection, """
                    INSERT INTO platform.ingestion_job
                        (id, organization_id, marketplace_account_id, platform_code,
                         service_account_id, job_code, display_name, status,
                         created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'OZON', '%s', 'ozon-returns',
                            'Ozon returns', 'ACTIVE', now(), now())
                    """.formatted(IngestionControlPlaneFixture.SECOND_JOB,
                    IngestionControlPlaneFixture.ORGANIZATION,
                    IngestionControlPlaneFixture.ACCOUNT,
                    IngestionControlPlaneFixture.SERVICE_ACCOUNT));
            execute(connection, """
                    INSERT INTO ops.ingestion_run
                        (id, job_id, state, fence_token, lease_owner, lease_expires_at,
                         attempt_no, last_call_seq, created_at, updated_at)
                    VALUES ('%s', '%s', 'RUNNING', 1, 'worker-b',
                            now() + interval '5 minutes', 1, 1, now(), now())
                    """.formatted(foreignRun, IngestionControlPlaneFixture.SECOND_JOB));
            execute(connection, rawObservation(observation, foreignRun, unit, content));
        }

        assertRefused(CHECKPOINT_WITHOUT_EVIDENCE, acknowledge(observation, 0, "orders-page-2"));

        try (Connection connection = asApplicationRole(container)) {
            assertThat(checkpointVersion(connection)).isZero();
        }
    }

    /**
     * Immutability is a property of the privilege set, so it holds for any
     * client, not only for code that behaves. With no UPDATE privilege on any
     * raw table the application cannot rewrite an observation, and it cannot
     * take a row lock on one either, so stored evidence stays exactly what the
     * acquisition wrote.
     */
    @Test
    @DisplayName("TC-CTRL-410 Raw evidence is structurally immutable for the application role")
    void rawEvidenceIsStructurallyImmutable() throws SQLException {
        assertRefused(INSUFFICIENT_PRIVILEGE,
                "UPDATE raw.raw_acquisition_observation SET native_status = 'rewritten'");
        assertRefused(INSUFFICIENT_PRIVILEGE, "DELETE FROM raw.raw_content");
        assertRefused(INSUFFICIENT_PRIVILEGE, "SELECT 1 FROM raw.raw_content FOR UPDATE");
    }

    @Test
    @DisplayName("TC-CTRL-411 content addressing collapses identical bytes into one row")
    void identicalBytesAreOneContentRow() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try {
                execute(connection, rawContent(UUID.randomUUID()));

                Throwable failure = Assertions.catchThrowable(() ->
                        execute(connection, rawContent(UUID.randomUUID())));

                assertThat(failure)
                        .as("a second row for the same bytes must be refused")
                        .isNotNull();
                assertThat(carriesSqlState(failure, UNIQUE_VIOLATION))
                        .as("expected SQLSTATE %s from: %s", UNIQUE_VIOLATION, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * At most one live run per job is what keeps each job's control-epoch row
     * to at most one share-lock holder, which is the argument that a
     * platform-wide control change waits for a bounded number of acquisitions
     * rather than for an unbounded queue of them.
     */
    @Test
    @DisplayName("TC-CTRL-412 at most one live run may exist per job")
    void atMostOneLiveRunPerJob() throws SQLException {
        assertRefused(UNIQUE_VIOLATION, """
                INSERT INTO ops.ingestion_run
                    (id, job_id, state, fence_token, attempt_no, last_call_seq,
                     created_at, updated_at)
                VALUES ('%s', '%s', 'QUEUED', 1, 0, 0, now(), now())
                """.formatted(UUID.randomUUID(), JOB));
    }

    @Test
    @DisplayName("TC-CTRL-413 an unknown pagination strategy may not carry a position")
    void unknownStrategyMayNotCarryAPosition() throws SQLException {
        assertRefused(CHECK_VIOLATION, """
                UPDATE ops.ingestion_checkpoint
                   SET strategy = 'UNKNOWN', position_value = 'guessed-cursor',
                       updated_at = now()
                 WHERE job_id = '%s'
                """.formatted(JOB));
    }

    /** The fixture grant at {@code fenceToken}, consuming the stored epochs. */
    private static String storedEpochGrant(long fenceToken, String correlationId) {
        return grantUsingStoredEpochs(fenceToken,
                storedEpoch("ORGANIZATION", ORGANIZATION),
                storedEpoch("MARKETPLACE_ACCOUNT", ACCOUNT),
                storedEpoch("SERVICE_ACCOUNT", SERVICE_ACCOUNT),
                storedEpoch("JOB", JOB),
                correlationId);
    }

    private static String runToRunning() {
        return """
                UPDATE ops.ingestion_run
                   SET state = 'RUNNING', updated_at = now()
                 WHERE id = '%s'
                """.formatted(RUN);
    }

    private static String rawContent(UUID id) {
        return """
                INSERT INTO raw.raw_content
                    (id, hash_algorithm, hash_value, byte_length, object_ref, first_seen_at)
                VALUES ('%s', 'SHA256', '%s', 2048,
                        'object-ref://raw/ozon/orders/p1', now())
                """.formatted(id, CONTENT_HASH);
    }

    private static String rawLogicalUnit(UUID id) {
        return """
                INSERT INTO raw.raw_logical_unit
                    (id, job_id, marketplace_account_id, unit_kind, source_unit_key,
                     first_seen_at)
                VALUES ('%s', '%s', '%s', 'ORDER_PAGE', 'orders-page-1', now())
                """.formatted(id, JOB, ACCOUNT);
    }

    private static String rawObservation(UUID id, UUID runId, UUID unitId, UUID contentId) {
        return """
                INSERT INTO raw.raw_acquisition_observation
                    (id, run_id, logical_unit_id, content_id, call_seq, native_status,
                     outcome_class, ingestion_time)
                VALUES ('%s', '%s', '%s', '%s', 1, 'OK', 'SUCCESS_BYTES', now())
                """.formatted(id, runId, unitId, contentId);
    }

    private static String acknowledge(UUID observationId, long expectedVersion, String position) {
        return "SELECT ops.acknowledge_checkpoint('%s', 1, '%s', %d, '%s')"
                .formatted(RUN, observationId, expectedVersion, position);
    }

    private static String runState(Connection connection) throws SQLException {
        return single(connection,
                "SELECT state FROM ops.ingestion_run WHERE id = '" + RUN + "'");
    }

    private static long evidenceRows(Connection connection) throws SQLException {
        return count(connection, "SELECT count(*) FROM ops.authorization_decision_evidence");
    }

    private static long checkpointVersion(Connection connection) throws SQLException {
        return count(connection,
                "SELECT checkpoint_version FROM ops.ingestion_checkpoint"
                        + " WHERE job_id = '" + JOB + "'");
    }

    private static void assertRefused(String sqlState, String sql) throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                Throwable failure = Assertions.catchThrowable(() -> statement.execute(sql));
                assertThat(failure)
                        .as("the statement must be refused with SQLSTATE %s", sqlState)
                        .isNotNull();
                assertThat(carriesSqlState(failure, sqlState))
                        .as("expected SQLSTATE %s from: %s", sqlState, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }
    }
}
