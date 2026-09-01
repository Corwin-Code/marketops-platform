package com.mimococo.marketops.operationsworkflow.internal.domain;

import com.mimococo.marketops.operationsworkflow.ExceptionAuthorityLevel;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What makes an accepted risk material enough to need a higher approver.
 *
 * <p>Every threshold here was published by a named owner. There is no
 * permissive default and no fallback: a caller with no version in force does
 * not get a lenient answer from this type, it gets no answer at all, and the
 * service records the request as authority-blocked. That is the difference
 * between failing closed and failing quietly.
 *
 * @param policyId the published version's identity
 * @param policyVersion its version number
 * @param currencyCode the currency the money threshold is expressed in
 * @param materialProfitAtRisk exposure at or above which an acceptance is material
 * @param materialDuration how long an acceptance may run before it is material
 * @param repeatOccurrenceCount acceptances of one cause that count as repetition
 * @param repeatLookback how far back repetition is counted
 * @param maxExceptionDuration the longest acceptance the organization allows
 */
public record ExceptionMaterialityPolicy(
        UUID policyId,
        int policyVersion,
        String currencyCode,
        BigDecimal materialProfitAtRisk,
        Duration materialDuration,
        int repeatOccurrenceCount,
        Duration repeatLookback,
        Duration maxExceptionDuration) {

    public ExceptionMaterialityPolicy {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(currencyCode, "currencyCode");
        Objects.requireNonNull(materialProfitAtRisk, "materialProfitAtRisk");
        Objects.requireNonNull(materialDuration, "materialDuration");
        Objects.requireNonNull(repeatLookback, "repeatLookback");
        Objects.requireNonNull(maxExceptionDuration, "maxExceptionDuration");
    }

    /**
     * How much authority accepting this exposure needs.
     *
     * @param severity the calculated lane being accepted
     * @param occurrenceCount acceptances of this cause inside the lookback, including this one
     * @param consequenceAmount the expected exposure, or {@code null} when none was stated
     * @param consequenceCurrency its currency, or {@code null}
     * @param from when the acceptance would start
     * @param until when it would end
     */
    public ExceptionAuthorityLevel requiredAuthority(String severity, int occurrenceCount,
                                                     BigDecimal consequenceAmount,
                                                     String consequenceCurrency,
                                                     Instant from, Instant until) {
        ExceptionAuthorityLevel level = forSeverity(severity);
        if (repeated(occurrenceCount)
                || material(consequenceAmount, consequenceCurrency)
                || longRunning(from, until)) {
            return level.raisedTo(ExceptionAuthorityLevel.RISK_AUTHORITY);
        }
        return level;
    }

    /**
     * Whether the requester may also be the final approver.
     *
     * <p>Separation is required exactly where the Contract requires it — for a
     * critical, repeated or material acceptance — and the answer is recorded on
     * the decision so the rule is evidence in the row rather than a claim about
     * what some service checked.
     */
    public boolean separationRequired(String severity, int occurrenceCount,
                                      BigDecimal consequenceAmount, String consequenceCurrency,
                                      Instant from, Instant until) {
        return requiredAuthority(severity, occurrenceCount, consequenceAmount,
                consequenceCurrency, from, until) == ExceptionAuthorityLevel.RISK_AUTHORITY;
    }

    /** Whether a requested period is longer than the organization allows at all. */
    public boolean exceedsMaximum(Instant from, Instant until) {
        return from == null || until == null
                || Duration.between(from, until).compareTo(maxExceptionDuration) > 0;
    }

    /**
     * The floor a lane sets before materiality raises it.
     *
     * <p>A blocked or unresolved lane sits with HIGH rather than with WATCH:
     * accepting a risk nobody has been able to calculate is at least as serious
     * as accepting one that has been.
     */
    private static ExceptionAuthorityLevel forSeverity(String severity) {
        return switch (severity == null ? "" : severity) {
            case "CRITICAL" -> ExceptionAuthorityLevel.RISK_AUTHORITY;
            case "WATCH" -> ExceptionAuthorityLevel.DOMAIN_LEAD;
            default -> ExceptionAuthorityLevel.OPS_LEAD;
        };
    }

    private boolean repeated(int occurrenceCount) {
        return occurrenceCount >= repeatOccurrenceCount;
    }

    /**
     * Whether the stated exposure reaches the money threshold.
     *
     * <p>A different currency is not compared and not converted. Comparing
     * across currencies without a rate somebody published would be inventing a
     * financial fact, so the amount is treated as unstated and the other tests
     * decide.
     */
    private boolean material(BigDecimal consequenceAmount, String consequenceCurrency) {
        return consequenceAmount != null
                && currencyCode.equals(consequenceCurrency)
                && consequenceAmount.compareTo(materialProfitAtRisk) >= 0;
    }

    private boolean longRunning(Instant from, Instant until) {
        return from != null && until != null
                && Duration.between(from, until).compareTo(materialDuration) >= 0;
    }
}
