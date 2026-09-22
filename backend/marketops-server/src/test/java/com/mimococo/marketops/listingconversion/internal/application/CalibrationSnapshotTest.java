package com.mimococo.marketops.listingconversion.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.listingconversion.internal.infrastructure.jdbc.CalibrationRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CalibrationSnapshotTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test
    void freezePreservesNestedMethodScheduleAndExactScalarTypes() {
        var nodes=json.readTree("""
                [{"nodeCode":"D14","maturityDays":14,"method":"QUALIFIED_COMPARISON","threshold":0.05,
                  "schedule":{"lookOffsetsDays":[14,30],"familyAlpha":"0.025"},
                  "qualification":{"reference":"fixture://qualified-method","criticalValue":"2.8"}}]
                """);
        var source=resolved("FORMAL_NODES",null,nodes);
        var frozen=CalibrationService.formalNodes(source);
        assertThat(json.<tools.jackson.databind.JsonNode>valueToTree(frozen)).isEqualTo(nodes);
        ((tools.jackson.databind.node.ObjectNode)nodes.get(0).path("schedule")).put("familyAlpha","0.9");
        assertThat(json.valueToTree(frozen).get(0).path("schedule").path("familyAlpha").asString()).isEqualTo("0.025");
    }

    @Test
    void stopRuleRetainsStructuredQualificationInsteadOfJsonInsideStrings() {
        var stop=json.readTree("""
                {"nodeCode":"D14","trigger":"QUALIFIED_FUTILITY",
                 "method":{"upperBoundRequired":true,"minimumEffect":0.05}}
                """);
        assertThat(json.<tools.jackson.databind.JsonNode>valueToTree(CalibrationService.stopRule(resolved("STOP_RULE",null,stop)))).isEqualTo(stop);
        assertThat(CalibrationService.stopRule(resolved("STOP_RULE",null,json.readTree("{}")))).isEmpty();
    }

    @Test
    void explicitZeroTailDiffersFromMissingFractionalAndInvalidTail() {
        assertThat(CalibrationService.crossPeriodWindowDays(resolved("CROSS_PERIOD_WINDOW",BigDecimal.ZERO,null))).contains(0);
        assertThat(CalibrationService.crossPeriodWindowDays(resolved("CROSS_PERIOD_WINDOW",null,null))).isEmpty();
        assertThat(CalibrationService.crossPeriodWindowDays(resolved("CROSS_PERIOD_WINDOW",new BigDecimal("0.9"),null))).isEmpty();
        assertThat(CalibrationService.crossPeriodWindowDays(resolved("CROSS_PERIOD_WINDOW",new BigDecimal("-1"),null))).isEmpty();
    }

    @Test
    void absentStopRuleDoesNotMeanAnExplicitlyAcceptedNoStopRule() {
        assertThat(CalibrationService.hasExplicitStopRule(resolved("STOP_RULE",null,json.readTree("{}")))).isTrue();
        assertThat(CalibrationService.hasExplicitStopRule(resolved("STOP_RULE",null,null))).isFalse();
        assertThat(CalibrationService.hasExplicitStopRule(resolved("STOP_RULE",null,json.readTree("[]")))).isFalse();
    }

    @Test
    void groupSnapshotRetainsItsOwnBoundsAndMembership() {
        var rule=json.readTree("""
                {"groups":[{"code":"HIGH_RETURN","members":["fixture-variant"],
                  "returnRateBound":0.02,"profitBound":0.01,"comparison":"NON_WORSENING"}]}
                """);
        var snapshot=CalibrationService.criticalGroupRules(resolved("CRITICAL_GROUP_RULE",null,rule));
        assertThat(json.<tools.jackson.databind.JsonNode>valueToTree(snapshot)).isEqualTo(rule.path("groups"));
        ((tools.jackson.databind.node.ObjectNode)rule.path("groups").get(0)).put("profitBound",0.9);
        assertThat(snapshot.get(0).path("profitBound").decimalValue()).isEqualByComparingTo("0.01");
    }

    private CalibrationService.Resolved resolved(String category,BigDecimal numeric,tools.jackson.databind.JsonNode body) {
        return new CalibrationService.Resolved(UUID.randomUUID(),1,Map.of(category,
                new CalibrationRepository.Value(category,numeric,null,body,"RULE",null,"fixture://calibration")));
    }
}
