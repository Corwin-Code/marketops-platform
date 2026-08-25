package com.mimococo.marketops.database;

import static com.mimococo.marketops.database.IngestionControlPlaneFixture.ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.EPOCH_NOT_MONOTONIC;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.JOB;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.ORGANIZATION;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.SERVICE_ACCOUNT;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.STORE;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.epochOf;
import static com.mimococo.marketops.database.IngestionControlPlaneFixture.strings;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The epoch trigger mechanism as database facts: the server behaviour that
 * forces one statement-level trigger per event, the exact correspondence
 * between the declared route inventory and the installed triggers, and the
 * advancement semantics an acquisition's staleness check consumes.
 *
 * <p>The control epoch only guards a grant if every table that can change a
 * control fact advances the scopes a statement reached, from inside the
 * database, whichever client wrote. A missing trigger produces no error, only
 * a grant that survives a revocation, so the correspondence between the
 * inventory and the catalog is asserted as a set equality rather than sampled.
 * The behavioural cases run inside rolled-back transactions and the fixture
 * graph is rebuilt before each case, so the shared container stays usable by
 * every other class in the run.
 */
class ControlEpochTriggerIT extends PostgresContainerSupport {

    private static final UUID SECOND_ORGANIZATION =
            UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID SECOND_LEGAL_ENTITY =
            UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID WILDBERRIES_CAPABILITY =
            UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID OZON_CAPABILITY =
            UUID.fromString("00000000-0000-0000-0000-000000000402");

    /** An identifier that matches no row in any fixture table. */
    private static final String ABSENT_ROW = "00000000-0000-0000-0000-00000000dead";

    /** A trigger body that does nothing, for probing declaration rules. */
    private static final String PROBE_FUNCTION = """
            CREATE FUNCTION platform.control_epoch_probe() RETURNS trigger
            LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$
            """;

    /**
     * Matches the generated epoch trigger names and nothing else: monotonicity
     * guards end in _bu and constraint triggers in _ar, so neither is counted.
     */
    private static final String EPOCH_TRIGGER_PATTERN = "'_control_epoch_a[iud]$'";

    /**
     * Correlated existence test for the insert-event epoch trigger of the
     * route-inventory row aliased {@code inventory} in the enclosing query.
     */
    private static final String EPOCH_TRIGGER_EXISTS = """
            SELECT 1
              FROM pg_trigger AS trg
              JOIN pg_class AS rel ON rel.oid = trg.tgrelid
              JOIN pg_namespace AS nsp ON nsp.oid = rel.relnamespace
             WHERE NOT trg.tgisinternal
               AND nsp.nspname = inventory.schema_name
               AND rel.relname = inventory.table_name
               AND trg.tgname = inventory.table_name || '_control_epoch_ai'
            """;

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

    /**
     * The server rule that dictates the whole trigger shape: one trigger
     * covering several events may not request transition relations, so a table
     * that needs them must carry one trigger per event. If an upgrade ever
     * relaxed this rule, the generated three-trigger shape would still be
     * correct but no longer forced, and that change should be noticed here
     * rather than discovered mid-incident.
     */
    @Test
    @DisplayName("TC-CTRL-100 PostgreSQL 18 refuses a multi-event trigger that requests transition relations")
    void multiEventTransitionTriggerIsRefused() throws SQLException {
        assertTriggerShapeRefused("""
                CREATE TRIGGER control_epoch_probe_multi
                    AFTER INSERT OR UPDATE OR DELETE ON platform.ingestion_job
                    REFERENCING NEW TABLE AS n OLD TABLE AS o
                    FOR EACH STATEMENT
                    EXECUTE FUNCTION platform.control_epoch_probe()
                """,
                "more than one event");
    }

