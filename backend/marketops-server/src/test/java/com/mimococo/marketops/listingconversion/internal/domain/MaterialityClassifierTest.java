package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.listingconversion.MaterialityRoute;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Materiality is decided per axis from published triggers, and never guessed. */
class MaterialityClassifierTest {

    private static final MaterialityClassifier.Triggers TRIGGERS = new MaterialityClassifier.Triggers(
            new BigDecimal("0.10"), new BigDecimal("0.40"), new BigDecimal("0.05"), new BigDecimal("0.20"));

    @Test
    @DisplayName("TC-LC-MAT-01 either axis at its material trigger makes the action material")
    void eitherAxisMakesItMaterial() {
        var content = MaterialityClassifier.classify(TRIGGERS, new BigDecimal("0.40"), new BigDecimal("0.01"));
        var exposure = MaterialityClassifier.classify(TRIGGERS, new BigDecimal("0.01"), new BigDecimal("0.25"));

        assertThat(content.route()).isEqualTo(MaterialityRoute.MATERIAL_IMPACT);
        assertThat(content.contentAxisMaterial()).isTrue();
        assertThat(content.exposureAxisMaterial()).isFalse();
        assertThat(exposure.route()).isEqualTo(MaterialityRoute.MATERIAL_IMPACT);
        assertThat(exposure.exposureAxisMaterial()).isTrue();
    }

    @Test
    @DisplayName("TC-LC-MAT-02 below both triggers the route is ordinary")
    void belowBothTriggersIsOrdinary() {
        var result = MaterialityClassifier.classify(TRIGGERS, new BigDecimal("0.39"), new BigDecimal("0.19"));

        assertThat(result.route()).isEqualTo(MaterialityRoute.ORDINARY_IMPACT);
        assertThat(result.contentAxisMaterial()).isFalse();
        assertThat(result.exposureAxisMaterial()).isFalse();
    }

    @Test
    @DisplayName("TC-LC-MAT-03 a missing trigger or a missing share leaves materiality unresolved")
    void missingInputLeavesItUnresolved() {
        var incomplete = new MaterialityClassifier.Triggers(new BigDecimal("0.10"), null,
                new BigDecimal("0.05"), new BigDecimal("0.20"));

        assertThat(MaterialityClassifier.classify(incomplete, BigDecimal.ONE, BigDecimal.ONE).route())
                .isEqualTo(MaterialityRoute.MATERIALITY_UNRESOLVED);
        assertThat(MaterialityClassifier.classify(TRIGGERS, null, BigDecimal.ONE).route())
                .isEqualTo(MaterialityRoute.MATERIALITY_UNRESOLVED);
        assertThat(MaterialityClassifier.classify(TRIGGERS, BigDecimal.ONE, null).contentAxisMaterial()).isNull();
        assertThat(MaterialityClassifier.classify(null, BigDecimal.ONE, BigDecimal.ONE).route())
                .isEqualTo(MaterialityRoute.MATERIALITY_UNRESOLVED);
    }

    @Test
    @DisplayName("TC-LC-MAT-04 the content change share is conservative and bounded to 0..1")
    void contentChangeShareIsConservative() {
        assertThat(MaterialityClassifier.contentChangeShare("same", "same")).isEqualByComparingTo("0");
        assertThat(MaterialityClassifier.contentChangeShare("", "")).isEqualByComparingTo("0");
        assertThat(MaterialityClassifier.contentChangeShare("abc", "xyz")).isEqualByComparingTo("1");
        assertThat(MaterialityClassifier.contentChangeShare("abc", "abcd")).isEqualByComparingTo("0.250000");
        assertThat(MaterialityClassifier.contentChangeShare("hello world", "hello there"))
                .isEqualByComparingTo("0.454546");
        assertThat(MaterialityClassifier.contentChangeShare(null, "x")).isNull();
    }
}
