package com.mimococo.marketops.operationsworkflow.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a bid-change recommendation may say, in Java. */
class AdBidChangeParameterContractTest {

    @Test
    @DisplayName("TC-AD-PARAM-001 every shared case is judged the same way")
    void everySharedCaseIsJudgedTheSameWay() {
        SoftAssertions softly = new SoftAssertions();
        for (AdBidParameterCases.Case testCase : AdBidParameterCases.all()) {
            boolean accepted;
            try {
                AdBidChangeParameterContract.requireValid(testCase.parameters());
                accepted = true;
            } catch (OperationRejectedException refused) {
                accepted = false;
            }
            softly.assertThat(accepted)
                    .describedAs("%s", testCase.description())
                    .isEqualTo(testCase.valid());
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("TC-AD-PARAM-002 null parameters are refused rather than defaulted")
    void nullParametersAreRefused() {
        assertThatThrownBy(() -> AdBidChangeParameterContract.requireValid(null))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    @DisplayName("TC-AD-PARAM-003 the readers validate before they read")
    void readersValidateBeforeTheyRead() {
        // A caller that reached targetBid() on an invalid map would get a number
        // no contract vouched for, which is the shape of every quiet mistake in
        // a write path.
        Map<String, String> invalid = Map.of("targetBid", "12.25");

        assertThatThrownBy(() -> AdBidChangeParameterContract.targetBid(invalid))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> AdBidChangeParameterContract.candidateId(invalid))
                .isInstanceOf(OperationRejectedException.class);
        assertThatThrownBy(() -> AdBidChangeParameterContract.direction(invalid))
                .isInstanceOf(OperationRejectedException.class);
    }

    @Test
    @DisplayName("TC-AD-PARAM-004 the target is read exactly as written")
    void targetIsReadExactly() {
        Map<String, String> parameters = Map.of(
                "candidateId", "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
                "direction", "PROTECTION_DECREASE",
                "targetBid", "12.5000");

        assertThatCode(() -> AdBidChangeParameterContract.requireValid(parameters))
                .doesNotThrowAnyException();
        // Not 12.5. The scale travels, because the database column has four
        // decimal places and a comparison against the candidate is exact.
        assertThat(AdBidChangeParameterContract.targetBid(parameters))
                .isEqualTo(new BigDecimal("12.5000"));
        assertThat(AdBidChangeParameterContract.direction(parameters))
                .isEqualTo("PROTECTION_DECREASE");
    }
}
