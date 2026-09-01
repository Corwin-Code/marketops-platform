package com.mimococo.marketops.operationsworkflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The distinctions the case state machine must refuse to collapse.
 *
 * <p>Each test here corresponds to a way the loop could be quietly made
 * dishonest: by letting a recorded action count as a success, by letting a
 * closed case be revived so its history could be rewritten, or by letting an
 * acceptance look like an outcome.
 */
class AvailabilityCaseStateTest {

    @Test
    @DisplayName("TC-CASE-001 recording an action can never reach success directly")
    void actionCannotReachSuccessDirectly() {
        assertThat(AvailabilityCaseState.ACTION_RECORDED.allowedNext())
                .doesNotContain(AvailabilityCaseState.VERIFIED_SUCCESS);
        assertThat(AvailabilityCaseState.ACTION_RECORDED.allowedNext())
                .contains(AvailabilityCaseState.VERIFYING);
    }

    @Test
    @DisplayName("TC-CASE-002 only verification reaches success")
    void onlyVerificationReachesSuccess() {
        var reaching = Arrays.stream(AvailabilityCaseState.values())
                .filter(state -> state.allowedNext().contains(AvailabilityCaseState.VERIFIED_SUCCESS))
                .toList();

        assertThat(reaching).containsExactly(AvailabilityCaseState.VERIFYING);
    }

    @Test
    @DisplayName("TC-CASE-003 an accepted risk is not an outcome")
    void acceptedRiskIsNotAnOutcome() {
        assertThat(AvailabilityCaseState.ACCEPTED_RISK.allowedNext())
                .doesNotContain(AvailabilityCaseState.VERIFIED_SUCCESS);
        assertThat(AvailabilityCaseState.ACCEPTED_RISK.terminal()).isFalse();
        assertThat(AvailabilityCaseState.ACCEPTED_RISK.live()).isTrue();
    }

    @Test
    @DisplayName("TC-CASE-004 a closed case cannot be revived")
    void terminalStatesAreTerminal() {
        assertThat(AvailabilityCaseState.VERIFIED_SUCCESS.allowedNext()).isEmpty();
        assertThat(AvailabilityCaseState.CANCELLED.allowedNext()).isEmpty();
        assertThat(AvailabilityCaseState.VERIFIED_SUCCESS.terminal()).isTrue();
        assertThat(AvailabilityCaseState.CANCELLED.terminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AvailabilityCaseState.class)
    @DisplayName("TC-CASE-005 a live state is exactly a non-terminal one")
    void liveIsTheComplementOfTerminal(AvailabilityCaseState state) {
        assertThat(state.live()).isEqualTo(!state.terminal());
    }

    @ParameterizedTest
    @EnumSource(value = AvailabilityCaseState.class,
            names = {"OPEN", "ASSIGNED", "IN_PROGRESS", "REOPENED", "ESCALATED",
                     "REWORK_REQUIRED"})
    @DisplayName("TC-CASE-006 every working state can still record an action")
    void everyWorkingStateCanRecordAnAction(AvailabilityCaseState state) {
        assertThat(state.allowedNext()).contains(AvailabilityCaseState.ACTION_RECORDED);
    }

    @Test
    @DisplayName("TC-CASE-007 a regression returns to the same case rather than closing it")
    void regressionReopensRatherThanClosing() {
        assertThat(AvailabilityCaseState.VERIFYING.allowedNext())
                .contains(AvailabilityCaseState.REOPENED,
                        AvailabilityCaseState.REWORK_REQUIRED);
        assertThat(AvailabilityCaseState.REOPENED.live()).isTrue();
    }

    @Test
    @DisplayName("TC-CASE-008 no action kind means merely acknowledging the case")
    void noActionKindMeansAcknowledgement() {
        assertThat(CaseActionKind.values()).hasSize(6);
        assertThat(Arrays.stream(CaseActionKind.values()).map(Enum::name))
                .allSatisfy(name -> assertThat(name)
                        .doesNotContain("ACKNOWLEDGE")
                        .doesNotContain("REVIEW")
                        .doesNotContain("NOTE"));
    }

    @Test
    @DisplayName("TC-CASE-009 a verification outcome distinguishes not-yet from no")
    void verificationDistinguishesNotYetFromNo() {
        assertThat(CaseVerificationOutcome.values()).containsExactlyInAnyOrder(
                CaseVerificationOutcome.VERIFIED, CaseVerificationOutcome.CONTINUING,
                CaseVerificationOutcome.FAILED, CaseVerificationOutcome.REGRESSED);
    }
}
