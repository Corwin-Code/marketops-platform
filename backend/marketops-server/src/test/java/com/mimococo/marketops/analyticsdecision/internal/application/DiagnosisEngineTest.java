package com.mimococo.marketops.analyticsdecision.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.DiagnosisFindingView;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.analyticsdecision.internal.config.AnalyticsProperties;
import com.mimococo.marketops.analyticsdecision.internal.domain.ComputedMetric;
import com.mimococo.marketops.analyticsdecision.internal.domain.RuleOutcome;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules that decide what an operator is told to look at.
 *
 * <p>Asserted against the engine directly because it is a pure function of the
 * values it is handed. The properties that matter are about the whole rule set
 * rather than any one rule: every rule answers, the order never changes, and a
 * rule that cannot answer says so instead of staying silent.
 *
 * <p>Silence is the failure this guards against. A rule that quietly returns
 * nothing when its input is missing looks exactly like a rule that ran and
 * found nothing wrong, and an operator reading a clean screen has no way to
 * tell a healthy listing from one nobody could assess.
 */
class DiagnosisEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-27T09:00:00Z");

    /** The nine rules, in the order the product evaluates them. */
    private static final List<String> RULES_IN_ORDER = List.of(
            "DATA_BLOCKED", "NEGATIVE_MARGIN", "STOCKOUT_RISK", "HIGH_RETURN",
            "LOW_IMPRESSION", "LOW_CLICK_THROUGH", "LOW_CONVERSION",
            "ADVERTISING_INEFFICIENT", "PRICE_BELOW_MINIMUM");

    private final DiagnosisEngine engine = new DiagnosisEngine(new AnalyticsProperties());

    @Nested
    @DisplayName("TC-RULE-001 every rule answers, in the same order, every time")
    class Wholeness {

        @Test
        void aHealthySubjectStillProducesAnOutcomeForEveryRule() {
            List<RuleOutcome> outcomes = engine.evaluate(healthy());

            assertThat(outcomes.stream().map(RuleOutcome::ruleCode).toList())
                    .isEqualTo(RULES_IN_ORDER);
        }

        @Test
        void aSubjectWithNoMetricsAtAllStillProducesAnOutcomeForEveryRule() {
            List<RuleOutcome> outcomes = engine.evaluate(Map.of());

            assertThat(outcomes.stream().map(RuleOutcome::ruleCode).toList())
                    .isEqualTo(RULES_IN_ORDER);
        }

        @Test
        void noRuleEverReturnsWithoutAnOutcome() {
            engine.evaluate(Map.of())
                    .forEach(outcome -> assertThat(outcome.outcome()).isNotNull());
        }

        @Test
        void everyDeclineSaysWhy() {
            engine.evaluate(Map.of()).stream()
                    .filter(outcome ->
                            outcome.outcome() == DiagnosisFindingView.Outcome.DECLINED)
                    .forEach(outcome -> assertThat(outcome.declineReason()).isNotBlank());
        }

        @Test
        void everyTriggeredFindingCarriesASeverity() {
            engine.evaluate(negativeMargin()).stream()
                    .filter(outcome ->
                            outcome.outcome() == DiagnosisFindingView.Outcome.TRIGGERED)
                    .forEach(outcome -> assertThat(outcome.severity()).isNotNull());
        }
    }

    @Nested
    @DisplayName("TC-RULE-002 a blocked subject is not assessed, and says so")
    class DataBlocked {

        @Test
        void tooLittleOfThePictureBlocksEverythingDownstream() {
            Map<MetricCode, ComputedMetric> metrics = healthy();
            metrics.put(MetricCode.DATA_COMPLETENESS,
                    value(MetricCode.DATA_COMPLETENESS, "0.1000"));

            List<RuleOutcome> outcomes = engine.evaluate(metrics);

            RuleOutcome blocked = outcomes.getFirst();
            assertThat(blocked.ruleCode()).isEqualTo("DATA_BLOCKED");
            assertThat(blocked.outcome()).isEqualTo(DiagnosisFindingView.Outcome.TRIGGERED);
            assertThat(outcomes.stream().skip(1))
                    .allMatch(outcome ->
                            outcome.outcome() == DiagnosisFindingView.Outcome.DECLINED);
        }

        @Test
        void aBlockedSubjectNamesTheEarlierRuleRatherThanTheMissingField() {
            // An operator reading a declined rule needs to know it was not
            // assessed, not to rediscover the same finding eight more times.
            Map<MetricCode, ComputedMetric> metrics = healthy();
            metrics.put(MetricCode.DATA_COMPLETENESS,
                    value(MetricCode.DATA_COMPLETENESS, "0.1000"));

            engine.evaluate(metrics).stream().skip(1)
                    .forEach(outcome -> assertThat(outcome.declineReason())
                            .isEqualTo("BLOCKED_BY_EARLIER_RULE"));
        }

        @Test
        void aSubjectWithNothingMeasuredCannotEvenBeCalledBlocked() {
            // DATA_BLOCKED needs the completeness figure in order to say the
            // data is insufficient. Without it the rule declines like any
            // other, and each downstream rule then names its own gap — which
            // tells an operator more than nine copies of one sentence.
            List<RuleOutcome> outcomes = engine.evaluate(Map.of());

            assertThat(outcomes.getFirst().outcome())
                    .isEqualTo(DiagnosisFindingView.Outcome.DECLINED);
            assertThat(outcomes.getFirst().declineReason())
                    .isEqualTo("REQUIRED_METRIC_UNAVAILABLE");
            assertThat(outcomes).allMatch(outcome ->
                    outcome.outcome() == DiagnosisFindingView.Outcome.DECLINED);
        }

        @Test
        void aCompleteSubjectIsNotBlocked() {
            RuleOutcome blocked = engine.evaluate(healthy()).getFirst();

            assertThat(blocked.outcome()).isEqualTo(DiagnosisFindingView.Outcome.CLEAR);
        }
    }

    @Nested
    @DisplayName("TC-RULE-003 the rules find what they are for")
    class Conditions {

        @Test
        void aUnitLosingMoneyIsCritical() {
            RuleOutcome margin = ruleOf(engine.evaluate(negativeMargin()), "NEGATIVE_MARGIN");

            assertThat(margin.outcome()).isEqualTo(DiagnosisFindingView.Outcome.TRIGGERED);
            assertThat(margin.severity()).isEqualTo(DiagnosisFindingView.Severity.CRITICAL);
        }

        @Test
        void aPriceUnderBreakEvenIsFound() {
            Map<MetricCode, ComputedMetric> metrics = healthy();
            metrics.put(MetricCode.OBSERVED_SELLING_PRICE,
                    value(MetricCode.OBSERVED_SELLING_PRICE, "70.0000"));

            RuleOutcome below = ruleOf(engine.evaluate(metrics), "PRICE_BELOW_MINIMUM");

            assertThat(below.outcome()).isEqualTo(DiagnosisFindingView.Outcome.TRIGGERED);
        }

        @Test
        void tooLittleStockCoverIsFound() {
            Map<MetricCode, ComputedMetric> metrics = healthy();
            metrics.put(MetricCode.STOCK_COVER_DAYS,
                    value(MetricCode.STOCK_COVER_DAYS, "1.0000"));

            RuleOutcome stockout = ruleOf(engine.evaluate(metrics), "STOCKOUT_RISK");

            assertThat(stockout.outcome()).isEqualTo(DiagnosisFindingView.Outcome.TRIGGERED);
        }

        @Test
        void aHighReturnRateIsFound() {
            Map<MetricCode, ComputedMetric> metrics = healthy();
            metrics.put(MetricCode.RETURN_RATE, value(MetricCode.RETURN_RATE, "0.4000"));

            RuleOutcome returns = ruleOf(engine.evaluate(metrics), "HIGH_RETURN");

            assertThat(returns.outcome()).isEqualTo(DiagnosisFindingView.Outcome.TRIGGERED);
        }

        @Test
        void aHealthySubjectTriggersNothing() {
            assertThat(engine.evaluate(healthy()))
                    .allMatch(outcome ->
                            outcome.outcome() == DiagnosisFindingView.Outcome.CLEAR);
        }
    }

    @Nested
    @DisplayName("TC-RULE-004 a rule declines rather than guessing")
    class Declining {

        @Test
        void aMissingInputForOneRuleDoesNotSilenceTheOthers() {
            Map<MetricCode, ComputedMetric> metrics = healthy();
            metrics.put(MetricCode.RETURN_RATE, unavailable(MetricCode.RETURN_RATE));

            List<RuleOutcome> outcomes = engine.evaluate(metrics);

            assertThat(ruleOf(outcomes, "HIGH_RETURN").outcome())
                    .isEqualTo(DiagnosisFindingView.Outcome.DECLINED);
            assertThat(ruleOf(outcomes, "NEGATIVE_MARGIN").outcome())
                    .isEqualTo(DiagnosisFindingView.Outcome.CLEAR);
        }

        @Test
        void aDeclinedRuleReadsNoMetrics() {
            engine.evaluate(Map.of()).stream().skip(1)
                    .forEach(outcome -> assertThat(outcome.readMetrics()).isEmpty());
        }
    }

    @Nested
    @DisplayName("TC-RULE-005 the same values produce the same answer")
    class Determinism {

        @Test
        void twoEvaluationsAgree() {
            Map<MetricCode, ComputedMetric> metrics = negativeMargin();

            assertThat(engine.evaluate(metrics)).isEqualTo(engine.evaluate(metrics));
        }

        @Test
        void theRuleVersionIsStated() {
            assertThat(DiagnosisEngine.RULE_VERSION).isEqualTo(1);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------

    private static RuleOutcome ruleOf(List<RuleOutcome> outcomes, String ruleCode) {
        return outcomes.stream()
                .filter(outcome -> outcome.ruleCode().equals(ruleCode))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for " + ruleCode));
    }

    /** A subject with nothing wrong with it. */
    private static Map<MetricCode, ComputedMetric> healthy() {
        Map<MetricCode, ComputedMetric> metrics = new EnumMap<>(MetricCode.class);
        metrics.put(MetricCode.DATA_COMPLETENESS, value(MetricCode.DATA_COMPLETENESS, "1.0000"));
        metrics.put(MetricCode.OBSERVED_SELLING_PRICE,
                value(MetricCode.OBSERVED_SELLING_PRICE, "100.0000"));
        metrics.put(MetricCode.MINIMUM_PRICE, value(MetricCode.MINIMUM_PRICE, "75.0000"));
        metrics.put(MetricCode.UNIT_COST, value(MetricCode.UNIT_COST, "60.0000"));
        metrics.put(MetricCode.PLATFORM_FEES, value(MetricCode.PLATFORM_FEES, "15.0000"));
        metrics.put(MetricCode.CONTRIBUTION_MARGIN,
                value(MetricCode.CONTRIBUTION_MARGIN, "0.2500"));
        metrics.put(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                value(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, "375.0000"));
        metrics.put(MetricCode.STOCK_COVER_DAYS, value(MetricCode.STOCK_COVER_DAYS, "45.0000"));
        metrics.put(MetricCode.PLATFORM_AVAILABLE_UNITS,
                value(MetricCode.PLATFORM_AVAILABLE_UNITS, "40"));
        metrics.put(MetricCode.RETURN_RATE, value(MetricCode.RETURN_RATE, "0.0200"));
        metrics.put(MetricCode.RETURN_UNITS, value(MetricCode.RETURN_UNITS, "1"));
        metrics.put(MetricCode.IMPRESSIONS, value(MetricCode.IMPRESSIONS, "50000"));
        metrics.put(MetricCode.CLICKS, value(MetricCode.CLICKS, "2500"));
        metrics.put(MetricCode.CLICK_THROUGH_RATE,
                value(MetricCode.CLICK_THROUGH_RATE, "0.0500"));
        metrics.put(MetricCode.CONVERSION_RATE, value(MetricCode.CONVERSION_RATE, "0.0600"));
        metrics.put(MetricCode.COMPLETED_UNITS, value(MetricCode.COMPLETED_UNITS, "150"));
        metrics.put(MetricCode.COMPLETED_NET_SALES,
                value(MetricCode.COMPLETED_NET_SALES, "15000.0000"));
        metrics.put(MetricCode.AD_SPEND, value(MetricCode.AD_SPEND, "500.0000"));
        metrics.put(MetricCode.AD_COST_OF_SALE, value(MetricCode.AD_COST_OF_SALE, "0.0330"));
        return metrics;
    }

    private static Map<MetricCode, ComputedMetric> negativeMargin() {
        Map<MetricCode, ComputedMetric> metrics = healthy();
        metrics.put(MetricCode.CONTRIBUTION_MARGIN,
                value(MetricCode.CONTRIBUTION_MARGIN, "-0.1000"));
        metrics.put(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT,
                value(MetricCode.OPERATIONAL_CONTRIBUTION_PROFIT, "-500.0000"));
        return metrics;
    }

    private static ComputedMetric value(MetricCode code, String amount) {
        return new ComputedMetric(code, ValueState.AVAILABLE, new BigDecimal(amount), "RUB",
                ConfidenceState.CANONICAL_CONFIRMED, NOW.minus(Duration.ofHours(1)), List.of());
    }

    private static ComputedMetric unavailable(MetricCode code) {
        return new ComputedMetric(code, ValueState.NOT_AVAILABLE, null, null,
                ConfidenceState.INCOMPLETE, null, List.of());
    }
}
