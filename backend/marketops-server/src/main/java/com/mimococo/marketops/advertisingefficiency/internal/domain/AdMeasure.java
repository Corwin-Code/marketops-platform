package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.analyticsdecision.ValueState;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One advertising number, together with whether it is a number at all.
 *
 * <p>Every quantity a case carries travels in this shape, and the compact
 * constructor is the reason it is worth having: a measure that claims to be
 * {@link ValueState#AVAILABLE} must carry a value, and one that does not must
 * not. Without that pairing the difference between "this object had no clicks"
 * and "nobody reported this object's clicks" survives only as long as the next
 * person to write a {@code coalesce}, and those two facts justify opposite
 * decisions.
 *
 * <p>The evidence state is separate from the value state on purpose. A number
 * can be perfectly present and three weeks stale.
 */
public record AdMeasure(ValueState valueState, BigDecimal value, AdEvidenceState evidenceState) {

    public AdMeasure {
        Objects.requireNonNull(valueState, "valueState");
        Objects.requireNonNull(evidenceState, "evidenceState");
        if ((valueState == ValueState.AVAILABLE) != (value != null)) {
            throw new IllegalArgumentException(
                    "an advertising measure carries a value exactly when it is AVAILABLE");
        }
    }

    /** A measure that was computed, with the evidence grade behind it. */
    public static AdMeasure available(BigDecimal value, AdEvidenceState evidenceState) {
        Objects.requireNonNull(value, "value");
        return new AdMeasure(ValueState.AVAILABLE, value, evidenceState);
    }

    /** A measure no source publishes. Never zero. */
    public static AdMeasure notAvailable(AdEvidenceState evidenceState) {
        return new AdMeasure(ValueState.NOT_AVAILABLE, null, evidenceState);
    }

    /** A measure whose definition has no answer for these inputs, such as a zero denominator. */
    public static AdMeasure undefined(AdEvidenceState evidenceState) {
        return new AdMeasure(ValueState.UNDEFINED, null, evidenceState);
    }

    /** Whether a number is present. */
    public boolean present() {
        return valueState == ValueState.AVAILABLE;
    }

    /**
     * Whether this measure may be consumed by a controlled write.
     *
     * <p>Both halves must hold: the number has to exist, and the evidence behind
     * it has to be good enough for a transmission. An explained estimate fails
     * the second half even though it passes the first.
     */
    public boolean sufficientForWrite() {
        return present() && evidenceState.sufficientForWrite();
    }

    /**
     * The value, or a stated fallback when absent.
     *
     * <p>Callers that need a number for ranking use this; callers that need a
     * number for a decision use {@link #present()} first. The distinction is why
     * there is no bare {@code value()} accessor that silently returns
     * {@code null} into arithmetic.
     */
    public BigDecimal orElse(BigDecimal fallback) {
        return present() ? value : fallback;
    }
}
