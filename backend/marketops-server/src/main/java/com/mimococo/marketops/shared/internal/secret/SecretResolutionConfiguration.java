package com.mimococo.marketops.shared.internal.secret;

import com.mimococo.marketops.shared.port.SecretResolverPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one place a resolved secret enters this process.
 *
 * <p>A single resolver serves every outbound adapter. Two resolvers would mean
 * two answers to the question of whether this environment can reach its secret
 * store, and an adapter could then hold a credential the rest of the process
 * believes is unavailable.
 *
 * <p>With no mount configured the resolver is still created and refuses every
 * reference. That is deliberate: an environment without secrets must fail at
 * the call, visibly and by the same path as an unreadable value, rather than
 * fail at startup in a way a deployment is tempted to work around.
 */
@Configuration
@EnableConfigurationProperties(SecretMountProperties.class)
public class SecretResolutionConfiguration {

    /** Resolution of opaque secret references for every outbound adapter. */
    @Bean
    public SecretResolverPort secretResolverPort(SecretMountProperties properties,
                                                 @Value("${marketops.environment:}") String environment) {
        // The workstation read path gives up descriptor-relative opens; a
        // serving environment asking for it is a misconfiguration, not a choice.
        if (properties.isWorkstationFallback() && !"local".equals(environment)) {
            throw new IllegalStateException(
                    "marketops.secrets.workstation-fallback is allowed only in the local environment");
        }
        return new MountedSecretResolver(properties.getMountDirectory(),
                properties.isWorkstationFallback());
    }
}
