package com.mimococo.marketops.marketplaceintegration;

/**
 * The states a description command moves through.
 *
 * <p>The same shape as the advertising command. Nothing collapses provider
 * acceptance, readback, operational success and settled confirmation into one
 * SUCCESS, and {@code UNKNOWN_REQUIRES_READBACK} has no edge back to a write.
 */
public enum ListingDescriptionCommandState {

    PENDING(false, false, false),
    LEASED(false, true, false),
    EXECUTING(false, true, false),
    PLATFORM_PENDING(false, true, false),
    READBACK_PENDING(false, true, false),
    READBACK_MATCHED(true, false, false),
    RETRY_WAIT(false, false, false),
    UNKNOWN_REQUIRES_READBACK(false, false, true),
    READBACK_MISMATCH(false, false, true),
    LATER_CHANGE_OR_MISMATCH_INVESTIGATION(false, false, true),
    MANUAL_RESOLUTION(false, false, true),
    FAILED_FINAL(true, false, false),
    TERMINATED_WITHOUT_PROVIDER_CALL(true, false, false),
    COMPENSATION_PENDING(false, true, true),
    COMPENSATED(true, false, false),
    COMPENSATION_FAILED(true, false, true);

    private final boolean terminal;
    private final boolean leaseHeld;
    private final boolean needsOperator;

    ListingDescriptionCommandState(boolean terminal, boolean leaseHeld, boolean needsOperator) {
        this.terminal = terminal;
        this.leaseHeld = leaseHeld;
        this.needsOperator = needsOperator;
    }

    public boolean terminal() {
        return terminal;
    }

    public boolean leaseHeld() {
        return leaseHeld;
    }

    public boolean needsOperator() {
        return needsOperator;
    }
}
