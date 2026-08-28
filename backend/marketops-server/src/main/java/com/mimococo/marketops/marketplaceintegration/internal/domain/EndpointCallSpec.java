package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.util.UUID;

/**
 * Everything recorded about how to reach one verified endpoint.
 *
 * <p>The specification is only ever loaded for an endpoint whose verification
 * state is VERIFIED and whose platform profile is active, so a call cannot be
 * built from a fact nobody has checked. Rate limit and timeout travel with it
 * because both are the platform's constraints rather than this application's
 * preferences.
 *
 * @param endpointId identifier of the endpoint
 * @param platformCode marketplace the endpoint belongs to
 * @param endpointCode registry name of the endpoint
 * @param baseUrl origin recorded for the platform
 * @param httpMethod method the endpoint expects
 * @param pathTemplate path, with placeholders the adapter substitutes
 * @param queryTemplate query string, or {@code null}
 * @param bodyTemplate request body, or {@code null}
 * @param responseContentType content type the endpoint returns, or {@code null}
 * @param continuationPointer where the source's continuation token lives, or {@code null}
 * @param paginationModel how the endpoint pages
 * @param rateLimitPerMinute recorded ceiling, or {@code null} when unrecorded
 * @param requestTimeoutMillis how long one call may take
 * @param maxResponseBytes largest response this adapter will read
 */
public record EndpointCallSpec(
        UUID endpointId,
        String platformCode,
        String endpointCode,
        String baseUrl,
        String httpMethod,
        String pathTemplate,
        String queryTemplate,
        String bodyTemplate,
        String responseContentType,
        String continuationPointer,
        String paginationModel,
        Integer rateLimitPerMinute,
        int requestTimeoutMillis,
        long maxResponseBytes) {
}
