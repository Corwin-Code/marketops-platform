package com.mimococo.marketops.shared.internal.config;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
     * backend emits no CORS response headers. This is the base-profile posture.
     *
     * <p>The policy is registered only for the metadata-read route the console
     * consumes. The maintenance surface under {@code /api/v1/admin/metadata} is
     * deliberately outside every CORS registration: no browser origin can
     * invoke it cross-origin, and no console reads it.
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
        return source;
    }

    /** Applies the finite CORS policy before a request reaches Spring MVC. */
    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }
}
