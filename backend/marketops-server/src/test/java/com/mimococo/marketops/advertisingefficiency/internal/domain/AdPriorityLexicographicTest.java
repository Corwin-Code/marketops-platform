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
        assertThat(missing.factors().get(1).displayNote()).startsWith("PRIORITY_POLICY_UNRESOLVED:");
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

    private static AdPriorityPolicy.Ranking repair(BigDecimal... tuple) {
        return AdPriorityPolicy.rank(new AdPriorityPolicy.Inputs(AdvertisingLane.DATA_REPAIR,null,
                amount(null),tuple[2]==null?amount(null):amount(tuple[2].toPlainString()),amount(null),amount(null),
                null,tuple[5],AdConfidence.HIGH,tuple[4],tuple[0],tuple[1],tuple[3],null,null),WEIGHTS);
    }
    @Test void dataRepairEveryEarlierFactorVetoesAllLaterFactors() {
        var codes=java.util.List.of(AdRankFactor.Code.BLOCKED_PROTECTION,AdRankFactor.Code.BLAST_RADIUS,
                AdRankFactor.Code.OFFICIAL_SPEND_EXPOSURE,AdRankFactor.Code.BLOCKED_WORK,
                AdRankFactor.Code.HUMAN_SLO_URGENCY,AdRankFactor.Code.CASE_AGE);
        for(int decisive=0;decisive<codes.size();decisive++) {
            BigDecimal[] preferred=new BigDecimal[codes.size()],other=new BigDecimal[codes.size()];
            for(int index=0;index<codes.size();index++) {
                preferred[index]=index<decisive?BigDecimal.TEN:index==decisive?BigDecimal.ONE:BigDecimal.ZERO;
                other[index]=index<decisive?BigDecimal.TEN:index==decisive?BigDecimal.ZERO:new BigDecimal("999999999999");
            }
            var first=repair(preferred);var second=repair(other);
            assertThat(first.factors()).extracting(AdRankFactor::code).containsExactlyElementsOf(codes);
            assertThat(AdPriorityPolicy.compare(first,"z",second,"a")).as("decisive factor %s",codes.get(decisive)).isNegative();
            assertThat(AdPriorityPolicy.compare(second,"a",first,"z")).isPositive();
        }
    }
    @Test void dataRepairUnknownFactorsRemainUnknownAndNeverBecomeConfirmedZero() {
        for(int missingIndex=0;missingIndex<6;missingIndex++) {
            BigDecimal[] known=new BigDecimal[6],unknown=new BigDecimal[6];
            java.util.Arrays.fill(known,BigDecimal.ZERO);java.util.Arrays.fill(unknown,BigDecimal.ZERO);
            unknown[missingIndex]=null;
            var rank=repair(unknown);
            assertThat(rank.factors().get(missingIndex).value()).isNull();
            assertThat(rank.factors().get(missingIndex).displayNote()).startsWith("PRIORITY_POLICY_UNRESOLVED:");
            assertThat(AdPriorityPolicy.compare(repair(known),"z",rank,"a")).isNegative();
        }
    }
    @Test void watchUsesStableIdentityWithoutInventingCommercialFactors() {
        var high=AdPriorityPolicy.rank(new AdPriorityPolicy.Inputs(AdvertisingLane.WATCH,null,
                amount("999999999999"),amount("999999999999"),amount("999999999999"),amount("999999999999"),
                BigDecimal.ONE,new BigDecimal("999999999999"),AdConfidence.HIGH),WEIGHTS);
        var unknown=AdPriorityPolicy.rank(new AdPriorityPolicy.Inputs(AdvertisingLane.WATCH,null,
                amount(null),amount(null),amount(null),amount(null),null,null,AdConfidence.UNUSABLE),WEIGHTS);
        assertThat(high.factors()).isEmpty();assertThat(unknown.factors()).isEmpty();
        assertThat(AdPriorityPolicy.compare(unknown,"a",high,"z")).isNegative();
        assertThat(AdPriorityPolicy.compare(high,"z",unknown,"a")).isPositive();
        assertThat(AdPriorityPolicy.compare(high,"same",unknown,"same")).isZero();
    }

}
