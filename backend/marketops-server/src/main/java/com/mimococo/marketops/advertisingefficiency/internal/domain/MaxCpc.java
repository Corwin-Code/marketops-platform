package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The economic ceiling a click may cost, or an explicit statement that there is
 * none.
 *
 * <p>Max CPC is stage-consistent Allowable CPA multiplied by stage-consistent
 * conversion, and the whole value of this type is the word "consistent". Pricing
 * an Order and converting a Retained Sale are both defensible individually;
 * multiplying them overstates the ceiling by the entire cancellation and return
 * rate, which is a mistake that shows up as a bid the seller cannot afford and
 * a report that says everything is fine.
 *
 * <p>{@link #compute} refuses the mismatch rather than correcting for it,
 * because correcting for it would mean inventing a cancellation rate nobody
 * published.
 *
 * <p>A ceiling is not a target. Nothing here proposes a bid; that is the target
 * policy's job, and it treats this value as a bound.
 */
public record MaxCpc(
        SaleStage stage,
        Money ceiling,
        AdEvidenceState evidenceState,
        Absence absence) {

    /** Why no ceiling could be computed, when none could. */
    public enum Absence {

        /** A ceiling was computed. */
        NONE,

        /** The Allowable CPA and the conversion price different sale events. */
        STAGE_MISMATCH,

        /** The conversion is not write-grade. */
        CONVERSION_NOT_WRITE_GRADE,

        /** No Allowable CPA definition resolves for this scope. */
        ALLOWABLE_CPA_UNRESOLVED,

        /** The conversion is zero, so no finite ceiling exists. */
        CONVERSION_ZERO
    }

    public MaxCpc {
        Objects.requireNonNull(evidenceState, "evidenceState");
        Objects.requireNonNull(absence, "absence");
        if ((absence == Absence.NONE) != (ceiling != null)) {
            throw new IllegalArgumentException(
                    "a Max CPC carries a ceiling exactly when no absence reason applies");
        }
        if (ceiling != null && stage == null) {
            throw new IllegalArgumentException("a ceiling always names the sale stage it prices");
        }
    }

    /**
     * The stage-consistent ceiling, or the exact reason there is none.
     *
     * <p>The stage check is first and deliberate. A caller that passed
     * mismatched stages has a defect, and returning a plausible number would
     * hide it behind a bid somebody approves.
     */
    public static MaxCpc compute(Money allowableCpa, SaleStage cpaStage, AdLinkedConversion conversion) {
        Objects.requireNonNull(conversion, "conversion");
        if (allowableCpa == null || cpaStage == null) {
            return absent(Absence.ALLOWABLE_CPA_UNRESOLVED, AdEvidenceState.POLICY_BLOCKED);
        }
        if (!cpaStage.pricesContribution()) {
            return absent(Absence.STAGE_MISMATCH, AdEvidenceState.POLICY_BLOCKED);
        }
        if (cpaStage != conversion.stage()) {
            return absent(Absence.STAGE_MISMATCH, AdEvidenceState.CONFLICTED);
        }
        if (!conversion.writeGrade()) {
            return absent(Absence.CONVERSION_NOT_WRITE_GRADE, conversion.evidenceState());
        }
        BigDecimal rate = conversion.rate().value();
        if (rate.signum() <= 0) {
            // A zero conversion means no click has ever been worth anything here.
            // That is a real business answer and it is not a ceiling of zero: a
            // ceiling of zero would read as a valid bound and justify a bid.
            return absent(Absence.CONVERSION_ZERO, conversion.evidenceState());
        }
        return new MaxCpc(cpaStage, allowableCpa.times(rate),
                conversion.evidenceState(), Absence.NONE);
    }

    /** No ceiling, with the reason a Preview must state. */
    public static MaxCpc absent(Absence absence, AdEvidenceState evidenceState) {
        if (absence == Absence.NONE) {
            throw new IllegalArgumentException("an absent Max CPC needs a reason");
        }
        return new MaxCpc(null, null, evidenceState, absence);
    }

    /** Whether a write-grade ceiling exists. */
    public boolean writeGrade() {
        return absence == Absence.NONE && evidenceState.sufficientForWrite();
    }
}
