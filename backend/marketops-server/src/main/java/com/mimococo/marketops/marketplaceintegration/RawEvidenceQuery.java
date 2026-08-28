package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Published read access to stored acquisition evidence.
 *
 * <p>Normalization, replay and evidence drill-through all read through this
 * contract rather than through the evidence tables, so Raw custody keeps one
 * owner and one verification path. Reading bytes verifies them against the
 * digest they were stored under, which is what makes a replay a proof rather
 * than a hope.
 */
public interface RawEvidenceQuery {

    /**
     * Observations of one job after a cursor, oldest first.
     *
     * <p>The cursor is a time and an identifier together, because two
     * observations can share an ingestion instant.
     */
    List<RawObservationView> observationsAfter(UUID jobId,
                                               Instant afterIngestionTime,
                                               UUID afterObservationId,
                                               int limit);

    /** One observation, when it exists. */
    Optional<RawObservationView> observation(UUID observationId);

    /**
     * The exact stored bytes of one observation, verified against their digest.
     *
     * <p>An empty result means custody no longer holds content matching the
     * record, which is a reconciliation finding rather than an ordinary miss.
     */
    Optional<byte[]> verifiedBody(UUID observationId);
}
