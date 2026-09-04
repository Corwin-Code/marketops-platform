package com.mimococo.marketops.operationsworkflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.analyticsdecision.MetricWindow;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The shapes an advertising decision travels in. */
class AdvertisingBidDecisionShapeTest {

    private static final UUID ID = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final UUID BUNDLE = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3302");

    private static AdvertisingBidProjection projection(BigDecimal current, BigDecimal target,
                                                       BigDecimal maxCpc) {
        return new AdvertisingBidProjection(ID, ID, ID, ID, ID, "PROTECTION", "TIER_1",
                "PROFIT_NEGATIVE_SPEND", "COMPLETE", "HIGH", List.of(),
                "PROTECTION_DECREASE", "MAX_CPC_DERIVED", current, target, "RUB",
                "CURRENCY_MAJOR", maxCpc, maxCpc == null ? "NOT_AVAILABLE" : "AVAILABLE",
                new BigDecimal("0.100000"), 47, "a".repeat(64), "MATERIAL_IMPACT",
                List.of(), "b".repeat(64), BUNDLE, 3);
    }

    @Nested
    @DisplayName("a projection says what a bid change would reach")
    class Projection {

        @Test
        @DisplayName("TC-AD-SHAPE-001 an absent ceiling refuses rather than permits")
        void absentMaxCpcRefuses() {
            // The failure this rules out: a bid whose ceiling could not be
            // computed being treated as a bid with no ceiling.
            assertThat(projection(new BigDecimal("30"), new BigDecimal("20"), null)
                    .exceedsMaxCpc()).isTrue();
            assertThat(projection(new BigDecimal("30"), new BigDecimal("20"),
                    new BigDecimal("25")).exceedsMaxCpc()).isFalse();
            assertThat(projection(new BigDecimal("30"), new BigDecimal("26"),
                    new BigDecimal("25")).exceedsMaxCpc()).isTrue();
        }

        @Test
        @DisplayName("TC-AD-SHAPE-002 a bid exactly at the ceiling is allowed")
        void bidAtTheCeilingIsAllowed() {
            assertThat(projection(new BigDecimal("30"), new BigDecimal("25.0000"),
                    new BigDecimal("25")).exceedsMaxCpc()).isFalse();
        }

