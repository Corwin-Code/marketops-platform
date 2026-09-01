package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.util.List;
import java.util.Objects;

/**
 * The reproducible argument that danger is already established.
 *
 * <p>A company risk may be reported as provisional only when this proof exists.
 * The rule it encodes is narrow: using nothing but supply that is owned, fresh
 * and proven distinct, the best case still runs out inside the horizon.
 * Whatever the unclassifiable units turn out to be, they can only make the
 * picture worse, so the conclusion survives the missing evidence.
 *
 * <p>An empty proof is a valid value — it means no such argument exists, and
 * therefore the answer must be unresolved rather than provisional. The database
 * refuses to store a provisional child whose proof has no terms.
 *
 * @param terms the steps of the argument, in the order they were established
 */
public record ConservativeProof(List<ProofTerm> terms) {

    public ConservativeProof {
        terms = List.copyOf(Objects.requireNonNull(terms, "terms"));
    }

    /** No argument could be constructed. */
    public static ConservativeProof none() {
        return new ConservativeProof(List.of());
    }

    /** An argument with at least one step. */
    public static ConservativeProof of(List<ProofTerm> terms) {
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("a proof with no terms proves nothing");
        }
        return new ConservativeProof(terms);
    }

    /** Whether an argument was actually established. */
    public boolean established() {
        return !terms.isEmpty();
    }
}
