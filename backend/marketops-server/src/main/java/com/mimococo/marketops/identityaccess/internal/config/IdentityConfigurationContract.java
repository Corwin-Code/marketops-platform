package com.mimococo.marketops.identityaccess.internal.config;

import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a serving environment that has no identity provider.
 *
 * <p>Without an issuer the console API denies every request, which is safe but
 * silent: an operator would see a working process answering every call with a
 * refusal and would have no signal about why. Failing startup turns that into a
 * message at the moment somebody can act on it.
 *
 * <p>The workstation and continuous-integration environments are exempt because
 * they exercise the boundary with their own decoder rather than a real provider.
 * They are named explicitly, so a new environment is covered by the rule rather
 * than by an omission.
 */
@Component
class IdentityConfigurationContract implements InitializingBean {

    /** Environments that legitimately run without a real identity provider. */
    private static final Set<String> EXEMPT_ENVIRONMENTS = Set.of("local", "ci");

    private final String environment;
    private final IdentityProperties properties;

    IdentityConfigurationContract(@Value("${marketops.environment}") String environment,
                                  IdentityProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (EXEMPT_ENVIRONMENTS.contains(environment) || properties.configured()) {
            return;
        }
        throw new IllegalStateException(
                "marketops.identity.oidc.issuer-uri must be configured in this environment");
    }
}
