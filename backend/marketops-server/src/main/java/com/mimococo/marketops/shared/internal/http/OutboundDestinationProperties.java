package com.mimococo.marketops.shared.internal.http;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned allowlist. No database URL or verification flag grants network access. */
@ConfigurationProperties(prefix = "marketops.outbound")
public record OutboundDestinationProperties(List<Rule> destinations) {
    public OutboundDestinationProperties { destinations = destinations == null ? List.of() : List.copyOf(destinations); }

    /**
     * One allowed destination.
     *
     * @param pathPrefix the path a call must have, or a path it must lie under
     * @param exactPath whether only {@code pathPrefix} itself matches, not the paths under it: for a
     *        read path with write methods beneath it (Ozon {@code /v2/actions/products} has
     *        {@code /v2/actions/products/deactivate})
     */
    public record Rule(String key, String host, String pathPrefix, Set<String> methods,
                       Set<String> headers, int maxRequestBytes, int maxResponseBytes,
                       int timeoutMillis, Boolean exactPath) {

        /** Whether a call's raw path matches this rule's path. */
        public boolean matchesPath(String rawPath) {
            if (pathPrefix == null || !pathPrefix.startsWith("/") || rawPath == null) {
                return false;
            }
            if (rawPath.equals(pathPrefix)) {
                return true;
            }
            return !Boolean.TRUE.equals(exactPath)
                    && rawPath.startsWith(pathPrefix.endsWith("/") ? pathPrefix : pathPrefix + "/");
        }
    }
}
