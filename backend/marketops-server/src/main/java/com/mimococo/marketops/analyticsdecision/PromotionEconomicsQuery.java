package com.mimococo.marketops.analyticsdecision;

import com.mimococo.marketops.operatingfacts.PromotionSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What joining each of a store's current promotions would mean for each product: the estimated
 * unit margin at the promotion's maximum and recommended prices and at the price that earns the
 * largest boost, beside the margin at today's price, with the same terms and the same margin floor
 * as the store diagnosis (Owner decisions of 2026-09-29).
 *
 * <p>The estimates are the store's official arithmetic ({@link ListingUnitEconomics}); a model
 * may explain them and never replace them.
 */
public interface PromotionEconomicsQuery {

    /** Every current promotion of a store with the economics of each of its products. */
    List<PromotionEconomics> forStore(UUID organizationId, UUID storeId, Instant asOf);

    /**
     * The store's promotions that ended from an inclusive instant up to an exclusive one, as their
     * newest snapshot described them; a promotion the store took part in can explain a buyer price
     * that rose when it ended.
     */
    List<PromotionSnapshot> endedForStore(UUID organizationId, UUID storeId, Instant from, Instant asOf);

    /**
     * One promotion and its products.
     *
     * @param minimumMarginRate the margin floor the verdicts compare with, or {@code null} when unset
     */
    record PromotionEconomics(PromotionSnapshot promotion, List<ItemEconomics> items, BigDecimal minimumMarginRate) {

        public PromotionEconomics {
            Objects.requireNonNull(promotion, "promotion");
            items = List.copyOf(items);
        }
    }

    /**
     * One product of a promotion. Margins are ratios (0.1685 = 16.85 %); a margin is {@code null}
     * when its price or an input is missing, and {@code missing} names the inputs that were.
     *
     * @param item the product as the promotion answer described it
     * @param marginNow the margin at today's buyer price
     * @param marginAtActionPrice the margin at the price a participant has in the promotion
     * @param marginAtMaxActionPrice the margin at the highest price the promotion allows
     * @param marginAtRecommendedPrice the margin at the price the marketplace recommends
     * @param marginAtMaxBoostPrice the margin at the price that earns the largest boost
     * @param breakEvenPrice the lowest price that covers the unit's costs, or {@code null}
     * @param verdict JOIN_KEEPS_FLOOR, JOIN_BELOW_FLOOR, JOIN_LOSES or UNKNOWN: for a participant judged
     *        at its action price, the price it sells at in the promotion; for a candidate at the
     *        highest price the promotion allows (joining never earns more than that)
     */
    record ItemEconomics(PromotionSnapshot.Item item, BigDecimal buyerPriceNow, BigDecimal marginNow,
                         BigDecimal marginAtActionPrice, BigDecimal marginAtMaxActionPrice,
                         BigDecimal marginAtRecommendedPrice,
                         BigDecimal marginAtMaxBoostPrice, BigDecimal breakEvenPrice, String verdict,
                         List<String> missing) {

        public ItemEconomics {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(verdict, "verdict");
            missing = List.copyOf(missing);
        }
    }
}
