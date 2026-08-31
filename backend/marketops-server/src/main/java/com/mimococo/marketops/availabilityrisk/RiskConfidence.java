package com.mimococo.marketops.availabilityrisk;

/**
 * How much weight the queue should give a calculated risk.
 *
 * <p>Confidence penalises a rank; it never rescues a lane. A low-confidence
 * CRITICAL is still CRITICAL, because the alternative — quietly demoting a risk
 * because its evidence is thin — is how a real stockout goes unnoticed.
 */
public enum RiskConfidence {

    /** Fresh, complete and unconflicted throughout. */
    HIGH(0),

    /** Complete but with one downgrade: an estimate, an operational source, a gap. */
    MEDIUM(1),

    /** Carried forward, materially censored or provisional. */
    LOW(2),

    /** Not usable as a commercial signal; the card exists to get the data fixed. */
    UNUSABLE(3);

    private final int penaltyRank;

    RiskConfidence(int penaltyRank) {
        this.penaltyRank = penaltyRank;
    }

    /** How much this confidence subtracts from a commercial rank. */
    public int penaltyRank() {
        return penaltyRank;
    }

    /** The weaker of two confidences. */
    public RiskConfidence weakest(RiskConfidence other) {
        if (other == null) {
            return this;
        }
        return other.penaltyRank > penaltyRank ? other : this;
    }
}
