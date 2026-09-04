package com.mimococo.marketops.advertisingefficiency;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One reservation currently standing over a set of variants.
 *
 * <p>Only a real intervention reserves. A candidate, a proposal or a queue case
 * nobody has acted on does not appear here, and that is deliberate: a console
 * that showed every unactioned proposal as a live hold would make the aggregate
 * envelope look exhausted while nothing was actually happening, and an operator
 * would start releasing things that were never taken.
 *
 * <p>The four condition flags are the release preconditions, shown separately
 * rather than reduced to "releasable". An operator looking at a reservation that
 * will not release needs to know which of the four is outstanding, and a single
 * boolean cannot say.
 *
 * @param id the reservation
 * @param adNativeObjectId the advertising object it was taken for
 * @param storeId the store, so scope is visible and not merely applied
 * @param affectedSetDigest the exact set of variants it covers
 * @param productVariantIds the variants themselves
 * @param interventionKind which of the three real interventions took it
 * @param interventionReferenceId the command or packet that owns it
 * @param direction which way the bid was to move, or {@code null} for a packet
 * @param lane the lane the intervention came from
 * @param state whether it still holds
 * @param configurationResolved whether the resulting configuration is known
 * @param unknownOrMismatchOpen whether an unresolved write still stands against it
 * @param earlyObservationComplete whether the early observation window has closed
 * @param regressionOpen whether an outcome regression is still open on it
 * @param reservedAt when it was taken
 * @param releasedAt when it was released, or {@code null} while it holds
 * @param releaseReason why it was released, or {@code null}
 */
public record AdvertisingReservationView(
        UUID id,
        UUID adNativeObjectId,
        UUID storeId,
        String affectedSetDigest,
        List<UUID> productVariantIds,
        String interventionKind,
        UUID interventionReferenceId,
        String direction,
        String lane,
        String state,
        boolean configurationResolved,
        boolean unknownOrMismatchOpen,
        boolean earlyObservationComplete,
        boolean regressionOpen,
        Instant reservedAt,
        Instant releasedAt,
        String releaseReason) {

    public AdvertisingReservationView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(interventionKind, "interventionKind");
        Objects.requireNonNull(state, "state");
        productVariantIds =
                List.copyOf(productVariantIds == null ? List.of() : productVariantIds);
    }

    /** Whether this reservation still stops anything else acting on these variants. */
    public boolean holding() {
        return "ACTIVE".equals(state);
    }

    /** Which release preconditions are not yet met, named rather than counted. */
    public List<String> outstandingReleaseConditions() {
        List<String> outstanding = new java.util.ArrayList<>(4);
        if (!configurationResolved) {
            outstanding.add("CONFIGURATION_NOT_RESOLVED");
        }
        if (unknownOrMismatchOpen) {
            outstanding.add("UNKNOWN_OR_MISMATCH_OPEN");
        }
        if (!earlyObservationComplete) {
            outstanding.add("EARLY_OBSERVATION_INCOMPLETE");
        }
        if (regressionOpen) {
            outstanding.add("REGRESSION_OPEN");
        }
        return List.copyOf(outstanding);
    }
}
