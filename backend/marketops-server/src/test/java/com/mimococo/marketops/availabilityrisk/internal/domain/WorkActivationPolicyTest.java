package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.FRESHNESS_MINUTES;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.NOW;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.blockedDemand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.channel;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.demand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.leadTime;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.profit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkActivationPolicyTest {

    private static final UUID POLICY = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Test
    @DisplayName("TC-ACTIVATE-001 a critical lane activates on the cycle that found it")
    void criticalActivatesImmediately() {
        ChildRisk critical = channelRisk(0, "10");

        assertThat(critical.lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(policy(3).decide(critical, 1, NOW)).isPresent();
    }

    @Test
    @DisplayName("TC-ACTIVATE-002 a high lane waits for its sustained condition")
    void highWaitsForTheSustainedCondition() {
        ChildRisk high = channelRisk(100, "10");                      // 10 days cover
        WorkActivationPolicy policy = policy(3);

        assertThat(high.lane()).isEqualTo(AvailabilityLane.HIGH);
        assertThat(policy.decide(high, 1, NOW)).isEmpty();
        assertThat(policy.decide(high, 2, NOW)).isEmpty();
        assertThat(policy.decide(high, 3, NOW)).isPresent();
    }

    @Test
    @DisplayName("TC-ACTIVATE-003 the qualifying cycle activates rather than the one after it")
    void highActivatesInTheQualifyingCycle() {
        WorkActivationPolicy.Activation activation =
                policy(2).decide(channelRisk(100, "10"), 2, NOW).orElseThrow();

        assertThat(activation.reason()).contains("2 of 2");
        assertThat(activation.actionDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(240)));
    }

    @Test
    @DisplayName("TC-ACTIVATE-004 a watch lane never raises work by itself")
    void watchNeverActivates() {
        ChildRisk watch = channelRisk(180, "10");                     // 18 days cover

        assertThat(watch.lane()).isEqualTo(AvailabilityLane.WATCH);
        assertThat(policy(1).decide(watch, 99, NOW)).isEmpty();
    }

    @Test
    @DisplayName("TC-ACTIVATE-005 a healthy lane raises nothing however long it holds")
    void healthyNeverActivates() {
        ChildRisk healthy = channelRisk(1000, "10");                  // 100 days cover

        assertThat(healthy.lane()).isEqualTo(AvailabilityLane.HEALTHY);
        assertThat(healthy.cause()).isEqualTo(RiskCause.NONE);
        assertThat(policy(1).decide(healthy, 99, NOW)).isEmpty();
    }

    @Test
    @DisplayName("TC-ACTIVATE-006 a blocker activates on its first sighting under its own SLA")
    void blockerActivatesImmediatelyOnItsOwnClock() {
        ChildRisk blocked = ChannelRiskCalculator.calculate(
                channel(50, NOW, Sellability.SELLABLE), blockedDemand(), leadTime(), profit(),
                FRESHNESS_MINUTES, NOW);
        WorkActivationPolicy.Activation activation =
                policy(5).decide(blocked, 1, NOW).orElseThrow();

        assertThat(blocked.cause().blocker()).isTrue();
        // The blocker clock, not the high clock its lane would otherwise use.
        assertThat(activation.actionDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(120)));
        assertThat(activation.reason()).contains(RiskCause.DEMAND_UNOBSERVABLE.name());
    }

    @Test
    @DisplayName("TC-ACTIVATE-007 the outcome clock starts at the action deadline, not now")
    void outcomeClockFollowsTheActionDeadline() {
        WorkActivationPolicy.Activation activation =
                policy(1).decide(channelRisk(0, "10"), 1, NOW).orElseThrow();

        assertThat(activation.actionDueAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(activation.outcomeDueAt())
                .isEqualTo(activation.actionDueAt().plus(Duration.ofMinutes(2880)));
        assertThat(activation.outcomeDueAt()).isAfter(activation.actionDueAt());
    }

    @Test
    @DisplayName("TC-ACTIVATE-008 a sustained condition of zero cycles is not a condition")
    void aSustainedConditionMustBeAtLeastOneCycle() {
        assertThatThrownBy(() -> policy(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a condition");
    }

    private static WorkActivationPolicy policy(int highSustainedCycles) {
        return new WorkActivationPolicy(POLICY, 2, highSustainedCycles,
                Duration.ofMinutes(30), Duration.ofMinutes(240), Duration.ofMinutes(120),
                Duration.ofMinutes(2880), Duration.ofMinutes(1440));
    }

    private static ChildRisk channelRisk(int units, String perDay) {
        return ChannelRiskCalculator.calculate(channel(units, NOW, Sellability.SELLABLE),
                demand(perDay), leadTime(), profit(), FRESHNESS_MINUTES, NOW);
    }
}
