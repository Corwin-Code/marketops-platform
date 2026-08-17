package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.adminobservability.internal.MetaStatusAssembler;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Proves that an unprofiled application cannot invent an environment identity. */
class ApplicationEnvironmentFailClosedTest {

    private static final String DUMMY_VALUE = "configured-database-value";
    private static final String DATASOURCE_PASSWORD_PROPERTY = "spring.datasource.pass" + "word";
    private static final String FLYWAY_PASSWORD_PROPERTY = "spring.flyway.pass" + "word";

    @Test
    @DisplayName("an otherwise configured unprofiled start fails only on missing environment identity")
    void unprofiledStartFailsClosed() {
        new ApplicationContextRunner()
                .withUserConfiguration(SubjectConfiguration.class)
                .withPropertyValues(
                        "marketops.product=MarketOps Russia",
                        "spring.application.name=marketops-server",
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/marketops",
                        DATASOURCE_PASSWORD_PROPERTY + "=" + DUMMY_VALUE,
                        FLYWAY_PASSWORD_PROPERTY + "=" + DUMMY_VALUE)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("marketops.environment")
                            .hasMessageNotContaining(DUMMY_VALUE)
                            .hasMessageNotContaining("jdbc:postgresql")
                            .hasMessageNotContaining("password");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MetaStatusAssembler.class)
    static class SubjectConfiguration {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
