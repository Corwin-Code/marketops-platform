package com.mimococo.marketops.analyticsdecision;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Recalculating a store's metrics and findings when nobody asked: after scheduled collection.
 *
 * <p>A calculation window ends at the previous full hour, so facts collected after that hour are
 * only counted by a calculation after the next one; callers wait for it rather than calculate early.
 */
public interface StoreRecalculation {

    /** Where the store's newest completed calculation over the window ended, whoever started it. */
    Optional<Instant> latestPeriodEnd(UUID storeId, MetricWindow window);

    /** Recalculate the store over the window ending at the previous full hour, as a SCHEDULED run. */
    Result recalculate(UUID storeId, MetricWindow window);

    /** What one calculation did. */
    record Result(UUID calculationRunId, int subjectCount, int valueCount, int findingCount) {
    }
}
