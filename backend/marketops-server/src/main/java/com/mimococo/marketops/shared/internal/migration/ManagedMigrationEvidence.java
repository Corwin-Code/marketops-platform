package com.mimococo.marketops.shared.internal.migration;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable bootstrap custody and a separate, durable record for every migration attempt. */
final class ManagedMigrationEvidence implements AutoCloseable {
    static final String ABSENT = "ABSENT";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final Path bootstrap;
    private final Path result;
    private final Path attested;
    private final String expectedBootstrapSha256;
    private final Map<String,Object> document;
    private boolean finished;

    ManagedMigrationEvidence(Path bootstrap, String expectedBootstrapSha256,
                             ManagedV0002Resolver.Attestation authority, Instant timestamp, String correlation) throws Exception {
        if (bootstrap == null || timestamp == null || correlation == null
                || !correlation.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{5,199}")
                || !(ABSENT.equals(expectedBootstrapSha256) || isSha256(expectedBootstrapSha256))) {
            throw new IllegalArgumentException("Closed managed evidence references required");
        }
        this.bootstrap = bootstrap.toAbsolutePath();
        this.expectedBootstrapSha256 = expectedBootstrapSha256;
        String attempt = "attempt-" + digest(correlation.getBytes(StandardCharsets.UTF_8));
        this.result = this.bootstrap.resolveSibling(attempt + ".result.json");
        this.attested = this.bootstrap.resolveSibling(attempt + ".attested.json");
        this.document = base(authority);
        document.put("artifactKind", "MIGRATION_ATTEMPT");
        document.put("expectedBootstrapSha256", expectedBootstrapSha256);
        document.put("timestamp", timestamp.toString());
        document.put("correlation", correlation);
        document.put("failureStage", "CREDENTIAL_PREPARATION");
        document.put("migrationResult", "STARTED");
        document.put("flywayHistoryBefore", Map.of("state", "NOT_OBSERVED"));
        document.put("flywayHistoryAfter", Map.of("state", "NOT_OBSERVED"));
        document.put("extensionFacts", Map.of());
        document.put("roleAssertions", Map.of());
        var start = this.bootstrap.resolveSibling(attempt + ".started.json");
        // This must succeed before any credential read or database mutation. Reusing a
        // correlation is refused, including after a process dies without a final record.
        publish(start, document);
        document.put("startedEvidenceSha256", sha256(start));
    }

    void stage(String value) { document.put("failureStage", value); }
    void before(History history) { document.put("flywayHistoryBefore", history.document()); }
    void after(History history) { document.put("flywayHistoryAfter", history.document()); }

    void capture(ManagedV0002Resolver.Evidence facts) {
        document.put("attestationState", facts.attested() ? "ATTESTED" : "NOT_ATTESTED");
        document.put("postgresqlMajor", facts.serverMajor() == null ? "NOT_OBSERVED" : facts.serverMajor());
        document.put("extensions", facts.extensions());
        document.put("databaseUrlSha256", facts.databaseUrlSha256() == null ? "NOT_OBSERVED" : facts.databaseUrlSha256());
        document.put("extensionFacts", facts.extensionFacts());
        document.put("roleAssertions", facts.roleAssertions());
        document.put("databaseIdentity", facts.databaseIdentity() == null ? "NOT_ATTESTED" : facts.databaseIdentity());
    }

    void attest(ManagedV0002Resolver.Evidence facts) throws Exception {
        capture(facts);
        document.put("migrationResult", "ATTESTED");
        publish(attested, document);
        document.put("attestedEvidenceSha256", sha256(attested));
    }

