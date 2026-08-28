package com.mimococo.marketops.operatingfacts;

import com.mimococo.marketops.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The purchase cost in force for one internal variant at one instant.
 *
 * <p>Cost is effective-dated, so a profit figure for last month resolves the
 * version that was in force then rather than the one somebody corrected
 * yesterday. The version identifier travels with the answer so the figure can
 * name exactly which cost it used.
 *
 * @param costVersionId the version that was in force
 * @param unitCost the cost of one unit
 * @param effectiveFrom when the version took effect
 * @param provenanceId where the version came from
 */
public record CostSnapshot(
        UUID costVersionId,
        Money unitCost,
        Instant effectiveFrom,
        UUID provenanceId) {
}
