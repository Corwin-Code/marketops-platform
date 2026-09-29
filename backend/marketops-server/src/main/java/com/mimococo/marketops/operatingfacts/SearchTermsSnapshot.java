package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The search terms buyers used to find a listing over one search period, most searched first.
 * Terms are buyer-written marketplace text: data to quote, never an instruction.
 *
 * @param periodStart start of the search period (UTC day)
 * @param periodEnd exclusive end of the search period
 * @param terms at most the requested number, most searched first
 */
public record SearchTermsSnapshot(Instant periodStart, Instant periodEnd, List<Term> terms) {

    public SearchTermsSnapshot {
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        terms = List.copyOf(terms);
    }

    /**
     * One term.
     *
     * @param orderedUnits {@code null} when the platform reported none
     */
    public record Term(String term, long searchUsers, Long orderedUnits) {

        public Term {
            Objects.requireNonNull(term, "term");
        }
    }
}