    /**
     * An INSERT has no old rows and a DELETE has no new ones, and the server
     * refuses a declaration that pretends otherwise. This is why the generated
     * insert trigger declares only NEW TABLE and the delete trigger only OLD
     * TABLE; declaring the illegal relation would fail at creation, not at the
     * first write.
     */
    @Test
    @DisplayName("TC-CTRL-101 each transition relation is legal only for the events that have it")
    void transitionRelationsAreEventSpecific() throws SQLException {
        assertTriggerShapeRefused("""
                CREATE TRIGGER control_epoch_probe_insert
                    AFTER INSERT ON platform.ingestion_job
                    REFERENCING OLD TABLE AS o
                    FOR EACH STATEMENT
                    EXECUTE FUNCTION platform.control_epoch_probe()
                """,
                "OLD TABLE");
        assertTriggerShapeRefused("""
                CREATE TRIGGER control_epoch_probe_delete
                    AFTER DELETE ON platform.ingestion_job
                    REFERENCING NEW TABLE AS n
                    FOR EACH STATEMENT
                    EXECUTE FUNCTION platform.control_epoch_probe()
                """,
                "NEW TABLE");
    }

    /**
     * A row-level trigger sees one row at a time and cannot order the epoch
     * writes of a bulk statement, so every epoch trigger must be statement
     * level, and the two rules above force exactly three of them per table.
     * The representative table pins the transition-relation declarations to
     * the only combination each event legally has.
     */
    @Test
    @DisplayName("TC-CTRL-102 every routed table carries exactly three statement-level epoch triggers")
    void routedTablesCarryThreeStatementTriggers() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            long routedTables = count(connection, """
                    SELECT count(*) FROM platform.control_route_inventory
                     WHERE route_kind <> 'NO_ROUTE'
                    """);
            assertThat(routedTables).isPositive();

            assertThat(count(connection, """
                    SELECT count(*) FROM pg_trigger
                     WHERE NOT tgisinternal AND tgname ~ %s
                    """.formatted(EPOCH_TRIGGER_PATTERN)))
                    .as("one epoch trigger per event for each routed table")
                    .isEqualTo(routedTables * 3);

            assertThat(count(connection, """
                    SELECT count(*) FROM pg_trigger
                     WHERE NOT tgisinternal AND tgname ~ %s
                       AND (tgtype & 1) <> 0
                    """.formatted(EPOCH_TRIGGER_PATTERN)))
                    .as("every epoch trigger fires per statement, never per row")
                    .isZero();