    /** The release pins the old bootstrap bytes; its commit and provider evidence may be older. */
    boolean validateBootstrap(History history, ManagedV0002Resolver.Attestation authority,
                              ManagedV0002Resolver.Evidence facts) throws Exception {
        boolean exists = Files.exists(bootstrap, LinkOption.NOFOLLOW_LINKS);
        if (history.v0002State() == 0) {
            if (exists || !ABSENT.equals(expectedBootstrapSha256)) {
                throw new IllegalStateException("Bootstrap evidence exists without canonical V0002 history");
            }
            return false;
        }
        if (history.v0002State() != 1 || !isSha256(expectedBootstrapSha256)
                || !Files.isRegularFile(bootstrap, LinkOption.NOFOLLOW_LINKS) || Files.size(bootstrap) > 131072
                || !sha256(bootstrap).equals(expectedBootstrapSha256)) {
            throw new IllegalStateException("Existing V0002 requires hash-pinned managed bootstrap evidence");
        }
        var prior = read(bootstrap);
        var identity = base(authority);
        for (String key : List.of("schemaVersion", "serviceProfile", "environmentReference", "expectedPostgresqlMajor",
                "canonicalV0002Path", "canonicalV0002GitBlob", "canonicalV0002Sha256", "canonicalV0002FlywayChecksum",
                "resolvedVersion", "resolvedDescription", "executorMode", "requiredExtensions", "providerDocumentSha256",
                "providerControlPlaneApplied")) {
            if (!identity.get(key).equals(prior.get(key))) throw new IllegalStateException("Managed bootstrap identity mismatch");
        }
        if (!"MANAGED_V0002_BOOTSTRAP".equals(prior.get("artifactKind"))
                || !"SUCCESS".equals(prior.get("migrationResult"))
                || !Integer.valueOf(17).equals(prior.get("postgresqlMajor"))
                || !"ATTESTED".equals(prior.get("attestationState"))
                || !authority.extensions().equals(prior.get("extensions"))
                || !authority.environmentReference().equals(prior.get("databaseIdentity"))
                || !ABSENT.equals(prior.get("expectedBootstrapSha256"))
                || !isSha256(prior.get("startedEvidenceSha256")) || !isSha256(prior.get("attestedEvidenceSha256"))
                || !facts.extensionFacts().equals(prior.get("extensionFacts"))
                || !facts.roleAssertions().equals(prior.get("roleAssertions"))
                || !isGitIdentity(prior.get("repositoryCommit")) || !isGitIdentity(prior.get("repositoryTree"))
                || !isSha256(prior.get("artifactSha256")) || !isSha256(prior.get("databaseUrlSha256"))
                || !isSha256(prior.get("providerEvidenceSha256"))
                || !(prior.get("flywayHistoryAfter") instanceof Map<?,?> after)
                || !"CANONICAL".equals(after.get("v0002"))) {
            throw new IllegalStateException("Managed bootstrap attestation mismatch");
        }
        if (!(prior.get("correlation") instanceof String correlation)
                || !correlation.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{5,199}")
                || !(prior.get("timestamp") instanceof String timestamp)) {
            throw new IllegalStateException("Managed bootstrap provenance missing");
        }
        Instant.parse(timestamp);
        document.put("bootstrapEvidenceSha256", expectedBootstrapSha256);
        return true;
    }

    void bootstrap(History history, ManagedV0002Resolver.Evidence facts) throws Exception {
        if (history.v0002State() != 1 || !facts.attested()) throw new IllegalStateException("Canonical V0002 not attested");
        capture(facts);
        var initial = new LinkedHashMap<>(document);
        initial.put("artifactKind", "MANAGED_V0002_BOOTSTRAP");
        initial.put("migrationResult", "SUCCESS");
        initial.put("failureStage", "NONE");
        initial.put("flywayHistoryAfter", history.document());
        publish(bootstrap, initial);
        document.put("bootstrapEvidenceSha256", sha256(bootstrap));
    }

    String success(int applied, String version) throws Exception {
        document.put("migrationsApplied", applied);
        document.put("schemaVersionAfter", version);
        document.put("failureStage", "NONE");
        document.put("migrationResult", "SUCCESS");
        publish(result, document);
        finished = true;
        return sha256(result);
    }

    @Override public void close() throws Exception {
        if (!finished) {
            document.put("migrationResult", "FAILED");
            // Never retain driver exceptions, connection URLs, SQL values or credentials.
            publish(result, document);
            finished = true;
        }
    }

