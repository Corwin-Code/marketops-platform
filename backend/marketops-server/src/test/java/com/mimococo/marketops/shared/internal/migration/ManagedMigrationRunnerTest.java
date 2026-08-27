package com.mimococo.marketops.shared.internal.migration;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ManagedMigrationRunnerTest {
    private static final String JDBC = "jdbc:postgresql://c-"+"a".repeat(20)+".rw.mdb.yandexcloud.net:6432/marketops?sslmode=verify-full&sslrootcert=/opt/marketops/certs/yandex-root.crt&targetServerType=primary";
    @TempDir Path directory;

    @Test
    void exactApprovedArtifactAndNonSecretManifestAreRequiredBeforeCredentialAccess() throws Exception {
        Path artifact = directory.resolve("app.jar");
        Files.writeString(artifact,"synthetic-artifact");
        var properties = manifest(artifact);
        Path file = write(properties);
        assertThat(ManagedMigrationRunner.readManifest(file,artifact,providerEvidence())).isEqualTo(properties);
        Files.writeString(artifact,"different-artifact");
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(file,artifact,providerEvidence()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("digest mismatch");
    }

    @ParameterizedTest
    @ValueSource(strings={"extra", "serviceProfile", "jdbcUrl", "credentialFile", "artifactSha256", "approvalReference",
            "environmentReference", "repositoryCommit", "repositoryTree", "providerEvidenceFile", "providerEvidenceSha256",
            "providerDocumentSha256", "bootstrapEvidenceFile", "expectedBootstrapSha256", "correlation"})
    void malformedAndUndeclaredManifestFieldsFailBeforeAnyConnection(String field) throws Exception {
        Path artifact = directory.resolve("app.jar");
        Files.writeString(artifact,"synthetic-artifact");
        var properties=manifest(artifact);
        properties.setProperty(field,"<invalid-reference>");
        Path file=write(properties);
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(file,artifact,providerEvidence())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absentOversizedAndSymlinkedManifestsAreRefused() throws Exception {
        Path artifact=directory.resolve("app.jar"); Files.writeString(artifact,"synthetic-artifact");
        Path missing=directory.resolve("missing");
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(missing,artifact,providerEvidence())).isInstanceOf(IllegalArgumentException.class);
        Path large=directory.resolve("large"); Files.writeString(large,"x".repeat(8193));
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(large,artifact,providerEvidence())).isInstanceOf(IllegalArgumentException.class);
        Path file=write(manifest(artifact)); Path link=directory.resolve("link"); Files.createSymbolicLink(link,file);
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(link,artifact,providerEvidence())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateManifestFieldsAndSymlinkedArtifactsAreRefused() throws Exception {
        Path artifact=directory.resolve("app.jar"); Files.writeString(artifact,"synthetic-artifact");
        var properties=manifest(artifact);
        Path file=write(properties);
        Files.writeString(file,"\napprovalReference=second-approval",java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(file,artifact,providerEvidence()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        Path validFile=write(properties);
        Path link=directory.resolve("linked-artifact"); Files.createSymbolicLink(link,artifact);
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(validFile,link,providerEvidence()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Regular approved artifact");
    }

    @Test
    void providerEvidenceMustDescribeAnAppliedPg17ResourceWithExactExtensions() throws Exception {
        assertThat(ManagedMigrationRunner.readAppliedProviderEvidence(providerEvidence()))
                .containsEntry("resourceMode","APPLIED_RESOURCE");
        String valid=Files.readString(providerEvidence());
        for(String mutation:java.util.List.of(valid.replace("APPLIED_RESOURCE","PLAN_ONLY"),
                valid.replace("\"postgresqlMajor\":17","\"postgresqlMajor\":18"),
                valid.replace("\"1.7\"","\"1.6\""),valid.replace(",\"pgcrypto\":\"1.3\"",""))) {
            assertThat(mutation).isNotEqualTo(valid);
            Path file=directory.resolve("mutated-"+UUID.randomUUID()+".json");
            Files.writeString(file,mutation);
            assertThatThrownBy(() -> ManagedMigrationRunner.readAppliedProviderEvidence(file))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void managedAttestationRejectsWrongExtensionVersionsAndPg18Claims() {
        assertThatThrownBy(() -> new ManagedV0002Resolver.Attestation("YANDEX_MANAGED","synthetic:database",
                "1".repeat(40),"2".repeat(40),"3".repeat(64),ManagedV0002Resolver.YANDEX_EXTENSION_SOURCE_SHA256,
                java.util.Map.of("btree_gist","1.6","pgcrypto","1.3"),true,"7".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void providerEvidenceIsBoundToTheExactManifestDatabaseAndRejectsDuplicateKeys() throws Exception {
        Path artifact=directory.resolve("app.jar"); Files.writeString(artifact,"synthetic-artifact");
        var properties=manifest(artifact);
        properties.setProperty("environmentReference","synthetic:other:environment");
        Path changedEnvironment=write(properties);
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(changedEnvironment,artifact,providerEvidence()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("database identity");
        properties=manifest(artifact);
        properties.setProperty("jdbcUrl",JDBC.replace("c-"+"a".repeat(20),"c-"+"b".repeat(20)));
        Path changedDestination=write(properties);
        assertThatThrownBy(() -> ManagedMigrationRunner.readManifest(changedDestination,artifact,providerEvidence()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("database identity");
        String original=Files.readString(providerEvidence());
        Files.writeString(providerEvidence(),original.replace("\"postgresqlMajor\":17","\"postgresqlMajor\":18,\"postgresqlMajor\":17"));
        assertThatThrownBy(() -> ManagedMigrationRunner.readAppliedProviderEvidence(providerEvidence()))
                .isInstanceOf(Exception.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new ManagedV0002Resolver.Attestation("YANDEX_MANAGED_EMULATION","synthetic:database",
                "1".repeat(40),"2".repeat(40),"3".repeat(40),ManagedV0002Resolver.YANDEX_EXTENSION_SOURCE_SHA256,
                java.util.Map.of("btree_gist","1.7","pgcrypto","1.3"),false,"7".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void managedInventoryIncludesEveryPackagedMigrationExceptCanonicalSqlV0002() throws Exception {
        var resources=new ManagedMigrationResources(getClass().getClassLoader());
        try(var paths=Files.list(Path.of("src/main/resources/db/migration"))) {
            var expected=paths.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".sql") && !n.startsWith("V0002__")).toList();
            assertThat(resources.getResources("V",new String[]{".sql"}).stream().map(r -> r.getFilename()).toList())
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
        assertThat(resources.getResources("R",new String[]{".sql"})).isEmpty();
        assertThat(resources.getResource(ManagedMigrationResources.V0002)).isNull();
        assertThat(resources.getResource("/db/migration/V0001__create_foundation_schemas.sql")).isNotNull();
        assertThat(resources.getResource("not-a-migration.sql")).isNull();
        try (var empty=new java.net.URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},null)) {
            assertThatThrownBy(() -> new ManagedMigrationResources(empty)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void failedDatabaseAttemptKeepsRedactedDurableEvidenceAndNeverOverwritesTheSameCorrelation() throws Exception {
        var source=org.mockito.Mockito.mock(javax.sql.DataSource.class);
        String privateDetail="synthetic-private-driver-detail-"+UUID.randomUUID();
        org.mockito.Mockito.when(source.getConnection()).thenThrow(new java.sql.SQLException(privateDetail));
        var authority=new ManagedV0002Resolver.Attestation("YANDEX_MANAGED_EMULATION","synthetic:database",
                "1".repeat(40),"2".repeat(40),"3".repeat(64),ManagedV0002Resolver.YANDEX_EXTENSION_SOURCE_SHA256,
                java.util.Map.of("btree_gist","1.7","pgcrypto","1.3"),false,"7".repeat(64));
        var bootstrap=directory.resolve("bootstrap.json");
        var timestamp=java.time.Instant.parse("2026-08-28T00:00:00Z");
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(source,authority,bootstrap,"ABSENT",timestamp,"connection-refusal"))
                .isInstanceOf(java.sql.SQLException.class);
        var result=directory.resolve("attempt-"+ManagedMigrationEvidence.digest("connection-refusal".getBytes(java.nio.charset.StandardCharsets.UTF_8))+".result.json");
        assertThat(ManagedMigrationEvidence.read(result)).containsEntry("migrationResult","FAILED")
                .containsEntry("failureStage","HISTORY_PREFLIGHT");
        assertThat(Files.readString(result)).doesNotContain(privateDetail);
        assertThat(bootstrap).doesNotExist();
        String preserved=ManagedMigrationEvidence.sha256(result);
        org.mockito.Mockito.clearInvocations(source);
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(source,authority,bootstrap,"ABSENT",timestamp,"connection-refusal"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        org.mockito.Mockito.verifyNoInteractions(source);
        assertThat(ManagedMigrationEvidence.sha256(result)).isEqualTo(preserved);
        var link=directory.resolve("linked-parent"); Files.createSymbolicLink(link,directory);
        assertThatThrownBy(() -> ManagedMigrationRunner.migrateManaged(source,authority,link.resolve("bootstrap.json"),"ABSENT",timestamp,"symlink-refusal"))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(source);
    }

    private Properties manifest(Path artifact) throws Exception {
        var properties=new Properties();
        properties.setProperty("jdbcUrl",JDBC);
        properties.setProperty("credentialFile","/run/marketops-migration/database-password");
        properties.setProperty("artifactSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
        properties.setProperty("approvalReference","synthetic-approval-reference");
        properties.setProperty("serviceProfile","YANDEX_MANAGED");
        properties.setProperty("environmentReference","synthetic:staging:database");
        properties.setProperty("repositoryCommit","1".repeat(64));
        properties.setProperty("repositoryTree","2".repeat(64));
        properties.setProperty("providerEvidenceFile","/run/marketops-migration/provider/database.json");
        properties.setProperty("providerEvidenceSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(providerEvidence()))));
        properties.setProperty("providerDocumentSha256",ManagedV0002Resolver.YANDEX_EXTENSION_SOURCE_SHA256);
        properties.setProperty("bootstrapEvidenceFile","/run/marketops-migration/evidence/managed-bootstrap.json");
        properties.setProperty("expectedBootstrapSha256","ABSENT");
        properties.setProperty("correlation","synthetic-migration-correlation");
        return properties;
    }

    private Path providerEvidence() throws Exception {
        Path file=directory.resolve("provider-evidence.json");
        if (!Files.exists(file)) Files.writeString(file,"""
                {"schemaVersion":"1.0","resourceMode":"APPLIED_RESOURCE","serviceProfile":"YANDEX_MANAGED",
                 "postgresqlMajor":17,"providerVersion":"yandex-cloud/yandex 0.220.0",
                 "resourceReference":"synthetic:staging:database","environmentReference":"synthetic:staging:database",
                 "databaseUrlSha256":"%s","extensions":{"btree_gist":"1.7","pgcrypto":"1.3"},
                 "providerDocumentSha256":"34e1f92c87f22eb1256f49b2a31c49911cd62bb7c18ce4f7960e43f585584c96"}
                """.formatted(ManagedMigrationEvidence.digest(JDBC.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return file;
    }

    private Path write(Properties properties) throws Exception {
        Path file=directory.resolve("manifest.properties");
        try(var output=Files.newBufferedWriter(file)) { properties.store(output,"synthetic manifest"); }
        return file;
    }
}
