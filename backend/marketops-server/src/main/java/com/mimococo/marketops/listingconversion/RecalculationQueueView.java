package com.mimococo.marketops.listingconversion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One queued trigger with its target and, once finished, its measured latency. */
public record RecalculationQueueView(UUID id, RecalculationClass triggerClass, int targetMinutes,
                                     UUID platformListingId, String triggerReference, Instant sourceTime,
                                     Instant acceptedAt, Instant startedAt, Instant finishedAt, String state,
                                     Integer latencySeconds, boolean withinTarget, List<UUID> measurementResultIds,
                                     Integer bindingAssessedCount, Integer bindingInvalidatedCount) {
    public RecalculationQueueView { measurementResultIds=List.copyOf(measurementResultIds); }
}
