package com.mimococo.marketops.marketplaceintegration.adapter.secret;

import com.mimococo.marketops.marketplaceintegration.port.SecretResolverPort;
import com.mimococo.marketops.shared.CorrelationId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves an opaque secret reference from the platform's delivery mount.
 *
 * <p>A reference names a path relative to the mount, so rotating a value is a
 * platform operation rather than an application deployment, and the application
 * never learns anything about the secret store beyond the name it was given.
 *
 * <p>Every failure is silent to the caller and specific in the log: the event
 * names the failure class and the correlation identifier, never the reference's
 * resolved path and never any part of the value. An unresolvable reference
 * returns empty, which every caller treats as a refusal.
 */
public final class MountedSecretResolver implements SecretResolverPort {

    private static final Logger log = LoggerFactory.getLogger(MountedSecretResolver.class);

    /** The opaque reference shape the registry issues. */
    private static final Pattern REFERENCE = Pattern.compile(
            "^secret-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,4}$");

    private static final String SCHEME = "secret-ref://";

    /** Largest value this resolver will read; a larger file is not a credential. */
    private static final long MAXIMUM_VALUE_BYTES = 16 * 1024L;

    private final Path mountDirectory;

    public MountedSecretResolver(Path mountDirectory) {
        this.mountDirectory = mountDirectory == null
                ? null : mountDirectory.toAbsolutePath().normalize();
    }

    @Override
    public Optional<char[]> resolve(String secretReference) {
        if (mountDirectory == null) {
            return Optional.empty();
        }
        if (secretReference == null || !REFERENCE.matcher(secretReference).matches()) {
            return refuse("secret_reference_malformed");
        }
        Path resolved = mountDirectory
                .resolve(secretReference.substring(SCHEME.length()).toLowerCase(Locale.ROOT))
                .normalize();
        if (!resolved.startsWith(mountDirectory)) {
            return refuse("secret_reference_outside_mount");
        }
        if (!Files.isRegularFile(resolved)) {
            return refuse("secret_reference_absent");
        }
        try {
            if (Files.size(resolved) > MAXIMUM_VALUE_BYTES) {
                return refuse("secret_value_oversized");
            }
            byte[] raw = Files.readAllBytes(resolved);
            char[] value = new String(raw, StandardCharsets.UTF_8).strip().toCharArray();
            java.util.Arrays.fill(raw, (byte) 0);
            return value.length == 0 ? refuse("secret_value_empty") : Optional.of(value);
        } catch (IOException unreadable) {
            return refuse("secret_value_unreadable");
        }
    }

    private static Optional<char[]> refuse(String event) {
        log.atWarn()
                .addKeyValue("event", event)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A secret reference could not be resolved");
        return Optional.empty();
    }

    @Override
    public String toString() {
        // The mount path can name a platform detail, so the representation says
        // only whether a mount is configured.
        return "MountedSecretResolver[configured=" + Objects.nonNull(mountDirectory) + "]";
    }
}
