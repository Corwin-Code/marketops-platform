package com.mimococo.marketops.shared.internal.config;

import java.time.Clock;
import java.util.List;
import com.mimococo.marketops.shared.CorrelationId;
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
 * <p>The clock is a bean so time-dependent behaviour can be exercised
 * deterministically in a test rather than depending on the wall clock.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig {

    /** UTC clock used wherever the application reports a time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * CORS policy for the local console origins explicitly named by the active profile.
     *
     * <p>When the origin list is empty, no URL pattern is registered and the
     * backend emits no CORS response headers. This is the base-profile posture.
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
        source.registerCorsConfiguration("/api/**", policy);
        return source;
    }

    /** Applies the finite CORS policy before a request reaches Spring MVC. */
    @Bean
    public CorsFilter corsFilter(CorsConfigurationSource corsConfigurationSource) {
        return new CorsFilter(corsConfigurationSource);
    }
}
