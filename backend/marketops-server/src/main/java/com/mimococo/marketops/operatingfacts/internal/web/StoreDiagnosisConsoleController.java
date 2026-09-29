package com.mimococo.marketops.operatingfacts.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ResourceScope;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreDiagnosisRepository;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreDiagnosisRepository.Row;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreDiagnosisRepository.SearchTerm;
import com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc.StoreDiagnosisRepository.SearchWindow;
import com.mimococo.marketops.shared.ConsoleApi;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Why a store's listings do not sell: every listing with the newest signal of
 * each kind the marketplace has given about it.
 *
 * <p>The view states facts and their times; it scores nothing. Price
 * competitiveness and search demand are the marketplace's own analytics
 * (confidence C) and are shown as such: they explain, they never drive a price
 * change. Amounts and ratings travel as decimal text, exactly as stored.
 */
@RestController
@ConsoleApi
@RequestMapping("/api/v1/console/stores")
class StoreDiagnosisConsoleController {

    /** The marketplace's words for an unfavourable and a moderate price. */
    private static final String INDEX_RED = "RED";
    private static final String INDEX_YELLOW = "YELLOW";

    /** How many search terms each listing carries, the most searched first. */
    static final int TERMS_PER_PRODUCT = 5;

    private final StoreDiagnosisRepository diagnosis;
    private final BusinessAuthorization authorization;
    private final MetadataAuditRecorder audit;
    private final Clock clock;

