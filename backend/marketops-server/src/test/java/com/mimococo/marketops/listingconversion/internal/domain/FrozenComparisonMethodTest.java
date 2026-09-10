package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FrozenComparisonMethodTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode nodes(String nodeAlpha) {
        return JSON.readTree("""
                [{"nodeCode":"D14","maturityDays":14,"method":"EXACT_BINOMIAL_FIXED_TRAFFIC_BONFERRONI_V1",
                  "methodParameters":{"familyAlpha":"0.05","nodeAlpha":"%s",
                   "samplingModel":"INDEPENDENT_BERNOULLI_VISITS","qualificationRef":"fixture://sampling-method"},
                  "schedule":{"notBeforeOffsetDays":28,"lastOffsetDays":42,"windowStartOffsetDays":0,"windowEndOffsetDays":14}}]
                """.formatted(nodeAlpha));
    }
    static FrozenComparisonMethod method() {
        return FrozenComparisonMethod.resolve(nodes("0.05"), JSON.readTree("[]"), "D14").orElseThrow();
    }

    @Test void explicitBudgetCoversBothTailsAndAllFrozenComparisons() {
        assertThat(method().componentTailAlpha()).isEqualByComparingTo("0.00625");
        var grouped = FrozenComparisonMethod.resolve(nodes("0.05"), JSON.readTree("[{\"code\":\"SMALL\"}]"), "D14").orElseThrow();
        assertThat(grouped.componentTailAlpha()).isEqualByComparingTo("0.003125");
        assertThat(grouped.qualificationReference()).isEqualTo("fixture://sampling-method");
        assertThat(grouped.notBeforeDays()).isEqualTo(28);
        assertThat(grouped.lastDay()).isEqualTo(42);
        assertThat(grouped.maturityDays()).isEqualTo(14);
    }

    @Test void laterNodesCannotOverspendTheFamilyOrUseAnotherConfidencePolicy() {
        String first = nodes("0.03").get(0).toString();
        String second = first.replace("D14", "D30").replace("\"maturityDays\":14", "\"maturityDays\":30")
                .replace("\"notBeforeOffsetDays\":28", "\"notBeforeOffsetDays\":44")
                .replace("\"lastOffsetDays\":42", "\"lastOffsetDays\":50");
        assertThat(resolve("[" + first + "," + second + "]")).isEmpty();
        assertThat(resolve(("[" + first + "," + second + "]").replace("0.03", "0.025"))).isPresent();
        assertThat(resolve("[" + first + "," + second.replace("0.05", "0.1") + "]")).isEmpty();
    }

    @Test void duplicateNodesAndGroupsCannotHideMultiplicity() {
        assertThat(resolve("[" + nodes("0.01").get(0) + "," + nodes("0.01").get(0) + "]")).isEmpty();
        assertThat(FrozenComparisonMethod.resolve(nodes("0.05"), JSON.readTree("[{\"code\":\"A\"},{\"code\":\"A\"}]"), "D14")).isEmpty();
        assertThat(FrozenComparisonMethod.resolve(nodes("0.05"), JSON.readTree("[\"A\"]"), "D14")).isEmpty();
    }

    @Test void missingInvalidOrUnrepresentableConfidenceHasNoDefault() {
        for (String alpha : new String[] {"0", "-0.05", "1", "NaN", "0.06", "0.00000000000000000000000000000001"})
            assertThat(FrozenComparisonMethod.resolve(nodes(alpha), JSON.readTree("[]"), "D14")).as(alpha).isEmpty();
        assertThat(resolve(nodes("0.05").toString().replace("\"familyAlpha\":\"0.05\",", ""))).isEmpty();
        assertThat(resolve(nodes("0.05").toString().replace("INDEPENDENT_BERNOULLI_VISITS", "UNVERIFIED"))).isEmpty();
        assertThat(resolve(nodes("0.05").toString().replace("fixture://sampling-method", ""))).isEmpty();
    }

    @Test void unsupportedMethodAndAbsentNodeStayUnavailable() {
        assertThat(resolve(nodes("0.05").toString().replace(FrozenComparisonMethod.CODE, "WILSON_LOWER_BOUND"))).isEmpty();
        assertThat(FrozenComparisonMethod.resolve(nodes("0.05"), JSON.readTree("[]"), "D7")).isEmpty();
        assertThat(FrozenComparisonMethod.resolve(null, JSON.readTree("[]"), "D14")).isEmpty();
    }

    @Test void finiteScheduleCannotPrecedeMaturityOrSmuggleAFractionalOffset() {
        String body = nodes("0.05").toString();
        for (String replacement : new String[] {"27", "28.5", "\"28\"", "3661", "-1"})
            assertThat(resolve(body.replace("\"notBeforeOffsetDays\":28", "\"notBeforeOffsetDays\":" + replacement))).as(replacement).isEmpty();
        assertThat(resolve(body.replace("\"lastOffsetDays\":42", "\"lastOffsetDays\":13"))).isEmpty();
        assertThat(resolve(body.replace("\"maturityDays\":14", "\"maturityDays\":14.5"))).isEmpty();
    }

    private static java.util.Optional<FrozenComparisonMethod> resolve(String body) {
        return FrozenComparisonMethod.resolve(JSON.readTree(body), JSON.readTree("[]"), "D14");
    }
}
