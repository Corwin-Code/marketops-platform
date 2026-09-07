package com.mimococo.marketops.advertisingefficiency.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The conjunction whose second term cannot be traded away. */
class SalesPreservationTest {

    private static SalesPreservation.UnitResult unit(
            String code, boolean required, SalesPreservation.Status status) {
        return new SalesPreservation.UnitResult(code, required, status);
    }

    @Test
    @DisplayName("TC-ADV-SALES-001 the total and every required unit passing is preservation")
    void totalAndRequiredUnitsPassing() {
        SalesPreservation result = SalesPreservation.evaluate(
                unit("COMPANY_TOTAL", true, SalesPreservation.Status.PASSED),
                List.of(unit("HERO_VARIANT", true, SalesPreservation.Status.PASSED),
                        unit("SECONDARY", false, SalesPreservation.Status.FAILED)));

        assertThat(result.preserved()).isTrue();
        assertThat(result.verdict()).isEqualTo(SalesPreservation.Verdict.PRESERVED);
    }

    @Test
    @DisplayName("TC-ADV-SALES-002 a healthy company total cannot rescue a failed required unit")
    void healthyTotalCannotRescueARequiredUnit() {
        SalesPreservation result = SalesPreservation.evaluate(
                unit("COMPANY_TOTAL", true, SalesPreservation.Status.PASSED),
                List.of(unit("HERO_VARIANT", true, SalesPreservation.Status.FAILED)));

        assertThat(result.preserved()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("CRITICAL_SALES_UNIT_BELOW_TOLERANCE");
    }

    @Test
    @DisplayName("TC-ADV-SALES-003 a non-required unit failing carries no veto")
    void nonRequiredUnitCarriesNoVeto() {
        SalesPreservation result = SalesPreservation.evaluate(
                unit("COMPANY_TOTAL", true, SalesPreservation.Status.PASSED),
                List.of(unit("LONG_TAIL", false, SalesPreservation.Status.FAILED),
                        unit("OTHER_TAIL", false, SalesPreservation.Status.UNRESOLVED)));

        assertThat(result.preserved()).isTrue();
    }

    @Test
    @DisplayName("TC-ADV-SALES-004 unresolved evidence for a required unit is never a pass")
    void unresolvedRequiredUnitIsNeverAPass() {
        SalesPreservation result = SalesPreservation.evaluate(
                unit("COMPANY_TOTAL", true, SalesPreservation.Status.PASSED),
                List.of(unit("HERO_VARIANT", true, SalesPreservation.Status.UNRESOLVED)));

        assertThat(result.verdict()).isEqualTo(SalesPreservation.Verdict.UNRESOLVED);
        assertThat(result.preserved()).isFalse();
        assertThat(result.evidenceComplete()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("CRITICAL_SALES_UNIT_EVIDENCE_UNRESOLVED");
    }

    @Test
    @DisplayName("TC-ADV-SALES-005 a known critical failure survives an unresolved company total")
    void knownCriticalFailureIsNotSilencedByUnknownTotal() {
        SalesPreservation result = SalesPreservation.evaluate(
                unit("COMPANY_TOTAL", true, SalesPreservation.Status.UNRESOLVED),
                List.of(unit("HERO_VARIANT", true, SalesPreservation.Status.FAILED)));

        assertThat(result.reasonCode()).isEqualTo("CRITICAL_SALES_UNIT_BELOW_TOLERANCE");
        assertThat(result.evidenceComplete()).isFalse();
    }

    @Test
    @DisplayName("TC-ADV-SALES-006 a failed company total fails preservation on its own")
    void failedTotalFailsAlone() {
        SalesPreservation result = SalesPreservation.evaluate(
                unit("COMPANY_TOTAL", true, SalesPreservation.Status.FAILED),
                List.of(unit("HERO_VARIANT", true, SalesPreservation.Status.PASSED)));

        assertThat(result.preserved()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("COMPANY_TOTAL_BELOW_TOLERANCE");
    }
}
