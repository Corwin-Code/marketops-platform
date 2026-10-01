package com.mimococo.marketops.aicopilot.internal.application;

import static com.mimococo.marketops.analyticsdecision.MetricCode.AD_COST_OF_SALE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.CLICKS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.CLICK_THROUGH_RATE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.COMPLETED_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.CONTENT_RATING;
import static com.mimococo.marketops.analyticsdecision.MetricCode.CONTRIBUTION_MARGIN;
import static com.mimococo.marketops.analyticsdecision.MetricCode.CONVERSION_RATE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.IMPRESSIONS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.INTERNAL_AVAILABLE_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.LISTING_SELLABLE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.OBSERVED_SELLING_PRICE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.ORDERED_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.PLATFORM_AVAILABLE_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.PLATFORM_COMPETITOR_MIN_PRICE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.PROJECTED_BREAK_EVEN_PRICE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.PROJECTED_UNIT_MARGIN;
import static com.mimococo.marketops.analyticsdecision.MetricCode.RETAINED_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.RETURN_RATE;
import static com.mimococo.marketops.analyticsdecision.MetricCode.RETURN_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.SEARCH_USERS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.SETTLED_UNITS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.STOCK_COVER_DAYS;
import static com.mimococo.marketops.analyticsdecision.MetricCode.TARGET_MARGIN_PRICE;

import com.mimococo.marketops.analyticsdecision.MetricCode;
import com.mimococo.marketops.analyticsdecision.ValueState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What may leave for a model provider, and how each value is written, per the Owner's decision of
 * 2026-09-29: listing titles and attributes, prices and competitor prices, search terms,
 * deterministic findings and ratio metrics may be sent; raw cost and profit amounts, buyer data and
 * credentials never.
 *
 * <p>So a metric is sent only when it is a count, a ratio, a score, or one of the two prices; every
 * other money amount (sales, fees, costs, profits and prices derived from cost) stays here. The
 * cost-derived prices travel only as ratios computed here from canonical values, and a finding's
 * details only through a per-rule list of keys. Every sent value is written the way a statement may
 * quote it, so the model copies numbers instead of converting them.
 */
final class ProjectionEgress {

    /**
     * Metrics sent with their value. Data completeness is not among them: it is the share of
     * realized profit inputs, which stays low on every listing that has not sold, and a model given
     * it reports the share instead of the listing. A block it causes arrives as the finding.
     */
    static final Set<MetricCode> SENT = EnumSet.of(IMPRESSIONS, CLICKS, CLICK_THROUGH_RATE, CONVERSION_RATE,
            COMPLETED_UNITS, RETAINED_UNITS, SETTLED_UNITS, RETURN_UNITS, RETURN_RATE, PLATFORM_AVAILABLE_UNITS,
            INTERNAL_AVAILABLE_UNITS, STOCK_COVER_DAYS, AD_COST_OF_SALE, CONTRIBUTION_MARGIN, OBSERVED_SELLING_PRICE,
            ORDERED_UNITS, SEARCH_USERS, LISTING_SELLABLE, CONTENT_RATING,
            PLATFORM_COMPETITOR_MIN_PRICE, PROJECTED_UNIT_MARGIN);

    /** Ratios, written as percentages. */
    private static final Set<MetricCode> RATIOS = EnumSet.of(CLICK_THROUGH_RATE, CONVERSION_RATE, RETURN_RATE,
            AD_COST_OF_SALE, CONTRIBUTION_MARGIN, PROJECTED_UNIT_MARGIN);

    /** Prices, written with their currency. */
    private static final Set<MetricCode> PRICES = EnumSet.of(OBSERVED_SELLING_PRICE, PLATFORM_COMPETITOR_MIN_PRICE);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ProjectionEgress() {
    }

    /** Ratios computed here between two canonical values, of which the first may not be sent. */
    enum Derived {

        /** The buyer price against the lowest Ozon competitor price. */
        PRICE_VS_COMPETITOR(OBSERVED_SELLING_PRICE, PLATFORM_COMPETITOR_MIN_PRICE),

        /** The estimated break-even price against the lowest competitor price. */
        BREAK_EVEN_VS_COMPETITOR(PROJECTED_BREAK_EVEN_PRICE, PLATFORM_COMPETITOR_MIN_PRICE),

        /** The estimated break-even price against the buyer price. */
        BREAK_EVEN_VS_PRICE(PROJECTED_BREAK_EVEN_PRICE, OBSERVED_SELLING_PRICE),

        /** The price that keeps the minimum unit margin against the buyer price. */
        TARGET_MARGIN_VS_PRICE(TARGET_MARGIN_PRICE, OBSERVED_SELLING_PRICE);

        final MetricCode numerator;
        final MetricCode denominator;

        Derived(MetricCode numerator, MetricCode denominator) {
            this.numerator = numerator;
            this.denominator = denominator;
        }
    }

    /** Every metric a derived ratio reads. */
    static final Set<MetricCode> DERIVATION_INPUTS = EnumSet.of(OBSERVED_SELLING_PRICE, PLATFORM_COMPETITOR_MIN_PRICE,
            PROJECTED_BREAK_EVEN_PRICE, TARGET_MARGIN_PRICE);

    /** One canonical value as a projection builder reads it, whichever query it came from. */
    record Value(UUID valueId, MetricCode code, ValueState state, BigDecimal number, String currencyCode) {

        boolean available() {
            return state == ValueState.AVAILABLE && number != null;
        }
    }

    /**
     * One derived ratio.
     *
     * @param displayValue a signed percentage, e.g. {@code +73.21%}
     * @param valueRefs the canonical values it was computed from
     */
    record DerivedValue(Derived code, String displayValue, List<UUID> valueRefs) {
    }

