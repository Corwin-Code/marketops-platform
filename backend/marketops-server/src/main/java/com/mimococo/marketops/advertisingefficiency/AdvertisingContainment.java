package com.mimococo.marketops.advertisingefficiency;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One hold, quarantine or kill currently standing over advertising.
 *
 * <p>Five kinds, and they are not degrees of the same thing. An entity hold
 * stops one object; an outcome quarantine stops a lineage whose result nobody
 * has explained; an authority-version quarantine stops everything decided under
 * a version that turned out to be wrong; a capability quarantine stops a
 * platform-store combination; a kill switch stops all of it. A single severity
 * number could not say which of these had been thrown, and an operator reading
 * "level 3" learns nothing about what to fix.
 *
 * <p>The reenablement fields are visible because reenablement is the part that
 * needs watching. Five conditions, two different people and, for a technical or
 * security cause, an attestation — and the row shows how many of them are
 * outstanding rather than only whether the thing is still held.
 *
 * @param id the containment
 * @param containmentKind what kind of stop this is
 * @param scopeKind what it covers
 * @param causeClass why it was thrown
 * @param reason the words somebody wrote at the time
 * @param evidenceReference where the evidence lives
 * @param activatedByUserId the person who threw it, or {@code null} when automatic
 * @param activatedByTrigger the rule that threw it, or {@code null} when a person did
 * @param activatedAt when it started
 * @param state whether it is active, in review, or lifted
 * @param outstandingConditions reenablement conditions not yet met
 * @param endorsedByUserId who endorsed lifting it, or {@code null}
 * @param approvedByUserId who approved lifting it, or {@code null}
 * @param reenabledAt when it was lifted, or {@code null}
 */
public record AdvertisingContainment(
        UUID id,
        String containmentKind,
        String scopeKind,
        String causeClass,
        String reason,
        String evidenceReference,
        UUID activatedByUserId,
        String activatedByTrigger,
        Instant activatedAt,
        String state,
        List<String> outstandingConditions,
        UUID endorsedByUserId,
        UUID approvedByUserId,
        Instant reenabledAt) {

    public AdvertisingContainment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(containmentKind, "containmentKind");
        Objects.requireNonNull(state, "state");
        outstandingConditions = List.copyOf(
                outstandingConditions == null ? List.of() : outstandingConditions);
    }

    /** Whether this containment still stops things happening. */
    public boolean holding() {
        return !"REENABLED".equals(state);
    }

    /** Whether everything reenablement needs has been recorded. */
    public boolean readyToLift() {
        return outstandingConditions.isEmpty()
                && endorsedByUserId != null && approvedByUserId != null;
    }
}
