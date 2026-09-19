package com.mimococo.marketops.listingconversion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProtectionScopeBasisTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void anEvidenceDefinedEmptyScopeIsDifferentFromAnAbsentScope() {
        assertThat(ProtectionScopeBasis.parse(null,List.of())).isEmpty();
        var value=json.valueToTree(Map.of("evidenceReference","evidence://fixture/no-additional-scope",
                "linkedProfitScopes",List.of(),"criticalReturnVariantIds",List.of()));
        assertThat(ProtectionScopeBasis.parse(value,List.of())).isPresent();
    }
    @Test void duplicateAndForeignCriticalMembersCannotBecomeAQualifiedScope() {
        UUID direct=UUID.randomUUID();
        for (var members:List.of(List.of(direct.toString(),direct.toString()),List.of(UUID.randomUUID().toString()))) {
            var value=json.valueToTree(Map.of("evidenceReference","evidence://fixture/scope",
                    "linkedProfitScopes",List.of(),"criticalReturnVariantIds",members));
            assertThat(ProtectionScopeBasis.parse(value,List.of(direct))).isEmpty();
        }
    }
}
