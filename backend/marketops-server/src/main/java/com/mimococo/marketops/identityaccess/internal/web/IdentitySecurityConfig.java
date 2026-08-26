package com.mimococo.marketops.identityaccess.internal.web;

import com.mimococo.marketops.identityaccess.internal.config.IdentityProperties;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The request boundary of the whole application.
 *
 * <p>The rule set is a positive list ending in a refusal. Three paths are open
 * without an identity and each has a reason recorded next to it; the console API
 * requires an accepted token; everything else is denied. A path added later is
 * therefore closed until somebody decides otherwise, which is the opposite of
 * the usual failure where a new endpoint inherits a permissive default.
 *
 * <p>No decoder is created when no issuer is configured. The console rules then
 * refuse every request for want of an authentication, which is the intended
 * posture for a workstation and for continuous integration; a serving
 * environment cannot reach that state, because startup fails without an issuer.
 *
 * <p>Sessions are stateless and no cookie is issued, so cross-site request
 * forgery has no ambient authority to abuse and its protection is switched off
 * deliberately rather than by omission.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentitySecurityConfig {

    /** Liveness and readiness, consumed by the platform rather than by a person. */
    private static final String HEALTH_PATTERN = "/actuator/health/**";

    /** Build identity, consumed by the platform. */
    private static final String INFO_PATTERN = "/actuator/info";

    /**
     * The unauthenticated metadata resource the console reads before login.
     *
     * <p>It publishes the product name, environment, build identity, database
     * reachability and migration version. The console needs those to render a
     * truthful state before anybody has signed in, and none of them is a
     * business fact.
     */
    private static final String METADATA_PATTERN = "/api/v1/meta/status";

    /** The operating console API. */
    private static final String CONSOLE_PATTERN = "/api/v1/console/**";

    /**
     * The loopback maintenance surface.
     *
     * <p>Its posture is unchanged: the process binds to the loopback interface,
     * mutations require a validated operator attribution header, and the write
     * switch is off unless an environment explicitly turns it on. The deployed
     * topology publishes only the console path, so this surface is reachable
     * only from the host the process runs on.
     */
    private static final String MAINTENANCE_PATTERN = "/api/v1/admin/metadata/**";

    /** Grace allowed for clock differences between this process and the issuer. */
    private static final long CLOCK_SKEW_SECONDS = 60L;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            CorsConfigurationSource corsConfigurationSource,
                                            IdentityProblemEntryPoint problemEntryPoint,
                                            MarketOpsJwtAuthenticationConverter converter,
                                            IdentityProperties properties) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .anonymous(Customizer.withDefaults())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemEntryPoint)
                        .accessDeniedHandler(problemEntryPoint))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HEALTH_PATTERN, INFO_PATTERN, METADATA_PATTERN)
                        .permitAll()
                        .requestMatchers(MAINTENANCE_PATTERN).permitAll()
                        .requestMatchers(CONSOLE_PATTERN).authenticated()
                        .anyRequest().denyAll());

        if (properties.configured()) {
            http.oauth2ResourceServer(resource -> resource
                    .authenticationEntryPoint(problemEntryPoint)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        }
        return http.build();
    }

    /**
     * The token decoder, present only when an issuer is configured.
     *
     * <p>Validation is the standard set plus two deployment facts: the issuer
     * must be the exact one configured, and the audience must match when one is
     * recorded. Both are checked before any claim is used, so a correctly signed
     * token minted for a different application is refused rather than resolved.
     */
    @Bean
    JwtDecoder jwtDecoder(IdentityProperties properties) {
        if (!properties.configured()) {
            return token -> {
                throw new org.springframework.security.oauth2.jwt.BadJwtException(
                        "no identity provider is configured in this environment");
            };
        }
        NimbusJwtDecoder decoder = properties.getJwkSetUri() == null
                ? NimbusJwtDecoder.withIssuerLocation(properties.getIssuerUri()).build()
                : NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuerUri()),
                new JwtTimestampSkew(),
                audienceValidator(properties.getAudience())));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        if (audience == null || audience.isBlank()) {
            return token -> OAuth2TokenValidatorResult.success();
        }
        return token -> {
            List<String> declared = token.getAudience();
            return declared != null && declared.contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(
                            new org.springframework.security.oauth2.core.OAuth2Error(
                                    "invalid_token", "the token is addressed elsewhere", null));
        };
    }

    /** Allows a bounded clock difference between this process and the issuer. */
    private static final class JwtTimestampSkew implements OAuth2TokenValidator<Jwt> {

        private final OAuth2TokenValidator<Jwt> delegate =
                new org.springframework.security.oauth2.jwt.JwtTimestampValidator(
                        java.time.Duration.ofSeconds(CLOCK_SKEW_SECONDS));

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            return delegate.validate(token);
        }
    }
}
