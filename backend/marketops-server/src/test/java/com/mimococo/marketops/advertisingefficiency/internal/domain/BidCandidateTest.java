package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * How far a bid may move, and where it is allowed to land.
 *
 * <p>Every case here is about a real amount of money on a real marketplace. The
 * property under test throughout is that no path produces a value which is
 * higher than the intent, off the platform's grid, or above what a click is
 * worth — and that when any of those cannot be established, nothing is produced
 * at all.
 */
class BidCandidateTest {

    private static final String BASIS = "MAX_CPC_DERIVED";

    private static ProviderBidGrid grid() {
        return new ProviderBidGrid("CURRENCY_MAJOR", "RUB", 2, new BigDecimal("0.5000"),
                new BigDecimal("1.0000"), new BigDecimal("500.0000"), true, "VERIFIED");
    }

    private static AdMeasure bid(String amount) {
        return AdMeasure.available(new BigDecimal(amount), AdEvidenceState.CANONICAL_CONFIRMED);
    }

    private static MaxCpc ceiling(String amount) {
        return new MaxCpc(SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE, Money.of(new BigDecimal(amount), "RUB"),
                AdEvidenceState.CANONICAL_CONFIRMED, MaxCpc.Absence.NONE);
    }

    private static BidStepLimits limits(String relative, String absolute, String headroom) {
        return new BidStepLimits(new BigDecimal(relative), new BigDecimal(absolute),
                headroom == null ? null : new BigDecimal(headroom));
    }

    @Nested
    @DisplayName("the platform's grid decides where a value may land")
    class Grid {

        @Test
        @DisplayName("TC-AD-BID-001 a request between steps lands on the step below")
        void requestLandsOnTheStepBelow() {
            assertThat(grid().normalizeDownward(new BigDecimal("12.7000")))
                    .contains(new BigDecimal("12.5000"));
            assertThat(grid().normalizeDownward(new BigDecimal("12.5000")))
                    .contains(new BigDecimal("12.5000"));
        }

        @Test
        @DisplayName("TC-AD-BID-002 a request under the platform minimum produces nothing")
        void requestUnderTheMinimumProducesNothing() {
            // Substituting the minimum would send a number the calculation did
            // not ask for, on a real auction.
            assertThat(grid().normalizeDownward(new BigDecimal("0.5000"))).isEmpty();
        }

        @Test
        @DisplayName("TC-AD-BID-003 a request over the maximum is capped, never exceeded")
        void requestOverTheMaximumIsCapped() {
            assertThat(grid().normalizeDownward(new BigDecimal("100000.0000")))
                    .contains(new BigDecimal("500.0000"));
        }

        @Test
        @DisplayName("TC-AD-BID-004 an unverified or incomplete grid describes no write")
        void unusableGridDescribesNoWrite() {
            for (ProviderBidGrid unusable : java.util.List.of(
                    new ProviderBidGrid("CURRENCY_MAJOR", "RUB", 2, new BigDecimal("0.5"),
                            new BigDecimal("1"), new BigDecimal("500"), true, "UNVERIFIED"),
                    new ProviderBidGrid("CURRENCY_MAJOR", "RUB", 2, new BigDecimal("0.5"),
                            new BigDecimal("1"), new BigDecimal("500"), false, "VERIFIED"),
                    new ProviderBidGrid("CURRENCY_MAJOR", "RUB", 2, null,
                            new BigDecimal("1"), new BigDecimal("500"), true, "VERIFIED"),
                    new ProviderBidGrid("CURRENCY_MAJOR", "RUB", null, new BigDecimal("0.5"),
                            new BigDecimal("1"), new BigDecimal("500"), true, "VERIFIED"))) {
                assertThat(unusable.usable()).isFalse();
                assertThat(unusable.normalizeDownward(new BigDecimal("12.7000"))).isEmpty();
            }
        }

