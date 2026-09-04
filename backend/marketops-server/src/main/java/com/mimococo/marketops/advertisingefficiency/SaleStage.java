package com.mimococo.marketops.advertisingefficiency;

/**
 * Which company sale event an advertising number is counted against.
 *
 * <p>The stage is the reason this enum exists rather than a boolean. An
 * Allowable CPA priced against an Order and a conversion measured against a
 * Retained Sale are both perfectly good numbers, and multiplying them together
 * produces a Max CPC that is wrong by the entire cancellation and return rate.
 * Carrying the stage on both sides makes that multiplication refusable instead
 * of merely inadvisable.
 *
 * <p>{@link #PROVIDER_NATIVE_OBSERVATION} is in the same enum so that the
 * marketplace's own number has somewhere honest to live. It is never a company
 * sale event, and no Allowable CPA may be priced against it.
 */
public enum SaleStage {

    /** What the marketplace attributed to itself. An observation, never company truth. */
    PROVIDER_NATIVE_OBSERVATION(false),

    /** A company order deterministically linked to this advertising object. */
    CANONICAL_AD_LINKED_ORDER(true),

    /** A linked order that completed. The early post-action safety stage. */
    CANONICAL_AD_LINKED_COMPLETED_SALE(true),

    /** A completed sale that survived the retention window. The final sales-protection stage. */
    CANONICAL_AD_LINKED_RETAINED_SALE(true);

    private final boolean canonical;

    SaleStage(boolean canonical) {
        this.canonical = canonical;
    }

    /** Whether this stage is a company sale event rather than a provider observation. */
    public boolean canonical() {
        return canonical;
    }

    /**
     * Whether an Allowable CPA may be priced against this stage.
     *
     * <p>Only a company sale event carries contribution. Pricing against the
     * marketplace's own attributed order would price against a number whose
     * economics we do not own.
     */
    public boolean pricesContribution() {
        return canonical;
    }
}