            assertThat(strings(connection, """
                    SELECT trg.tgname || '=' || coalesce(trg.tgoldtable::text, '<none>')
                           || '/' || coalesce(trg.tgnewtable::text, '<none>')
                      FROM pg_trigger AS trg
                      JOIN pg_class AS rel ON rel.oid = trg.tgrelid
                      JOIN pg_namespace AS nsp ON nsp.oid = rel.relnamespace
                     WHERE nsp.nspname = 'core' AND rel.relname = 'store'
                       AND NOT trg.tgisinternal AND trg.tgname ~ %s
                     ORDER BY trg.tgname
                    """.formatted(EPOCH_TRIGGER_PATTERN)))
                    .as("each event declares only the transition relations it has")
                    .containsExactly(
                            "store_control_epoch_ad=o/<none>",
                            "store_control_epoch_ai=<none>/n",
                            "store_control_epoch_au=o/n");
        }
    }

    @Test
    @DisplayName("TC-CTRL-103 the route inventory and the installed triggers describe the same set")
    void inventoryAndTriggersAgree() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            assertThat(strings(connection, """
                    SELECT inventory.schema_name || '.' || inventory.table_name
                      FROM platform.control_route_inventory AS inventory
                     WHERE inventory.route_kind <> 'NO_ROUTE'
                       AND NOT EXISTS (%s)
                     ORDER BY 1
                    """.formatted(EPOCH_TRIGGER_EXISTS)))
                    .as("every routed table carries its epoch trigger")
                    .isEmpty();

            assertThat(strings(connection, """
                    SELECT inventory.schema_name || '.' || inventory.table_name
                      FROM platform.control_route_inventory AS inventory
                     WHERE inventory.route_kind = 'NO_ROUTE'
                       AND EXISTS (%s)
                     ORDER BY 1
                    """.formatted(EPOCH_TRIGGER_EXISTS)))
                    .as("no unrouted table carries an epoch trigger")
                    .isEmpty();

            // The membership guard is the serialization point that fan-outs
            // and job creation lock; routing it would make every advancement
            // recurse into the authority it depends on.
            assertThat(single(connection, """
                    SELECT route_kind FROM platform.control_route_inventory
                     WHERE schema_name = 'platform'
                       AND table_name = 'control_epoch_membership_guard'
                    """))
                    .isEqualTo("NO_ROUTE");
            assertThat(count(connection, """
                    SELECT count(*)
                      FROM pg_trigger AS trg
                      JOIN pg_class AS rel ON rel.oid = trg.tgrelid
                      JOIN pg_namespace AS nsp ON nsp.oid = rel.relnamespace
                     WHERE NOT trg.tgisinternal
                       AND nsp.nspname = 'platform'
                       AND rel.relname = 'control_epoch_membership_guard'
                       AND trg.tgname ~ %s
                    """.formatted(EPOCH_TRIGGER_PATTERN)))
                    .isZero();
        }
    }

    /**
     * The inventory is only a proof of completeness if it is total. A table
     * created without an inventory row is indistinguishable from a table
     * nobody thought about, so a failure here means a migration added a table
     * without deciding its routing.
     */
    @Test
    @DisplayName("TC-CTRL-104 every foundation table appears exactly once in the route inventory")
    void inventoryIsTotal() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            var present = strings(connection, """
                    SELECT schemaname || '.' || tablename FROM pg_tables
                     WHERE schemaname IN %s
                     ORDER BY 1
                    """.formatted(quotedFoundationSchemas()));
            var inventoried = strings(connection, """
                    SELECT schema_name || '.' || table_name
                      FROM platform.control_route_inventory
                     ORDER BY 1
                    """);

            assertThat(present).isNotEmpty();
            // The inventory's primary key forbids a duplicate, so equal ordered
            // lists mean each table appears exactly once.
            assertThat(inventoried).containsExactlyElementsOf(present);
        }
    }

    /**
     * A statement trigger fires even when the statement matched nothing, so
     * this case proves the empty transition relation advances no scope: a
     * write that changed nothing must not invalidate an in-flight grant.
     */
    @Test
    @DisplayName("TC-CTRL-105 a statement that matches no row advances no epoch")
    void emptyStatementAdvancesNothing() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                long before = epochOf(connection, "MARKETPLACE_ACCOUNT", ACCOUNT);
                assertThat(before).isPositive();

                statement.execute("""
                        UPDATE core.store SET display_name = 'unreachable'
                         WHERE id = '%s'
                        """.formatted(ABSENT_ROW));

                assertThat(epochOf(connection, "MARKETPLACE_ACCOUNT", ACCOUNT))
                        .isEqualTo(before);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @DisplayName("TC-CTRL-106 an ordinary update advances exactly the scope that owns the row")
    void ordinaryUpdateAdvancesOwningScopeOnly() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                long accountBefore = epochOf(connection, "MARKETPLACE_ACCOUNT", ACCOUNT);
                long organizationBefore = epochOf(connection, "ORGANIZATION", ORGANIZATION);
                long subjectBefore = epochOf(connection, "SERVICE_ACCOUNT", SERVICE_ACCOUNT);
                assertThat(accountBefore).isPositive();

                statement.execute("""
                        UPDATE core.store SET display_name = 'Acme Store Renamed'
                         WHERE id = '%s'
                        """.formatted(STORE));

                assertThat(epochOf(connection, "MARKETPLACE_ACCOUNT", ACCOUNT))
                        .as("the owning account scope advances exactly once")
                        .isEqualTo(accountBefore + 1);
                assertThat(epochOf(connection, "ORGANIZATION", ORGANIZATION))
                        .as("an unrelated scope stays untouched")
                        .isEqualTo(organizationBefore);
                assertThat(epochOf(connection, "SERVICE_ACCOUNT", SERVICE_ACCOUNT))
                        .as("an unrelated scope stays untouched")
                        .isEqualTo(subjectBefore);
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * An upsert that hits its conflicting row fires both the insert and the
     * update statement triggers, so one statement may advance a scope more
     * than once. The mechanism accepts that, and this case deliberately
     * asserts a lower bound only: a spurious advance can only invalidate an
     * in-flight grant, never validate one, so over-advancing errs on the
     * refusing side and pinning an exact count would forbid the safe
     * behaviour.
     */
    @Test
    @DisplayName("TC-CTRL-107 an upsert may advance a scope more than once, and over-advancing is safe")
    void upsertMayAdvanceMoreThanOnce() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                long before = epochOf(connection, "MARKETPLACE_ACCOUNT", ACCOUNT);
                assertThat(before).isPositive();

                statement.execute("""
                        INSERT INTO core.store
                            (id, organization_id, marketplace_account_id, code,
                             display_name, status, created_at, updated_at)
                        VALUES ('%s', '%s', '%s', 'acme-store', 'Acme Store Upserted',
                                'ACTIVE', now(), now())
                        ON CONFLICT (id) DO UPDATE
                            SET display_name = EXCLUDED.display_name, updated_at = now()
                        """.formatted(STORE, ORGANIZATION, ACCOUNT));

                assertThat(epochOf(connection, "MARKETPLACE_ACCOUNT", ACCOUNT))
                        .isGreaterThanOrEqualTo(before + 1);
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * The update trigger reads the old and the new transition relation, and
     * this is the case that justifies it: when a row changes owner, a grant
     * standing on the old scope is as invalidated as one standing on the new,
     * and advancing only the destination would leave the departure holding a
     * frozen epoch under changed facts.
     */
    @Test
    @DisplayName("TC-CTRL-108 moving a row between scopes advances both the old and the new scope")
    void movingARowAdvancesBothScopes() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO core.organization
                            (id, code, display_name, status, created_at, updated_at)
                        VALUES ('%s', 'acme-second', 'Acme Second', 'ACTIVE', now(), now())
                        """.formatted(SECOND_ORGANIZATION));
                statement.execute("""
                        INSERT INTO core.legal_entity
                            (id, organization_id, code, display_name, status,
                             created_at, updated_at)
                        VALUES ('%s', '%s', 'acme-ru-second', 'Acme RU Second',
                                'ACTIVE', now(), now())
                        """.formatted(SECOND_LEGAL_ENTITY, ORGANIZATION));

                long firstBefore = epochOf(connection, "ORGANIZATION", ORGANIZATION);
                long secondBefore = epochOf(connection, "ORGANIZATION", SECOND_ORGANIZATION);
                assertThat(firstBefore).isPositive();
                assertThat(secondBefore).isPositive();

                statement.execute("""
                        UPDATE core.legal_entity SET organization_id = '%s'
                         WHERE id = '%s'
                        """.formatted(SECOND_ORGANIZATION, SECOND_LEGAL_ENTITY));

                assertThat(epochOf(connection, "ORGANIZATION", ORGANIZATION))
                        .as("the scope the row left advances")
                        .isEqualTo(firstBefore + 1);
                assertThat(epochOf(connection, "ORGANIZATION", SECOND_ORGANIZATION))
                        .as("the scope the row joined advances")
                        .isEqualTo(secondBefore + 1);
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * A platform registry fact reaches the jobs of that platform and no
     * others. Fanning wider would let one platform's registry churn invalidate
     * every acquisition in the system, which is exactly the starvation the
     * per-scope epoch partitioning exists to avoid.
     */
    @Test
    @DisplayName("TC-CTRL-109 a platform-wide fan-out reaches only the jobs of that platform")
    void platformFanOutReachesOnlyItsJobs() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                long before = epochOf(connection, "JOB", JOB);
                assertThat(before).isPositive();

                statement.execute(capability(
                        WILDBERRIES_CAPABILITY, "WILDBERRIES", "catalog.read"));
                assertThat(epochOf(connection, "JOB", JOB))
                        .as("a Wildberries fact does not reach an Ozon job")
                        .isEqualTo(before);

                statement.execute(capability(OZON_CAPABILITY, "OZON", "catalog.read"));
                assertThat(epochOf(connection, "JOB", JOB))
                        .as("an Ozon fact reaches the Ozon job")
                        .isEqualTo(before + 1);
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * The acquisition protocol freezes an epoch row with a share lock, and the
     * privilege that buys the lock is column-level UPDATE on updated_at alone.
     * These three statements walk the boundary: the lock is available, the
     * epoch column is unwritable outright, and the one writable column cannot
     * be used to smuggle a write past the monotonicity guard.
     */
    @Test
    @DisplayName("TC-CTRL-110 the application role can take the share lock but cannot write the epoch")
    void applicationRoleCanLockButNotWrite() throws SQLException {
        try (Connection connection = asApplicationRole(container);
             Statement statement = connection.createStatement()) {
            assertThat(count(connection, "SELECT count(*) FROM platform.control_epoch"))
                    .as("the fixture graph created epoch rows to lock")
                    .isPositive();

            Throwable shareLock = Assertions.catchThrowable(() ->
                    statement.execute("SELECT 1 FROM platform.control_epoch FOR SHARE"));
            assertThat(shareLock)
                    .as("the protocol's share lock must be available to the application role")
                    .isNull();
        }

        assertRejected(INSUFFICIENT_PRIVILEGE,
                "UPDATE platform.control_epoch SET epoch = epoch + 1");
        assertRejected(EPOCH_NOT_MONOTONIC,
                "UPDATE platform.control_epoch SET updated_at = now()");
    }

    /**
     * Attempt one trigger declaration in a rolled-back transaction and assert
     * the server refuses it for the expected reason. The probe function and
     * the failed declaration disappear with the rollback, so the shared
     * container is untouched.
     */
    private static void assertTriggerShapeRefused(String createTriggerSql, String expectedFragment)
            throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(PROBE_FUNCTION);
                Throwable failure =
                        Assertions.catchThrowable(() -> statement.execute(createTriggerSql));
                assertThat(failure)
                        .as("the trigger declaration must be refused")
                        .isNotNull()
                        .hasMessageContaining(expectedFragment);
            } finally {
                connection.rollback();
            }
        }
    }

    private static void assertRejected(String sqlState, String sql) throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                Throwable failure = Assertions.catchThrowable(() -> statement.execute(sql));
                assertThat(failure)
                        .as("the statement must be refused with SQLSTATE %s: %s", sqlState, sql)
                        .isNotNull();
                assertThat(carriesSqlState(failure, sqlState))
                        .as("expected SQLSTATE %s from: %s", sqlState, failure)
                        .isTrue();
            } finally {
                connection.rollback();
            }
        }
    }

    private static String capability(UUID id, String platform, String code) {
        return """
                INSERT INTO platform.platform_capability
                    (id, platform_code, capability_code, display_name, applies_to,
                     read_write_class, subscription_required, verification_state,
                     owner_label, contract_test_status, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', 'MARKETPLACE_ACCOUNT', 'READ',
                        'UNKNOWN', 'UNKNOWN', 'platform-team', 'NOT_IMPLEMENTED',
                        'ACTIVE', now(), now())
                """.formatted(id, platform, code, code);
    }

    private static String quotedFoundationSchemas() {
        return FOUNDATION_SCHEMAS.stream()
                .map(name -> "'" + name + "'")
                .reduce((left, right) -> left + "," + right)
                .map(joined -> "(" + joined + ")")
                .orElseThrow();
    }
}
