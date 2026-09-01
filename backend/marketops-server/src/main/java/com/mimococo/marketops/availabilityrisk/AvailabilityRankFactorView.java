package com.mimococo.marketops.availabilityrisk;

import java.math.BigDecimal;

/**
 * One visible reason a card sits where it does.
 *
 * <p>Sent to the console so the queue can show its reasoning rather than a
 * score. An operator who can argue with a ranking trusts it; one shown only a
 * number learns to ignore it.
 *
 * @param factorCode which factor
 * @param value the measured quantity
 * @param weight the policy weight applied
 * @param contribution what it added to the score
 * @param displayNote a short sentence explaining the term
 */
public record AvailabilityRankFactorView(
        String factorCode, BigDecimal value, BigDecimal weight,
        BigDecimal contribution, String displayNote) {
}
