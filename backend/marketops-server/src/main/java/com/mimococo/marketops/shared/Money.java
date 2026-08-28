package com.mimococo.marketops.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact monetary amount in one stated currency.
 *
 * <p>Money is never a bare number in this product. A profit figure that lost
 * its currency, or that was accumulated in binary floating point, is wrong in a
 * way nobody notices until a price decision has already been executed, so the
 * amount is a {@link BigDecimal} fixed at four decimal places and the currency
 * travels with it.
 *
 * <p>Arithmetic refuses to mix currencies. There is deliberately no conversion
 * here: a rate is a business fact with a source and an effective time, and
 * inventing one inside an arithmetic helper would put an unattributed
 * assumption inside every derived figure.
 */
public record Money(BigDecimal amount, String currencyCode) implements Comparable<Money> {

    /** Scale every stored and computed amount is normalised to. */
    public static final int SCALE = 4;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currencyCode, "currencyCode");
        if (!isKnownCurrency(currencyCode)) {
            throw OperationRejectedException.of(ErrorCode.INVALID_CURRENCY);
        }
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** An amount in a currency, rounded half-up to the canonical scale. */
    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currencyCode);
    }

    /** Zero in a currency, used as an accumulator start rather than as a fact. */
    public static Money zero(String currencyCode) {
        return new Money(BigDecimal.ZERO, currencyCode);
    }

    /** Sum, refusing a currency mismatch. */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currencyCode);
    }

    /** Difference, refusing a currency mismatch. */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currencyCode);
    }

    /** Product with a dimensionless factor such as a quantity or a rate. */
    public Money times(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        return new Money(amount.multiply(factor), currencyCode);
    }

    /**
     * Quotient by a dimensionless divisor.
     *
     * <p>A zero divisor is refused rather than answered. A metric whose
     * denominator is zero is UNDEFINED, and the caller has to record that
     * rather than receive a substituted number.
     */
    public Money dividedBy(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor");
        if (divisor.signum() == 0) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        return new Money(amount.divide(divisor, SCALE, RoundingMode.HALF_UP), currencyCode);
    }

    /** The same magnitude with the opposite sign. */
    public Money negated() {
        return new Money(amount.negate(), currencyCode);
    }

    /** Whether the amount is greater than zero. */
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** Whether the amount is less than zero. */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currencyCode.equals(other.currencyCode)) {
            throw OperationRejectedException.of(ErrorCode.CURRENCY_MISMATCH);
        }
    }

    private static boolean isKnownCurrency(String code) {
        if (code.length() != 3) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
