package com.mimococo.marketops.aicopilot.internal.config;

import com.mimococo.marketops.aicopilot.adapter.http.HttpModelGateway;
import com.mimococo.marketops.aicopilot.internal.infrastructure.jdbc.AiRepository;
import com.mimococo.marketops.aicopilot.port.ModelGatewayPort;
import com.mimococo.marketops.shared.port.SecretResolverPort;
import com.mimococo.marketops.shared.port.OutboundHttp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the single outbound model doorway.
 *
 * <p>One gateway exists in the running application. Provider differences are
 * recorded facts rather than separate implementations, so there is no dispatcher
 * to get wrong and no second adapter that could reach a provider by another
 * route.
 */
@Configuration
public class ModelGatewayConfiguration {

    /** How long the client waits to establish a connection to a provider. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);


    /** The single outbound model gateway. */
    @Bean
    public ModelGatewayPort modelGatewayPort(OutboundHttp modelGatewayHttpClient,
                                             AiRepository aiRepository,
                                             SecretResolverPort secretResolverPort,
                                             ObjectMapper objectMapper,
                                             Clock clock) {
        return new HttpModelGateway(modelGatewayHttpClient, aiRepository, secretResolverPort,
                objectMapper, clock);
    }
}
