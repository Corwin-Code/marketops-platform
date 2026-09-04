package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.BidDirection;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The advertising rules that have to hold for every input, not for the examples
 * somebody thought of.
 *
 * <p>The example tests beside this file pin the arithmetic of cases a person
 * chose. That leaves the interesting gap: a rule such as "no amount of money
 * outranks a higher band" is a claim about all inputs, and one worked example
 * cannot distinguish a rule that holds from a rule that happens to hold at the
 * value tried. Each group here therefore states one such claim and drives at
 * least five hundred generated inputs through it.
 *
 * <p>Generation is a {@link Random} built from a fixed literal seed rather than a
 * property-testing library, because none is available to this build. That trades
 * shrinking for reproducibility, which is the right way round here: a failure
 * re-runs identically from the seed, and every assertion carries the generated
 * inputs in its description, so the failure message names the exact case instead
 * of pointing at an iteration number.
 *
 * <p>Where a generator is deliberately shaped to make a candidate exist, the test
 * also counts how many it got. A property that quietly stopped generating the
 * situation it is about would otherwise pass forever while asserting nothing.
 */
class AdvertisingDomainPropertyTest {

    /** How many generated inputs each property runs. */
    private static final int CASES = 500;

    private static final String CURRENCY = "RUB";
    private static final String BID_UNIT = "CURRENCY_MAJOR";

    /** A value no generator produces, so a fallback that surfaces is visibly a fallback. */
    private static final BigDecimal SENTINEL = new BigDecimal("-999.9999");

    /** Every place in the queue's severity order, named the way the policy names it. */
    private static final List<Placement> PLACEMENTS = List.of(
            new Placement(AdvertisingLane.WATCH, null),
            new Placement(AdvertisingLane.OPTIMIZATION, null),
            new Placement(AdvertisingLane.DATA_REPAIR, null),
            new Placement(AdvertisingLane.PROTECTION, ProtectionTier.P3),
            new Placement(AdvertisingLane.PROTECTION, ProtectionTier.P2),
            new Placement(AdvertisingLane.PROTECTION, ProtectionTier.P1),
            new Placement(AdvertisingLane.PROTECTION, ProtectionTier.P0));

    /** The component codes a contribution profit names when one is missing. */
    private static final List<String> PROFIT_COMPONENTS = List.of(
            "ATTRIBUTABLE_NET_SALES", "UNIT_COST", "PLATFORM_FEES_PER_UNIT",
            "RETURN_LOSS_PER_UNIT", "PROMOTION_COST_PER_UNIT", "VARIABLE_TAX_PER_UNIT",
            "OFFICIAL_AD_SPEND");

    private static Random seeded(long seed) {
        return new Random(seed);
    }

