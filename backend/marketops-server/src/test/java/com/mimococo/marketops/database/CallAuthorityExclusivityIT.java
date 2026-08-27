package com.mimococo.marketops.database;

import static com.mimococo.marketops.database.IngestionControlPlaneFixture.JOB;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.JOB_GRAPH_NOT_AUTHORITATIVE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.RUN;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.RUN_AUTHORITY_LOST;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.CREDENTIAL_NOT_AUTHORITATIVE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SCOPE_GRANT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SCOPE_GRANT_NOT_AUTHORITATIVE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SERVICE_ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.epochOf;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.grant;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The serialization the grant and the acknowledgement must provide against a
 * concurrent control mutation and a concurrent takeover, proven with two live
 * connections and real lock waits.
 *
 * <p>The contract under test has exactly two permitted interleavings. A control
 * mutation either commits before the grant transaction begins, in which case
 * the grant evaluates the mutated truth; or it queues behind the grant's held
 * locks and commits after, in which case the already-issued authority stands,
 * bounded as granted. A takeover either commits first, in which case the old
 * worker's next transition matches zero rows; or it waits behind the run lock
 * and the old worker's transition completes before authority moves. There is
 * no third interleaving, and each case here closes one candidate for it.
 */
class CallAuthorityExclusivityIT extends PostgresContainerSupport {

    /** How long a blocked statement must demonstrably stay blocked. */
    private static final long BLOCKED_PROOF_MS = 1500;

    /** How long a released statement may take before the case is a hang. */
    private static final long RELEASE_BOUND_SECONDS = 30;

