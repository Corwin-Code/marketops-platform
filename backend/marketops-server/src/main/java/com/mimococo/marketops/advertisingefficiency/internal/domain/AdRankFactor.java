package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One visible term of a case's commercial rank.
 *
 * <p>Every term is emitted on every calculation, including at zero, so that a
 * person looking at two adjacent queue rows can see why one is above the other.
 * A rank that only appears as a number is a rank nobody can argue with, and a
 * rank nobody can argue with is one nobody checks.
 */
public record AdRankFactor(
        Code code,
        BigDecimal value,
        BigDecimal weight,
        BigDecimal contribution,
        String displayNote) {

    /** The closed set of terms, mirrored by the factor-code check constraint. */
    public enum Code {

        /** Confirmed contribution profit being lost per day. */
        CONFIRMED_PROFIT_LOSS_RATE,

        /** Retained sales of frozen required critical units inside the affected set. */
        CRITICAL_SALES_EXPOSURE,

        /** Official advertising spend currently flowing through this object. */
        OFFICIAL_SPEND_EXPOSURE,

        /** Contribution profit a qualified optimization could recover. */
        RECOVERABLE_CONTRIBUTION_PROFIT,

        /** How complete and settled the evidence behind the case is. */
        EVIDENCE_MATURITY,

        /** How long the case has been open. */
        CASE_AGE,

        /** The subtraction uncertainty applies. Never positive. */
        CONFIDENCE_PENALTY,
        HUMAN_SLO_URGENCY,
        BLOCKED_PROTECTION,
        BLAST_RADIUS,
        BLOCKED_WORK,
        DUAL_AXIS_GAP,
        DUAL_AXIS_PER_RUB_GAP,
        CRITICAL_SALES_HEADROOM
    }

    public AdRankFactor {
        Objects.requireNonNull(code, "code");
        if (value == null && (displayNote == null || !displayNote.startsWith("UNRESOLVED:"))) {
            throw new IllegalArgumentException("an absent rank input must explain its unresolved state");
        }
        Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(contribution, "contribution");
        if (code == Code.CONFIDENCE_PENALTY && contribution.signum() > 0) {
            throw new IllegalArgumentException("a confidence penalty can only subtract");
        }
    }
}
