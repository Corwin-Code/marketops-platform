package com.mimococo.marketops.aicopilot.internal.application;

import static com.mimococo.marketops.aicopilot.internal.application.ProjectionBuilder.field;

import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.PromotionEconomicsQuery;
import com.mimococo.marketops.analyticsdecision.PromotionEconomicsQuery.ItemEconomics;
import com.mimococo.marketops.analyticsdecision.PromotionEconomicsQuery.PromotionEconomics;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery.ListingResult;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery.StoreRun;
import com.mimococo.marketops.operatingfacts.PromotionSnapshot;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.productlisting.ListingIdentityDirectory;
import com.mimococo.marketops.productlisting.SubjectIdentity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds what a model is allowed to see about a store's current promotions: each promotion's
 * terms, how many of its products keep the margin floor, fall below it or lose money at its highest
 * price, and the products that matter most, each with the promotion's prices and the estimated
 * margins at them beside its search demand and orders over the newest calculation window.
 *
 * <p>The margins are the official estimates of {@link PromotionEconomicsQuery}. Only ratios leave:
 * never a cost, a profit or a break-even amount. Prices are the marketplace's own. A fact can cite
 * only the listing values of the newest calculation; the promotion's figures have no identifier.
 */
@Component
class PromotionProjectionBuilder {

    static final String PROJECTION_CODE = "PROMOTION_REVIEW";
    static final int PROJECTION_VERSION = 1;

    /** A long projection makes a model repeat itself instead of answering: few promotions, few products. */
    private static final int PROMOTIONS_SHOWN = 3;
    private static final int ITEMS_PER_PROMOTION = 5;

    /** The listing values shown per product: its demand, which a fact may cite. */
    private static final List<MetricCode> SHOWN = List.of(MetricCode.SEARCH_USERS, MetricCode.ORDERED_UNITS);

    /** The competitor price the highest promotion price is compared with. */
    private static final MetricCode COMPETITOR = MetricCode.PLATFORM_COMPETITOR_MIN_PRICE;

    /** The verdicts in the order they are counted. */
    private static final List<String> VERDICTS = List.of("JOIN_KEEPS_FLOOR", "JOIN_BELOW_FLOOR", "JOIN_LOSES",
            "UNKNOWN");

    private final PromotionEconomicsQuery promotions;
    private final StoreFindingsQuery findings;
    private final ListingIdentityDirectory listings;
    private final OrganizationDirectory organizations;
    private final AiRepository repository;

    PromotionProjectionBuilder(PromotionEconomicsQuery promotions, StoreFindingsQuery findings,
                               ListingIdentityDirectory listings, OrganizationDirectory organizations,
                               AiRepository repository) {
        this.promotions = promotions;
        this.findings = findings;
        this.listings = listings;
        this.organizations = organizations;
        this.repository = repository;
    }

