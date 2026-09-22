package com.mimococo.marketops.marketplaceintegration.internal.config;

import com.mimococo.marketops.marketplaceintegration.adapter.http.PlatformHttpDescriptionWriteAdapter;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.DescriptionWritePort;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ListingDescriptionWriteProperties.class)
public class ListingDescriptionWriteRuntimeConfiguration {

    @Bean
    DescriptionWritePort descriptionWritePort(WriteOperationRepository operations,
                                              PlatformCallSpecRepository specs,
                                              SecretResolverPort secrets,
                                              OutboundHttp http,
                                              Clock clock) {
        return new PlatformHttpDescriptionWriteAdapter(operations, specs, secrets, http, clock);
    }
}
