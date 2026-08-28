package com.mimococo.marketops.marketplaceintegration.port;

/**
 * The one doorway a price change leaves through.
 *
 * <p>The port is deliberately narrow: one call, one answer, no state. Leasing,
 * retrying, readback comparison and compensation are decisions about a command,
 * and keeping them out of the adapter means the marketplace-specific code
 * cannot accidentally decide that something succeeded.
 *
 * <p>An implementation returns a result rather than raising. A write whose
 * outcome is unknown is the single most important case in this product, and an
 * exception would let a caller treat it as a failure — which is exactly the
 * belief that leads to writing the same change twice.
 */
public interface PriceWritePort {

    /** Perform one recorded operation against a platform. */
    PriceWriteResult perform(PriceWriteRequest request);
}