    private static PostgreSQLContainer container;
    private static ExecutorService second;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
        second = Executors.newSingleThreadExecutor();
    }

    @AfterAll
    static void stopExecutor() {
        second.shutdownNow();
    }

    @BeforeEach
    void resetAndSeedFixture() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            IngestionControlPlaneFixture.reset(connection);
            IngestionControlPlaneFixture.seed(connection);
        }
    }

    @Test
    @DisplayName("TC-CTRL-420 a revocation committed before the grant refuses it"
            + " from database truth, with zero residue")
    void revocationCommittedBeforeGrantRefusesIt() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            execute(connection, """
                    UPDATE iam.service_account_scope_grant
                       SET status = 'REVOKED', reason = 'operator revocation',
                           updated_at = now()
                     WHERE id = '%s'
                    """.formatted(SCOPE_GRANT));
        }

        try (Connection connection = asApplicationRole(container)) {
            Throwable failure = catchThrowable(() ->
                    execute(connection, grant(1, "worker-a", "corr-420")));
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, SCOPE_GRANT_NOT_AUTHORITATIVE)).isTrue();

            assertThat(single(connection,
                    "SELECT state FROM ops.ingestion_run WHERE id = '" + RUN + "'"))
                    .isEqualTo("LEASED");
            assertThat(count(connection,
                    "SELECT count(*) FROM ops.authorization_decision_evidence")).isZero();
        }
    }

    /**
     * The heart of F01: while the grant transaction is open it holds the four
     * epoch rows FOR SHARE, so the metadata writer's epoch advance -- fired by
     * its own statement trigger -- demonstrably waits, and its change lands
     * strictly after an authority that was already bounded and committed.
     */
    @Test
    @DisplayName("TC-CTRL-421 a control writer waits behind the grant's held locks"
            + " and the granted authority stands")
    void controlWriterWaitsBehindTheGrantLocks() throws Exception {
        long subjectEpochBefore;
        try (Connection reader = asApplicationRole(container)) {
            subjectEpochBefore = epochOf(reader, "SERVICE_ACCOUNT", SERVICE_ACCOUNT);
        }

        try (Connection granting = asApplicationRole(container);
             Connection writer = asApplicationRole(container)) {
            granting.setAutoCommit(false);
            execute(granting, grant(1, "worker-a", "corr-421"));

            Future<?> mutation = second.submit(() -> {
                execute(writer, """
                        UPDATE iam.service_account
                           SET display_name = 'Acme Worker (late)', updated_at = now(),
                               version = version + 1
                         WHERE id = '%s'
                        """.formatted(SERVICE_ACCOUNT));
                return null;
            });

            assertThatThrownBy(() -> mutation.get(BLOCKED_PROOF_MS, TimeUnit.MILLISECONDS))
                    .as("the epoch advance waits behind the grant's FOR SHARE")
                    .isInstanceOf(TimeoutException.class);

            granting.commit();
            mutation.get(RELEASE_BOUND_SECONDS, TimeUnit.SECONDS);
        }

        try (Connection reader = asApplicationRole(container)) {
            assertThat(strings(reader, """
                    SELECT value::text
                      FROM ops.authorization_decision_evidence,
                           unnest(control_epoch_scopes, control_epoch_values)
                               AS consumed (scope, value)
                     WHERE consumed.scope = 'SERVICE_ACCOUNT'
                    """))
                    .as("the committed grant consumed the pre-mutation epoch")
                    .containsExactly(String.valueOf(subjectEpochBefore));
            assertThat(epochOf(reader, "SERVICE_ACCOUNT", SERVICE_ACCOUNT))
                    .as("the writer's advance landed after the grant")
                    .isEqualTo(subjectEpochBefore + 1);
        }
    }

    /**
     * The mirror of the wait: when the writer already holds the epoch row, a
     * grant bounded by lock_timeout refuses rather than proceeding on state it
     * could not lock, and the refusal leaves nothing behind.
     */
    @Test
    @DisplayName("TC-CTRL-422 a grant that cannot obtain the epoch locks in time"
            + " refuses with zero residue")
    void lockTimeoutRefusesTheGrantWithZeroResidue() throws Exception {
        try (Connection writer = asApplicationRole(container);
             Connection granting = asApplicationRole(container)) {
            writer.setAutoCommit(false);
            execute(writer, """
                    UPDATE iam.service_account
                       SET display_name = 'Acme Worker (held)', updated_at = now(),
                           version = version + 1
                     WHERE id = '%s'
                    """.formatted(SERVICE_ACCOUNT));

            granting.setAutoCommit(false);
            try {
                execute(granting, "SET LOCAL lock_timeout = '300ms'");
                Throwable failure = catchThrowable(() ->
                        execute(granting, grant(1, "worker-a", "corr-422")));
                assertThat(failure)
                        .as("the grant must not wait out a held control lock forever")
                        .isNotNull();
                assertThat(carriesSqlState(failure, "55P03")).isTrue();
            } finally {
                granting.rollback();
                writer.rollback();
            }
        }

        try (Connection reader = asApplicationRole(container)) {
            assertThat(single(reader,
                    "SELECT state FROM ops.ingestion_run WHERE id = '" + RUN + "'"))
                    .isEqualTo("LEASED");
            assertThat(count(reader,
                    "SELECT count(*) FROM ops.authorization_decision_evidence")).isZero();
        }
    }

    @Test
    @DisplayName("TC-CTRL-F01-A a revocation that owns the epoch commits first;"
            + " the waiting grant re-evaluates and refuses")
    void revocationCommitWhileGrantWaitsIsReevaluated() throws Exception {
        try (Connection writer = asApplicationRole(container);
             Connection granting = asApplicationRole(container)) {
            writer.setAutoCommit(false);
            execute(writer, """
                    UPDATE iam.service_account_scope_grant
                       SET status = 'REVOKED', reason = 'concurrent revocation',
                           updated_at = now()
                     WHERE id = '%s'
                    """.formatted(SCOPE_GRANT));

            Future<Throwable> attempt = second.submit(() ->
                    catchThrowable(() -> execute(
                            granting, grant(1, "worker-a", "corr-f01-a"))));
            assertThatThrownBy(() -> attempt.get(BLOCKED_PROOF_MS, TimeUnit.MILLISECONDS))
                    .as("the grant waits at the epoch serialization point")
                    .isInstanceOf(TimeoutException.class);

            writer.commit();
            Throwable failure = attempt.get(RELEASE_BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, SCOPE_GRANT_NOT_AUTHORITATIVE)).isTrue();
        }

        assertZeroGrantResidue();
    }

    @Test
    @DisplayName("TC-CTRL-F01-B a Credential disable that commits first"
            + " is observed by the waiting grant")
    void credentialDisableCommitWhileGrantWaitsIsReevaluated() throws Exception {
        try (Connection writer = asApplicationRole(container);
             Connection granting = asApplicationRole(container)) {
            writer.setAutoCommit(false);
            execute(writer, """
                    UPDATE platform.credential_metadata
                       SET status = 'DISABLED', updated_at = now()
                     WHERE id = '%s'
                    """.formatted(IngestionControlPlaneFixture.CREDENTIAL));

            Future<Throwable> attempt = second.submit(() ->
                    catchThrowable(() -> execute(
                            granting, grant(1, "worker-a", "corr-f01-b"))));
            assertThatThrownBy(() -> attempt.get(BLOCKED_PROOF_MS, TimeUnit.MILLISECONDS))
                    .as("the grant waits behind the Credential epoch write")
                    .isInstanceOf(TimeoutException.class);

            writer.commit();
            Throwable failure = attempt.get(RELEASE_BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, CREDENTIAL_NOT_AUTHORITATIVE)).isTrue();
        }

        assertZeroGrantResidue();
    }

    @Test
    @DisplayName("TC-CTRL-F01-C1 a Job mutation committed first is observed by the grant")
    void jobMutationCommittedFirstIsObserved() throws Exception {
        try (Connection writer = asApplicationRole(container);
             Connection granting = asApplicationRole(container)) {
            writer.setAutoCommit(false);
            execute(writer, "UPDATE platform.ingestion_job SET status = 'PAUSED',"
                    + " updated_at = now() WHERE id = '" + JOB + "'");

            Future<Throwable> attempt = second.submit(() ->
                    catchThrowable(() -> execute(
                            granting, grant(1, "worker-a", "corr-f01-c1"))));
            assertThatThrownBy(() -> attempt.get(BLOCKED_PROOF_MS, TimeUnit.MILLISECONDS))
                    .as("the grant waits behind the exact Job row")
                    .isInstanceOf(TimeoutException.class);

            writer.commit();
            Throwable failure = attempt.get(RELEASE_BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, JOB_GRAPH_NOT_AUTHORITATIVE)).isTrue();
        }

        assertZeroGrantResidue();
    }

    @Test
    @DisplayName("TC-CTRL-F01-C2 a Job writer ordered after a grant waits and lands after it")
    void jobWriterAfterGrantWaitsAndLandsAfterIt() throws Exception {
        try (Connection granting = asApplicationRole(container);
             Connection writer = asApplicationRole(container)) {
            granting.setAutoCommit(false);
            execute(granting, grant(1, "worker-a", "corr-f01-c2"));

            Future<?> mutation = second.submit(() -> {
                execute(writer, "UPDATE platform.ingestion_job SET status = 'PAUSED',"
                        + " updated_at = now() WHERE id = '" + JOB + "'");
                return null;
            });
            assertThatThrownBy(() -> mutation.get(BLOCKED_PROOF_MS, TimeUnit.MILLISECONDS))
                    .as("the Job writer waits behind the grant's Job lock")
                    .isInstanceOf(TimeoutException.class);

            granting.commit();
            mutation.get(RELEASE_BOUND_SECONDS, TimeUnit.SECONDS);
        }

        try (Connection reader = asApplicationRole(container)) {
            assertThat(single(reader,
                    "SELECT state FROM ops.ingestion_run WHERE id = '" + RUN + "'"))
                    .isEqualTo("RUNNING");
            assertThat(single(reader,
                    "SELECT status FROM platform.ingestion_job WHERE id = '" + JOB + "'"))
                    .isEqualTo("PAUSED");
            assertThat(count(reader,
                    "SELECT count(*) FROM ops.authorization_decision_evidence")).isOne();
        }
    }

    @Test
    @DisplayName("TC-CTRL-423 a takeover waits behind an open grant"
            + " and the superseded worker's next transition matches nothing")
    void takeoverWaitsBehindAnOpenGrant() throws Exception {
        try (Connection granting = asApplicationRole(container);
             Connection admin = asMigrationRole(container)) {
            granting.setAutoCommit(false);
            execute(granting, grant(1, "worker-a", "corr-423"));

            Future<?> takeover = second.submit(() -> {
                execute(admin, """
                        UPDATE ops.ingestion_run
                           SET fence_token = fence_token + 1, lease_owner = 'worker-b',
                               state = 'LEASED',
                               lease_expires_at = now() + interval '5 minutes',
                               updated_at = now()
                         WHERE id = '%s'
                        """.formatted(RUN));
                return null;
            });

            assertThatThrownBy(() -> takeover.get(BLOCKED_PROOF_MS, TimeUnit.MILLISECONDS))
                    .as("the takeover waits behind the run lock the grant holds")
                    .isInstanceOf(TimeoutException.class);

            granting.commit();
            takeover.get(RELEASE_BOUND_SECONDS, TimeUnit.SECONDS);
        }

        // The old worker's authority is gone: its next acknowledgement names a
        // fence and owner the run no longer has.
        try (Connection stale = asApplicationRole(container)) {
            Throwable failure = catchThrowable(() -> execute(stale,
                    "SELECT ops.acknowledge_checkpoint('%s', 1, 'worker-a', '%s', 0, 'p9')"
                            .formatted(RUN, UUID.randomUUID())));
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, RUN_AUTHORITY_LOST)).isTrue();
        }
    }

    @Test
    @DisplayName("TC-CTRL-424 a takeover committed first makes the old worker's grant"
            + " a refusal, with zero residue")
    void takeoverCommittedFirstRefusesTheOldGrant() throws SQLException {
        try (Connection admin = asMigrationRole(container)) {
            execute(admin, """
                    UPDATE ops.ingestion_run
                       SET fence_token = 2, lease_owner = 'worker-b',
                           lease_expires_at = now() + interval '5 minutes',
                           updated_at = now()
                     WHERE id = '%s'
                    """.formatted(RUN));
        }

        try (Connection stale = asApplicationRole(container)) {
            Throwable failure = catchThrowable(() ->
                    execute(stale, grant(1, "worker-a", "corr-424")));
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, RUN_AUTHORITY_LOST)).isTrue();
            assertThat(count(stale,
                    "SELECT count(*) FROM ops.authorization_decision_evidence")).isZero();
        }

        // The current worker's grant, at the current fence, succeeds.
        try (Connection current = asApplicationRole(container)) {
            assertThat(single(current, """
                    SELECT platform.grant_call_authority(
                        '%s', 2, 'worker-b', '%s', interval '30 seconds', 'corr-424b')
                    """.formatted(RUN, SCOPE_GRANT)))
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("TC-CTRL-425 an expired lease refuses the grant even at the right fence")
    void expiredLeaseRefusesTheGrant() throws SQLException {
        try (Connection admin = asMigrationRole(container)) {
            execute(admin, """
                    UPDATE ops.ingestion_run
                       SET lease_expires_at = now() - interval '1 second', updated_at = now()
                     WHERE id = '%s'
                    """.formatted(RUN));
        }

        try (Connection stale = asApplicationRole(container)) {
            Throwable failure = catchThrowable(() ->
                    execute(stale, grant(1, "worker-a", "corr-425")));
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, RUN_AUTHORITY_LOST)).isTrue();
            assertThat(count(stale,
                    "SELECT count(*) FROM ops.authorization_decision_evidence")).isZero();
        }
    }

    @Test
    @DisplayName("TC-CTRL-426 the wrong lease owner refuses the grant even at the right fence")
    void wrongOwnerRefusesTheGrant() throws SQLException {
        try (Connection stale = asApplicationRole(container)) {
            Throwable failure = catchThrowable(() ->
                    execute(stale, grant(1, "worker-b", "corr-426")));
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, RUN_AUTHORITY_LOST)).isTrue();
            assertThat(single(stale,
                    "SELECT state FROM ops.ingestion_run WHERE id = '" + RUN + "'"))
                    .isEqualTo("LEASED");
        }
    }

    /**
     * The acknowledgement holds the run lock through the checkpoint write, so
     * a takeover cannot land between the authority check and the cursor CAS:
     * it waits, and by the time it proceeds the cursor move is already
     * committed under the old, then-valid authority.
     */
    @Test
    @DisplayName("TC-CTRL-427 a takeover waits behind an open acknowledgement"
            + " and cannot split it from its cursor write")
    void takeoverWaitsBehindAnOpenAcknowledgement() throws Exception {
        UUID observation = UUID.randomUUID();
        seedObservation(observation);

        try (Connection acknowledging = asApplicationRole(container);
             Connection admin = asMigrationRole(container)) {
            acknowledging.setAutoCommit(false);
            execute(acknowledging,
                    "SELECT ops.acknowledge_checkpoint('%s', 1, 'worker-a', '%s', 0, 'p2')"
                            .formatted(RUN, observation));

            Future<?> takeover = second.submit(() -> {
                execute(admin, """
                        UPDATE ops.ingestion_run
                           SET fence_token = fence_token + 1, lease_owner = 'worker-b',
                               updated_at = now()
                         WHERE id = '%s'
                        """.formatted(RUN));
                return null;
            });

            assertThatThrownBy(() -> takeover.get(BLOCKED_PROOF_MS, TimeUnit.MILLISECONDS))
                    .as("the takeover waits behind the acknowledgement's run lock")
                    .isInstanceOf(TimeoutException.class);

            acknowledging.commit();
            takeover.get(RELEASE_BOUND_SECONDS, TimeUnit.SECONDS);
        }

        try (Connection reader = asApplicationRole(container)) {
            assertThat(single(reader,
                    "SELECT position_value FROM ops.ingestion_checkpoint"
                            + " WHERE job_id = '" + JOB + "'"))
                    .isEqualTo("p2");
        }
    }

    @Test
    @DisplayName("TC-CTRL-428 a takeover committed before the acknowledgement"
            + " leaves the superseded worker's cursor write with zero effect")
    void takeoverCommittedFirstRefusesTheAcknowledgement() throws SQLException {
        UUID observation = UUID.randomUUID();
        seedObservation(observation);

        try (Connection admin = asMigrationRole(container)) {
            execute(admin, """
                    UPDATE ops.ingestion_run
                       SET fence_token = 2, lease_owner = 'worker-b', updated_at = now()
                     WHERE id = '%s'
                    """.formatted(RUN));
        }

        try (Connection stale = asApplicationRole(container)) {
            Throwable failure = catchThrowable(() -> execute(stale,
                    "SELECT ops.acknowledge_checkpoint('%s', 1, 'worker-a', '%s', 0, 'p3')"
                            .formatted(RUN, observation)));
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, RUN_AUTHORITY_LOST)).isTrue();
            assertThat(single(stale,
                    "SELECT coalesce(position_value, '<none>')"
                            + " FROM ops.ingestion_checkpoint WHERE job_id = '" + JOB + "'"))
                    .as("the cursor did not move")
                    .isEqualTo("<none>");
        }
    }

    @Test
    @DisplayName("TC-CTRL-F03-A checkpoint blocking past lease expiry refuses the final CAS")
    void checkpointBlockPastLeaseExpiryRefusesFinalCas() throws Exception {
        UUID observation = UUID.randomUUID();
        seedObservation(observation);

        try (Connection admin = asMigrationRole(container)) {
            execute(admin, "UPDATE ops.ingestion_run"
                    + " SET lease_expires_at = clock_timestamp() + interval '3 seconds'"
                    + " WHERE id = '" + RUN + "'");
        }

        try (Connection blocker = asMigrationRole(container);
             Connection acknowledging = asApplicationRole(container);
             Connection observer = asApplicationRole(container)) {
            blocker.setAutoCommit(false);
            execute(blocker, "SELECT 1 FROM ops.ingestion_checkpoint"
                    + " WHERE job_id = '" + JOB + "' FOR UPDATE");

            Future<Throwable> attempt = second.submit(() -> catchThrowable(() -> execute(
                    acknowledging,
                    "SELECT ops.acknowledge_checkpoint('%s', 1, 'worker-a', '%s', 0, 'late')"
                            .formatted(RUN, observation))));
            assertThatThrownBy(() -> attempt.get(BLOCKED_PROOF_MS, TimeUnit.MILLISECONDS))
                    .as("acknowledgement holds the run and waits on the checkpoint row")
                    .isInstanceOf(TimeoutException.class);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!singleBoolean(observer,
                    "SELECT clock_timestamp() >= lease_expires_at"
                            + " FROM ops.ingestion_run WHERE id = '" + RUN + "'")) {
                if (System.nanoTime() >= deadline) {
                    throw new TimeoutException("lease did not expire within the test bound");
                }
                Thread.sleep(50);
            }

            blocker.commit();
            Throwable failure = attempt.get(RELEASE_BOUND_SECONDS, TimeUnit.SECONDS);
            assertThat(failure).isNotNull();
            assertThat(carriesSqlState(failure, RUN_AUTHORITY_LOST)).isTrue();
        }

        try (Connection reader = asApplicationRole(container)) {
            assertThat(single(reader, "SELECT coalesce(position_value, '<none>')"
                    + " FROM ops.ingestion_checkpoint WHERE job_id = '" + JOB + "'"))
                    .isEqualTo("<none>");
            assertThat(count(reader, "SELECT checkpoint_version"
                    + " FROM ops.ingestion_checkpoint WHERE job_id = '" + JOB + "'"))
                    .isZero();
        }
    }

    @Test
    @DisplayName("TC-CTRL-F03-B wrong-owner and already-expired acknowledgements refuse")
    void wrongOwnerAndExpiredAcknowledgementsRefuse() throws SQLException {
        UUID observation = UUID.randomUUID();
        seedObservation(observation);

        try (Connection stale = asApplicationRole(container)) {
            Throwable wrongOwner = catchThrowable(() -> execute(stale,
                    "SELECT ops.acknowledge_checkpoint('%s', 1, 'worker-b', '%s', 0, 'wrong')"
                            .formatted(RUN, observation)));
            assertThat(wrongOwner).isNotNull();
            assertThat(carriesSqlState(wrongOwner, RUN_AUTHORITY_LOST)).isTrue();
        }

        try (Connection admin = asMigrationRole(container)) {
            execute(admin, "UPDATE ops.ingestion_run"
                    + " SET lease_expires_at = now() - interval '1 second'"
                    + " WHERE id = '" + RUN + "'");
        }
        try (Connection stale = asApplicationRole(container)) {
            Throwable expired = catchThrowable(() -> execute(stale,
                    "SELECT ops.acknowledge_checkpoint('%s', 1, 'worker-a', '%s', 0, 'expired')"
                            .formatted(RUN, observation)));
            assertThat(expired).isNotNull();
            assertThat(carriesSqlState(expired, RUN_AUTHORITY_LOST)).isTrue();
            assertThat(count(stale, "SELECT checkpoint_version"
                    + " FROM ops.ingestion_checkpoint WHERE job_id = '" + JOB + "'"))
                    .isZero();
        }
    }

    private void assertZeroGrantResidue() throws SQLException {
        try (Connection reader = asApplicationRole(container)) {
            assertThat(single(reader,
                    "SELECT state FROM ops.ingestion_run WHERE id = '" + RUN + "'"))
                    .isEqualTo("LEASED");
            assertThat(count(reader,
                    "SELECT count(*) FROM ops.authorization_decision_evidence")).isZero();
        }
    }

    /** Grant into RUNNING, then store one complete evidence chain for it. */
    private void seedObservation(UUID observation) throws SQLException {
        UUID content = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        try (Connection connection = asApplicationRole(container)) {
            execute(connection, grant(1, "worker-a", "corr-seed-" + observation));
            execute(connection, """
                    INSERT INTO raw.raw_content
                        (id, hash_algorithm, hash_value, byte_length, object_ref)
                    VALUES ('%s', 'SHA256', repeat('c', 64), 2,
                            'object-ref://raw/ozon/orders/race')
                    """.formatted(content));
            execute(connection, """
                    INSERT INTO raw.raw_logical_unit
                        (id, job_id, marketplace_account_id, unit_kind, source_unit_key)
                    VALUES ('%s', '%s', '%s', 'ORDER_PAGE', 'race-%s')
                    """.formatted(unit, JOB,
                    IngestionControlPlaneFixture.ACCOUNT, unit));
            execute(connection, """
                    INSERT INTO raw.raw_acquisition_observation
                        (id, run_id, logical_unit_id, content_id, call_seq,
                         native_status, outcome_class, pagination_outcome)
                    VALUES ('%s', '%s', '%s', '%s', 1, 'OK', 'SUCCESS_BYTES', 'NEXT')
                    """.formatted(observation, RUN, unit, content));
        }
    }

    /** Run one statement, wrapping any SQL failure for use inside a lambda. */
    private static void execute(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
