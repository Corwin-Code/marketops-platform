package com.mimococo.marketops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The database the application-level integration test runs against.
 *
 * <p>The server is initialised by the same scripts the workstation stack uses,
 * so a privilege that only exists because a test set it up differently cannot
 * make the application appear to work.
 */
final class TestDatabase {

    private static final String MIGRATION_ROLE = "marketops_migration";
    private static final String APPLICATION_ROLE = "marketops_app";

    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String APPLICATION_PASSWORD = UUID.randomUUID().toString();

    private static final PostgreSQLContainer CONTAINER = build();

    static {
        CONTAINER.start();
    }

    private TestDatabase() {
    }

    static PostgreSQLContainer container() {
        return CONTAINER;
    }

    static String migrationRole() {
        return MIGRATION_ROLE;
    }

    static String applicationRole() {
        return APPLICATION_ROLE;
    }

    static String migrationPassword() {
        return MIGRATION_PASSWORD;
    }

    static String applicationPassword() {
        return APPLICATION_PASSWORD;
    }

    private static PostgreSQLContainer build() {
        Path initDirectory = repositoryRoot().resolve("infra/compose/postgres-init");
        return new PostgreSQLContainer(DockerImageName.parse("postgres:18.4"))
                .withDatabaseName("marketops")
                .withUsername("postgres")
                .withPassword(UUID.randomUUID().toString())
                .withEnv("MARKETOPS_DB_MIGRATION_PASSWORD", MIGRATION_PASSWORD)
                .withEnv("MARKETOPS_DB_APP_PASSWORD", APPLICATION_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(initDirectory.resolve("01-init-roles.sh"), 0755),
                        "/docker-entrypoint-initdb.d/01-init-roles.sh")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(initDirectory.resolve("sql/01-roles.sql"), 0644),
                        "/docker-entrypoint-initdb.d/sql/01-roles.sql");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("bootstrap-manifest.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("the repository root could not be located");
    }
}
