package com.mimococo.marketops.operatingfacts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Published drill-through from a canonical fact to what produced it.
 *
 * <p>This is what makes a metric checkable rather than merely displayed. A
 * figure an operator disputes leads to the provenance record, which leads to the
 * acquisition answer or the submitted file, which leads to the exact bytes.
 *
 * <p>Reading bytes verifies them against the digest they were stored under. An
 * empty result therefore means custody no longer holds matching content, which
 * is a reconciliation finding rather than an ordinary miss.
 */
public interface EvidenceQuery {

    /** Where one fact came from. */
    Optional<EvidenceTrail> trail(UUID provenanceId);

    /** Where several facts came from, in the order they were asked about. */
    List<EvidenceTrail> trails(List<UUID> provenanceIds);

    /** The exact stored bytes behind one fact, verified against their digest. */
    Optional<byte[]> verifiedBytes(UUID provenanceId);
}
