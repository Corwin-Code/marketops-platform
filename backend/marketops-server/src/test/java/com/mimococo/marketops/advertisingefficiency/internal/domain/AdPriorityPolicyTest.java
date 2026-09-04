package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The band arithmetic that stops a commercial number buying a higher rank.
 *
 * <p>The band constants are asserted literally here because the SQL read path
 * mirrors them, and a change on one side that is not made on the other would
 * quietly reorder the queue.
 */
class AdPriorityPolicyTest {

    private static final AdPriorityPolicy.Weights HEAVY_COMMERCIAL = new AdPriorityPolicy.Weights(
            new BigDecimal("99999"), new BigDecimal("99999"), new BigDecimal("99999"),
            new BigDecimal("99999"), new BigDecimal("99999"), new BigDecimal("99999"),
            BigDecimal.ZERO);

    private static final AdPriorityPolicy.Weights BALANCED = new AdPriorityPolicy.Weights(
            new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("80"),
            new BigDecimal("60"), new BigDecimal("10"), new BigDecimal("5"),
            new BigDecimal("-20"));

    private static AdMeasure money(String value) {
        return AdMeasure.available(new BigDecimal(value), AdEvidenceState.CANONICAL_CONFIRMED);
    }

    private static AdPriorityPolicy.Inputs inputs(
            AdvertisingLane lane, ProtectionTier tier, String exposure, AdConfidence confidence) {
        return new AdPriorityPolicy.Inputs(lane, tier,
                money(exposure), money(exposure), money(exposure), money(exposure),
                new BigDecimal("1.0"), new BigDecimal("3"), confidence);
    }

    @Test
    @DisplayName("TC-ADV-RANK-001 the band constant is 100000 and the tier order is fixed")
    void bandConstantsArePinned() {
        assertThat(AdPriorityPolicy.BAND).isEqualByComparingTo("100000");
        assertThat(AdPriorityPolicy.band(AdvertisingLane.PROTECTION, ProtectionTier.P0)).isEqualTo(6);
        assertThat(AdPriorityPolicy.band(AdvertisingLane.PROTECTION, ProtectionTier.P1)).isEqualTo(5);
        assertThat(AdPriorityPolicy.band(AdvertisingLane.PROTECTION, ProtectionTier.P2)).isEqualTo(4);
        assertThat(AdPriorityPolicy.band(AdvertisingLane.PROTECTION, ProtectionTier.P3)).isEqualTo(3);
        assertThat(AdPriorityPolicy.band(AdvertisingLane.DATA_REPAIR, null)).isEqualTo(2);
        assertThat(AdPriorityPolicy.band(AdvertisingLane.OPTIMIZATION, null)).isEqualTo(1);
        assertThat(AdPriorityPolicy.band(AdvertisingLane.WATCH, null)).isEqualTo(0);
    }

    @Test
    @DisplayName("TC-ADV-RANK-002 the largest conceivable opportunity cannot outrank the smallest data defect")
    void opportunityCannotOutrankDataRepair() {
        AdPriorityPolicy.Ranking opportunity = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.OPTIMIZATION, null, "999999999", AdConfidence.HIGH),
                HEAVY_COMMERCIAL);
        AdPriorityPolicy.Ranking defect = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.DATA_REPAIR, null, "0", AdConfidence.UNUSABLE),
                HEAVY_COMMERCIAL);

        assertThat(opportunity.score()).isLessThan(defect.score());
    }

    @Test
    @DisplayName("TC-ADV-RANK-003 the largest conceivable data defect cannot outrank the weakest Protection case")
    void dataRepairCannotOutrankProtection() {
        AdPriorityPolicy.Ranking defect = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.DATA_REPAIR, null, "999999999", AdConfidence.HIGH),
                HEAVY_COMMERCIAL);
        AdPriorityPolicy.Ranking weakestProtection = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.PROTECTION, ProtectionTier.P3, "0", AdConfidence.UNUSABLE),
                HEAVY_COMMERCIAL);

        assertThat(defect.score()).isLessThan(weakestProtection.score());
    }

    @Test
    @DisplayName("TC-ADV-RANK-004 a P2 loss of any size cannot outrank a P0 integrity question")
    void protectionSubTiersDoNotCompensate() {
        AdPriorityPolicy.Ranking hugeLoss = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.PROTECTION, ProtectionTier.P2, "999999999", AdConfidence.HIGH),
                HEAVY_COMMERCIAL);
        AdPriorityPolicy.Ranking integrity = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.PROTECTION, ProtectionTier.P0, "0", AdConfidence.UNUSABLE),
                HEAVY_COMMERCIAL);

        assertThat(hugeLoss.score()).isLessThan(integrity.score());
    }

    @Test
    @DisplayName("TC-ADV-RANK-005 the commercial part is clamped strictly below one band")
    void commercialPartIsClampedBelowOneBand() {
        AdPriorityPolicy.Ranking saturated = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.WATCH, null, "999999999", AdConfidence.HIGH),
                HEAVY_COMMERCIAL);

        assertThat(saturated.score()).isLessThan(new BigDecimal("100000"));
        assertThat(saturated.score()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("TC-ADV-RANK-006 every named term is emitted even at zero")
    void everyTermIsEmitted() {
        AdPriorityPolicy.Ranking ranking = AdPriorityPolicy.rank(
                new AdPriorityPolicy.Inputs(AdvertisingLane.WATCH, null,
                        AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                        AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                        AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                        AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                        null, null, AdConfidence.HIGH),
                BALANCED);

        assertThat(ranking.factors()).hasSize(AdRankFactor.Code.values().length);
        assertThat(ranking.factors()).extracting(AdRankFactor::code)
                .containsExactlyInAnyOrder(AdRankFactor.Code.values());
    }

    @Test
    @DisplayName("TC-ADV-RANK-007 uncertainty lowers a rank inside its band and never leaves it")
    void confidenceOnlySubtractsInsideTheBand() {
        AdPriorityPolicy.Ranking confident = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.OPTIMIZATION, null, "1000", AdConfidence.HIGH), BALANCED);
        AdPriorityPolicy.Ranking uncertain = AdPriorityPolicy.rank(
                inputs(AdvertisingLane.OPTIMIZATION, null, "1000", AdConfidence.UNUSABLE), BALANCED);

        assertThat(uncertain.score()).isLessThan(confident.score());
        assertThat(uncertain.score()).isGreaterThanOrEqualTo(new BigDecimal("100000"));
        assertThat(uncertain.score()).isLessThan(new BigDecimal("200000"));
    }

    @Test
    @DisplayName("TC-ADV-RANK-008 an absent policy ranks by severity alone and invents no weights")
    void absentPolicyRanksBySeverityOnly() {
        AdPriorityPolicy.Ranking ranking =
                AdPriorityPolicy.unranked(AdvertisingLane.PROTECTION, ProtectionTier.P1);

        assertThat(ranking.score()).isEqualByComparingTo("500000");
        assertThat(ranking.factors()).isEmpty();
    }

    @Test
    @DisplayName("TC-ADV-RANK-009 a positive confidence weight is refused")
    void positiveConfidenceWeightIsRefused() {
        assertThatThrownBy(() -> new AdPriorityPolicy.Weights(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only subtract");
    }

    @Test
    @DisplayName("TC-ADV-RANK-010 a Protection rank without a sub-tier is refused")
    void protectionWithoutTierIsRefused() {
        assertThatThrownBy(() -> AdPriorityPolicy.band(AdvertisingLane.PROTECTION, null))
                .isInstanceOf(NullPointerException.class);
    }
}
