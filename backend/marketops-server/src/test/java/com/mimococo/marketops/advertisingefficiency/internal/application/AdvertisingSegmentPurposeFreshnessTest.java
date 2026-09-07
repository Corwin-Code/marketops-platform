package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.ObjectFactAggregate;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdvertisingSegmentPurposeFreshnessTest {
    static final UUID ID=new UUID(0,1);
    static final Instant AT=Instant.parse("2026-09-01T12:00:00Z");
    static final String PURPOSE="PROTECTION_BID_WRITE";
    @ParameterizedTest @ValueSource(strings={"OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC"})
    void latestAcceptanceCannotHideAnOldConsumedSegmentEvenWithinTheSourceBound(String kind) {
        var assessment=AdvertisingPurposeFreshness.assess(evidence(kind,90*60),PURPOSE,List.of(kind)).getFirst();
        assertThat(assessment.sourceTime()).isEqualTo(AT.minusSeconds(90*60));
        assertThat(assessment.acceptedAt()).isEqualTo(AT.minusSeconds(90*60));
        assertThat(assessment.eligible()).isFalse();
        assertThat(assessment.reasonCodes()).contains("FRESHNESS_BOUND_UNMET:"+PURPOSE+":"+kind);
    }
    @ParameterizedTest @ValueSource(strings={"OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC"})
    void everySegmentInsideBothDeclaredBoundsRemainsEligible(String kind) {
        assertThat(AdvertisingPurposeFreshness.assess(evidence(kind,30*60),PURPOSE,List.of(kind)).getFirst().eligible()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"OFFICIAL_AD_SPEND","OFFICIAL_AD_TRAFFIC"})
    void aFreshOlderSegmentCannotHideAFutureSourceInAnotherConsumedSegment(String kind) {
        var assessed=AdvertisingPurposeFreshness.assess(evidence(kind,30*60,true),PURPOSE,List.of(kind)).getFirst();
        assertThat(assessed.sourceTime()).isEqualTo(AT.minusSeconds(90*60));
        assertThat(assessed.eligible()).isFalse();
        assertThat(assessed.reasonCodes()).contains("FRESHNESS_INPUT_TIME_UNRESOLVED:"+PURPOSE+":"+kind);
    }
    @ParameterizedTest @ValueSource(strings={"SOURCE_FUTURE","SOURCE_NULL","ACCEPTED_FUTURE","ACCEPTED_NULL"})
    void aFreshLinkedLineCannotHideAnotherConsumedLinesInvalidTime(String mutation) {
        String kind="AD_LINKED_SALE_EVENT";
        var base=evidence(kind,30*60);
        Instant source=mutation.equals("SOURCE_NULL")?null:mutation.equals("SOURCE_FUTURE")?AT.plusSeconds(1):AT;
        Instant accepted=mutation.equals("ACCEPTED_NULL")?null:mutation.equals("ACCEPTED_FUTURE")?AT.plusSeconds(1):AT;
        var good=line(AT.minusSeconds(60),AT.minusSeconds(60));
        var sales=new com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.LinkedSaleAggregate(
                2,BigDecimal.TEN,"RUB",1,ID,List.of(good,line(source,accepted)));
        var evidence=new AdvertisingEvidenceGatherer.Evidence(null,Optional.empty(),Optional.empty(),base.objectFacts(),Optional.of(sales),Optional.empty(),
                List.of(),null,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Map.of(),Map.of(),base.windowStart(),AT,base.authorities());
        var assessed=AdvertisingPurposeFreshness.assess(evidence,PURPOSE,List.of(kind)).getFirst();
        assertThat(assessed.eligible()).isFalse();
        assertThat(assessed.reasonCodes()).contains("FRESHNESS_INPUT_TIME_UNRESOLVED:"+PURPOSE+":"+kind);
    }
    private static com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.LinkedSaleLine
            line(Instant source,Instant accepted) {
        return new com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository.LinkedSaleLine(
                ID,ID,ID,ID,ID,ID,"CANONICAL_AD_LINKED_COMPLETED_SALE","DETERMINISTIC_OBJECT_LINKAGE",1,BigDecimal.ONE,"RUB",
                AT.minusSeconds(86400),AT,source,accepted);
    }
    private static AdvertisingEvidenceGatherer.Evidence evidence(String kind,int oldestAcceptanceAge) {
        return evidence(kind,oldestAcceptanceAge,false);
    }
    private static AdvertisingEvidenceGatherer.Evidence evidence(String kind,int oldestAcceptanceAge,boolean futureSource) {
        var aggregate=new ObjectFactAggregate(new BigDecimal("100"),"RUB",100L,100L,100L,10L,new BigDecimal("500"),
                true,false,AT.minusSeconds(90*60),futureSource?AT.plusSeconds(1):AT,2,ID,BigDecimal.ONE,AT,AT.minusSeconds(86400),AT,AT.minusSeconds(oldestAcceptanceAge));
        // Distinct declared source/acceptance bounds isolate the acceptance-age rule.
        var profile=new FreshnessProfile(ID,1,kind,PURPOSE,120,60,0,0,true,true,BigDecimal.ONE,"CANONICAL_CONFIRMED",false,AT.plusSeconds(3600));
        return new AdvertisingEvidenceGatherer.Evidence(null,Optional.empty(),Optional.empty(),Optional.of(aggregate),Optional.empty(),Optional.empty(),
                List.of(),null,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Map.of(),Map.of(),AT.minusSeconds(86400),AT,
                new AdvertisingEvidenceGatherer.Authorities(Map.of(),Map.of(PURPOSE+":"+kind,profile),Map.of(),false,List.of(),Map.of(),false));
    }
}
