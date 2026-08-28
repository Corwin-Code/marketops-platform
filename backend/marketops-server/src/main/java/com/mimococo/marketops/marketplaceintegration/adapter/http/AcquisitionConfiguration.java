package com.mimococo.marketops.marketplaceintegration.adapter.http;

import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.PlatformCallSpecRepository;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionPort;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.mimococo.marketops.shared.port.OutboundHttp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single outbound acquisition doorway.
 *
 * <p>Exactly one acquisition port exists in the running application. Platform
 * differences are recorded facts rather than separate implementations, so there
 * is no dispatcher to get wrong and no second adapter that could reach a
 * marketplace by another route.
 */
@Configuration
public class AcquisitionConfiguration {

    /** How long the client waits to establish a connection to a marketplace. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);


    /** The single outbound acquisition port. */
    @Bean
    public AcquisitionPort acquisitionPort(OutboundHttp acquisitionHttpClient,
                                           PlatformCallSpecRepository callSpecs,
                                           SecretResolverPort secretResolverPort,
                                           Clock clock) {
        return new PlatformHttpAcquisitionAdapter(
                acquisitionHttpClient, callSpecs, secretResolverPort, clock);
    }
}
