package com.mimococo.marketops.advertisingefficiency;

/**
 * How much a calculated advertising answer may be trusted.
 *
 * <p>{@code sufficientForWrite} is the only method that matters at the
 * transmission boundary, and it is deliberately true for exactly two states. An
 * estimate is admissible for diagnosis and is never admissible for a bid; a
 * stale, conflicted, incomplete or unknown answer is admissible for neither.
 *
 * <p>The blocked states are separate from the missing ones on purpose.
 * {@code DATA_BLOCKED} means a fact we expect did not arrive.
 * {@code POLICY_BLOCKED} means the fact arrived and no governing version says
 * what to do with it. A console that showed those as the same grey dash would
 * send the wrong person to fix it.
 */
public enum AdEvidenceState {

    /** Settled, reconciled and confirmed. */
    CANONICAL_CONFIRMED(true, false),

    /** Complete and current, but the settlement that will confirm it has not matured. */
    OPERATIONAL(true, false),

    /** Derived from an allocation or an assumption, with its method stated. */
    PROVISIONAL_OR_ESTIMATED(false, false),

    /** The fact exists but is older than its purpose allows. */
    STALE(false, false),

    /** Part of the required evidence is missing. */
    INCOMPLETE(false, false),

    /** Two sources disagree and neither has been made authoritative. */
    CONFLICTED(false, false),

    /** The platform's own semantics for this fact are not known. */
    UNKNOWN(false, false),

    /** No source publishes this fact. Distinct from a fact whose value is zero. */
    NOT_AVAILABLE(false, false),

    /** An expected fact did not arrive, or arrived unusable. */
    DATA_BLOCKED(false, true),

    /** The fact is present and no governing policy version says what to do with it. */
    POLICY_BLOCKED(false, true),

    /** No freshness or qualification profile resolves for the consuming purpose. */
    PROFILE_UNRESOLVED(false, true),

    /** No unique complete active decision policy bundle covers this scope. */
    BUNDLE_UNRESOLVED(false, true);

    private final boolean sufficientForWrite;
    private final boolean blocked;

    AdEvidenceState(boolean sufficientForWrite, boolean blocked) {
        this.sufficientForWrite = sufficientForWrite;
        this.blocked = blocked;
    }

    /**
     * Whether a controlled bid change may rest on evidence in this state.
     *
     * <p>True for {@code CANONICAL_CONFIRMED} and {@code OPERATIONAL} only. Every
     * other state, including a perfectly explained estimate, fails closed.
     */
    public boolean sufficientForWrite() {
        return sufficientForWrite;
    }

    /**
     * Whether this state names something a person must repair.
     *
     * <p>A blocked state is somebody's task. A merely absent one may just be a
     * fact the marketplace does not publish.
     */
    public boolean blocked() {
        return blocked;
    }

    /** The weaker of two states, so a conjunction of evidence cannot improve on its worst part. */
    public AdEvidenceState weakest(AdEvidenceState other) {
        if (other == null) {
            return this;
        }
        return other.ordinal() > ordinal() ? other : this;
    }
}
