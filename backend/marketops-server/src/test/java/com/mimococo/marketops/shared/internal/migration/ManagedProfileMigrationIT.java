package com.mimococo.marketops.shared.internal.migration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** PostgreSQL 17 emulation of the accepted managed profile; no provider is contacted. */
class ManagedProfileMigrationIT {
    private static final String IMAGE = "postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3";
    private static final PostgreSQLContainer DATABASE = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketops").withUsername("bootstrap_admin");
    private static final String MIGRATION_PASSWORD = UUID.randomUUID() + "'migration";
    private static final String APPLICATION_PASSWORD = UUID.randomUUID() + "'application";
    private static DriverManagerDataSource managed;
    private static DriverManagerDataSource standard;
    private static DriverManagerDataSource admin;

    @BeforeAll
    static void prepareCluster() throws Exception {
        DATABASE.start();
        admin = dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        var jdbc = new JdbcTemplate(admin);
        createRuntimeRoles(jdbc, MIGRATION_PASSWORD, APPLICATION_PASSWORD);
        jdbc.execute("CREATE EXTENSION btree_gist WITH SCHEMA public");
        jdbc.execute("CREATE EXTENSION pgcrypto WITH SCHEMA public");
        jdbc.execute("ALTER DATABASE marketops OWNER TO marketops_migration");
        jdbc.execute("REVOKE ALL ON DATABASE marketops FROM PUBLIC");
        jdbc.execute("GRANT CONNECT,CREATE ON DATABASE marketops TO marketops_migration");
        jdbc.execute("GRANT CONNECT ON DATABASE marketops TO marketops_app");
        jdbc.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        jdbc.execute("CREATE DATABASE marketops_standard OWNER marketops_migration");
        jdbc.execute("REVOKE ALL ON DATABASE marketops_standard FROM PUBLIC");
        jdbc.execute("GRANT CONNECT,CREATE ON DATABASE marketops_standard TO marketops_migration");
        new JdbcTemplate(dataSource(databaseUrl("marketops_standard"), DATABASE.getUsername(), DATABASE.getPassword()))
                .execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        managed = dataSource(DATABASE.getJdbcUrl(), "marketops_migration", MIGRATION_PASSWORD);
        standard = dataSource(databaseUrl("marketops_standard"), "marketops_migration", MIGRATION_PASSWORD);
    }

    @AfterAll
    static void stopCluster() { DATABASE.stop(); }

    @Test
    void pg17StandardAndManagedProfilesHaveCanonicalHistoryAndEquivalentApplicationSchemas() throws Exception {
        var standardResult = ManagedMigrationRunner.migrate(standard);
        assertThat(standardResult.schemaVersion()).isEqualTo("0052");

        // Missing provider extension is rejected after V0001 and before V0003.
        new JdbcTemplate(admin).execute("DROP EXTENSION pgcrypto");
        var missingEvidence = Files.createTempDirectory("managed-v0002-missing-").resolve("evidence.json");
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed, attestation(), missingEvidence, "ABSENT",
                Instant.parse("2026-08-28T00:00:00Z"), "managed-negative-missing"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new JdbcTemplate(managed).queryForObject("SELECT to_regclass('audit.metadata_event') IS NULL",Boolean.class)).isTrue();
        new JdbcTemplate(admin).execute("CREATE EXTENSION pgcrypto WITH SCHEMA public");

        new JdbcTemplate(admin).execute("DROP EXTENSION btree_gist");
        new JdbcTemplate(admin).execute("CREATE EXTENSION btree_gist WITH SCHEMA public VERSION '1.6'");
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed,attestation(),missingEvidence,"ABSENT",
                Instant.parse("2026-08-28T00:00:01Z"),"managed-negative-version"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("extension identity");
        assertThat(attempt(missingEvidence.getParent(),"managed-negative-version").get("extensions"))
                .isEqualTo(Map.of("btree_gist","1.6","pgcrypto","1.3"));
        new JdbcTemplate(admin).execute("DROP EXTENSION btree_gist");
        new JdbcTemplate(admin).execute("CREATE EXTENSION btree_gist WITH SCHEMA public");

