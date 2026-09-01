package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.FRESHNESS_MINUTES;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.NOW;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.blockedDemand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.carriedForwardDemand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.channel;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.demand;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.leadTime;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.profit;
import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.RiskCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutcomeConditionTest {

    @Test
    @DisplayName("TC-OUTCOME-001 a shortage is repaired when the lane falls back below activation")
    void aShortageIsRepairedByTheLaneFalling() {
        assertThat(OutcomeCondition.holds(RiskCause.CHANNEL_COVER_SHORT, healthy())).isTrue();
        assertThat(OutcomeCondition.holds(RiskCause.CHANNEL_COVER_SHORT, coverShort())).isFalse();
    }

    @Test
    @DisplayName("TC-OUTCOME-002 a still-present cause is never a repair, whatever the lane says")
    void theSameCauseIsNeverARepair() {
        assertThat(OutcomeCondition.holds(RiskCause.CHANNEL_OUT_OF_STOCK, outOfStock())).isFalse();
    }

    @Test
    @DisplayName("TC-OUTCOME-003 evidence that cannot establish safety cannot establish a repair")
    void unusableEvidenceCannotRepairAnything() {
        ChildRisk carriedForward = ChannelRiskCalculator.calculate(
                channel(600, NOW, Sellability.SELLABLE), carriedForwardDemand("6"), leadTime(),
                profit(), FRESHNESS_MINUTES, NOW);

        assertThat(carriedForward.lane().safe()).isTrue();
        assertThat(OutcomeCondition.holds(RiskCause.CHANNEL_OUT_OF_STOCK, carriedForward))
                .as("a source that went quiet looks exactly like a source reporting good news")
                .isFalse();
    }

    @Test
    @DisplayName("TC-OUTCOME-004 a defect is repaired when the defect is gone, not when the lane is")
    void aDefectIsJudgedByTheDefect() {
        ChildRisk blocked = ChannelRiskCalculator.calculate(
                channel(50, NOW, Sellability.SELLABLE), blockedDemand(), leadTime(), profit(),
                FRESHNESS_MINUTES, NOW);
        assertThat(blocked.cause()).isEqualTo(RiskCause.DEMAND_UNOBSERVABLE);

        // The defect is gone and the answer is now a real shortage. That is a
        // different case with a different owner, and the data owner's case is
        // repaired even though the card is still red.
        assertThat(OutcomeCondition.holds(RiskCause.DEMAND_UNOBSERVABLE, coverShort())).isTrue();
        assertThat(OutcomeCondition.holds(RiskCause.DEMAND_UNOBSERVABLE, blocked)).isFalse();
    }

    @Test
    @DisplayName("TC-OUTCOME-005 nothing is ever repaired for a cause that was never a problem")
    void thereIsNothingToRepairForNoCause() {
        assertThat(OutcomeCondition.holds(RiskCause.NONE, healthy())).isFalse();
    }

    /** 600 units against six a day: a hundred days of cover. */
    private static ChildRisk healthy() {
        return ChannelRiskCalculator.calculate(channel(600, NOW, Sellability.SELLABLE),
                demand("6"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);
    }

    /** 60 units against six a day: ten days, inside the fourteen-day lead time. */
    private static ChildRisk coverShort() {
        return ChannelRiskCalculator.calculate(channel(60, NOW, Sellability.SELLABLE),
                demand("6"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);
    }

    private static ChildRisk outOfStock() {
        return ChannelRiskCalculator.calculate(channel(0, NOW, Sellability.SELLABLE),
                demand("6"), leadTime(), profit(), FRESHNESS_MINUTES, NOW);
    }
}
