package com.mimococo.marketops.shared.internal.migration;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import javax.sql.DataSource;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.executor.MigrationExecutor;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.jdbc.Results;

/** Externally satisfied canonical V0002 for the explicit Yandex managed profile. */
final class ManagedV0002Resolver implements MigrationResolver {
    static final String CANONICAL_SHA256 = "438f67ccf3c2f640a1e7a4e325e24fb60d1eb4f363ab545e1e69babba202db16";
    static final String CANONICAL_GIT_BLOB = "bd3e55ea737ffda9d519a931eea1f3cc58b8c522";
    static final int CANONICAL_CHECKSUM = 1291326236;
    static final String YANDEX_EXTENSION_SOURCE_SHA256 = "34e1f92c87f22eb1256f49b2a31c49911cd62bb7c18ce4f7960e43f585584c96";
    private final Attestation attestation;
    private final Evidence capture;
    private final int checksum;

    ManagedV0002Resolver(ManagedMigrationResources resources, Attestation attestation, Evidence capture) {
        this.attestation = attestation;
        this.capture = capture;
        var canonical = resources.canonicalV0002();
        this.checksum = checksum(canonical);
        if (!sha256(canonical).equals(CANONICAL_SHA256) || checksum != CANONICAL_CHECKSUM) {
            throw new IllegalStateException("Canonical V0002 identity mismatch");
        }
    }

    int canonicalChecksum() { return checksum; }

    void attest(DataSource source) throws SQLException {
        try (var connection = source.getConnection()) { verify(connection,attestation,capture); }
    }

    @Override
    public Collection<ResolvedMigration> resolveMigrations(Context context) {
        return List.of(new ManagedMigration(checksum, new AttestingExecutor(attestation, capture)));
    }

