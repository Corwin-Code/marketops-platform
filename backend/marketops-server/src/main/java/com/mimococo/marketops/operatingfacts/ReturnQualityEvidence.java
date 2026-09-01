package com.mimococo.marketops.operatingfacts;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative coverage state of the completed/retained/return/QC report set.
 *
 * <p>This is deliberately separate from event aggregates. A return event can
 * prove that at least one return happened, but only a complete report can prove
 * that no return happened or that the whole reporting window was covered.
 */
public record ReturnQualityEvidence(
        State state,
        UUID snapshotId,
        Instant reportWindowStart,
        Instant reportWindowEnd,
        Instant acceptedAt,
        String evidenceReference) {

    public enum State {
        FRESH_COMPLETE_ZERO_RETURNS,
        FRESH_COMPLETE_OBSERVED_RETURNS,
        INCOMPLETE,
        STALE,
        CONFLICTED,
        NO_EVIDENCE
    }

    public static ReturnQualityEvidence noEvidence(FactWindow window) {
        return new ReturnQualityEvidence(State.NO_EVIDENCE, null, window.periodStart(),
                window.periodEnd(), null, null);
    }
}
