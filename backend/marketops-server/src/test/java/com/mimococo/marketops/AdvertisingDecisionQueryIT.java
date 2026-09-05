package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.operationsworkflow.AdvertisingDecisionAuthority;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The advertising decision-resolution queries, executed against the real schema.
 *
 * <p>Three long queries decide what the console shows before somebody approves a
 * real bid change on a real marketplace: what the decision consists of, what the
 * case says about it, and which exposure axes are already spent. None of them
 * can be type-checked without a server, and a misspelt column in any of them
 * would surface as an empty preview rather than an error.
 *
 * <p>Nothing is seeded. An empty database is the strongest statement of the
 * default: no decision resolves, every reason is unresolved, and no axis of an
 * envelope that does not exist can be anything other than unresolved.
 */
@SpringBootTest
@ActiveProfiles("ci")
class AdvertisingDecisionQueryIT {

    @Autowired
    private AdvertisingDecisionAuthority advertising;

    @Autowired
    private com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc
            .AdvertisingDecisionRepository decisions;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        var container = TestDatabase.container();
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase::applicationRole);
        registry.add("spring.datasource.password", TestDatabase::applicationPassword);
        registry.add("spring.flyway.user", TestDatabase::migrationRole);
        registry.add("spring.flyway.password", TestDatabase::migrationPassword);
    }

    @Test
    @DisplayName("TC-AD-DECISION-001 a recommendation that does not exist resolves to nothing")
    void unknownRecommendationResolvesToNothing() {
        UUID absent = UUID.randomUUID();

        assertThat(advertising.decisionScope(absent)).isEmpty();
        assertThat(advertising.bidProjection(absent)).isEmpty();
        assertThat(advertising.unresolvedReasons(absent))
                .containsExactly("NOT_AN_ADVERTISING_BID_CHANGE");
    }

    @Test
    @DisplayName("TC-AD-DECISION-002 with no envelope in force, exposure is unresolved rather than open")
    void absentEnvelopeIsUnresolvedNotPermissive() {
        // The failure mode this rules out: an organization with no envelope
        // configured being treated as an organization with unlimited headroom.
        for (String direction : new String[] {"PROTECTION_DECREASE", "OPTIMIZATION_INCREASE",
                "EXACT_PRIOR_BID_COMPENSATION"}) {
            assertThat(decisions.exhaustedExposureAxes(UUID.randomUUID(), direction))
                    .describedAs("%s", direction)
                    .containsExactly("AGGREGATE_ENVELOPE_UNRESOLVED");
        }
    }

    @Test
    @DisplayName("TC-AD-DECISION-003 with no materiality policy, no route resolves")
    void absentMaterialityPolicyResolvesToNoRoute() {
        assertThat(decisions.materialityRoute(UUID.randomUUID(), new BigDecimal("1.0000")))
                .isEqualTo("MATERIALITY_UNRESOLVED");
        assertThat(decisions.materialityRoute(UUID.randomUUID(), BigDecimal.ZERO))
                .isEqualTo("MATERIALITY_UNRESOLVED");
    }

    @Test
    @DisplayName("TC-AD-DECISION-004 bundle ambiguity is asked without raising on an absent decision")
    void bundleAmbiguityIsFalseWhenNothingClaimsTheScope() {
        assertThat(decisions.bundleIsAmbiguous(UUID.randomUUID())).isFalse();
    }
}