        @Test
        @DisplayName("TC-AD-SHAPE-003 the change is a distance, in both directions")
        void changeIsADistance() {
            assertThat(projection(new BigDecimal("30"), new BigDecimal("20"), null)
                    .changeAmount()).isEqualByComparingTo("10");
            assertThat(projection(new BigDecimal("20"), new BigDecimal("30"), null)
                    .changeAmount()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("TC-AD-SHAPE-003b a bundle is named with its version or not at all")
        void bundleIsNamedWithItsVersion() {
            // The verdict records both, and the schema admits both or neither.
            // A half-named authority would be a PASS nobody can trace to the
            // policy that allowed it.
            assertThatThrownBy(() -> new AdvertisingBidProjection(ID, ID, ID, ID, ID,
                    "PROTECTION", null, "CAUSE", "COMPLETE", "HIGH", List.of(),
                    "PROTECTION_DECREASE", "MAX_CPC_BOUNDED", BigDecimal.ONE, BigDecimal.TEN,
                    "RUB", "CURRENCY_MAJOR", null, "NOT_AVAILABLE", null, 1, "a".repeat(64),
                    "MATERIAL_IMPACT", null, "b".repeat(64), BUNDLE, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(projection(new BigDecimal("30"), new BigDecimal("20"), null)
                    .authorised()).isTrue();
        }

        @Test
        @DisplayName("TC-AD-SHAPE-004 the blocker and axis lists cannot be mutated afterwards")
        void listsAreCopied() {
            List<String> blockers = new java.util.ArrayList<>(List.of("ONE"));
            AdvertisingBidProjection built = new AdvertisingBidProjection(ID, ID, ID, ID, ID,
                    "PROTECTION", null, "CAUSE", "COMPLETE", "HIGH", blockers,
                    "PROTECTION_DECREASE", "MAX_CPC_DERIVED", BigDecimal.ONE, BigDecimal.TEN,
                    "RUB", "CURRENCY_MAJOR", null, "NOT_AVAILABLE", null, 1, "a".repeat(64),
                    "MATERIAL_IMPACT", null, "b".repeat(64), BUNDLE, 3);

            blockers.add("TWO");
            assertThat(built.blockerCodes()).containsExactly("ONE");
            assertThat(built.exhaustedExposureAxes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("a preview tells an operator everything that stands in the way")
    class Preview {

        private static AdBidImpactPreview preview(boolean passed, List<String> gate,
                                                  List<String> unresolved) {
            return new AdBidImpactPreview(ID,
                    projection(new BigDecimal("30"), new BigDecimal("20"),
                            new BigDecimal("25")),
                    gate, unresolved,
                    new GuardrailVerdict(ID, GuardrailPurpose.IMPACT_PREVIEW, passed,
                            List.of(), null, null, Map.of(), "c".repeat(64)));
        }

        @Test
        @DisplayName("TC-AD-SHAPE-005 a passing verdict is not the same as a clear path")
        void passingVerdictIsNotAClearPath() {
            assertThat(preview(true, List.of(), List.of()).clear()).isTrue();
            assertThat(preview(true, List.of("KILL_SWITCH_ACTIVE"), List.of()).clear()).isFalse();
            assertThat(preview(true, List.of(), List.of("RESERVATION_NOT_HELD")).clear())
                    .isFalse();
            assertThat(preview(false, List.of(), List.of()).clear()).isFalse();
        }

        @Test
        @DisplayName("TC-AD-SHAPE-006 the affected variant count survives to the operator")
        void affectedCountIsCarried() {
            // The number that distinguishes a bid change from a price change.
            assertThat(preview(true, List.of(), List.of()).affectedVariantCount()).isEqualTo(47);
        }
    }

    @Nested
    @DisplayName("a proposal carries its own service level")
    class Proposal {

        private static AdvertisingBidProposal proposal(Duration window) {
            return new AdvertisingBidProposal("calculation", ID, ID, ID, ID, ID,
                    "PROTECTION_DECREASE", new BigDecimal("20.5000"), MetricWindow.D30,
                    new BigDecimal("700100"), Map.of(), "MEDIUM", 14, window, ID,
                    "d".repeat(64), List.of());
        }

        @Test
        @DisplayName("TC-AD-SHAPE-007 the parameters are exactly the three the contract admits")
        void parametersAreTheContractsThree() {
            assertThat(proposal(Duration.ofHours(4)).parameters())
                    .containsOnlyKeys("candidateId", "direction", "targetBid")
                    .containsEntry("targetBid", "20.5000");
        }

        @Test
        @DisplayName("TC-AD-SHAPE-007b an identity that is not the database's digest is refused")
        void identityMustBeTheDatabasesDigest() {
            // The approval compares itself against exactly this value. A
            // proposal carrying something else would make the "have the facts
            // moved" check compare two different things and pass by accident.
            assertThatThrownBy(() -> new AdvertisingBidProposal("calculation", ID, ID, ID, ID, ID,
                    "PROTECTION_DECREASE", new BigDecimal("20.5000"), MetricWindow.D30,
                    new BigDecimal("700100"), Map.of(), "MEDIUM", 14, Duration.ofHours(4), ID,
                    "not-a-digest", List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sixty-four hex");
        }

        @Test
        @DisplayName("TC-AD-SHAPE-008 a review window nobody can meet is not a service level")
        void impossibleReviewWindowIsRefused() {
            assertThatThrownBy(() -> proposal(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> proposal(Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("a scope is complete or it does not exist")
    class Scope {

        @Test
        @DisplayName("TC-AD-SHAPE-009 no element of a decision scope may be absent")
        void everyElementIsRequired() {
            assertThatThrownBy(() -> new AdvertisingDecisionScope(ID, ID, ID, ID, ID, null, ID,
                    "PROTECTION_DECREASE", "MAX_CPC_DERIVED", BigDecimal.TEN, BigDecimal.ONE,
                    "RUB", "CURRENCY_MAJOR", Instant.EPOCH))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reservationId");
        }

        @Test
        @DisplayName("TC-AD-SHAPE-010 the change a scope describes is a distance")
        void changeIsADistance() {
            assertThat(new AdvertisingDecisionScope(ID, ID, ID, ID, ID, ID, ID,
                    "PROTECTION_DECREASE", "MAX_CPC_DERIVED", new BigDecimal("30.0000"),
                    new BigDecimal("20.0000"), "RUB", "CURRENCY_MAJOR", Instant.EPOCH)
                    .changeAmount()).isEqualByComparingTo("10");
        }
    }
}
