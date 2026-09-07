package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import com.mimococo.marketops.advertisingefficiency.SaleStage;
import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The economic ceiling, and the mismatches it refuses rather than corrects. */
class MaxCpcTest {

    private static final BigDecimal FULL = BigDecimal.ONE;

    private static AdLinkedConversion conversionAt(SaleStage stage, String rate) {
        long traffic = 1000;
        long linked = new BigDecimal(rate).multiply(BigDecimal.valueOf(traffic)).longValueExact();
        return AdLinkedConversion.writeGrade(stage, linked, traffic, FULL, FULL, true, true,
                1, FULL, FULL, AdEvidenceState.CANONICAL_CONFIRMED);
    }

    @Test
    @DisplayName("TC-ADV-CPC-001 a stage-consistent ceiling is Allowable CPA times the conversion rate")
    void stageConsistentCeilingMultiplies() {
        MaxCpc ceiling = MaxCpc.compute(
                Money.of(new BigDecimal("200.00"), "RUB"),
                SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,
                conversionAt(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE, "0.05"));

        assertThat(ceiling.absence()).isEqualTo(MaxCpc.Absence.NONE);
        assertThat(ceiling.writeGrade()).isTrue();
        assertThat(ceiling.ceiling().amount()).isEqualByComparingTo("10.0000");
        assertThat(ceiling.ceiling().currencyCode()).isEqualTo("RUB");
    }

    @Test
    @DisplayName("TC-ADV-CPC-002 an order-priced CPA against a retained conversion is refused, not corrected")
    void stageMismatchIsRefused() {
        MaxCpc ceiling = MaxCpc.compute(
                Money.of(new BigDecimal("200.00"), "RUB"),
                SaleStage.CANONICAL_AD_LINKED_ORDER,
                conversionAt(SaleStage.CANONICAL_AD_LINKED_RETAINED_SALE, "0.05"));

        assertThat(ceiling.absence()).isEqualTo(MaxCpc.Absence.STAGE_MISMATCH);
        assertThat(ceiling.ceiling()).isNull();
        assertThat(ceiling.writeGrade()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CPC-003 a CPA priced against a provider observation is refused")
    void providerPricedCpaIsRefused() {
        MaxCpc ceiling = MaxCpc.compute(
                Money.of(new BigDecimal("200.00"), "RUB"),
                SaleStage.PROVIDER_NATIVE_OBSERVATION,
                conversionAt(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE, "0.05"));

        assertThat(ceiling.absence()).isEqualTo(MaxCpc.Absence.STAGE_MISMATCH);
    }

    @Test
    @DisplayName("TC-ADV-CPC-004 a conversion that is not write-grade produces no ceiling")
    void nonWriteGradeConversionProducesNoCeiling() {
        AdLinkedConversion weak = AdLinkedConversion.writeGrade(
                SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,
                50, 1000, FULL, FULL, true, true, 1, FULL, FULL, AdEvidenceState.STALE);

        MaxCpc ceiling = MaxCpc.compute(
                Money.of(new BigDecimal("200.00"), "RUB"),
                SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE, weak);

        assertThat(ceiling.absence()).isEqualTo(MaxCpc.Absence.CONVERSION_NOT_WRITE_GRADE);
        assertThat(ceiling.writeGrade()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-CPC-005 a missing Allowable CPA definition produces no ceiling")
    void missingAllowableCpaProducesNoCeiling() {
        MaxCpc ceiling = MaxCpc.compute(
                null, SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,
                conversionAt(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE, "0.05"));

        assertThat(ceiling.absence()).isEqualTo(MaxCpc.Absence.ALLOWABLE_CPA_UNRESOLVED);
        assertThat(ceiling.evidenceState()).isEqualTo(AdEvidenceState.POLICY_BLOCKED);
    }

    @Test
    @DisplayName("TC-ADV-CPC-006 a zero conversion is an absent ceiling, never a ceiling of zero")
    void zeroConversionIsAbsentRatherThanZero() {
        // Built directly rather than through the write-grade factory. Every
        // published Conversion Definition requires at least one sample event, so
        // a zero rate reaches the factory only as an insufficient sample. The
        // guard below is what protects the arithmetic from a caller that
        // assembled a conversion some other way, and a ceiling of zero would
        // read as a valid bound and justify a bid of zero rather than refusing.
        AdLinkedConversion zeroRate = new AdLinkedConversion(
                SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,
                AdMeasure.available(BigDecimal.ZERO, AdEvidenceState.CANONICAL_CONFIRMED),
                0, 1000, FULL, FULL, true, true, AdEvidenceState.CANONICAL_CONFIRMED);

        assertThat(zeroRate.writeGrade()).isTrue();

        MaxCpc ceiling = MaxCpc.compute(
                Money.of(new BigDecimal("200.00"), "RUB"),
                SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE, zeroRate);

        assertThat(ceiling.absence()).isEqualTo(MaxCpc.Absence.CONVERSION_ZERO);
        assertThat(ceiling.ceiling()).isNull();
    }

    @Test
    @DisplayName("TC-ADV-CPC-010 a zero-rate conversion from the factory fails the sample gate first")
    void zeroRateThroughTheFactoryFailsTheSampleGate() {
        MaxCpc ceiling = MaxCpc.compute(
                Money.of(new BigDecimal("200.00"), "RUB"),
                SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE,
                conversionAt(SaleStage.CANONICAL_AD_LINKED_COMPLETED_SALE, "0.00"));

        assertThat(ceiling.absence()).isEqualTo(MaxCpc.Absence.CONVERSION_NOT_WRITE_GRADE);
        assertThat(ceiling.ceiling()).isNull();
    }

    @Test
    @DisplayName("TC-ADV-CPC-007 a ceiling cannot be constructed alongside an absence reason")
    void ceilingAndAbsenceCannotCoexist() {
        assertThatThrownBy(() -> new MaxCpc(
                SaleStage.CANONICAL_AD_LINKED_ORDER,
                Money.of(BigDecimal.TEN, "RUB"),
                AdEvidenceState.CANONICAL_CONFIRMED,
                MaxCpc.Absence.STAGE_MISMATCH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-ADV-CPC-008 an absent ceiling must name a reason")
    void absentCeilingNeedsAReason() {
        assertThatThrownBy(() -> MaxCpc.absent(MaxCpc.Absence.NONE, AdEvidenceState.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("TC-ADV-CPC-009 a ceiling always names the stage it prices")
    void ceilingAlwaysNamesItsStage() {
        assertThatThrownBy(() -> new MaxCpc(
                null, Money.of(BigDecimal.TEN, "RUB"),
                AdEvidenceState.CANONICAL_CONFIRMED, MaxCpc.Absence.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sale stage");
    }
}
