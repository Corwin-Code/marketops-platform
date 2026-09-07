package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BidCandidateSetTest {
    private static final AdEvidenceState GRADE=AdEvidenceState.CANONICAL_CONFIRMED;
    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static MaxCpc ceiling(String value) {
        return new MaxCpc(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,new Money(n(value),"RUB"),GRADE,MaxCpc.Absence.NONE);
    }
    private static ProviderBidGrid grid(String unit,String step,int precision) {
        return new ProviderBidGrid(unit,"RUB",precision,n(step),n(step),n("100000"),true,"VERIFIED");
    }

    @Test void finiteOrderedSetIsDeterministicAndAZeroPolicyGeneratesNothing() {
        var limits=new BidStepLimits(n("0.5"),n("100"),n("0.1"));var grid=grid("CURRENCY_MAJOR","1",0);
        var endpoint=BidCandidate.decrease(AdMeasure.available(n("100"),GRADE),ceiling("90"),limits,grid,
                BidCandidate.MAX_CPC_BOUNDED).orElseThrow();
        var set=BidCandidateSet.generate(endpoint,3,limits,grid,ceiling("90"),true);
        assertThat(set).extracting(BidCandidate::providerNormalizedAmount).containsExactly(n("93.0000"),n("87.0000"),n("81.0000"));
        assertThat(BidCandidateSet.generate(endpoint,3,limits,grid,ceiling("90"),true)).isEqualTo(set);
        assertThat(BidCandidateSet.generate(endpoint,0,limits,grid,ceiling("90"),true)).isEmpty();
    }

    @Test void intermediateProtectionRequiresExplicitPolicyPermission() {
        var limits=new BidStepLimits(n("0.5"),n("100"),n("0.1"));var grid=grid("CURRENCY_MAJOR","1",0);
        var endpoint=BidCandidate.decrease(AdMeasure.available(n("100"),GRADE),ceiling("90"),limits,grid,
                BidCandidate.MAX_CPC_BOUNDED).orElseThrow();
        assertThat(BidCandidateSet.generate(endpoint,3,limits,grid,ceiling("90"),false))
                .extracting(BidCandidate::providerNormalizedAmount).containsExactly(n("81.0000"));
    }

    @Test void providerRoundingCannotCrossTheAuthorizedAbsoluteOrRelativeFloor() {
        var limits=new BidStepLimits(n("0.01"),n("1"),n("0.1"));var grid=grid("CURRENCY_MAJOR","10",0);
        var endpoint=new BidCandidate(BidCandidate.PROTECTION_DECREASE,BidCandidate.MAX_CPC_BOUNDED,
                n("100"),n("99"),n("90"),"RUB","CURRENCY_MAJOR");
        assertThat(BidCandidateSet.generate(endpoint,4,limits,grid,ceiling("30"),true)).isEmpty();
    }

    @Test void minorUnitBidComparesAgainstConvertedEconomicsAndAbsoluteBounds() {
        var limits=new BidStepLimits(n("0.2"),n("0.50"),n("0.1"));var grid=grid("CURRENCY_MINOR","1",0);
        var candidate=BidCandidate.increase(AdMeasure.available(n("1000"),GRADE),ceiling("100"),limits,grid,
                BidCandidate.MAX_CPC_BOUNDED).orElseThrow();
        assertThat(candidate.providerNormalizedAmount()).isEqualByComparingTo("1050");
        assertThat(AdBidUnitConversion.toMajor(candidate.changeAmount(),candidate.bidUnitCode()))
                .isEqualByComparingTo("0.50");
        assertThat(BidCandidateSet.generate(candidate,2,limits,grid,ceiling("100"),false))
                .extracting(BidCandidate::providerNormalizedAmount).containsExactly(n("1025.0000"),n("1050.0000"));
    }

    @Test void unknownDenominationNeverProducesAUsableGrid() {
        assertThat(grid("UNKNOWN","1",0).usable()).isFalse();
    }
}