        // A provider extension in the wrong schema is equally unusable.
        new JdbcTemplate(admin).execute("CREATE SCHEMA extension_drift");
        new JdbcTemplate(admin).execute("ALTER EXTENSION pgcrypto SET SCHEMA extension_drift");
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed, attestation(), missingEvidence, "ABSENT",
                Instant.parse("2026-08-28T00:00:01Z"), "managed-negative-schema"))
                .isInstanceOf(IllegalStateException.class);
        new JdbcTemplate(admin).execute("ALTER EXTENSION pgcrypto SET SCHEMA public");

        // Wrong ownership is refused even when the name, version and schema match.
        new JdbcTemplate(admin).execute("DROP EXTENSION pgcrypto");
        new JdbcTemplate(managed).execute("CREATE EXTENSION pgcrypto WITH SCHEMA public");
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed, attestation(), missingEvidence, "ABSENT",
                Instant.parse("2026-08-28T00:00:01Z"), "managed-negative-owner"))
                .isInstanceOf(IllegalStateException.class);
        new JdbcTemplate(managed).execute("DROP EXTENSION pgcrypto");
        new JdbcTemplate(admin).execute("CREATE EXTENSION pgcrypto WITH SCHEMA public");
        installEmulatedProviderDdlBoundary();
        for(var source:java.util.List.of(managed,dataSource(DATABASE.getJdbcUrl(),"marketops_app",APPLICATION_PASSWORD))) {
            for(String ddl:java.util.List.of("CREATE EXTENSION intagg","ALTER EXTENSION pgcrypto UPDATE","DROP EXTENSION pgcrypto")) {
                assertThatThrownBy(() -> new JdbcTemplate(source).execute(ddl))
                        .as("runtime roles cannot create/alter/drop provider extensions")
                        .isInstanceOf(Exception.class).hasStackTraceContaining("provider-managed extension DDL denied");
            }
        }

        var canonicalResources = new ManagedMigrationResources(ManagedMigrationRunner.class.getClassLoader());
        var duplicateResolver = new ManagedV0002Resolver(canonicalResources,attestation(),new ManagedV0002Resolver.Evidence());
        assertThatThrownBy(() -> org.flywaydb.core.Flyway.configure().dataSource(managed)
                .locations("classpath:db/migration").resolvers(duplicateResolver).load().migrate())
                .as("canonical SQL and managed V0002 must never be exposed together")
                .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
                .hasMessageContaining("Found more than one migration with version");

        var directory = Files.createTempDirectory("managed-v0002-evidence-");
        var evidence = directory.resolve("bootstrap.json");
        new JdbcTemplate(admin).execute("ALTER FUNCTION public.digest(bytea,text) SET SCHEMA extension_drift");
        try {
            assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed,attestation(),evidence,"ABSENT",
                    Instant.parse("2026-08-28T00:00:02Z"),"managed-member-schema-drift"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("member schema");
            assertThat(new JdbcTemplate(managed).queryForObject("SELECT to_regclass('audit.metadata_event') IS NULL",Boolean.class)).isTrue();
        } finally {
            new JdbcTemplate(admin).execute("ALTER FUNCTION extension_drift.digest(bytea,text) SET SCHEMA public");
        }
        var managedResult = ManagedMigrationRunner.migrateManaged(managed, attestation(), evidence, "ABSENT",
                Instant.parse("2026-08-28T00:00:02Z"), "managed-positive-profile");
        assertThat(managedResult.schemaVersion()).isEqualTo("0052");
        assertThat(evidence).isRegularFile();
        Path retained = Path.of("target/managed-profile-evidence/bootstrap.json");
        Files.createDirectories(retained.getParent());
        Files.copy(evidence,retained,java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        Map<String,Object> standardV2 = history(standard);
        Map<String,Object> managedV2 = history(managed);
        assertThat(managedV2).containsAllEntriesOf(standardV2);
        assertThat(managedResult.canonicalV0002Checksum()).isEqualTo(standardV2.get("checksum"));
        assertThat(catalogSignature(managed)).isEqualTo(catalogSignature(standard));

        String bootstrapHash = ManagedMigrationEvidence.sha256(evidence);
        assertThat(ManagedMigrationRunner.migrateManaged(managed, attestation(), evidence, bootstrapHash,
                Instant.parse("2026-08-28T00:00:03Z"), "managed-replay-profile").migrationsApplied()).isZero();
        var nextRelease = new ManagedV0002Resolver.Attestation("YANDEX_MANAGED_EMULATION", "test:postgres17:marketops",
                "4".repeat(40), "5".repeat(40), "6".repeat(64),
                ManagedV0002Resolver.YANDEX_EXTENSION_SOURCE_SHA256,
                Map.of("btree_gist","1.7","pgcrypto","1.3"), false, "7".repeat(64));
        assertThat(ManagedMigrationRunner.migrateManaged(managed, nextRelease, evidence, bootstrapHash,
                Instant.parse("2026-08-28T00:00:03Z"), "managed-next-release").migrationsApplied())
                .as("a new release retains the original bootstrap identity").isZero();
        assertThat(ManagedMigrationEvidence.sha256(evidence)).isEqualTo(bootstrapHash);
        var nextAttempt = attempt(directory,"managed-next-release");
        assertThat(nextAttempt).containsEntry("migrationResult","SUCCESS")
                .containsEntry("repositoryCommit",nextRelease.repositoryCommit())
                .containsEntry("providerEvidenceSha256",nextRelease.providerEvidenceSha256())
                .containsEntry("bootstrapEvidenceSha256",bootstrapHash);
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed,attestation(),evidence,"ABSENT",
                Instant.parse("2026-08-28T00:00:03Z"),"managed-missing-hash-pin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hash-pinned");
        String originalEvidence = Files.readString(evidence);
        Files.writeString(evidence,originalEvidence.replace("\"migrationResult\" : \"SUCCESS\"","\"migrationResult\" : \"FAILED\""));
        assertThat(Files.readString(evidence)).isNotEqualTo(originalEvidence);
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed,attestation(),evidence,bootstrapHash,
                Instant.parse("2026-08-28T00:00:03Z"),"managed-mismatched-replay"))
                .isInstanceOf(IllegalStateException.class);
        Files.writeString(evidence,originalEvidence);
        Files.delete(evidence);
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed, attestation(), evidence, bootstrapHash,
                Instant.parse("2026-08-28T00:00:04Z"), "managed-missing-replay"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(attempt(directory,"managed-missing-replay"))
                .containsEntry("migrationResult","FAILED").containsEntry("failureStage","BOOTSTRAP_IDENTITY");

        // Provider restore is emulated by the isolated cluster administrator;
        // neither application nor migration credentials execute restore DDL.
        var dump = DATABASE.execInContainer("pg_dump","-U","bootstrap_admin","-d","marketops","-Fc","-f","/tmp/managed-profile.dump");
        assertThat(dump.getExitCode()).as("isolated pg_dump").isZero();
        var maintenance = new JdbcTemplate(dataSource(databaseUrl("postgres"),DATABASE.getUsername(),DATABASE.getPassword()));
        maintenance.execute("ALTER DATABASE marketops RENAME TO marketops_before_restore");
        maintenance.execute("CREATE DATABASE marketops OWNER marketops_migration");
        maintenance.execute("REVOKE ALL ON DATABASE marketops FROM PUBLIC");
        maintenance.execute("GRANT CONNECT,CREATE ON DATABASE marketops TO marketops_migration");
        maintenance.execute("GRANT CONNECT ON DATABASE marketops TO marketops_app");
        var restore = DATABASE.execInContainer("pg_restore","-U","bootstrap_admin","-d","marketops","/tmp/managed-profile.dump");
        assertThat(restore.getExitCode()).as("isolated pg_restore: %s",restore.getStderr()).isZero();
        Files.writeString(evidence,originalEvidence);
        assertThat(ManagedMigrationRunner.migrateManaged(managed,attestation(),evidence,bootstrapHash,
                Instant.parse("2026-08-28T00:00:05Z"),"managed-restored-profile").migrationsApplied()).isZero();
        assertThat(history(managed)).isEqualTo(standardV2);
        assertThat(catalogSignature(managed)).isEqualTo(catalogSignature(standard));
        retain(directory,"clean-replay-restore");
        retain(missingEvidence.getParent(),"extension-refusals");
        verifyForwardFailureAndPriorReleaseUpgrade(nextRelease);
    }

    private static void verifyForwardFailureAndPriorReleaseUpgrade(ManagedV0002Resolver.Attestation nextRelease) throws Exception {
        var maintenance=new JdbcTemplate(dataSource(databaseUrl("postgres"),DATABASE.getUsername(),DATABASE.getPassword()));
        maintenance.execute("ALTER DATABASE marketops RENAME TO marketops_after_restore");
        maintenance.execute("CREATE DATABASE marketops OWNER marketops_migration");
        var jdbc=new JdbcTemplate(admin);
        jdbc.execute("CREATE EXTENSION btree_gist WITH SCHEMA public");
        jdbc.execute("CREATE EXTENSION pgcrypto WITH SCHEMA public");
        jdbc.execute("REVOKE ALL ON DATABASE marketops FROM PUBLIC");
        jdbc.execute("GRANT CONNECT,CREATE ON DATABASE marketops TO marketops_migration");
        jdbc.execute("GRANT CONNECT ON DATABASE marketops TO marketops_app");
        jdbc.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        installEmulatedProviderDdlBoundary();
        jdbc.execute("""
                CREATE FUNCTION provider_control.deny_forward_migration_fixture() RETURNS event_trigger
                LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
                BEGIN
                  IF session_user='marketops_migration' AND to_regclass('public.flyway_schema_history') IS NOT NULL THEN
                    IF EXISTS (SELECT 1 FROM public.flyway_schema_history WHERE version='0002' AND success) THEN
                      RAISE EXCEPTION 'synthetic forward migration interruption';
                    END IF;
                  END IF;
                END $$
                """);
        jdbc.execute("""
                CREATE EVENT TRIGGER managed_forward_failure_fixture ON ddl_command_start
                WHEN TAG IN ('CREATE TABLE') EXECUTE FUNCTION provider_control.deny_forward_migration_fixture()
                """);

        // Model a prior artifact containing exactly the protected V0001-V0010 bytes.
        // Flyway and the same production runner create its history; no SQL history manipulation.
        var release=Files.createTempDirectory("managed-prior-release-");
        var migrationDirectory=release.resolve("db/migration");
        Files.createDirectories(migrationDirectory);
        try(var paths=Files.list(Path.of("src/main/resources/db/migration"))) {
            for(Path path:paths.filter(p -> p.getFileName().toString().matches("V00(?:0[1-9]|10)__.*[.]sql")).toList()) {
                Files.copy(path,migrationDirectory.resolve(path.getFileName()));
            }
        }
        var evidenceDirectory=Files.createTempDirectory("managed-upgrade-evidence-");
        var evidence=evidenceDirectory.resolve("bootstrap.json");
        try(var loader=new java.net.URLClassLoader(new java.net.URL[]{release.toUri().toURL()},null)) {
            assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed,attestation(),evidence,"ABSENT",
                    Instant.parse("2026-08-28T00:03:00Z"),"managed-forward-failure",loader))
                    .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
            assertThat(evidence).isRegularFile();
            assertThat(history(managed)).isEqualTo(history(standard));
            assertThat(new JdbcTemplate(managed).queryForObject("SELECT max(version) FROM flyway_schema_history",String.class)).isEqualTo("0002");
            assertThat(attempt(evidenceDirectory,"managed-forward-failure"))
                    .containsEntry("migrationResult","FAILED").containsEntry("failureStage","FORWARD_MIGRATION");
            String bootstrapHash=ManagedMigrationEvidence.sha256(evidence);
            jdbc.execute("DROP EVENT TRIGGER managed_forward_failure_fixture");
            var resumed=ManagedMigrationRunner.migrateManaged(managed,attestation(),evidence,bootstrapHash,
                    Instant.parse("2026-08-28T00:03:01Z"),"managed-prior-release-resume",loader);
            assertThat(resumed.schemaVersion()).isEqualTo("0010");
            assertThat(resumed.migrationsApplied()).isEqualTo(8);
            var upgraded=ManagedMigrationRunner.migrateManaged(managed,nextRelease,evidence,bootstrapHash,
                    Instant.parse("2026-08-28T00:03:02Z"),"managed-prior-release-upgrade");
            assertThat(upgraded.schemaVersion()).isEqualTo("0052");
            assertThat(upgraded.migrationsApplied()).isEqualTo(25);
            assertThat(ManagedMigrationEvidence.sha256(evidence)).isEqualTo(bootstrapHash);
            assertThat(catalogSignature(managed)).isEqualTo(catalogSignature(standard));
            assertThat(attempt(evidenceDirectory,"managed-prior-release-upgrade"))
                    .containsEntry("bootstrapEvidenceSha256",bootstrapHash)
                    .containsEntry("repositoryCommit",nextRelease.repositoryCommit());
        }
        retain(evidenceDirectory,"forward-failure-upgrade");
    }

    @Test
    void managedProfileRejectsRoleDriftBeforeMigration() throws Exception {
        var jdbc = new JdbcTemplate(admin);
        for(String[] mutation:new String[][]{
                {"ALTER ROLE marketops_app INHERIT","ALTER ROLE marketops_app NOINHERIT"},
                {"ALTER ROLE marketops_migration INHERIT","ALTER ROLE marketops_migration NOINHERIT"},
                {"ALTER ROLE marketops_migration CREATEROLE","ALTER ROLE marketops_migration NOCREATEROLE"}}) {
            jdbc.execute(mutation[0]);
            var evidence=Files.createTempDirectory("managed-role-drift-").resolve("evidence.json");
            try {
                assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(managed, attestation(), evidence, "ABSENT",
                        Instant.parse("2026-08-28T00:01:00Z"), "managed-role-drift"))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("privilege preflight");
            } finally { jdbc.execute(mutation[1]); }
        }
    }

    @Test
    void pg18ManagedProfileFailsBeforeV0003() throws Exception {
        var image = DockerImageName.parse("postgres:18.4-bookworm@sha256:882236b897e39051d2368c5ccc6cda944904723506b2dfc97f2a8f5bc9afa382")
                .asCompatibleSubstituteFor("postgres");
        try (var database = new PostgreSQLContainer(image).withDatabaseName("marketops").withUsername("bootstrap_admin")) {
            database.start();
            String migrationPassword = UUID.randomUUID().toString();
            String applicationPassword = UUID.randomUUID().toString();
            var root = dataSource(database.getJdbcUrl(),database.getUsername(),database.getPassword());
            var jdbc = new JdbcTemplate(root);
            createRuntimeRoles(jdbc, migrationPassword, applicationPassword);
            jdbc.execute("CREATE EXTENSION btree_gist WITH SCHEMA public");
            jdbc.execute("CREATE EXTENSION pgcrypto WITH SCHEMA public");
            jdbc.execute("ALTER DATABASE marketops OWNER TO marketops_migration");
            jdbc.execute("REVOKE ALL ON DATABASE marketops FROM PUBLIC");
            jdbc.execute("GRANT CONNECT,CREATE ON DATABASE marketops TO marketops_migration");
            jdbc.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
            var source = dataSource(database.getJdbcUrl(),"marketops_migration",migrationPassword);
            var evidence=Files.createTempDirectory("managed-pg18-").resolve("evidence.json");
            assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(source,attestation(),
                    evidence, "ABSENT",
                    Instant.parse("2026-08-28T00:02:00Z"),"managed-pg18-refusal"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(new JdbcTemplate(source).queryForObject("SELECT to_regclass('audit.metadata_event') IS NULL",Boolean.class)).isTrue();
            assertThat(attempt(evidence.getParent(),"managed-pg18-refusal"))
                    .containsEntry("migrationResult","FAILED").containsEntry("postgresqlMajor",18)
                    .containsEntry("expectedPostgresqlMajor",17).containsEntry("attestationState","NOT_ATTESTED");
            retain(evidence.getParent(),"pg18-refusal");
        }
    }

    private static ManagedV0002Resolver.Attestation attestation() {
        return new ManagedV0002Resolver.Attestation("YANDEX_MANAGED_EMULATION", "test:postgres17:marketops",
                "1".repeat(64), "2".repeat(64), "3".repeat(64),
                ManagedV0002Resolver.YANDEX_EXTENSION_SOURCE_SHA256,
                Map.of("btree_gist","1.7","pgcrypto","1.3"), false, "7".repeat(64));
    }

    private static Map<String,Object> attempt(Path directory, String correlation) throws Exception {
        String digest = ManagedMigrationEvidence.digest(correlation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ManagedMigrationEvidence.read(directory.resolve("attempt-"+digest+".result.json"));
    }

    private static void createRuntimeRoles(JdbcTemplate jdbc, String migrationPassword, String applicationPassword) {
        // DDL cannot bind a password directly. PostgreSQL format %L quotes each
        // bound synthetic value as a literal before the local administrator executes it.
        String migrationDdl = jdbc.queryForObject("""
                SELECT format('CREATE ROLE marketops_migration LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', ?::text)
                """, String.class, migrationPassword);
        String applicationDdl = jdbc.queryForObject("""
                SELECT format('CREATE ROLE marketops_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', ?::text)
                """, String.class, applicationPassword);
        jdbc.execute(migrationDdl);
        jdbc.execute(applicationDdl);
    }

    private static void retain(Path directory, String scenario) throws Exception {
        var target=Path.of("target/managed-profile-evidence",scenario);
        Files.createDirectories(target);
        try(var files=Files.list(directory)) {
            for(Path file:files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Files.copy(file,target.resolve(file.getFileName()),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(target.resolve("FIXTURE-ONLY.txt"),
                "Local PG17/18 emulation with synthetic repository/provider identities. Not a Yandex staging run or release approval.\n");
    }

    private static void installEmulatedProviderDdlBoundary() {
        var jdbc = new JdbcTemplate(admin);
        jdbc.execute("CREATE SCHEMA provider_control");
        jdbc.execute("""
                CREATE FUNCTION provider_control.deny_managed_extension_ddl() RETURNS event_trigger
                LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $$
                BEGIN
                  IF session_user IN ('marketops_migration','marketops_app') THEN
                    RAISE EXCEPTION 'provider-managed extension DDL denied';
                  END IF;
                END $$
                """);
        jdbc.execute("""
                CREATE EVENT TRIGGER marketops_managed_extension_ddl_boundary ON ddl_command_start
                WHEN TAG IN ('CREATE EXTENSION','ALTER EXTENSION','DROP EXTENSION')
                EXECUTE FUNCTION provider_control.deny_managed_extension_ddl()
                """);
    }

    private static Map<String,Object> history(DriverManagerDataSource source) {
        return new JdbcTemplate(source).queryForMap("""
                SELECT version,description,type,script,checksum,success
                FROM flyway_schema_history WHERE version='0002'
                """);
    }

    private static String catalogSignature(DriverManagerDataSource source) {
        return new JdbcTemplate(source).queryForObject("""
                SELECT md5(string_agg(item,E'\\n' ORDER BY item)) FROM (
                  SELECT 'column|'||table_schema||'|'||table_name||'|'||column_name||'|'||data_type||'|'||is_nullable item
                    FROM information_schema.columns WHERE table_schema IN ('audit','core','iam','platform','raw','staging','ledger','ops','mart','ai')
                  UNION ALL SELECT 'constraint|'||n.nspname||'|'||c.relname||'|'||x.conname||'|'||pg_get_constraintdef(x.oid,true)
                    FROM pg_constraint x JOIN pg_class c ON c.oid=x.conrelid JOIN pg_namespace n ON n.oid=c.relnamespace
                    WHERE n.nspname IN ('audit','core','iam','platform','raw','staging','ledger','ops','mart','ai')
                  UNION ALL SELECT 'index|'||schemaname||'|'||tablename||'|'||indexname||'|'||indexdef
                    FROM pg_indexes WHERE schemaname IN ('audit','core','iam','platform','raw','staging','ledger','ops','mart','ai')
                  UNION ALL SELECT 'routine|'||n.nspname||'|'||p.proname||'|'||pg_get_function_identity_arguments(p.oid)||'|'||pg_get_functiondef(p.oid)||'|'||coalesce(p.proacl::text,'')
                    FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                    WHERE n.nspname IN ('audit','core','iam','platform','raw','staging','ledger','ops','mart','ai')
                  UNION ALL SELECT 'schema|'||nspname||'|'||pg_get_userbyid(nspowner)||'|'||coalesce(nspacl::text,'')
                    FROM pg_namespace WHERE nspname IN ('audit','core','iam','platform','raw','staging','ledger','ops','mart','ai')
                  UNION ALL SELECT 'route|'||row_to_json(route)::text FROM platform.control_route_inventory route
                  UNION ALL SELECT 'grant|'||table_schema||'|'||table_name||'|'||grantee||'|'||privilege_type
                    FROM information_schema.role_table_grants WHERE table_schema IN ('audit','core','iam','platform','raw','staging','ledger','ops','mart','ai')
                ) inventory
                """, String.class);
    }

    private static DriverManagerDataSource dataSource(String url, String user, String password) {
        return new DriverManagerDataSource(url, user, password);
    }

    private static String databaseUrl(String database) {
        return DATABASE.getJdbcUrl().replaceFirst("/marketops([?]|$)", "/" + database + "$1");
    }
}
