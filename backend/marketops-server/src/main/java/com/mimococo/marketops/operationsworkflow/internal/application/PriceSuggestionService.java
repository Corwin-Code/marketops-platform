package com.mimococo.marketops.operationsworkflow.internal.application;

import static com.mimococo.marketops.analyticsdecision.MetricCode.OBSERVED_SELLING_PRICE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.ORDERED_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.PLATFORM_AVAILABLE_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.PLATFORM_COMPETITOR_MIN_PRICE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.PROJECTED_UNIT_MARGIN;
import static com.mimococo.marketops.analyticsdecision.MetricCode.SEARCH_USERS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.TARGET_MARGIN_PRICE;

import com.mimococo.marketops.analyticsdecision.ListingPriceEstimateQuery;
import com.mimococo.marketops.analyticsdecision.ListingUnitEconomics;
import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.MetricWindow;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery;
import com.mimococo.marketops.analyticsdecision.StoreFindingsQuery.ListingResult;
import com.mimococo.marketops.analyticsdecision.ValueState;
import com.mimococo.marketops.operationsworkflow.ActionKind;
import com.mimococo.marketops.operationsworkflow.GuardrailPurpose;
import com.mimococo.marketops.operationsworkflow.RecommendationState;
import com.mimococo.marketops.operationsworkflow.RecommendationView;
import com.mimococo.marketops.operationsworkflow.internal.config.PriceSuggestionProperties;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.PriceDecisionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Price suggestions from the store's newest seven-day findings (P8).
 *
 * <p>Three rules lead to a PRICE_CHANGE proposal, chosen in this order: PRICE_GAP_REDUCIBLE (the buyer
 * price can come down to the lowest Ozon competitor price and keep the minimum unit margin),
 * PRICE_GAP_PARTIAL (matching the competitor keeps a profit but not the minimum margin, so the
 * suggestion stops at the target margin price) and PRICE_HEADROOM (buyers look and nobody orders, and
 * the price can come down before the margin reaches the minimum). A listing with a structural price
 * gap, no stock or no sellable status gets none. A listing whose buyer price has just jumped
 * (BUYER_PRICE_JUMP) waits: the searches and the missing orders a suggestion rests on were observed
 * at a much lower price, so a step down from the new one has no ground; its undecided suggestion is
 * withdrawn as paused, and a new one comes once the price has held for the rule's lookback.
 *
 * <p>The price comes from the deterministic layer alone: never below the target margin price (rounded
 * up to a whole unit, so the margin really holds), never more than one configured step below today's
 * buyer price (rounded up, so the step really holds), and for a reducible gap never below the
 * competitor price. That price is grade C platform analytics: it bounds a candidate a person reviews
 * and never authorizes a change (HR-07). Prices are buyer prices, promotions included.
 *
 * <p>Every pass works from one calculation and reads the economics as they stood at its end, so a
 * suggestion's margin agrees with the findings it rests on. A suggestion of this service nobody has
 * decided on yet is withdrawn when the newest calculation no longer calls for it, and replaced when it
 * calls for another price. After a person recorded a decision about a listing, nothing new is suggested
 * for it until the effect has had the validation horizon to show.
 *
 * <p>A proposal enters the review with its guardrails evaluated for preview. On the pilot the chain
 * cannot pass yet (Owner decision 2026-10-01: its prerequisites come before writes are enabled), and
 * the recorded verdict says why; a person who agrees changes the price in the back office and records
 * that ({@link PriceDecisionService}).
 */
