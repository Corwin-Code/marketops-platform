package com.mimococo.marketops.marketplaceintegration;

import java.util.Optional;

/**
 * The single authority for immutable custody of exact source bytes.
 *
 * <p>Both the acquisition path and the internal file-intake path store their
 * evidence here, which is what makes evidence drill-through one mechanism
 * rather than two that can disagree. Storage is content-addressed and
 * write-once: identical bytes are one custody record however they arrived, and
 * nothing overwrites.
 *
 * <p>A store is not complete until the bytes have been read back and their
 * digest matches what was written. A cursor acknowledged against unverified
 * bytes, or an import accepted against them, would be acknowledged against
 * hope, so verification is part of the operation rather than a caller's
 * optional courtesy.
 */
public interface RawCustody {

    /**
     * Store bytes under a namespace and return their custody reference.
     *
     * <p>The namespace groups evidence by what produced it and appears in the
     * opaque locator. It carries no authority: two namespaces are one store.
     *
     * @throws com.mimococo.marketops.shared.OperationRejectedException when the
     *         stored bytes do not read back with the digest they were written
     *         under
     */
    RawContentRef store(String namespace, byte[] body);

    /** The stored bytes for a custody reference, when custody still holds them. */
    Optional<byte[]> read(RawContentRef reference);

    /** Whether custody still holds bytes matching the reference's digest. */
    boolean verify(RawContentRef reference);
}
