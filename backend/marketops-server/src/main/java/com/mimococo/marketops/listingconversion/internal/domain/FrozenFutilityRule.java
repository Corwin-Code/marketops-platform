package com.mimococo.marketops.listingconversion.internal.domain;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Optional, independently qualified upper-bound rule frozen with the plan. */
public record FrozenFutilityRule(String nodeCode, BigDecimal minimumEffect, String qualificationReference) {
    public static final String TRIGGER = "QUALIFIED_FUTILITY";

    /** Empty means either explicitly disabled ({@code {}}) or malformed; callers distinguish with {@code rule.isEmpty()}. */
    public static Optional<FrozenFutilityRule> resolve(JsonNode rule, JsonNode formalNodes) {
        if (rule == null || !rule.isObject() || rule.isEmpty() || formalNodes == null || !formalNodes.isArray())
            return Optional.empty();
        String nodeCode=rule.path("nodeCode").asText();
        JsonNode method=rule.path("method");
        if (!nodeCode.matches("[A-Z][A-Z0-9_]{0,63}") || !TRIGGER.equals(rule.path("trigger").asText())
                || !method.isObject() || !FrozenComparisonMethod.CODE.equals(method.path("code").asText())
                || !method.path("upperBoundRequired").asBoolean(false)
                || !method.path("qualificationRef").isTextual() || method.path("qualificationRef").asText().isBlank())
            return Optional.empty();
        var exactCodes=new HashSet<String>();
        formalNodes.forEach(node->{
            if (FrozenComparisonMethod.CODE.equals(node.path("method").asText()))
                exactCodes.add(node.path("nodeCode").asText());
        });
        if (!exactCodes.contains(nodeCode)) return Optional.empty();
        try {
            JsonNode value=method.path("minimumEffect");
            if (!value.isNumber() && !value.isTextual()) return Optional.empty();
            BigDecimal minimum=new BigDecimal(value.asText());
            if (minimum.signum()<0 || minimum.compareTo(BigDecimal.ONE)>0) return Optional.empty();
            return Optional.of(new FrozenFutilityRule(nodeCode,minimum,method.path("qualificationRef").asText()));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
