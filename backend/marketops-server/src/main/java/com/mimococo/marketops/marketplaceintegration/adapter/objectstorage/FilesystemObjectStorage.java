package com.mimococo.marketops.marketplaceintegration.adapter.objectstorage;

import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <p>A write lands in a temporary file and is moved into place atomically, so a
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

    private final Path root;

    public FilesystemObjectStorage(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public PutOutcome putIfAbsent(String objectRef, byte[] body) {
        Path target = resolve(objectRef);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                return PutOutcome.ALREADY_PRESENT;
            }
            Path staged = Files.createTempFile(target.getParent(), "staged-", ".part");
            try {
                Files.write(staged, body);
                moveIntoPlace(staged, target);
                return PutOutcome.STORED;
            } catch (FileAlreadyExistsException raced) {
                Files.deleteIfExists(staged);
                return PutOutcome.ALREADY_PRESENT;
            } catch (IOException | RuntimeException failure) {
                Files.deleteIfExists(staged);
                throw failure;
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public Optional<byte[]> read(String objectRef) {
        Path target = resolve(objectRef);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(target));
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
     * <p>An atomic move is preferred and is what makes a concurrent writer see
     * either the whole object or none of it. Filesystems that cannot promise
     * atomicity fall back to a non-replacing move, which still refuses to
     * overwrite; only the all-or-nothing visibility is weaker there.
     */
    private static void moveIntoPlace(Path staged, Path target) throws IOException {
        try {
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(staged, target);
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
