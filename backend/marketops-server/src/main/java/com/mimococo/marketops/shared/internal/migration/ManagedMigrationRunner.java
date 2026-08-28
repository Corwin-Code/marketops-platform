package com.mimococo.marketops.shared.internal.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One-shot deployment tool, never a Spring bean or a workload endpoint.
 *
 * <p>The separately authorized migration identity checks the approved artifact,
 * verifies the role boundary, migrates, validates and exits. Application and
 * worker processes have neither this identity nor its mounted credential.
 */
public final class ManagedMigrationRunner {
    private static final Logger log = LoggerFactory.getLogger(ManagedMigrationRunner.class);
    private ManagedMigrationRunner() { }

    /** The only successful output contains counts and a version, never connection metadata. */
    public record Result(int migrationsApplied, String schemaVersion) { }

    /** Result of the explicit managed profile, including its separately stored attestation. */
    public record ManagedResult(int migrationsApplied, String schemaVersion, int canonicalV0002Checksum,
                                String evidenceSha256) { }

    /** Migrate with the owning role only after verifying the privilege prerequisites. */
    public static Result migrate(DataSource source) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery("""
                    SELECT current_user='marketops_migration'
                        AND (SELECT count(*)=2 AND bool_and(rolcanlogin AND NOT rolsuper AND NOT rolcreatedb
                            AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit)
                            FROM pg_catalog.pg_roles WHERE rolname IN ('marketops_migration','marketops_app'))
                        AND has_database_privilege('marketops_migration',current_database(),'CREATE')
                        AND NOT has_database_privilege('marketops_app',current_database(),'CREATE')
                        AND NOT pg_has_role('marketops_app','marketops_migration','MEMBER')
                        AND NOT has_database_privilege('marketops_app',current_database(),'TEMPORARY')
                        AND NOT has_schema_privilege('marketops_app','public','USAGE')
                        AND NOT has_schema_privilege('marketops_app','public','CREATE')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException("Migration privilege preflight refused");
            }
        }
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).validateOnMigrate(true).load();
        var result = flyway.migrate();
        flyway.validate();
        var current = flyway.info().current();
        if (current == null || current.getVersion() == null) throw new IllegalStateException("Migration version absent");
        return new Result(result.migrationsExecuted, current.getVersion().getVersion());
    }

    /**
     * Run the accepted Yandex-managed compatibility profile through Flyway's public resolver API.
     * Canonical SQL V0002 is hidden from the SQL resolver only in this method; the custom
     * executor attests the provider-managed extension and Flyway records the canonical identity.
     */
    static ManagedResult migrateManaged(DataSource source, ManagedV0002Resolver.Attestation attestation,
                                        Path evidencePath, String expectedBootstrapSha256,
                                        Instant timestamp, String correlation) throws Exception {
        return migrateManaged(source, attestation, evidencePath, expectedBootstrapSha256, timestamp, correlation,
                ManagedMigrationRunner.class.getClassLoader());
    }

    /** Testable release classpath; the executable entry point always uses its own approved artifact. */
    static ManagedResult migrateManaged(DataSource source, ManagedV0002Resolver.Attestation attestation,
                                        Path evidencePath, String expectedBootstrapSha256,
                                        Instant timestamp, String correlation, ClassLoader releaseLoader) throws Exception {
        try (var evidence = new ManagedMigrationEvidence(evidencePath, expectedBootstrapSha256,
                attestation, timestamp, correlation)) {
            return migrateManaged(source, attestation, evidence, releaseLoader);
        }
    }

    private static ManagedResult migrateManaged(DataSource source, ManagedV0002Resolver.Attestation authority,
                                                 ManagedMigrationEvidence evidence, ClassLoader releaseLoader) throws Exception {
        var capture = new ManagedV0002Resolver.Evidence();
        int applied = 0;
        String version;
        try {
            evidence.stage("HISTORY_PREFLIGHT");
            var before = ManagedMigrationEvidence.history(source);
            evidence.before(before);
            evidence.stage("ROLE_AND_EXTENSION_PREFLIGHT");
            preflight(source);
            var resources = new ManagedMigrationResources(releaseLoader);
            var resolver = new ManagedV0002Resolver(resources, authority, capture);
            resolver.attest(source);
            evidence.stage("BOOTSTRAP_IDENTITY");
            boolean bootstrapped = evidence.validateBootstrap(before, authority, capture);
            evidence.attest(capture);
            if (!bootstrapped) {
                evidence.stage("V0002_BOOTSTRAP");
                // Persist V0002's proof before running later migrations. A later migration
                // failure can then resume normally with the hash-pinned bootstrap intact.
                var bootstrap = managedFlyway(source, resources, resolver).target("0002").load();
                applied += bootstrap.migrate().migrationsExecuted;
                bootstrap.validate();
                evidence.bootstrap(ManagedMigrationEvidence.history(source), capture);
            }
            evidence.stage("FORWARD_MIGRATION");
            var flyway = managedFlyway(source, resources, resolver).load();
            applied += flyway.migrate().migrationsExecuted;
            flyway.validate();
            var current = flyway.info().current();
            if (current == null || current.getVersion() == null) throw new IllegalStateException("Migration version absent");
            version = current.getVersion().getVersion();
        } finally {
            evidence.capture(capture);
            try { evidence.after(ManagedMigrationEvidence.history(source)); }
            catch (Exception unavailable) { evidence.after(ManagedMigrationEvidence.unavailable()); }
        }
        return new ManagedResult(applied, version, ManagedV0002Resolver.CANONICAL_CHECKSUM,
                evidence.success(applied, version));
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration managedFlyway(
            DataSource source, ManagedMigrationResources resources, ManagedV0002Resolver resolver) {
        return Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .resourceProvider(resources).resolvers(resolver)
                .baselineOnMigrate(false).outOfOrder(false).cleanDisabled(true).validateOnMigrate(true);
    }

    private static void preflight(DataSource source) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery("""
                    SELECT current_user='marketops_migration'
                        AND (SELECT count(*)=2 AND bool_and(rolcanlogin AND NOT rolsuper AND NOT rolcreatedb
                            AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit)
                            FROM pg_catalog.pg_roles WHERE rolname IN ('marketops_migration','marketops_app'))
                        AND has_database_privilege('marketops_migration',current_database(),'CREATE')
                        AND NOT has_database_privilege('marketops_app',current_database(),'CREATE')
                        AND NOT pg_has_role('marketops_app','marketops_migration','MEMBER')
                        AND NOT has_database_privilege('marketops_app',current_database(),'TEMPORARY')
                        AND NOT has_schema_privilege('marketops_app','public','USAGE')
                        AND NOT has_schema_privilege('marketops_app','public','CREATE')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException("Migration privilege preflight refused");
            }
        }
    }

    /** Validate the non-secret deployment manifest before reading its credential or connecting. */
    public static Properties readManifest(Path path, Path artifact) throws Exception {
        return readManifest(path, artifact, null);
    }

    /** Package-scoped fixture seam; production always resolves the exact mounted path. */
    static Properties readManifest(Path path, Path artifact, Path providerEvidenceOverride) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8192) {
            throw new IllegalArgumentException("Invalid migration manifest");
        }
        var properties = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("Duplicate migration manifest field");
                return super.put(key, value);
            }
        };
        try (var input = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(input); }
        Set<String> required = Set.of("serviceProfile", "jdbcUrl", "credentialFile", "artifactSha256", "approvalReference",
                "environmentReference", "repositoryCommit", "repositoryTree", "providerEvidenceFile", "providerEvidenceSha256",
                "providerDocumentSha256", "bootstrapEvidenceFile", "expectedBootstrapSha256", "correlation");
        if (!properties.stringPropertyNames().equals(required)) {
            throw new IllegalArgumentException("Closed migration manifest required");
        }
        String jdbc = properties.getProperty("jdbcUrl");
        if (!jdbc.matches("jdbc:postgresql://c-[a-z0-9]{20}[.]rw[.]mdb[.]yandexcloud[.]net:6432/marketops[?]"
                + "sslmode=verify-full&sslrootcert=/opt/marketops/certs/yandex-root[.]crt&targetServerType=primary")) {
            throw new IllegalArgumentException("Verified private database destination required");
        }
        if (!properties.getProperty("serviceProfile").equals("YANDEX_MANAGED")
                || !properties.getProperty("credentialFile").equals("/run/marketops-migration/database-password")
                || !properties.getProperty("approvalReference").matches("[A-Za-z0-9][A-Za-z0-9:._/-]{5,199}")
                || !properties.getProperty("environmentReference").matches("[A-Za-z0-9][A-Za-z0-9:._/-]{5,199}")
                || !properties.getProperty("repositoryCommit").matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                || !properties.getProperty("repositoryTree").matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
                || !properties.getProperty("artifactSha256").matches("[0-9a-f]{64}")
                || !properties.getProperty("providerEvidenceSha256").matches("[0-9a-f]{64}")
                || !properties.getProperty("providerDocumentSha256").equals(ManagedV0002Resolver.YANDEX_EXTENSION_SOURCE_SHA256)
                || !properties.getProperty("providerEvidenceFile").equals("/run/marketops-migration/provider/database.json")
                || !properties.getProperty("bootstrapEvidenceFile").equals("/run/marketops-migration/evidence/managed-bootstrap.json")
                || !(properties.getProperty("expectedBootstrapSha256").equals("ABSENT")
                    || properties.getProperty("expectedBootstrapSha256").matches("[0-9a-f]{64}"))
                || !properties.getProperty("correlation").matches("[A-Za-z0-9][A-Za-z0-9:._/-]{5,199}")) {
            throw new IllegalArgumentException("Invalid migration references");
        }
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Regular approved artifact required");
        }
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(properties.getProperty("artifactSha256"))) {
            throw new IllegalArgumentException("Approved artifact digest mismatch");
        }
        Path providerEvidence = providerEvidenceOverride == null
                ? Path.of(properties.getProperty("providerEvidenceFile")) : providerEvidenceOverride;
        if (!Files.isRegularFile(providerEvidence, LinkOption.NOFOLLOW_LINKS) || Files.size(providerEvidence) > 32768
                || !ManagedMigrationEvidence.sha256(providerEvidence).equals(properties.getProperty("providerEvidenceSha256"))) {
            throw new IllegalArgumentException("Provider evidence identity mismatch");
        }
        var applied = readAppliedProviderEvidence(providerEvidence);
        if (!properties.getProperty("environmentReference").equals(applied.get("environmentReference"))
                || !ManagedMigrationEvidence.digest(jdbc.getBytes(StandardCharsets.UTF_8)).equals(applied.get("databaseUrlSha256"))) {
            throw new IllegalArgumentException("Provider evidence database identity mismatch");
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> readAppliedProviderEvidence(Path path) throws Exception {
        Map<String,Object> evidence = ManagedMigrationEvidence.read(path);
        if (!evidence.keySet().equals(Set.of("schemaVersion","resourceMode","serviceProfile","postgresqlMajor",
                "providerVersion","resourceReference","environmentReference","databaseUrlSha256","extensions","providerDocumentSha256"))
                || !"1.0".equals(evidence.get("schemaVersion")) || !"APPLIED_RESOURCE".equals(evidence.get("resourceMode"))
                || !"YANDEX_MANAGED".equals(evidence.get("serviceProfile")) || !Integer.valueOf(17).equals(evidence.get("postgresqlMajor"))
                || !"yandex-cloud/yandex 0.220.0".equals(evidence.get("providerVersion"))
                || !(evidence.get("resourceReference") instanceof String reference)
                || !reference.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{5,199}")
                || !(evidence.get("environmentReference") instanceof String environment)
                || !environment.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{5,199}")
                || !(evidence.get("databaseUrlSha256") instanceof String destination) || !destination.matches("[0-9a-f]{64}")
                || !Map.of("btree_gist","1.7","pgcrypto","1.3").equals(evidence.get("extensions"))
                || !ManagedV0002Resolver.YANDEX_EXTENSION_SOURCE_SHA256.equals(evidence.get("providerDocumentSha256"))) {
            throw new IllegalArgumentException("Applied provider evidence refused");
        }
        return Map.copyOf(evidence);
    }

    /** Execute only as an explicit deployment step with an Owner-approved manifest. */
    public static void main(String[] arguments) {
        try {
            if (arguments.length != 2 || !arguments[0].equals("--migrate")) throw new IllegalArgumentException("Explicit migration action required");
            var manifest = readManifest(Path.of(arguments[1]), Path.of("/opt/marketops/app.jar"));
            var attestation = new ManagedV0002Resolver.Attestation("YANDEX_MANAGED",
                    manifest.getProperty("environmentReference"), manifest.getProperty("repositoryCommit"),
                    manifest.getProperty("repositoryTree"), manifest.getProperty("providerEvidenceSha256"),
                    manifest.getProperty("providerDocumentSha256"), Map.of("btree_gist","1.7","pgcrypto","1.3"), true,
                    manifest.getProperty("artifactSha256"));
            try (var evidence = new ManagedMigrationEvidence(Path.of(manifest.getProperty("bootstrapEvidenceFile")),
                    manifest.getProperty("expectedBootstrapSha256"), attestation,
                    java.time.Clock.systemUTC().instant(), manifest.getProperty("correlation"))) {
                Path credential = Path.of(manifest.getProperty("credentialFile"));
                char[] value = readCredential(credential);
                var source = new DriverManagerDataSource(manifest.getProperty("jdbcUrl"), "marketops_migration", new String(value));
                try {
                    ManagedResult result = migrateManaged(source, attestation, evidence, ManagedMigrationRunner.class.getClassLoader());
                    log.info("MIGRATION_VALIDATED applied={} version={} evidenceSha256={}",
                            result.migrationsApplied(), result.schemaVersion(), result.evidenceSha256());
                } finally {
                    Arrays.fill(value, '\0');
                    source.setPassword("");
                }
            }
        } catch (Exception refused) {
            // Driver/Flyway messages may contain connection or statement details.
            log.error("MIGRATION_FAILED");
            System.exit(1);
        }
    }

    private static char[] readCredential(Path credential) throws Exception {
        if (!Files.isRegularFile(credential, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid credential mount");
        byte[] bytes;
        try (var input = Files.newInputStream(credential, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(16385); }
        try {
            if (bytes.length < 1 || bytes.length > 16384) throw new IOException("Invalid credential mount");
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().toCharArray();
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
}
