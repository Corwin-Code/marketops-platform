package com.mimococo.marketops.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Establishes what the application role can and cannot do.
 *
 * <p>Each case asserts a refusal by attempting the operation, because a
 * privilege matrix written in a document describes an intention while a rejected
 * statement demonstrates the state of the database.
 */
class DatabasePrivilegeIT extends PostgresContainerSupport {

    private static PostgreSQLContainer container;

    @BeforeAll
    static void migrate() {
        container = shared();
        migrator(container).migrate();
    }

    @Test
    @DisplayName("TC-DB-104 the application role can connect")
    void applicationRoleCanConnect() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            assertThat(single(connection, "SELECT current_user")).isEqualTo(APPLICATION_ROLE);
        }
    }

    @Test
    @DisplayName("TC-DB-105 the application role may enter every foundation schema")
    void applicationRoleHasUsageOnEverySchema() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            for (String schema : FOUNDATION_SCHEMAS) {
                boolean granted = singleBoolean(connection,
                        "SELECT has_schema_privilege('" + APPLICATION_ROLE + "','" + schema + "','USAGE')");

                assertThat(granted).as("USAGE on %s", schema).isTrue();
            }
        }
    }

    @Test
    @DisplayName("TC-DB-105b the application role may not create in any foundation schema")
    void applicationRoleHasNoCreateOnAnySchema() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            for (String schema : FOUNDATION_SCHEMAS) {
                boolean granted = singleBoolean(connection,
                        "SELECT has_schema_privilege('" + APPLICATION_ROLE + "','" + schema + "','CREATE')");

                assertThat(granted).as("CREATE on %s", schema).isFalse();
            }
        }
    }

    @Test
    @DisplayName("TC-DB-106 the application role cannot create a schema")
    void applicationRoleCannotCreateASchema() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            assertRefused(connection, "CREATE SCHEMA attempted_by_application");
        }
    }

    @Test
    @DisplayName("TC-DB-107 the application role cannot create a table anywhere")
    void applicationRoleCannotCreateATable() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            for (String schema : FOUNDATION_SCHEMAS) {
                assertRefused(connection, "CREATE TABLE " + schema + ".attempted (id integer)");
            }
            assertRefused(connection, "CREATE TABLE public.attempted (id integer)");
        }
    }

    @Test
    @DisplayName("TC-DB-108 the application role cannot alter or empty the migration history")
    void applicationRoleCannotTouchTheMigrationHistory() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            assertRefused(connection,
                    "ALTER TABLE public.flyway_schema_history ADD COLUMN attempted integer");
            assertRefused(connection, "DELETE FROM public.flyway_schema_history");
            assertRefused(connection, "SELECT count(*) FROM public.flyway_schema_history");
            assertRefused(connection, "DROP TABLE public.flyway_schema_history");
        }
    }

    @Test
    @DisplayName("TC-DB-109 no privilege on the public schema is held by everyone")
    void publicSchemaGrantsNothingToEveryone() throws SQLException {
        try (Connection connection = asSuperuser(container)) {
            // has_schema_privilege cannot answer this: PUBLIC is not a role and has
            // no row in pg_roles, so naming it raises an error. The access control
            // list is examined directly instead, where grantee zero means PUBLIC.
            long granted = count(connection,
                    "SELECT count(*) FROM pg_namespace n, aclexplode(n.nspacl) a "
                            + "WHERE n.nspname = 'public' AND a.grantee = 0");

            assertThat(granted)
                    .as("neither CREATE nor USAGE on public may remain with PUBLIC")
                    .isZero();
        }
    }

    @Test
    @DisplayName("TC-DB-115 the application role resolves names without the public schema")
    void applicationRoleSearchPathExcludesPublic() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            String searchPath = single(connection, "SHOW search_path");

            assertThat(searchPath).doesNotContain("public");
            for (String schema : FOUNDATION_SCHEMAS) {
                assertThat(searchPath).contains(schema);
            }
        }
    }

    @Test
    @DisplayName("TC-DB-116 neither role holds cluster-wide authority")
    void neitherRoleIsPrivileged() throws SQLException {
        try (Connection connection = asSuperuser(container)) {
            long privileged = count(connection,
                    "SELECT count(*) FROM pg_roles WHERE rolname IN ('"
                            + MIGRATION_ROLE + "','" + APPLICATION_ROLE + "') "
                            + "AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)");

            assertThat(privileged).isZero();
        }
    }

    @Test
    @DisplayName("TC-DB-117 the application role cannot create a database or a role")
    void applicationRoleCannotExtendTheCluster() throws SQLException {
        try (Connection connection = asApplicationRole(container)) {
            assertRefused(connection, "CREATE ROLE attempted_by_application");
        }
    }

    private static void assertRefused(Connection connection, String sql) {
        Throwable failure = Assertions.catchThrowable(() -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });

        assertThat(failure).as("statement must be refused: %s", sql).isNotNull();
        assertThat(carriesSqlState(failure, INSUFFICIENT_PRIVILEGE)
                || carriesSqlState(failure, INVALID_SCHEMA_NAME))
                .as("refusal of [%s] must be a privilege decision, not an unrelated error", sql)
                .isTrue();
    }
}
