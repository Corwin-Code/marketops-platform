package com.mimococo.marketops.shared.internal.config;

import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Cross-cutting technical beans.
 *
 * <p>The clock and the identifier source are beans so time- and
 * identity-dependent behaviour can be exercised deterministically in a test
 * rather than depending on the wall clock or a process-global generator.
 */
@Configuration
@EnableConfigurationProperties({
        CorsProperties.class,
        MetadataMaintenanceProperties.class,
        ProductionWriteProperties.class})
public class WebConfig {

    /** UTC clock used wherever the application reports a time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Random identifier source used wherever the application creates identity. */
    @Bean
    public IdGenerator idGenerator() {
        return UUID::randomUUID;
    }

    /**
     * CORS policy for the local console origins explicitly named by the active profile.
     *
     * <p>When the origin list is empty, no URL pattern is registered and the
     * backend emits no CORS response headers. This is the base-profile posture,
     * and it is also the production posture: the console and the API are served
     * from one origin there, so a cross-origin policy would permit something
     * the deployment does not need.
     *
     * <p>Two policies are registered, and they differ because the two surfaces
     * differ. The metadata resource is read without a credential, so its policy
     * allows no credential-bearing header and no mutating method. The console
     * API carries a bearer token and performs operating commands, so its policy
     * admits the authorization header and the mutating methods it uses — and
     * nothing else.
     *
     * <p>The maintenance surface under {@code /api/v1/admin/metadata} is
     * deliberately outside every registration: no browser origin can invoke it
     * cross-origin, and no console reads it.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (properties.getAllowedOrigins().isEmpty()) {
            return source;
        }

        CorsConfiguration policy = new CorsConfiguration();
        policy.setAllowedOrigins(properties.getAllowedOrigins());
        policy.setAllowedMethods(List.of("GET", "OPTIONS"));
        policy.setAllowedHeaders(List.of("Accept", CorrelationId.HEADER_NAME));
        policy.setExposedHeaders(List.of(CorrelationId.HEADER_NAME));
        policy.setAllowCredentials(false);
        policy.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/v1/meta/**", policy);

        CorsConfiguration consolePolicy = new CorsConfiguration();
        consolePolicy.setAllowedOrigins(properties.getAllowedOrigins());
        consolePolicy.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        consolePolicy.setAllowedHeaders(List.of(
                "Accept", "Content-Type", HttpHeaders.AUTHORIZATION, CorrelationId.HEADER_NAME, "Idempotency-Key"));
        consolePolicy.setExposedHeaders(List.of(CorrelationId.HEADER_NAME));
        // The token travels in the authorization header, never in a cookie, so
        // credentialed requests stay off and a browser cannot be induced to
        // attach ambient authority to a cross-origin call.
        consolePolicy.setAllowCredentials(false);
        consolePolicy.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/v1/console/**", consolePolicy);
        return source;
    }

    /**
     * The finite CORS policy, applied ahead of every other filter.
     *
     * <p>Ordering is part of the contract. A preflight request carries no
     * credential by definition, so it has to be answered before the security
     * chain evaluates authentication; a policy filter that ran afterwards would
     * turn every cross-origin console request into a refused preflight.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(
            CorsConfigurationSource corsConfigurationSource) {
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(corsFilter(corsConfigurationSource));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** The policy filter itself, separated so its behaviour can be exercised directly. */
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }
}
