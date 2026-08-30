package com.mimococo.marketops.operationsworkflow.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.analyticsdecision.ConfidenceState;
import com.mimococo.marketops.analyticsdecision.DecisionFreshness;
import com.mimococo.marketops.analyticsdecision.FeeFamily;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricValueView;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.SubjectKind;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsProfile;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsResolution;
import com.mimococo.marketops.operationsworkflow.GuardrailReason;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailInput;
import com.mimococo.marketops.operationsworkflow.internal.domain.GuardrailOutcome;
import com.mimococo.marketops.operationsworkflow.internal.domain.PolicyLimits;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rules that decide whether a real price may change.
 *
 * <p>Asserted against the engine directly because it is a pure function: the
 * same facts must produce the same verdict on any day in any environment, which
 * is what lets a refusal be re-derived when somebody disputes it a month later.
 *
 * <p>The cases are written around the two mistakes an automated pricing system
 * makes. It sells below cost because a missing input was treated as a zero, and
 * it disrupts a listing because several individually acceptable changes added
 * up. Both have their own group here.
 */
class GuardrailEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-27T09:00:00Z");
    private static final UUID POLICY_ID = UUID.fromString(
            "44444444-4444-4444-8444-444444444444");

    @Nested
    @DisplayName("TC-GUARD-001 a proposal is only decided under rules that exist")
    class PolicyPresence {

        @Test
        void noPolicyInForceBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().policy(null).build());

            assertThat(outcome.passed()).isFalse();
            assertThat(outcome.reasons()).contains(GuardrailReason.NO_POLICY_IN_FORCE);
        }

        @Test
        void aPolicyMissingARequiredLimitBlocks() {
            PolicyLimits incomplete = new PolicyLimits(POLICY_ID, 1, "RUB", "GROWTH",
                    Map.of("MIN_DATA_COMPLETENESS", rate("0.8")), Map.of(), Map.of(),
                    Map.of());

            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().policy(incomplete).build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.POLICY_LIMIT_NOT_CONFIGURED);
            assertThat(outcome.detail()).containsKey("missingLimit");
        }

        @Test
        void aCompletePolicyOnGoodFactsPasses() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(input().build());

            assertThat(outcome.reasons()).isEmpty();
            assertThat(outcome.passed()).isTrue();
        }
    }

    @Nested
    @DisplayName("TC-GUARD-002 absence never becomes an acceptable default")
    class MissingInputs {

        @Test
        void aMissingUnitCostBlocksRatherThanCountingAsZero() {
            Map<MetricCode, MetricValueView> metrics = defaultMetrics();
            metrics.remove(MetricCode.UNIT_COST);

            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().metrics(metrics).build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.REQUIRED_METRIC_UNAVAILABLE);
            assertThat(outcome.detail()).containsEntry("unavailableMetrics", "UNIT_COST");
        }

        @Test
        void aMetricThatIsNotAvailableIsTreatedAsMissing() {
            Map<MetricCode, MetricValueView> metrics = defaultMetrics();
            metrics.put(MetricCode.REQUIRED_PROFIT_PER_UNIT,
                    unavailable(MetricCode.REQUIRED_PROFIT_PER_UNIT));

            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().metrics(metrics).build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.REQUIRED_METRIC_UNAVAILABLE);
        }

        @Test
        void aMetricThatIsOnlyEstimatedBlocksAWrite() {
            Map<MetricCode, MetricValueView> metrics = defaultMetrics();
            metrics.put(MetricCode.UNIT_COST, value(MetricCode.UNIT_COST, "60.0000",
                    ConfidenceState.ESTIMATED_EXPLAINED, 3_600L));

            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().metrics(metrics).build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.METRIC_CONFIDENCE_INSUFFICIENT);
            assertThat(outcome.detail())
                    .containsEntry("lowConfidenceMetrics", "UNIT_COST=ESTIMATED_EXPLAINED");
        }

        @Test
        void staleInputsBlock() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().freshness(freshness(Duration.ofDays(3), null)).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.INPUT_TOO_STALE);
        }

        @Test
        void lowDataCompletenessBlocks() {
            Map<MetricCode, MetricValueView> metrics = defaultMetrics();
            metrics.put(MetricCode.DATA_COMPLETENESS,
                    value(MetricCode.DATA_COMPLETENESS, "0.4000",
                            ConfidenceState.CANONICAL_CONFIRMED, 60L));

            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().metrics(metrics).build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.DATA_COMPLETENESS_BELOW_MINIMUM);
        }

        @Test
        void missingFreshnessBlocks() {
            DecisionFreshness complete = freshness();
            Map<DecisionFreshness.Feed, DecisionFreshness.Watermark> withoutReturns =
                    new EnumMap<>(complete.watermarks());
            withoutReturns.remove(DecisionFreshness.Feed.RETURNS);

            GuardrailOutcome outcome = GuardrailEngine.evaluate(input().freshness(
                    new DecisionFreshness(withoutReturns, complete.requiredFeeds())).build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.INPUT_FRESHNESS_UNAVAILABLE);
        }

        @Test
        void currencyMismatchBlocks() {
            PriceEconomicsProfile rub = economics().profile();
            PriceEconomicsProfile usd = new PriceEconomicsProfile(rub.profileId(),
                    rub.profileVersion(), rub.organizationId(), rub.platformCode(),
                    rub.marketplaceAccountId(), rub.storeId(), rub.fulfillmentModeCode(),
                    "USD", rub.effectiveFrom(), rub.effectiveTo(), rub.verificationState(),
                    rub.verifiedAt(), rub.verificationExpiresAt(), rub.evidenceReference(),
                    rub.minimumSupportedPrice(), rub.maximumSupportedPrice(),
                    rub.familyApplicability(), rub.components());

            GuardrailOutcome outcome = GuardrailEngine.evaluate(input().economics(
                    new PriceEconomicsResolution(PriceEconomicsResolution.Status.AVAILABLE,
                            usd, "currency-mutation")).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.CURRENCY_MISMATCH);
        }

        @Test
        void aWholeWindowAggregateCannotReplacePerUnitEconomics() {
            Map<MetricCode, MetricValueView> metrics = defaultMetrics();
            metrics.put(MetricCode.PLATFORM_FEES,
                    value(MetricCode.PLATFORM_FEES, "150.0000",
                            ConfidenceState.CANONICAL_CONFIRMED, 600L));

            GuardrailOutcome outcome = GuardrailEngine.evaluate(input().metrics(metrics)
                    .economics(PriceEconomicsResolution.unavailable(
                            PriceEconomicsResolution.Status.MISSING,
                            "historical-aggregate-is-not-a-profile")).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.ECONOMICS_PROFILE_MISSING);
        }

        @Test
        void expiredAmbiguousAndUnverifiedProfilesEachFailClosed() {
            Map<PriceEconomicsResolution.Status, GuardrailReason> cases = Map.of(
                    PriceEconomicsResolution.Status.EXPIRED,
                    GuardrailReason.ECONOMICS_PROFILE_EXPIRED,
                    PriceEconomicsResolution.Status.AMBIGUOUS,
                    GuardrailReason.ECONOMICS_PROFILE_AMBIGUOUS,
                    PriceEconomicsResolution.Status.UNVERIFIED,
                    GuardrailReason.ECONOMICS_PROFILE_UNVERIFIED);

            cases.forEach((status, reason) -> {
                GuardrailOutcome outcome = GuardrailEngine.evaluate(input().economics(
                        PriceEconomicsResolution.unavailable(status,
                                "synthetic:" + status)).build());

                assertThat(outcome.passed()).as(status.name()).isFalse();
                assertThat(outcome.reasons()).as(status.name()).contains(reason);
            });
        }

        @Test
        void removingAnyRequiredCostOrSafetyInputBreaksTheWriteGradePath() {
            List<MetricCode> requiredEconomics = List.of(
                    MetricCode.UNIT_COST,
                    MetricCode.REQUIRED_PROFIT_PER_UNIT,
                    MetricCode.SAFETY_BUFFER_PER_UNIT);

            for (MetricCode removed : requiredEconomics) {
                Map<MetricCode, MetricValueView> metrics = defaultMetrics();
                metrics.remove(removed);

                GuardrailOutcome outcome = GuardrailEngine.evaluate(
                        input().metrics(metrics).build());

                assertThat(outcome.passed()).as("removed %s", removed).isFalse();
                assertThat(outcome.reasons()).as("removed %s", removed)
                        .contains(GuardrailReason.REQUIRED_METRIC_UNAVAILABLE);
            }
        }
    }

    @Nested
    @DisplayName("TC-GUARD-003 a price may not be taken below what it costs")
    class ProfitFloors {

        @Test
        void aPriceUnderBreakEvenBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().proposedPrice("70.0000").build());

            assertThat(outcome.reasons()).contains(GuardrailReason.BELOW_BREAK_EVEN);
        }

        @Test
        void aPriceLeavingTooLittleUnitProfitBlocks() {
            // A floor stated in money rather than as a proportion. A small
            // change can satisfy every rate limit and still leave a unit
            // earning less than the business is willing to sell it for.
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().policy(policyWithUnitProfitFloor("30.0000"))
                            .proposedPrice("104.0000").build());

            assertThat(outcome.reasons())
                    .containsExactly(GuardrailReason.UNIT_PROFIT_BELOW_MINIMUM);
        }

        @Test
        void aPriceLeavingTooLittleMarginBlocks() {
            PolicyLimits strictMargin = defaultPolicy(Map.of(
                    "MIN_CONTRIBUTION_MARGIN", rate("0.400000")));

            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().policy(strictMargin).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.MARGIN_BELOW_MINIMUM);
        }

        @Test
        void theProjectionIsComputedEvenWhenTheVerdictBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().proposedPrice("70.0000").build());

            assertThat(outcome.passed()).isFalse();
            assertThat(outcome.projectedUnitProfit()).isEqualByComparingTo("-6.0000");
            assertThat(outcome.currentUnitProfit()).isEqualByComparingTo("24.0000");
        }
    }

    @Nested
    @DisplayName("TC-GUARD-004 a listing is not disrupted by accumulation")
    class ChangeSize {

        @Test
        void aSingleChangeBeyondTheLimitBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().proposedPrice("140.0000").build());

            assertThat(outcome.reasons()).contains(GuardrailReason.SINGLE_CHANGE_TOO_LARGE);
        }

        @Test
        void aCutIsMeasuredByMagnitudeJustAsARiseIs() {
            GuardrailOutcome rise = GuardrailEngine.evaluate(
                    input().proposedPrice("140.0000").build());
            GuardrailOutcome cut = GuardrailEngine.evaluate(
                    input().proposedPrice("88.0000").currentPrice("200.0000").build());

            assertThat(rise.reasons()).contains(GuardrailReason.SINGLE_CHANGE_TOO_LARGE);
            assertThat(cut.reasons()).contains(GuardrailReason.SINGLE_CHANGE_TOO_LARGE);
        }

        @Test
        void severalAcceptableChangesMayNotAddUpPastTheDailyBound() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().proposedPrice("108.0000").cumulativeDailyChangeRate("0.180000")
                            .build());

            assertThat(outcome.reasons()).contains(GuardrailReason.DAILY_CHANGE_EXCEEDED);
            assertThat(outcome.detail()).containsEntry("cumulativeDailyChangeRate", "0.260000");
        }

        @Test
        void aRecentChangeMustBeLeftAloneLongEnoughToObserve() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().lastChangeAt(NOW.minus(Duration.ofHours(2))).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.COOLDOWN_ACTIVE);
            assertThat(outcome.detail()).containsEntry("secondsSinceLastChange", "7200");
        }

        @Test
        void anOldChangeDoesNotBlock() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().lastChangeAt(NOW.minus(Duration.ofDays(3))).build());

            assertThat(outcome.reasons()).doesNotContain(GuardrailReason.COOLDOWN_ACTIVE);
        }
    }

    @Nested
    @DisplayName("TC-GUARD-005 the case must still be about this listing, now")
    class Applicability {

        @Test
        void anUnresolvedMappingBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().mappingResolved(false).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.MAPPING_UNRESOLVED);
        }

        @Test
        void anOpenMappingConflictBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().mappingConflictOpen(true).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.MAPPING_CONFLICT_OPEN);
        }

        @Test
        void aBlockingDiagnosisBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().diagnosisBlocksExecution(true).build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.DIAGNOSIS_BLOCKS_EXECUTION);
        }

        @Test
        void factsThatMovedSinceTheCaseWasBuiltBlock() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().entityVersionMatches(false).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.ENTITY_VERSION_CHANGED);
        }

        @Test
        void anElapsedProposalBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().recommendationValid(false).build());

            assertThat(outcome.reasons()).contains(GuardrailReason.RECOMMENDATION_EXPIRED);
        }

        @Test
        void tooLittleStockBlocks() {
            Map<MetricCode, MetricValueView> metrics = defaultMetrics();
            metrics.put(MetricCode.PLATFORM_AVAILABLE_UNITS,
                    value(MetricCode.PLATFORM_AVAILABLE_UNITS, "2",
                            ConfidenceState.CANONICAL_CONFIRMED, 60L));

            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().metrics(metrics).build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.INVENTORY_BELOW_MINIMUM);
        }


        @Test
        void missingStockBlocks() {
            Map<MetricCode, MetricValueView> metrics = defaultMetrics();
            metrics.remove(MetricCode.PLATFORM_AVAILABLE_UNITS);

            GuardrailOutcome outcome = GuardrailEngine.evaluate(input().metrics(metrics).build());

            assertThat(outcome.reasons()).contains(
                    GuardrailReason.REQUIRED_METRIC_UNAVAILABLE,
                    GuardrailReason.INVENTORY_EVIDENCE_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("TC-GUARD-006 a standing authorization bounds what it can be spent on")
    class AuthorizationBound {

        @Test
        void aChangeLargerThanTheBoundBlocks() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().proposedPrice("110.0000").authorizationMaxChangeRate("0.050000")
                            .build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.CHANGE_EXCEEDS_POLICY_AUTHORIZATION);
        }

        @Test
        void aChangeWithinTheBoundPasses() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().proposedPrice("103.0000").authorizationMaxChangeRate("0.050000")
                            .build());

            assertThat(outcome.reasons()).isEmpty();
        }

        @Test
        void anUnmeasurableChangeCannotSpendAnAuthorization() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().currentPriceAbsent().authorizationMaxChangeRate("0.050000")
                            .build());

            assertThat(outcome.reasons())
                    .contains(GuardrailReason.CHANGE_EXCEEDS_POLICY_AUTHORIZATION);
        }
    }

    @Nested
    @DisplayName("TC-GUARD-007 every blocking condition is reported at once")
    class Completeness {

        @Test
        void aProposalThatFailsSeveralWaysNamesAllOfThem() {
            GuardrailOutcome outcome = GuardrailEngine.evaluate(
                    input().proposedPrice("60.0000")
                            .mappingResolved(false)
                            .diagnosisBlocksExecution(true)
                            .lastChangeAt(NOW.minus(Duration.ofMinutes(10)))
                            .build());

            assertThat(outcome.reasons()).contains(
                    GuardrailReason.MAPPING_UNRESOLVED,
                    GuardrailReason.DIAGNOSIS_BLOCKS_EXECUTION,
                    GuardrailReason.BELOW_BREAK_EVEN,
                    GuardrailReason.SINGLE_CHANGE_TOO_LARGE,
                    GuardrailReason.COOLDOWN_ACTIVE);
        }

        @Test
        void theSameInputAlwaysProducesTheSameVerdict() {
            GuardrailInput once = input().proposedPrice("70.0000").build();

            assertThat(GuardrailEngine.evaluate(once).reasons())
                    .isEqualTo(GuardrailEngine.evaluate(once).reasons());
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /**
     * A proposal that passes.
     *
     * <p>Deliberately a passing case rather than a blocking one, so every test
     * changes exactly one thing and the reason it blocks is the thing it
     * changed.
     */
    private static Builder input() {
        return new Builder();
    }

    private static PolicyLimits defaultPolicy(Map<String, BigDecimal> rateOverrides) {
        Map<String, BigDecimal> rates = new java.util.HashMap<>(Map.of(
                "MIN_DATA_COMPLETENESS", rate("0.700000"),
                "MIN_CONTRIBUTION_MARGIN", rate("0.100000"),
                "MAX_SINGLE_CHANGE_RATE", rate("0.150000"),
                "MAX_DAILY_CHANGE_RATE", rate("0.200000")));
        rates.putAll(rateOverrides);
        return new PolicyLimits(POLICY_ID, 1, "RUB", "GROWTH", rates,
                Map.of("MIN_UNIT_CONTRIBUTION_PROFIT", new BigDecimal("5.0000")),
                Map.of("MIN_AVAILABLE_UNITS", 5),
                Map.of("MAX_INPUT_AGE_SECONDS", 86_400L, "COOLDOWN_SECONDS", 43_200L));
    }

    /** The same policy with a money floor high enough to bind on its own. */
    private static PolicyLimits policyWithUnitProfitFloor(String amount) {
        PolicyLimits base = defaultPolicy(Map.of());
        return new PolicyLimits(base.policyId(), base.policyVersion(), base.currencyCode(),
                base.lifecycleObjective(), base.rates(),
                Map.of("MIN_UNIT_CONTRIBUTION_PROFIT", new BigDecimal(amount)),
                base.counts(), base.durations());
    }

    private static Map<MetricCode, MetricValueView> defaultMetrics() {
        Map<MetricCode, MetricValueView> metrics = new EnumMap<>(MetricCode.class);
        metrics.put(MetricCode.OBSERVED_SELLING_PRICE,
                value(MetricCode.OBSERVED_SELLING_PRICE, "100.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.UNIT_COST,
                value(MetricCode.UNIT_COST, "60.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.PLATFORM_FEES_PER_UNIT,
                value(MetricCode.PLATFORM_FEES_PER_UNIT, "10.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.RETURN_LOSS_PER_UNIT,
                value(MetricCode.RETURN_LOSS_PER_UNIT, "2.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.AD_SPEND_PER_UNIT,
                value(MetricCode.AD_SPEND_PER_UNIT, "3.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.VARIABLE_TAX_PER_UNIT,
                value(MetricCode.VARIABLE_TAX_PER_UNIT, "1.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.REQUIRED_PROFIT_PER_UNIT,
                value(MetricCode.REQUIRED_PROFIT_PER_UNIT, "5.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.SAFETY_BUFFER_PER_UNIT,
                value(MetricCode.SAFETY_BUFFER_PER_UNIT, "2.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.BREAK_EVEN_PRICE,
                value(MetricCode.BREAK_EVEN_PRICE, "76.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.MINIMUM_PRICE,
                value(MetricCode.MINIMUM_PRICE, "83.0000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.DATA_COMPLETENESS,
                value(MetricCode.DATA_COMPLETENESS, "0.9000",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        metrics.put(MetricCode.PLATFORM_AVAILABLE_UNITS,
                value(MetricCode.PLATFORM_AVAILABLE_UNITS, "40",
                        ConfidenceState.CANONICAL_CONFIRMED, 600L));
        return metrics;
    }

    private static MetricValueView value(MetricCode code, String amount,
                                         ConfidenceState confidence, Long freshnessSeconds) {
        return value(code, amount, "RUB", confidence, freshnessSeconds);
    }

    private static MetricValueView value(MetricCode code, String amount, String currency,
                                         ConfidenceState confidence, Long freshnessSeconds) {
        return new MetricValueView(UUID.randomUUID(), code, 2,
                SubjectKind.PLATFORM_LISTING_VARIANT, UUID.randomUUID(), MetricWindow.D30,
                NOW.minus(Duration.ofDays(30)), NOW, ValueState.AVAILABLE,
                new BigDecimal(amount), currency, confidence, false,
                NOW.minus(Duration.ofDays(30)), freshnessSeconds, "digest-" + code.name(),
                NOW, List.of());
    }

    private static MetricValueView unavailable(MetricCode code) {
        return new MetricValueView(UUID.randomUUID(), code, 2,
                SubjectKind.PLATFORM_LISTING_VARIANT, UUID.randomUUID(), MetricWindow.D30,
                NOW.minus(Duration.ofDays(30)), NOW, ValueState.NOT_AVAILABLE, null, null,
                ConfidenceState.INCOMPLETE, false, null, null, "digest-" + code.name(), NOW,
                List.of());
    }

    private static BigDecimal rate(String value) {
        return new BigDecimal(value);
    }

    private static PriceEconomicsResolution economics() {
        UUID profileId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        Map<FeeFamily, PriceEconomicsProfile.Applicability> families =
                new EnumMap<>(FeeFamily.class);
        for (FeeFamily family : FeeFamily.values()) {
            families.put(family, PriceEconomicsProfile.Applicability.REQUIRED);
        }
        List<PriceEconomicsProfile.Component> components = List.of(
                component("10000000-0000-4000-8000-000000000001", "COMMISSION",
                        FeeFamily.COMMISSION, "10.0000"),
                component("10000000-0000-4000-8000-000000000002", "FULFILLMENT",
                        FeeFamily.FULFILLMENT_DELIVERY, "1.0000"),
                component("10000000-0000-4000-8000-000000000003", "STORAGE",
                        FeeFamily.STORAGE, "0.0000"),
                component("10000000-0000-4000-8000-000000000004", "PROMOTION",
                        FeeFamily.PROMOTION, "0.0000"),
                component("10000000-0000-4000-8000-000000000005", "OTHER_VARIABLE",
                        FeeFamily.OTHER_VARIABLE, "0.0000"),
                component("10000000-0000-4000-8000-000000000006", "RETURN_LOSS",
                        FeeFamily.RETURN_LOSS, "2.0000"),
                component("10000000-0000-4000-8000-000000000007", "ADVERTISING",
                        FeeFamily.ADVERTISING, "2.0000"),
                component("10000000-0000-4000-8000-000000000008", "VARIABLE_TAX",
                        FeeFamily.VARIABLE_TAX, "1.0000"));
        PriceEconomicsProfile profile = new PriceEconomicsProfile(profileId, 1,
                UUID.randomUUID(), "OZON", UUID.randomUUID(), UUID.randomUUID(),
                "MARKETPLACE_FULFILLED", "RUB", NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(1)),
                PriceEconomicsProfile.VerificationState.ENGINEERING_VERIFIED,
                NOW.minus(Duration.ofMinutes(10)), NOW.plus(Duration.ofDays(1)),
                "synthetic:guardrail-engine", new BigDecimal("1.0000"),
                new BigDecimal("1000.0000"), families, components);
        return new PriceEconomicsResolution(PriceEconomicsResolution.Status.AVAILABLE,
                profile, "synthetic-current-profile");
    }

    private static PriceEconomicsProfile.Component component(String id, String code,
                                                              FeeFamily family,
                                                              String amount) {
        return new PriceEconomicsProfile.Component(UUID.fromString(id), code, family,
                PriceEconomicsProfile.ComponentKind.FIXED, new BigDecimal(amount), null,
                null, null, "synthetic:" + code);
    }

    private static DecisionFreshness freshness() {
        return freshness(Duration.ofMinutes(10), null);
    }

    private static DecisionFreshness freshness(Duration age,
                                                DecisionFreshness.Feed reconciledFeed) {
        Map<DecisionFreshness.Feed, DecisionFreshness.Watermark> watermarks =
                new EnumMap<>(DecisionFreshness.Feed.class);
        for (DecisionFreshness.Feed feed : DecisionFreshness.Feed.values()) {
            watermarks.put(feed, new DecisionFreshness.Watermark(UUID.randomUUID(), feed,
                    NOW.minus(age), NOW.minus(age).plusSeconds(30),
                    feed == reconciledFeed ? NOW.minus(age).plusSeconds(60) : null,
                    "synthetic:" + feed));
        }
        return new DecisionFreshness(watermarks,
                List.of(DecisionFreshness.Feed.values()));
    }

    /** Builds one guardrail input, starting from a case that passes. */
    private static final class Builder {

        private PolicyLimits policy = defaultPolicy(Map.of());
        private Map<MetricCode, MetricValueView> metrics = defaultMetrics();
        private BigDecimal currentPrice = new BigDecimal("100.0000");
        private String currentPriceCurrency = "RUB";
        private BigDecimal proposedPrice = new BigDecimal("105.0000");
        private PriceEconomicsResolution economics = GuardrailEngineTest.economics();
        private DecisionFreshness freshness = GuardrailEngineTest.freshness();
        private BigDecimal cumulativeDailyChangeRate = BigDecimal.ZERO;
        private Instant lastChangeAt;
        private boolean mappingResolved = true;
        private boolean mappingConflictOpen;
        private boolean diagnosisBlocksExecution;
        private boolean entityVersionMatches = true;
        private boolean recommendationValid = true;
        private BigDecimal authorizationMaxChangeRate;

        Builder policy(PolicyLimits value) {
            this.policy = value;
            return this;
        }

        Builder metrics(Map<MetricCode, MetricValueView> value) {
            this.metrics = value;
            return this;
        }

        Builder economics(PriceEconomicsResolution value) {
            this.economics = value;
            return this;
        }

        Builder freshness(DecisionFreshness value) {
            this.freshness = value;
            return this;
        }

        Builder currentPrice(String value) {
            this.currentPrice = new BigDecimal(value);
            return this;
        }

        Builder currentPriceAbsent() {
            this.currentPrice = null;
            this.currentPriceCurrency = null;
            Map<MetricCode, MetricValueView> without = new EnumMap<>(this.metrics);
            without.remove(MetricCode.OBSERVED_SELLING_PRICE);
            this.metrics = without;
            return this;
        }

        Builder proposedPrice(String value) {
            this.proposedPrice = new BigDecimal(value);
            return this;
        }

        Builder cumulativeDailyChangeRate(String value) {
            this.cumulativeDailyChangeRate = new BigDecimal(value);
            return this;
        }

        Builder lastChangeAt(Instant value) {
            this.lastChangeAt = value;
            return this;
        }

        Builder mappingResolved(boolean value) {
            this.mappingResolved = value;
            return this;
        }

        Builder mappingConflictOpen(boolean value) {
            this.mappingConflictOpen = value;
            return this;
        }

        Builder diagnosisBlocksExecution(boolean value) {
            this.diagnosisBlocksExecution = value;
            return this;
        }

        Builder entityVersionMatches(boolean value) {
            this.entityVersionMatches = value;
            return this;
        }

        Builder recommendationValid(boolean value) {
            this.recommendationValid = value;
            return this;
        }

        Builder authorizationMaxChangeRate(String value) {
            this.authorizationMaxChangeRate = new BigDecimal(value);
            return this;
        }

        GuardrailInput build() {
            return new GuardrailInput(policy, metrics, currentPrice, currentPriceCurrency,
                    proposedPrice, "MARKETPLACE_FULFILLED", economics, freshness,
                    cumulativeDailyChangeRate, lastChangeAt, NOW, mappingResolved,
                    mappingConflictOpen, diagnosisBlocksExecution, entityVersionMatches,
                    recommendationValid, authorizationMaxChangeRate);
        }
    }
}