    StoreDiagnosisConsoleController(StoreDiagnosisRepository diagnosis,
                                    BusinessAuthorization authorization,
                                    MetadataAuditRecorder audit,
                                    Clock clock) {
        this.diagnosis = diagnosis;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /** Every observed listing of the store with its newest signals, and the counts across them. */
    @GetMapping("/{storeId}/diagnosis")
    @Transactional
    StoreDiagnosis diagnosis(AuthenticatedActor actor, @PathVariable UUID storeId) {
        authorization.require(actor, ActionScopeCode.DIAGNOSTIC_VIEW, ResourceScope.store(storeId));
        SearchWindow searchWindow = diagnosis.searchWindow(actor.organizationId(), storeId).orElse(null);
        Map<UUID, List<Term>> terms = searchWindow == null ? Map.of()
                : diagnosis.searchTerms(actor.organizationId(), storeId, searchWindow, TERMS_PER_PRODUCT)
                        .stream()
                        .collect(Collectors.groupingBy(SearchTerm::variantId,
                                Collectors.mapping(term -> new Term(term.term(), term.searchUsers(),
                                        term.orderedCount()), Collectors.toList())));
        List<ProductDiagnosis> products = diagnosis.rows(actor.organizationId(), storeId, searchWindow).stream()
                .map(row -> product(row, terms.getOrDefault(row.variantId(), List.of())))
                .toList();
        OrdersWindow window = diagnosis.ordersWindow(actor.organizationId(), storeId)
                .map(found -> new OrdersWindow(found.from(), found.to(), found.daysCovered(),
                        StoreDiagnosisRepository.ORDER_DAYS))
                .orElse(null);
        audit.recordChange(new MetadataAuditChange(AuditSourceDomain.OPERATING_FACTS,
                actor.userId().toString(), AuditAction.READ, "store_diagnosis", storeId, null,
                Map.of(), "diagnosis", null));
        return new StoreDiagnosis(storeId, clock.instant(), summary(products), window,
                searchWindow == null ? null : new SearchPeriod(searchWindow.from(), searchWindow.to()),
                products);
    }

    private static ProductDiagnosis product(Row row, List<Term> terms) {
        Visibility visibility = row.healthAt() == null ? null
                : new Visibility(row.sellable(), row.nativeStatus(), row.blockedReasonNative(), row.healthAt());
        Stock stock = row.stockAt() == null ? null
                : new Stock(row.available(), row.reserved(), row.fulfillmentModes(), row.stockAt());
        Price price = row.priceAt() == null ? null
                : new Price(row.currencyCode(), text(row.listPrice()), text(row.sellingPrice()),
                        text(row.discountPrice()), row.priceIndexNative(),
                        text(row.platformCompetitorMinPrice()), row.platformCompetitorCurrencyCode(),
                        text(row.externalCompetitorMinPrice()), row.externalCompetitorCurrencyCode(),
                        premium(row),
                        new Tariffs(text(row.salesCommissionPercentFbs()), text(row.fbsLogisticsMax()),
                                text(row.acquiringMax()), text(row.vatRate())),
                        row.priceAt());
        Content content = row.contentAt() == null ? null
                : new Content(text(row.contentRating()), row.contentAt());
        Orders orders = row.orderedUnits() == null ? null
                : new Orders(row.orderedUnits(), row.daysWithRecords() == null ? 0 : row.daysWithRecords());
        Search search = row.searchUsers() == null && terms.isEmpty() ? null
                : new Search(row.searchUsers(), text(row.searchRevenue()),
                        row.searchRevenue() == null ? null : row.searchCurrencyCode(), terms);
        return new ProductDiagnosis(row.listingId(), row.variantId(), row.nativeListingKey(),
                row.nativeSkuKey(), row.title(), visibility, stock, price, content, search, orders);
    }

    /**
     * How far the buyer-facing price sits above the lowest competitor price on
     * the same marketplace, as a ratio (0.5 = 50 % more expensive).
     *
     * <p>The price with the seller's promotions is compared when there is one,
     * the seller's price otherwise, and only in the competitor's own currency.
     */
    private static String premium(Row row) {
        BigDecimal ours = row.discountPrice() != null ? row.discountPrice() : row.sellingPrice();
        BigDecimal theirs = row.platformCompetitorMinPrice();
        if (ours == null || theirs == null || theirs.signum() <= 0
                || !Objects.equals(row.currencyCode(), row.platformCompetitorCurrencyCode())) {
            return null;
        }
        return ours.divide(theirs, MathContext.DECIMAL64).subtract(BigDecimal.ONE)
                .setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static Summary summary(List<ProductDiagnosis> products) {
        int notSellable = 0;
        int sellabilityUnknown = 0;
        int withoutStock = 0;
        int red = 0;
        int yellow = 0;
        int withOrders = 0;
        int withSearchDemand = 0;
        int searchDemandWithoutOrders = 0;
        int rated = 0;
        BigDecimal ratingTotal = BigDecimal.ZERO;
        for (ProductDiagnosis product : products) {
            if (product.visibility() == null || "UNKNOWN".equals(product.visibility().sellable())) {
                sellabilityUnknown++;
            } else if ("NO".equals(product.visibility().sellable())) {
                notSellable++;
            }
            if (product.stock() != null && product.stock().available() != null
                    && product.stock().available() == 0) {
                withoutStock++;
            }
            if (product.price() != null && INDEX_RED.equals(product.price().indexNative())) {
                red++;
            } else if (product.price() != null && INDEX_YELLOW.equals(product.price().indexNative())) {
                yellow++;
            }
            boolean ordered = product.orders() != null && product.orders().orderedUnits() > 0;
            if (ordered) {
                withOrders++;
            }
            if (product.search() != null && product.search().searchUsers() != null
                    && product.search().searchUsers() > 0) {
                withSearchDemand++;
                if (!ordered) {
                    searchDemandWithoutOrders++;
                }
            }
            if (product.content() != null && product.content().rating() != null) {
                rated++;
                ratingTotal = ratingTotal.add(new BigDecimal(product.content().rating()));
            }
        }
        String averageRating = rated == 0 ? null
                : ratingTotal.divide(BigDecimal.valueOf(rated), 1, RoundingMode.HALF_UP).toPlainString();
        return new Summary(products.size(), notSellable, sellabilityUnknown, withoutStock, red, yellow,
                withOrders, withSearchDemand, searchDemandWithoutOrders, rated, averageRating);
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    /**
     * The whole view.
     *
     * @param ordersWindow the days the order sums cover, or {@code null} when the store has no traffic facts
     * @param searchPeriod the period search demand is reported for, or {@code null} when the store has no search facts
     */
    record StoreDiagnosis(UUID storeId, Instant generatedAt, Summary summary, OrdersWindow ordersWindow,
                          SearchPeriod searchPeriod, List<ProductDiagnosis> products) {
    }

    /**
     * Counts across the store's listings.
     *
     * @param sellabilityUnknown listings with no availability observed, or one the marketplace left open
     * @param withSearchDemand listings buyers searched for in the search period
     * @param searchDemandWithoutOrders of those, the ones with no ordered unit in the order window
     * @param averageContentRating mean over the rated listings, one decimal, or {@code null}
     */
    record Summary(int products, int notSellable, int sellabilityUnknown, int withoutStock,
                   int priceIndexRed, int priceIndexYellow, int withOrders, int withSearchDemand,
                   int searchDemandWithoutOrders, int rated, String averageContentRating) {
    }

    /** The order window: {@code days} ending at the store's latest traffic day. */
    record OrdersWindow(Instant from, Instant to, int daysCovered, int days) {
    }

    /** The search period: inclusive start, exclusive end. */
    record SearchPeriod(Instant from, Instant to) {
    }

    /** One listing variant and its newest signals; an unobserved signal is {@code null}. */
    record ProductDiagnosis(UUID listingId, UUID variantId, String nativeListingKey, String nativeSkuKey,
                            String title, Visibility visibility, Stock stock, Price price,
                            Content content, Search search, Orders orders) {
    }

    /** Whether a buyer can see and buy it: {@code YES}, {@code NO} or {@code UNKNOWN}. */
    record Visibility(String sellable, String nativeStatus, String blockedReason, Instant observedAt) {
    }

    /** Units summed over the fulfillment modes of the newest stock snapshot. */
    record Stock(Long available, Long reserved, List<String> fulfillmentModes, Instant observedAt) {
    }

    /** The newest price and the marketplace's view of its competitiveness (confidence C). */
    record Price(String currencyCode, String listPrice, String sellingPrice, String discountPrice,
                 String indexNative, String platformCompetitorMinPrice,
                 String platformCompetitorCurrencyCode, String externalCompetitorMinPrice,
                 String externalCompetitorCurrencyCode, String premiumOverPlatformCompetitor,
                 Tariffs tariffs, Instant observedAt) {
    }

    /**
     * The tariffs the marketplace stated with the price, in its currency; each
     * {@code null} when not stated.
     *
     * @param salesCommissionPercentFbs the FBS sales commission in percent
     * @param fbsLogisticsMax the highest stated FBS processing, trunk and last-mile tariffs summed
     * @param vatRate the VAT rate contained in the price (0.05 = 5 %)
     */
    record Tariffs(String salesCommissionPercentFbs, String fbsLogisticsMax, String acquiringMax,
                   String vatRate) {
    }

    /** The marketplace's content rating, 0 to 100. */
    record Content(String rating, Instant observedAt) {
    }

    /**
     * Buyers who searched for the listing in the search period (confidence C),
     * and the terms they used, the most searched first.
     *
     * @param searchUsers {@code null} when only terms were observed
     * @param revenue sales the marketplace attributes to searches, or {@code null}
     */
    record Search(Long searchUsers, String revenue, String revenueCurrencyCode, List<Term> terms) {
    }

    /** One search term; {@code orderedCount} is {@code null} when the marketplace did not state it. */
    record Term(String term, long searchUsers, Long orderedCount) {
    }

    /** Units ordered on the days of the window this listing has a record for. */
    record Orders(long orderedUnits, long daysWithRecords) {
    }
}
