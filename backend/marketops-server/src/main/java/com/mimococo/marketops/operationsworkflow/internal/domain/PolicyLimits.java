package com.mimococo.marketops.operationsworkflow.internal.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The limits one policy version configures, keyed by limit code.
 *
 * <p>An absent limit is not an unlimited one. Every accessor returns an empty
 * result rather than a permissive default, and the engine turns an absent
 * required limit into a refusal, so a half-configured policy denies instead of
 * silently permitting whatever it forgot to bound.
 *
 * @param policyId the policy version these limits belong to
 * @param policyVersion its version number
 * @param currencyCode currency the amount limits are expressed in
 * @param lifecycleObjective what the policy is trying to achieve for the subject
 * @param rates rate limits, by code
 * @param amounts money limits, by code
 * @param counts count limits, by code
 * @param durations duration limits in seconds, by code
 */
public record PolicyLimits(
        UUID policyId,
        int policyVersion,
        String currencyCode,
        String lifecycleObjective,
        Map<String, BigDecimal> rates,
        Map<String, BigDecimal> amounts,
        Map<String, Integer> counts,
        Map<String, Long> durations) {

    public PolicyLimits {
        rates = Map.copyOf(Objects.requireNonNull(rates, "rates"));
        amounts = Map.copyOf(Objects.requireNonNull(amounts, "amounts"));
        counts = Map.copyOf(Objects.requireNonNull(counts, "counts"));
        durations = Map.copyOf(Objects.requireNonNull(durations, "durations"));
    }

    /** A rate limit, when the policy configures one. */
    public Optional<BigDecimal> rate(String limitCode) {
        return Optional.ofNullable(rates.get(limitCode));
    }

    /** A money limit, when the policy configures one. */
    public Optional<BigDecimal> amount(String limitCode) {
        return Optional.ofNullable(amounts.get(limitCode));
    }

    /** A count limit, when the policy configures one. */
    public Optional<Integer> count(String limitCode) {
        return Optional.ofNullable(counts.get(limitCode));
    }

    /** A duration limit in seconds, when the policy configures one. */
    public Optional<Long> duration(String limitCode) {
        return Optional.ofNullable(durations.get(limitCode));
    }

    /** Whether the policy configures anything at all under this code. */
    public boolean configures(String limitCode) {
        return rates.containsKey(limitCode) || amounts.containsKey(limitCode)
                || counts.containsKey(limitCode) || durations.containsKey(limitCode);
    }
}
