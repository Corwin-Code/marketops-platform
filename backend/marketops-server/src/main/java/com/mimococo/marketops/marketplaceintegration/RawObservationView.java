package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.UUID;

/**
 * One stored acquisition answer, as other modules may see it.
 *
 * <p>The view carries the evidence a normalizer needs to attribute a fact and
 * nothing that would let it reach custody itself: the bytes are fetched through
 * the published contract, which verifies them, rather than through a locator a
 * caller could read directly.
 *
 * @param observationId identifier of the observation
 * @param jobId job whose evidence this is
 * @param runId run that produced it
 * @param unitKind kind of source page
 * @param sourceUnitKey the source's own key for the page
 * @param sourceTime when the source considered it true, or {@code null}
 * @param nativeStatus the transport's own status words
 * @param outcomeClass how the answer was classified
 * @param ingestionTime when this system learned it
 * @param sha256 digest of the stored bytes
 * @param byteLength length of the stored bytes
 */
public record RawObservationView(
        UUID observationId,
        UUID jobId,
        UUID runId,
        String unitKind,
        String sourceUnitKey,
        Instant sourceTime,
        String nativeStatus,
        String outcomeClass,
        Instant ingestionTime,
        String sha256,
        long byteLength) {

    /** Whether this answer carried a source payload worth normalizing. */
    public boolean carriesPayload() {
        return "SUCCESS_BYTES".equals(outcomeClass);
    }
}
