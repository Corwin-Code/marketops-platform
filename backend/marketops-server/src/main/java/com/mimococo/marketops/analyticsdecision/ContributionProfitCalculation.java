package com.mimococo.marketops.analyticsdecision;

import com.mimococo.marketops.shared.Money;
import java.util.Objects;

/** Canonical contribution-profit arithmetic. Source qualification remains with the publishing owner. */
public final class ContributionProfitCalculation {
    private ContributionProfitCalculation() { }

    public static Money calculate(Money netRevenue, Money goodsCost, Money platformFees,
                                  Money returnLoss, Money advertising, Money variableTax) {
        Objects.requireNonNull(netRevenue,"netRevenue");
        return netRevenue.minus(Objects.requireNonNull(goodsCost,"goodsCost"))
                .minus(Objects.requireNonNull(platformFees,"platformFees"))
                .minus(Objects.requireNonNull(returnLoss,"returnLoss"))
                .minus(Objects.requireNonNull(advertising,"advertising"))
                .minus(Objects.requireNonNull(variableTax,"variableTax"));
    }
}
