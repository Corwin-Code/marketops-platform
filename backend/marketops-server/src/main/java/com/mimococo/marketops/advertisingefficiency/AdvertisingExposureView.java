package com.mimococo.marketops.advertisingefficiency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The aggregate exposure envelope in force, and what is consumed against it.
 *
 * <p>Four axes, each reported against its own limit and never combined. That is
 * the same rule the write gate follows: there is no point at which one axis's
 * slack is added to another's, so a console that showed a single "percentage
 * used" would be describing a quantity the product does not have.
 *
 * <p>This is a report, not an authority. The gate re-derives every one of these
 * numbers inside the database at the moment a write is attempted, so a stale or
 * optimistic reading here cannot let anything through — it can only mislead an
 * operator, which is why the envelope's own identity and version travel with it.
 *
 * @param envelopeId the envelope in force, or {@code null} when none resolves
 * @param policyVersion its version, or {@code null}
 * @param scopeKind the scope it was written at
 * @param currencyCode the currency its money limits are denominated in
 * @param activeInterventions reservations currently holding
 * @param maxActiveInterventions the limit on them
 * @param reservedRecoveryHeadroom how many of those are reserved for compensation
 * @param unresolvedTransmittedWrites writes whose outcome is not established
 * @param maxUnresolvedTransmittedWrites the limit on them
 * @param cumulativeBidChangeAmount absolute bid movement inside the window
 * @param maxCumulativeBidChangeAmount the limit on it
 * @param cumulativeWindowHours how long that window is
 * @param effectiveFrom when the envelope took effect
 * @param status whether the envelope is active or retired
 */
public record AdvertisingExposureView(
        UUID envelopeId,
        Integer policyVersion,
        String scopeKind,
        String currencyCode,
        long activeInterventions,
        Integer maxActiveInterventions,
        Integer reservedRecoveryHeadroom,
        long unresolvedTransmittedWrites,
        Integer maxUnresolvedTransmittedWrites,
        BigDecimal cumulativeBidChangeAmount,
        BigDecimal maxCumulativeBidChangeAmount,
        Integer cumulativeWindowHours,
        Instant effectiveFrom,
        String status) {

    public AdvertisingExposureView {
        // An envelope that names itself has to name its bounds as well, because
        // a limit read as absent would be read as no limit.
        if (envelopeId != null && (maxActiveInterventions == null
                || reservedRecoveryHeadroom == null
                || maxUnresolvedTransmittedWrites == null)) {
            throw new IllegalArgumentException("a resolved envelope carries every bound");
        }
    }

    /** Whether an envelope resolves at all; without one nothing may be written. */
    public boolean resolved() {
        return envelopeId != null;
    }

    /**
     * Which axes are at or past their limit, named individually.
     *
     * <p>Ordinary work may not consume the recovery headroom, so the
     * intervention axis is reported against the reduced capacity rather than
     * the raw maximum. A compensation may use the headroom, which is what it is
     * for, and that case is decided by the gate rather than reported here.
     */
    public List<String> exhaustedAxes() {
        if (!resolved()) {
            return List.of("AGGREGATE_ENVELOPE_UNRESOLVED");
        }
        List<String> exhausted = new java.util.ArrayList<>(3);
        if (activeInterventions >= (long) maxActiveInterventions - reservedRecoveryHeadroom) {
            exhausted.add("ACTIVE_INTERVENTIONS");
        }
        if (unresolvedTransmittedWrites >= maxUnresolvedTransmittedWrites) {
            exhausted.add("UNRESOLVED_TRANSMITTED_WRITES");
        }
        if (cumulativeBidChangeAmount != null && maxCumulativeBidChangeAmount != null
                && cumulativeBidChangeAmount.compareTo(maxCumulativeBidChangeAmount) >= 0) {
            exhausted.add("CUMULATIVE_BID_CHANGE");
        }
        return List.copyOf(exhausted);
    }

    /** The reading for an organization with no envelope written yet. */
    public static AdvertisingExposureView unresolved(long activeInterventions,
            long unresolvedTransmittedWrites) {
        return new AdvertisingExposureView(null, null, null, null, activeInterventions, null,
                null, unresolvedTransmittedWrites, null, null, null, null, null, null);
    }
}
