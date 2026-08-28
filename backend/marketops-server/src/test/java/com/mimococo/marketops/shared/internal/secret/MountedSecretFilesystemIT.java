package com.mimococo.marketops.shared.internal.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** The deployment filesystem contract runs on Linux even when Maven runs on macOS. */
class MountedSecretFilesystemIT {
    @Test
    void linuxRuntimeReadsExactValuesAndRefusesEverySymlinkBoundary() throws Exception {
        String image = "eclipse-temurin:21-jre-noble@sha256:"
                + "96975602e131485862eb8cd32927face8a06d7591a5e865944b634a701d9df72";
        try (var runtime = new GenericContainer<>(DockerImageName.parse(image))
                .withNetworkMode("none")
                .withCopyFileToContainer(classes(MountedSecretResolver.class), "/app/main")
                .withCopyFileToContainer(classes(MountedSecretFilesystemProbe.class), "/app/test")
                .withCopyFileToContainer(classes(LoggerFactory.class), "/app/slf4j.jar")
                .withCommand("sleep", "120")) {
            runtime.start();
            var result = runtime.execInContainer("java", "-cp", "/app/main:/app/test:/app/slf4j.jar",
                    MountedSecretFilesystemProbe.class.getName());
            assertThat(result.getExitCode()).as("Linux filesystem contract: %s", result.getStderr()).isZero();
            assertThat(result.getStdout().strip()).isEqualTo("MOUNTED_SECRET_FILESYSTEM_PASS cases=19");
        }
    }

    private static MountableFile classes(Class<?> type) throws Exception {
        return MountableFile.forHostPath(Path.of(type.getProtectionDomain().getCodeSource()
                .getLocation().toURI()));
    }
}
