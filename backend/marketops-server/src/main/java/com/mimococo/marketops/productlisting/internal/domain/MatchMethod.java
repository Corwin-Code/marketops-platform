package com.mimococo.marketops.productlisting.internal.domain;

import java.math.BigDecimal;

/**
 * How a mapping proposal was produced, and how much that method is worth.
 *
 * <p>The confidence is a property of the method rather than of the individual
 * proposal, so two proposals produced the same way are comparable and a
 * reviewer can reason about the method once instead of about every row.
 *
 * <p>No method reaches certainty. Even an exact barcode match stops short of
 * one, because a duplicate barcode in the internal catalogue is exactly the
 * situation the conflict queue exists for.
 */
public enum MatchMethod {

    /** The platform barcode equals a live internal barcode. */
    BARCODE("0.9500"),

    /** The platform's own seller SKU equals an internal variant code. */
    NATIVE_SKU_KEY("0.9000"),

    /** Normalised titles agree; a weak signal offered only for review. */
    NORMALIZED_TITLE("0.4000"),

    /** A person asserted the mapping directly. */
    MANUAL("1.0000"),

    /** The mapping arrived through a reviewed internal file. */
    IMPORTED("0.8000");

    private final BigDecimal confidence;

    MatchMethod(String confidence) {
        this.confidence = new BigDecimal(confidence);
    }

    /** The confidence this method carries, between zero and one. */
    public BigDecimal confidence() {
        return confidence;
    }
}
