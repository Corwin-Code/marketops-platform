package com.mimococo.marketops.operationsworkflow.internal.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriceChangeParameterContractTest {

    @Test
    void targetPriceAloneAndOneExplicitModeAreTheOnlyValidShapes() {
        assertThatCode(() -> PriceChangeParameterContract.requireValid(
                Map.of("targetPrice", "105.0000"))).doesNotThrowAnyException();
        assertThatCode(() -> PriceChangeParameterContract.requireValid(Map.of(
                "targetPrice", "105.0000",
                "fulfillmentModeCode", "SELLER_FULFILLED")))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownInactiveSyntaxAndEveryExtraKeyFailClosed() {
        assertInvalid(Map.of("targetPrice", "105.0000",
                "fulfillmentModeCode", "UNKNOWN"));
        assertInvalid(Map.of("targetPrice", "105.0000",
                "fulfillmentModeCode", "inactive-lowercase"));
        assertInvalid(Map.of("targetPrice", "105.0000", "unexpected", "value"));
        assertInvalid(Map.of("fulfillmentModeCode", "SELLER_FULFILLED"));
        assertInvalid(Map.of("targetPrice", "0"));
    }

    private static void assertInvalid(Map<String, String> parameters) {
        assertThatThrownBy(() -> PriceChangeParameterContract.requireValid(parameters))
                .isInstanceOf(OperationRejectedException.class)
                .extracting(failure -> ((OperationRejectedException) failure).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
