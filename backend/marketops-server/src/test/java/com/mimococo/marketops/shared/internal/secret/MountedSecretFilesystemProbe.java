package com.mimococo.marketops.shared.internal.secret;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.Arrays;

/** Synthetic, disconnected runtime probe. Never receives host secrets or a host mount. */
public final class MountedSecretFilesystemProbe {
    private static final String REF = "secret-ref://fixture/api-key";
    private static int cases;

    private MountedSecretFilesystemProbe() { }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("secret-filesystem-").toRealPath();
        try (var root = Files.newDirectoryStream(temporary.getRoot())) {
            check(root instanceof SecureDirectoryStream<?>, "descriptor traversal is supported");
        }
        Path mount = Files.createDirectory(temporary.resolve("mount"));
        Files.createDirectory(mount.resolve("fixture"));
        Path file = mount.resolve("fixture/api-key");
        var resolver = new MountedSecretResolver(mount);
        for (String value : new String[] {"synthetic-value", "  exact synthetic value\n", "пример合成🙂"}) {
            Files.writeString(file, value);
            char[] resolved = resolver.resolve(REF).orElseThrow();
            check(Arrays.equals(resolved, value.toCharArray()), "exact UTF-8 and whitespace");
            Arrays.fill(resolved, '\0');
        }
        for (String value : new String[] {"", " \t\r\n", "é".repeat(8192) + "a"}) {
            Files.writeString(file, value);
            check(resolver.resolve(REF).isEmpty(), "empty and oversized values refused");
        }
        Files.writeString(file, "é".repeat(8192));
        check(resolver.resolve(REF).orElseThrow().length == 8192, "16384-byte boundary accepted");
        Files.write(file, new byte[] {'a', (byte) 0xc3, 0x28});
        check(resolver.resolve(REF).isEmpty(), "invalid UTF-8 refused");
        Files.delete(file);
        check(resolver.resolve(REF).isEmpty(), "missing leaf refused");
        Files.createDirectory(file);
        check(resolver.resolve(REF).isEmpty(), "directory leaf refused");
        Files.delete(file);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path outsideFile = Files.writeString(outside.resolve("api-key"), "synthetic-outside");
        Files.createSymbolicLink(file, outsideFile);
        check(resolver.resolve(REF).isEmpty(), "outside leaf symlink refused");
        Files.delete(file);
        Path insideFile = Files.writeString(mount.resolve("fixture/real-value"), "synthetic-inside");
        Files.createSymbolicLink(file, insideFile);
        check(resolver.resolve(REF).isEmpty(), "inside leaf symlink refused");
        Files.createSymbolicLink(mount.resolve("redirect"), outside);
        check(resolver.resolve("secret-ref://redirect/api-key").isEmpty(), "parent symlink refused");
        Path alias = temporary.resolve("mount-alias");
        Files.createSymbolicLink(alias, mount);
        check(new MountedSecretResolver(alias).resolve(REF).isEmpty(), "mount symlink refused");
        Path ancestor = temporary.resolve("ancestor-alias");
        Files.createSymbolicLink(ancestor, temporary);
        check(new MountedSecretResolver(ancestor.resolve("mount")).resolve(REF).isEmpty(),
                "mount ancestor symlink refused");
        check(resolver.resolve("secret-ref://fixture/../../outside/api-key").isEmpty(), "traversal refused");
        Path nested = mount.resolve("fixture/rotation/v2/api-key");
        Files.createDirectories(nested.getParent());
        Files.writeString(nested, "synthetic-first");
        check(Arrays.equals(resolver.resolve("secret-ref://fixture/rotation/v2/api-key").orElseThrow(),
                "synthetic-first".toCharArray()), "nested reference read");
        Files.writeString(nested, "synthetic-second");
        check(Arrays.equals(resolver.resolve("secret-ref://fixture/rotation/v2/api-key").orElseThrow(),
                "synthetic-second".toCharArray()), "rotation read without caching");
        System.out.println("MOUNTED_SECRET_FILESYSTEM_PASS cases=" + cases);
    }

    private static void check(boolean condition, String scenario) {
        if (!condition) throw new AssertionError(scenario);
        cases++;
    }
}
