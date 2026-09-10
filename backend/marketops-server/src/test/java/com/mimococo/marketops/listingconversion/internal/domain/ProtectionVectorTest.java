package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.listingconversion.NodeVerdict;
import com.mimococo.marketops.listingconversion.ProtectionVerdict;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Five protections, each answered on its own; one FAIL fails the vector, one gap leaves it undetermined. */
class ProtectionVectorTest {

    private static Map<String, ProtectionVerdict> allPass() {
        Map<String, ProtectionVerdict> vector = new HashMap<>();
        ProtectionVector.REQUIRED.forEach(key -> vector.put(key, ProtectionVerdict.PASS));
        return vector;
    }

    @Test
    @DisplayName("TC-LC-P01 a comparison with a missing side is undetermined, never a pass")
    void missingSideIsUndetermined() {
        assertThat(ProtectionVector.compare(null, BigDecimal.ONE, true)).isEqualTo(ProtectionVerdict.UNDETERMINED);
        assertThat(ProtectionVector.compare(BigDecimal.ONE, null, true)).isEqualTo(ProtectionVerdict.UNDETERMINED);
    }

    @Test
    @DisplayName("TC-LC-P02 the direction of worse is explicit per protection")
    void directionIsExplicit() {
        assertThat(ProtectionVector.compare(new BigDecimal("0.30"), new BigDecimal("0.25"), true))
                .isEqualTo(ProtectionVerdict.FAIL);
        assertThat(ProtectionVector.compare(new BigDecimal("0.25"), new BigDecimal("0.25"), true))
                .isEqualTo(ProtectionVerdict.PASS);
        assertThat(ProtectionVector.compare(new BigDecimal("900"), new BigDecimal("1000"), false))
                .isEqualTo(ProtectionVerdict.FAIL);
        assertThat(ProtectionVector.compare(new BigDecimal("1000"), new BigDecimal("1000"), false))
                .isEqualTo(ProtectionVerdict.PASS);
    }

    @Test
    @DisplayName("TC-LC-P03 the vector fails on one failure and is undetermined on one gap")
    void vectorVerdict() {
        assertThat(ProtectionVector.verdictOf(allPass())).isEqualTo(ProtectionVerdict.PASS);
        Map<String, ProtectionVerdict> oneFail = allPass();
        oneFail.put("SUPPLY_COVERAGE", ProtectionVerdict.FAIL);
        oneFail.put("OVERALL_RETURN_RATE", ProtectionVerdict.UNDETERMINED);
        assertThat(ProtectionVector.verdictOf(oneFail)).isEqualTo(ProtectionVerdict.FAIL);
        Map<String, ProtectionVerdict> oneGap = allPass();
        oneGap.put("LINKED_SCOPE_PROFIT", ProtectionVerdict.UNDETERMINED);
        assertThat(ProtectionVector.verdictOf(oneGap)).isEqualTo(ProtectionVerdict.UNDETERMINED);
        Map<String, ProtectionVerdict> missingKey = allPass();
        missingKey.remove("CRITICAL_VARIANT_RETURN");
        assertThat(ProtectionVector.verdictOf(missingKey)).isEqualTo(ProtectionVerdict.UNDETERMINED);
        missingKey.put("SUPPLY_COVERAGE",ProtectionVerdict.FAIL);
        assertThat(ProtectionVector.verdictOf(missingKey)).isEqualTo(ProtectionVerdict.FAIL);
        var nullValue=allPass();
        nullValue.put("SUPPLY_COVERAGE",null);
        assertThat(ProtectionVector.verdictOf(nullValue)).isEqualTo(ProtectionVerdict.UNDETERMINED);
    }

    @Test
    @DisplayName("TC-LC-P04 a node is met by its conservative bound, and only after maturity")
    void nodeVerdictUsesTheConservativeBound() {
        assertThat(ProtectionVector.nodeVerdict(new BigDecimal("0.05"), new BigDecimal("0.04"),
                new BigDecimal("0.04"), true)).isEqualTo(NodeVerdict.MET);
        assertThat(ProtectionVector.nodeVerdict(new BigDecimal("0.05"), new BigDecimal("0.039"),
                new BigDecimal("0.04"), true)).isEqualTo(NodeVerdict.NOT_MET);
        assertThat(ProtectionVector.nodeVerdict(new BigDecimal("0.05"), new BigDecimal("0.04"),
                new BigDecimal("0.04"), false)).isEqualTo(NodeVerdict.UNDETERMINED);
        assertThat(ProtectionVector.nodeVerdict(null, new BigDecimal("0.04"), new BigDecimal("0.04"), true))
                .isEqualTo(NodeVerdict.UNDETERMINED);
        assertThat(ProtectionVector.nodeVerdict(new BigDecimal("0.05"), new BigDecimal("0.04"), null, true))
                .isEqualTo(NodeVerdict.UNDETERMINED);
    }

    @Test
    @DisplayName("TC-LC-P05 a missed lower bound does not establish futility; a qualified upper bound may")
    void stopRuleIsNarrow() {
        BigDecimal minimum = new BigDecimal("0.05");
        assertThat(ProtectionVector.nodeVerdict(new BigDecimal("0.06"),new BigDecimal("0.01"),minimum,true))
                .isEqualTo(NodeVerdict.NOT_MET);
        assertThat(ProtectionVector.stopTriggered(new BigDecimal("0.20"),minimum,true,true,true)).isFalse();
        assertThat(ProtectionVector.stopTriggered(null,minimum,true,true,true)).isFalse();
        assertThat(ProtectionVector.stopTriggered(new BigDecimal("0.03"),minimum,true,true,true)).isTrue();
        assertThat(ProtectionVector.stopTriggered(new BigDecimal("0.03"),minimum,false,true,true)).isFalse();
        assertThat(ProtectionVector.stopTriggered(new BigDecimal("0.03"),minimum,true,false,true)).isFalse();
        assertThat(ProtectionVector.stopTriggered(new BigDecimal("0.03"),minimum,true,true,false)).isFalse();
        assertThat(ProtectionVector.stopTriggered(minimum,minimum,true,true,true)).isFalse();
        assertThat(ProtectionVector.toStrings(Map.of("SUPPLY_COVERAGE", ProtectionVerdict.FAIL)))
                .containsEntry("SUPPLY_COVERAGE", "FAIL");
    }
}
