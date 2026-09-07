package com.mimococo.marketops.marketplaceintegration.port;

/**
 * One call to a marketplace advertising API, and one answer.
 *
 * <p>No state, no retry, no readback comparison, no compensation. Those are
 * decisions, and decisions live where the lease and the fence token are. An
 * adapter that retried on its own behalf would be making a second bid change
 * that nothing recorded.
 *
 * <p>Implementations never throw for a provider condition. A failure is an
 * {@link AdBidWriteResult.Outcome}, because an exception thrown across this
 * boundary would lose the distinction between "the platform refused" and "we
 * never found out", and that distinction is the whole basis of the unknown-state
 * handling on the other side.
 */
public interface AdBidWritePort {

    /** Perform one operation and report what came back. */
    AdBidWriteResult perform(AdBidWriteRequest request);
}
