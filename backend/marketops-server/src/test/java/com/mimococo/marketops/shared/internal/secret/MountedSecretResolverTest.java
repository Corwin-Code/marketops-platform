package com.mimococo.marketops.shared.internal.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class MountedSecretResolverTest {
    private static final String REFERENCE = "secret-ref://fixture/api-key";
    @TempDir Path temporary;
    private Path mount;
    private MountedSecretResolver resolver;

    @BeforeEach
    void configureRealDirectory() throws IOException {
        // macOS exposes the temporary directory through /var -> /private/var.
        // Only the fixture setup resolves that alias; the resolver must not.
        mount = Files.createDirectory(temporary.toRealPath().resolve("mount"));
        Files.createDirectory(mount.resolve("fixture"));
        resolver = new MountedSecretResolver(mount);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "api-key", "secret-ref://fixture", "SECRET-REF://fixture/api-key",
            "secret-ref://fixture/API", "secret-ref://../api-key", "secret-ref://fixture/../api-key",
            "secret-ref://fixture//api-key", "secret-ref://fixture/api-key/",
            "secret-ref://fixture/a/b/c/d/e", "secret-ref://fixture/%2e%2e", "secret-ref://fixture/a?b",
            "secret-ref://fixture/a#b", "secret-ref://fixture/a\\b", "secret-ref://fixture/a\nb"})
    void malformedReferencesNeverResolve(String reference) {
        assertThat(resolver.resolve(reference)).isEmpty();
    }

    @Test
    void unconfiguredMountIsClosed() {
        assertThat(new MountedSecretResolver(null).resolve(REFERENCE)).isEmpty();
        assertThat(new MountedSecretResolver(null)).hasToString("MountedSecretResolver[configured=false]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"synthetic-value", "  exact synthetic value\n", "пример合成🙂"})
    void preservesExactUtf8AndWhitespace(String value) throws IOException {
        Path file = Files.writeString(mount.resolve("fixture/api-key"), value);
        char[] resolved = readFile(file).orElseThrow();
        assertThat(resolved).containsExactly(value.toCharArray());
        Arrays.fill(resolved, '\0');
    }

    @Test
    void nestedRotationReadsFreshFileContents() throws IOException {
        Path file = mount.resolve("fixture/rotation/v2/api-key");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "synthetic-first");
        assertThat(readFile(file).orElseThrow())
                .containsExactly("synthetic-first".toCharArray());
        Files.writeString(file, "synthetic-second");
        assertThat(readFile(file).orElseThrow())
                .containsExactly("synthetic-second".toCharArray());
    }

    @Test
    void missingFileAndDirectoryAreNotValues() throws IOException {
        assertThat(resolver.resolve(REFERENCE)).isEmpty();
        Files.createDirectory(mount.resolve("fixture/api-key"));
        assertThat(resolver.resolve(REFERENCE)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\r\n", "\u2003"})
    void emptyOrWhitespaceOnlyFilesAreRefused(String value) throws IOException {
        Files.writeString(mount.resolve("fixture/api-key"), value);
        assertThat(readFile(mount.resolve("fixture/api-key"))).isEmpty();
    }

    @Test
    void maximumAppliesToBytesNotCharacters() throws IOException {
        String exact = "é".repeat(8192);
        Path file = mount.resolve("fixture/api-key");
        Files.writeString(file, exact);
        assertThat(readFile(file).orElseThrow()).containsExactly(exact.toCharArray());
        Files.writeString(file, exact + "a");
        assertThat(readFile(file)).isEmpty();
    }

    @Test
    void malformedUtf8IsRefusedWithoutReplacementCharacters() throws IOException {
        Files.write(mount.resolve("fixture/api-key"), new byte[] {'a', (byte) 0xc3, 0x28});
        assertThatThrownBy(() -> readFile(mount.resolve("fixture/api-key")))
                .isInstanceOf(java.nio.charset.CharacterCodingException.class);
    }

    @Test
    void nativeFilesystemEitherSupportsDescriptorTraversalOrRefusesTheMount() throws IOException {
        Files.writeString(mount.resolve("fixture/api-key"), "synthetic-value");
        try (var root = Files.newDirectoryStream(mount.getRoot())) {
            if (root instanceof SecureDirectoryStream<?>) {
                assertThat(resolver.resolve(REFERENCE).orElseThrow())
                        .containsExactly("synthetic-value".toCharArray());
            } else {
                assertThat(resolver.resolve(REFERENCE)).isEmpty();
            }
        }
    }

    @Test
    void leafSymlinkCannotReadOutsideMount() throws IOException {
        Path outside = Files.writeString(temporary.toRealPath().resolve("outside"), "synthetic-outside");
        Files.createSymbolicLink(mount.resolve("fixture/api-key"), outside);
        assertThat(resolver.resolve(REFERENCE)).isEmpty();
    }

    @Test
    void symlinkToAValueInsideMountIsAlsoRefused() throws IOException {
        Path target = Files.writeString(mount.resolve("fixture/real-value"), "synthetic-inside");
        Files.createSymbolicLink(mount.resolve("fixture/api-key"), target);
        assertThat(resolver.resolve(REFERENCE)).isEmpty();
    }

    @Test
    void referenceParentSymlinkCannotReadOutsideMount() throws IOException {
        Path outside = Files.createDirectory(temporary.toRealPath().resolve("outside"));
        Files.writeString(outside.resolve("api-key"), "synthetic-outside");
        Files.createSymbolicLink(mount.resolve("redirect"), outside);
        assertThat(resolver.resolve("secret-ref://redirect/api-key")).isEmpty();
    }

    @Test
    void configuredMountAndItsAncestorsCannotBeSymlinks() throws IOException {
        Files.writeString(mount.resolve("fixture/api-key"), "synthetic-value");
        Path alias = temporary.toRealPath().resolve("mount-alias");
        Files.createSymbolicLink(alias, mount);
        assertThat(new MountedSecretResolver(alias).resolve(REFERENCE)).isEmpty();
        Path ancestor = temporary.toRealPath().resolve("ancestor-alias");
        Files.createSymbolicLink(ancestor, temporary.toRealPath());
        assertThat(new MountedSecretResolver(ancestor.resolve("mount")).resolve(REFERENCE)).isEmpty();
    }

    @Test
    void growingOrUnendingReadStopsAtMaximumPlusOneAndClearsBytes() throws IOException {
        SeekableByteChannel channel = mock(SeekableByteChannel.class);
        AtomicReference<byte[]> raw = new AtomicReference<>();
        doAnswer(call -> {
            ByteBuffer buffer = call.getArgument(0);
            raw.set(buffer.array());
            int size = Math.min(1024, buffer.remaining());
            for (int i = 0; i < size; i++) buffer.put((byte) 'x');
            return size;
        }).when(channel).read(any(ByteBuffer.class));
        assertThat(MountedSecretResolver.readValue(channel)).isEmpty();
        verify(channel, times(17)).read(any(ByteBuffer.class));
        assertThat(raw.get()).hasSize(16385).containsOnly((byte) 0);
    }

    @Test
    void ioFailureAlsoClearsThePartiallyReadBytes() throws IOException {
        SeekableByteChannel channel = mock(SeekableByteChannel.class);
        AtomicReference<byte[]> raw = new AtomicReference<>();
        doAnswer(call -> {
            ByteBuffer buffer = call.getArgument(0);
            raw.set(buffer.array());
            buffer.put("synthetic-partial".getBytes(StandardCharsets.UTF_8));
            throw new IOException("synthetic device failure");
        }).when(channel).read(any(ByteBuffer.class));
        assertThatThrownBy(() -> MountedSecretResolver.readValue(channel)).isInstanceOf(IOException.class);
        assertThat(raw.get()).containsOnly((byte) 0);
    }

    @Test
    void diagnosticsContainNoSecretReferencePathOrException() throws IOException {
        Files.write(mount.resolve("fixture/api-key"), new byte[] {'s', (byte) 0xff});
        Logger logger = (Logger) LoggerFactory.getLogger(MountedSecretResolver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(resolver.resolve(REFERENCE + "?synthetic-value")).isEmpty();
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).isEqualTo("A secret reference could not be resolved");
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs().toString())
                    .contains("secret_reference_malformed")
                    .doesNotContain(REFERENCE, mount.toString(), "synthetic-value");
            assertThat(resolver).hasToString("MountedSecretResolver[configured=true]");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static java.util.Optional<char[]> readFile(Path file) throws IOException {
        try (var channel = Files.newByteChannel(file)) {
            return MountedSecretResolver.readValue(channel);
        }
    }
}
