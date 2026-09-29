package com.mimococo.marketops.operatingfacts;

import java.time.Instant;

/**
 * How many buyers searched for a listing variant in one search period
 * (marketplace analytics, evidence grade C).
 *
 * @param periodStart inclusive start of the period the buyers were counted over
 * @param periodEnd exclusive end of that period
 * @param evidence what the answer was derived from
 */
public record SearchDemandSnapshot(long searchUsers, Instant periodStart, Instant periodEnd,
                                   FactEvidence evidence) {
}
