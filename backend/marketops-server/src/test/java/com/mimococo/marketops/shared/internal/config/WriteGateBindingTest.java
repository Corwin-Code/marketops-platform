package com.mimococo.marketops.shared.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mimococo.marketops.shared.MaintenanceWriteGate;
import com.mimococo.marketops.shared.ProductionWritePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The two write gates as binding contracts.
 *
 * <p>The production-write gate is unoverridable: a configuration that claims
 * production writes are enabled is invalid, so no environment can start a
 * process that believes they are on. The maintenance gate is mandatory and
 * fail-closed: absence is a startup failure, never an implicit default.
 */
class WriteGateBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GateConfiguration.class);

    @Test
    @DisplayName("TC-FF-101 configuring enabled production writes fails startup")
    void enabledProductionWritesFailStartup() {
        runner.withPropertyValues(
                        "marketops.production-writes.enabled=true",
                        "marketops.metadata-maintenance.write-enabled=false")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("marketops.production-writes");
                });
    }

    @Test
    @DisplayName("TC-FF-102 a missing maintenance write switch fails startup")
    void missingMaintenanceSwitchFailsStartup() {
        runner.withPropertyValues("marketops.production-writes.enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("TC-FF-103 the disabled posture binds and both gates read closed")
    void disabledPostureBindsClosed() {
        runner.withPropertyValues(
                        "marketops.production-writes.enabled=false",
                        "marketops.metadata-maintenance.write-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProductionWritePolicy.class)
                            .productionWritesEnabled()).isFalse();
                    assertThat(context.getBean(MaintenanceWriteGate.class)
                            .writeEnabled()).isFalse();
                });
    }

    @Test
    @DisplayName("TC-FF-104 an opted-in environment accepts maintenance writes only")
    void optedInEnvironmentOpensTheMaintenanceGateOnly() {
        runner.withPropertyValues(
                        "marketops.production-writes.enabled=false",
                        "marketops.metadata-maintenance.write-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(MaintenanceWriteGate.class)
                            .writeEnabled()).isTrue();
                    assertThat(context.getBean(ProductionWritePolicy.class)
                            .productionWritesEnabled()).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
            ProductionWriteProperties.class,
            MetadataMaintenanceProperties.class})
    @Import({ProductionWritePolicy.class, MaintenanceWriteGate.class})
    static class GateConfiguration {
    }
}
