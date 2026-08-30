package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact recommendation parameter schema shared by the Java price-change path. */
final class PriceChangeParameterContract {

    private static final Set<String> ALLOWED_KEYS =
            Set.of("targetPrice", "fulfillmentModeCode");
    private static final Pattern TARGET_PRICE =
            Pattern.compile("^[0-9]{1,14}([.][0-9]{1,4})?$");
    private static final Pattern FULFILLMENT_MODE =
            Pattern.compile("^[A-Z][A-Z0-9_]{1,62}$");

    private PriceChangeParameterContract() {
    }

    static void requireValid(Map<String, String> parameters) {
        if (parameters == null || !ALLOWED_KEYS.containsAll(parameters.keySet())
                || !validTarget(parameters.get("targetPrice"))
                || !validMode(parameters)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static boolean validTarget(String value) {
        if (value == null || !TARGET_PRICE.matcher(value).matches()) {
            return false;
        }
        try {
            return new BigDecimal(value).signum() > 0;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    private static boolean validMode(Map<String, String> parameters) {
        if (!parameters.containsKey("fulfillmentModeCode")) {
            return true;
        }
        String mode = parameters.get("fulfillmentModeCode");
        return mode != null && FULFILLMENT_MODE.matcher(mode).matches()
                && !"UNKNOWN".equals(mode);
    }
}
