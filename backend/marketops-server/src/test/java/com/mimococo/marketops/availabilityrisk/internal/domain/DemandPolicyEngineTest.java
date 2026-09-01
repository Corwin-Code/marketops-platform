package com.mimococo.marketops.availabilityrisk.internal.domain;

import static com.mimococo.marketops.availabilityrisk.internal.domain.DemandFixtures.NOW;
import static com.mimococo.marketops.availabilityrisk.internal.domain.DemandFixtures.censored;
import static com.mimococo.marketops.availabilityrisk.internal.domain.DemandFixtures.observed;
import static com.mimococo.marketops.availabilityrisk.internal.domain.DemandFixtures.policy;
import static com.mimococo.marketops.availabilityrisk.internal.domain.DemandFixtures.spiked;
import static com.mimococo.marketops.availabilityrisk.internal.domain.DemandFixtures.unobserved;
import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.availabilityrisk.RiskConfidence;
import com.mimococo.marketops.availabilityrisk.RiskEvidenceState;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DemandPolicyEngineTest {

    @Test
    @DisplayName("TC-DEMAND-001 a stable portfolio uses the longest eligible window")
    void stableUsesBaseline() {
        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(observed(DemandWindow.D7, 70),
                        observed(DemandWindow.D14, 140),
                        observed(DemandWindow.D30, 300)),
                policy(), null, NOW);

        assertThat(decision.selectedWindow()).isEqualTo(DemandWindow.D30);
        assertThat(decision.selectedRate()).isEqualByComparingTo("10");
        assertThat(decision.reason()).contains("stable baseline");
        assertThat(decision.evidenceState()).isEqualTo(RiskEvidenceState.CONFIRMED);
        assertThat(decision.confidence()).isEqualTo(RiskConfidence.HIGH);
    }

    @Test
    @DisplayName("TC-DEMAND-002 sustained acceleration selects the recent window and says so")
    void sustainedAccelerationSelectsD7() {
        // 20/day recently, 12/day over a fortnight, 8/day over a month: the rise
        // is both large and monotone.
        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(observed(DemandWindow.D7, 140),
                        observed(DemandWindow.D14, 168),
                        observed(DemandWindow.D30, 240)),
                policy(), null, NOW);

        assertThat(decision.selectedWindow()).isEqualTo(DemandWindow.D7);
        assertThat(decision.selectedRate()).isEqualByComparingTo("20");
        assertThat(decision.reason()).contains("sustained recent acceleration");
    }

    @Test
    @DisplayName("TC-DEMAND-003 an unsustained spike is a window conflict, not acceleration")
    void unsustainedSpikeIsConflict() {
        // D7 is 20/day but D14 is only 8/day against a D30 of 10/day: the recent
        // jump is not carried by the middle window, so no rule resolves it.
        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(observed(DemandWindow.D7, 140),
                        observed(DemandWindow.D14, 112),
                        observed(DemandWindow.D30, 360)),
                policy(), null, NOW);

        assertThat(decision.selectedWindow()).isNull();
        assertThat(decision.evidenceState()).isEqualTo(RiskEvidenceState.CONFLICTED);
        assertThat(decision.usable()).isFalse();
        assertThat(decision.reason()).contains("window conflict");
    }

    @Test
    @DisplayName("TC-DEMAND-004 sustained deceleration is recognised and named")
    void sustainedDecelerationSelectsD7() {
        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(observed(DemandWindow.D7, 35),
                        observed(DemandWindow.D14, 140),
                        observed(DemandWindow.D30, 360)),
                policy(), null, NOW);

        assertThat(decision.selectedWindow()).isEqualTo(DemandWindow.D7);
        assertThat(decision.reason()).contains("sustained recent deceleration");
    }

    @Test
    @DisplayName("TC-DEMAND-005 a materially censored window cannot lower the canonical rate")
    void censoredWindowIsExcluded() {
        // The last week sold almost nothing because the listing was unavailable
        // for six of its seven days. The month is still fully observable.
        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(censored(DemandWindow.D7, 2, 1.0,
                                DemandWindowEvidence.CensoringReason.NO_STOCK),
                        observed(DemandWindow.D14, 140),
                        observed(DemandWindow.D30, 300)),
                policy(), null, NOW);

        assertThat(decision.selectedWindow()).isEqualTo(DemandWindow.D30);
        assertThat(decision.selectedRate()).isEqualByComparingTo("10");
        assertThat(decision.confidence()).isEqualTo(RiskConfidence.MEDIUM);
    }

    @Test
    @DisplayName("TC-DEMAND-006 every window censored carries the last eligible answer forward")
    void allCensoredCarriesForward() {
        CarriedForwardDemand last = new CarriedForwardDemand(
                new BigDecimal("9"), DemandWindow.D30, NOW.minus(Duration.ofDays(3)));

        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(censored(DemandWindow.D7, 0, 0.5,
                                DemandWindowEvidence.CensoringReason.NOT_SELLABLE),
                        censored(DemandWindow.D14, 0, 1.0,
                                DemandWindowEvidence.CensoringReason.NOT_SELLABLE),
                        censored(DemandWindow.D30, 1, 2.0,
                                DemandWindowEvidence.CensoringReason.NOT_SELLABLE)),
                policy(), last, NOW);

        assertThat(decision.selectedRate()).isEqualByComparingTo("9");
        assertThat(decision.evidenceState()).isEqualTo(RiskEvidenceState.CARRIED_FORWARD);
        assertThat(decision.confidence()).isEqualTo(RiskConfidence.LOW);
        assertThat(decision.carriedForwardFrom()).isEqualTo(NOW.minus(Duration.ofDays(3)));
        assertThat(decision.carryForwardExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(11)));
        assertThat(decision.usable()).isTrue();
    }

    @Test
    @DisplayName("TC-DEMAND-006A one censored window cannot carry across mixed defects")
    void mixedCensoringAndLowSampleDoesNotCarryForward() {
        CarriedForwardDemand last = new CarriedForwardDemand(
                new BigDecimal("9"), DemandWindow.D30, NOW.minus(Duration.ofDays(3)));

        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(censored(DemandWindow.D7, 0, 0.5,
                                DemandWindowEvidence.CensoringReason.NOT_SELLABLE),
                        observed(DemandWindow.D14, 1),
                        observed(DemandWindow.D30, 2)),
                policy(), last, NOW);

        assertThat(decision.selectedRate()).isNull();
        assertThat(decision.evidenceState()).isEqualTo(RiskEvidenceState.DATA_BLOCKED);
        assertThat(decision.reason()).contains("mixed").contains("forbidden");
    }

    @Test
    @DisplayName("TC-DEMAND-007 an expired carry-forward blocks rather than returning zero")
    void expiredCarryForwardBlocks() {
        CarriedForwardDemand stale = new CarriedForwardDemand(
                new BigDecimal("9"), DemandWindow.D30, NOW.minus(Duration.ofDays(30)));

        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(censored(DemandWindow.D7, 0, 0.5,
                                DemandWindowEvidence.CensoringReason.NOT_SELLABLE),
                        censored(DemandWindow.D14, 0, 1.0,
                                DemandWindowEvidence.CensoringReason.NOT_SELLABLE),
                        censored(DemandWindow.D30, 0, 2.0,
                                DemandWindowEvidence.CensoringReason.NOT_SELLABLE)),
                policy(), stale, NOW);

        assertThat(decision.selectedRate()).isNull();
        assertThat(decision.evidenceState()).isEqualTo(RiskEvidenceState.DATA_BLOCKED);
        assertThat(decision.usable()).isFalse();
        assertThat(decision.reason()).contains("carry-forward expired");
    }

    @Test
    @DisplayName("TC-DEMAND-008 a low sample blocks explicitly rather than reporting near-zero")
    void lowSampleBlocks() {
        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(observed(DemandWindow.D7, 1),
                        observed(DemandWindow.D14, 2),
                        observed(DemandWindow.D30, 4)),
                policy(), null, NOW);

        assertThat(decision.selectedRate()).isNull();
        assertThat(decision.evidenceState()).isEqualTo(RiskEvidenceState.DATA_BLOCKED);
        assertThat(decision.reason()).contains("minimum sample");
    }

    @Test
    @DisplayName("TC-DEMAND-009 one dominating day sends the window to review, not to the rate")
    void outlierSendsToReview() {
        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(spiked(DemandWindow.D7, 100, 0.95),
                        spiked(DemandWindow.D14, 110, 0.90),
                        spiked(DemandWindow.D30, 120, 0.85)),
                policy(), null, NOW);

        assertThat(decision.selectedRate()).isNull();
        assertThat(decision.reason()).contains("outlier");
    }

    @Test
    @DisplayName("TC-DEMAND-010 no source answering is unknown, never zero demand")
    void nothingObservedBlocks() {
        DemandDecision decision = DemandPolicyEngine.decide(
                List.of(unobserved(DemandWindow.D7),
                        unobserved(DemandWindow.D14),
                        unobserved(DemandWindow.D30)),
                policy(), null, NOW);

        assertThat(decision.selectedRate()).isNull();
        assertThat(decision.evidenceState()).isEqualTo(RiskEvidenceState.DATA_BLOCKED);
        assertThat(decision.reason()).contains("no source answered");
    }

    @Test
    @DisplayName("TC-DEMAND-011 the rate divides by observed days, not by window length")
    void rateUsesObservedDays() {
        DemandWindowEvidence partial = censored(DemandWindow.D7, 20, 4.0,
                DemandWindowEvidence.CensoringReason.PARTIAL_COVERAGE);

        assertThat(partial.dailyRate()).isEqualByComparingTo("5");
        assertThat(partial.coverageRatio()).isEqualByComparingTo("0.571428571429");
    }
}
