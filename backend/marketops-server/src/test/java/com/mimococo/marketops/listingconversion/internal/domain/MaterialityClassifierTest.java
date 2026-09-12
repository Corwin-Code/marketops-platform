package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import com.mimococo.marketops.listingconversion.MaterialityRoute;
import org.junit.jupiter.api.Test;

class MaterialityClassifierTest {
    @Test void eitherKnownMaterialAxisRequiresTheOwner() {
        for (Boolean other:new Boolean[]{false,true,null}) {
            assertThat(MaterialityClassifier.classify(true,other).route()).isEqualTo(MaterialityRoute.MATERIAL_IMPACT);
            assertThat(MaterialityClassifier.classify(other,true).route()).isEqualTo(MaterialityRoute.MATERIAL_IMPACT);
        }
    }
    @Test void ordinaryRequiresBothAxesKnownAndOrdinary() {
        assertThat(MaterialityClassifier.classify(false,false).route()).isEqualTo(MaterialityRoute.ORDINARY_IMPACT);
        assertThat(MaterialityClassifier.classify(null,false).route()).isEqualTo(MaterialityRoute.MATERIALITY_UNRESOLVED);
        assertThat(MaterialityClassifier.classify(false,null).route()).isEqualTo(MaterialityRoute.MATERIALITY_UNRESOLVED);
        assertThat(MaterialityClassifier.classify(null,null).route()).isEqualTo(MaterialityRoute.MATERIALITY_UNRESOLVED);
    }
    @Test void unknownMeaningIsRetainedEvenWhenExposureAlreadyRequiresTheOwner() {
        var classification=MaterialityClassifier.classify(null,true);
        assertThat(classification.contentAxisMaterial()).isNull();
        assertThat(classification.exposureAxisMaterial()).isTrue();
    }
}
