package com.mimococo.marketops.advertisingefficiency.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingEvidenceRepository;
import com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc.AdvertisingPolicyRepository.FreshnessProfile;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdvertisingPhysicalPurposeFreshnessTest {
    static final UUID ID=new UUID(0,1), OTHER=new UUID(0,2);
    static final Instant AT=Instant.parse("2026-09-01T12:00:00Z");
    static final String PURPOSE="PROTECTION_BID_WRITE";

    @ParameterizedTest @ValueSource(strings={"SELLABILITY","AVAILABILITY"})
    void aConfirmedLabelCannotPromoteAnUnknownPhysicalState(String kind) {
        var states=Map.of(ID,new AdvertisingEvidenceGatherer.VariantAvailability(
                kind.equals("SELLABILITY")?"UNKNOWN":"SELLABLE",
                kind.equals("AVAILABILITY")?"UNKNOWN":"AVAILABLE",AT,"CANONICAL_CONFIRMED",List.of(ID)));
        assertThat(AdvertisingPurposeFreshness.assess(evidence(kind,List.of(ID),states),PURPOSE,List.of(kind)).getFirst().eligible()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"SELLABILITY","AVAILABILITY"})
    void completeKnownStatePreservesIndependentPhysicalPurpose(String kind) {
        var states=Map.of(ID,new AdvertisingEvidenceGatherer.VariantAvailability("SELLABLE","AVAILABLE",AT,"CANONICAL_CONFIRMED",List.of(ID)));
        assertThat(AdvertisingPurposeFreshness.assess(evidence(kind,List.of(ID),states),PURPOSE,List.of(kind)).getFirst().eligible()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"SELLABILITY","AVAILABILITY"})
    void oneKnownVariantCannotStandForTheOtherAffectedVariant(String kind) {
        var states=Map.of(ID,new AdvertisingEvidenceGatherer.VariantAvailability("SELLABLE","AVAILABLE",AT,"CANONICAL_CONFIRMED",List.of(ID)));
        assertThat(AdvertisingPurposeFreshness.assess(evidence(kind,List.of(ID,OTHER),states),PURPOSE,List.of(kind)).getFirst().eligible()).isFalse();
    }
    private static AdvertisingEvidenceGatherer.Evidence evidence(String kind,List<UUID> products,
            Map<UUID,AdvertisingEvidenceGatherer.VariantAvailability> states) {
        var set=new AdvertisingEvidenceRepository.AffectedSetRow(ID,"a".repeat(64),products,products,"COMPLETE",List.of(),AT);
        var profile=new FreshnessProfile(ID,1,kind,PURPOSE,30,30,0,0,true,true,BigDecimal.ONE,"CANONICAL_CONFIRMED",false,AT.plusSeconds(3600));
        return new AdvertisingEvidenceGatherer.Evidence(null,Optional.of(set),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),
                List.of(),null,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Map.of(),states,AT.minusSeconds(86400),AT,
                new AdvertisingEvidenceGatherer.Authorities(Map.of(),Map.of(PURPOSE+":"+kind,profile),Map.of(),false,List.of(),Map.of(),false));
    }
}
