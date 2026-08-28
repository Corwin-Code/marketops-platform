package com.mimococo.marketops.marketplaceintegration.internal.config;

import com.mimococo.marketops.marketplaceintegration.adapter.http.PlatformHttpPriceWriteAdapter;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.PriceWritePort;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.mimococo.marketops.shared.port.OutboundHttp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the one doorway a price change leaves through.
 *
 * <p>The client never follows a redirect. A redirected write would send a
 * credential to a host nobody recorded, and a marketplace that answers a price
 * change with a redirect is a fact worth failing on rather than following.
 *
 * <p>Scheduling is enabled by a nested configuration that exists only when the
 * worker switch is on, so turning the worker on does not start every scheduled
 * method in the process.
 */
@Configuration
@EnableConfigurationProperties(PriceWriteProperties.class)
public class PriceWriteRuntimeConfiguration {

    /** How long the client waits to establish a connection to a marketplace. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);


    /** The adapter that performs a recorded write operation. */
    @Bean
    public PriceWritePort priceWritePort(OutboundHttp priceWriteHttpClient,
                                         WriteOperationRepository operations,
                                         PlatformCallSpecRepository specs,
                                         SecretResolverPort secretResolverPort,
                                         ObjectMapper objectMapper,
                                         Clock clock) {
        return new PlatformHttpPriceWriteAdapter(priceWriteHttpClient, operations, specs,
                secretResolverPort, objectMapper, clock);
    }

    /** Enables scheduled execution only where the price worker is switched on. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "marketops.price-write", name = "worker-enabled",
            havingValue = "true")
    static class SchedulingConfiguration {
    }
}
