package com.mimococo.marketops.database;

import static com.mimococo.marketops.database.IngestionControlPlaneFixture.ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.JOB;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.JOB_PLATFORM_IMMUTABLE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.MEMBERSHIP_GUARD_INCOMPLETE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.ORGANIZATION;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SECOND_JOB;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SERVICE_ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.epochOf;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.execute;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.grant;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.guardGenerationOf;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The per-platform membership guard: the serialization point between the two
 * writers that can disagree about which Jobs a platform-wide control change
 * covers.
 *
 * <p>A share lock freezes only the rows it saw. A Job created concurrently is
 * invisible to a fan-out's enumeration, commits on its own, and then acquires
 * call authority from control state the fan-out already superseded. Both
 * writers therefore take the same guard row FOR UPDATE before doing anything
 * else, which makes their commit order total: either the new Job is inside the
 * fan-out's membership and its epoch advances, or the Job is created strictly
 * after the control change commits and every later evaluation reads the new
 * state. These cases prove the guard is total over the platform set, cannot be
 * bypassed by an arbitrary SQL client, fails closed when a guard is missing,
 * and stays off the grant hot path.
 */
class MembershipGuardIT extends PostgresContainerSupport {

    private static final UUID FAN_OUT_FLAG =
            UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID SECOND_FAN_OUT_FLAG =
            UUID.fromString("00000000-0000-0000-0000-000000000402");

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @BeforeEach
    void resetAndSeed() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            IngestionControlPlaneFixture.reset(connection);
            IngestionControlPlaneFixture.seed(connection);
        }
    }

    @Test
    @DisplayName("TC-CTRL-300 every marketplace platform has exactly one PLATFORM_JOB_SET guard")
    void everyPlatformHasExactlyOneGuard() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> platforms = strings(connection,
                    "SELECT code FROM core.marketplace_platform ORDER BY code");
            List<String> guarded = strings(connection,
                    "SELECT platform_code FROM platform.control_epoch_membership_guard"
                            + " WHERE guard_kind = 'PLATFORM_JOB_SET' ORDER BY platform_code");

            assertThat(platforms).containsExactly("OZON", "WILDBERRIES");
            assertThat(guarded)
                    .as("the guarded set is the platform set, in both directions")
                    .containsExactlyElementsOf(platforms);
            // No orphan of any kind either: the guard table holds nothing
            // beyond the one row per platform asserted above.
            assertThat(count(connection,
                    "SELECT count(*) FROM platform.control_epoch_membership_guard"))
                    .isEqualTo(platforms.size());
        }
    }

    @Test
    @DisplayName("TC-CTRL-301 a guard cannot name a code that is not a platform")
    void orphanGuardIsUnrepresentable() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            connection.setAutoCommit(false);
            try {
                Throwable failure = Assertions.catchThrowable(() -> execute(connection,
                        "INSERT INTO platform.control_epoch_membership_guard"
                                + " (guard_kind, platform_code, generation)"
                                + " VALUES ('PLATFORM_JOB_SET', 'NOT_A_PLATFORM', 1)"));
                assertThat(failure)
                        .as("a guard for a code that is not a platform must be refused")
                        .isNotNull();
                assertThat(carriesSqlState(failure, FOREIGN_KEY_VIOLATION))
                        .as("expected SQLSTATE %s from: %s", FOREIGN_KEY_VIOLATION, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * Two mechanisms refuse this transaction, in order. The routed fan-out
     * trigger on the platform table enumerates every platform code and demands
     * every guard at the end of the INSERT itself, so the statement fails with
     * the guard-incompleteness code before the deferred totality trigger would
     * get its turn at commit. The assertion covers the transaction as a whole:
     * whichever layer answers first, a platform without its guard can never
     * reach a committed state.
     */
    @Test
    @DisplayName("TC-CTRL-302 a platform added without its guard cannot commit")
    void platformWithoutGuardCannotCommit() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            connection.setAutoCommit(false);
            try {
                Throwable failure = Assertions.catchThrowable(() -> {
                    execute(connection,
                            "INSERT INTO core.marketplace_platform (code, display_name, status)"
                                    + " VALUES ('YANDEX_MARKET', 'Yandex Market', 'ACTIVE')");
                    connection.commit();
                });
                assertThat(failure)
                        .as("the transaction must not commit a guardless platform")
                        .isNotNull();
                assertThat(carriesSqlState(failure, MEMBERSHIP_GUARD_INCOMPLETE))
                        .as("expected SQLSTATE %s from: %s",
                                MEMBERSHIP_GUARD_INCOMPLETE, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }
        try (Connection connection = asMigrationRole(container)) {
            assertThat(strings(connection,
                    "SELECT code FROM core.marketplace_platform ORDER BY code"))
                    .as("the failure must leave the platform set unchanged")
                    .containsExactly("OZON", "WILDBERRIES");
        }
    }

    /**
     * The platform and its guard have to appear in one statement, not merely
     * one transaction: the fan-out trigger on the platform table runs at the
     * end of the INSERT that creates the platform and already demands the
     * guard, while the guard's foreign key demands the platform. A
     * data-modifying CTE satisfies both, because the foreign key sees the
     * platform row created earlier in the same statement and the trigger runs
     * only after the guard row exists. The cleanup deletes the guard first --
     * the foreign key forbids removing a platform that a guard still names --
     * and commits both deletions together so the deferred totality check sees
     * the two sets equal again.
     */
    @Test
    @DisplayName("TC-CTRL-303 a platform and its guard created together commit together")
    void platformAndGuardCreatedTogetherSucceed() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            execute(connection, """
                    WITH new_platform AS (
                        INSERT INTO core.marketplace_platform (code, display_name, status)
                        VALUES ('YANDEX_MARKET', 'Yandex Market', 'ACTIVE')
                        RETURNING code
                    )
                    INSERT INTO platform.control_epoch_membership_guard
                        (guard_kind, platform_code, generation)
                    SELECT 'PLATFORM_JOB_SET', code, 1 FROM new_platform
                    """);
            try {
                assertThat(strings(connection,
                        "SELECT code FROM core.marketplace_platform ORDER BY code"))
                        .containsExactly("OZON", "WILDBERRIES", "YANDEX_MARKET");
                assertThat(guardGenerationOf(connection, "YANDEX_MARKET"))
                        .as("the new platform's guard exists")
                        .isPositive();
            } finally {
                connection.setAutoCommit(false);
                execute(connection,
                        "DELETE FROM platform.control_epoch_membership_guard"
                                + " WHERE platform_code = 'YANDEX_MARKET'");
                execute(connection,
                        "DELETE FROM core.marketplace_platform WHERE code = 'YANDEX_MARKET'");
                connection.commit();
                connection.setAutoCommit(true);
            }
            assertThat(strings(connection,
                    "SELECT code FROM core.marketplace_platform ORDER BY code"))
                    .as("the cleanup must restore the seeded platform set")
                    .containsExactly("OZON", "WILDBERRIES");
        }
    }

    /**
     * The mirror direction of totality. Nothing fires on the guard delete
     * itself, so the statement succeeds and the violation is only visible to
     * the deferred constraint trigger when the transaction tries to commit --
     * which is exactly where it must surface, because guard and platform are
     * necessarily written by separate statements and any intermediate state
     * inside one transaction is legitimate.
     */
    @Test
    @DisplayName("TC-CTRL-304 removing a guard from a live platform fails at commit")
    void removingAGuardFromALivePlatformFailsAtCommit() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            connection.setAutoCommit(false);
            execute(connection,
                    "DELETE FROM platform.control_epoch_membership_guard"
                            + " WHERE guard_kind = 'PLATFORM_JOB_SET'"
                            + " AND platform_code = 'OZON'");

            Throwable failure = Assertions.catchThrowable(connection::commit);
            assertThat(failure)
                    .as("the commit must refuse to leave OZON without its guard")
                    .isNotNull();
            assertThat(carriesSqlState(failure, MEMBERSHIP_GUARD_INCOMPLETE))
                    .as("expected SQLSTATE %s from: %s", MEMBERSHIP_GUARD_INCOMPLETE, failure)
                    .isTrue();
        }
        try (Connection connection = asMigrationRole(container)) {
            assertThat(guardGenerationOf(connection, "OZON"))
                    .as("the rolled-back deletion must leave the guard in place")
                    .isPositive();
        }
    }

    /**
     * SELECT ... FOR UPDATE locks the rows it returns, and a missing guard
     * returns no row: without the row-count assertion inside the acquisition
     * function the statement would succeed, lock nothing, and the caller would
     * proceed believing it holds a serialization point it does not hold. A
     * missing guard must stop the transaction, not silently weaken it, so the
     * function counts what it locked against what it was asked for and refuses
     * on any shortfall.
     */
    @Test
    @DisplayName("TC-CTRL-305 acquiring a missing guard fails closed instead of locking zero rows")
    void acquiringAMissingGuardFailsClosed() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            long ozonBefore = guardGenerationOf(connection, "OZON");
            long wildberriesBefore = guardGenerationOf(connection, "WILDBERRIES");

            Throwable failure = Assertions.catchThrowable(() -> execute(connection,
                    "SELECT platform.acquire_platform_job_set_guard("
                            + "ARRAY['OZON','NOT_A_PLATFORM'])"));
            assertThat(failure)
                    .as("acquiring a guard that does not exist must fail")
                    .isNotNull();
            assertThat(carriesSqlState(failure, MEMBERSHIP_GUARD_INCOMPLETE))
                    .as("expected SQLSTATE %s from: %s", MEMBERSHIP_GUARD_INCOMPLETE, failure)
                    .isTrue();
            assertThat(failure.getMessage())
                    .as("the refusal reports how many of the requested guards were locked")
                    .contains("locked 1 of 2");

            assertThat(guardGenerationOf(connection, "OZON"))
                    .as("the aborted acquisition advances nothing")
                    .isEqualTo(ozonBefore);
            assertThat(guardGenerationOf(connection, "WILDBERRIES"))
                    .isEqualTo(wildberriesBefore);
        }
    }

    @Test
    @DisplayName("TC-CTRL-306 acquiring an existing guard advances its generation by exactly one")
    void acquiringAnExistingGuardAdvancesItsGeneration() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            long before = guardGenerationOf(connection, "OZON");

            assertThat(single(connection,
                    "SELECT platform.acquire_platform_job_set_guard(ARRAY['OZON'])"))
                    .as("the acquisition reports one guard locked")
                    .isEqualTo("1");

            assertThat(guardGenerationOf(connection, "OZON"))
                    .as("each acquisition is visible as exactly one generation step")
                    .isEqualTo(before + 1);
        }
    }

    @Test
    @DisplayName("TC-CTRL-307 an ingestion job may never change platform")
    void jobPlatformIsWriteOnce() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try {
                Throwable failure = Assertions.catchThrowable(() -> execute(connection,
                        "UPDATE platform.ingestion_job SET platform_code = 'WILDBERRIES'"
                                + " WHERE id = '" + JOB + "'"));
                assertThat(failure)
                        .as("moving a job between membership sets must be refused")
                        .isNotNull();
                assertThat(carriesSqlState(failure, JOB_PLATFORM_IMMUTABLE))
                        .as("expected SQLSTATE %s from: %s", JOB_PLATFORM_IMMUTABLE, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * The race the guard exists to close, taken in the order that would lose
     * without it. The fan-out enumerates the OZON jobs and holds its
     * transaction open; a concurrent job creation must queue behind the guard
     * rather than commit a job the fan-out never saw. When the fan-out
     * commits, the creation proceeds, and the two terminal states are the only
     * two the design admits: the pre-existing job's epoch advanced because it
     * was inside the fan-out's membership, and the new job exists with a fresh
     * epoch because it was created after the control change committed. The
     * bounded waits assert ordering -- blocked while the guard is held, done
     * once it is released -- never elapsed time.
     */
    @Test
    @DisplayName("TC-CTRL-308 a job created during a platform fan-out waits for it and lands after it")
    void jobCreationDuringFanOutWaitsForTheGuard() throws Exception {
        long jobEpochBefore;
        try (Connection reader = asMigrationRole(container)) {
            jobEpochBefore = epochOf(reader, "JOB", JOB);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection fanOut = asMigrationRole(container);
             Connection creator = asMigrationRole(container)) {
            fanOut.setAutoCommit(false);
            execute(fanOut, platformFanOut(FAN_OUT_FLAG, "ozon-fan-out"));

            Future<Void> creation = executor.submit(() -> {
                execute(creator, secondJob());
                return null;
            });

            assertThatThrownBy(() -> creation.get(2, TimeUnit.SECONDS))
                    .as("the job creation must still be blocked while the fan-out"
                            + " holds the guard")
                    .isInstanceOf(TimeoutException.class);

            fanOut.commit();
            creation.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        try (Connection reader = asMigrationRole(container)) {
            assertThat(epochOf(reader, "JOB", JOB))
                    .as("the pre-existing job was inside the fan-out's membership")
                    .isEqualTo(jobEpochBefore + 1);
            assertThat(epochOf(reader, "JOB", SECOND_JOB))
                    .as("the new job exists, with the fresh epoch of a job created"
                            + " after the control change committed; there is no third state")
                    .isEqualTo(1L);
        }
    }

    /**
     * The same race with the commit order reversed. The second job commits
     * before the fan-out begins, so it is a member the guard's serialization
     * forces the fan-out to see: both jobs' epochs advance, and no job that
     * committed first can retain call authority derived from the superseded
     * control state.
     */
    @Test
    @DisplayName("TC-CTRL-309 a fan-out advances every job that committed before it")
    void fanOutAfterJobCreationAdvancesBothJobs() throws SQLException {
        long firstBefore;
        long secondBefore;
        try (Connection creator = asMigrationRole(container)) {
            execute(creator, secondJob());
            firstBefore = epochOf(creator, "JOB", JOB);
            secondBefore = epochOf(creator, "JOB", SECOND_JOB);
            assertThat(secondBefore)
                    .as("the just-created job starts at the initial epoch")
                    .isEqualTo(1L);
        }

        try (Connection fanOut = asMigrationRole(container)) {
            execute(fanOut, platformFanOut(SECOND_FAN_OUT_FLAG, "ozon-fan-out-late"));

            assertThat(epochOf(fanOut, "JOB", JOB)).isEqualTo(firstBefore + 1);
            assertThat(epochOf(fanOut, "JOB", SECOND_JOB))
                    .as("a committed job is inside the membership the guard serialised")
                    .isEqualTo(secondBefore + 1);
        }
    }

    /**
     * The membership protocol is attached to the table, not to an application
     * code path, so a direct INSERT from any SQL session joins it whether or
     * not the session knows the protocol exists. The catalog fact is asserted
     * alongside the behavior: a bypass would first have to not be there.
     */
    @Test
    @DisplayName("TC-CTRL-310 the guard acquisition is a trigger no SQL client can skip")
    void guardAcquisitionCannotBeBypassed() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            assertThat(singleBoolean(connection, """
                    SELECT EXISTS (
                        SELECT 1
                          FROM pg_trigger AS trg
                          JOIN pg_class AS rel ON rel.oid = trg.tgrelid
                          JOIN pg_namespace AS nsp ON nsp.oid = rel.relnamespace
                         WHERE nsp.nspname = 'platform'
                           AND rel.relname = 'ingestion_job'
                           AND trg.tgname = 'ingestion_job_membership_guard_ai'
                           AND NOT trg.tgisinternal
                           AND (trg.tgtype & 1) = 0)
                    """))
                    .as("the AFTER INSERT statement trigger that acquires the guard"
                            + " is attached to platform.ingestion_job")
                    .isTrue();

            long ozonBefore = guardGenerationOf(connection, "OZON");
            long wildberriesBefore = guardGenerationOf(connection, "WILDBERRIES");

            execute(connection, secondJob());

            assertThat(guardGenerationOf(connection, "OZON"))
                    .as("a direct INSERT still passes through the guard")
                    .isEqualTo(ozonBefore + 1);
            assertThat(guardGenerationOf(connection, "WILDBERRIES"))
                    .as("job creation takes only its own platform's guard")
                    .isEqualTo(wildberriesBefore);
        }
    }

    /**
     * The grant path reads epochs under share locks and never touches the
     * membership guard, and that absence is load-bearing: the guard is an
     * exclusive lock, and putting one on the acquisition hot path would let a
     * high-frequency read path starve the low-frequency operator action that
     * needs to stop it. The proof of membership coverage is carried by the
     * commit-order argument the guard already provides to the writers, so the
     * grant has nothing left to lock here.
     */
    @Test
    @DisplayName("TC-CTRL-311 the grant path never touches the membership guard")
    void grantPathNeverLocksTheMembershipGuard() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            long ozonBefore = guardGenerationOf(connection, "OZON");
            long wildberriesBefore = guardGenerationOf(connection, "WILDBERRIES");

            assertThat(single(connection, grant(1, "worker-a", "tc-ctrl-311")))
                    .as("the grant succeeds and returns a bounded authority")
                    .isNotNull();

            assertThat(guardGenerationOf(connection, "OZON"))
                    .as("a successful grant leaves every guard generation untouched")
                    .isEqualTo(ozonBefore);
            assertThat(guardGenerationOf(connection, "WILDBERRIES"))
                    .isEqualTo(wildberriesBefore);
        }
    }

    /** A second OZON job, identical in shape to the seeded one. */
    private static String secondJob() {
        return """
                INSERT INTO platform.ingestion_job
                    (id, organization_id, marketplace_account_id, platform_code,
                     service_account_id, endpoint_id, job_code, display_name, status,
                     created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'OZON', '%s', '%s', 'ozon-stocks', 'Ozon stocks',
                        'ACTIVE', now(), now())
                """.formatted(SECOND_JOB, ORGANIZATION, ACCOUNT, SERVICE_ACCOUNT,
                IngestionControlPlaneFixture.ENDPOINT);
    }

    /**
     * A platform-wide control change: an operational flag scoped to OZON. Its
     * fan-out enumerates the platform's jobs, which is what forces it through
     * the membership guard.
     */
    private static String platformFanOut(UUID flagId, String flagCode) {
        return """
                INSERT INTO platform.feature_flag
                    (id, flag_code, flag_kind, scope_kind, platform_code,
                     state, status, created_at, updated_at)
                VALUES ('%s', '%s', 'OPERATIONAL', 'PLATFORM', 'OZON',
                        'DISABLED', 'ACTIVE', now(), now())
                """.formatted(flagId, flagCode);
    }
}
