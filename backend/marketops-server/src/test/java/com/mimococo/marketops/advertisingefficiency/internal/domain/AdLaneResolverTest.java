package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdConfidence;
import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.AdvertisingCause;
import com.mimococo.marketops.advertisingefficiency.AdvertisingLane;
import com.mimococo.marketops.advertisingefficiency.ProtectionTier;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ladder, and the specific reorderings that would break the product.
 */
class AdLaneResolverTest {

    private static OneSidedDangerProof proven(String cause) {
        return OneSidedDangerProof.of(cause, List.of("PROVEN_TERM"), List.of(), false);
    }

    /** A helper so each case states only the signal it is about. */
    private static AdLaneResolver.Signals signals(
            boolean integrity,
            OneSidedDangerProof sellability,
            OneSidedDangerProof availability,
            OneSidedDangerProof criticalSales,
            OneSidedDangerProof economic,
            AdvertisingCause dataDefect,
            boolean controllable,
            boolean qualified,
            boolean material,
            AdMeasure recoverable) {
        return new AdLaneResolver.Signals(
                integrity, false, false,
                sellability, availability, criticalSales, economic, OneSidedDangerProof.none(),
                dataDefect, List.of("BLOCKER"), controllable, qualified, material, recoverable,
                AdEvidenceState.CANONICAL_CONFIRMED, AdConfidence.HIGH);
    }

    private static final AdMeasure RECOVERABLE =
            AdMeasure.available(new BigDecimal("5000"), AdEvidenceState.CANONICAL_CONFIRMED);

    @Test
    @DisplayName("TC-ADV-LANE-001 unresolved execution integrity outranks a proven loss")
    void integrityOutranksProvenLoss() {
        AdLaneResolver.Decision decision = AdLaneResolver.resolve(signals(
                true, OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                OneSidedDangerProof.none(), proven("PROVEN_ADVERTISING_LOSS"),
                null, true, true, true, RECOVERABLE));

        assertThat(decision.lane()).isEqualTo(AdvertisingLane.PROTECTION);
        assertThat(decision.protectionTier()).isEqualTo(ProtectionTier.P0);
        assertThat(decision.cause()).isEqualTo(AdvertisingCause.ACTION_OUTCOME_REGRESSION);
    }

    @Test
    @DisplayName("TC-ADV-LANE-002 a broken feed cannot silence a live proven loss")
    void dataDefectCannotSilenceProvenLoss() {
        AdLaneResolver.Decision decision = AdLaneResolver.resolve(signals(
                false, OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                OneSidedDangerProof.none(), proven("PROVEN_ADVERTISING_LOSS"),
                AdvertisingCause.OFFICIAL_AD_FACT_DEFECT, true, false, false, null));

        assertThat(decision.lane()).isEqualTo(AdvertisingLane.PROTECTION);
        assertThat(decision.protectionTier()).isEqualTo(ProtectionTier.P2);
        assertThat(decision.blockerCodes()).containsExactly("BLOCKER");
    }

    @Test
    @DisplayName("TC-ADV-LANE-003 a not-sellable promoted variant is P1, above proven economic harm")
    void sellabilityDangerIsAboveEconomicHarm() {
        AdLaneResolver.Decision decision = AdLaneResolver.resolve(signals(
                false, proven("PROMOTED_VARIANT_NOT_SELLABLE"), OneSidedDangerProof.none(),
                OneSidedDangerProof.none(), proven("PROVEN_ADVERTISING_LOSS"),
                null, true, false, false, null));

        assertThat(decision.protectionTier()).isEqualTo(ProtectionTier.P1);
        assertThat(decision.cause()).isEqualTo(AdvertisingCause.PROMOTED_VARIANT_NOT_SELLABLE);
    }

