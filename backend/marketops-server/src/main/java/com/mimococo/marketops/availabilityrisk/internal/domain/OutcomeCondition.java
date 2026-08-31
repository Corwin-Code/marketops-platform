package com.mimococo.marketops.availabilityrisk.internal.domain;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.RiskCause;

/**
 * Whether the thing a case was raised about has actually stopped being true.
 *
 * <p>This is the second stage's question, and it is deliberately not "did
 * somebody do something". An action is evidence that work happened; only a
 * fresh calculation of the same subject is evidence that the risk improved, and
 * the two are different facts about different things.
 *
 * <p>The condition depends on what was wrong. A shortage is repaired when the
 * lane falls back below the activation band; a defect is repaired when the
 * defect is gone and the evidence is usable again. Judging a repaired data
 * source by the lane would refuse to close it while a real, correctly
 * calculated shortage remained — which is a different case with a different
 * owner.
 */
public final class OutcomeCondition {

    private OutcomeCondition() {
    }

    /**
     * Whether the cause a case was raised for is repaired right now.
     *
     * @param raised the cause the case was raised for
     * @param current the freshly calculated child for the same subject
     */
    public static boolean holds(RiskCause raised, ChildRisk current) {
        if (raised == RiskCause.NONE || current.cause() == raised) {
            return false;
        }
        // Evidence that is not sufficient for safety cannot establish that
        // anything improved. A source that went quiet looks exactly like a
        // source reporting good news, and closing a case on it would be the
        // false safety the whole Slice is built to refuse.
        if (!current.evidenceState().sufficientForSafety()) {
            return false;
        }
        if (raised.blocker()) {
            return true;
        }
        return current.lane().severityOrdinal() <= AvailabilityLane.WATCH.severityOrdinal();
    }
}
