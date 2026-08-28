package com.mimococo.marketops.marketplaceintegration.adapter.objectstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.marketplaceintegration.port.ObjectStoragePort;
import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local custody has the same finite body limit and non-replacement semantics as managed custody. */
class FilesystemObjectStorageTest {
    private static final String REFERENCE = "object-ref://marketops-raw/export/content";
    @TempDir Path root;

    @Test
    void concurrentWritersCannotReplaceTheFirstPublishedBytes() throws Exception {
        var storage = new FilesystemObjectStorage(root);
        byte[] first = new byte[1024];
        byte[] second = new byte[1024]; Arrays.fill(second, (byte) 1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var attempts = pool.invokeAll(List.of(() -> storage.putIfAbsent(REFERENCE, first),
                    () -> storage.putIfAbsent(REFERENCE, second)));
            assertThat(List.of(attempts.get(0).get(), attempts.get(1).get()))
                    .containsExactlyInAnyOrder(ObjectStoragePort.PutOutcome.STORED, ObjectStoragePort.PutOutcome.ALREADY_PRESENT);
        }
        byte[] published = storage.read(REFERENCE).orElseThrow();
        assertThat(Arrays.equals(published, first) || Arrays.equals(published, second)).isTrue();
        assertThat(storage.putIfAbsent(REFERENCE, new byte[] {9})).isEqualTo(ObjectStoragePort.PutOutcome.ALREADY_PRESENT);
        assertThat(storage.read(REFERENCE).orElseThrow()).isEqualTo(published);
        assertThat(storage.verify(REFERENCE, Digest.ofBytes(published))).isTrue();
        assertThat(storage.verify(REFERENCE, "0".repeat(64))).isFalse();
        try (var files = Files.list(root.resolve("marketops-raw/export"))) {
            assertThat(files.map(path -> path.getFileName().toString())).containsExactly("content");
        }
    }

    @Test
    void readRefusesAnOversizedReplacedFileAndDoesNotFollowALeafSymlink() throws Exception {
        var storage = new FilesystemObjectStorage(root);
        storage.putIfAbsent(REFERENCE, new byte[] {1});
        Path file = root.resolve("marketops-raw/export/content");
        try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(8 * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
        }
        assertThatThrownBy(() -> storage.read(REFERENCE)).isInstanceOf(OperationRejectedException.class);
        Files.delete(file);
        Path other = root.resolve("unrelated"); Files.write(other, new byte[] {2});
        Files.createSymbolicLink(file, other);
        assertThat(storage.read(REFERENCE)).isEmpty();
    }

    @Test
    void exactBoundEmptyBodiesMissingObjectsAndInvalidInputsAreExplicit() {
        var storage = new FilesystemObjectStorage(root);
        assertThat(storage.read(REFERENCE)).isEmpty();
        assertThat(storage.verify(REFERENCE, "0".repeat(64))).isFalse();
        byte[] maximum = new byte[8 * 1024 * 1024];
        storage.putIfAbsent(REFERENCE, maximum);
        assertThat(storage.read(REFERENCE).orElseThrow()).hasSize(maximum.length);
        storage.putIfAbsent("object-ref://marketops-raw/export/empty", new byte[0]);
        assertThat(storage.read("object-ref://marketops-raw/export/empty").orElseThrow()).isEmpty();
        assertThatThrownBy(() -> storage.putIfAbsent(REFERENCE, new byte[maximum.length + 1])).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> storage.putIfAbsent(REFERENCE, null)).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> storage.read("object-ref://marketops-raw/../unrelated")).isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> storage.read(null)).isInstanceOf(OperationRejectedException.class);
    }
}
