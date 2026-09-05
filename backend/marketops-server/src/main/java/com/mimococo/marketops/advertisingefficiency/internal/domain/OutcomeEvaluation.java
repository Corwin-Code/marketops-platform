package com.mimococo.marketops.advertisingefficiency.internal.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a bid change turned out to do, judged against a plan written beforehand.
 *
 * <p>Every threshold, window and metric here came from a policy row that
 * existed before the command did. That is the point of the plan: once a bid has
 * moved, any choice about what "worked" means is made by somebody who can
 * already see the answer.
 *
 * <p>{@code INDETERMINATE} is a real verdict and the most important one. It says
 * the evidence did not settle the question, which is not the same as saying the
 * change did nothing — and a product that collapsed the two would report
 * "unchanged" for every case where it simply could not tell.
 *
 * <p>A settled verdict additionally passes the early Completed-Sales Guard. In
 * this market a sale placed today can be cancelled or returned for weeks, so an
 * improvement measured on sales too recent to have completed is a claim about
 * orders rather than about money.
 */
public record OutcomeEvaluation(
        Stage stage,
        Verdict verdict,
        GuardState guardState,
        BigDecimal changeRatio,
        List<String> unresolvedReasons) {

    /** Which of the two views of the same window this is. */
    public enum Stage {

        /** What the numbers look like now: orders placed, spend recorded. */
        OPERATIONAL,

        /** What survived cancellations, returns and provider corrections. */
        RETAINED, SETTLED
    }

    /** What the evidence says. */
    public enum Verdict {
        IMPROVED, UNCHANGED, REGRESSED, INDETERMINATE, NOT_YET_EVALUABLE
    }

    /** Whether a settled claim may be made yet. */
    public enum GuardState {

        /** Enough time has passed and enough of the window has settled. */
        SATISFIED,

        /** The sales in the window are too recent to have completed. */
        SALES_TOO_RECENT,

        /** Too little of the window settled to judge the whole of it. */
        COVERAGE_INSUFFICIENT,

        /** An operational view makes no settled claim, so nothing to guard. */
        NOT_APPLICABLE
    }

    private static final MathContext CONTEXT = new MathContext(20, RoundingMode.HALF_UP);

    public OutcomeEvaluation {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(guardState, "guardState");
        unresolvedReasons = List.copyOf(
                unresolvedReasons == null ? List.of() : unresolvedReasons);
        if ((verdict == Verdict.INDETERMINATE || verdict == Verdict.NOT_YET_EVALUABLE)
                != !unresolvedReasons.isEmpty()) {
            throw new IllegalArgumentException(
                    "a verdict that settled nothing says why, and one that settled "
                            + "something has nothing left to explain");
        }
        if ((stage == Stage.OPERATIONAL) != (guardState == GuardState.NOT_APPLICABLE)) {
            throw new IllegalArgumentException(
                    "the completed-sales guard applies to a settled claim and only to one");
        }
        if (stage != Stage.OPERATIONAL && guardState != GuardState.SATISFIED
                && verdict != Verdict.INDETERMINATE && verdict != Verdict.NOT_YET_EVALUABLE
                && verdict != Verdict.REGRESSED) {
            throw new IllegalArgumentException(
                    "a settled claim cannot outrun the completed-sales guard");
        }
    }

    /**
     * Judge one window against the plan.
     *
     * <p>Order matters. The evidence is checked before the arithmetic, because
     * a ratio computed from a missing baseline is a number with no meaning that
     * would nonetheless compare cleanly against a threshold.
     */
    public static OutcomeEvaluation evaluate(Stage stage, AdMeasure baseline, AdMeasure observed,
                                             Long observedTraffic, OutcomePlan plan,
                                             GuardState guardState) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(plan, "plan");
        GuardState guard = stage == Stage.OPERATIONAL ? GuardState.NOT_APPLICABLE : guardState;

        List<String> reasons = new ArrayList<>();
        if (baseline == null || !baseline.present()) {
            reasons.add("BASELINE_UNAVAILABLE");
        }
        if (observed == null || !observed.present()) {
            reasons.add("OBSERVED_UNAVAILABLE");
        }
        if (observedTraffic == null) {
            reasons.add("TRAFFIC_UNAVAILABLE");
        } else if (observedTraffic < plan.minimumTrafficCount()) {
            // Too little traffic to distinguish a change from noise. Saying so
            // is more useful than reporting UNCHANGED on four clicks.
            reasons.add("TRAFFIC_BELOW_MINIMUM");
        }
        if (stage != Stage.OPERATIONAL && guard != GuardState.SATISFIED) {
            reasons.add(guard == GuardState.SALES_TOO_RECENT
                    ? "COMPLETED_SALES_GUARD_OPEN" : "SETTLED_COVERAGE_INSUFFICIENT");
        }
        if (!reasons.isEmpty()) {
            Verdict verdict = reasons.contains("COMPLETED_SALES_GUARD_OPEN")
                    ? Verdict.NOT_YET_EVALUABLE : Verdict.INDETERMINATE;
            return new OutcomeEvaluation(stage, verdict, guard, null, List.copyOf(reasons));
        }

        BigDecimal baselineValue = baseline.orElse(null);
        BigDecimal observedValue = observed.orElse(null);
        if (baselineValue.signum() == 0) {
            // No proportional change exists from zero. This is a real state of
            // the world rather than a division to guard against.
            return new OutcomeEvaluation(stage, Verdict.INDETERMINATE, guard, null,
                    List.of("BASELINE_IS_ZERO"));
        }
        BigDecimal ratio = observedValue.subtract(baselineValue)
                .divide(baselineValue.abs(), CONTEXT);

        // The stored ratio is what actually happened; the compared ratio is
        // oriented toward improvement. For spend a fall is the improvement, and
        // reading the raw sign as a verdict would report every successful
        // Protection decrease as a regression.
        BigDecimal towardImprovement =
                plan.measure().higherIsBetter() ? ratio : ratio.negate();

        Verdict verdict;
        if (towardImprovement.compareTo(plan.improvementThresholdRatio()) >= 0) {
            verdict = Verdict.IMPROVED;
        } else if (towardImprovement.compareTo(plan.regressionThresholdRatio().negate()) <= 0) {
            verdict = Verdict.REGRESSED;
        } else {
            verdict = Verdict.UNCHANGED;
        }
        return new OutcomeEvaluation(stage, verdict, guard, ratio, List.of());
    }

    /** Whether this observation is a claim about money rather than about orders. */
    public boolean settledClaim() {
        return stage != Stage.OPERATIONAL && guardState == GuardState.SATISFIED;
    }

    /**
     * The thresholds and bounds one plan fixes, before any of it is measured.
     *
     * <p>The measure travels with them because which way is better is a property
     * of what is being measured, not a separate setting. A plan that could say
     * "less revenue is an improvement" is a plan somebody can misconfigure.
     */
    public record OutcomePlan(
            OutcomeMeasure measure,
            BigDecimal improvementThresholdRatio,
            BigDecimal regressionThresholdRatio,
            long minimumTrafficCount) {

        public OutcomePlan {
            Objects.requireNonNull(measure, "measure");
            Objects.requireNonNull(improvementThresholdRatio, "improvementThresholdRatio");
            Objects.requireNonNull(regressionThresholdRatio, "regressionThresholdRatio");
            if (improvementThresholdRatio.signum() <= 0 || regressionThresholdRatio.signum() <= 0) {
                throw new IllegalArgumentException(
                        "a threshold of zero would call every measurement a result");
            }
            if (minimumTrafficCount < 0) {
                throw new IllegalArgumentException("traffic is not negative");
            }
        }
    }
}