    private static Map<String,Object> base(ManagedV0002Resolver.Attestation authority) {
        var record = new LinkedHashMap<String,Object>();
        record.put("schemaVersion", "2.0");
        record.put("serviceProfile", authority.profile());
        record.put("environmentReference", authority.environmentReference());
        record.put("repositoryCommit", authority.repositoryCommit());
        record.put("repositoryTree", authority.repositoryTree());
        record.put("artifactSha256", authority.artifactSha256());
        record.put("databaseUrlSha256", "NOT_OBSERVED");
        record.put("expectedPostgresqlMajor", 17);
        record.put("postgresqlMajor", "NOT_OBSERVED");
        record.put("attestationState", "NOT_ATTESTED");
        record.put("canonicalV0002Path", ManagedMigrationResources.V0002);
        record.put("canonicalV0002GitBlob", ManagedV0002Resolver.CANONICAL_GIT_BLOB);
        record.put("canonicalV0002Sha256", ManagedV0002Resolver.CANONICAL_SHA256);
        record.put("canonicalV0002FlywayChecksum", ManagedV0002Resolver.CANONICAL_CHECKSUM);
        record.put("resolvedVersion", "0002");
        record.put("resolvedDescription", "enable btree gist extension");
        record.put("executorMode", "EXTERNALLY_SATISFIED_PROVIDER_EXTENSION");
        record.put("requiredExtensions", authority.extensions());
        record.put("extensions", Map.of());
        record.put("providerEvidenceSha256", authority.providerEvidenceSha256());
        record.put("providerDocumentSha256", authority.providerDocumentSha256());
        record.put("providerControlPlaneApplied", authority.providerControlPlaneApplied());
        return record;
    }

    /** Only migration metadata is read; installed-by identities and business data are excluded. */
    static History history(DataSource source) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var exists = statement.executeQuery("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")) {
                if (!exists.next() || !exists.getBoolean(1)) return new History(Map.of("state", ABSENT, "v0002", ABSENT), 0);
            }
            var migrations = new ArrayList<Map<String,Object>>();
            int v2Count = 0;
            boolean canonical = false;
            try (var rows = statement.executeQuery("SELECT version,description,type,script,checksum,success FROM public.flyway_schema_history ORDER BY installed_rank")) {
                while (rows.next()) {
                    if (migrations.size() >= 1024) throw new IllegalStateException("Migration history exceeds evidence budget");
                    var row = new LinkedHashMap<String,Object>();
                    row.put("version", rows.getString(1)); row.put("description", rows.getString(2));
                    row.put("type", rows.getString(3)); row.put("script", rows.getString(4));
                    row.put("checksum", rows.getObject(5)); row.put("success", rows.getBoolean(6));
                    migrations.add(row);
                    if (rows.getString(1) != null && rows.getString(1).matches("0*2")) {
                        v2Count++;
                        canonical = "0002".equals(rows.getString(1)) && "enable btree gist extension".equals(rows.getString(2))
                                && "SQL".equals(rows.getString(3)) && "V0002__enable_btree_gist_extension.sql".equals(rows.getString(4))
                                && Integer.valueOf(ManagedV0002Resolver.CANONICAL_CHECKSUM).equals(rows.getObject(5)) && rows.getBoolean(6);
                    }
                }
            }
            int state = v2Count == 0 ? 0 : v2Count == 1 && canonical ? 1 : -1;
            return new History(Map.of("state", "PRESENT", "migrations", migrations,
                    "v0002", state == 0 ? ABSENT : state == 1 ? "CANONICAL" : "CONFLICT"), state);
        }
    }

    static History unavailable() { return new History(Map.of("state", "UNAVAILABLE", "v0002", "UNKNOWN"), -1); }
    record History(Map<String,Object> document, int v0002State) { }

    @SuppressWarnings("unchecked")
    static Map<String,Object> read(Path path) throws Exception {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return JSON.readValue(input, Map.class);
        }
    }

    static String sha256(Path path) throws Exception {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static boolean isSha256(Object value) { return value instanceof String s && s.matches("[0-9a-f]{64}"); }
    private static boolean isGitIdentity(Object value) { return value instanceof String s && s.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"); }

    private static void publish(Path path, Map<String,Object> evidence) throws Exception {
        Path parent = path.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("Managed evidence directory must already exist");
        }
        byte[] bytes = (JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n").getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(parent, ".managed-evidence-", ".tmp");
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var content = ByteBuffer.wrap(bytes);
                while (content.hasRemaining()) output.write(content);
                output.force(true);
            }
            Files.createLink(path.toAbsolutePath(), temporary);
            try (var directory = FileChannel.open(parent, StandardOpenOption.READ)) { directory.force(true); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
