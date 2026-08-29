package com.mimococo.marketops.identityaccess.internal.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdentityConfigurationContractTest {

    @Test
    void blankServingAudienceFailsStartupContract() {
        IdentityProperties properties = servingProperties();
        properties.setAudience("   ");

        assertThatThrownBy(() -> new IdentityConfigurationContract("staging", properties)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void oversizedServingAudienceFailsStartupContract() {
        IdentityProperties properties = servingProperties();
        properties.setAudience("x".repeat(256));

        assertThatThrownBy(() -> new IdentityConfigurationContract("production", properties)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void localAndCiRemainExplicitlyExempt() {
        for (String environment : java.util.List.of("local", "ci")) {
            assertThatCode(() -> new IdentityConfigurationContract(environment,
                    new IdentityProperties()).afterPropertiesSet()).doesNotThrowAnyException();
        }
    }

    private static IdentityProperties servingProperties() {
        IdentityProperties properties = new IdentityProperties();
        properties.setIssuerUri("https://identity.example.test/marketops");
        return properties;
    }
}
