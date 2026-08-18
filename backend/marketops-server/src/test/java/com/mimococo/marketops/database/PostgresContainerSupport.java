package com.mimococo.marketops.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Starts PostgreSQL configured exactly as the workstation stack configures it.
 *
 * <p>The container mounts the same initialisation script the compose file
 * mounts, rather than a copy kept beside the tests. A second copy would let the
 * two drift, and the drift would show up as a privilege that passes in a test
 * and fails on a workstation.
 *
 * <p>Passwords are generated per run. Nothing that could be mistaken for a
 * credential is committed, and no test depends on a shared value.
 */
abstract class PostgresContainerSupport {

    /** Server release the workstation stack runs. */
    static final String IMAGE = "postgres:18.4";

    static final String DATABASE = "marketops";
    static final String SUPERUSER = "postgres";
    static final String MIGRATION_ROLE = "marketops_migration";
    static final String APPLICATION_ROLE = "marketops_app";

    /** The schemas the earliest migration creates. */
    static final List<String> FOUNDATION_SCHEMAS =
            List.of("iam", "platform", "raw", "staging", "core", "ledger", "mart", "ops");

    /** SQLSTATE PostgreSQL raises when a schema already exists. */
    static final String DUPLICATE_SCHEMA = "42P06";

    /** SQLSTATE PostgreSQL raises for a unique-constraint violation. */
    static final String UNIQUE_VIOLATION = "23505";

    /** SQLSTATE PostgreSQL raises for a foreign-key violation. */
    static final String FOREIGN_KEY_VIOLATION = "23503";

    /** SQLSTATE PostgreSQL raises for a check-constraint violation. */
    static final String CHECK_VIOLATION = "23514";

    /** SQLSTATE PostgreSQL raises for an exclusion-constraint violation. */
    static final String EXCLUSION_VIOLATION = "23P01";

    /** SQLSTATE PostgreSQL raises when a role lacks a privilege. */
    static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** SQLSTATE PostgreSQL raises when a schema cannot be resolved. */
    static final String INVALID_SCHEMA_NAME = "3F000";

    static final String MIGRATION_LOCATION = "classpath:db/migration";

    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String APPLICATION_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer SHARED = create();

    static {
        SHARED.start();
    }

    /** The container every test in this run shares. */
    static PostgreSQLContainer shared() {
        return SHARED;
    }

    /**
     * Build a container initialised by the workstation scripts.
     *
     * <p>Each call produces an independent server, which is what a test needs
     * when it has to observe a database in a state the shared one must never be
     * left in.
     */
    static PostgreSQLContainer create() {
        Path initDirectory = repositoryRoot().resolve("infra/compose/postgres-init");
        return new PostgreSQLContainer(DockerImageName.parse(IMAGE))
                .withDatabaseName(DATABASE)
                .withUsername(SUPERUSER)
                .withPassword(UUID.randomUUID().toString())
                .withEnv("MARKETOPS_DB_MIGRATION_PASSWORD", migrationPassword())
                .withEnv("MARKETOPS_DB_APP_PASSWORD", applicationPassword())
                .withCopyFileToContainer(
                        MountableFile.forHostPath(initDirectory.resolve("01-init-roles.sh"), 0755),
                        "/docker-entrypoint-initdb.d/01-init-roles.sh")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(initDirectory.resolve("sql/01-roles.sql"), 0644),
                        "/docker-entrypoint-initdb.d/sql/01-roles.sql");
    }

    static String migrationPassword() {
        return MIGRATION_PASSWORD;
    }

    static String applicationPassword() {
        return APPLICATION_PASSWORD;
    }

    /** Open a connection as the role that owns the schemas. */
    static Connection asMigrationRole(PostgreSQLContainer container) throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(), MIGRATION_ROLE, migrationPassword());
    }

    /** Open a connection as the role the application runs as. */
    static Connection asApplicationRole(PostgreSQLContainer container) throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(), APPLICATION_ROLE, applicationPassword());
    }

    /** Open a connection as the cluster superuser. */
    static Connection asSuperuser(PostgreSQLContainer container) throws SQLException {
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** Build the migration runner the application uses, pointed at {@code container}. */
    static Flyway migrator(PostgreSQLContainer container) {
        return Flyway.configure()
                .dataSource(container.getJdbcUrl(), MIGRATION_ROLE, migrationPassword())
                .locations(MIGRATION_LOCATION)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .cleanDisabled(true)
                .load();
    }

    /** Return the single value produced by {@code sql}. */
    static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                return null;
            }
            return rows.getString(1);
        }
    }

    /** Return the boolean produced by {@code sql}. */
    static boolean singleBoolean(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new SQLException("the boolean query returned no row");
            }
            return rows.getBoolean(1);
        }
    }

    /** Return the number produced by a counting query. */
    static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                return 0L;
            }
            long value = rows.getLong(1);
            return rows.wasNull() ? 0L : value;
        }
    }

    /**
     * Walk upwards to the repository root.
     *
     * <p>The build runs from the module directory, and the initialisation scripts
     * belong to the workstation stack rather than to the module, so the path is
     * resolved from a marker instead of from a fixed number of parent steps.
     */
    static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("bootstrap-manifest.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "the repository root could not be located from " + Path.of("").toAbsolutePath());
    }

    /** Report whether {@code throwable} or any cause carries {@code sqlState}. */
    static boolean carriesSqlState(Throwable throwable, String sqlState) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException
                    && sqlState.equals(sqlException.getSQLState())) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
        }
        return false;
    }
}
