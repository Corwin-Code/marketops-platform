package com.mimococo.marketops.marketplaceintegration.internal.config;

import com.mimococo.marketops.marketplaceintegration.adapter.http.PlatformHttpContentWriteAdapter;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ContentOperationRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.port.ContentWritePort;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/** Wires the one doorway a listing title and description change leaves through (W2). */
@Configuration
@EnableConfigurationProperties(ContentWriteProperties.class)
public class ContentWriteRuntimeConfiguration {

    /** The adapter that performs a recorded, verified content operation. */
    @Bean
    public ContentWritePort contentWritePort(OutboundHttp httpClient,
                                             ContentOperationRepository operations,
                                             PlatformCallSpecRepository specs,
                                             SecretResolverPort secretResolverPort,
                                             ObjectMapper objectMapper,
                                             Clock clock) {
        return new PlatformHttpContentWriteAdapter(httpClient, operations, specs, secretResolverPort,
                objectMapper, clock);
    }

    /** Enables scheduled execution only where the content worker is switched on. */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "marketops.content-write", name = "worker-enabled", havingValue = "true")
    static class SchedulingConfiguration {
    }
}
