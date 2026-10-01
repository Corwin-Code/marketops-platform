package com.mimococo.marketops.availabilityrisk.internal.domain;

/**
 * Which unit a demand window counts, as the published demand-policy version names it (V0034).
 *
 * <p>The two are different observations of demand, never mixed in one window and never carried from one
 * into the other.
 */
public enum DemandSource {

    /** Completed sales from the ledger; the Slice 002 default. */
    COMPLETED_SALES,

    /**
     * The units the marketplace reports ordered per UTC day (P9, Owner decision 2026-10-02), until the
     * store has completed sales. Windows end with the newest day the store's order facts cover, and a
     * window observable for enough of its length with zero orders is evidence of zero demand.
     */
    ORDERED_UNITS;

    /** Whether an observable window with no units at all is evidence rather than too small a sample. */
    public boolean observedZeroIsEvidence() {
        return this == ORDERED_UNITS;
    }
}
