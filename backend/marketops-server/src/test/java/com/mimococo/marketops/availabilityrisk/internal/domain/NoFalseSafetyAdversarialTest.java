package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.NOW;
import static com.mimococo.marketops.availabilityrisk.internal.domain.RiskFixtures.VARIANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.ChildKind;
import com.mimococo.marketops.availabilityrisk.RiskCause;
import com.mimococo.marketops.availabilityrisk.RiskConfidence;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Adversarial tests: construct the values a removed gate would produce and prove
 * they cannot exist.
 *
 * <p>These do not go through the calculators. They attack the value type
 * directly, so they keep failing even if somebody rewrites a calculator to skip
 * a check — which is exactly the mutation the Contract asks to be caught.
 */
class NoFalseSafetyAdversarialTest {

    @ParameterizedTest
    @EnumSource(value = RiskEvidenceState.class,
            names = {"PROVISIONAL", "CARRIED_FORWARD", "DATA_BLOCKED", "POLICY_BLOCKED",
                     "CONFLICTED", "STALE", "UNKNOWN"})
    @DisplayName("TC-ADV-001 no insufficient evidence state can carry a healthy company child")
    void healthyCompanyRequiresSufficientEvidence(RiskEvidenceState insufficient) {
        assertThatThrownBy(() -> company(AvailabilityLane.HEALTHY, insufficient,
                ConservativeProof.of(List.of(ProofTerm.qualitative("X", "x")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be healthy");
    }

    @Test
    @DisplayName("TC-ADV-002 a provisional risk without a proof cannot be constructed")
    void provisionalRequiresAProof() {
        assertThatThrownBy(() -> company(AvailabilityLane.CRITICAL,
                RiskEvidenceState.PROVISIONAL, ConservativeProof.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must carry the proof");
    }

    @Test
    @DisplayName("TC-ADV-003 an empty proof cannot be presented as an established argument")
    void anEmptyProofIsRefused() {
        assertThatThrownBy(() -> ConservativeProof.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("proves nothing");
    }

    @Test
    @DisplayName("TC-ADV-004 a counted supply component cannot also claim an exclusion reason")
    void countedComponentCannotClaimExclusion() {
        assertThatThrownBy(() -> new SupplyComponent(SupplyComponent.Source.INTERNAL_WAREHOUSE,
                10, true, SupplyComponent.ExclusionReason.MIRRORS_INTERNAL_STOCK, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-ADV-005 an excluded component must say why it was excluded")
    void excludedComponentMustGiveAReason() {
        assertThatThrownBy(() -> new SupplyComponent(SupplyComponent.Source.PLATFORM_VISIBLE,
                10, false, SupplyComponent.ExclusionReason.COUNTED, null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-ADV-006 a blocked lead-time policy cannot be asked for a horizon")
    void blockedPolicyHasNoHorizon() {
        assertThatThrownBy(() -> LeadTimeResolution.blocked("none").coverageHorizonDays())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("TC-ADV-007 a lane cannot be derived from a blocked policy")
    void blockedPolicyYieldsNoLane() {
        assertThatThrownBy(() -> LaneThresholds.laneFor(java.math.BigDecimal.ONE,
                LeadTimeResolution.blocked("none")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("TC-ADV-008 a cover too far out to be a date is not reported as one")
    void anUnusableProjectionIsNotADate() {
        // A demand rate near zero produces a cover measured in millennia. That
        // is not a stockout date, and expressing it would overflow the instant.
        java.math.BigDecimal millennia = java.math.BigDecimal.valueOf(400_000);
        assertThat(LaneThresholds.stockoutAt(millennia, NOW)).isNull();
        assertThat(LaneThresholds.stockoutAt(java.math.BigDecimal.valueOf(30), NOW))
                .isEqualTo(NOW.plus(java.time.Duration.ofDays(30)));
    }

    private ChildRisk company(AvailabilityLane lane, RiskEvidenceState evidence,
                              ConservativeProof proof) {
        return new ChildRisk(ChildKind.COMPANY, lane, evidence, RiskConfidence.LOW,
                RiskCause.COMPANY_SUPPLY_SHORT, ProvenSupply.none(),
                RiskFixtures.demand("10"), RiskFixtures.leadTime(), RiskFixtures.profit(),
                null, null, proof, List.of());
    }
}
