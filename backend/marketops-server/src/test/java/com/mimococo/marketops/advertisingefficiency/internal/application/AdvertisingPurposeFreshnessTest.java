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
import org.junit.jupiter.api.Test;

class AdvertisingPurposeFreshnessTest {
    private static final UUID ID = new UUID(0, 1);
    private static final Instant AT = Instant.parse("2026-09-01T12:00:00Z");
    private static final String KIND = "OFFICIAL_AD_SPEND";
    private static FreshnessProfile profile(String purpose, int minutes, Instant expires) {
        return new FreshnessProfile(ID, 1, KIND, purpose, minutes, 60, 0, 0, true, true,
                BigDecimal.ONE, "CANONICAL_CONFIRMED", false, expires);
    }
    private static AdvertisingEvidenceGatherer.Evidence evidence(Instant source, boolean correction,
            BigDecimal coverage, Map<String, FreshnessProfile> profiles) {
        var facts = new AdvertisingEvidenceRepository.ObjectFactAggregate(new BigDecimal("100"), "RUB",
                100L, 100L, 100L, 10L, new BigDecimal("500"), true, correction, source, source, 1, ID,
                coverage, AT, AT.minusSeconds(86400), AT);
        return new AdvertisingEvidenceGatherer.Evidence(null, Optional.empty(), Optional.empty(), Optional.of(facts),
                Optional.empty(), Optional.empty(), List.of(), null, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Map.of(), AT.minusSeconds(86400), AT,
                new AdvertisingEvidenceGatherer.Authorities(Map.of(), profiles, Map.of(), false, List.of(), Map.of(), false));
    }
    @Test void newlyAcceptedOldSourceIsStillStale() {
        var e = evidence(AT.minusSeconds(3600), false, BigDecimal.ONE,
                Map.of("TASK_ACTIVATION:" + KIND, profile("TASK_ACTIVATION", 30, null)));
        assertThat(AdvertisingPurposeFreshness.failures(e, "TASK_ACTIVATION", List.of(KIND))).isNotEmpty();
    }
    @Test void taskVisibilityCannotPromoteTheSameFactIntoWriteGrade() {
        var e = evidence(AT.minusSeconds(2700), false, BigDecimal.ONE,
                Map.of("TASK_ACTIVATION:" + KIND, profile("TASK_ACTIVATION", 120, null),
                        "OPTIMIZATION_BID_WRITE:" + KIND, profile("OPTIMIZATION_BID_WRITE", 30, null)));
        assertThat(AdvertisingPurposeFreshness.failures(e, "TASK_ACTIVATION", List.of(KIND))).isEmpty();
        assertThat(AdvertisingPurposeFreshness.failures(e, "OPTIMIZATION_BID_WRITE", List.of(KIND))).isNotEmpty();
    }
    @Test void deadlineComesFromActualSourceAndProfileExpiry() {
        var e = evidence(AT.minusSeconds(600), false, BigDecimal.ONE,
                Map.of("TASK_ACTIVATION:" + KIND, profile("TASK_ACTIVATION", 30, AT.plusSeconds(100))));
        var result = AdvertisingPurposeFreshness.assess(e, "TASK_ACTIVATION", List.of(KIND)).getFirst();
        assertThat(result.eligible()).isTrue();
        assertThat(result.sourceTime()).isEqualTo(AT.minusSeconds(600));
        assertThat(result.expiresAt()).isEqualTo(AT.plusSeconds(100));
    }
    @Test void missingCoverageOrOpenCorrectionCannotPassACompleteWindowFlag() {
        var profiles = Map.of("TASK_ACTIVATION:" + KIND, profile("TASK_ACTIVATION", 30, null));
        assertThat(AdvertisingPurposeFreshness.failures(evidence(AT, false, null, profiles), "TASK_ACTIVATION", List.of(KIND))).isNotEmpty();
        assertThat(AdvertisingPurposeFreshness.failures(evidence(AT, true, BigDecimal.ONE, profiles), "TASK_ACTIVATION", List.of(KIND))).isNotEmpty();
    }
    @Test void absentProfileHasAnExplicitUnresolvedReason() {
        var result = AdvertisingPurposeFreshness.assess(evidence(AT, false, BigDecimal.ONE, Map.of()), "TASK_ACTIVATION", List.of(KIND)).getFirst();
        assertThat(result.eligible()).isFalse();
        assertThat(result.expiresAt()).isNull();
        assertThat(result.reasonCodes()).containsExactly("FRESHNESS_PROFILE_UNRESOLVED:TASK_ACTIVATION:OFFICIAL_AD_SPEND");
    }
    @Test void oldEffectiveCostIsNotStaleWhenCanonicalMetricReconfirmedItsExactApplicability() {
        var original=evidence(AT,false,BigDecimal.ONE,Map.of());
        var amount=com.mimococo.marketops.advertisingefficiency.internal.domain.AdMeasure.available(BigDecimal.TEN,
                com.mimococo.marketops.advertisingefficiency.AdEvidenceState.CANONICAL_CONFIRMED);
        var metric=new com.mimococo.marketops.analyticsdecision.MetricValueView(ID,
                com.mimococo.marketops.analyticsdecision.MetricCode.UNIT_COST,2,
                com.mimococo.marketops.analyticsdecision.SubjectKind.PLATFORM_LISTING_VARIANT,ID,
                com.mimococo.marketops.analyticsdecision.MetricWindow.D30,AT.minusSeconds(30*86400),AT,
                amount.valueState(),amount.value(),"RUB",com.mimococo.marketops.analyticsdecision.ConfidenceState.CANONICAL_CONFIRMED,
                false,AT.minusSeconds(180*86400),0L,"a".repeat(64),AT,List.of(ID));
        var cost=new AdvertisingEvidenceGatherer.VariantEconomics(amount,amount,amount,amount,"RUB",List.of(metric));
        var profile=new FreshnessProfile(ID,1,"COST_AND_FEE","PROTECTION_BID_WRITE",30,30,0,0,true,true,BigDecimal.ONE,"CANONICAL_CONFIRMED",false);
        var e=new AdvertisingEvidenceGatherer.Evidence(null,Optional.empty(),Optional.empty(),original.objectFacts(),Optional.empty(),Optional.empty(),
                List.of(),null,Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Map.of(ID,cost),Map.of(),
                original.windowStart(),AT,new AdvertisingEvidenceGatherer.Authorities(Map.of(),Map.of("PROTECTION_BID_WRITE:COST_AND_FEE",profile),Map.of(),false,List.of(ID),Map.of(),false));
        var assessed=AdvertisingPurposeFreshness.assess(e,"PROTECTION_BID_WRITE",List.of("COST_AND_FEE")).getFirst();
        assertThat(assessed.eligible()).isTrue();assertThat(assessed.sourceTime()).isEqualTo(AT);
    }

}
