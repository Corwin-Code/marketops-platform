package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.UUID;

/**
 * Where one canonical fact came from, in the terms an operator needs to check
 * it.
 *
 * <p>The trail names the source without exposing a way to reach it. There is no
 * storage locator and no signed link here: reading the bytes is a separate,
 * separately authorized step that verifies them on the way out.
 *
 * @param provenanceId identifier of the provenance record
 * @param sourceKind whether the fact came from a marketplace, a file or a person
 * @param sourceTime when the source considered it true, or {@code null}
 * @param ingestionTime when this system learned it
 * @param rawObservationId the acquisition answer it came from, or {@code null}
 * @param nativeStatus the transport's own words for that answer, or {@code null}
 * @param contentSha256 digest of the stored bytes, or {@code null}
 * @param contentByteLength length of the stored bytes, or {@code null}
 * @param importBatchId the submitted file it came from, or {@code null}
 * @param declaredFileName the name that file was submitted under, or {@code null}
 * @param recordedByUserId who entered it by hand, or {@code null}
 * @param evidenceNote what the recorder said about it, or {@code null}
 */
public record EvidenceTrail(
        UUID provenanceId,
        String sourceKind,
        Instant sourceTime,
        Instant ingestionTime,
        UUID rawObservationId,
        String nativeStatus,
        String contentSha256,
        Long contentByteLength,
        UUID importBatchId,
        String declaredFileName,
        UUID recordedByUserId,
        String evidenceNote) {
}
