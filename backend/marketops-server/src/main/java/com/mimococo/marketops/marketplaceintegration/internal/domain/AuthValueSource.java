package com.mimococo.marketops.marketplaceintegration.internal.domain;

/**
 * Where the value of one recorded authentication header comes from.
 *
 * <p>The set is closed and deliberately narrow. There is no source that could
 * read an arbitrary column, so a recorded header cannot be used to send business
 * data outward, and the only secret-bearing source is resolved through the
 * secret port at the moment of use.
 */
public enum AuthValueSource {

    /** The credential's secret, resolved by opaque reference. */
    RESOLVED_SECRET,

    /** The marketplace account's own identifier, which is not secret. */
    ACCOUNT_NATIVE_KEY,

    /** A fixed value such as a content type. */
    LITERAL
}
