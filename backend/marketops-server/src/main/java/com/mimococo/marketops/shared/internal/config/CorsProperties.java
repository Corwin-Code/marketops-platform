package com.mimococo.marketops.shared.internal.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Browser origins permitted to read the local metadata API.
 *
 * <p>The base profile leaves the list empty. Environment profiles may select
 * only the two loopback origins used by the development and preview servers;
 * binding fails when any other origin is configured.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.web.cors")
public final class CorsProperties {

    private static final String LOCAL_CONSOLE_ORIGIN =
            "http://127\\.0\\.0\\.1:(?:5173|4173)";

    @NotNull
    @Size(max = 2)
    private List<@Pattern(regexp = LOCAL_CONSOLE_ORIGIN) String> allowedOrigins = List.of();

    /** Origins that may read the API, or an empty list when CORS is disabled. */
    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    /** Replaces the origin allowlist with an immutable copy. */
    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }
}
