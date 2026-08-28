package com.mimococo.marketops.marketplaceintegration;

import java.util.Objects;
import java.util.UUID;

/**
 * A reference to bytes held in immutable Raw custody.
 *
 * <p>The reference names content, not a location a caller could write to. It
 * carries the digest and the length so a consumer can state what it is holding
 * without reading the bytes back, and the opaque locator so custody can move
 * between providers without any consumer changing.
 *
 * @param contentId identifier of the custody record
 * @param sha256 lower-case hexadecimal digest of the stored bytes
 * @param byteLength length of the stored bytes
 * @param objectRef opaque locator inside the approved object store
 */
public record RawContentRef(UUID contentId, String sha256, long byteLength, String objectRef) {

    public RawContentRef {
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(objectRef, "objectRef");
    }
}
