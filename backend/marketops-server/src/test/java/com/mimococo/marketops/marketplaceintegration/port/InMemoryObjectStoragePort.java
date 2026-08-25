package com.mimococo.marketops.marketplaceintegration.port;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A write-once, content-verifying object store held in memory.
 *
 * <p>The map enforces the same contract the port declares — a reference is
 * written at most once, and verification digests the stored copy rather than
 * trusting the writer — so a flow tested against it exercises the real custody
 * discipline without choosing a provider.
 */
public final class InMemoryObjectStoragePort implements ObjectStoragePort {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public PutOutcome putIfAbsent(String objectRef, byte[] body) {
        byte[] previous = objects.putIfAbsent(objectRef, body.clone());
        return previous == null ? PutOutcome.STORED : PutOutcome.ALREADY_PRESENT;
    }

    @Override
    public Optional<byte[]> read(String objectRef) {
        byte[] stored = objects.get(objectRef);
        return stored == null ? Optional.empty() : Optional.of(stored.clone());
    }

    @Override
    public boolean verify(String objectRef, String sha256Hex) {
        byte[] stored = objects.get(objectRef);
        return stored != null && sha256Of(stored).equals(sha256Hex);
    }

    /** The lowercase hexadecimal SHA-256 digest of these bytes. */
    public static String sha256Of(byte[] body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", impossible);
        }
    }
}
