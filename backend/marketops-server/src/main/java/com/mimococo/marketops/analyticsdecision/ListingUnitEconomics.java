package com.mimococo.marketops.analyticsdecision;

import com.mimococo.marketops.operatingfacts.ListingPriceTerms;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * What one unit of a listing earns at a price, estimated from the tariffs the
 * marketplace states for it.
 *
 * <p>The Owner's decisions of 2026-09-29 fix the terms: the sales commission of
 * the store's fulfilment scheme, the highest stated logistics tariffs,
 * acquiring, VAT at the listing's rate contained in the price (no other
 * turnover tax), and the unit purchase cost. With a price {@code P}, commission
 * rate {@code c}, fixed tariffs {@code F} (logistics plus acquiring), VAT rate
 * {@code v} and unit cost {@code C}:
 *
 * <pre>
 * profit(P)    = P - P*c - F - P*v/(1+v) - C
 * break-even   = (F + C) / (1 - c - v/(1+v))
 * target(m)    = (F + C) / (1 - c - v/(1+v) - m)
 * </pre>
 *
 * <p>A price that no rate structure can cover (a denominator at or below zero)
 * has no break-even or target price; that is reported as absent, not as a
 * number. Everything is exact decimal arithmetic, rounded only at the end:
 * money to the minor unit, with break-even and target prices rounded up so the
 * price shown really reaches its bound, and the margin to four decimals.
 */
public final class ListingUnitEconomics {

    /** Internal precision before the final rounding. */
    private static final int SCALE = 12;

    /** Money is quoted in minor units; every currency the marketplaces price in here has two. */
    private static final int MONEY_SCALE = 2;

    /** A margin to a hundredth of a percent. */
    private static final int RATIO_SCALE = 4;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ListingUnitEconomics() {
    }

    /** Who ships the order, which decides the commission and logistics that apply. */
    public enum Scheme {

        /** The seller ships from its own warehouse (Ozon FBS). */
        SELLER_FULFILLED,

        /** The marketplace ships from its warehouse (Ozon FBO). */
        MARKETPLACE_FULFILLED
    }

    /**
     * The terms that apply to one sale under one scheme.
     *
     * @param commissionRate the sales commission as a ratio (0.52 = 52 %)
     * @param logistics the highest stated logistics tariffs summed, per unit
     * @param acquiring the stated acquiring fee per unit
     * @param vatRate the VAT rate contained in the price
     */
    public record Terms(BigDecimal commissionRate, BigDecimal logistics, BigDecimal acquiring,
                        BigDecimal vatRate) {
    }

    /**
     * One estimate.
     *
     * @param breakEvenPrice {@code null} when no price covers the cost
     * @param targetMarginPrice {@code null} when no price keeps the margin
     */
    public record Estimate(BigDecimal price, BigDecimal commission, BigDecimal logistics,
                           BigDecimal acquiring, BigDecimal vat, BigDecimal unitCost, BigDecimal profit,
                           BigDecimal margin, BigDecimal breakEvenPrice, BigDecimal targetMarginPrice) {
    }

    /**
     * The terms of a sale under a scheme, from what the marketplace stated;
     * empty when any of them was not stated.
     */
    public static Optional<Terms> terms(ListingPriceTerms stated, Scheme scheme) {
        if (stated == null || scheme == null || stated.acquiringMax() == null || stated.vatRate() == null) {
            return Optional.empty();
        }
        BigDecimal percent;
        BigDecimal logistics;
        if (scheme == Scheme.SELLER_FULFILLED) {
            percent = stated.salesCommissionPercentFbs();
            logistics = sum(stated.fbsFirstMileMax(), stated.fbsDirectFlowMax(), stated.fbsLastMile());
        } else {
            percent = stated.salesCommissionPercentFbo();
            logistics = sum(stated.fboDirectFlowMax(), stated.fboLastMile());
        }
        if (percent == null || logistics == null) {
            return Optional.empty();
        }
        return Optional.of(new Terms(percent.divide(HUNDRED, SCALE, RoundingMode.HALF_UP), logistics,
                stated.acquiringMax(), stated.vatRate()));
    }

    /**
     * Estimate one unit sold at {@code price}.
     *
     * @param minimumMarginRate the margin the target price keeps, or {@code null} for no target price
     */
    public static Estimate estimate(BigDecimal price, Terms terms, BigDecimal unitCost,
                                    BigDecimal minimumMarginRate) {
        BigDecimal vatShare = terms.vatRate().divide(BigDecimal.ONE.add(terms.vatRate()), SCALE,
                RoundingMode.HALF_UP);
        BigDecimal commission = price.multiply(terms.commissionRate());
        BigDecimal vat = price.multiply(vatShare);
        BigDecimal fixed = terms.logistics().add(terms.acquiring());
        BigDecimal profit = price.subtract(commission).subtract(fixed).subtract(vat).subtract(unitCost);
        BigDecimal margin = price.signum() == 0 ? null : profit.divide(price, SCALE, RoundingMode.HALF_UP);
        BigDecimal cover = BigDecimal.ONE.subtract(terms.commissionRate()).subtract(vatShare);
        BigDecimal breakEven = priceFor(fixed.add(unitCost), cover);
        BigDecimal target = minimumMarginRate == null ? null
                : priceFor(fixed.add(unitCost), cover.subtract(minimumMarginRate));
        return new Estimate(money(price), money(commission), money(terms.logistics()),
                money(terms.acquiring()), money(vat), money(unitCost), money(profit),
                margin == null ? null : margin.setScale(RATIO_SCALE, RoundingMode.HALF_UP),
                breakEven, target);
    }

    /** The lowest price, in minor units, at which {@code share} of it covers {@code costs}. */
    private static BigDecimal priceFor(BigDecimal costs, BigDecimal share) {
        return share.signum() <= 0 ? null
                : costs.divide(share, SCALE, RoundingMode.HALF_UP).setScale(MONEY_SCALE, RoundingMode.CEILING);
    }

    private static BigDecimal sum(BigDecimal... amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            if (amount == null) {
                return null;
            }
            total = total.add(amount);
        }
        return total;
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
