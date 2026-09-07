package com.mimococo.marketops.operationsworkflow;

import java.util.UUID;

/** Routes a canonical Settled contradiction into the existing human Task authority. */
public interface AdvertisingOutcomeReviewIntake {
    /** Returns the same Finance task across revisions, or null when no current review is required. */
    UUID record(UUID observationId);
}
