package com.mimococo.marketops.availabilityrisk;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One demand window and how much of it could actually be observed.
 *
 * <p>Coverage travels with the units because the two are meaningless apart. A
 * console that showed "2 sold in 7 days" without showing that the listing was
 * buyable for one of them would be teaching its reader the wrong lesson.
 *
 * @param windowCode {@code D7}, {@code D14} or {@code D30}
 * @param periodStart inclusive start
 * @param periodEnd exclusive end
 * @param completedUnits units completed, or {@code null} when no source answered
 * @param dailyRate units per observable day, or {@code null}
 * @param observedDays days the listing could actually sell in
 * @param coverageRatio the share of the window that was observable
 * @param sampleSufficient whether the sample met the policy minimum
 * @param censored whether observation was materially incomplete
 * @param censoringReason why, or {@code null}
 * @param outlierShare the busiest day's share, or {@code null}
 * @param eligibility the policy verdict for this window
 */
public record DemandWindowView(
        String windowCode, Instant periodStart, Instant periodEnd, Integer completedUnits,
        BigDecimal dailyRate, BigDecimal observedDays, BigDecimal coverageRatio,
        boolean sampleSufficient, boolean censored, String censoringReason,
        BigDecimal outlierShare, String eligibility) {
}
