package com.mimococo.marketops.listingconversion.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Executable method parameters from the complete frozen node family. This
 * validates arithmetic and predeclared multiplicity, not evidence eligibility,
 * independence of visits, comparability, version coverage or causal authority.
 */
public final class FrozenComparisonMethod {
    public static final String CODE = "EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1";
    private static final Set<String> MATURITY = Set.of("7", "14", "30");
    private final String nodeCode;
    private final int maturityDays;
    private final int notBeforeDays;
    private final int lastDay;
    private final int windowStartDay;
    private final int windowEndDay;
    private final BigDecimal componentTailAlpha;
    private final String qualificationReference;

    private FrozenComparisonMethod(String nodeCode, int maturityDays, int notBeforeDays, int lastDay, int windowStartDay, int windowEndDay,
                                   BigDecimal componentTailAlpha, String qualificationReference) {
        this.nodeCode = nodeCode;
        this.maturityDays = maturityDays;
        this.notBeforeDays = notBeforeDays;
        this.lastDay = lastDay;
        this.windowStartDay = windowStartDay;
        this.windowEndDay = windowEndDay;
        this.componentTailAlpha = componentTailAlpha;
        this.qualificationReference = qualificationReference;
    }

    /**
     * Every node spends a positive, explicit part of one family alpha. A node's
     * budget covers both tails of four binomial components per comparison
     * (reference/target times advertising/organic), including each frozen
     * critical group's own comparison. Dependence between components or looks
     * does not invalidate this union bound; within-component binomial sampling
     * still needs independently verified evidence outside this parser.
     */
    public static Optional<FrozenComparisonMethod> resolve(JsonNode nodes, JsonNode criticalGroups, String requestedNode) {
        if (nodes == null || !nodes.isArray() || nodes.size() < 1 || nodes.size() > 8
                || criticalGroups == null || !criticalGroups.isArray()) return Optional.empty();
        Set<String> groupCodes = new HashSet<>();
        for (JsonNode group : criticalGroups) {
            if (!group.isObject() || !group.path("code").isTextual()
                    || !group.path("code").asText().matches("[A-Z][A-Z0-9_]{0,63}")
                    || !groupCodes.add(group.path("code").asText())) return Optional.empty();
        }
        Set<String> codes = new HashSet<>();
        BigDecimal family = null, spent = BigDecimal.ZERO;
        FrozenComparisonMethod selected = null;
        try {
            for (JsonNode node : nodes) {
                String code = node.path("nodeCode").asText();
                if (!node.isObject() || !code.matches("[A-Z][A-Z0-9_]{0,63}") || !codes.add(code)
                        || !CODE.equals(node.path("method").asText())
                        || !MATURITY.contains(node.path("maturityDays").asText())) return Optional.empty();
                JsonNode parameters = node.path("methodParameters"), schedule = node.path("schedule");
                if (!"INDEPENDENT_BERNOULLI_VISITS".equals(parameters.path("samplingModel").asText())
                        || !parameters.path("qualificationRef").isTextual()
                        || parameters.path("qualificationRef").asText().isBlank()) return Optional.empty();
                BigDecimal thisFamily = probability(parameters.path("familyAlpha"));
                BigDecimal allocated = probability(parameters.path("nodeAlpha"));
                if (family == null) family = thisFamily;
                if (family.compareTo(thisFamily) != 0) return Optional.empty();
                spent = spent.add(allocated);
                int maturity = Integer.parseInt(node.path("maturityDays").asText());
                int first = boundedDay(schedule.path("notBeforeOffsetDays"));
                int last = boundedDay(schedule.path("lastOffsetDays"));
                int start = boundedDay(schedule.path("windowStartOffsetDays"));
                int end = boundedDay(schedule.path("windowEndOffsetDays"));
                if (start >= end || first < end + maturity || last < first) return Optional.empty();
                // Round alpha down, never spend more error probability than
                // accepted. These are arithmetic precision limits, not policy.
                BigDecimal tail = allocated.divide(BigDecimal.valueOf(8L * (1L + groupCodes.size())),
                        24, RoundingMode.DOWN);
                if (tail.signum() <= 0 || 1.0 - tail.doubleValue() == 1.0) return Optional.empty();
                if (code.equals(requestedNode)) selected = new FrozenComparisonMethod(code, maturity, first, last, start, end,
                        tail, parameters.path("qualificationRef").asText());
            }
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return Optional.empty();
        }
        return family != null && spent.compareTo(family) <= 0 ? Optional.ofNullable(selected) : Optional.empty();
    }

    private static BigDecimal probability(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) throw new IllegalArgumentException("explicit probability required");
        BigDecimal probability = new BigDecimal(value.asText());
        if (probability.signum() <= 0 || probability.compareTo(BigDecimal.ONE) >= 0)
            throw new IllegalArgumentException("probability outside open unit interval");
        return probability;
    }

    private static int boundedDay(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException("integer day required");
        int day = value.intValue();
        if (day < 0 || day > 3660) throw new IllegalArgumentException("day outside supported plan horizon");
        return day;
    }

    public String nodeCode() { return nodeCode; }
    public int maturityDays() { return maturityDays; }
    public int notBeforeDays() { return notBeforeDays; }
    public int lastDay() { return lastDay; }
    public int windowStartDay() { return windowStartDay; }
    public int windowEndDay() { return windowEndDay; }
    public BigDecimal componentTailAlpha() { return componentTailAlpha; }
    public String qualificationReference() { return qualificationReference; }
}