    private static BigDecimal ratio(Random random, double lowest, double highest) {
        return BigDecimal.valueOf(lowest + random.nextDouble() * (highest - lowest))
                .setScale(5, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Random random, double magnitude) {
        return BigDecimal.valueOf(random.nextDouble() * magnitude)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** One generated bid situation, together with the text a failure prints. */
    private record BidScenario(
            ProviderBidGrid grid,
            AdMeasure currentBid,
            MaxCpc maxCpc,
            BidStepLimits limits,
            BigDecimal causeStepRatio,
            String description) {
    }

    /**
     * A platform grid with an awkward step, and a bid that sits exactly on it.
     *
     * <p>The bid is placed a few hundred steps above the minimum and the ceiling
     * is placed on the far side of it, so a candidate exists in essentially every
     * generated case. That is on purpose: a generator that mostly produced "no
     * candidate" would satisfy every invariant below without ever exercising one.
     * The step, minimum, maximum and precision still vary widely, including steps
     * that divide nothing and minimums that are not round numbers.
     */
    private static BidScenario bidScenario(Random random, boolean ceilingAbove) {
        int precision = 1 + random.nextInt(3);
        BigDecimal unit = BigDecimal.ONE.movePointLeft(precision);
        BigDecimal step = unit.multiply(BigDecimal.valueOf(1L + random.nextInt(37)));
        BigDecimal minimum = unit.multiply(BigDecimal.valueOf(1L + random.nextInt(20)));
        long stepsToCurrent = 200L + random.nextInt(600);
        BigDecimal current = minimum.add(step.multiply(BigDecimal.valueOf(stepsToCurrent)));
        BigDecimal maximum = minimum.add(step.multiply(
                BigDecimal.valueOf(stepsToCurrent + 50L + random.nextInt(2000))));
        BigDecimal ceilingFactor = ceilingAbove
                ? ratio(random, 1.30, 3.00)
                : ratio(random, 0.30, 0.85);
        BigDecimal headroom = ceilingAbove
                ? ratio(random, 0.00, 0.10)
                : ratio(random, 0.00, 0.20);
        BigDecimal relative = ratio(random, 0.10, 0.60);
        BigDecimal causeStepRatio = ratio(random, 0.05, 0.95);
        ProviderBidGrid grid = new ProviderBidGrid(BID_UNIT, CURRENCY, precision, step,
                minimum, maximum, true, "VERIFIED");
        MaxCpc maxCpc = new MaxCpc(SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE,
                Money.of(current.multiply(ceilingFactor), CURRENCY),
                AdEvidenceState.CANONICAL_CONFIRMED, MaxCpc.Absence.NONE);
        String description = ("step=%s minimum=%s maximum=%s current=%s ceiling=%s "
                + "relative=%s headroom=%s causeRatio=%s").formatted(step, minimum, maximum,
                current, maxCpc.ceiling().amount(), relative, headroom, causeStepRatio);
        return new BidScenario(grid,
                AdMeasure.available(current, AdEvidenceState.CANONICAL_CONFIRMED),
                maxCpc, new BidStepLimits(relative, current, headroom), causeStepRatio,
                description);
    }

    private static Optional<BidCandidate> decrease(BidScenario scenario) {
        return BidCandidate.decrease(scenario.currentBid(), scenario.maxCpc(),
                scenario.limits(), scenario.grid(), BidCandidate.MAX_CPC_BOUNDED);
    }

    private static Optional<BidCandidate> increase(BidScenario scenario) {
        return BidCandidate.increase(scenario.currentBid(), scenario.maxCpc(),
                scenario.limits(), scenario.grid(), BidCandidate.MAX_CPC_BOUNDED);
    }

    private static Optional<BidCandidate> causeBound(BidScenario scenario) {
        return BidCandidate.causeBoundDecrease(scenario.currentBid(), scenario.causeStepRatio(),
                scenario.limits(), scenario.grid());
    }

    /** Every bound the platform imposes, checked on one produced candidate. */
    private static void assertOnTheGrid(BidCandidate proposal, BidScenario scenario) {
        ProviderBidGrid grid = scenario.grid();
        BigDecimal landed = proposal.providerNormalizedAmount();
        assertThat(landed).as("%s: at least the platform minimum", scenario.description())
                .isGreaterThanOrEqualTo(grid.minimum());
        assertThat(landed).as("%s: at most the platform maximum", scenario.description())
                .isLessThanOrEqualTo(grid.maximum());
        assertThat(landed.subtract(grid.minimum()).remainder(grid.step()))
                .as("%s: an exact number of steps above the minimum", scenario.description())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(landed)
                .as("%s: never above the amount the calculation asked for",
                        scenario.description())
                .isLessThanOrEqualTo(proposal.requestedAmount());
        assertThat(proposal.currencyCode())
                .as("%s: the currency the grid is denominated in", scenario.description())
                .isEqualTo(grid.bidCurrencyCode());
        assertThat(proposal.bidUnitCode())
                .as("%s: the unit the grid counts in", scenario.description())
                .isEqualTo(grid.bidUnitCode());
    }

    /** One place in the queue's severity order. */
    private record Placement(AdvertisingLane lane, ProtectionTier tier) {

        int band() {
            return AdPriorityPolicy.band(lane, tier);
        }

        @Override
        public String toString() {
            return "%s/%s(band %d)".formatted(lane, tier, band());
        }
    }

    private static AdPriorityPolicy.Weights weights(Random random) {
        // The confidence weight is negated because the policy refuses a positive
        // one: uncertainty may only ever subtract.
        return new AdPriorityPolicy.Weights(money(random, 1_000_000d), money(random, 1_000_000d),
                money(random, 1_000_000d), money(random, 1_000_000d), money(random, 1_000_000d),
                money(random, 1_000_000d), money(random, 1_000_000d).negate());
    }

    private static AdPriorityPolicy.Inputs inputs(Random random, Placement placement,
                                                  double magnitude) {
        return new AdPriorityPolicy.Inputs(placement.lane(), placement.tier(),
                exposure(random, magnitude), exposure(random, magnitude),
                exposure(random, magnitude), exposure(random, magnitude),
                ratio(random, 0.00, 1.00), BigDecimal.valueOf(random.nextInt(900)),
                confidence(random));
    }

    private static AdMeasure exposure(Random random, double magnitude) {
        if (random.nextInt(8) == 0) {
            // Absent rather than zero, because a rank has to survive a figure
            // nobody published as well as one that is genuinely nothing.
            return AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);
        }
        return AdMeasure.available(money(random, magnitude), AdEvidenceState.CANONICAL_CONFIRMED);
    }

    private static AdConfidence confidence(Random random) {
        AdConfidence[] values = AdConfidence.values();
        return values[random.nextInt(values.length)];
    }

    /** The richest case the clamp admits: every commercial term at its largest. */
    private static AdPriorityPolicy.Inputs richest(Placement placement) {
        AdMeasure huge = AdMeasure.available(new BigDecimal("999999999.0000"),
                AdEvidenceState.CANONICAL_CONFIRMED);
        return new AdPriorityPolicy.Inputs(placement.lane(), placement.tier(),
                huge, huge, huge, huge, BigDecimal.ONE, new BigDecimal("100000"),
                AdConfidence.HIGH);
    }

    /** The poorest: nothing measured at all, on the largest confidence penalty. */
    private static AdPriorityPolicy.Inputs poorest(Placement placement) {
        AdMeasure absent = AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE);
        return new AdPriorityPolicy.Inputs(placement.lane(), placement.tier(),
                absent, absent, absent, absent, null, null, AdConfidence.UNUSABLE);
    }

    private static String describe(AdPriorityPolicy.Inputs inputs) {
        return ("%s profitLoss=%s spend=%s criticalSales=%s recoverable=%s maturity=%s "
                + "age=%s confidence=%s").formatted(
                new Placement(inputs.lane(), inputs.protectionTier()),
                inputs.confirmedProfitLossRate(), inputs.officialSpendExposure(),
                inputs.criticalSalesExposure(), inputs.recoverableProfit(),
                inputs.evidenceMaturityRatio(), inputs.caseAgeDays(), inputs.confidence());
    }

    private static AdLinkedConversion conversion(SaleStage stage, long linked, long traffic) {
        // No sample floor and no coverage floors: this fixture is about the
        // stage, and a refusal for some other reason would hide the answer.
        return AdLinkedConversion.writeGrade(stage, linked, traffic, BigDecimal.ONE,
                BigDecimal.ONE, true, true, 0L, null, null,
                AdEvidenceState.CANONICAL_CONFIRMED);
    }

    private static SalesPreservation.Status status(Random random) {
        SalesPreservation.Status[] values = SalesPreservation.Status.values();
        return values[random.nextInt(values.length)];
    }

    private static AdvertisingContributionProfit.Components profitComponents(
            AdMeasure[] parts, long adLinkedUnits) {
        return new AdvertisingContributionProfit.Components(parts[0], adLinkedUnits, parts[1],
                parts[2], parts[3], parts[4], parts[5], parts[6], CURRENCY);
    }

