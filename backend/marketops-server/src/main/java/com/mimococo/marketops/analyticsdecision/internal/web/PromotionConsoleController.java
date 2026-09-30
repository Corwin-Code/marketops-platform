package com.mimococo.marketops.analyticsdecision.internal.web;

import com.mimococo.marketops.analyticsdecision.PromotionEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.PromotionEconomicsQuery.ItemEconomics;
import com.mimococo.marketops.analyticsdecision.PromotionEconomicsQuery.PromotionEconomics;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operatingfacts.PromotionSnapshot;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.SubjectIdentity;
import com.mimococo.marketops.shared.ConsoleApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A store's current promotions and what joining each would mean for each product: the prices the
 * promotion allows and recommends, the boost it offers, and the estimated unit margin at those
 * prices beside today's, judged against the Owner's margin floor. Amounts and ratios travel as
 * decimal text, exactly as computed.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/diagnosis")
class PromotionConsoleController {

    private final PromotionEconomicsQuery promotions;
    private final ListingIdentityDirectory listings;
    private final BusinessAuthorization authorization;
    private final Clock clock;

    PromotionConsoleController(PromotionEconomicsQuery promotions, ListingIdentityDirectory listings,
                               BusinessAuthorization authorization, Clock clock) {
        this.promotions = promotions;
        this.listings = listings;
        this.authorization = authorization;
        this.clock = clock;
    }

    /** Every current promotion of the store, with the economics of each of its products. */
    @GetMapping(value = "/stores/{storeId}/promotions", produces = MediaType.APPLICATION_JSON_VALUE)
    StorePromotions storePromotions(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        Instant now = clock.instant();
        List<PromotionEconomics> found = promotions.forStore(actor.organizationId(), storeId, now);
        Map<UUID, SubjectIdentity> identities = listings.identities(actor.organizationId(), found.stream()
                .flatMap(promotion -> promotion.items().stream())
                .map(item -> item.item().listingVariantId())
                .distinct()
                .toList());
        BigDecimal floor = found.isEmpty() ? null : found.getFirst().minimumMarginRate();
        return new StorePromotions(storeId, now, text(floor), found.stream()
                .map(promotion -> promotion(promotion, identities))
                .toList());
    }

    private static Promotion promotion(PromotionEconomics economics, Map<UUID, SubjectIdentity> identities) {
        PromotionSnapshot promotion = economics.promotion();
        return new Promotion(promotion.promotionId(), promotion.nativePromotionKey(), promotion.observedAt(),
                promotion.title(), promotion.promotionKind(), promotion.description(), promotion.startsAt(),
                promotion.endsAt(), promotion.freezesAt(), promotion.candidateCount(), promotion.participantCount(),
                promotion.bannedCount(), promotion.participating(), promotion.voucher(), promotion.targeted(),
                promotion.discountKind(), text(promotion.discountValue()),
                economics.items().stream().map(item -> item(item, identities.get(item.item().listingVariantId())))
                        .toList());
    }

    private static Item item(ItemEconomics economics, SubjectIdentity identity) {
        PromotionSnapshot.Item item = economics.item();
        return new Item(item.listingVariantId(), identity == null ? null : identity.productName(),
                identity == null ? null : identity.platformSkuKey(), identity == null ? null : identity.sizeLabel(),
                identity == null ? null : identity.colorLabel(), item.membership(), item.observedAt(),
                item.currencyCode(), text(item.price()), text(item.actionPrice()), text(item.maxActionPrice()),
                text(item.recommendedActionPrice()), item.aboveRecommended(), text(item.currentBoost()),
                text(item.minBoost()), text(item.maxBoost()), text(item.priceForMinBoost()),
                text(item.priceForMaxBoost()), item.minStock(), item.recommendedStock(), item.stock(),
                item.addMode(), item.quarantined(), text(economics.buyerPriceNow()), text(economics.marginNow()),
                text(economics.marginAtMaxActionPrice()), text(economics.marginAtRecommendedPrice()),
                text(economics.marginAtMaxBoostPrice()), text(economics.breakEvenPrice()), economics.verdict(),
                economics.missing());
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * The store's current promotions.
     *
     * @param minimumMarginRate the margin floor the verdicts compare with, as a ratio, or {@code null}
     */
    record StorePromotions(UUID storeId, Instant generatedAt, String minimumMarginRate, List<Promotion> promotions) {
    }

    /** One promotion as the newest snapshot described it. */
    record Promotion(UUID promotionId, String nativePromotionKey, Instant observedAt, String title,
                     String promotionKind, String description, Instant startsAt, Instant endsAt, Instant freezesAt,
                     Integer candidateCount, Integer participantCount, Integer bannedCount, Boolean participating,
                     Boolean voucher, Boolean targeted, String discountKind, String discountValue, List<Item> items) {
    }

    /**
     * One product of a promotion with its economics; margins are ratios as decimal text.
     *
     * @param offerId the seller's article of the listing, when known
     */
    record Item(UUID listingVariantId, String title, String offerId, String size, String color, String membership,
                Instant observedAt, String currencyCode, String price, String actionPrice, String maxActionPrice,
                String recommendedActionPrice, Boolean aboveRecommended, String currentBoost, String minBoost,
                String maxBoost, String priceForMinBoost, String priceForMaxBoost, Integer minStock,
                Integer recommendedStock, Integer stock, String addMode, Boolean quarantined, String buyerPriceNow,
                String marginNow, String marginAtMaxActionPrice, String marginAtRecommendedPrice,
                String marginAtMaxBoostPrice, String breakEvenPrice, String verdict, List<String> missing) {
    }
}
