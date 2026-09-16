package com.mimococo.marketops.listingconversion.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Qualifies aggregate method inputs without inventing visit identities.
 *
 * <p>The formal fixed-traffic method consumes exact retained-visit numerator and
 * visit denominator counts for both supported source strata. Critical groups
 * use the same count shape. Reported totals remain independently usable when
 * these auxiliary method inputs are absent or invalid.
 *
 * <p>A critical group is admitted only as a subset of its own source cohort in
 * the same window. For a cohort of {@code N} visits with {@code K} successes and a
 * group of {@code n} visits with {@code k} successes that means {@code n <= N},
 * {@code k <= K} and, because the complement must be able to carry the remaining
 * successes, {@code K - k <= N - n}. Two separate upper bounds are not enough:
 * every one of {@code N} visits with fewer than {@code K} successes is not a
 * subset of the cohort. Groups may overlap; nothing requires them to sum to the
 * cohort. A group that fails the rule is retained as reported and excluded from
 * the method bridge; the source strata and the independent total are unaffected.
 */
public final class OfficialSummaryMethodEvidence {

    public static final int VERSION = 1;
    private static final Set<String> SOURCES = Set.of("ADVERTISING", "ORGANIC");
    private static final Set<String> COUNT_FIELDS = Set.of("visits", "retained");
    private static final Pattern GROUP_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,62}$");

    private OfficialSummaryMethodEvidence() {
    }

    public record Count(long visits, long retained) {
    }

    public record Assessment(boolean sourceStrataValid, boolean criticalGroupsValid,
                             Map<String, Count> sourceStrata,
                             Map<String, Map<String, Count>> criticalGroups,
                             List<String> reasonCodes) {
        public Assessment {
            sourceStrata = java.util.Collections.unmodifiableMap(new TreeMap<>(sourceStrata));
            var groups = new TreeMap<String, Map<String, Count>>();
            criticalGroups.forEach((code, counts) -> groups.put(code,
                    java.util.Collections.unmodifiableMap(new TreeMap<>(counts))));
            criticalGroups = java.util.Collections.unmodifiableMap(groups);
            reasonCodes = List.copyOf(reasonCodes);
        }
    }

    /** Validate only the formal method bridge; the independent reported total is not rejected here. */
    public static Assessment assess(Integer version, JsonNode source, JsonNode groups,
                                    Long reportedVisits, Long reportedRetained) {
        List<String> reasons = new ArrayList<>();
        if (version == null || version != VERSION) {
            reasons.add("SUMMARY_METHOD_INPUT_VERSION_UNQUALIFIED");
        }
        Map<String, Count> sourceCounts = counts(source);
        boolean sourceValid = sourceCounts != null;
        if (!sourceValid) {
            reasons.add(source == null || source.isMissingNode() || source.isNull()
                    ? "SUMMARY_SOURCE_STRATA_MISSING" : "SUMMARY_SOURCE_STRATA_SCHEMA_INVALID");
            sourceCounts = Map.of();
        } else if (reportedVisits == null || reportedRetained == null
                || sumVisits(sourceCounts) != reportedVisits || sumRetained(sourceCounts) != reportedRetained) {
            reasons.add("SUMMARY_SOURCE_STRATA_TOTAL_MISMATCH");
            sourceValid = false;
        }

        Map<String, Map<String, Count>> groupCounts = new TreeMap<>();
        boolean groupsValid = groups != null && groups.isObject() && !groups.isEmpty();
        if (!groupsValid) {
            reasons.add(groups == null || groups.isMissingNode() || groups.isNull() || groups.isEmpty()
                    ? "SUMMARY_CRITICAL_GROUP_STRATA_MISSING" : "SUMMARY_CRITICAL_GROUP_STRATA_SCHEMA_INVALID");
        } else {
            for (var group : groups.properties()) {
                Map<String, Count> memberCounts = counts(group.getValue());
                if (!GROUP_CODE.matcher(group.getKey()).matches() || memberCounts == null) {
                    groupsValid = false;
                    reasons.add("SUMMARY_CRITICAL_GROUP_STRATA_SCHEMA_INVALID");
                    continue;
                }
                groupCounts.put(group.getKey(), memberCounts);
                if (sourceValid && !within(memberCounts, sourceCounts)) {
                    groupsValid = false;
                    reasons.add("SUMMARY_CRITICAL_GROUP_OUTSIDE_TOTAL");
                }
            }
        }
        return new Assessment(version != null && version == VERSION && sourceValid,
                version != null && version == VERSION && sourceValid && groupsValid,
                sourceCounts, groupCounts, reasons.stream().distinct().toList());
    }

    private static Map<String, Count> counts(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        var names = node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        if (!names.equals(SOURCES)) {
            return null;
        }
        Map<String, Count> result = new TreeMap<>();
        for (String source : SOURCES) {
            JsonNode count = node.path(source);
            var fields = count.isObject()
                    ? count.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet())
                    : Set.<String>of();
            JsonNode visits = count.path("visits"), retained = count.path("retained");
            if (!fields.equals(COUNT_FIELDS) || !visits.isIntegralNumber() || !retained.isIntegralNumber()
                    || !visits.canConvertToLong() || !retained.canConvertToLong()) {
                return null;
            }
            long denominator = visits.longValue(), numerator = retained.longValue();
            if (denominator < 0 || numerator < 0 || numerator > denominator) {
                return null;
            }
            result.put(source, new Count(denominator, numerator));
        }
        return result;
    }

    /** The group and its complement must both be realisable inside the same source cohort. */
    private static boolean within(Map<String, Count> group, Map<String, Count> total) {
        return SOURCES.stream().allMatch(source -> {
            Count member = group.get(source), cohort = total.get(source);
            return member.visits() <= cohort.visits()
                    && member.retained() <= cohort.retained()
                    && cohort.retained() - member.retained() <= cohort.visits() - member.visits();
        });
    }

    private static long sumVisits(Map<String, Count> counts) {
        try {
            return counts.values().stream().mapToLong(Count::visits).reduce(0, Math::addExact);
        } catch (ArithmeticException overflow) {
            return Long.MIN_VALUE;
        }
    }

    private static long sumRetained(Map<String, Count> counts) {
        try {
            return counts.values().stream().mapToLong(Count::retained).reduce(0, Math::addExact);
        } catch (ArithmeticException overflow) {
            return Long.MIN_VALUE;
        }
    }
}