@Service
public class PriceSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(PriceSuggestionService.class);

    /** Who the proposals and their transitions are recorded as. */
    static final String OPERATOR = "price-suggestions";

    /** The rules that lead to a suggestion, in the order one is chosen. */
    private static final List<String> CAUSES = List.of("PRICE_GAP_REDUCIBLE", "PRICE_GAP_PARTIAL", "PRICE_HEADROOM");

    /** Conclusions under which no price change is suggested. */
    private static final Set<String> BLOCKERS = Set.of("PRICE_GAP_STRUCTURAL", "STOCKOUT_RISK", "LISTING_NOT_SELLABLE");

    /** The conclusion under which a suggestion waits for the buyer price to settle. */
    private static final String PAUSE = "BUYER_PRICE_JUMP";

    /** Why an undecided suggestion was withdrawn while the buyer price settles. */
    static final String PAUSED_REASON = "PAUSED_BY_BUYER_PRICE_JUMP";

    /** The values a suggestion reads and cites. */
    private static final Set<MetricCode> READ = EnumSet.of(OBSERVED_SELLING_PRICE, TARGET_MARGIN_PRICE,
            PROJECTED_UNIT_MARGIN, PLATFORM_COMPETITOR_MIN_PRICE, SEARCH_USERS, ORDERED_UNITS, PLATFORM_AVAILABLE_UNITS);

    /** The values cited as supporting evidence, when available. */
    private static final List<MetricCode> SUPPORTING = List.of(OBSERVED_SELLING_PRICE, TARGET_MARGIN_PRICE,
            PROJECTED_UNIT_MARGIN, SEARCH_USERS, ORDERED_UNITS, PLATFORM_AVAILABLE_UNITS, PLATFORM_COMPETITOR_MIN_PRICE);

    /** Searchers per point of priority: the queue puts the most searched listing first. */
    private static final BigDecimal SEARCHERS_PER_POINT = BigDecimal.valueOf(50);
    private static final BigDecimal MAXIMUM_PRIORITY = BigDecimal.valueOf(1000);

    /** Price changes up to this share are low risk; up to the next, medium. */
    private static final BigDecimal LOW_RISK_CHANGE = new BigDecimal("0.05");
    private static final BigDecimal MEDIUM_RISK_CHANGE = new BigDecimal("0.10");

    private final StoreFindingsQuery findings;
    private final ListingPriceEstimateQuery estimates;
    private final RecommendationService recommendations;
    private final PriceDecisionRepository decisions;
    private final GuardrailService guardrails;
    private final PriceSuggestionProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PriceSuggestionService(StoreFindingsQuery findings, ListingPriceEstimateQuery estimates,
                           RecommendationService recommendations, PriceDecisionRepository decisions,
                           GuardrailService guardrails, PriceSuggestionProperties properties,
                           PlatformTransactionManager transactionManager, Clock clock) {
        this.findings = findings;
        this.estimates = estimates;
        this.recommendations = recommendations;
        this.decisions = decisions;
        this.guardrails = guardrails;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Bring the store's price suggestions in line with its newest seven-day findings: propose where they
     * call for a price and nothing stands, replace an undecided suggestion of this service that calls
     * for another price, and withdraw one they no longer call for. Elapsed proposals expire first, so a
     * stale one blocks nothing. Each listing is its own transaction: one that fails holds back no other.
     */
    public Result generate(UUID organizationId, UUID storeId) {
        int expired = recommendations.expireElapsed();
        Optional<StoreFindingsQuery.StoreRun> run = findings.latest(organizationId, storeId, MetricWindow.D7, READ);
        if (run.isEmpty()) {
            return new Result(null, expired, 0, 0, 0, 0, 0, 0, 0);
        }
        Instant asOf = run.get().periodEnd();
        Instant now = clock.instant();
        int proposed = 0;
        int refreshed = 0;
        int withdrawn = 0;
        int standing = 0;
        int cooling = 0;
        int paused = 0;
        int failed = 0;
        for (ListingResult listing : run.get().listings()) {
            UUID variantId = listing.listingVariantId();
            boolean jumped = listing.findings().stream().anyMatch(finding -> PAUSE.equals(finding.ruleCode()));
            Optional<Suggestion> called = suggest(storeId, listing, asOf);
            Optional<Suggestion> suggestion = jumped ? Optional.empty() : called;
            paused += jumped && called.isPresent() ? 1 : 0;
            Optional<RecommendationView> live = recommendations.live(variantId, ActionKind.PRICE_CHANGE);
            if (live.isPresent() && (!ours(live.get())
                    || (suggestion.isPresent() && same(live.get(), suggestion.get())
                            && recommendations.restsOnCurrentValues(live.get())))) {
                standing++;
                continue;
            }
            if (live.isEmpty() && (suggestion.isEmpty() || coolingDown(organizationId, variantId, now))) {
                cooling += suggestion.isPresent() ? 1 : 0;
                continue;
            }
            try {
                transactions.executeWithoutResult(status -> {
                    live.ifPresent(outdated -> recommendations.transition(OPERATOR, outdated.id(),
                            RecommendationState.CANCELLED,
                            suggestion.isPresent() ? "SUPERSEDED_BY_NEWER_CALCULATION"
                                    : jumped ? PAUSED_REASON : "NO_LONGER_SUGGESTED",
                            outdated.version()));
                    suggestion.ifPresent(next -> propose(organizationId, storeId, run.get().runId(), variantId, next));
                });
                if (live.isEmpty()) {
                    proposed++;
                } else if (suggestion.isPresent()) {
                    refreshed++;
                } else {
                    withdrawn++;
                }
            } catch (RuntimeException refused) {
                failed++;
                log.atWarn().addKeyValue("event", "price_suggestion_failed")
                        .addKeyValue("listingVariantId", variantId)
                        .addKeyValue("failureType", refused.getClass().getSimpleName())
                        .log("A price suggestion could not be proposed or withdrawn");
            }
        }
        return new Result(run.get().runId(), expired, proposed, refreshed, withdrawn, standing, cooling, paused,
                failed);
    }

    /** Whether a live price proposal is one of this service's that nobody has decided on yet. */
    private static boolean ours(RecommendationView live) {
        return "DETERMINISTIC".equals(live.origin()) && live.expectedEffect().containsKey("basis")
                && (live.state() == RecommendationState.DRAFT || live.state() == RecommendationState.VALIDATED
                        || live.state() == RecommendationState.READY_FOR_REVIEW);
    }

    /**
     * Whether a standing suggestion already says what the newest findings say: the same price and every
     * figure behind it, so a reviewer never reads a margin or a demand the calculation has moved past.
     * It must also rest on the current values (checked by the caller): the guardrail refuses a proposal
     * whose facts changed since, so one with the same figures but newer facts is replaced too.
     */
    private static boolean same(RecommendationView live, Suggestion suggestion) {
        String target = live.proposedParameters().get("targetPrice");
        return target != null && new BigDecimal(target).compareTo(suggestion.targetPrice()) == 0
                && live.expectedEffect().equals(suggestion.expectedEffect());
    }

    /** Whether a decision about the listing is younger than the validation horizon. */
    private boolean coolingDown(UUID organizationId, UUID listingVariantId, Instant now) {
        return decisions.latestDecidedAt(organizationId, listingVariantId)
                .map(decided -> decided.isAfter(now.minus(java.time.Duration.ofDays(
                        properties.getValidationHorizonDays()))))
                .orElse(false);
    }

    private void propose(UUID organizationId, UUID storeId, UUID runId, UUID listingVariantId, Suggestion suggestion) {
        UUID id = recommendations.propose(OPERATOR, organizationId, storeId, listingVariantId, ActionKind.PRICE_CHANGE,
                "DETERMINISTIC", null, runId, MetricWindow.D7, suggestion.priority(),
                Map.of("targetPrice", suggestion.targetPrice().toPlainString()), suggestion.expectedEffect(),
                suggestion.risk(), properties.getValidationHorizonDays(), suggestion.evidence());
        RecommendationView proposal = recommendations.require(id);
        // VALIDATED is a draft whose guardrails were evaluated for preview and that holds together: the
        // verdict is recorded with it, passed or not, and approval evaluates the guardrails again.
        guardrails.preview(proposal, null, GuardrailPurpose.IMPACT_PREVIEW);
        recommendations.transition(OPERATOR, id, RecommendationState.VALIDATED, null, proposal.version());
        recommendations.transition(OPERATOR, id, RecommendationState.READY_FOR_REVIEW, null, proposal.version() + 1);
    }

    /**
     * The suggestion one listing's findings call for, or empty when they call for none.
     *
     * @param asOf the end of the calculation the findings come from: the economics are read as they
     *        stood then
     */
    Optional<Suggestion> suggest(UUID storeId, ListingResult listing, Instant asOf) {
        Map<String, StoreFindingsQuery.Finding> byRule = new LinkedHashMap<>();
        listing.findings().forEach(finding -> byRule.putIfAbsent(finding.ruleCode(), finding));
        if (BLOCKERS.stream().anyMatch(byRule::containsKey)) {
            return Optional.empty();
        }
        Optional<StoreFindingsQuery.Finding> cause = CAUSES.stream().map(byRule::get).filter(Objects::nonNull)
                .findFirst();
        StoreFindingsQuery.Value price = available(listing, OBSERVED_SELLING_PRICE);
        StoreFindingsQuery.Value target = available(listing, TARGET_MARGIN_PRICE);
        if (cause.isEmpty() || price == null || target == null || price.numericValue().signum() <= 0
                || price.currencyCode() == null || !price.currencyCode().equals(target.currencyCode())) {
            return Optional.empty();
        }
        String currency = price.currencyCode();
        BigDecimal current = price.numericValue();
        BigDecimal floor = target.numericValue().setScale(0, RoundingMode.CEILING);
        BigDecimal step = current.multiply(BigDecimal.ONE.subtract(properties.getMaxStepRate()))
                .setScale(0, RoundingMode.CEILING);
        BigDecimal suggested = floor.max(step);
        StoreFindingsQuery.Value competitor = available(listing, PLATFORM_COMPETITOR_MIN_PRICE);
        boolean comparable = competitor != null && currency.equals(competitor.currencyCode());
        if ("PRICE_GAP_REDUCIBLE".equals(cause.get().ruleCode())) {
            if (!comparable) {
                return Optional.empty();
            }
            suggested = suggested.max(competitor.numericValue().setScale(0, RoundingMode.FLOOR));
        }
        if (suggested.compareTo(current) >= 0) {
            return Optional.empty();
        }
        BigDecimal change = suggested.divide(current, 4, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);

        Map<String, String> effect = new LinkedHashMap<>();
        effect.put("basis", cause.get().ruleCode());
        effect.put("priceScope", "BUYER_PRICE");
        effect.put("currencyCode", currency);
        effect.put("currentPrice", plain(current));
        effect.put("targetPrice", plain(suggested));
        effect.put("changeRate", plain(change));
        effect.put("rangeLower", plain(floor));
        effect.put("rangeUpper", plain(current));
        effect.put("maxStepRate", plain(properties.getMaxStepRate()));
        StoreFindingsQuery.Value margin = available(listing, PROJECTED_UNIT_MARGIN);
        if (margin != null) {
            effect.put("marginNow", plain(margin.numericValue()));
        }
        estimates.estimateAt(storeId, listing.listingVariantId(), suggested, currency, asOf)
                .map(ListingUnitEconomics.Estimate::margin).filter(Objects::nonNull)
                .ifPresent(atTarget -> effect.put("marginAtTarget", plain(atTarget)));
        String floorRate = cause.get().detail().get("minimumUnitMarginRate");
        if (floorRate != null) {
            effect.put("minimumUnitMarginRate", floorRate);
        }
        if (comparable) {
            effect.put("competitorMinPrice", plain(competitor.numericValue()));
        }
        StoreFindingsQuery.Value search = available(listing, SEARCH_USERS);
        if (search != null) {
            effect.put("searchUsers", plain(search.numericValue()));
        }

        BigDecimal priority = search == null ? BigDecimal.ZERO
                : search.numericValue().divide(SEARCHERS_PER_POINT, 4, RoundingMode.DOWN).min(MAXIMUM_PRIORITY);
        BigDecimal size = change.abs();
        String risk = size.compareTo(LOW_RISK_CHANGE) <= 0 ? "LOW"
                : size.compareTo(MEDIUM_RISK_CHANGE) <= 0 ? "MEDIUM" : "HIGH";
        List<RecommendationService.EvidenceLink> evidence = new ArrayList<>();
        evidence.add(new RecommendationService.EvidenceLink(null, cause.get().findingId(), null, "PRIMARY_CAUSE"));
        for (MetricCode code : SUPPORTING) {
            StoreFindingsQuery.Value value = available(listing, code);
            if (value != null && value.valueId() != null) {
                evidence.add(new RecommendationService.EvidenceLink(value.valueId(), null, null, "SUPPORTING"));
            }
        }
        return Optional.of(new Suggestion(suggested, java.util.Collections.unmodifiableMap(effect), priority, risk,
                List.copyOf(evidence)));
    }

    private static StoreFindingsQuery.Value available(ListingResult listing, MetricCode code) {
        StoreFindingsQuery.Value value = listing.metrics().get(code);
        return value != null && value.valueState() == ValueState.AVAILABLE && value.numericValue() != null
                ? value : null;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** One listing's suggestion before it is proposed. */
    record Suggestion(BigDecimal targetPrice, Map<String, String> expectedEffect, BigDecimal priority, String risk,
                      List<RecommendationService.EvidenceLink> evidence) {
    }

    /**
     * What one pass did.
     *
     * @param calculationRunId the calculation the suggestions rest on, or {@code null} without one
     * @param expired proposals of any store whose validity elapsed and were expired first
     * @param proposed suggestions that entered the review for listings without one
     * @param refreshed undecided suggestions replaced by one with another price
     * @param withdrawn undecided suggestions withdrawn because the findings no longer call for them, or
     *        because the buyer price has just jumped
     * @param standing listings whose suggestion still stands, or whose price proposal is not this service's
     * @param cooling listings left alone because a decision about them is younger than the horizon
     * @param paused listings the findings call for a price for, waiting because their buyer price has just
     *        jumped
     * @param failed listings whose suggestion could not be proposed or withdrawn
     */
    public record Result(UUID calculationRunId, int expired, int proposed, int refreshed, int withdrawn,
                         int standing, int cooling, int paused, int failed) {
    }
}