    /** An absent component, in each of the three shapes absence actually arrives in. */
    private static AdMeasure absentComponent(Random random) {
        return switch (random.nextInt(3)) {
            case 0 -> null;
            case 1 -> AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED);
            default -> AdMeasure.undefined(AdEvidenceState.NOT_AVAILABLE);
        };
    }

    private static AdMeasure presentComponent(Random random, double magnitude) {
        AdEvidenceState[] states = AdEvidenceState.values();
        BigDecimal value = random.nextInt(6) == 0
                ? new BigDecimal("0.0000")
                : money(random, magnitude);
        return AdMeasure.available(value, states[random.nextInt(states.length)]);
    }

    /**
     * A calculator that is not a function of its inputs cannot be compared
     * between a targeted run and a sweep, which is how this product is meant
     * to be checked: the same case recalculated on a cycle must reproduce the
     * bid a person approved, or the approval was for something else.
     *
     * <p>The comparison is within one process. Repeatability across processes —
     * that no locale, hash iteration order or default rounding outside these
     * types changes an answer between two runs of the sweep — is not something a
     * unit test can observe, and showing it would need the same case scored in
     * two runs with the results compared afterwards.
     */
    @Nested
    @DisplayName("the same inputs always produce the same answer")
    class Determinism {

        private static final long SEED = 20260905_01L;

        @Test
        @DisplayName("TC-AD-PROP-001 a bid calculation repeated on one input is the same bid")
        void aBidCalculationRepeatedOnOneInputIsTheSameBid() {
            Random random = seeded(SEED);
            for (int index = 0; index < CASES; index++) {
                BidScenario scenario = bidScenario(random, index % 2 == 0);

                assertThat(decrease(scenario))
                        .as("%s: a decrease computed twice", scenario.description())
                        .isEqualTo(decrease(scenario));
                assertThat(increase(scenario))
                        .as("%s: an increase computed twice", scenario.description())
                        .isEqualTo(increase(scenario));
                assertThat(causeBound(scenario))
                        .as("%s: a cause-bound decrease computed twice", scenario.description())
                        .isEqualTo(causeBound(scenario));
            }
        }

        @Test
        @DisplayName("TC-AD-PROP-002 a ceiling computed twice from one input is the same ceiling")
        void aCeilingComputedTwiceFromOneInputIsTheSameCeiling() {
            Random random = seeded(SEED + 1);
            SaleStage[] stages = SaleStage.values();
            for (int index = 0; index < CASES; index++) {
                SaleStage cpaStage = stages[random.nextInt(stages.length)];
                SaleStage conversionStage = stages[random.nextInt(stages.length)];
                long traffic = 1L + random.nextInt(1000);
                long linked = random.nextInt((int) traffic + 1);
                Money allowable = Money.of(money(random, 5000d), CURRENCY);
                AdLinkedConversion conversion = conversion(conversionStage, linked, traffic);
                String description = ("cpaStage=%s conversionStage=%s linked=%d traffic=%d "
                        + "allowable=%s").formatted(cpaStage, conversionStage, linked, traffic,
                        allowable.amount());

                assertThat(MaxCpc.compute(allowable, cpaStage, conversion))
                        .as("%s: a ceiling computed twice", description)
                        .isEqualTo(MaxCpc.compute(allowable, cpaStage, conversion));
            }
        }

        @Test
        @DisplayName("TC-AD-PROP-003 a rank repeated on one input keeps its score and its terms")
        void aRankRepeatedOnOneInputKeepsItsScoreAndItsTerms() {
            Random random = seeded(SEED + 2);
            for (int index = 0; index < CASES; index++) {
                Placement placement = PLACEMENTS.get(random.nextInt(PLACEMENTS.size()));
                AdPriorityPolicy.Weights weights = weights(random);
                AdPriorityPolicy.Inputs inputs = inputs(random, placement, 1_000_000d);
                String description = "%s weights=%s".formatted(describe(inputs), weights);

                AdPriorityPolicy.Ranking first = AdPriorityPolicy.rank(inputs, weights);
                assertThat(AdPriorityPolicy.rank(inputs, weights))
                        .as("%s: a rank computed twice", description)
                        .isEqualTo(first);
                assertThat(AdPriorityPolicy.workflowPriority(first.score()))
                        .as("%s: the workflow mapping of one score", description)
                        .isEqualByComparingTo(AdPriorityPolicy.workflowPriority(first.score()));
            }
        }
    }

    /**
     * A value off the grid gets one of three answers from a marketplace: a
     * refusal, a silent round, or an acceptance that reads back differently.
     * The middle one is the dangerous answer, because it changes a live bid
     * to a number nobody chose and nothing in the audit trail says so. So the
     * claim is about every candidate the calculator is capable of producing,
     * not about the ones an example test happened to ask for.
     */
    @Nested
    @DisplayName("every candidate that exists is one the platform would accept unchanged")
    class ProviderValidTargets {

        private static final long SEED = 20260905_02L;

        @Test
        @DisplayName("TC-AD-PROP-004 every decrease lands on the grid, below the bid it lowers")
        void everyDecreaseLandsOnTheGridBelowTheBidItLowers() {
            Random random = seeded(SEED);
            int produced = 0;
            for (int index = 0; index < CASES; index++) {
                BidScenario scenario = bidScenario(random, false);
                Optional<BidCandidate> candidate = decrease(scenario);
                if (candidate.isEmpty()) {
                    continue;
                }
                produced++;
                BidCandidate proposal = candidate.orElseThrow();

                assertOnTheGrid(proposal, scenario);
                assertThat(proposal.providerNormalizedAmount())
                        .as("%s: a decrease lands strictly below the current bid",
                                scenario.description())
                        .isLessThan(proposal.currentBid());
                assertThat(proposal.direction())
                        .as("%s: a decrease is a protection decrease", scenario.description())
                        .isEqualTo(BidCandidate.PROTECTION_DECREASE);
            }

            assertThat(produced)
                    .as("a generator that stopped producing decreases would satisfy every "
                            + "bound above while asserting nothing")
                    .isGreaterThan(CASES / 2);
        }

        @Test
        @DisplayName("TC-AD-PROP-005 every increase lands on the grid, above the bid and under "
                + "the intent")
        void everyIncreaseLandsOnTheGridAboveTheBidAndUnderTheIntent() {
            Random random = seeded(SEED + 1);
            int produced = 0;
            for (int index = 0; index < CASES; index++) {
                BidScenario scenario = bidScenario(random, true);
                Optional<BidCandidate> candidate = increase(scenario);
                if (candidate.isEmpty()) {
                    continue;
                }
                produced++;
                BidCandidate proposal = candidate.orElseThrow();

                assertOnTheGrid(proposal, scenario);
                assertThat(proposal.providerNormalizedAmount())
                        .as("%s: an increase lands strictly above the current bid",
                                scenario.description())
                        .isGreaterThan(proposal.currentBid());
                assertThat(proposal.requestedAmount())
                        .as("%s: the intent itself never passes the headroom-adjusted ceiling",
                                scenario.description())
                        .isLessThanOrEqualTo(scenario.limits()
                                .applyCeilingHeadroom(scenario.maxCpc().ceiling().amount()));
                assertThat(proposal.direction())
                        .as("%s: an increase is an optimization increase", scenario.description())
                        .isEqualTo(BidCandidate.OPTIMIZATION_INCREASE);
            }

            assertThat(produced)
                    .as("a generator that stopped producing increases would satisfy every "
                            + "bound above while asserting nothing")
                    .isGreaterThan(CASES / 2);
        }

        @Test
        @DisplayName("TC-AD-PROP-006 every cause-bound decrease is bounded by the step limit too")
        void everyCauseBoundDecreaseIsBoundedByTheStepLimitToo() {
            Random random = seeded(SEED + 2);
            int produced = 0;
            for (int index = 0; index < CASES; index++) {
                BidScenario scenario = bidScenario(random, index % 2 == 0);
                Optional<BidCandidate> candidate = causeBound(scenario);
                if (candidate.isEmpty()) {
                    continue;
                }
                produced++;
                BidCandidate proposal = candidate.orElseThrow();

                assertOnTheGrid(proposal, scenario);
                assertThat(proposal.providerNormalizedAmount())
                        .as("%s: a cause-bound decrease lands strictly below the current bid",
                                scenario.description())
                        .isLessThan(proposal.currentBid());
                // The policy floor still binds, and the only slack is the single
                // step the grid may round down by. Asserting the floor itself
                // would be wrong: a floor that falls between two grid points is
                // reached by landing on the point below it.
                assertThat(proposal.providerNormalizedAmount())
                        .as("%s: a cause justifies a decrease, it does not lift the bound on "
                                + "how far one decision may move spend", scenario.description())
                        .isGreaterThan(scenario.limits()
                                .lowestPermittedFrom(proposal.currentBid())
                                .subtract(scenario.grid().step()));
                assertThat(proposal.candidateBasis())
                        .as("%s: the candidate admits it never had an economic ceiling",
                                scenario.description())
                        .isEqualTo(BidCandidate.CAUSE_BOUND_PROTECTION_STEP);
            }

            assertThat(produced)
                    .as("a generator that stopped producing cause-bound decreases would "
                            + "satisfy every bound above while asserting nothing")
                    .isGreaterThan(CASES / 2);
        }
    }

    /**
     * The queue is the product's opinion about what to do first. If a large
     * enough commercial term could lift an optimization above a data defect,
     * then a broken spend feed would sit under an opportunity while the money
     * kept leaving, and nobody would see a decision being made — they would
     * see a sort order. The band arithmetic is what makes that impossible
     * rather than merely unlikely, so the property is asserted against the
     * worst case the clamp admits, not against a typical one.
     */
    @Nested
    @DisplayName("no amount of money moves a case across a band")
    class NonCompensatingPriority {

        private static final long SEED = 20260905_03L;

        @Test
        @DisplayName("TC-AD-PROP-007 a higher band outranks a lower one whatever the money says")
        void aHigherBandOutranksALowerOneWhateverTheMoneySays() {
            Random random = seeded(SEED);
            for (int index = 0; index < CASES; index++) {
                AdPriorityPolicy.Weights weights = weights(random);
                int first = random.nextInt(PLACEMENTS.size());
                int second = random.nextInt(PLACEMENTS.size() - 1);
                if (second >= first) {
                    second++;
                }
                Placement one = PLACEMENTS.get(first);
                Placement other = PLACEMENTS.get(second);
                Placement lower = one.band() < other.band() ? one : other;
                Placement higher = one.band() < other.band() ? other : one;
                String pair = "%s under %s weights=%s".formatted(lower, higher, weights);

                assertThat(higher.band())
                        .as("%s: the seven placements occupy seven distinct bands", pair)
                        .isGreaterThan(lower.band());
                assertThat(AdPriorityPolicy.rank(poorest(higher), weights).score())
                        .as("%s: the largest conceivable commercial term in the lower band", pair)
                        .isGreaterThan(AdPriorityPolicy.rank(richest(lower), weights).score());

                AdPriorityPolicy.Inputs lowerInputs = inputs(random, lower, 1_000_000_000d);
                AdPriorityPolicy.Inputs higherInputs = inputs(random, higher, 1d);
                assertThat(AdPriorityPolicy.rank(higherInputs, weights).score())
                        .as("%s: lower=[%s] higher=[%s]", pair, describe(lowerInputs),
                                describe(higherInputs))
                        .isGreaterThan(AdPriorityPolicy.rank(lowerInputs, weights).score());
            }
        }

        @Test
        @DisplayName("TC-AD-PROP-008 the workflow scale fits its column and keeps the ordering")
        void theWorkflowScaleFitsItsColumnAndKeepsTheOrdering() {
            Random random = seeded(SEED + 1);
            for (int index = 0; index < CASES; index++) {
                AdPriorityPolicy.Weights weights = weights(random);
                int first = random.nextInt(PLACEMENTS.size());
                int second = random.nextInt(PLACEMENTS.size() - 1);
                if (second >= first) {
                    second++;
                }
                Placement one = PLACEMENTS.get(first);
                Placement other = PLACEMENTS.get(second);
                Placement lower = one.band() < other.band() ? one : other;
                Placement higher = one.band() < other.band() ? other : one;
                AdPriorityPolicy.Inputs richLower = richest(lower);
                AdPriorityPolicy.Inputs poorHigher = poorest(higher);
                String pair = "%s under %s weights=%s".formatted(lower, higher, weights);

                BigDecimal mappedLower = AdPriorityPolicy.workflowPriority(
                        AdPriorityPolicy.rank(richLower, weights).score());
                BigDecimal mappedHigher = AdPriorityPolicy.workflowPriority(
                        AdPriorityPolicy.rank(poorHigher, weights).score());

                // numeric(9, 4) bounded at a thousand, on the workflow side.
                assertThat(mappedLower).as("%s: the mapped lower rank fits the column", pair)
                        .isBetween(BigDecimal.ZERO, new BigDecimal("1000"));
                assertThat(mappedHigher).as("%s: the mapped higher rank fits the column", pair)
                        .isBetween(BigDecimal.ZERO, new BigDecimal("1000"));
                assertThat(mappedLower.scale())
                        .as("%s: the column stores four decimal places", pair)
                        .isEqualTo(4);
                assertThat(mappedHigher)
                        .as("%s: the mapping does not let a rich low band overtake a poor "
                                + "high one", pair)
                        .isGreaterThan(mappedLower);
            }
        }
    }

    /**
     * A company total that looks healthy because one product doubled while a
     * protected one died is exactly the outcome the per-unit term exists to
     * catch. The term is therefore not a weight and not a tiebreak: it is a
     * conjunction, and the property is that nothing else in the input can
     * turn a required unit's failure into a pass.
     *
     * <p>What cannot be asserted here is the numeric side of the same claim —
     * that a larger surplus on the total never offsets a shortfall on a unit —
     * because {@code evaluate} receives statuses rather than the observed
     * sales and the tolerance they were compared against. Asserting it would
     * need an overload that takes those magnitudes.
     */
    @Nested
    @DisplayName("a critical sales unit cannot be paid for out of another one's growth")
    class NonCompensatingMateriality {

        private static final long SEED = 20260905_04L;

        @Test
        @DisplayName("TC-AD-PROP-009 a required unit that did not pass is never a preserved "
                + "verdict")
        void aRequiredUnitThatDidNotPassIsNeverAPreservedVerdict() {
            Random random = seeded(SEED);
            for (int index = 0; index < CASES; index++) {
                SalesPreservation.Status total = status(random);
                List<SalesPreservation.UnitResult> units = new ArrayList<>();
                StringBuilder shape = new StringBuilder("total=").append(total);
                boolean requiredAllPassed = true;
                boolean requiredAllMeasured = true;
                int count = random.nextInt(7);
                for (int unit = 0; unit < count; unit++) {
                    boolean required = random.nextBoolean();
                    SalesPreservation.Status unitStatus = status(random);
                    units.add(new SalesPreservation.UnitResult("UNIT_" + unit, required,
                            unitStatus));
                    shape.append(' ').append(required ? "required:" : "optional:")
                            .append(unitStatus);
                    if (required && unitStatus != SalesPreservation.Status.PASSED) {
                        requiredAllPassed = false;
                    }
                    if (required && unitStatus == SalesPreservation.Status.UNRESOLVED) {
                        requiredAllMeasured = false;
                    }
                }
                SalesPreservation result = SalesPreservation.evaluate(
                        new SalesPreservation.UnitResult("COMPANY_TOTAL", true, total), units);

                assertThat(result.preserved())
                        .as("%s: preservation holds only when the total and every required "
                                + "unit passed", shape)
                        .isEqualTo(total == SalesPreservation.Status.PASSED && requiredAllPassed);
                assertThat(result.evidenceComplete())
                        .as("%s: an unmeasurable required term is never reported as measured",
                                shape)
                        .isEqualTo(total != SalesPreservation.Status.UNRESOLVED
                                && requiredAllMeasured);
                assertThat(result.reasonCode())
                        .as("%s: the verdict always says which term produced it", shape)
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("TC-AD-PROP-010 a unit that carries no veto also carries no rescue")
        void aUnitThatCarriesNoVetoAlsoCarriesNoRescue() {
            Random random = seeded(SEED + 1);
            for (int index = 0; index < CASES; index++) {
                SalesPreservation.UnitResult total = new SalesPreservation.UnitResult(
                        "COMPANY_TOTAL", true, status(random));
                List<SalesPreservation.UnitResult> required = new ArrayList<>();
                StringBuilder shape = new StringBuilder("total=").append(total.status());
                int requiredCount = 1 + random.nextInt(4);
                for (int unit = 0; unit < requiredCount; unit++) {
                    SalesPreservation.Status unitStatus = status(random);
                    required.add(new SalesPreservation.UnitResult("REQUIRED_" + unit, true,
                            unitStatus));
                    shape.append(" required:").append(unitStatus);
                }
                List<SalesPreservation.UnitResult> withNoise = new ArrayList<>(required);
                int noiseCount = random.nextInt(6);
                for (int unit = 0; unit < noiseCount; unit++) {
                    SalesPreservation.Status unitStatus = status(random);
                    withNoise.add(new SalesPreservation.UnitResult("OPTIONAL_" + unit, false,
                            unitStatus));
                    shape.append(" optional:").append(unitStatus);
                }
                Collections.shuffle(withNoise, random);

                SalesPreservation noisy = SalesPreservation.evaluate(total, withNoise);
                SalesPreservation bare = SalesPreservation.evaluate(total, required);

                assertThat(noisy.verdict())
                        .as("%s: units that carry no veto do not move the verdict either way",
                                shape)
                        .isEqualTo(bare.verdict());
                assertThat(noisy.criticalUnits())
                        .as("%s: every unit stays visible even when it decides nothing", shape)
                        .hasSize(withNoise.size());
            }
        }
    }

    /**
     * A contribution figure computed without return loss is not a
     * conservative contribution figure; it is a wrong one, and wrong in the
     * direction of spending more. So a missing component blocks the answer
     * and names itself, and no size of the components that did arrive can
     * stand in for the one that did not.
     */
    @Nested
    @DisplayName("a profit is whole or it is not a profit")
    class TwoAxesThatCannotRescueEachOther {

        private static final long SEED = 20260905_05L;

        @Test
        @DisplayName("TC-AD-PROP-011 a missing component blocks the profit and names itself")
        void aMissingComponentBlocksTheProfitAndNamesItself() {
            Random random = seeded(SEED);
            for (int index = 0; index < CASES; index++) {
                AdMeasure[] parts = new AdMeasure[PROFIT_COMPONENTS.size()];
                List<String> expectedMissing = new ArrayList<>();
                for (int part = 0; part < parts.length; part++) {
                    if (random.nextInt(3) == 0) {
                        parts[part] = absentComponent(random);
                        expectedMissing.add(PROFIT_COMPONENTS.get(part));
                    } else {
                        parts[part] = presentComponent(random, 100_000d);
                    }
                }
                long units = random.nextInt(500);
                String description = "units=%d parts=%s".formatted(units, List.of(
                        String.valueOf(parts[0]), String.valueOf(parts[1]),
                        String.valueOf(parts[2]), String.valueOf(parts[3]),
                        String.valueOf(parts[4]), String.valueOf(parts[5]),
                        String.valueOf(parts[6])));

                AdvertisingContributionProfit profit = AdvertisingContributionProfit.compute(
                        profitComponents(parts, units));

                if (expectedMissing.isEmpty()) {
                    assertThat(profit.resolved())
                            .as("%s: every component arrived", description).isTrue();
                    assertThat(profit.missingComponentCodes())
                            .as("%s: nothing to report missing", description).isEmpty();
                    AdEvidenceState weakest = profit.absoluteProfit().evidenceState();
                    for (AdMeasure part : parts) {
                        assertThat(weakest.weakest(part.evidenceState()))
                                .as("%s: a profit built from one estimated input is an "
                                        + "estimated profit", description)
                                .isEqualTo(weakest);
                    }
                    assertThat(profit.profitPerAdRub().present())
                            .as("%s: profit per rouble exists only when a rouble was spent",
                                    description)
                            .isEqualTo(parts[6].value().signum() > 0);
                } else {
                    assertThat(profit.resolved())
                            .as("%s: a partial profit is not a profit", description).isFalse();
                    assertThat(profit.missingComponentCodes())
                            .as("%s: the answer names every component that stopped it",
                                    description)
                            .containsExactlyInAnyOrderElementsOf(expectedMissing);
                    assertThat(profit.provenLoss())
                            .as("%s: an unresolved profit is not a loss", description).isFalse();
                }
            }
        }

        @Test
        @DisplayName("TC-AD-PROP-012 no size of the components that arrived replaces one that "
                + "did not")
        void noSizeOfTheComponentsThatArrivedReplacesOneThatDidNot() {
            Random random = seeded(SEED + 1);
            for (int index = 0; index < CASES; index++) {
                int absentIndex = index % PROFIT_COMPONENTS.size();
                AdMeasure[] parts = new AdMeasure[PROFIT_COMPONENTS.size()];
                for (int part = 0; part < parts.length; part++) {
                    // Deliberately extreme: an enormous net sales figure and no
                    // costs at all is the shape most likely to tempt a caller
                    // into treating the gap as a zero.
                    parts[part] = part == absentIndex
                            ? absentComponent(random)
                            : presentComponent(random, random.nextBoolean() ? 1d : 1_000_000_000d);
                }
                long units = random.nextInt(100_000);
                String description = "absent=%s units=%d".formatted(
                        PROFIT_COMPONENTS.get(absentIndex), units);

                AdvertisingContributionProfit profit = AdvertisingContributionProfit.compute(
                        profitComponents(parts, units));

                assertThat(profit.resolved())
                        .as("%s: the answer stays blocked", description).isFalse();
                assertThat(profit.missingComponentCodes())
                        .as("%s: and names exactly the component that is missing", description)
                        .containsExactly(PROFIT_COMPONENTS.get(absentIndex));
                assertThat(profit.absoluteProfit().evidenceState())
                        .as("%s: a blocked profit is somebody's repair task", description)
                        .isEqualTo(AdEvidenceState.DATA_BLOCKED);
                assertThat(profit.profitPerAdRub().present())
                        .as("%s: neither axis is rescued by the other", description).isFalse();
            }
        }
    }

    /**
     * An Allowable CPA priced against an Order multiplied by a conversion
     * measured against a Retained Sale overstates the ceiling by the whole
     * cancellation and return rate. The overstated number is plausible, sorts
     * correctly and is wrong, so the mismatch has to be refused rather than
     * corrected: correcting it would mean inventing a cancellation rate
     * nobody published.
     */
    @Nested
    @DisplayName("a ceiling prices one sale event or it prices nothing")
    class StageConsistency {

        private static final long SEED = 20260905_06L;

        @Test
        @DisplayName("TC-AD-PROP-013 two different sale events never multiply into a ceiling")
        void twoDifferentSaleEventsNeverMultiplyIntoACeiling() {
            Random random = seeded(SEED);
            SaleStage[] stages = SaleStage.values();
            boolean[][] exercised = new boolean[stages.length][stages.length];
            for (int index = 0; index < CASES; index++) {
                int cpa = random.nextInt(stages.length);
                int measured = random.nextInt(stages.length - 1);
                if (measured >= cpa) {
                    measured++;
                }
                SaleStage cpaStage = stages[cpa];
                SaleStage conversionStage = stages[measured];
                long traffic = 1L + random.nextInt(1000);
                long linked = 1L + random.nextInt((int) traffic);
                Money allowable = Money.of(money(random, 5000d).add(BigDecimal.ONE), CURRENCY);
                String description = "cpaStage=%s conversionStage=%s linked=%d traffic=%d"
                        .formatted(cpaStage, conversionStage, linked, traffic);

                MaxCpc result = MaxCpc.compute(allowable, cpaStage,
                        conversion(conversionStage, linked, traffic));

                assertThat(result.absence())
                        .as("%s: the mismatch is refused by name", description)
                        .isEqualTo(MaxCpc.Absence.STAGE_MISMATCH);
                assertThat(result.ceiling())
                        .as("%s: and no plausible number is produced anyway", description)
                        .isNull();
                assertThat(result.stage())
                        .as("%s: an absent ceiling prices nothing", description).isNull();
                assertThat(result.writeGrade())
                        .as("%s: nothing may be written on it", description).isFalse();
                exercised[cpa][measured] = true;
            }

            int pairs = 0;
            for (boolean[] row : exercised) {
                for (boolean seen : row) {
                    pairs += seen ? 1 : 0;
                }
            }
            assertThat(pairs)
                    .as("every distinct ordered pair of sale stages was actually generated")
                    .isEqualTo(stages.length * (stages.length - 1));
        }

        @Test
        @DisplayName("TC-AD-PROP-014 a ceiling that exists names the stage it was priced against")
        void aCeilingThatExistsNamesTheStageItWasPricedAgainst() {
            Random random = seeded(SEED + 1);
            List<SaleStage> priceable = new ArrayList<>();
            for (SaleStage stage : SaleStage.values()) {
                if (stage.pricesContribution()) {
                    priceable.add(stage);
                }
            }
            for (int index = 0; index < CASES; index++) {
                SaleStage stage = priceable.get(random.nextInt(priceable.size()));
                long traffic = 1L + random.nextInt(1000);
                long linked = 1L + random.nextInt((int) traffic);
                Money allowable = Money.of(money(random, 5000d).add(new BigDecimal("100")),
                        CURRENCY);
                String description = "stage=%s linked=%d traffic=%d allowable=%s"
                        .formatted(stage, linked, traffic, allowable.amount());

                MaxCpc result = MaxCpc.compute(allowable, stage,
                        conversion(stage, linked, traffic));

                assertThat(result.absence())
                        .as("%s: matched stages produce a ceiling", description)
                        .isEqualTo(MaxCpc.Absence.NONE);
                assertThat(result.stage())
                        .as("%s: the ceiling names the stage its Allowable CPA priced",
                                description)
                        .isEqualTo(stage);
                assertThat(result.ceiling().currencyCode())
                        .as("%s: and keeps the currency it was priced in", description)
                        .isEqualTo(CURRENCY);
                assertThat(result.ceiling().amount())
                        .as("%s: a ceiling of zero would read as a valid bound", description)
                        .isGreaterThan(BigDecimal.ZERO);
                assertThat(result.writeGrade())
                        .as("%s: and a write may rest on it", description).isTrue();
            }
        }
    }

    /**
     * "This object had no clicks" and "nobody reported this object's clicks"
     * justify opposite decisions, and the difference between them survives
     * only as long as nobody writes a coalesce. The pairing of value state and
     * value is what makes the second one unwriteable, so the property covers
     * every state rather than the two a caller usually thinks about.
     */
    @Nested
    @DisplayName("an absent number is never quietly a zero")
    class AbsenceIsAValue {

        private static final long SEED = 20260905_07L;

        @Test
        @DisplayName("TC-AD-PROP-015 presence, write-sufficiency and the fallback agree with the "
                + "value state")
        void presenceWriteSufficiencyAndTheFallbackAgreeWithTheValueState() {
            Random random = seeded(SEED);
            AdEvidenceState[] states = AdEvidenceState.values();
            for (int index = 0; index < CASES; index++) {
                AdEvidenceState evidence = states[random.nextInt(states.length)];
                BigDecimal value = money(random, 10_000d);
                String description = "evidence=%s value=%s".formatted(evidence, value);

                AdMeasure available = AdMeasure.available(value, evidence);
                assertThat(available.present())
                        .as("%s: a computed measure is present", description).isTrue();
                assertThat(available.valueState())
                        .as("%s: and says so", description).isEqualTo(ValueState.AVAILABLE);
                assertThat(available.orElse(SENTINEL))
                        .as("%s: the fallback is not consulted when a number exists", description)
                        .isSameAs(value);
                assertThat(available.sufficientForWrite())
                        .as("%s: an explained estimate is present and still not writeable",
                                description)
                        .isEqualTo(evidence.sufficientForWrite());

                AdMeasure notAvailable = AdMeasure.notAvailable(evidence);
                AdMeasure undefined = AdMeasure.undefined(evidence);
                for (AdMeasure absent : List.of(notAvailable, undefined)) {
                    assertThat(absent.present())
                            .as("%s: %s is not a number", description, absent.valueState())
                            .isFalse();
                    assertThat(absent.sufficientForWrite())
                            .as("%s: %s is never writeable, whatever the evidence", description,
                                    absent.valueState())
                            .isFalse();
                    assertThat(absent.value())
                            .as("%s: %s carries nothing", description, absent.valueState())
                            .isNull();
                    assertThat(absent.orElse(SENTINEL))
                            .as("%s: %s yields the caller's stated fallback, not a zero",
                                    description, absent.valueState())
                            .isSameAs(SENTINEL);
                    assertThat(absent.orElse(null))
                            .as("%s: %s with no fallback stays absent", description,
                                    absent.valueState())
                            .isNull();
                }
                assertThat(undefined.valueState())
                        .as("%s: a definition with no answer is not a fact nobody publishes",
                                description)
                        .isNotEqualTo(notAvailable.valueState());
            }
        }

        @Test
        @DisplayName("TC-AD-PROP-016 a value state that disagrees with its value is "
                + "unrepresentable")
        void aValueStateThatDisagreesWithItsValueIsUnrepresentable() {
            Random random = seeded(SEED + 1);
            AdEvidenceState[] states = AdEvidenceState.values();
            for (int index = 0; index < CASES; index++) {
                AdEvidenceState evidence = states[random.nextInt(states.length)];
                BigDecimal value = money(random, 10_000d);
                ValueState absentState = random.nextBoolean()
                        ? ValueState.NOT_AVAILABLE
                        : ValueState.UNDEFINED;
                String description = "evidence=%s value=%s absentState=%s"
                        .formatted(evidence, value, absentState);

                assertThatThrownBy(() -> new AdMeasure(ValueState.AVAILABLE, null, evidence))
                        .as("%s: an available measure without a number", description)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exactly when it is AVAILABLE");
                assertThatThrownBy(() -> new AdMeasure(absentState, value, evidence))
                        .as("%s: an absent measure carrying a number", description)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("exactly when it is AVAILABLE");
            }
        }
    }

    /**
     * Most causes justify nothing, and the ones that read as if they should
     * are the reason this mapping is a closed switch rather than a lookup
     * somebody extends. The cause set is closed too, so the generator sweeps
     * it in a seeded order instead of sampling: every value is visited on
     * every pass, and the order still varies between passes.
     */
    @Nested
    @DisplayName("only a cause that justifies a bid move produces one")
    class DirectionIsClosed {

        private static final long SEED = 20260905_08L;

        @Test
        @DisplayName("TC-AD-PROP-017 a direction appears only where a cause can carry one")
        void aDirectionAppearsOnlyWhereACauseCanCarryOne() {
            Random random = seeded(SEED);
            List<AdvertisingCause> deck = new ArrayList<>(List.of(AdvertisingCause.values()));
            Set<AdvertisingCause> visited = EnumSet.noneOf(AdvertisingCause.class);
            Set<AdvertisingCause> lowering = EnumSet.noneOf(AdvertisingCause.class);
            Set<AdvertisingCause> raising = EnumSet.noneOf(AdvertisingCause.class);
            for (int index = 0; index < CASES; index++) {
                if (index % deck.size() == 0) {
                    Collections.shuffle(deck, random);
                }
                AdvertisingCause cause = deck.get(index % deck.size());
                visited.add(cause);

                Optional<BidDirection> direction = BidDirectionForCause.of(cause);

                assertThat(direction)
                        .as("cause %s: the answer is total, never null", cause).isNotNull();
                if (direction.isEmpty()) {
                    continue;
                }
                BidDirection moved = direction.orElseThrow();
                assertThat(cause.actionable())
                        .as("cause %s justifies %s, so it has to be able to raise work at all",
                                cause, moved)
                        .isTrue();
                assertThat(cause.dataDefect())
                        .as("cause %s justifies %s, and a data defect is repair work rather "
                                + "than a bid change", cause, moved)
                        .isFalse();
                assertThat(moved)
                        .as("cause %s: a fresh decision never restores a prior bid", cause)
                        .isNotEqualTo(BidDirection.EXACT_PRIOR_BID_COMPENSATION);
                if (moved == BidDirection.PROTECTION_DECREASE) {
                    lowering.add(cause);
                } else {
                    raising.add(cause);
                }
            }

            assertThat(visited).as("every cause was swept")
                    .containsExactlyInAnyOrder(AdvertisingCause.values());
            assertThat(lowering).as("the causes that justify lowering a bid")
                    .containsExactlyInAnyOrder(AdvertisingCause.PROVEN_ADVERTISING_LOSS,
                            AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE,
                            AdvertisingCause.PROMOTED_VARIANT_UNAVAILABLE);
            assertThat(raising).as("the one cause that justifies raising a bid")
                    .containsExactly(AdvertisingCause.RECOVERABLE_ADVERTISING_PROFIT);
            assertThat(BidDirectionForCause.of(null))
                    .as("no cause at all justifies no direction").isEmpty();
        }

        @Test
        @DisplayName("TC-AD-PROP-018 a cause about keeping sales never lowers the bid that "
                + "carries them")
        void aCauseAboutKeepingSalesNeverLowersTheBidThatCarriesThem() {
            Random random = seeded(SEED + 1);
            List<AdvertisingCause> deck = new ArrayList<>(List.of(AdvertisingCause.values()));
            for (int index = 0; index < CASES; index++) {
                if (index % deck.size() == 0) {
                    Collections.shuffle(deck, random);
                }
                AdvertisingCause cause = deck.get(index % deck.size());

                Optional<BidDirection> direction = BidDirectionForCause.of(cause);

                if (direction.orElse(null) == BidDirection.PROTECTION_DECREASE) {
                    assertThat(cause)
                            .as("lowering the bid of a unit whose sales the business cannot "
                                    + "afford to lose is what preservation exists to refuse")
                            .isNotEqualTo(AdvertisingCause.CRITICAL_SALES_UNIT_AT_RISK);
                }
                if (cause == AdvertisingCause.CRITICAL_SALES_UNIT_AT_RISK) {
                    assertThat(direction)
                            .as("cause %s stays a case a person owns", cause).isEmpty();
                }
                if (cause == AdvertisingCause.ACTION_OUTCOME_REGRESSION) {
                    assertThat(direction)
                            .as("cause %s is answered by the exact prior bid inside the "
                                    + "original lineage, not by a fresh decision", cause)
                            .isEmpty();
                }
            }
        }
    }
}
