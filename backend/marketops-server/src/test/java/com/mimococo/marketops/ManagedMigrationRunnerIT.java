package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.*;

import com.mimococo.marketops.shared.internal.migration.ManagedMigrationRunner;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class ManagedMigrationRunnerIT {
    private static final PostgreSQLContainer DATABASE = TestDatabase.isolatedContainer();
    private static final DriverManagerDataSource MIGRATION = new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.migrationRole(),TestDatabase.migrationPassword());
    private static final DriverManagerDataSource APPLICATION = new DriverManagerDataSource(DATABASE.getJdbcUrl(),TestDatabase.applicationRole(),TestDatabase.applicationPassword());
    private static final JdbcTemplate ADMIN = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(),DATABASE.getUsername(),DATABASE.getPassword()));
    private static ManagedMigrationRunner.Result clean;

    @BeforeAll
    static void cleanInstall() throws SQLException { clean = ManagedMigrationRunner.migrate(MIGRATION); }

    @Test
    void cleanInstallAndReplayValidateAllMigrationsWithoutGivingTheApplicationTheOwningRole() throws Exception {
        assertThat(clean.migrationsApplied()).isEqualTo(35);
        assertThat(clean.schemaVersion()).isEqualTo("0047");
        assertThat(ManagedMigrationRunner.migrate(MIGRATION).migrationsApplied()).isZero();
        assertThatThrownBy(() -> ManagedMigrationRunner.migrate(APPLICATION)).isInstanceOf(IllegalStateException.class);
        assertThat(new JdbcTemplate(APPLICATION).queryForObject("SELECT has_schema_privilege(current_user,'public','USAGE')",Boolean.class)).isFalse();
    }

    @Test
    void inheritedOrSchemaCreatingApplicationAuthorityPreventsMigration() throws Exception {
        for(String[] statements:new String[][]{
                {"ALTER ROLE marketops_app INHERIT","ALTER ROLE marketops_app NOINHERIT"},
                {"GRANT CREATE ON DATABASE marketops TO marketops_app","REVOKE CREATE ON DATABASE marketops FROM marketops_app"},
                {"GRANT marketops_migration TO marketops_app","REVOKE marketops_migration FROM marketops_app"},
                {"GRANT TEMPORARY ON DATABASE marketops TO marketops_app","REVOKE TEMPORARY ON DATABASE marketops FROM marketops_app"},
                {"GRANT USAGE ON SCHEMA public TO marketops_app","REVOKE USAGE ON SCHEMA public FROM marketops_app"}}) {
            ADMIN.execute(statements[0]);
            try {
                assertThatThrownBy(() -> ManagedMigrationRunner.migrate(MIGRATION)).isInstanceOf(IllegalStateException.class);
            } finally { ADMIN.execute(statements[1]); }
        }
        assertThat(ManagedMigrationRunner.migrate(MIGRATION).migrationsApplied()).isZero();
    }
}
