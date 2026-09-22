package com.mimococo.marketops.listingconversion.internal.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Three separate clocks on one responsibility Task.
 *
 * <p>Acknowledgement, substantive disposition and outcome maturity each run
 * from the first-raised time. Reassignment and recalculation never reset it;
 * only a qualified dependency hold pauses the action clock, and the hold is
 * subtracted rather than restarting anything.
 */
public final class ResponsibilityClocks {

    private ResponsibilityClocks() {
    }

    public record Deadlines(Instant firstRaisedAt, Instant acknowledgementDue, Instant actionDue,
                            Instant outcomeMaturityDue) {
    }

    public static Deadlines deadlines(Instant firstRaisedAt, Duration acknowledgement, Duration action,
                                      Duration outcomeMaturity, Duration qualifiedHold) {
        Objects.requireNonNull(firstRaisedAt, "firstRaisedAt");
        Duration hold = qualifiedHold == null || qualifiedHold.isNegative() ? Duration.ZERO : qualifiedHold;
        return new Deadlines(firstRaisedAt,
                firstRaisedAt.plus(acknowledgement),
                firstRaisedAt.plus(action).plus(hold),
                firstRaisedAt.plus(outcomeMaturity));
    }

    /** Elapsed seconds on the action clock, with a qualified hold subtracted. */
    public static long actionElapsedSeconds(Instant firstRaisedAt, Instant now, Duration qualifiedHold) {
        long elapsed = Duration.between(firstRaisedAt, now).getSeconds();
        long hold = qualifiedHold == null ? 0 : Math.max(0, qualifiedHold.getSeconds());
        return Math.max(0, elapsed - hold);
    }
}
