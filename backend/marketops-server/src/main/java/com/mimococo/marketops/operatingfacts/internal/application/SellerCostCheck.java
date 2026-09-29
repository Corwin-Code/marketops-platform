package com.mimococo.marketops.operatingfacts.internal.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a seller's marketplace cost may replace the purchase cost in force
 * without a person looking at it.
 *
 * <p>The rules are the Owner's (2026-09-29): a changed cost is adopted
 * automatically unless it moves by more than the policy's limit from the cost
 * in force, is not below the price buyers are offered, or arrives in another
 * currency. Those are the changes a typo or a misunderstanding produces.
 */
public final class SellerCostCheck {

    /** The Owner's default limit: ±30 % from the cost in force. */
    public static final BigDecimal DEFAULT_CHANGE_LIMIT = new BigDecimal("0.30");

    /** The cost in force and the new cost are in different currencies. */
    public static final String CURRENCY_CHANGED = "CURRENCY_CHANGED";

    /** The new cost is not below the price buyers are offered. */
    public static final String COST_NOT_BELOW_PRICE = "COST_NOT_BELOW_PRICE";

    /** The new cost moves by more than the limit from the cost in force. */
    public static final String CHANGE_OVER_LIMIT = "CHANGE_OVER_LIMIT";

    private SellerCostCheck() {
    }

    /**
     * Why the new cost must wait for a person, or empty when it may be adopted.
     *
     * @param sellerCost the new cost, positive
     * @param currencyCode its currency, which is also the buyer price's
     * @param buyerPrice the price buyers are offered in the same observation, or {@code null}
     * @param currentCost the purchase cost in force, or {@code null} for a first cost
     * @param currentCurrencyCode its currency, or {@code null}
     * @param limit the largest relative change adopted without a person
     */
    public static Optional<String> anomaly(BigDecimal sellerCost, String currencyCode, BigDecimal buyerPrice,
                                           BigDecimal currentCost, String currentCurrencyCode,
                                           BigDecimal limit) {
        if (currentCost != null && !Objects.equals(currentCurrencyCode, currencyCode)) {
            return Optional.of(CURRENCY_CHANGED);
        }
        if (buyerPrice != null && buyerPrice.signum() > 0 && sellerCost.compareTo(buyerPrice) >= 0) {
            return Optional.of(COST_NOT_BELOW_PRICE);
        }
        if (currentCost != null && currentCost.signum() > 0) {
            BigDecimal change = sellerCost.subtract(currentCost).abs()
                    .divide(currentCost, 6, RoundingMode.HALF_UP);
            if (change.compareTo(limit) > 0) {
                return Optional.of(CHANGE_OVER_LIMIT);
            }
        }
        return Optional.empty();
    }
}