    record Attestation(String profile, String environmentReference, String repositoryCommit,
                       String repositoryTree, String providerEvidenceSha256,
                       String providerDocumentSha256, Map<String,String> extensions,
                       boolean providerControlPlaneApplied, String artifactSha256) {
        Attestation {
            if (!Set.of("YANDEX_MANAGED", "YANDEX_MANAGED_EMULATION").contains(profile)
                    || environmentReference == null || !environmentReference.matches("[A-Za-z0-9][A-Za-z0-9:._/-]{5,199}")
                    || !hex(repositoryCommit) || !hex(repositoryTree)
                    || providerEvidenceSha256 == null || !providerEvidenceSha256.matches("[0-9a-f]{64}")
                    || artifactSha256 == null || !artifactSha256.matches("[0-9a-f]{64}")
                    || !YANDEX_EXTENSION_SOURCE_SHA256.equals(providerDocumentSha256)
                    || !Map.of("btree_gist", "1.7", "pgcrypto", "1.3").equals(extensions)
                    || (profile.equals("YANDEX_MANAGED") && !providerControlPlaneApplied)) {
                throw new IllegalArgumentException("Closed managed V0002 attestation required");
            }
            extensions = Map.copyOf(extensions);
        }
        private static boolean hex(String value) { return value != null && value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"); }
    }

    static final class Evidence {
        private boolean attested;
        private Integer serverMajor;
        private String databaseUrlSha256;
        private String databaseIdentity;
        private Map<String,String> extensions = Map.of();
        private Map<String,Map<String,String>> extensionFacts = Map.of();
        private Map<String,Boolean> roleAssertions = Map.of();
        boolean attested() { return attested; }
        Integer serverMajor() { return serverMajor; }
        String databaseUrlSha256() { return databaseUrlSha256; }
        String databaseIdentity() { return databaseIdentity; }
        Map<String,String> extensions() { return extensions; }
        Map<String,Map<String,String>> extensionFacts() { return extensionFacts; }
        Map<String,Boolean> roleAssertions() { return roleAssertions; }
    }

    private record ManagedMigration(int checksum, MigrationExecutor executor) implements ResolvedMigration {
        @Override public MigrationVersion getVersion() { return MigrationVersion.fromVersion("0002"); }
        @Override public String getDescription() { return "enable btree gist extension"; }
        @Override public String getScript() { return "V0002__enable_btree_gist_extension.sql"; }
        @Override public Integer getChecksum() { return checksum; }
        @Override public CoreMigrationType getType() { return CoreMigrationType.SQL; }
        @Override public String getPhysicalLocation() { return "classpath:" + ManagedMigrationResources.V0002; }
        @Override public MigrationExecutor getExecutor() { return executor; }
        @Override public boolean checksumMatches(Integer appliedChecksum) { return Integer.valueOf(checksum).equals(appliedChecksum); }
        @Override public boolean checksumMatchesWithoutBeingIdentical(Integer appliedChecksum) { return false; }
    }

    private record AttestingExecutor(Attestation attestation, Evidence capture) implements MigrationExecutor {
        @Override public List<Results> execute(org.flywaydb.core.api.executor.Context context) throws SQLException {
            verify(context.getConnection(), attestation, capture);
            return List.of(Results.EMPTY_RESULTS);
        }
        @Override public boolean canExecuteInTransaction() { return true; }
        @Override public boolean shouldExecute() { return true; }
    }

    private static void verify(Connection connection, Attestation attestation, Evidence capture) throws SQLException {
        String url = connection.getMetaData().getURL();
        try { capture.databaseUrlSha256 = ManagedMigrationEvidence.digest(url.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception unavailable) { throw new IllegalStateException("Database identity digest unavailable", unavailable); }
        if (attestation.profile().equals("YANDEX_MANAGED")
                && !url.matches("jdbc:postgresql://c-[a-z0-9]{20}[.]rw[.]mdb[.]yandexcloud[.]net:6432/marketops.*")) {
            throw new IllegalStateException("Managed profile requires the intended private Yandex database identity");
        }
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(10);
            try (var rows = statement.executeQuery("SELECT current_setting('server_version_num')::integer/10000")) {
                if (!rows.next()) throw new IllegalStateException("Managed server version unavailable");
                capture.serverMajor = rows.getInt(1);
                if (capture.serverMajor != 17) throw new IllegalStateException("Managed server major refused");
            }
            try (var rows = statement.executeQuery("""
                    SELECT current_user='marketops_migration'
                       AND (SELECT count(*)=2 AND bool_and(NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole
                            AND NOT rolreplication AND NOT rolbypassrls)
                            FROM pg_roles WHERE rolname IN ('marketops_migration','marketops_app'))
                       AND NOT pg_has_role('marketops_app','marketops_migration','MEMBER')
                       AND NOT has_database_privilege('marketops_app',current_database(),'CREATE')
                       AND NOT has_schema_privilege('marketops_app','public','CREATE')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException("Managed role or server attestation refused");
            }
            try (var rows = statement.executeQuery("""
                    SELECT extension.extname,extension.extversion,namespace.nspname,owner.rolname
                    FROM pg_extension extension
                    JOIN pg_namespace namespace ON namespace.oid=extension.extnamespace
                    JOIN pg_roles owner ON owner.oid=extension.extowner
                    WHERE extension.extname IN ('btree_gist','pgcrypto') ORDER BY extension.extname
                    """)) {
                var found = new java.util.LinkedHashMap<String,String>();
                var facts = new java.util.LinkedHashMap<String,Map<String,String>>();
                boolean schemaAndOwnerMatch = true;
                while (rows.next()) {
                    if (!rows.getString(3).equals("public") || Set.of("marketops_app", "marketops_migration").contains(rows.getString(4))) {
                        schemaAndOwnerMatch = false;
                    }
                    found.put(rows.getString(1), rows.getString(2));
                    facts.put(rows.getString(1),Map.of("version",rows.getString(2),"schema",rows.getString(3),"owner",rows.getString(4)));
                }
                capture.extensions = Map.copyOf(found);
                capture.extensionFacts = Map.copyOf(facts);
                if (!schemaAndOwnerMatch) throw new IllegalStateException("Managed extension schema or ownership refused");
                if (!found.equals(attestation.extensions())) throw new IllegalStateException("Managed extension identity refused");
            }
            try (var rows = statement.executeQuery("SELECT current_database()")) {
                if (!rows.next() || !rows.getString(1).equals("marketops")) throw new IllegalStateException("Managed database identity refused");
                capture.databaseIdentity = attestation.environmentReference();
            }
            // extnamespace alone is insufficient: an individual member function/type can
            // be moved while pg_extension still reports the expected public namespace.
            try (var rows = statement.executeQuery("""
                    SELECT count(*) > 0 AND bool_and(object.schema IS NOT DISTINCT FROM 'public')
                    FROM pg_depend dependency JOIN pg_extension extension
                      ON extension.oid=dependency.refobjid AND dependency.refclassid='pg_extension'::regclass
                    CROSS JOIN LATERAL pg_identify_object(dependency.classid,dependency.objid,dependency.objsubid) object
                    WHERE dependency.deptype='e' AND extension.extname IN ('btree_gist','pgcrypto')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException("Managed extension member schema refused");
            }
            try (var rows = statement.executeQuery("""
                    SELECT bool_and(NOT pg_has_role('marketops_app',extowner,'MEMBER')
                                    AND NOT pg_has_role('marketops_migration',extowner,'MEMBER'))
                    FROM pg_extension WHERE extname IN ('btree_gist','pgcrypto')
                    """)) {
                if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException("Managed extension owner membership refused");
            }
            if (attestation.profile().equals("YANDEX_MANAGED_EMULATION")) {
                try (var rows = statement.executeQuery("""
                        SELECT count(*)=1 AND bool_and(evtenabled='O') FROM pg_event_trigger
                        WHERE evtname='marketops_managed_extension_ddl_boundary'
                        """)) {
                    if (!rows.next() || !rows.getBoolean(1)) throw new IllegalStateException("Managed SQL DDL boundary absent");
                }
            }
        }
        capture.roleAssertions = Map.of(
                "serverMajorIs17",true,"currentUserIsMigration",true,"runtimeRolesAreNonPrivileged",true,
                "applicationCannotCreateDatabaseObjects",true,"applicationIsNotMigrationMember",true,
                "managedExtensionsNotOwnedByRuntimeRoles",true,"providerSqlExtensionDdlDenied",
                attestation.profile().equals("YANDEX_MANAGED") ? attestation.providerControlPlaneApplied() : true,
                "extensionMembersInSecureSchema",true,"runtimeCannotAssumeExtensionOwner",true);
        capture.attested = true;
    }

    private static int checksum(LoadableResource resource) {
        try (var lines = new BufferedReader(resource.read(), 4096)) {
            CRC32 crc = new CRC32();
            String line = lines.readLine();
            if (line != null && line.startsWith("\uFEFF")) line = line.substring(1);
            while (line != null) {
                crc.update(line.getBytes(StandardCharsets.UTF_8));
                line = lines.readLine();
            }
            return (int)crc.getValue();
        } catch (Exception error) {
            throw new IllegalStateException("Canonical V0002 checksum unavailable", error);
        }
    }

    private static String sha256(LoadableResource resource) {
        try (var reader = resource.read()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            char[] chars = new char[4096];
            int read;
            while ((read = reader.read(chars)) != -1) digest.update(new String(chars, 0, read).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception error) {
            throw new IllegalStateException("Canonical V0002 digest unavailable", error);
        }
    }
}