    @Test
    @DisplayName("TC-ADV-LANE-004 a material data defect is Data Repair, never a quiet Watch")
    void materialDefectIsDataRepairNotWatch() {
        AdLaneResolver.Decision decision = AdLaneResolver.resolve(signals(
                false, OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                AdvertisingCause.AFFECTED_SET_UNRESOLVED, true, true, true, RECOVERABLE));

        assertThat(decision.lane()).isEqualTo(AdvertisingLane.DATA_REPAIR);
        assertThat(decision.cause()).isEqualTo(AdvertisingCause.AFFECTED_SET_UNRESOLVED);
        assertThat(decision.protectionTier()).isNull();
    }

    @Test
    @DisplayName("TC-ADV-LANE-005 a qualified material opportunity on a controllable object is Optimization")
    void qualifiedMaterialOpportunityIsOptimization() {
        AdLaneResolver.Decision decision = AdLaneResolver.resolve(signals(
                false, OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                null, true, true, true, RECOVERABLE));

        assertThat(decision.lane()).isEqualTo(AdvertisingLane.OPTIMIZATION);
        assertThat(decision.cause()).isEqualTo(AdvertisingCause.RECOVERABLE_ADVERTISING_PROFIT);
    }

    @Test
    @DisplayName("TC-ADV-LANE-006 an unsustained or immaterial opportunity stays a Watch")
    void immaterialOpportunityStaysWatch() {
        AdLaneResolver.Decision decision = AdLaneResolver.resolve(signals(
                false, OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                null, true, true, false, RECOVERABLE));

        assertThat(decision.lane()).isEqualTo(AdvertisingLane.WATCH);
        assertThat(decision.cause()).isEqualTo(AdvertisingCause.IMMATURE_SIGNAL);
    }

    @Test
    @DisplayName("TC-ADV-LANE-007 an object the platform will not let us control never becomes an opportunity")
    void uncontrollableObjectIsNeverAnOpportunity() {
        AdLaneResolver.Decision decision = AdLaneResolver.resolve(signals(
                false, OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                null, false, true, true, RECOVERABLE));

        assertThat(decision.lane()).isEqualTo(AdvertisingLane.WATCH);
        assertThat(decision.cause())
                .isEqualTo(AdvertisingCause.OBJECT_NOT_INDEPENDENTLY_CONTROLLABLE);
    }

    @Test
    @DisplayName("TC-ADV-LANE-008 an unproven danger does not raise Protection")
    void unprovenDangerDoesNotRaiseProtection() {
        OneSidedDangerProof reversible = OneSidedDangerProof.of(
                "PROVEN_ADVERTISING_LOSS", List.of("SPEND_OBSERVED"),
                List.of("AD_LINKED_CONVERSION"), true);

        AdLaneResolver.Decision decision = AdLaneResolver.resolve(signals(
                false, OneSidedDangerProof.none(), OneSidedDangerProof.none(),
                OneSidedDangerProof.none(), reversible, null, true, false, false, null));

        assertThat(reversible.established()).isFalse();
        assertThat(reversible.refusalCode()).isEqualTo("MISSING_FACT_COULD_REVERSE_DIRECTION");
        assertThat(decision.lane()).isEqualTo(AdvertisingLane.WATCH);
    }

    @Test
    @DisplayName("TC-ADV-LANE-009 a non-Protection decision carrying a sub-tier is unrepresentable")
    void nonProtectionWithTierIsRefused() {
        assertThatThrownBy(() -> new AdLaneResolver.Decision(
                AdvertisingLane.WATCH, ProtectionTier.P0, AdvertisingCause.NONE,
                AdEvidenceState.CANONICAL_CONFIRMED, AdConfidence.HIGH, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub-tier");
    }

    @Test
    @DisplayName("TC-ADV-LANE-010 a Protection decision without a sub-tier is unrepresentable")
    void protectionWithoutTierIsRefused() {
        assertThatThrownBy(() -> new AdLaneResolver.Decision(
                AdvertisingLane.PROTECTION, null, AdvertisingCause.PROVEN_ADVERTISING_LOSS,
                AdEvidenceState.CANONICAL_CONFIRMED, AdConfidence.HIGH, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub-tier");
    }
}
