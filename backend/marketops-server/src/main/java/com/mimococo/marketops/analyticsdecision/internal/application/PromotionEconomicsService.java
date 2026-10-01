package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.ListingUnitEconomics;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.PromotionEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.internal.config.AnalyticsProperties;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.PromotionSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unit economics of a store's current promotions, computed exactly as the store diagnosis
 * computes them at today's price: the stated tariffs of the store's fulfilment scheme, VAT at the
 * listing's rate, the mapped unit cost, and the Owner's margin floor.
 */
@Service
class PromotionEconomicsService implements PromotionEconomicsQuery {

    private final OperatingFactQuery facts;
    private final ListingEconomicsInputs listingInputs;
    private final PriceEconomicsQuery economics;
    private final AnalyticsProperties properties;

    PromotionEconomicsService(OperatingFactQuery facts, ListingEconomicsInputs listingInputs,
                              PriceEconomicsQuery economics, AnalyticsProperties properties) {
        this.facts = facts;
        this.listingInputs = listingInputs;
        this.economics = economics;
        this.properties = properties;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PromotionEconomics> forStore(UUID organizationId, UUID storeId, Instant asOf) {
        List<PromotionSnapshot> promotions = facts.currentPromotions(storeId, asOf);
        if (promotions.isEmpty()) {
            return List.of();
        }
        List<String> modes = economics.activeFulfillmentModes(storeId, asOf);
        BigDecimal floor = properties.getThresholds().getMinimumUnitMarginRate();
        java.util.Map<UUID, ListingEconomicsInputs.Inputs> inputs = new java.util.HashMap<>();
        List<PromotionEconomics> result = new ArrayList<>();
        for (PromotionSnapshot promotion : promotions) {
            List<ItemEconomics> items = promotion.items().stream()
                    .map(item -> economics(item, inputs.computeIfAbsent(item.listingVariantId(),
                            variant -> listingInputs.of(variant, modes, asOf)), floor))
                    .toList();
            result.add(new PromotionEconomics(promotion, items, floor));
        }
        return result;
    }

    private static ItemEconomics economics(PromotionSnapshot.Item item, ListingEconomicsInputs.Inputs inputs,
                                           BigDecimal floor) {
        List<String> missing = new ArrayList<>(inputs.missing());
        String currency = item.currencyCode();
        if (inputs.cost() != null && currency != null
                && !inputs.cost().unitCost().currencyCode().equals(currency)) {
            missing.add("UNIT_COST_CURRENCY");
        }
        // A participant sells at its own action price, which the highest allowed price can exceed
        // (21 of 28 on 2026-10-01); a candidate can earn no more than the highest allowed price.
        boolean participant = "PARTICIPANT".equals(item.membership());
        BigDecimal judgedPrice = participant && item.actionPrice() != null ? item.actionPrice() : item.maxActionPrice();
        if (judgedPrice == null) {
            missing.add("MAX_ACTION_PRICE");
        }
        boolean computable = inputs.terms() != null && inputs.cost() != null && !missing.contains("UNIT_COST_CURRENCY");
        BigDecimal buyerNow = inputs.stated() == null ? null : inputs.stated().buyerPrice();
        BigDecimal marginNow = computable ? margin(buyerNow, inputs, floor) : null;
        BigDecimal atAction = computable ? margin(item.actionPrice(), inputs, floor) : null;
        BigDecimal atMax = computable ? margin(item.maxActionPrice(), inputs, floor) : null;
        BigDecimal atRecommended = computable ? margin(item.recommendedActionPrice(), inputs, floor) : null;
        BigDecimal atMaxBoost = computable ? margin(item.priceForMaxBoost(), inputs, floor) : null;
        BigDecimal judged = computable ? margin(judgedPrice, inputs, floor) : null;
        BigDecimal breakEven = computable && judgedPrice != null
                ? ListingUnitEconomics.estimate(judgedPrice, inputs.terms(),
                        inputs.cost().unitCost().amount(), floor).breakEvenPrice()
                : null;
        String verdict;
        if (judged == null) {
            verdict = "UNKNOWN";
        } else if (judged.signum() < 0) {
            verdict = "JOIN_LOSES";
        } else if (floor != null && judged.compareTo(floor) < 0) {
            verdict = "JOIN_BELOW_FLOOR";
        } else {
            verdict = "JOIN_KEEPS_FLOOR";
        }
        return new ItemEconomics(item, buyerNow, marginNow, atAction, atMax, atRecommended, atMaxBoost, breakEven,
                verdict, missing);
    }

    private static BigDecimal margin(BigDecimal price, ListingEconomicsInputs.Inputs inputs, BigDecimal floor) {
        if (price == null || price.signum() <= 0) {
            return null;
        }
        return ListingUnitEconomics.estimate(price, inputs.terms(), inputs.cost().unitCost().amount(), floor).margin();
    }
}
