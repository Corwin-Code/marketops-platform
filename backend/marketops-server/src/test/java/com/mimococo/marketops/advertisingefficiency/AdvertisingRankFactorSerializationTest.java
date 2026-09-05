package com.mimococo.marketops.advertisingefficiency;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.advertisingefficiency.internal.domain.AdPriorityPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AdvertisingRankFactorSerializationTest {
    @Test void missingPolicySerializesItsReasonAndNullValueThroughThePublicView() {
        var factor=AdPriorityPolicy.unranked(AdvertisingLane.PROTECTION,ProtectionTier.P0).factors().getFirst();
        var view=new AdvertisingRankFactorView(factor.code().name(),factor.value(),factor.weight(),factor.contribution(),factor.displayNote());
        var mapper=JsonMapper.builder().build();
        var tree=mapper.readTree(mapper.writeValueAsString(view));
        // The existing console parser reads factorCode/displayNote and preserves
        // a null numeric value as unresolved. No new DTO field or guessed zero.
        assertThat(tree.get("factorCode").asText()).isEqualTo("EVIDENCE_MATURITY");
        assertThat(tree.get("value").isNull()).isTrue();
        assertThat(tree.get("displayNote").asText()).isEqualTo("PRIORITY_POLICY_UNRESOLVED:PROFILE");
        assertThat(view.contribution()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
