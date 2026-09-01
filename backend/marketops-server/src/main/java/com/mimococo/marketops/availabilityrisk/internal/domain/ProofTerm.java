package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.util.Objects;

/**
 * One step of a conservative danger proof.
 *
 * <p>Terms are structured rather than prose so a reviewer can recompute the
 * argument and a test can assert it. "We counted 12 owned units, excluded 400
 * of undeclared ownership, and 12 units against 6 per day does not cover a
 * 21-day horizon" is three terms, not a sentence.
 *
 * @param code a stable machine-readable term identifier
 * @param label a short human-readable statement
 * @param value the number the term asserts, or {@code null} for a qualitative step
 */
public record ProofTerm(String code, String label, java.math.BigDecimal value) {
    public ProofTerm {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
        if (code.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("a proof term needs a code and a label");
        }
    }

    /** A term asserting a quantity. */
    public static ProofTerm of(String code, String label, java.math.BigDecimal value) {
        return new ProofTerm(code, label, value);
    }

    /** A term asserting a fact with no number attached. */
    public static ProofTerm qualitative(String code, String label) {
        return new ProofTerm(code, label, null);
    }
}