    /** How a sent value is written; empty text when it is not available. */
    static String display(Value value) {
        if (!value.available()) {
            return "";
        }
        BigDecimal number = value.number();
        if (RATIOS.contains(value.code())) {
            return percent(number, false);
        }
        if (value.code() == CONTENT_RATING) {
            return plain(number.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP));
        }
        if (value.code() == LISTING_SELLABLE) {
            return number.signum() > 0 ? "YES" : "NO";
        }
        if (PRICES.contains(value.code())) {
            return money(number, value.currencyCode());
        }
        if (value.code() == STOCK_COVER_DAYS) {
            return plain(number.setScale(1, RoundingMode.HALF_UP));
        }
        return plain(number);
    }

    /** Every derived ratio the values allow: both available, the same currency, a positive base. */
    static List<DerivedValue> derive(Map<MetricCode, Value> values) {
        return java.util.Arrays.stream(Derived.values())
                .map(code -> derive(code, values.get(code.numerator), values.get(code.denominator)))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<DerivedValue> derive(Derived code, Value numerator, Value denominator) {
        if (numerator == null || denominator == null || !numerator.available() || !denominator.available()
                || denominator.number().signum() <= 0
                || !java.util.Objects.equals(numerator.currencyCode(), denominator.currencyCode())) {
            return Optional.empty();
        }
        BigDecimal ratio = numerator.number().divide(denominator.number(), 8, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        return Optional.of(new DerivedValue(code, percent(ratio, true),
                List.of(numerator.valueId(), denominator.valueId())));
    }

    /** The detail keys of each rule that may be sent, and how each is written. */
    private static final Map<String, Map<String, Kind>> DETAIL_KEYS = Map.of(
            "PRICE_GAP_REDUCIBLE", priceGapKeys(),
            "PRICE_GAP_PARTIAL", priceGapKeys(),
            "PRICE_GAP_STRUCTURAL", priceGapKeys(),
            "DEMAND_NOT_CONVERTING", keys("searchUsers", Kind.COUNT, "orderedUnits", Kind.COUNT,
                    "platformAvailableUnits", Kind.COUNT, "demandSearchUsersFloor", Kind.COUNT),
            "LOW_SEARCH_EXPOSURE", keys("searchUsers", Kind.COUNT, "platformAvailableUnits", Kind.COUNT,
                    "lowExposureSearchUsers", Kind.COUNT),
            "CONTENT_BELOW_TARGET", keys("contentRating", Kind.SCORE, "contentRatingFloor", Kind.SCORE),
            "LISTING_NOT_SELLABLE", keys("listingSellable", Kind.YES_NO),
            "STOCKOUT_RISK", keys("platformAvailableUnits", Kind.COUNT, "stockCoverDays", Kind.COUNT,
                    "stockCoverDaysFloor", Kind.COUNT),
            "PROMOTION_OPPORTUNITY", keys("promotionMargin", Kind.RATIO, "minimumUnitMarginRate", Kind.RATIO),
            "PRICE_HEADROOM", keys("searchUsers", Kind.COUNT, "projectedUnitMargin", Kind.RATIO,
                    "minimumUnitMarginRate", Kind.RATIO, "priceRoom", Kind.RATIO));

    private enum Kind { COUNT, RATIO, SIGNED_RATIO, SCORE, YES_NO, PRICE }

    private static Map<String, Kind> priceGapKeys() {
        return keys("buyerPrice", Kind.PRICE, "platformCompetitorMinPrice", Kind.PRICE,
                "premiumOverCompetitor", Kind.SIGNED_RATIO, "minimumUnitMarginRate", Kind.RATIO);
    }

    private static Map<String, Kind> keys(Object... pairs) {
        Map<String, Kind> keys = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            keys.put((String) pairs[index], (Kind) pairs[index + 1]);
        }
        return java.util.Collections.unmodifiableMap(keys);
    }

    /** A finding's sendable details, in the rule's own order, written for quoting. */
    static Map<String, String> details(String ruleCode, Map<String, String> detail) {
        Map<String, Kind> allowed = DETAIL_KEYS.getOrDefault(ruleCode, Map.of());
        Map<String, String> sent = new LinkedHashMap<>();
        allowed.forEach((key, kind) -> {
            String raw = detail.get(key);
            if (raw == null || raw.isBlank()) {
                return;
            }
            BigDecimal number;
            try {
                number = new BigDecimal(raw.trim());
            } catch (NumberFormatException notNumeric) {
                return;
            }
            sent.put(key, switch (kind) {
                case COUNT -> plain(number);
                case RATIO -> percent(number, false);
                case SIGNED_RATIO -> percent(number, true);
                case SCORE -> plain(number.multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP));
                case YES_NO -> number.signum() > 0 ? "YES" : "NO";
                case PRICE -> money(number, detail.get("currencyCode"));
            });
        });
        return sent;
    }

    /** A ratio as a percentage with at most two decimals, e.g. {@code 16.85%} or {@code +183.79%}. */
    static String percent(BigDecimal ratio, boolean signed) {
        BigDecimal percent = ratio.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
        String text = plain(percent) + "%";
        return signed && percent.signum() > 0 ? "+" + text : text;
    }

    private static String money(BigDecimal amount, String currencyCode) {
        String text = plain(amount.setScale(2, RoundingMode.HALF_UP));
        return currencyCode == null || currencyCode.isBlank() ? text : text + " " + currencyCode;
    }

    /** A decimal without trailing zeros or exponent. */
    static String plain(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }
}
