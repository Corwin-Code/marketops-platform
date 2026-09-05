package com.mimococo.marketops.marketplaceintegration.internal.config;

import com.mimococo.marketops.marketplaceintegration.adapter.http.PlatformHttpAdBidWriteAdapter;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.WriteOperationRepository;
import com.mimococo.marketops.marketplaceintegration.port.AdBidWritePort;
import com.mimococo.marketops.shared.port.OutboundHttp;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the advertising write path.
 *
 * <p>The adapter is constructed rather than component-scanned so it is obvious
 * from one place what it depends on, and so a test can supply a different port
 * without the scan finding two.
 *
 * <p>Declaring the bean does not enable anything. The adapter refuses before any
 * socket unless a verified operation and a verified advertising auth header both
 * exist, and neither does.
 */
@Configuration
@EnableConfigurationProperties(AdBidWriteProperties.class)
public class AdBidWriteRuntimeConfiguration {

    @Bean
    AdBidWritePort adBidWritePort(
            WriteOperationRepository operations,
            PlatformCallSpecRepository specs,
            SecretResolverPort secrets,
            OutboundHttp http,
            Clock clock) {
        return new PlatformHttpAdBidWriteAdapter(operations, specs, secrets, http, clock);
    }
}
