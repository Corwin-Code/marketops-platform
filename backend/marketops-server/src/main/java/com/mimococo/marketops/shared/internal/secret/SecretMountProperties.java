package com.mimococo.marketops.shared.internal.secret;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where resolved secret material is presented to this process.
 *
 * <p>The platform's secret manager delivers values into a mounted directory
 * that only this process can read; the application reads them by reference and
 * never holds a credential in its own configuration, its database or its image.
 *
 * <p>With no mount configured, every reference is unresolvable. That is the
 * intended workstation and continuous-integration posture: no secret is present
 * and every operation that would need one refuses.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.secrets")
public final class SecretMountProperties {

    private Path mountDirectory;

    /** Directory the secret manager delivers values into, or {@code null}. */
    public Path getMountDirectory() {
        return mountDirectory;
    }

    /** Bind the mount directory. */
    public void setMountDirectory(Path mountDirectory) {
        this.mountDirectory = mountDirectory;
    }
}
