package com.mimococo.marketops.advertisingefficiency;

/**
 * The hard sub-tier inside {@link AdvertisingLane#PROTECTION}.
 *
 * <p>These are not weights. A P0 case outranks every P1 case regardless of how
 * much money the P1 case is losing, because an unresolved execution state or an
 * active quarantine is a question about whether the system is telling the truth,
 * and no amount of measured loss should be allowed to answer it.
 *
 * <p>Only Protection has sub-tiers. The other lanes order on their own reason
 * sequences, and giving them tiers would invite the same weighted-sum collapse
 * the tiers exist to prevent.
 */
public enum ProtectionTier {

    /** Action or outcome regression, active quarantine, unresolved execution integrity, or a compensation decision. */
    P0(3),

    /** Confirmed sellability or availability danger, or protected critical-sales risk. */
    P1(2),

    /** Proven continuing advertising economic harm. */
    P2(1),

    /** Another explicitly policy-qualified Protection danger. */
    P3(0);

    private final int tierBand;

    ProtectionTier(int tierBand) {
        this.tierBand = tierBand;
    }

    /** The band this tier occupies above the Protection lane band. */
    public int tierBand() {
        return tierBand;
    }
}
