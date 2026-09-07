package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The conversion rate the product is allowed to believe.
 *
 * <p>These cases attack the value type directly rather than the service that
 * builds it, so a calculator rewritten to skip a gate still fails here.
 */
class AdLinkedConversionTest {

    private static final BigDecimal FULL = BigDecimal.ONE;
    private static final BigDecimal EIGHTY_PERCENT = new BigDecimal("0.80");

    private static AdLinkedConversion writeGrade(
            long linked, long traffic, BigDecimal linkage, BigDecimal affectedSet,
            boolean complete, boolean aligned, long minSample, AdEvidenceState state) {
        return AdLinkedConversion.writeGrade(
                SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,
                linked, traffic, linkage, affectedSet, complete, aligned,
                minSample, EIGHTY_PERCENT, EIGHTY_PERCENT, state);
    }

    @Test
    @DisplayName("TC-ADV-CONV-001 a complete write-grade conversion is the linked events over eligible traffic")
    void completeConversionDividesLinkedEventsByEligibleTraffic() {
        AdLinkedConversion conversion = writeGrade(
                50, 1000, FULL, FULL, true, true, 10, AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.writeGrade()).isTrue();
        assertThat(conversion.rate().present()).isTrue();
        assertThat(conversion.rate().value()).isEqualByComparingTo("0.05");
        assertThat(conversion.stage()).isEqualTo(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE);
    }

    @Test
    @DisplayName("TC-ADV-CONV-002 a provider observation can never be write-grade")
    void providerObservationIsNeverWriteGrade() {
        AdLinkedConversion observed = AdLinkedConversion.providerObservation(40, 800);

        assertThat(observed.rate().present()).isTrue();
        assertThat(observed.stage()).isEqualTo(SaleStage.PROVIDER_NATIVE_OBSERVATION);
        assertThat(observed.writeGrade()).isFalse();
        assertThat(observed.rate().sufficientForWrite()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CONV-003 a provider stage cannot be smuggled through the write-grade factory")
    void providerStageThroughWriteGradeFactoryIsRefused() {
        AdLinkedConversion conversion = AdLinkedConversion.writeGrade(
                SaleStage.PROVIDER_NATIVE_OBSERVATION,
                50, 1000, FULL, FULL, true, true, 1, EIGHTY_PERCENT, EIGHTY_PERCENT,
                AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.writeGrade()).isFalse();
        assertThat(conversion.rate().present()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CONV-004 a hand-built provider conversion cannot claim write sufficiency")
    void handBuiltProviderConversionIsRefusedByTheConstructor() {
        assertThatThrownBy(() -> new AdLinkedConversion(
                SaleStage.PROVIDER_NATIVE_OBSERVATION,
                AdMeasure.available(new BigDecimal("0.05"), AdEvidenceState.CANONICAL_CONFIRMED),
                50, 1000, FULL, FULL, true, true, AdEvidenceState.CANONICAL_CONFIRMED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider observation");
    }

    @Test
    @DisplayName("TC-ADV-CONV-005 no eligible traffic is an undefined conversion, never zero")
    void absentDenominatorIsUndefinedRatherThanZero() {
        AdLinkedConversion conversion = writeGrade(
                0, 0, FULL, FULL, true, true, 0, AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.rate().present()).isFalse();
        assertThat(conversion.rate().valueState().name()).isEqualTo("UNDEFINED");
        assertThat(conversion.writeGrade()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CONV-006 an incomplete affected set refuses a write-grade conversion")
    void incompleteAffectedSetRefusesWriteGrade() {
        AdLinkedConversion conversion = writeGrade(
                50, 1000, FULL, FULL, false, true, 10, AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.writeGrade()).isFalse();
        assertThat(conversion.evidenceState()).isEqualTo(AdEvidenceState.INCOMPLETE);
    }

    @Test
    @DisplayName("TC-ADV-CONV-007 a misaligned observation window refuses a write-grade conversion")
    void misalignedWindowRefusesWriteGrade() {
        AdLinkedConversion conversion = writeGrade(
                50, 1000, FULL, FULL, true, false, 10, AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.writeGrade()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CONV-008 too few linked events refuses a write-grade conversion")
    void insufficientSampleRefusesWriteGrade() {
        AdLinkedConversion conversion = writeGrade(
                3, 1000, FULL, FULL, true, true, 10, AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.writeGrade()).isFalse();
        assertThat(conversion.linkedEventCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("TC-ADV-CONV-009 linkage coverage below the published floor refuses a write-grade conversion")
    void linkageCoverageBelowFloorRefusesWriteGrade() {
        AdLinkedConversion conversion = writeGrade(
                50, 1000, new BigDecimal("0.40"), FULL, true, true, 10,
                AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.writeGrade()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CONV-010 affected-set coverage below the published floor refuses a write-grade conversion")
    void affectedSetCoverageBelowFloorRefusesWriteGrade() {
        AdLinkedConversion conversion = writeGrade(
                50, 1000, FULL, new BigDecimal("0.10"), true, true, 10,
                AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.writeGrade()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CONV-011 an absent coverage measurement is treated as below any floor")
    void absentCoverageIsBelowAnyFloor() {
        AdLinkedConversion conversion = writeGrade(
                50, 1000, null, FULL, true, true, 10, AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(conversion.writeGrade()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CONV-012 negative counts are refused outright")
    void negativeCountsAreRefused() {
        assertThatThrownBy(() -> new AdLinkedConversion(
                SaleStage.CANONICAL_AD_LINKED_ORDER,
                AdMeasure.notAvailable(AdEvidenceState.UNKNOWN),
                -1, 10, FULL, FULL, true, true, AdEvidenceState.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Nested
    @DisplayName("no insufficient evidence state can produce a write-grade conversion")
    class EvidenceGate {

        @ParameterizedTest
        @EnumSource(value = AdEvidenceState.class,
                names = {"PROVISIONAL_OR_ESTIMATED", "STALE", "INCOMPLETE", "CONFLICTED",
                         "UNKNOWN", "NOT_AVAILABLE", "DATA_BLOCKED", "POLICY_BLOCKED",
                         "PROFILE_UNRESOLVED", "BUNDLE_UNRESOLVED"})
        @DisplayName("TC-ADV-CONV-013 every non-write-sufficient evidence state fails closed")
        void insufficientEvidenceStatesFailClosed(AdEvidenceState state) {
            AdLinkedConversion conversion = writeGrade(
                    500, 1000, FULL, FULL, true, true, 1, state);

            assertThat(conversion.writeGrade())
                    .describedAs("evidence state %s must not produce a write-grade conversion", state)
                    .isFalse();
            assertThat(conversion.rate().present()).isFalse();
        }
    }
}
