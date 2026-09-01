package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.FRESHNESS_MINUTES;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.NOW;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.channel;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.demand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.leadTime;
import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.ProfitLane;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriorityPolicyTest {

    private static final PriorityPolicyVersion POLICY = new PriorityPolicyVersion(
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"), 1,
            BigDecimal.valueOf(400), BigDecimal.valueOf(300), BigDecimal.valueOf(200),
            BigDecimal.valueOf(100), BigDecimal.valueOf(-150));

    @Test
    @DisplayName("TC-RANK-001 a critical card cannot be overtaken by a more valuable high one")
    void criticalOutranksAnyCommerciallyRicherHigh() {
        ChildRisk critical = channelRisk(60, "10", "1.0000");         // 6 days cover
        ChildRisk high = channelRisk(100, "10", "9999999.0000");      // 10 days cover, huge profit

        assertThat(critical.lane()).isEqualTo(AvailabilityLane.CRITICAL);
        assertThat(high.lane()).isEqualTo(AvailabilityLane.HIGH);
        assertThat(PriorityPolicy.rank(critical, BigDecimal.ONE, POLICY).score())
                .isGreaterThan(PriorityPolicy.rank(high, BigDecimal.ONE, POLICY).score());
    }

    @Test
    @DisplayName("TC-RANK-002 every permitted factor is exposed with its own contribution")
    void allFactorsAreVisible() {
        PriorityPolicy.Ranking ranking =
                PriorityPolicy.rank(channelRisk(100, "10", "120.0000"),
                        new BigDecimal("0.5"), POLICY);

        assertThat(ranking.factors()).extracting(RankFactor::code)
                .containsExactlyInAnyOrder(RankFactor.Code.values());
        assertThat(ranking.factors()).allSatisfy(factor ->
                assertThat(factor.displayNote()).isNotBlank());
    }

    @Test
    @DisplayName("TC-RANK-003 confidence lowers commercial order but never the lane band")
    void confidencePenaltyStaysInsideItsBand() {
        ChildRisk confident = channelRisk(60, "10", "500.0000");
        PriorityPolicy.Ranking ranking = PriorityPolicy.rank(confident, BigDecimal.ONE, POLICY);

        BigDecimal band = PriorityPolicy.LANE_BAND
                .multiply(BigDecimal.valueOf(PriorityPolicy.band(AvailabilityLane.CRITICAL)));
        assertThat(ranking.score()).isGreaterThanOrEqualTo(band);
        assertThat(ranking.score()).isLessThan(band.add(PriorityPolicy.LANE_BAND));
    }

    @Test
    @DisplayName("TC-RANK-004 an evidence-limited lane ranks with HIGH, not below WATCH")
    void evidenceLimitedLanesAreNotBuried() {
        assertThat(PriorityPolicy.band(AvailabilityLane.UNRESOLVED))
                .isEqualTo(PriorityPolicy.band(AvailabilityLane.HIGH));
        assertThat(PriorityPolicy.band(AvailabilityLane.REVIEW))
                .isGreaterThan(PriorityPolicy.band(AvailabilityLane.WATCH));
    }

    @Test
    @DisplayName("TC-RANK-005 ranking the same risk twice produces the same score")
    void rankingIsDeterministic() {
        ChildRisk risk = channelRisk(100, "10", "120.0000");
        assertThat(PriorityPolicy.rank(risk, BigDecimal.ONE, POLICY).score())
                .isEqualByComparingTo(PriorityPolicy.rank(risk, BigDecimal.ONE, POLICY).score());
    }

    private ChildRisk channelRisk(int units, String perDay, String profitPerUnit) {
        ProfitAssessment profit = new ProfitAssessment(ProfitLane.CONFIRMED_ELIGIBLE,
                new BigDecimal(profitPerUnit), "RUB",
                UUID.fromString("00000000-0000-0000-0000-0000000000b1"), "settled");
        return ChannelRiskCalculator.calculate(
                channel(units, NOW.minus(Duration.ofMinutes(5)), Sellability.SELLABLE),
                demand(perDay), leadTime(), profit, FRESHNESS_MINUTES, NOW);
    }
}
