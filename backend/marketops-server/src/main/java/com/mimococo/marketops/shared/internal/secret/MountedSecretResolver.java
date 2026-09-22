package com.mimococo.marketops.shared.internal.secret;

import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.mimococo.marketops.shared.CorrelationId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private static final int MAXIMUM_VALUE_BYTES = 16 * 1024;

    private final Path mountDirectory;
    private final boolean workstationFallback;

    public MountedSecretResolver(Path mountDirectory) {
        this(mountDirectory, false);
    }

    /**
     * @param mountDirectory where the secret manager delivers values, or {@code null}
     * @param workstationFallback whether a platform without descriptor-relative
     *        directory access may read by path; see {@link #readByPath}
     */
    public MountedSecretResolver(Path mountDirectory, boolean workstationFallback) {
        this.mountDirectory = mountDirectory == null
                ? null : mountDirectory.toAbsolutePath().normalize();
        this.workstationFallback = workstationFallback;
    }

    @Override
    public Optional<char[]> resolve(String secretReference) {
        if (mountDirectory == null) {
            return Optional.empty();
        }
        if (secretReference == null || !REFERENCE.matcher(secretReference).matches()) {
            return refuse("secret_reference_malformed");
        }
        Path resolved = mountDirectory.resolve(secretReference.substring(SCHEME.length()));
        // Walk from the filesystem root using directory descriptors. A lexical
        // startsWith check and a final NOFOLLOW are insufficient: any parent,
        // including the configured mount, could otherwise redirect the read.
        try {
            try (var root = Files.newDirectoryStream(resolved.getRoot())) {
                if (root instanceof SecureDirectoryStream<Path> directory) {
                    return readRelative(directory, resolved.getRoot().relativize(resolved));
                }
            }
            return workstationFallback ? readByPath(resolved) : refuse("secret_mount_unsupported");
        } catch (IOException | SecurityException | UnsupportedOperationException unreadable) {
            return refuse("secret_value_unreadable");
        }
    }

    /**
     * Read a value on a platform with no descriptor-relative directory access
     * (macOS). Only the local environment can enable this.
     *
     * <p>Without descriptor-relative opens a parent directory could be swapped
     * between the check and the read, so the walk accepts only directories that
     * nobody but their owner can change: the mount must be its own canonical
     * path, every directory from the mount down must be a real directory that
     * neither group nor others can write, and the value must be a regular file
     * that is not a link and is still the same file after it was opened.
     */
    private Optional<char[]> readByPath(Path resolved) throws IOException {
        if (!mountDirectory.toRealPath().equals(mountDirectory)) {
            return refuse("secret_mount_not_canonical");
        }
        Path relative = mountDirectory.relativize(resolved);
        Path directory = mountDirectory;
        if (!ownerOnlyDirectory(directory)) {
            return refuse("secret_mount_not_private");
        }
        for (int index = 0; index < relative.getNameCount() - 1; index++) {
            directory = directory.resolve(relative.getName(index));
            if (!ownerOnlyDirectory(directory)) {
                return refuse("secret_mount_not_private");
            }
        }
        Path file = directory.resolve(relative.getFileName());
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) {
            return refuse("secret_reference_not_regular");
        }
        if (before.size() > MAXIMUM_VALUE_BYTES) {
            return refuse("secret_value_oversized");
        }
        try (var channel = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (before.fileKey() == null || !before.fileKey().equals(after.fileKey())) {
                return refuse("secret_reference_changed");
            }
            return readValue(channel);
        }
    }

    private static boolean ownerOnlyDirectory(Path directory) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(directory,
                PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Set<PosixFilePermission> permissions = attributes.permissions();
        return attributes.isDirectory()
                && !permissions.contains(PosixFilePermission.GROUP_WRITE)
                && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
    }

    private static Optional<char[]> readRelative(SecureDirectoryStream<Path> directory,
                                                 Path remaining) throws IOException {
        Path name = remaining.getName(0);
        if (remaining.getNameCount() > 1) {
            try (var child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                return readRelative(child, remaining.subpath(1, remaining.getNameCount()));
            }
        }
        var attributes = directory.getFileAttributeView(name, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (!attributes.isRegularFile()) {
            return refuse("secret_reference_not_regular");
        }
        if (attributes.size() > MAXIMUM_VALUE_BYTES) {
            return refuse("secret_value_oversized");
        }
        try (var channel = directory.newByteChannel(name,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            return readValue(channel);
        }
    }

    /** Bounded even if the platform rotates or grows a file after the size check. */
    static Optional<char[]> readValue(SeekableByteChannel channel) throws IOException {
        byte[] raw = new byte[MAXIMUM_VALUE_BYTES + 1];
        CharBuffer decoded = null;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(raw);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // File channels may return fewer bytes than requested.
            }
            if (buffer.position() > MAXIMUM_VALUE_BYTES) {
                return refuse("secret_value_oversized");
            }
            buffer.flip();
            decoded = CharBuffer.allocate(buffer.remaining());
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            var result = decoder.decode(buffer, decoded, true);
            if (result.isError()) {
                result.throwException();
            }
            decoder.flush(decoded);
            decoded.flip();
            if (decoded.isEmpty() || decoded.chars().allMatch(Character::isWhitespace)) {
                return refuse("secret_value_empty");
            }
            // One trailing line terminator is how `echo`, editors and most secret
            // tooling end a file, and it is never part of a credential; left in,
            // it would make every header built from the value invalid. Only that
            // one is dropped. No String copy and no strip(): any other whitespace
            // may be part of a secret.
            int length = decoded.remaining();
            if (decoded.get(decoded.position() + length - 1) == '\n') {
                length--;
                if (length > 0 && decoded.get(decoded.position() + length - 1) == '\r') {
                    length--;
                }
            }
            char[] value = new char[length];
            decoded.get(value, 0, length);
            return Optional.of(value);
        } finally {
            Arrays.fill(raw, (byte) 0);
            if (decoded != null && decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
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
        return "MountedSecretResolver[configured=" + Objects.nonNull(mountDirectory)
                + ",workstationFallback=" + workstationFallback + "]";
    }
}
