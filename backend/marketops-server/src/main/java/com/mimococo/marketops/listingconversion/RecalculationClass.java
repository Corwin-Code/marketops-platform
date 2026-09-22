package com.mimococo.marketops.listingconversion;

/** How fast a canonical change trigger must be recalculated, as an internal target. */
public enum RecalculationClass {
    RISK(5),
    ORDINARY(15),
    FULL_REVIEW(60);

    private final int targetMinutes;

    RecalculationClass(int targetMinutes) {
        this.targetMinutes = targetMinutes;
    }

    public int targetMinutes() {
        return targetMinutes;
    }
}
