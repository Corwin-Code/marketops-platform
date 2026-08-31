package com.mimococo.marketops.operationsworkflow;

/**
 * What an acceptance covers.
 *
 * <p>The scope is recorded rather than inferred, because an acceptance granted
 * for one channel must not silently become an acceptance of the same cause
 * everywhere. A scope change invalidates the acceptance for exactly that
 * reason.
 */
public enum ExceptionScopeKind {

    /** One exact calculated child. */
    CHILD,

    /** One internal variant, across its channels. */
    VARIANT,

    /** One store. */
    STORE,

    /** One listing variant and fulfillment mode. */
    CHANNEL
}
