package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.AdEvidenceState;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The two profit axes, and the ways one is not allowed to pay for the other. */
class DualAxisVerdictTest {

    private static final BigDecimal MATERIAL = new BigDecimal("100");
    private static final BigDecimal MATERIAL_RATIO = new BigDecimal("0.10");

    private static AdMeasure at(String value) {
        return AdMeasure.available(new BigDecimal(value), AdEvidenceState.CANONICAL_CONFIRMED);
    }

    private static DualAxisVerdict evaluate(
            String baseAbsolute, String currentAbsolute,
            String basePerRub, String currentPerRub, boolean preserved) {
        return DualAxisVerdict.evaluate(at(baseAbsolute), at(currentAbsolute),
                at(basePerRub), at(currentPerRub), MATERIAL, MATERIAL_RATIO, preserved, true);
    }

    @Test
    @DisplayName("TC-ADV-AXIS-001 both axes improving with sales preserved is a verified success")
    void bothAxesImprovingIsSuccess() {
        DualAxisVerdict verdict = evaluate("1000", "5000", "0.10", "0.50", true);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.VERIFIED_EFFICIENCY_SUCCESS);
        assertThat(verdict.healthy()).isTrue();
        assertThat(verdict.closesResponsibility()).isTrue();
    }

    @Test
    @DisplayName("TC-ADV-AXIS-002 one axis improving while the other holds inside the band is a success")
    void oneAxisImprovingWithTheOtherStableIsSuccess() {
        DualAxisVerdict verdict = evaluate("1000", "5000", "0.10", "0.12", true);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.VERIFIED_EFFICIENCY_SUCCESS);
        assertThat(verdict.profitPerAdRub())
                .isEqualTo(DualAxisVerdict.AxisMovement.UNCHANGED_WITHIN_BAND);
    }

    @Test
    @DisplayName("TC-ADV-AXIS-003 a large gain on one axis cannot pay for a material loss on the other")
    void oneAxisCannotPayForTheOther() {
        // Profit per rouble soars because spend collapsed; absolute contribution
        // fell off a cliff. A blended score would call this an improvement.
        DualAxisVerdict verdict = evaluate("50000", "100", "0.10", "9.00", true);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.REGRESSION);
        assertThat(verdict.absoluteProfit())
                .isEqualTo(DualAxisVerdict.AxisMovement.MATERIALLY_WORSENED);
        assertThat(verdict.reasonCode()).isEqualTo("PROFIT_AXIS_MATERIALLY_WORSENED");
    }

    @Test
    @DisplayName("TC-ADV-AXIS-004 a loss that shrinks is improved and is not healthy")
    void shrinkingLossIsImprovedNotHealthy() {
        DualAxisVerdict verdict = evaluate("-2000000", "-1000000", "-0.50", "-0.20", true);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.IMPROVED_NOT_HEALTHY);
        assertThat(verdict.healthy()).isFalse();
        assertThat(verdict.closesResponsibility()).isFalse();
        assertThat(verdict.reasonCode()).isEqualTo("ABSOLUTE_CONTRIBUTION_PROFIT_STILL_NEGATIVE");
    }

    @Test
    @DisplayName("TC-ADV-AXIS-005 failed sales preservation is a regression whatever the profit did")
    void failedSalesPreservationOverridesProfit() {
        DualAxisVerdict verdict = evaluate("1000", "500000", "0.10", "5.00", false);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.REGRESSION);
        assertThat(verdict.reasonCode()).isEqualTo("SALES_PRESERVATION_FAILED");
        assertThat(verdict.salesPreserved()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-AXIS-006 incomplete sales evidence is unresolved, never a pass")
    void incompleteSalesEvidenceIsUnresolved() {
        DualAxisVerdict verdict = DualAxisVerdict.evaluate(
                at("1000"), at("5000"), at("0.10"), at("0.50"),
                MATERIAL, MATERIAL_RATIO, true, false);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.UNRESOLVED);
        assertThat(verdict.reasonCode()).isEqualTo("SALES_PRESERVATION_EVIDENCE_INCOMPLETE");
    }

    @Test
    @DisplayName("TC-ADV-AXIS-007 an unmeasurable axis is unresolved, not silently unchanged")
    void unmeasurableAxisIsUnresolved() {
        DualAxisVerdict verdict = DualAxisVerdict.evaluate(
                at("1000"), AdMeasure.notAvailable(AdEvidenceState.DATA_BLOCKED),
                at("0.10"), at("0.50"), MATERIAL, MATERIAL_RATIO, true, true);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.UNRESOLVED);
        assertThat(verdict.absoluteProfit()).isEqualTo(DualAxisVerdict.AxisMovement.UNRESOLVED);
        assertThat(verdict.reasonCode()).isEqualTo("PROFIT_AXIS_EVIDENCE_INCOMPLETE");
    }

    @Test
    @DisplayName("TC-ADV-AXIS-008 movement inside the band on both axes is no material improvement")
    void noMovementIsNoImprovement() {
        DualAxisVerdict verdict = evaluate("1000", "1050", "0.10", "0.12", true);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.NO_MATERIAL_IMPROVEMENT);
        assertThat(verdict.healthy()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-AXIS-009 a missing material threshold makes the axis unresolved rather than passing")
    void absentThresholdIsUnresolved() {
        DualAxisVerdict verdict = DualAxisVerdict.evaluate(
                at("1000"), at("5000"), at("0.10"), at("0.50"),
                null, MATERIAL_RATIO, true, true);

        assertThat(verdict.outcome()).isEqualTo(DualAxisVerdict.Outcome.UNRESOLVED);
    }
}
