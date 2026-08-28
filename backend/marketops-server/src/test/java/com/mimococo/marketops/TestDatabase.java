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
public final class TestDatabase {

    private static final String MIGRATION_ROLE = "marketops_migration";
    private static final String APPLICATION_ROLE = "marketops_app";

    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String APPLICATION_PASSWORD = UUID.randomUUID().toString();

    private static final class Shared {
        private static final PostgreSQLContainer CONTAINER = isolatedContainer();
    }

    private TestDatabase() {
    }

    static PostgreSQLContainer container() {
        return Shared.CONTAINER;
    }

    /** An independent server for tests that change shared platform configuration. */
    public static PostgreSQLContainer isolatedContainer() {
        var container = build();
        container.start();
        return container;
    }

    public static String migrationRole() {
        return MIGRATION_ROLE;
    }

    public static String applicationRole() {
        return APPLICATION_ROLE;
    }

    public static String migrationPassword() {
        return MIGRATION_PASSWORD;
    }

    public static String applicationPassword() {
        return APPLICATION_PASSWORD;
    }

    private static PostgreSQLContainer build() {
        Path initDirectory = repositoryRoot().resolve("infra/compose/postgres-init");
        return new PostgreSQLContainer(DockerImageName.parse(
                "postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3")
                .asCompatibleSubstituteFor("postgres"))
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
