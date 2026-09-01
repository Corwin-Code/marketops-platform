package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable total key over the accepted-fact feed. */
public record AcceptedFactCursor(Instant ingestionTime, UUID provenanceId, String itemKey) {

    public static final UUID BEFORE_ANY_PROVENANCE = new UUID(0, 0);

    public AcceptedFactCursor {
        Objects.requireNonNull(ingestionTime, "ingestionTime");
        Objects.requireNonNull(provenanceId, "provenanceId");
        Objects.requireNonNull(itemKey, "itemKey");
    }

    public static AcceptedFactCursor beginningAt(Instant at) {
        return new AcceptedFactCursor(at, BEFORE_ANY_PROVENANCE, "");
    }
}
