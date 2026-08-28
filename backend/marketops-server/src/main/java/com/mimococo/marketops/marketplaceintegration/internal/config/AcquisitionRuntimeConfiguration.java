package com.mimococo.marketops.marketplaceintegration.internal.config;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.JdbcAuthorizedAcquisitionGateway;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the acquisition authority chain and, when an environment asks for it,
 * the scheduler that drives it.
 *
 * <p>Scheduling is enabled by a nested configuration that exists only when the
 * switch is on. Turning scheduling on for the whole application would start
 * every scheduled method in the process, including ones added later for other
 * reasons, so the switch controls exactly what it claims to control.
 */
@Configuration
@EnableConfigurationProperties(AcquisitionProperties.class)
public class AcquisitionRuntimeConfiguration {

    /**
     * The sole chain from the database grant primitive to one acquisition call.
     *
     * <p>It is constructed rather than component-scanned so its two collaborators
     * stay package-private and unreachable: the mapper and the executor are
     * created inside the gateway and are not beans anybody could inject.
     */
    @Bean
    public JdbcAuthorizedAcquisitionGateway authorizedAcquisitionGateway(
            DataSource dataSource, AcquisitionPort acquisitionPort) {
        return new JdbcAuthorizedAcquisitionGateway(dataSource, acquisitionPort);
    }

    /** Enables scheduled execution only where acquisition is switched on. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "marketops.acquisition", name = "scheduler-enabled",
            havingValue = "true")
    static class SchedulingConfiguration {
    }
}