        @Test
        @DisplayName("TC-AD-BID-005 a step the precision cannot express is refused")
        void stepAndPrecisionMustAgree() {
            // A third of a rouble on a two-decimal field. Sending it means the
            // readback can never match what was sent.
            ProviderBidGrid inconsistent = new ProviderBidGrid("CURRENCY_MAJOR", "RUB", 2,
                    new BigDecimal("0.3330"), new BigDecimal("1.0000"),
                    new BigDecimal("500.0000"), true, "VERIFIED");

            assertThat(inconsistent.normalizeDownward(new BigDecimal("12.7000"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("a decrease protects and never overshoots")
    class Decrease {

        @Test
        @DisplayName("TC-AD-BID-006 the tighter of the two step limits wins")
        void tighterStepLimitWins() {
            // Relative would allow 20% of 100 = 20; absolute allows 5. The bid
            // may fall to 95, and the grid rounds to a step at or above it.
            Optional<BidCandidate> candidate = BidCandidate.decrease(bid("100.0000"),
                    ceiling("40.0000"), limits("0.20000", "5.0000", null), grid(), BASIS);

            assertThat(candidate).isPresent();
            assertThat(candidate.get().providerNormalizedAmount())
                    .isEqualByComparingTo("95.0000");
            assertThat(candidate.get().direction())
                    .isEqualTo(BidCandidate.PROTECTION_DECREASE);
        }

        @Test
        @DisplayName("TC-AD-BID-007 a bid already under the ceiling produces no candidate")
        void bidUnderTheCeilingProducesNothing() {
            assertThat(BidCandidate.decrease(bid("30.0000"), ceiling("40.0000"),
                    limits("0.20000", "5.0000", null), grid(), BASIS)).isEmpty();
        }

        @Test
        @DisplayName("TC-AD-BID-008 headroom keeps the target below the ceiling, not on it")
        void headroomKeepsTheTargetBelowTheCeiling() {
            // Ceiling 40, ten percent headroom, so the target is 36 rather than
            // 40. With a wide step limit the bid may reach it.
            Optional<BidCandidate> candidate = BidCandidate.decrease(bid("100.0000"),
                    ceiling("40.0000"), limits("0.90000", "500.0000", "0.10000"), grid(), BASIS);

            assertThat(candidate).isPresent();
            assertThat(candidate.get().providerNormalizedAmount())
                    .isEqualByComparingTo("36.0000");
        }

        @Test
        @DisplayName("TC-AD-BID-009 an absent ceiling produces no candidate at all")
        void absentCeilingProducesNothing() {
            MaxCpc absent = MaxCpc.absent(MaxCpc.Absence.CONVERSION_ZERO,
                    AdEvidenceState.INCOMPLETE);

            assertThat(BidCandidate.decrease(bid("100.0000"), absent,
                    limits("0.20000", "5.0000", null), grid(), BASIS)).isEmpty();
        }

        @Test
        @DisplayName("TC-AD-BID-010 an estimated bid is not write-grade, so nothing is proposed")
        void estimatedBidIsNotWriteGrade() {
            AdMeasure estimated = AdMeasure.available(new BigDecimal("100.0000"),
                    AdEvidenceState.PROVISIONAL_OR_ESTIMATED);

            assertThat(estimated.sufficientForWrite()).isFalse();
            assertThat(BidCandidate.decrease(estimated, ceiling("40.0000"),
                    limits("0.20000", "5.0000", null), grid(), BASIS)).isEmpty();
        }

        @Test
        @DisplayName("TC-AD-BID-011 a ceiling in another currency cannot bound this bid")
        void currencyMismatchProducesNothing() {
            MaxCpc otherCurrency = new MaxCpc(SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE,
                    Money.of(new BigDecimal("40.0000"), "USD"), AdEvidenceState.CANONICAL_CONFIRMED,
                    MaxCpc.Absence.NONE);

            assertThat(BidCandidate.decrease(bid("100.0000"), otherCurrency,
                    limits("0.20000", "5.0000", null), grid(), BASIS)).isEmpty();
        }
    }

    @Nested
    @DisplayName("an increase approaches what a click is worth and stops")
    class Increase {

        @Test
        @DisplayName("TC-AD-BID-012 an increase never passes the ceiling")
        void increaseNeverPassesTheCeiling() {
            // Step limit would allow 30 -> 60; the ceiling is 41, so the bid
            // stops at the step at or below 41.
            Optional<BidCandidate> candidate = BidCandidate.increase(bid("30.0000"),
                    ceiling("41.0000"), limits("1.00000", "500.0000", null), grid(), BASIS);

            assertThat(candidate).isPresent();
            assertThat(candidate.get().providerNormalizedAmount())
                    .isEqualByComparingTo("41.0000");
            assertThat(candidate.get().direction())
                    .isEqualTo(BidCandidate.OPTIMIZATION_INCREASE);
        }

        @Test
        @DisplayName("TC-AD-BID-013 the step limit binds when it is tighter than the ceiling")
        void stepLimitBindsWhenTighter() {
            Optional<BidCandidate> candidate = BidCandidate.increase(bid("30.0000"),
                    ceiling("400.0000"), limits("0.10000", "500.0000", null), grid(), BASIS);

            assertThat(candidate).isPresent();
            assertThat(candidate.get().providerNormalizedAmount())
                    .isEqualByComparingTo("33.0000");
        }

        @Test
        @DisplayName("TC-AD-BID-014 a bid already at the ceiling produces no candidate")
        void bidAtTheCeilingProducesNothing() {
            assertThat(BidCandidate.increase(bid("40.0000"), ceiling("40.0000"),
                    limits("0.20000", "5.0000", null), grid(), BASIS)).isEmpty();
        }

        @Test
        @DisplayName("TC-AD-BID-015 a step too small to cross a grid step proposes nothing")
        void stepTooSmallToMoveProposesNothing() {
            // Allowed to move 0.30; the grid's step is 0.50. Rounding down
            // returns the current bid, which is not a change.
            assertThat(BidCandidate.increase(bid("30.0000"), ceiling("400.0000"),
                    limits("0.01000", "0.3000", null), grid(), BASIS)).isEmpty();
        }
    }

    @Nested
    @DisplayName("a candidate that changes nothing is not a candidate")
    class Identity {

        @Test
        @DisplayName("TC-AD-BID-016 proposing the current bid is unrepresentable")
        void proposingTheCurrentBidIsUnrepresentable() {
            assertThatThrownBy(() -> new BidCandidate(BidCandidate.PROTECTION_DECREASE, BASIS,
                    new BigDecimal("30.0000"), new BigDecimal("30.0000"),
                    new BigDecimal("30.0000"), "RUB", "CURRENCY_MAJOR"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("proposes nothing");
        }

        @Test
        @DisplayName("TC-AD-BID-017 proposing zero is unrepresentable")
        void proposingZeroIsUnrepresentable() {
            assertThatThrownBy(() -> new BidCandidate(BidCandidate.PROTECTION_DECREASE, BASIS,
                    new BigDecimal("30.0000"), BigDecimal.ZERO, BigDecimal.ZERO,
                    "RUB", "CURRENCY_MAJOR"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("withdraws an object");
        }

        @Test
        @DisplayName("TC-AD-BID-018 the change is a distance in both directions")
        void changeIsADistance() {
            assertThat(new BidCandidate(BidCandidate.PROTECTION_DECREASE, BASIS,
                    new BigDecimal("30.0000"), new BigDecimal("25.0000"),
                    new BigDecimal("25.0000"), "RUB", "CURRENCY_MAJOR").changeAmount())
                    .isEqualByComparingTo("5");
        }
    }

    @Nested
    @DisplayName("a limit that permits nothing coherent is unrepresentable")
    class Limits {

        @Test
        @DisplayName("TC-AD-BID-019 negative limits and whole-ceiling headroom are refused")
        void incoherentLimitsAreRefused() {
            assertThatThrownBy(() -> limits("-0.10000", "5.0000", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> limits("0.10000", "-5.0000", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> limits("0.10000", "5.0000", "1.00000"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
