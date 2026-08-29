package com.mimococo.marketops.identityaccess.internal.config;

import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How this deployment reaches its external identity provider.
 *
 * <p>Both locations are optional at binding time and absent by default, which
 * is the fail-closed posture: with no issuer configured no token can be
 * validated, so the console API denies every request rather than serving one.
 * A deployment that is meant to serve people is required to configure an issuer
 * by {@link IdentityConfigurationContract}, which fails startup rather than
 * letting an environment run without the boundary it is supposed to have.
 *
 * <p>Neither value is a secret. An issuer identifier and a key-set location are
 * public by design; the private key never leaves the provider, and this
 * application holds no credential for it.
 */
@Validated
@ConfigurationProperties(prefix = "marketops.identity.oidc")
public final class IdentityProperties {

    private static final String HTTPS_LOCATION =
            "https://[a-z0-9][a-z0-9.-]{0,252}(/[A-Za-z0-9._~-]{1,64})*";

    @Pattern(regexp = HTTPS_LOCATION, message = "the issuer must be an https location")
    private String issuerUri;

    @Pattern(regexp = HTTPS_LOCATION, message = "the key set must be an https location")
    private String jwkSetUri;

    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}",
            message = "the audience must be a bounded identifier")
    private String audience;

    private Duration sessionRecordingInterval = Duration.ofMinutes(5);

    /** Issuer identifier tokens must carry, or {@code null} when unconfigured. */
    public String getIssuerUri() {
        return issuerUri;
    }

    /** Bind the issuer identifier. */
    public void setIssuerUri(String issuerUri) {
        this.issuerUri = issuerUri;
    }

    /** Key-set location, or {@code null} to discover it from the issuer. */
    public String getJwkSetUri() {
        return jwkSetUri;
    }

    /** Bind the key-set location. */
    public void setJwkSetUri(String jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
    }

    /** Audience a token must be addressed to, or {@code null} when locally unconfigured. */
    public String getAudience() {
        return audience;
    }

    /** Bind the expected audience. */
    public void setAudience(String audience) {
        this.audience = audience;
    }

    /** How often one person's continued activity is journalled. */
    public Duration getSessionRecordingInterval() {
        return sessionRecordingInterval;
    }

    /** Bind the activity journalling interval. */
    public void setSessionRecordingInterval(Duration sessionRecordingInterval) {
        this.sessionRecordingInterval = sessionRecordingInterval;
    }

    /** Whether an issuer is configured at all. */
    public boolean configured() {
        return issuerUri != null && !issuerUri.isBlank();
    }

    /** Whether a bounded nonblank token audience is configured. */
    public boolean audienceConfigured() {
        return audience != null && audience.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}");
    }
}
