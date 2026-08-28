package com.mimococo.marketops.marketplaceintegration.adapter.objectstorage;

import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable Raw custody in a local directory.
 *
 * <p>This is the custody provider for a single-node deployment and for a
 * workstation. It is not a stand-in: write-once, content-addressed and
 * read-back-verified are properties of this implementation, not concessions
 * made because the managed store is elsewhere.
 *
 * <p>A write lands in a temporary file and is linked into place atomically, so a
 * process that dies mid-write leaves no partial object under a name that claims
 * to be complete. An existing name is never replaced; the move is refused and
 * the caller is told the reference already holds content.
 *
 * <p>There is deliberately no delete. Raw evidence leaves custody through a
 * governed retention decision, never through an interface a worker can reach.
 */
public final class FilesystemObjectStorage implements ObjectStoragePort {

    /** The locator shape the custody schema accepts. */
    private static final Pattern LOCATOR = Pattern.compile(
            "^object-ref://[a-z0-9][a-z0-9-]{0,62}(/[a-z0-9][a-z0-9._-]{0,62}){1,6}$");

    private static final String SCHEME = "object-ref://";
    /** Same custody body ceiling as the managed adapter and import boundary. */
    private static final int MAXIMUM_BYTES = 8 * 1024 * 1024;

    private final Path root;

    public FilesystemObjectStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public PutOutcome putIfAbsent(String objectRef, byte[] body) {
        if (body == null || body.length > MAXIMUM_BYTES) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        Path target = resolve(objectRef);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                return PutOutcome.ALREADY_PRESENT;
            }
            Path staged = Files.createTempFile(target.getParent(), "staged-", ".part");
            try {
                try (var channel = java.nio.channels.FileChannel.open(staged, java.nio.file.StandardOpenOption.WRITE)) {
                    var bytes = java.nio.ByteBuffer.wrap(body);
                    while (bytes.hasRemaining()) { channel.write(bytes); }
                    channel.force(true);
                }
                moveIntoPlace(staged, target);
                return PutOutcome.STORED;
            } catch (FileAlreadyExistsException raced) {
                return PutOutcome.ALREADY_PRESENT;
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public Optional<byte[]> read(String objectRef) {
        Path target = resolve(objectRef);
        if (!Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            try (var input = Files.newInputStream(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAXIMUM_BYTES + 1);
                if (bytes.length > MAXIMUM_BYTES) {
                    throw OperationRejectedException.of(ErrorCode.OBJECT_STORAGE_VERIFICATION_FAILED);
                }
                return Optional.of(bytes);
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public boolean verify(String objectRef, String sha256Hex) {
        return read(objectRef)
                .map(stored -> Digest.ofBytes(stored).equalsIgnoreCase(sha256Hex))
                .orElse(false);
    }

    /**
     * Move the staged file into place without replacing an existing object.
     *
     * <p>Atomic move does not promise non-replacement on every filesystem.
     * Creating a hard link fails if the name exists, so two competing writers
     * cannot replace one another. Unsupported filesystems fail closed.
     */
    private static void moveIntoPlace(Path staged, Path target) throws IOException {
        Files.createLink(target, staged);
        try (var directory = java.nio.channels.FileChannel.open(target.getParent(), java.nio.file.StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    /**
     * Turn a locator into a path inside the custody root.
     *
     * <p>The locator shape is validated and the resolved path is checked to be
     * inside the root, so a reference cannot address a file outside custody
     * however it was constructed.
     */
    private Path resolve(String objectRef) {
        if (objectRef == null || !LOCATOR.matcher(objectRef).matches()) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String relative = objectRef.substring(SCHEME.length()).toLowerCase(Locale.ROOT);
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return resolved;
    }
}
