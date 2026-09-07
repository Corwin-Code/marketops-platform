package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation.GuardState;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation.OutcomePlan;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation.Stage;
import com.mimococo.marketops.advertisingefficiency.internal.domain.OutcomeEvaluation.Verdict;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What a bid change turned out to do, and when this product may say so.
 *
 * <p>The cases below are all about the difference between a measurement and a
 * claim. A number can be computed from almost anything; a claim about whether
 * spending more or less made the business money requires evidence that did not
 * exist a day after the change, and the guard is what keeps the two apart.
 */
class OutcomeEvaluationTest {

    private static final OutcomePlan PLAN = new OutcomePlan(
            OutcomeMeasure.ADVERTISING_CONTRIBUTION_PROFIT,
            new BigDecimal("0.10000"), new BigDecimal("0.05000"), 100);

    /** The same thresholds against a measure where a fall is the improvement. */
    private static final OutcomePlan SPEND_PLAN = new OutcomePlan(
            OutcomeMeasure.AD_SPEND,
            new BigDecimal("0.10000"), new BigDecimal("0.05000"), 100);

    private static AdMeasure value(String amount) {
        return AdMeasure.available(new BigDecimal(amount), AdEvidenceState.CANONICAL_CONFIRMED);
    }

    private static AdMeasure absent() {
        return AdMeasure.notAvailable(AdEvidenceState.INCOMPLETE);
    }

    @Nested
    @DisplayName("the thresholds a plan fixed beforehand decide the verdict")
    class Thresholds {

