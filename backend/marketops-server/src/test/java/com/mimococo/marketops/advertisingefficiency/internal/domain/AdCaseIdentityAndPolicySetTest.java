package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Case identity, policy versioning and the pure calculation value. */
class AdCaseIdentityAndPolicySetTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID OBJECT = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID VARIANT = UUID.fromString("00000000-0000-0000-0000-0000000000e4");

    @Nested
    @DisplayName("a case is identified by its cause and its object generation")
    class Identity {

        @Test
        @DisplayName("TC-ADV-ID-001 the same cause on the same object generation is one case")
        void sameCauseIsOneCase() {
            AdCaseIdentity first = new AdCaseIdentity(ORG, OBJECT, 3,
                    AdvertisingCause.PROVEN_ADVERTISING_LOSS);
            AdCaseIdentity again = new AdCaseIdentity(ORG, OBJECT, 3,
                    AdvertisingCause.PROVEN_ADVERTISING_LOSS);

            assertThat(first.caseKey()).isEqualTo(again.caseKey());
            assertThat(again.matches(first.caseKey())).isTrue();
        }

        @Test
        @DisplayName("TC-ADV-ID-002 two causes on one object are two cases")
        void twoCausesAreTwoCases() {
            AdCaseIdentity loss = new AdCaseIdentity(ORG, OBJECT, 1,
                    AdvertisingCause.PROVEN_ADVERTISING_LOSS);
            AdCaseIdentity defect = new AdCaseIdentity(ORG, OBJECT, 1,
                    AdvertisingCause.OFFICIAL_AD_FACT_DEFECT);

            assertThat(loss.caseKey()).isNotEqualTo(defect.caseKey());
        }

        @Test
        @DisplayName("TC-ADV-ID-003 a rebuilt object does not inherit the previous case")
        void rebuiltObjectDoesNotInheritHistory() {
            AdCaseIdentity before = new AdCaseIdentity(ORG, OBJECT, 3,
                    AdvertisingCause.PROVEN_ADVERTISING_LOSS);
            AdCaseIdentity afterRebuild = new AdCaseIdentity(ORG, OBJECT, 4,
                    AdvertisingCause.PROVEN_ADVERTISING_LOSS);

            assertThat(afterRebuild.matches(before.caseKey())).isFalse();
        }

        @Test
        @DisplayName("TC-ADV-ID-004 a generation below one is unrepresentable")
        void generationBelowOneIsRefused() {
            assertThatThrownBy(() -> new AdCaseIdentity(ORG, OBJECT, 0,
                    AdvertisingCause.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("TC-ADV-ID-005 several causes produce several identities")
        void severalCausesProduceSeveralIdentities() {
            List<AdCaseIdentity> identities = AdCaseIdentity.forCauses(ORG, OBJECT, 2,
                    List.of(AdvertisingCause.PROVEN_ADVERTISING_LOSS,
                            AdvertisingCause.OFFICIAL_AD_FACT_DEFECT));

            assertThat(identities).hasSize(2);
            assertThat(identities).extracting(AdCaseIdentity::caseKey).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("the policy set says what was in force, including what was not")
    class Policies {

        private static AdPolicySet withVersions(UUID conversion, UUID qualification, UUID priority) {
            return new AdPolicySet(conversion, conversion == null ? null : 1,
                    null, null, qualification, qualification == null ? null : 1,
                    priority, priority == null ? null : 1,
                    null, null, null, null, null, null, null, null, null, null, null, null);
        }

        @Test
        @DisplayName("TC-ADV-POL-001 identical versions produce identical digests")
        void identicalVersionsProduceIdenticalDigests() {
            UUID conversion = UUID.randomUUID();
            UUID qualification = UUID.randomUUID();

            assertThat(withVersions(conversion, qualification, null).versionDigest())
                    .isEqualTo(withVersions(conversion, qualification, null).versionDigest());
        }

        @Test
        @DisplayName("TC-ADV-POL-002 a policy change changes the digest")
        void aPolicyChangeChangesTheDigest() {
            UUID qualification = UUID.randomUUID();

            assertThat(withVersions(UUID.randomUUID(), qualification, null).versionDigest())
                    .isNotEqualTo(withVersions(UUID.randomUUID(), qualification, null).versionDigest());
        }

        @Test
        @DisplayName("TC-ADV-POL-003 two different absences do not collide")
        void differentAbsencesDoNotCollide() {
            UUID present = UUID.randomUUID();

            assertThat(withVersions(present, null, null).versionDigest())
                    .isNotEqualTo(withVersions(null, present, null).versionDigest());
        }

        @Test
        @DisplayName("TC-ADV-POL-004 an empty set calculates no lane and authorizes no write")
        void emptySetAuthorizesNothing() {
            AdPolicySet empty = AdPolicySet.empty();

            assertThat(empty.laneCalculable()).isFalse();
            assertThat(empty.rankable()).isFalse();
            assertThat(empty.writeCapable()).isFalse();
            assertThat(empty.versionDigest()).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("TC-ADV-POL-005 a lane is calculable well before a write is")
        void laneIsCalculableBeforeAWriteIs() {
            AdPolicySet partial = withVersions(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

            assertThat(partial.laneCalculable()).isTrue();
            assertThat(partial.rankable()).isTrue();
            assertThat(partial.writeCapable()).isFalse();
        }
    }

    @Nested
    @DisplayName("a calculation is a pure value the two schedules both produce")
    class Calculations {

        private static AdCaseCalculation.ScoredCase scored(AdvertisingCause cause) {
            return new AdCaseCalculation.ScoredCase(
                    new AdCaseIdentity(ORG, OBJECT, 1, cause),
                    new AdLaneResolver.Decision(AdvertisingLane.PROTECTION, ProtectionTier.P2,
                            cause, AdEvidenceState.CANONICAL_CONFIRMED, AdConfidence.HIGH, List.of()),
                    AdPriorityPolicy.unranked(AdvertisingLane.PROTECTION, ProtectionTier.P2),
                    AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                    AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                    AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                    AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                    null, null,
                    AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                    AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                    AdMeasure.notAvailable(AdEvidenceState.NOT_AVAILABLE),
                    "RUB", List.of());
        }

        private static AdCaseCalculation calculation(List<AdCaseCalculation.ScoredCase> cases) {
            return new AdCaseCalculation(ORG, OBJECT, STORE, "OZON", UUID.randomUUID(), 1,
                    Instant.parse("2026-09-04T00:00:00Z"), AdPolicySet.empty(),
                    AffectedSet.complete(List.of(VARIANT), List.of()), cases);
        }

        @Test
        @DisplayName("TC-ADV-CALC-001 two cases for the same cause are unrepresentable")
        void duplicateCauseIsRefused() {
            assertThatThrownBy(() -> calculation(List.of(
                    scored(AdvertisingCause.PROVEN_ADVERTISING_LOSS),
                    scored(AdvertisingCause.PROVEN_ADVERTISING_LOSS))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same cause");
        }

        @Test
        @DisplayName("TC-ADV-CALC-002 several independent causes are several cases")
        void severalCausesAreAllowed() {
            AdCaseCalculation result = calculation(List.of(
                    scored(AdvertisingCause.PROVEN_ADVERTISING_LOSS),
                    scored(AdvertisingCause.OFFICIAL_AD_FACT_DEFECT)));

            assertThat(result.cases()).hasSize(2);
            assertThat(result.raisesWork()).isTrue();
        }

        @Test
        @DisplayName("TC-ADV-CALC-003 an object with no cases is a Watch, never a healthy state")
        void noCasesIsWatchRatherThanHealthy() {
            AdCaseCalculation result = calculation(List.of());

            assertThat(result.mostSevereLane()).isEqualTo(AdvertisingLane.WATCH);
            assertThat(result.raisesWork()).isFalse();
        }

        @Test
        @DisplayName("TC-ADV-CALC-004 a variant's basis is derived, so it cannot disagree with itself")
        void variantBasisIsDerived() {
            AdCaseCalculation.VariantDiagnostic observed = new AdCaseCalculation.VariantDiagnostic(
                    VARIANT, null, true, "CANONICAL_CONFIRMED", BigDecimal.TEN, 5L,
                    BigDecimal.ONE, "RUB", "SELLABLE", "AVAILABLE", false);
            AdCaseCalculation.VariantDiagnostic allocated = new AdCaseCalculation.VariantDiagnostic(
                    VARIANT, null, false, "ESTIMATED_EXPLAINED", BigDecimal.TEN, 5L,
                    BigDecimal.ONE, "RUB", "SELLABLE", "AVAILABLE", false);

            assertThat(observed.basis()).isEqualTo("OFFICIAL_OBSERVATION");
            assertThat(allocated.basis()).isEqualTo("ESTIMATED_ALLOCATION");
        }
    }
}
