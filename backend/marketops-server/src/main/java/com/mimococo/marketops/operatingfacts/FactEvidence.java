package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a fact answer was derived from, and how trustworthy the derivation is.
 *
 * <p>Every answer carries its provenance so a metric computed from it can cite
 * the exact rows, and carries the oldest source time so freshness is a property
 * of the answer rather than something a caller has to reconstruct.
 *
 * <p>An empty provenance list means nothing contributed. That is a different
 * statement from a zero total, and keeping the two apart is what stops an
 * absence of data from being read as an absence of business.
 *
 * <p>{@code currencyConflict} reports that contributing rows disagreed about
 * currency. Summing across currencies would produce a confident number that
 * means nothing, so the answer is withheld and the disagreement is reported
 * instead; a caller records the metric as conflicted rather than as available.
 *
 * @param provenanceIds the provenance records that contributed
 * @param oldestSourceTime the earliest time a contributing source considered
 *        true, or {@code null} when no source stated one
 * @param currencyConflict whether contributing rows disagreed about currency
 */
public record FactEvidence(
        List<UUID> provenanceIds,
        Instant oldestSourceTime,
        boolean currencyConflict) {

    public FactEvidence {
        provenanceIds = List.copyOf(Objects.requireNonNull(provenanceIds, "provenanceIds"));
    }

    /** Evidence for an answer nothing contributed to. */
    public static FactEvidence none() {
        return new FactEvidence(List.of(), null, false);
    }

    /** Evidence for an answer that was withheld because currencies disagreed. */
    public static FactEvidence conflicted(List<UUID> provenanceIds, Instant oldestSourceTime) {
        return new FactEvidence(provenanceIds, oldestSourceTime, true);
    }

    /** Evidence for an answer that resolved cleanly. */
    public static FactEvidence of(List<UUID> provenanceIds, Instant oldestSourceTime) {
        return new FactEvidence(provenanceIds, oldestSourceTime, false);
    }

    /** Whether anything contributed to the answer. */
    public boolean present() {
        return !provenanceIds.isEmpty();
    }

    /** Whether the answer resolved to a number a caller may use. */
    public boolean usable() {
        return present() && !currencyConflict;
    }
}