        @Test
        @DisplayName("TC-AD-OUTCOME-001 a rise past the improvement threshold is an improvement")
        void riseIsAnImprovement() {
            OutcomeEvaluation evaluated = OutcomeEvaluation.evaluate(Stage.OPERATIONAL,
                    value("100"), value("115"), 1000L, PLAN, GuardState.NOT_APPLICABLE);

            assertThat(evaluated.verdict()).isEqualTo(Verdict.IMPROVED);
            assertThat(evaluated.changeRatio()).isEqualByComparingTo("0.15");
            assertThat(evaluated.unresolvedReasons()).isEmpty();
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-002 the thresholds are asymmetric and both are honoured")
        void thresholdsAreAsymmetric() {
            // A five percent fall is a regression; a five percent rise is not
            // yet an improvement. That asymmetry is a policy decision and this
            // is where it either holds or quietly does not.
            assertThat(OutcomeEvaluation.evaluate(Stage.OPERATIONAL, value("100"), value("95"),
                    1000L, PLAN, GuardState.NOT_APPLICABLE).verdict())
                    .isEqualTo(Verdict.REGRESSED);
            assertThat(OutcomeEvaluation.evaluate(Stage.OPERATIONAL, value("100"), value("105"),
                    1000L, PLAN, GuardState.NOT_APPLICABLE).verdict())
                    .isEqualTo(Verdict.UNCHANGED);
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-003b a fall in spend is the improvement a decrease wanted")
        void fallInSpendIsAnImprovement() {
            // The failure this rules out: reporting every successful Protection
            // decrease as a regression because the raw sign was read as a
            // verdict.
            OutcomeEvaluation evaluated = OutcomeEvaluation.evaluate(Stage.OPERATIONAL,
                    value("1000"), value("800"), 1000L, SPEND_PLAN, GuardState.NOT_APPLICABLE);

            assertThat(evaluated.verdict()).isEqualTo(Verdict.IMPROVED);
            // The stored ratio is what happened, not what it meant.
            assertThat(evaluated.changeRatio()).isEqualByComparingTo("-0.2");
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-003c spend rising past the regression threshold is a regression")
        void riseInSpendIsARegression() {
            assertThat(OutcomeEvaluation.evaluate(Stage.OPERATIONAL, value("1000"),
                    value("1060"), 1000L, SPEND_PLAN, GuardState.NOT_APPLICABLE).verdict())
                    .isEqualTo(Verdict.REGRESSED);
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-003 exactly at a threshold counts as reaching it")
        void exactlyAtAThresholdCounts() {
            assertThat(OutcomeEvaluation.evaluate(Stage.OPERATIONAL, value("100"), value("110"),
                    1000L, PLAN, GuardState.NOT_APPLICABLE).verdict())
                    .isEqualTo(Verdict.IMPROVED);
        }
    }

    @Nested
    @DisplayName("evidence is checked before arithmetic")
    class Evidence {

        @Test
        @DisplayName("TC-AD-OUTCOME-004 a missing baseline or observation settles nothing")
        void missingValuesSettleNothing() {
            assertThat(OutcomeEvaluation.evaluate(Stage.OPERATIONAL, absent(), value("115"),
                    1000L, PLAN, GuardState.NOT_APPLICABLE))
                    .satisfies(evaluated -> {
                        assertThat(evaluated.verdict()).isEqualTo(Verdict.INDETERMINATE);
                        assertThat(evaluated.unresolvedReasons())
                                .containsExactly("BASELINE_UNAVAILABLE");
                        assertThat(evaluated.changeRatio()).isNull();
                    });
            assertThat(OutcomeEvaluation.evaluate(Stage.OPERATIONAL, value("100"), absent(),
                    1000L, PLAN, GuardState.NOT_APPLICABLE).unresolvedReasons())
                    .containsExactly("OBSERVED_UNAVAILABLE");
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-005 too little traffic is said out loud, not called unchanged")
        void tooLittleTrafficIsSaidOutLoud() {
            // Reporting UNCHANGED on four clicks would be a measurement
            // presented as a finding.
            OutcomeEvaluation evaluated = OutcomeEvaluation.evaluate(Stage.OPERATIONAL,
                    value("100"), value("100"), 4L, PLAN, GuardState.NOT_APPLICABLE);

            assertThat(evaluated.verdict()).isEqualTo(Verdict.INDETERMINATE);
            assertThat(evaluated.unresolvedReasons()).containsExactly("TRAFFIC_BELOW_MINIMUM");
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-006 no proportional change exists from a baseline of zero")
        void zeroBaselineIsItsOwnAnswer() {
            OutcomeEvaluation evaluated = OutcomeEvaluation.evaluate(Stage.OPERATIONAL,
                    value("0"), value("50"), 1000L, PLAN, GuardState.NOT_APPLICABLE);

            assertThat(evaluated.verdict()).isEqualTo(Verdict.INDETERMINATE);
            assertThat(evaluated.unresolvedReasons()).containsExactly("BASELINE_IS_ZERO");
        }
    }

    @Nested
    @DisplayName("a settled claim cannot outrun the completed-sales guard")
    class Guard {

        @Test
        @DisplayName("TC-AD-OUTCOME-007 sales too recent to have completed settle nothing")
        void recentSalesSettleNothing() {
            OutcomeEvaluation evaluated = OutcomeEvaluation.evaluate(Stage.SETTLED,
                    value("100"), value("140"), 1000L, PLAN, GuardState.SALES_TOO_RECENT);

            // Forty percent up on paper, and the product still says it does not
            // know, because none of those sales has had the chance to be
            // returned.
            assertThat(evaluated.verdict()).isEqualTo(Verdict.NOT_YET_EVALUABLE);
            assertThat(evaluated.unresolvedReasons())
                    .containsExactly("COMPLETED_SALES_GUARD_OPEN");
            assertThat(evaluated.settledClaim()).isFalse();
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-008 insufficient settled coverage settles nothing either")
        void insufficientCoverageSettlesNothing() {
            OutcomeEvaluation evaluated = OutcomeEvaluation.evaluate(Stage.SETTLED,
                    value("100"), value("140"), 1000L, PLAN, GuardState.COVERAGE_INSUFFICIENT);

            assertThat(evaluated.verdict()).isEqualTo(Verdict.INDETERMINATE);
            assertThat(evaluated.unresolvedReasons())
                    .containsExactly("SETTLED_COVERAGE_INSUFFICIENT");
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-009 a satisfied guard permits a claim about money")
        void satisfiedGuardPermitsAClaim() {
            OutcomeEvaluation evaluated = OutcomeEvaluation.evaluate(Stage.SETTLED,
                    value("100"), value("140"), 1000L, PLAN, GuardState.SATISFIED);

            assertThat(evaluated.verdict()).isEqualTo(Verdict.IMPROVED);
            assertThat(evaluated.settledClaim()).isTrue();
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-010 an operational view never carries a guard state")
        void operationalViewCarriesNoGuard() {
            // Asking the guard about an operational view is a category error:
            // it makes no settled claim, so there is nothing to guard.
            assertThat(OutcomeEvaluation.evaluate(Stage.OPERATIONAL, value("100"), value("140"),
                    1000L, PLAN, GuardState.SATISFIED).guardState())
                    .isEqualTo(GuardState.NOT_APPLICABLE);
        }
    }

    @Nested
    @DisplayName("an incoherent outcome is unrepresentable")
    class Coherence {

        @Test
        @DisplayName("TC-AD-OUTCOME-011 a settled improvement past an open guard cannot be built")
        void settledImprovementPastAnOpenGuardCannotBeBuilt() {
            assertThatThrownBy(() -> new OutcomeEvaluation(Stage.SETTLED, Verdict.IMPROVED,
                    GuardState.SALES_TOO_RECENT, new BigDecimal("0.40"), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot outrun");
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-012 a verdict and its reasons must agree")
        void verdictAndReasonsMustAgree() {
            assertThatThrownBy(() -> new OutcomeEvaluation(Stage.OPERATIONAL, Verdict.IMPROVED,
                    GuardState.NOT_APPLICABLE, new BigDecimal("0.40"),
                    List.of("BASELINE_UNAVAILABLE")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new OutcomeEvaluation(Stage.OPERATIONAL,
                    Verdict.INDETERMINATE, GuardState.NOT_APPLICABLE, null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("TC-AD-OUTCOME-013 a threshold of zero would call every measurement a result")
        void zeroThresholdIsRefused() {
            assertThatThrownBy(() -> new OutcomePlan(OutcomeMeasure.AD_SPEND, BigDecimal.ZERO,
                    new BigDecimal("0.05"), 100))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
