package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Asserts the configuration decisions that carry a security consequence.
 *
 * <p>These properties are the difference between an unauthenticated resource
 * reachable only from the workstation and one reachable from the network, so
 * they are verified as data rather than left to review.
 */
class ApplicationConfigurationTest {

    /** Variables whose values exist only in the ignored local file. */
    private static final List<String> SECRET_VARIABLES = List.of(
            "MARKETOPS_DB_APP_PASSWORD",
            "MARKETOPS_DB_MIGRATION_PASSWORD",
            "MARKETOPS_POSTGRES_SUPERUSER_PASSWORD");

    /** Character that turns a variable name into a value assignment. */
    private static final String ASSIGNMENT = "=";

    private final PropertySource<?> base = load("application.yaml");

    @Test
    @DisplayName("the server listens on loopback only")
    void serverBindsToLoopback() {
        assertThat(base.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("the base profile enables no cross-origin access")
    void baseProfileHasNoCorsOrigins() {
        assertThat(base.getProperty("marketops.web.cors.allowed-origins")).isNull();
    }

    @Test
    @DisplayName("local profiles name only the two loopback console origins")
    void localProfilesUseTheFiniteCorsAllowlist() {
        String expected = "http://127.0.0.1:5173,http://127.0.0.1:4173";
        assertThat(load("application-local.yaml").getProperty("marketops.web.cors.allowed-origins"))
                .isEqualTo(expected);
        assertThat(load("application-ci.yaml").getProperty("marketops.web.cors.allowed-origins"))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("only health and info are reachable over HTTP")
    void managementSurfaceIsAnAllowlist() {
        assertThat(base.getProperty("management.endpoints.access.default")).isEqualTo("none");
        assertThat(base.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info");
    }

    @Test
    @DisplayName("health never reports component detail")
    void healthDetailIsWithheld() {
        assertThat(base.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
        assertThat(base.getProperty("management.endpoint.health.show-components")).isEqualTo("never");
    }

    @Test
    @DisplayName("readiness includes the datasource and liveness does not")
    void probesSeparateReadinessFromLiveness() {
        assertThat(base.getProperty("management.endpoint.health.group.readiness.include"))
                .isEqualTo("readinessState,db");
        assertThat(base.getProperty("management.endpoint.health.group.liveness.include"))
                .isEqualTo("livenessState");
    }

    @Test
    @DisplayName("info never exposes the environment")
    void infoDoesNotExposeTheEnvironment() {
        assertThat(base.getProperty("management.info.env.enabled")).isEqualTo(false);
        assertThat(base.getProperty("management.info.build.enabled")).isEqualTo(true);
    }

    @Test
    @DisplayName("migration runs under the owning role and the application under its own")
    void rolesAreSeparated() {
        assertThat(base.getProperty("spring.datasource.username")).isEqualTo("marketops_app");
        assertThat(base.getProperty("spring.flyway.user")).isEqualTo("marketops_migration");
        assertThat(String.valueOf(base.getProperty("spring.datasource.password")))
                .isEqualTo("${MARKETOPS_DB_APP_PASSWORD}");
        assertThat(String.valueOf(base.getProperty("spring.flyway.password")))
                .isEqualTo("${MARKETOPS_DB_MIGRATION_PASSWORD}");
    }

    @Test
    @DisplayName("database acquisition fails fast enough for metadata to report an outage")
    void databaseTimeoutFitsInsideTheConsoleRequestWindow() {
        assertThat(base.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo(1000);
        assertThat(base.getProperty("spring.datasource.hikari.validation-timeout")).isEqualTo(750);
    }

    @Test
    @DisplayName("no password is written into the checked-in configuration")
    void noLiteralPasswordIsPresent() throws IOException {
        for (String resource : List.of("application.yaml", "application-local.yaml", "application-ci.yaml")) {
            String text = new ClassPathResource(resource)
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            for (String variable : SECRET_VARIABLES) {
                // A name followed by an assignment is a value; a name inside a
                // placeholder is a reference. Only the second belongs in a file
                // that is committed.
                assertThat(text)
                        .as("%s must reference %s rather than assign it", resource, variable)
                        .doesNotContain(variable + ASSIGNMENT);
            }
        }
    }

    @Test
    @DisplayName("schema destruction is disabled and history is validated")
    void migrationSafetySwitchesAreSet() {
        assertThat(base.getProperty("spring.flyway.clean-disabled")).isEqualTo(true);
        assertThat(base.getProperty("spring.flyway.validate-on-migrate")).isEqualTo(true);
        assertThat(base.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo(false);
        assertThat(base.getProperty("spring.flyway.out-of-order")).isEqualTo(false);
        assertThat(base.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
    }

    @Test
    @DisplayName("an unprofiled start does not silently activate a profile file")
    void defaultProfileOwnsNoFile() {
        assertThat(base.getProperty("spring.profiles.default")).isEqualTo("none");
        assertThat(new ClassPathResource("application-none.yaml").exists()).isFalse();
        assertThat(new ClassPathResource("application-default.yaml").exists()).isFalse();
    }

    @Test
    @DisplayName("every log record can be traced to the request that produced it")
    void logRecordsCarryTheCorrelationIdentifier() {
        assertThat(String.valueOf(base.getProperty("logging.pattern.correlation")))
                .contains("correlationId");
    }

    @Test
    @DisplayName("each profile names the environment it configures")
    void profilesNameTheirEnvironment() {
        assertThat(base.getProperty("marketops.environment")).isEqualTo("unspecified");
        assertThat(load("application-local.yaml").getProperty("marketops.environment"))
                .isEqualTo("local");
        assertThat(load("application-ci.yaml").getProperty("marketops.environment")).isEqualTo("ci");
    }

    private static PropertySource<?> load(String resource) {
        try {
            List<PropertySource<?>> sources =
                    new YamlPropertySourceLoader().load(resource, new ClassPathResource(resource));
            assertThat(sources).as("%s must contain exactly one document", resource).hasSize(1);
            return sources.get(0);
        } catch (IOException exception) {
            throw new IllegalStateException("configuration resource " + resource + " is unreadable", exception);
        }
    }
}
