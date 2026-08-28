package com.mimococo.marketops.productlisting.internal.domain;

/**
 * Why a listing variant cannot be mapped without a person deciding.
 *
 * <p>Each kind names a situation where an automatic answer would be a guess
 * with financial consequences, so each blocks precise cost, precise profit and
 * any platform write until it is resolved.
 */
public enum ConflictKind {

    /** Nothing in the internal catalogue matches this listing variant. */
    NO_CANDIDATE,

    /** More than one internal variant matches, and the methods disagree. */
    MULTIPLE_CANDIDATES,

    /** The platform barcode matches more than one live internal barcode. */
    DUPLICATE_BARCODE,

    /** A confirmation contradicts a mapping that is already in force. */
    CONFLICTING_CONFIRMATION,

    /** The mapped internal variant has been retired underneath the mapping. */
    ARCHIVED_INTERNAL_VARIANT
}
