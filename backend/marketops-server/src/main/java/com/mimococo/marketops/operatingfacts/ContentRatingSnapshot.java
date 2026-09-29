package com.mimococo.marketops.operatingfacts;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The marketplace's newest content rating of a listing variant, 0 to 100.
 *
 * @param evidence what the answer was derived from
 */
public record ContentRatingSnapshot(BigDecimal rating, Instant observedAt, FactEvidence evidence) {
}
