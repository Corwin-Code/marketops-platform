package com.mimococo.marketops.shared.internal.http;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment-owned allowlist. No database URL or verification flag grants network access. */
@ConfigurationProperties(prefix = "marketops.outbound")
public record OutboundDestinationProperties(List<Rule> destinations) {
    public OutboundDestinationProperties { destinations = destinations == null ? List.of() : List.copyOf(destinations); }

    public record Rule(String key, String host, String pathPrefix, Set<String> methods,
                       Set<String> headers, int maxRequestBytes, int maxResponseBytes,
                       int timeoutMillis) { }
}
