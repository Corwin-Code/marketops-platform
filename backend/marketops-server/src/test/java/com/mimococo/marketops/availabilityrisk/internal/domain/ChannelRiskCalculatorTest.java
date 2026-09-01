package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.FRESHNESS_MINUTES;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.NOW;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.blockedDemand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.blockedLeadTime;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.channel;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.demand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.leadTime;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.profit;
import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChannelRiskCalculatorTest {

    @Test
    @DisplayName("TC-CHANNEL-001 a fresh zero is CRITICAL before demand is even consulted")
    void freshZeroIsCritical() {
        ChildRisk risk = ChannelRiskCalculator.calculate(
                channel(0, NOW.minus(Duration.ofMinutes(5)), Sellability.SELLABLE),
                blockedDemand(), blockedLeadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(risk.cause()).isEqualTo(RiskCause.CHANNEL_OUT_OF_STOCK);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.CONFIRMED);
        assertThat(risk.proof().established()).isTrue();
    }

    @Test
    @DisplayName("TC-CHANNEL-002 an unrelated blocked demand source cannot erase a fresh stockout")
    void unrelatedDefectDoesNotEraseTheStockout() {
        // Demand is unusable and the lead-time policy is missing. Neither has
        // anything to do with the exact observation that the shelf is empty.
        ChildRisk risk = ChannelRiskCalculator.calculate(
                channel(0, NOW.minus(Duration.ofMinutes(1)), Sellability.UNKNOWN),
                blockedDemand(), blockedLeadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(risk.actionable()).isTrue();
    }

    @Test
    @DisplayName("TC-CHANNEL-003 fresh and unsellable is CRITICAL whatever the quantity")
    void freshNotSellableIsCritical() {
        ChildRisk risk = ChannelRiskCalculator.calculate(
                channel(500, NOW.minus(Duration.ofMinutes(5)), Sellability.NOT_SELLABLE),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(risk.cause()).isEqualTo(RiskCause.CHANNEL_NOT_SELLABLE);
    }

    @Test
    @DisplayName("TC-CHANNEL-004 a stale observation is review, never current availability")
    void staleObservationIsReview() {
        ChildRisk risk = ChannelRiskCalculator.calculate(
                channel(400, NOW.minus(Duration.ofHours(48)), Sellability.SELLABLE),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.REVIEW);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.STALE);
        assertThat(risk.supply().provenUnits()).isZero();
        assertThat(risk.blockerCodes()).contains("CHANNEL_OBSERVATION_STALE");
    }

    @Test
    @DisplayName("TC-CHANNEL-005 a mode reported without a quantity is unresolved, not zero")
    void missingQuantityIsUnresolved() {
        ChildRisk risk = ChannelRiskCalculator.calculate(
                channel(null, NOW.minus(Duration.ofMinutes(5)), Sellability.SELLABLE),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.UNRESOLVED);
        assertThat(risk.evidenceState()).isEqualTo(RiskEvidenceState.UNKNOWN);
        assertThat(risk.cause()).isEqualTo(RiskCause.STOCK_DATA_DEFECT);
    }

    @Test
    @DisplayName("TC-CHANNEL-006 lanes come from the published policy, not from constants")
    void lanesFollowThePolicy() {
        // Horizon is 14 + 7 = 21 days at 10 units a day.
        assertThat(laneFor(60)).isEqualTo(AvailabilityLane.CRITICAL);   // 6 days <= safety 7
        assertThat(laneFor(100)).isEqualTo(AvailabilityLane.HIGH);      // 10 days <= lead 14
        assertThat(laneFor(180)).isEqualTo(AvailabilityLane.WATCH);     // 18 days <= horizon 21
        assertThat(laneFor(300)).isEqualTo(AvailabilityLane.HEALTHY);   // 30 days > horizon 21
    }

    @Test
    @DisplayName("TC-CHANNEL-007 a healthy channel names no cause")
    void healthyChannelHasNoCause() {
        ChildRisk risk = ChannelRiskCalculator.calculate(
                channel(300, NOW.minus(Duration.ofMinutes(5)), Sellability.SELLABLE),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.HEALTHY);
        assertThat(risk.cause()).isEqualTo(RiskCause.NONE);
        assertThat(risk.actionable()).isFalse();
    }

    @Test
    @DisplayName("TC-CHANNEL-008 unusable demand on a stocked channel is review, not healthy")
    void unusableDemandOnStockedChannelIsReview() {
        ChildRisk risk = ChannelRiskCalculator.calculate(
                channel(300, NOW.minus(Duration.ofMinutes(5)), Sellability.SELLABLE),
                blockedDemand(), leadTime(), profit(), FRESHNESS_MINUTES, NOW);

        assertThat(risk.lane()).isEqualTo(AvailabilityLane.REVIEW);
        assertThat(risk.cause()).isEqualTo(RiskCause.DEMAND_UNOBSERVABLE);
    }

    private AvailabilityLane laneFor(int units) {
        return ChannelRiskCalculator.calculate(
                channel(units, NOW.minus(Duration.ofMinutes(5)), Sellability.SELLABLE),
                demand("10"), leadTime(), profit(), FRESHNESS_MINUTES, NOW).lane();
    }
}
