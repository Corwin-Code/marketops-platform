package com.mimococo.marketops.advertisingefficiency;

import java.time.Instant;
import java.util.UUID;

/**
 * A reference to something the case was calculated from.
 *
 * <p>The identifiers are opaque on purpose: this view says which kind of
 * evidence exists and when it was observed, and opening it is a separate,
 * separately authorized read. A queue row that inlined its source facts would
 * disclose more than the queue's own permission covers.
 */
public record AdvertisingEvidenceView(
        String evidenceRole,
        UUID referenceId,
        Instant observedAt,
        String note) {
}
