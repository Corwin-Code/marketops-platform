package com.mimococo.marketops.operatingfacts;

import java.util.UUID;

/**
 * Turning what one acquisition job stored into facts, now.
 *
 * <p>Passes run until the job has nothing left, a pass stops for a reason a person has to look at,
 * or the pass limit is reached; each pass is its own transaction and cursor, so asking again is
 * always safe. New catalogue or price facts also run the store's master-data policy when one is in
 * force.
 */
public interface FactNormalization {

    /** Normalize everything the job stored that is not yet a fact. */
    Outcome normalizeJob(UUID jobId);

    /**
     * What normalizing one job did.
     *
     * @param lastReason why the last pass stopped: NOTHING_TO_PROCESS when the job is caught up,
     *        PROCESSED when the pass limit was reached first, otherwise a reason to look at
     * @param masterDataAutomationRan whether the store's master-data policy ran afterwards
     * @param masterDataAutomationFailed whether it ran and failed; the facts are recorded either way
     */
    record Outcome(int passes, int observationsExamined, int factsRecorded, int recordsRejected,
                   String lastReason, boolean masterDataAutomationRan, boolean masterDataAutomationFailed) {

        /** Whether everything the job stored is now a fact. */
        public boolean caughtUp() {
            return "NOTHING_TO_PROCESS".equals(lastReason);
        }

        /** Whether a pass stopped on something a person has to look at. */
        public boolean stopped() {
            return !caughtUp() && !"PROCESSED".equals(lastReason);
        }
    }
}
