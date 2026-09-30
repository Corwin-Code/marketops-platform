package com.mimococo.marketops.analyticsdecision.internal.application;

import com.mimococo.marketops.analyticsdecision.ListingUnitEconomics;
import com.mimococo.marketops.analyticsdecision.PriceEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.PromotionEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.internal.config.AnalyticsProperties;
import com.mimococo.marketops.operatingfacts.CostSnapshot;
import com.mimococo.marketops.operatingfacts.ListingPriceTerms;
import com.mimococo.marketops.operatingfacts.OperatingFactQuery;
import com.mimococo.marketops.operatingfacts.PromotionSnapshot;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final ListingIdentityDirectory listings;
    private final PriceEconomicsQuery economics;
    private final AnalyticsProperties properties;

    PromotionEconomicsService(OperatingFactQuery facts, ListingIdentityDirectory listings,
                              PriceEconomicsQuery economics, AnalyticsProperties properties) {
        this.facts = facts;
        this.listings = listings;
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
        java.util.Map<UUID, Inputs> inputs = new java.util.HashMap<>();
        List<PromotionEconomics> result = new ArrayList<>();
        for (PromotionSnapshot promotion : promotions) {
            List<ItemEconomics> items = promotion.items().stream()
                    .map(item -> economics(item, inputs.computeIfAbsent(item.listingVariantId(),
                            variant -> inputs(variant, modes, asOf)), floor))
                    .toList();
            result.add(new PromotionEconomics(promotion, items, floor));
        }
        return result;
    }

    /** The terms and cost of one listing, read once however many promotions name it. */
    private Inputs inputs(UUID listingVariantId, List<String> modes, Instant asOf) {
        List<String> missing = new ArrayList<>();
        Optional<ListingPriceTerms> stated = facts.latestPriceTerms(listingVariantId, asOf);
        Optional<ListingUnitEconomics.Scheme> scheme = MetricEngine.scheme(modes,
                facts.latestStock(listingVariantId, asOf));
        Optional<ListingUnitEconomics.Terms> terms = stated.isEmpty() || scheme.isEmpty() ? Optional.empty()
                : ListingUnitEconomics.terms(stated.get(), scheme.get());
        if (scheme.isEmpty()) {
            missing.add("FULFILLMENT_SCHEME");
        }
        if (terms.isEmpty()) {
            missing.add("MARKETPLACE_TARIFFS");
        }
        Optional<CostSnapshot> cost = listings.variantContext(listingVariantId, asOf)
                .filter(context -> context.mapped() && !context.conflictOpen())
                .flatMap(context -> facts.unitCost(context.productVariantId(), asOf))
                .filter(snapshot -> snapshot.costVersionId() != null && snapshot.unitCost() != null
                        && snapshot.unitCost().amount().signum() >= 0 && snapshot.effectiveFrom() != null
                        && snapshot.effectiveFrom().isBefore(asOf));
        if (cost.isEmpty()) {
            missing.add("UNIT_COST");
        }
        return new Inputs(stated.orElse(null), terms.orElse(null), cost.orElse(null), missing);
    }

    private static ItemEconomics economics(PromotionSnapshot.Item item, Inputs inputs, BigDecimal floor) {
        List<String> missing = new ArrayList<>(inputs.missing());
        String currency = item.currencyCode();
        if (inputs.cost() != null && currency != null
                && !inputs.cost().unitCost().currencyCode().equals(currency)) {
            missing.add("UNIT_COST_CURRENCY");
        }
        if (item.maxActionPrice() == null) {
            missing.add("MAX_ACTION_PRICE");
        }
        boolean computable = inputs.terms() != null && inputs.cost() != null && !missing.contains("UNIT_COST_CURRENCY");
        BigDecimal buyerNow = inputs.stated() == null ? null : inputs.stated().buyerPrice();
        BigDecimal marginNow = computable ? margin(buyerNow, inputs, floor) : null;
        BigDecimal atMax = computable ? margin(item.maxActionPrice(), inputs, floor) : null;
        BigDecimal atRecommended = computable ? margin(item.recommendedActionPrice(), inputs, floor) : null;
        BigDecimal atMaxBoost = computable ? margin(item.priceForMaxBoost(), inputs, floor) : null;
        BigDecimal breakEven = computable && item.maxActionPrice() != null
                ? ListingUnitEconomics.estimate(item.maxActionPrice(), inputs.terms(),
                        inputs.cost().unitCost().amount(), floor).breakEvenPrice()
                : null;
        String verdict;
        if (atMax == null) {
            verdict = "UNKNOWN";
        } else if (atMax.signum() < 0) {
            verdict = "JOIN_LOSES";
        } else if (floor != null && atMax.compareTo(floor) < 0) {
            verdict = "JOIN_BELOW_FLOOR";
        } else {
            verdict = "JOIN_KEEPS_FLOOR";
        }
        return new ItemEconomics(item, buyerNow, marginNow, atMax, atRecommended, atMaxBoost, breakEven, verdict,
                missing);
    }

    private static BigDecimal margin(BigDecimal price, Inputs inputs, BigDecimal floor) {
        if (price == null || price.signum() <= 0) {
            return null;
        }
        return ListingUnitEconomics.estimate(price, inputs.terms(), inputs.cost().unitCost().amount(), floor).margin();
    }

    /** What one listing's economics are computed from; {@code missing} names what was not there. */
    private record Inputs(ListingPriceTerms stated, ListingUnitEconomics.Terms terms, CostSnapshot cost,
                          List<String> missing) {
    }
}
