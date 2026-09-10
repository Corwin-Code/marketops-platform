package com.mimococo.marketops.listingconversion.internal.domain;

import com.mimococo.marketops.listingconversion.RatioState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Retained-visit conversion as a set ratio: |S| / |V|.
 *
 * <p>The denominator is the set of qualified visits and the numerator is the
 * subset of those visits with at least one retained purchase. A visit with
 * several retained purchases counts once, so the numerator is bounded by the
 * denominator by construction. A zero denominator is UNDEFINED, and missing
 * maturity or eligibility is NOT_AVAILABLE; neither is zero.
 *
 * <p>There is no constructor that takes orders, completed sales, units, buyouts,
 * add-to-cart conversions or a platform "conversion rate" label. The type
 * system has no way to make those the numerator.
 */
public final class VisitConversion {

    private static final int SCALE = 6;

    private VisitConversion() {
    }

    /** One qualified visit with its sellability at visit time and its source channel. */
    public record Visit(String visitKey, String sellableAtVisit, String sourceChannel) {
        public Visit {
            Objects.requireNonNull(visitKey, "visitKey");
            sellableAtVisit = sellableAtVisit == null ? "UNKNOWN" : sellableAtVisit;
            sourceChannel = sourceChannel == null ? "UNKNOWN" : sourceChannel;
        }
    }

    /**
     * The result of one computation.
     *
     * @param visitCount |V|
     * @param retainedPurchaseVisitCount |S|
     * @param ratio |S| / |V| when DEFINED, otherwise {@code null}
     * @param state whether the ratio has a value
     * @param sellableSplit the same ratio per sellability group, auxiliary only
     * @param sourceStratified whether every visit carries a known source channel
     */
    public record Result(long visitCount, long retainedPurchaseVisitCount, BigDecimal ratio,
                         RatioState state, Map<String, String> sellableSplit, boolean sourceStratified) {
        public Result {
            sellableSplit = Map.copyOf(sellableSplit == null ? Map.of() : sellableSplit);
        }
    }

    public record SourceCount(long visits, long retained) { }

    /** Exact diagnostic counts over the same filtered visit cohort as the primary result. */
    public static Map<String, SourceCount> sourceCounts(Collection<Visit> visits,
                                                       Collection<String> retainedVisitKeys) {
        Map<String,Visit> distinct=new LinkedHashMap<>();
        visits.forEach(visit->distinct.putIfAbsent(visit.visitKey(),visit));
        Set<String> retained=new HashSet<>(retainedVisitKeys);
        Map<String,SourceCount> counts=new LinkedHashMap<>();
        for (String source : List.of("ADVERTISING","ORGANIC","UNKNOWN")) {
            var members=distinct.values().stream().filter(visit->source.equals(
                    Set.of("ADVERTISING","ORGANIC").contains(visit.sourceChannel())?visit.sourceChannel():"UNKNOWN")).toList();
            counts.put(source,new SourceCount(members.size(),members.stream().filter(v->retained.contains(v.visitKey())).count()));
        }
        return java.util.Collections.unmodifiableMap(counts);
    }

    /**
     * Compute the ratio.
     *
     * @param visits the qualified visits of the window, de-duplicated by key here
     * @param visitKeysWithRetainedPurchase visit keys that have at least one retained purchase
     * @param maturityReached whether the retention window has elapsed for the whole window
     * @param eligible whether the evidence path and Listing Health make the measurement eligible
     */
    public static Result compute(Collection<Visit> visits, Collection<String> visitKeysWithRetainedPurchase,
                                 boolean maturityReached, boolean eligible) {
        Map<String, Visit> distinct = new LinkedHashMap<>();
        for (Visit visit : visits) {
            distinct.putIfAbsent(visit.visitKey(), visit);
        }
        Set<String> retained = new HashSet<>();
        for (String key : visitKeysWithRetainedPurchase) {
            if (distinct.containsKey(key)) {
                retained.add(key);
            }
        }
        long denominator = distinct.size();
        long numerator = retained.size();
        boolean stratified = distinct.values().stream().noneMatch(v -> "UNKNOWN".equals(v.sourceChannel()));
        Map<String, String> split = new LinkedHashMap<>();
        for (String group : new String[] {"YES", "NO", "UNKNOWN"}) {
            long groupVisits = distinct.values().stream().filter(v -> group.equals(v.sellableAtVisit())).count();
            long groupRetained = distinct.values().stream()
                    .filter(v -> group.equals(v.sellableAtVisit()) && retained.contains(v.visitKey())).count();
            split.put(group, groupVisits == 0 ? "UNDEFINED" : ratioOf(groupRetained, groupVisits).toPlainString());
        }
        if (!maturityReached || !eligible) {
            return new Result(denominator, numerator, null, RatioState.NOT_AVAILABLE, split, stratified);
        }
        if (denominator == 0) {
            return new Result(0, 0, null, RatioState.UNDEFINED, split, stratified);
        }
        return new Result(denominator, numerator, ratioOf(numerator, denominator), RatioState.DEFINED, split,
                stratified);
    }

    private static BigDecimal ratioOf(long numerator, long denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.DOWN);
    }
}
