package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One attested inbound claim, as the calculation sees it.
 *
 * <p>An inbound record is a statement by an accountable person that goods will
 * arrive. It is not stock, and it earns its place in a projection only by
 * passing every test in {@link #eligibleAt}: an accepted business status, an
 * arrival window inside the horizon, and a verification recent enough to still
 * mean something.
 *
 * @param attestationVersionId the exact attested version
 * @param quantity units claimed
 * @param expectedArrivalFrom earliest expected arrival
 * @param expectedArrivalTo latest expected arrival
 * @param businessStatus the attested status
 * @param lastVerifiedAt when somebody last confirmed the claim still held
 * @param evidenceReference the attributable evidence behind it
 */
public record InboundConsignment(
        UUID attestationVersionId,
        int quantity,
        Instant expectedArrivalFrom,
        Instant expectedArrivalTo,
        Status businessStatus,
        Instant lastVerifiedAt,
        String evidenceReference) {

    public InboundConsignment {
        Objects.requireNonNull(attestationVersionId, "attestationVersionId");
        Objects.requireNonNull(expectedArrivalFrom, "expectedArrivalFrom");
        Objects.requireNonNull(expectedArrivalTo, "expectedArrivalTo");
        Objects.requireNonNull(businessStatus, "businessStatus");
        Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
        Objects.requireNonNull(evidenceReference, "evidenceReference");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (expectedArrivalTo.isBefore(expectedArrivalFrom)) {
            throw new IllegalArgumentException("the arrival window ends before it starts");
        }
    }

    /** The attested business status of an inbound claim. */
    public enum Status {
        /** Somebody is drafting it. Visible; reduces nothing. */
        DRAFT(false),
        /** Requested but not confirmed by the supplier. Visible; reduces nothing. */
        REQUESTED(false),
        /** The supplier confirmed it against attributable evidence. */
        SUPPLIER_CONFIRMED(true),
        /** It has shipped and is in transit. */
        IN_TRANSIT(true),
        /** It arrived; the ledger, not this record, now carries the units. */
        RECEIVED(false),
        /** Cancelled. Stops providing safety immediately. */
        CANCELLED(false),
        /** Past its window without arriving. Stops providing safety immediately. */
        OVERDUE(false),
        /** Two sources disagree about it. */
        CONFLICTED(false),
        /** Its state cannot currently be established. */
        UNKNOWN(false);

        private final boolean mayReduceRisk;

        Status(boolean mayReduceRisk) {
            this.mayReduceRisk = mayReduceRisk;
        }

        /** Whether this status permits the claim to reduce company risk at all. */
        public boolean mayReduceRisk() {
            return mayReduceRisk;
        }
    }

    /**
     * Whether this consignment may reduce risk at {@code asOf}.
     *
     * <p>The latest end of the arrival window is what must fall inside the
     * horizon. Believing the earliest end would let a consignment that "might
     * arrive Monday, or might arrive in three weeks" count as Monday's supply.
     *
     * @param asOf the calculation instant
     * @param horizonEnd the end of the coverage horizon
     * @param freshnessMaxMinutes how old a verification may be
     */
    public boolean eligibleAt(Instant asOf, Instant horizonEnd, long freshnessMaxMinutes) {
        if (!businessStatus.mayReduceRisk()) {
            return false;
        }
        if (expectedArrivalTo.isAfter(horizonEnd)) {
            return false;
        }
        if (expectedArrivalTo.isBefore(asOf)) {
            // The window has closed and the ledger has not recorded an arrival.
            // Whatever happened, this is no longer evidence of future supply.
            return false;
        }
        return !lastVerifiedAt.plusSeconds(freshnessMaxMinutes * 60L).isBefore(asOf);
    }

    /** Why this consignment was refused at {@code asOf}, for the evidence trail. */
    public SupplyComponent.ExclusionReason exclusionAt(Instant asOf, Instant horizonEnd,
                                                       long freshnessMaxMinutes) {
        if (!businessStatus.mayReduceRisk()) {
            return SupplyComponent.ExclusionReason.INELIGIBLE_STATUS;
        }
        if (expectedArrivalTo.isAfter(horizonEnd) || expectedArrivalTo.isBefore(asOf)) {
            return SupplyComponent.ExclusionReason.OUTSIDE_HORIZON;
        }
        return SupplyComponent.ExclusionReason.STALE_OBSERVATION;
    }
}
