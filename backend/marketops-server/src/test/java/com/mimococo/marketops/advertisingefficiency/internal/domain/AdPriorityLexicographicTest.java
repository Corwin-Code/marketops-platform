package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AdPriorityLexicographicTest {
    private static final AdPriorityPolicy.Weights WEIGHTS = new AdPriorityPolicy.Weights(BigDecimal.ONE,
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO);
    private static AdMeasure amount(String value) {
        return value == null ? AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE)
                : AdMeasure.available(new BigDecimal(value), AdEvidenceState.CANONICAL_CONFIRMED);
    }
    private static AdPriorityPolicy.Ranking protection(String loss, String spend, String critical, String due) {
        return AdPriorityPolicy.rank(new AdPriorityPolicy.Inputs(AdvertisingLane.PROTECTION, ProtectionTier.P1,
                amount(loss), amount(spend), amount(critical), amount(null), BigDecimal.ONE, BigDecimal.ONE,
                AdConfidence.HIGH, new BigDecimal(due), null, null, null, null, null), WEIGHTS);
    }
    @Test void earlierSloOutranksArbitrarilyLargeLaterCommercialTerms() {
        assertThat(AdPriorityPolicy.compare(protection("1", "1", "1", "-10"), "z",
                protection("999999999", "999999999", "999999999", "-20"), "a")).isNegative();
    }
    @Test void confirmedLossPrecedesSpendWithinTheSameDueTime() {
        assertThat(AdPriorityPolicy.compare(protection("10", "1", "1", "-10"), "z",
                protection("9", "999999999", "999999999", "-10"), "a")).isNegative();
    }
    @Test void criticalExposurePrecedesSpend() {
        assertThat(AdPriorityPolicy.compare(protection("10", "1", "2", "-10"), "z",
                protection("10", "999999999", "1", "-10"), "a")).isNegative();
    }
    @Test void missingIsExplicitAndDifferentFromConfirmedZero() {
        var missing = protection(null, "1", "1", "-10");
        var zero = protection("0", "1", "1", "-10");
        assertThat(missing.factors().get(1).value()).isNull();
        assertThat(missing.factors().get(1).displayNote()).startsWith("UNRESOLVED:");
        assertThat(AdPriorityPolicy.compare(zero, "z", missing, "a")).isNegative();
    }
    @Test void stableIdentityBreaksExactTies() {
        var rank = protection("10", "1", "2", "-10");
        assertThat(AdPriorityPolicy.compare(rank, "a", rank, "b")).isNegative();
    }
    @Test void unrelatedWeightsCannotChangeCanonicalOrder() {
        var normal = protection("10", "1", "2", "-10");
        var extreme = AdPriorityPolicy.rank(new AdPriorityPolicy.Inputs(AdvertisingLane.PROTECTION, ProtectionTier.P1,
                amount("10"), amount("1"), amount("2"), amount(null), BigDecimal.ONE, BigDecimal.ONE,
                AdConfidence.HIGH, new BigDecimal("-10"), null, null, null, null, null),
                new AdPriorityPolicy.Weights(BigDecimal.ZERO, new BigDecimal("999999999"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(AdPriorityPolicy.compare(normal, "same", extreme, "same")).isZero();
    }
    static AdPriorityPolicy.Ranking optimization(String absolute,String ratio,String spend) {
        return AdPriorityPolicy.rank(new AdPriorityPolicy.Inputs(AdvertisingLane.OPTIMIZATION,null,amount(null),amount(spend),amount(null),amount("100"),
                BigDecimal.ONE,BigDecimal.ONE,AdConfidence.HIGH,null,null,null,null,
                absolute==null?null:new BigDecimal(absolute),BigDecimal.ONE,ratio==null?null:new BigDecimal(ratio)),WEIGHTS);
    }
    @Test void independentProfitAxesRemainOrderedAcrossAllInputPermutations() {
        var ranked=java.util.Map.of("absolute",optimization("10","0","1"),"ratio",optimization("9","999999","999999"),
                "secondRatio",optimization("9","2","999999999"),"missing",optimization(null,null,"99999999999"));
        var keys=new java.util.ArrayList<>(ranked.keySet());
        for(int seed=0;seed<24;seed++) {
            java.util.Collections.shuffle(keys,new java.util.Random(seed));
            keys.sort((a,b)->AdPriorityPolicy.compare(ranked.get(a),a,ranked.get(b),b));
            assertThat(keys).containsExactly("absolute","ratio","secondRatio","missing");
        }
        assertThat(ranked.get("absolute").factors()).extracting(AdRankFactor::code)
                .containsSubsequence(AdRankFactor.Code.RECOVERABLE_CONTRIBUTION_PROFIT,AdRankFactor.Code.DUAL_AXIS_GAP,AdRankFactor.Code.DUAL_AXIS_PER_RUB_GAP,AdRankFactor.Code.OFFICIAL_SPEND_EXPOSURE);
    }

}
