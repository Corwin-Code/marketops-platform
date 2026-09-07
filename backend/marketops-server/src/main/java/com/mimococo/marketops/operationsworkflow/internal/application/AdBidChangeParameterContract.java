package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exact recommendation parameter schema for the advertising bid-change path.
 *
 * <p>Deliberately the same shape as {@code ops.ad_bid_parameter_contract_is_valid}
 * in the database: the same three keys, no others, and the same patterns. Two
 * copies of one rule is a risk, and the alternative — one copy, in Java — is a
 * worse one, because the database is what refuses a row nobody's Java touched.
 * The agreement between them is asserted rather than assumed.
 *
 * <p>The candidate identifier is required because a bid change is never proposed
 * freehand. It names the provider-normalized candidate the target came from, and
 * command creation refuses if that candidate no longer agrees.
 */
final class AdBidChangeParameterContract {

    private static final Set<String> ALLOWED_KEYS =
            Set.of("candidateId", "direction", "targetBid");

    /** The three directions a bid may move, and there is no fourth. */
    private static final Set<String> DIRECTIONS = Set.of(
            "PROTECTION_DECREASE", "OPTIMIZATION_INCREASE", "EXACT_PRIOR_BID_COMPENSATION");

    private static final Pattern TARGET_BID =
            Pattern.compile("^[0-9]{1,14}([.][0-9]{1,4})?$");
    private static final Pattern CANDIDATE_ID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private AdBidChangeParameterContract() {
    }

    static void requireValid(Map<String, String> parameters) {
        if (parameters == null
                || !ALLOWED_KEYS.equals(parameters.keySet())
                || !validCandidate(parameters.get("candidateId"))
                || !DIRECTIONS.contains(parameters.get("direction"))
                || !validTarget(parameters.get("targetBid"))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
    }

    /** The candidate this proposal's target came from. */
    static UUID candidateId(Map<String, String> parameters) {
        requireValid(parameters);
        return UUID.fromString(parameters.get("candidateId"));
    }

    /** The proposed bid, as a number the database will accept unchanged. */
    static BigDecimal targetBid(Map<String, String> parameters) {
        requireValid(parameters);
        return new BigDecimal(parameters.get("targetBid"));
    }

    /** Which way the bid moves, and why. */
    static String direction(Map<String, String> parameters) {
        requireValid(parameters);
        return parameters.get("direction");
    }

    private static boolean validCandidate(String value) {
        return value != null && CANDIDATE_ID.matcher(value).matches();
    }

    private static boolean validTarget(String value) {
        if (value == null || !TARGET_BID.matcher(value).matches()) {
            return false;
        }
        // Zero is not a small bid. A marketplace that accepts it either stops
        // the object or refuses, and neither is what a decrease means here.
        return new BigDecimal(value).signum() > 0;
    }
}
