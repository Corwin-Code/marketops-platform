package com.mimococo.marketops.advertisingefficiency.internal.domain;

import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Whether advertising efficiency actually improved, on two axes that cannot
 * rescue each other.
 *
 * <p>The rule is a non-compensating Pareto test: one axis must improve
 * materially, the other must not worsen materially, and sales preservation must
 * pass separately. A single blended efficiency score would let a large gain in
 * profit-per-rouble — which is trivially achievable by spending almost nothing —
 * pay for a collapse in absolute contribution, and the seller would read
 * "improved" while earning less money.
 *
 * <p>The second rule this type enforces is that a loss which shrinks is not
 * health. Halving a monthly loss from two million to one million is a real,
 * reportable, praiseworthy result and it is still a million-rouble loss.
 * {@link Outcome#IMPROVED_NOT_HEALTHY} exists so that result can be celebrated
 * without the responsibility for it being closed.
 */
public record DualAxisVerdict(
        Outcome outcome,
        AxisMovement absoluteProfit,
        AxisMovement profitPerAdRub,
        boolean salesPreserved,
        String reasonCode) {

    /** What the two axes and the sales guard jointly proved. */
    public enum Outcome {

        /** Both profit conditions and sales preservation hold, and the result is positive. */
        VERIFIED_EFFICIENCY_SUCCESS,

        /** The result improved materially and remains a loss. */
        IMPROVED_NOT_HEALTHY,

        /** Nothing moved by more than the policy's material band. */
        NO_MATERIAL_IMPROVEMENT,

        /** An axis worsened materially, or sales preservation failed. */
        REGRESSION,

        /** The evidence cannot support a verdict either way. */
        UNRESOLVED
    }

    /** How one axis moved relative to its frozen baseline. */
    public enum AxisMovement {

        /** Improved by at least the policy's material threshold. */
        MATERIALLY_IMPROVED,

        /** Moved within the policy's non-worsening band. */
        UNCHANGED_WITHIN_BAND,

        /** Worsened by at least the policy's material threshold. */
        MATERIALLY_WORSENED,

        /** The axis could not be measured. */
        UNRESOLVED
    }

    public DualAxisVerdict {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(absoluteProfit, "absoluteProfit");
        Objects.requireNonNull(profitPerAdRub, "profitPerAdRub");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    /**
     * Judge the two axes against a frozen threshold policy.
     *
     * <p>Order matters and is not an optimisation. Sales preservation is checked
     * before either profit axis, because a change that made money by losing
     * volume is a change the Contract refuses regardless of its profit
     * arithmetic. Unresolved evidence is checked next, because an unmeasurable
     * axis must not be silently treated as unchanged.
     */
    public static DualAxisVerdict evaluate(
            AdMeasure baselineAbsolute,
            AdMeasure currentAbsolute,
            AdMeasure baselinePerRub,
            AdMeasure currentPerRub,
            BigDecimal materialAbsoluteDelta,
            BigDecimal materialPerRubDelta,
            boolean salesPreserved,
            boolean salesEvidenceComplete) {
        if (!salesEvidenceComplete) {
            return new DualAxisVerdict(Outcome.UNRESOLVED, AxisMovement.UNRESOLVED,
                    AxisMovement.UNRESOLVED, false, "SALES_PRESERVATION_EVIDENCE_INCOMPLETE");
        }
        if (!salesPreserved) {
            return new DualAxisVerdict(Outcome.REGRESSION, AxisMovement.UNRESOLVED,
                    AxisMovement.UNRESOLVED, false, "SALES_PRESERVATION_FAILED");
        }
        AxisMovement absolute = movement(baselineAbsolute, currentAbsolute, materialAbsoluteDelta);
        AxisMovement perRub = movement(baselinePerRub, currentPerRub, materialPerRubDelta);
        if (absolute == AxisMovement.UNRESOLVED || perRub == AxisMovement.UNRESOLVED) {
            return new DualAxisVerdict(Outcome.UNRESOLVED, absolute, perRub, true,
                    "PROFIT_AXIS_EVIDENCE_INCOMPLETE");
        }
        if (absolute == AxisMovement.MATERIALLY_WORSENED || perRub == AxisMovement.MATERIALLY_WORSENED) {
            return new DualAxisVerdict(Outcome.REGRESSION, absolute, perRub, true,
                    "PROFIT_AXIS_MATERIALLY_WORSENED");
        }
        boolean oneImproved = absolute == AxisMovement.MATERIALLY_IMPROVED
                || perRub == AxisMovement.MATERIALLY_IMPROVED;
        if (!oneImproved) {
            return new DualAxisVerdict(Outcome.NO_MATERIAL_IMPROVEMENT, absolute, perRub, true,
                    "NO_AXIS_MATERIALLY_IMPROVED");
        }
        // The improvement is real. Whether it is health depends on where it
        // landed, not on how far it travelled.
        if (currentAbsolute.value().signum() < 0) {
            return new DualAxisVerdict(Outcome.IMPROVED_NOT_HEALTHY, absolute, perRub, true,
                    "ABSOLUTE_CONTRIBUTION_PROFIT_STILL_NEGATIVE");
        }
        return new DualAxisVerdict(Outcome.VERIFIED_EFFICIENCY_SUCCESS, absolute, perRub, true,
                "DUAL_AXIS_PARETO_SATISFIED");
    }

    /** Whether this verdict may be reported as a healthy efficiency success. */
    public boolean healthy() {
        return outcome == Outcome.VERIFIED_EFFICIENCY_SUCCESS;
    }

    /**
     * Whether the responsibility that produced this case may be closed.
     *
     * <p>{@code IMPROVED_NOT_HEALTHY} deliberately does not close it: partial
     * loss reduction with continuing proven harm is progress, not completion.
     */
    public boolean closesResponsibility() {
        return outcome == Outcome.VERIFIED_EFFICIENCY_SUCCESS;
    }

    private static AxisMovement movement(AdMeasure baseline, AdMeasure current, BigDecimal materialDelta) {
        if (baseline == null || current == null || !baseline.present() || !current.present()
                || materialDelta == null) {
            return AxisMovement.UNRESOLVED;
        }
        BigDecimal delta = current.value().subtract(baseline.value());
        if (delta.compareTo(materialDelta) >= 0) {
            return AxisMovement.MATERIALLY_IMPROVED;
        }
        if (delta.negate().compareTo(materialDelta) >= 0) {
            return AxisMovement.MATERIALLY_WORSENED;
        }
        return AxisMovement.UNCHANGED_WITHIN_BAND;
    }

    /** A convenience for the common case where both axes share a currency. */
    public static BigDecimal amountOf(Money money) {
        return money == null ? null : money.amount();
    }
}