    /** Assemble the projection of a store's promotions; empty when no promotion names a product. */
    SubjectProjection build(UUID organizationId, UUID storeId, MetricWindow window, Instant asOf) {
        List<PromotionEconomics> withItems = promotions.forStore(organizationId, storeId, asOf).stream()
                .filter(promotion -> !promotion.items().isEmpty())
                .toList();
        if (withItems.isEmpty()) {
            return SubjectProjection.empty();
        }
        Set<MetricCode> read = EnumSet.copyOf(SHOWN);
        read.add(COMPETITOR);
        StoreRun run = findings.latest(organizationId, storeId, window, read).orElse(null);
        Map<UUID, Map<MetricCode, ProjectionEgress.Value>> values = new HashMap<>();
        if (run != null) {
            for (ListingResult listing : run.listings()) {
                Map<MetricCode, ProjectionEgress.Value> own = new EnumMap<>(MetricCode.class);
                listing.metrics().forEach((code, value) -> own.put(code, new ProjectionEgress.Value(value.valueId(),
                        code, value.valueState(), value.numericValue(), value.currencyCode())));
                values.put(listing.listingVariantId(), own);
            }
        }

        List<SubjectProjection.Field> fields = new ArrayList<>();
        Set<UUID> metricValueIds = new LinkedHashSet<>();
        BigDecimal floor = withItems.getFirst().minimumMarginRate();
        fields.add(field("store.storeRef", storeId.toString()));
        fields.add(field("store.platformCode", platformCode(storeId)));
        fields.add(field("store.currencyCode", withItems.stream()
                .flatMap(promotion -> promotion.items().stream())
                .map(item -> item.item().currencyCode())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("")));
        fields.add(field("store.minimumMargin", floor == null ? "" : ProjectionEgress.percent(floor, false)));
        fields.add(field("store.promotionCount", Integer.toString(withItems.size())));
        if (run != null) {
            fields.add(field("window.windowCode", window.name()));
            fields.add(field("window.periodStart", run.periodStart().toString()));
            fields.add(field("window.periodEnd", run.periodEnd().toString()));
        }

        List<PromotionEconomics> shown = withItems.stream()
                .sorted(Comparator.comparingInt((PromotionEconomics promotion) ->
                                Boolean.TRUE.equals(promotion.promotion().participating()) ? 0 : 1)
                        .thenComparing(promotion -> -promotion.items().size())
                        .thenComparing(promotion -> promotion.promotion().endsAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(promotion -> promotion.promotion().promotionId().toString()))
                .limit(PROMOTIONS_SHOWN)
                .toList();
        List<UUID> shownVariants = shown.stream()
                .flatMap(promotion -> ranked(promotion, values).stream())
                .map(item -> item.item().listingVariantId())
                .distinct()
                .toList();
        Map<UUID, SubjectIdentity> identities = listings.identities(organizationId, shownVariants);

        for (PromotionEconomics economics : shown) {
            PromotionSnapshot promotion = economics.promotion();
            String promotionRef = promotion.promotionId().toString();
            fields.add(field("promotions.promotionRef", promotionRef));
            addText(fields, "promotions.title", promotion.title());
            addText(fields, "promotions.kind", promotion.promotionKind());
            addDate(fields, "promotions.startsOn", promotion.startsAt());
            addDate(fields, "promotions.endsOn", promotion.endsAt());
            addDate(fields, "promotions.freezesOn", promotion.freezesAt());
            if (promotion.participating() != null) {
                fields.add(field("promotions.participating", promotion.participating() ? "YES" : "NO"));
            }
            addText(fields, "promotions.discount", discount(promotion));
            fields.add(field("promotions.productCount", Integer.toString(economics.items().size())));
            fields.add(field("promotions.keepsFloorCount", count(economics, "JOIN_KEEPS_FLOOR")));
            fields.add(field("promotions.belowFloorCount", count(economics, "JOIN_BELOW_FLOOR")));
            fields.add(field("promotions.losesCount", count(economics, "JOIN_LOSES")));
            fields.add(field("promotions.unknownCount", count(economics, "UNKNOWN")));

            for (ItemEconomics item : ranked(economics, values)) {
                PromotionSnapshot.Item terms = item.item();
                UUID variant = terms.listingVariantId();
                fields.add(field("items.listingRef", variant.toString()));
                fields.add(field("items.promotionRef", promotionRef));
                SubjectIdentity identity = identities.get(variant);
                if (identity != null) {
                    addText(fields, "items.title", identity.productName());
                    addText(fields, "items.size", identity.sizeLabel());
                    addText(fields, "items.color", identity.colorLabel());
                }
                fields.add(field("items.membership", terms.membership()));
                addText(fields, "items.addMode", terms.addMode());
                addText(fields, "items.priceNow", money(item.buyerPriceNow(), terms.currencyCode()));
                addText(fields, "items.marginNow", ratio(item.marginNow()));
                if ("PARTICIPANT".equals(terms.membership())) {
                    addText(fields, "items.actionPrice", money(terms.actionPrice(), terms.currencyCode()));
                    addText(fields, "items.marginAtActionPrice", ratio(item.marginAtActionPrice()));
                }
                addText(fields, "items.maxActionPrice", money(terms.maxActionPrice(), terms.currencyCode()));
                addText(fields, "items.marginAtMaxActionPrice", ratio(item.marginAtMaxActionPrice()));
                addText(fields, "items.recommendedActionPrice",
                        money(terms.recommendedActionPrice(), terms.currencyCode()));
                addText(fields, "items.marginAtRecommendedPrice", ratio(item.marginAtRecommendedPrice()));
                fields.add(field("items.verdict", item.verdict()));
                item.missing().forEach(code -> fields.add(field("items.missingInput", code)));
                Map<MetricCode, ProjectionEgress.Value> own = values.getOrDefault(variant, Map.of());
                // The price the verdict is judged at: a participant's own action price, which the
                // highest allowed price can exceed, or a candidate's highest allowed price.
                BigDecimal judgedPrice = "PARTICIPANT".equals(terms.membership()) && terms.actionPrice() != null
                        ? terms.actionPrice() : terms.maxActionPrice();
                addText(fields, "items.promotionPriceVsCompetitor",
                        versusCompetitor(judgedPrice, terms.currencyCode(), own.get(COMPETITOR)));
                for (MetricCode code : SHOWN) {
                    ProjectionEgress.Value value = own.get(code);
                    if (value == null || !value.available()) {
                        continue;
                    }
                    metricValueIds.add(value.valueId());
                    fields.add(field("items.metricCode", code.name()));
                    fields.add(field("items.displayValue", ProjectionEgress.display(value)));
                    fields.add(field("items.valueRef", value.valueId().toString()));
                }
            }
        }

        SubjectProjection projection = new SubjectProjection(fields, metricValueIds, Set.of());
        ProjectionBuilder.enforceAllowlist(projection, PROJECTION_CODE, PROJECTION_VERSION, repository);
        return projection;
    }

    /**
     * The products shown for a promotion: first a participant that loses money or falls below the
     * floor, which has to leave before the freeze; then by search demand, then by verdict.
     */
    private static List<ItemEconomics> ranked(PromotionEconomics promotion,
                                              Map<UUID, Map<MetricCode, ProjectionEgress.Value>> values) {
        return promotion.items().stream()
                .sorted(Comparator.comparingInt(PromotionProjectionBuilder::urgency)
                        .thenComparing(item -> searchUsers(values.get(item.item().listingVariantId())),
                                Comparator.reverseOrder())
                        .thenComparingInt(item -> VERDICTS.indexOf(item.verdict()))
                        .thenComparing(item -> item.item().listingVariantId().toString()))
                .limit(ITEMS_PER_PROMOTION)
                .toList();
    }

    /** How many of a promotion's products reached a verdict; the count is complete, the list is not. */
    private static String count(PromotionEconomics promotion, String verdict) {
        return Long.toString(promotion.items().stream().filter(item -> verdict.equals(item.verdict())).count());
    }

    private static int urgency(ItemEconomics item) {
        boolean participant = "PARTICIPANT".equals(item.item().membership());
        boolean costly = "JOIN_LOSES".equals(item.verdict()) || "JOIN_BELOW_FLOOR".equals(item.verdict());
        return participant && costly ? 0 : 1;
    }

    private static BigDecimal searchUsers(Map<MetricCode, ProjectionEgress.Value> own) {
        ProjectionEgress.Value value = own == null ? null : own.get(MetricCode.SEARCH_USERS);
        return value != null && value.available() ? value.number() : BigDecimal.valueOf(-1);
    }

    /** A promotion price against the lowest competitor price, signed; empty when not comparable. */
    private static String versusCompetitor(BigDecimal price, String currencyCode, ProjectionEgress.Value competitor) {
        if (price == null || competitor == null || !competitor.available() || competitor.number().signum() <= 0
                || !Objects.equals(currencyCode, competitor.currencyCode())) {
            return "";
        }
        BigDecimal ratio = price.divide(competitor.number(), 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
        return ProjectionEgress.percent(ratio, true);
    }

    /** The discount as the marketplace stated it: a percentage, or an amount in the store currency. */
    private static String discount(PromotionSnapshot promotion) {
        if (promotion.discountValue() == null) {
            return "";
        }
        String value = ProjectionEgress.plain(promotion.discountValue());
        return "PERCENT".equals(promotion.discountKind()) ? value + "%" : value;
    }

    private static String ratio(BigDecimal ratio) {
        return ratio == null ? "" : ProjectionEgress.percent(ratio, false);
    }

    private static String money(BigDecimal amount, String currencyCode) {
        if (amount == null) {
            return "";
        }
        String text = ProjectionEgress.plain(amount.setScale(2, RoundingMode.HALF_UP));
        return currencyCode == null || currencyCode.isBlank() ? text : text + " " + currencyCode;
    }

    private String platformCode(UUID storeId) {
        return organizations.store(storeId)
                .flatMap(store -> organizations.marketplaceAccount(store.marketplaceAccountId()))
                .map(account -> account.platformCode())
                .orElse("");
    }

    /** A date as the other projections write one: the UTC calendar day. */
    private static void addDate(List<SubjectProjection.Field> fields, String path, Instant at) {
        if (at != null) {
            fields.add(field(path, LocalDate.ofInstant(at, ZoneOffset.UTC).toString()));
        }
    }

    private static void addText(List<SubjectProjection.Field> fields, String path, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(field(path, value.strip()));
        }
    }
}
