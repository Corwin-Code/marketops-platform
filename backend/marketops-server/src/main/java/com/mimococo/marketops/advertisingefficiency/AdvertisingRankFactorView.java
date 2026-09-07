package com.mimococo.marketops.advertisingefficiency;

import java.math.BigDecimal;

/**
 * One visible term of a case's rank.
 *
 * <p>Present on every case for every term, including at zero, so two adjacent
 * queue rows can be compared by a person rather than accepted by one.
 */
public record AdvertisingRankFactorView(
        String factorCode,
        BigDecimal value,
        BigDecimal weight,
        BigDecimal contribution,
        String displayNote) {
}
