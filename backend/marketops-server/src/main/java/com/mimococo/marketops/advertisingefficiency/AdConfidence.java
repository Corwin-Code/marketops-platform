package com.mimococo.marketops.advertisingefficiency;

/**
 * How much the inputs behind an advertising answer agree with each other.
 *
 * <p>Confidence penalises a rank and never rescues a lane. A case whose lane is
 * {@code PROTECTION} on high confidence and one whose lane is
 * {@code PROTECTION} on low confidence are both Protection cases; the second
 * simply sorts lower inside its tier. Letting confidence move a lane would make
 * an uncertain danger disappear, which is the opposite of what uncertainty
 * should do.
 */
public enum AdConfidence {

    /** Every consumed input was complete and agreed. */
    HIGH(0),

    /** One non-determinative input was weak, absent or in disagreement. */
    MEDIUM(1),

    /** Several inputs were weak, or one determinative input was estimated. */
    LOW(2),

    /** The inputs cannot support a quantitative claim at all. */
    UNUSABLE(3);

    private final int penaltyRank;

    AdConfidence(int penaltyRank) {
        this.penaltyRank = penaltyRank;
    }

    /** How much this confidence subtracts from a commercial rank contribution. */
    public int penaltyRank() {
        return penaltyRank;
    }

    /** The weaker of two confidences. */
    public AdConfidence weakest(AdConfidence other) {
        if (other == null) {
            return this;
        }
        return other.penaltyRank > penaltyRank ? other : this;
    }
}
