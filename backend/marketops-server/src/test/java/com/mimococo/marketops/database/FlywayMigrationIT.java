package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Establishes what the approved migration set produces, and what the earliest
 * migration does when the database is not the empty one it expects.
 *
 * <p>The negative case is the reason the migration refuses to tolerate an
 * existing schema, so it is asserted in full rather than described.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlywayMigrationIT extends PostgresContainerSupport {

    /** The approved migration files in their application order. */
    private static final List<String> APPROVED_MIGRATIONS = List.of(
            "V0001__create_foundation_schemas.sql",
            "V0002__enable_btree_gist_extension.sql",
            "V0003__create_metadata_audit_event.sql",
            "V0004__create_core_organization_metadata.sql",
            "V0005__create_iam_access_metadata.sql",
            "V0006__create_platform_registry_metadata.sql",
            "V0007__create_ingestion_control_plane_authority.sql",
            "V0008__attach_control_epoch_triggers.sql",
            "V0009__create_control_boundary_kinds_and_decision_evidence.sql",
            "V0010__create_ingestion_run_checkpoint_and_raw_evidence.sql");

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @Test
    @Order(0)
    @DisplayName("TC-DB-100 the container and running server are exactly PostgreSQL 18.4")
    void serverReleaseIsExactlyPinned() throws SQLException {
        assertThat(container.getDockerImageName()).isEqualTo(IMAGE);
        try (Connection connection = asSuperuser(container)) {
            assertThat(single(connection, "SHOW server_version_num")).isEqualTo("180004");
            assertThat(single(connection, "SHOW server_version")).startsWith("18.4");
        }
    }

    @Test
    @Order(1)
    @DisplayName("TC-DB-101 the eight foundation schemas exist")
    void foundationSchemasExist() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> present = strings(connection,
                    "SELECT nspname FROM pg_namespace WHERE nspname IN "
                            + quotedFoundationSchemas() + " ORDER BY nspname");

            assertThat(present)
                    .containsExactlyInAnyOrderElementsOf(FOUNDATION_SCHEMAS);
        }
    }

    @Test
    @Order(2)
    @DisplayName("TC-DB-102 every foundation schema belongs to the migrating role")
    void foundationSchemasAreOwnedByTheMigratingRole() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> owners = strings(connection,
                    "SELECT n.nspname || '=' || r.rolname FROM pg_namespace n "
                            + "JOIN pg_roles r ON r.oid = n.nspowner "
                            + "WHERE n.nspname IN " + quotedFoundationSchemas()
                            + " ORDER BY n.nspname");

            assertThat(owners).hasSize(FOUNDATION_SCHEMAS.size());
            assertThat(owners).allSatisfy(entry ->
                    Assertions.assertThat(entry).endsWith("=" + MIGRATION_ROLE));
        }
    }

    @Test
    @Order(3)
    @DisplayName("TC-DB-110 the approved tables exist, and nothing else does")
    void exactlyTheMetadataTablesExist() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> tables = strings(connection,
                    "SELECT schemaname || '.' || tablename FROM pg_tables "
                            + "WHERE schemaname IN " + quotedFoundationSchemas()
                            + " ORDER BY 1");

            assertThat(tables).containsExactly(
                    "core.fulfillment_mode",
                    "core.legal_entity",
                    "core.marketplace_account",
                    "core.marketplace_platform",
                    "core.organization",
                    "core.store",
                    "core.store_fulfillment_declaration",
                    "core.store_warehouse_link",
                    "core.warehouse",
                    "iam.permission_kind",
                    "iam.service_account",
                    "iam.service_account_allowed_source",
                    "iam.service_account_scope_grant",
                    "ops.authorization_decision_evidence",
                    "ops.ingestion_checkpoint",
                    "ops.ingestion_run",
                    "ops.metadata_audit_event",
                    "platform.capability_subject_status",
                    "platform.capability_verification_event",
                    "platform.control_boundary_kind",
                    "platform.control_epoch",
                    "platform.control_epoch_membership_guard",
                    "platform.control_route_inventory",
                    "platform.credential_metadata",
                    "platform.credential_purpose",
                    "platform.credential_store_scope",
                    "platform.feature_flag",
                    "platform.ingestion_job",
                    "platform.platform_capability",
                    "platform.platform_endpoint",
                    "platform.platform_permission_requirement",
                    "raw.raw_acquisition_observation",
                    "raw.raw_content",
                    "raw.raw_logical_unit");
        }
    }

    @Test
    @Order(3)
    @DisplayName("TC-DB-118 the reference seeds are the approved rows and nothing more")
    void referenceSeedsAreExact() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            assertThat(strings(connection,
                    "SELECT code FROM core.marketplace_platform ORDER BY code"))
                    .containsExactly("OZON", "WILDBERRIES");
            assertThat(strings(connection,
                    "SELECT code FROM core.fulfillment_mode ORDER BY code"))
                    .containsExactly("MARKETPLACE_FULFILLED", "SELLER_FULFILLED", "UNKNOWN");
            assertThat(strings(connection,
                    "SELECT code FROM iam.permission_kind ORDER BY code"))
                    .containsExactly("ADS", "CREDENTIAL_ADMIN", "FINANCE", "READ", "WRITE");
            assertThat(strings(connection,
                    "SELECT code FROM platform.credential_purpose ORDER BY code"))
                    .containsExactly(
                            "ADS_WRITE", "FINANCE", "INVENTORY_WRITE", "PRICE_WRITE", "READ");
            assertThat(strings(connection,
                    "SELECT kind FROM platform.control_boundary_kind ORDER BY ordinal"))
                    .containsExactly(
                            "SERVICE_ACCOUNT_EXPIRY",
                            "SELECTED_SCOPE_GRANT_END",
                            "FUTURE_SCOPE_GRANT_START",
                            "SELECTED_CREDENTIAL_EXPIRY",
                            "FUTURE_CREDENTIAL_START",
                            "STORE_SCOPE_BOUNDARY");
            // Every platform carries its membership guard from the migration
            // that created the platform, so the serialization point exists
            // before any job can reference it.
            assertThat(strings(connection,
                    "SELECT platform_code FROM platform.control_epoch_membership_guard"
                            + " WHERE guard_kind = 'PLATFORM_JOB_SET' ORDER BY platform_code"))
                    .containsExactly("OZON", "WILDBERRIES");
            assertThat(single(connection,
                    "SELECT extname FROM pg_extension WHERE extname = 'btree_gist'"))
                    .isEqualTo("btree_gist");
        }
    }

    @Test
    @Order(4)
    @DisplayName("TC-DB-111 the migration history holds the approved set in order")
    void historyHoldsTheApprovedSet() throws SQLException {
        try (Connection connection = asMigrationRole(container)) {
            List<String> applied = strings(connection,
                    "SELECT script FROM public.flyway_schema_history "
                            + "WHERE type = 'SQL' ORDER BY installed_rank");

            assertThat(applied).containsExactly(APPROVED_MIGRATIONS.toArray(String[]::new));
            assertThat(count(connection,
                    "SELECT count(*) FROM public.flyway_schema_history WHERE success = false"))
                    .isZero();
        }
    }

    @Test
    @Order(5)
    @DisplayName("TC-DB-112 migrating an up-to-date database applies nothing")
    void repeatedMigrationAppliesNothing() throws SQLException {
        long before;
        try (Connection connection = asMigrationRole(container)) {
            before = count(connection, "SELECT count(*) FROM public.flyway_schema_history");
        }

        MigrateResult result = migrator(container).migrate();

        assertThat(result.migrationsExecuted).isZero();
        try (Connection connection = asMigrationRole(container)) {
            assertThat(count(connection, "SELECT count(*) FROM public.flyway_schema_history"))
                    .isEqualTo(before);
        }
    }

    @Test
    @Order(6)
    @DisplayName("TC-DB-113 the source tree carries exactly the approved migrations")
    void exactlyTheApprovedMigrationsAreDeclared() throws Exception {
        Path migrations = repositoryRoot()
                .resolve("backend/marketops-server/src/main/resources/db/migration");

        try (var entries = Files.list(migrations)) {
            List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();

            assertThat(names).containsExactly(APPROVED_MIGRATIONS.toArray(String[]::new));
        }
    }

    /**
     * TC-DB-103 — a database that already carries a foundation schema.
     *
     * <p>The case runs against a server of its own, because it has to leave the
     * database in a state no other test may observe. Twelve observations are made
     * in order, so a failure identifies which guarantee broke rather than only
     * that the migration behaved unexpectedly.
     */
    @Test
    @Order(7)
    @DisplayName("TC-DB-103 a pre-existing schema fails the migration and leaves nothing behind")
    void contaminatedDatabaseFailsAndRollsBack() throws Exception {
        try (PostgreSQLContainer contaminated = create()) {
            contaminated.start();

            // 1 — the roles the initialisation script creates are present.
            try (Connection connection = asSuperuser(contaminated)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_roles WHERE rolname IN ('"
                                + MIGRATION_ROLE + "','" + APPLICATION_ROLE + "')"))
                        .isEqualTo(2);
            }

            // 2 — no foundation schema exists yet.
            try (Connection connection = asSuperuser(contaminated)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_namespace WHERE nspname IN "
                                + quotedFoundationSchemas()))
                        .isZero();
            }

            // 3 — something other than the migration creates one of them.
            try (Connection connection = asSuperuser(contaminated);
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA iam");
            }

            // 4 — and it belongs to whatever created it, not to the migrating role.
            try (Connection connection = asSuperuser(contaminated)) {
                assertThat(schemaOwner(connection, "iam")).isEqualTo(contaminated.getUsername());
            }

            // 5 — the migration refuses to run against that database.
            Flyway flyway = migrator(contaminated);
            Throwable failure = Assertions.catchThrowable(flyway::migrate);
            assertThat(failure).isNotNull();

            // 6 — and the reason is that the schema already exists.
            assertThat(carriesSqlState(failure, DUPLICATE_SCHEMA))
                    .as("the failure must be a duplicate schema, not an unrelated error")
                    .isTrue();

            try (Connection connection = asSuperuser(contaminated)) {
                // 7 — the schemas the migration would have created are absent, so the
                // statements that ran before the failure were rolled back.
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_namespace WHERE nspname IN "
                                + quotedFoundationSchemasExcept("iam")))
                        .isZero();

                // 8 — the pre-existing schema is untouched, including its owner.
                assertThat(schemaOwner(connection, "iam")).isEqualTo(contaminated.getUsername());

                // 9 — the history table exists, because Flyway creates it before it
                // runs anything.
                assertThat(single(connection, "SELECT to_regclass('public.flyway_schema_history')"))
                        .isNotNull();

                // 10 — it holds no record of the attempt. PostgreSQL applies the
                // migration inside a transaction, so the failure rolled the history
                // insert back together with the schema creation.
                assertThat(count(connection,
                        "SELECT count(*) FROM public.flyway_schema_history WHERE type = 'SQL'"))
                        .isZero();

                // 11 — and in particular there is no failed row to repair. A recovery
                // procedure that expects one would wait for something that never
                // appears on this database.
                assertThat(count(connection,
                        "SELECT count(*) FROM public.flyway_schema_history WHERE success = false"))
                        .isZero();
            }

            // 12 — once the contamination is removed the same migration succeeds,
            // which shows the refusal was about the database and not about the file.
            try (Connection connection = asSuperuser(contaminated);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA iam");
            }
            MigrateResult recovered = migrator(contaminated).migrate();
            assertThat(recovered.migrationsExecuted).isEqualTo(APPROVED_MIGRATIONS.size());
            try (Connection connection = asSuperuser(contaminated)) {
                assertThat(count(connection,
                        "SELECT count(*) FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner "
                                + "WHERE n.nspname IN " + quotedFoundationSchemas()
                                + " AND r.rolname = '" + MIGRATION_ROLE + "'"))
                        .isEqualTo(FOUNDATION_SCHEMAS.size());
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("TC-DB-114 destroying the schema is not an available operation")
    void cleanIsRefused() {
        assertThatThrownBy(() -> migrator(container).clean())
                .as("no path in this project may drop a schema")
                .isInstanceOf(Exception.class);
    }

    private static String schemaOwner(Connection connection, String schema) throws SQLException {
        return single(connection,
                "SELECT r.rolname FROM pg_namespace n JOIN pg_roles r ON r.oid = n.nspowner "
                        + "WHERE n.nspname = '" + schema + "'");
    }

    private static String quotedFoundationSchemas() {
        return quoted(FOUNDATION_SCHEMAS);
    }

    private static String quotedFoundationSchemasExcept(String excluded) {
        return quoted(FOUNDATION_SCHEMAS.stream().filter(name -> !name.equals(excluded)).toList());
    }

    private static String quoted(List<String> names) {
        return names.stream()
                .map(name -> "'" + name + "'")
                .reduce((left, right) -> left + "," + right)
                .map(joined -> "(" + joined + ")")
                .orElseThrow();
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
