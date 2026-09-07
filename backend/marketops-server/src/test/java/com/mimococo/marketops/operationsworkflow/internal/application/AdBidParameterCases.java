package com.mimococo.marketops.operationsworkflow.internal.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The exact parameter shapes the bid-change contract accepts and refuses.
 *
 * <p>Shared by the unit test that asserts the Java contract and the integration
 * test that asserts the database function, so the two cannot be asserted against
 * different cases. There are two copies of this rule in the product — one in
 * Java, one in SQL — and a shared case list is what turns that from a risk into
 * something a test can check.
 */
final class AdBidParameterCases {

    private static final String CANDIDATE = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

    private AdBidParameterCases() {
    }

    /** One shape and whether both implementations must accept it. */
    record Case(String description, Map<String, String> parameters, boolean valid) {
    }

    static List<Case> all() {
        return List.of(
                new Case("a well-formed increase",
                        parameters(CANDIDATE, "OPTIMIZATION_INCREASE", "31.5000"), true),
                new Case("a well-formed decrease",
                        parameters(CANDIDATE, "PROTECTION_DECREASE", "12"), true),
                new Case("a compensation back to the prior bid",
                        parameters(CANDIDATE, "EXACT_PRIOR_BID_COMPENSATION", "12.25"), true),
                new Case("a direction nobody defined",
                        parameters(CANDIDATE, "SOMETHING_ELSE", "12.25"), false),
                new Case("a budget change, which is not a bid change",
                        parameters(CANDIDATE, "AD_BUDGET_CHANGE", "12.25"), false),
                new Case("a candidate that is not an identifier",
                        parameters("not-a-uuid", "PROTECTION_DECREASE", "12.25"), false),
                new Case("an uppercase candidate identifier",
                        parameters(CANDIDATE.toUpperCase(java.util.Locale.ROOT),
                                "PROTECTION_DECREASE", "12.25"), false),
                new Case("a zero bid, which stops rather than lowers",
                        parameters(CANDIDATE, "PROTECTION_DECREASE", "0"), false),
                new Case("a negative bid",
                        parameters(CANDIDATE, "PROTECTION_DECREASE", "-5.00"), false),
                new Case("a bid with more precision than the column holds",
                        parameters(CANDIDATE, "PROTECTION_DECREASE", "12.123456"), false),
                new Case("a bid in scientific notation",
                        parameters(CANDIDATE, "PROTECTION_DECREASE", "1.2e3"), false),
                new Case("a missing candidate",
                        Map.of("direction", "PROTECTION_DECREASE", "targetBid", "12.25"), false),
                new Case("a missing direction",
                        Map.of("candidateId", CANDIDATE, "targetBid", "12.25"), false),
                new Case("a missing target",
                        Map.of("candidateId", CANDIDATE, "direction", "PROTECTION_DECREASE"),
                        false),
                new Case("an extra key the contract does not admit",
                        withExtra("targetPrice", "99.00"), false),
                new Case("a budget carried alongside a bid",
                        withExtra("targetBudget", "5000.00"), false),
                new Case("no parameters at all", Map.of(), false));
    }

    private static Map<String, String> parameters(String candidate, String direction,
                                                  String targetBid) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("candidateId", candidate);
        parameters.put("direction", direction);
        parameters.put("targetBid", targetBid);
        return Map.copyOf(parameters);
    }

    private static Map<String, String> withExtra(String key, String value) {
        Map<String, String> parameters =
                new LinkedHashMap<>(parameters(CANDIDATE, "PROTECTION_DECREASE", "12.25"));
        parameters.put(key, value);
        return Map.copyOf(parameters);
    }
}
