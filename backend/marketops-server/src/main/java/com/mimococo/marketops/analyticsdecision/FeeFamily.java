package com.mimococo.marketops.analyticsdecision;

import java.util.Set;

/**
 * One independently covered family of projected or historical unit economics.
 *
 * <p>The historical categories are deliberately explicit. A non-null aggregate
 * cannot establish that any one family contributed, and an unknown native fee
 * never gets silently assigned to the most convenient family.
 */
public enum FeeFamily {
    COMMISSION(Set.of("COMMISSION"), true),
    FULFILLMENT_DELIVERY(Set.of("FULFILLMENT", "DELIVERY"), true),
    STORAGE(Set.of("STORAGE"), true),
    PROMOTION(Set.of("PROMOTION"), true),
    OTHER_VARIABLE(Set.of("OTHER_VARIABLE", "RETURN_PROCESSING"), true),
    RETURN_LOSS(Set.of(), false),
    ADVERTISING(Set.of("ADVERTISING"), false),
    VARIABLE_TAX(Set.of("VARIABLE_TAX"), false);

    private final Set<String> historicalCategories;
    private final boolean historicalPlatformFeeFamily;

    FeeFamily(Set<String> historicalCategories, boolean historicalPlatformFeeFamily) {
        this.historicalCategories = Set.copyOf(historicalCategories);
        this.historicalPlatformFeeFamily = historicalPlatformFeeFamily;
    }

    /** Native-normalized ledger categories that establish this family in observed actuals. */
    public Set<String> historicalCategories() {
        return historicalCategories;
    }

    /** Whether Contribution Profit expects this family inside platform-fee facts. */
    public boolean historicalPlatformFeeFamily() {
        return historicalPlatformFeeFamily;
    }
}
