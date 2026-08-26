package com.mimococo.marketops.marketplaceintegration.port;

import java.util.Optional;

/**
 * Provider-neutral resolution of an opaque secret reference to its value.
 *
 * <p>The reference is a name; the value never appears in a business table, a
 * request DTO, a log record, an audit row or a user interface. Resolution
 * happens inside an adapter, at the moment of use, and the result is returned as
 * a character array so a caller can clear it rather than leave it for the
 * garbage collector to publish through a heap dump.
 *
 * <p>An unresolvable reference yields an empty result. That is the fail-closed
 * answer: an environment that cannot reach its secret store must refuse the
 * operation, never proceed with an unauthenticated call.
 */
public interface SecretResolverPort {

    /**
     * Resolve one reference, or report that this environment cannot.
     *
     * <p>The caller owns the returned array and is expected to clear it once the
     * value has been used.
     */
    Optional<char[]> resolve(String secretReference);
}
