package com.mimococo.marketops.operatingfacts;

import com.mimococo.marketops.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A company-owned input to the profit definition, in force at one instant.
 *
 * <p>Exactly one of the two values is set, matching how the version was
 * recorded. A rate and an amount are not interchangeable, and letting a caller
 * read whichever is non-null keeps the distinction visible at the point of use.
 *
 * @param financeInputVersionId the version that was in force
 * @param inputCode which input this is
 * @param rateValue the rate, or {@code null} when the input is an amount
 * @param amountValue the amount, or {@code null} when the input is a rate
 * @param effectiveFrom when the version took effect
 * @param provenanceId where the version came from
 */
public record FinanceInputSnapshot(
        UUID financeInputVersionId,
        String inputCode,
        BigDecimal rateValue,
        Money amountValue,
        Instant effectiveFrom,
        UUID provenanceId) {
}
