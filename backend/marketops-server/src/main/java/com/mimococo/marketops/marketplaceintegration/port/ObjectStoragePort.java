package com.mimococo.marketops.marketplaceintegration.port;

import java.util.Optional;

/**
 * Provider-neutral custody of immutable Raw bytes.
 *
 * <p>Storage is content-addressed and write-once. {@code putIfAbsent} either
 * stores the bytes under the reference or reports that the reference already
 * holds bytes; nothing overwrites, and there is deliberately no delete — Raw
 * evidence leaves custody through a governed retention decision, never through
 * an API a worker can reach.
 *
 * <p>A write is only durable once it has been read back and its digest matches
 * what was written, which is why verification is part of this contract instead
 * of a caller's optional courtesy: a cursor acknowledged against unverified
 * bytes would be acknowledged against hope.
 *
 * <p>No concrete provider appears here. The reference is an opaque
 * {@code object-ref://} locator, and binding it to a real store is gated on the
 * open Object Storage decision.
 */
public interface ObjectStoragePort {

    /**
     * Store the bytes under the reference unless the reference already holds
     * content, and report which of the two happened.
     */
    PutOutcome putIfAbsent(String objectRef, byte[] body);

    /** The stored bytes, when the reference holds any. */
    Optional<byte[]> read(String objectRef);

    /**
     * Whether the stored bytes match this SHA-256 digest, read back from
     * storage rather than answered from what the writer remembers sending.
     */
    boolean verify(String objectRef, String sha256Hex);

    /** The two ways a write-once store can answer a put. */
    enum PutOutcome {
        STORED,
        ALREADY_PRESENT
    }
}
