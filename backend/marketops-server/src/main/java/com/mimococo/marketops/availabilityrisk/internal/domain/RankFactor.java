package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One visible reason a card sits where it does in the queue.
 *
 * <p>The queue shows these rather than only a score. An operator who can see
 * "18 days to stockout, 42 000 RUB at risk, confidence penalty because demand
 * was carried forward" can argue with the ranking; an operator shown only
 * "score 612" can only accept or ignore it.
 *
 * @param code which factor this is
 * @param value the measured quantity
 * @param weight the policy weight applied to it
 * @param contribution weight times the normalised value
 * @param displayNote a short sentence explaining the term
 */
public record RankFactor(Code code, BigDecimal value, BigDecimal weight,
                         BigDecimal contribution, String displayNote) {

    public RankFactor {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(contribution, "contribution");
        Objects.requireNonNull(displayNote, "displayNote");
    }

    /**
     * The closed set of factors a rank may use.
     *
     * <p>Closed because the Contract fixes it. A factor that is not here cannot
     * influence order, which is what stops an opaque signal — a model score, a
     * hand-tuned boost — being added later without anybody noticing.
     */
    public enum Code {
        /** How soon cover runs out. Sooner ranks higher. */
        TIME_TO_STOCKOUT,
        /** Contribution profit exposed while unavailable. More ranks higher. */
        CONTRIBUTION_PROFIT_AT_RISK,
        /** Units per day. Faster ranks higher. */
        SALES_VELOCITY,
        /** Owner-approved lifecycle strategy for the variant. */
        LIFECYCLE_STRATEGY,
        /** How much the evidence is trusted. Weaker evidence ranks lower. */
        CONFIDENCE_PENALTY
    }
}
