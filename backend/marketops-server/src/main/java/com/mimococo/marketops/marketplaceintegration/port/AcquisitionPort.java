package com.mimococo.marketops.marketplaceintegration.port;

/**
 * Platform-neutral outbound acquisition: one authorised call to one source,
 * returning exactly what the source returned.
 *
 * <p>The port is the only doorway through which acquisition traffic may ever
 * leave the system, so its contract carries the security posture rather than
 * trusting each implementation to remember it. The request names a credential
 * by opaque reference only; no secret material crosses this boundary in either
 * direction, and resolving the reference to a secret is an adapter concern
 * behind its own approval gate.
 *
 * <p>The result preserves the source verbatim. Returned bytes are the bytes,
 * the native status is the source's own words, and an unrecognised state stays
 * {@code UNKNOWN_STATE} — a caller that cannot classify an answer must record
 * it as unclassified, never promote it to a success.
 */
public interface AcquisitionPort {

    /**
     * Perform one acquisition call under a live, bounded call authority.
     *
     * <p>The internal JDBC authority gateway has already validated and consumed
     * the bound authority and derived this request. An implementation performs
     * the exchange and reports it, and never widens, extends or re-derives the
     * authority it was handed.
     */
    AcquisitionResult acquire(AcquisitionRequest request);
}
