package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FrozenFutilityRuleTest {
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test void explicitEmptyRuleDisablesStopping() {
        assertThat(FrozenFutilityRule.resolve(JSON.readTree("{}"),FrozenComparisonMethodTest.nodes("0.05"))).isEmpty();
    }

    @Test void exactUpperBoundRuleBindsOneFrozenNodeAndItsOwnMinimumEffect() {
        var rule=FrozenFutilityRule.resolve(JSON.readTree("""
                {"nodeCode":"D14","trigger":"QUALIFIED_FUTILITY",
                 "method":{"code":"EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1",
                  "upperBoundRequired":true,"minimumEffect":"0.03","qualificationRef":"fixture://futility"}}
                """),FrozenComparisonMethodTest.nodes("0.05")).orElseThrow();
        assertThat(rule.nodeCode()).isEqualTo("D14");
        assertThat(rule.minimumEffect()).isEqualByComparingTo(new BigDecimal("0.03"));
        assertThat(rule.qualificationReference()).isEqualTo("fixture://futility");
    }

    @Test void legacyMissTriggerAndMalformedIndependentMethodStayUnqualified() {
        assertThat(FrozenFutilityRule.resolve(JSON.readTree(
                "{\"nodeCode\":\"D14\",\"trigger\":\"NOT_MET_AFTER_MATURITY\"}"),
                FrozenComparisonMethodTest.nodes("0.05"))).isEmpty();
        assertThat(FrozenFutilityRule.resolve(JSON.readTree("""
                {"nodeCode":"D14","trigger":"QUALIFIED_FUTILITY",
                 "method":{"code":"EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1",
                  "upperBoundRequired":true,"minimumEffect":"0.03","qualificationRef":"fixture://futility"}}
                """),JSON.readTree(FrozenComparisonMethodTest.nodes("0.05").toString()
                    .replace(FrozenComparisonMethod.CODE,"WILSON_LOWER_BOUND")))).isEmpty();
        assertThat(FrozenFutilityRule.resolve(JSON.readTree("""
                {"nodeCode":"D14","trigger":"QUALIFIED_FUTILITY",
                 "method":{"code":"EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1",
                  "upperBoundRequired":false,"minimumEffect":"0.03","qualificationRef":"fixture://futility"}}
                """),FrozenComparisonMethodTest.nodes("0.05"))).isEmpty();
    }
}
