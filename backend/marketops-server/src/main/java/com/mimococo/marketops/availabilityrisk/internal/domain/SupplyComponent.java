package com.mimococo.marketops.availabilityrisk.internal.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One thing that either did or did not count towards proven supply.
 *
 * <p>Excluded components are kept rather than dropped. "We saw 400 units at the
 * marketplace and did not count them because nobody has declared whether they
 * are the same goods as the warehouse holds" is the sentence an operator needs;
 * silently omitting them makes the company total look like an unexplained
 * shortfall.
 *
 * @param source where the units were observed
 * @param units the observed quantity
 * @param counted whether the units contributed to proven supply
 * @param reason why they were counted or excluded
 * @param provenanceId the fact this came from, or {@code null} for a policy decision
 * @param observedAt when the source considered it true, or {@code null} when unknown
 */
public record SupplyComponent(
        Source source,
        int units,
        boolean counted,
        ExclusionReason reason,
        UUID provenanceId,
        java.time.Instant observedAt) {

    public SupplyComponent {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reason, "reason");
        if (units < 0) {
            throw new IllegalArgumentException("units cannot be negative");
        }
        if (counted && reason != ExclusionReason.COUNTED) {
            throw new IllegalArgumentException("a counted component cannot carry an exclusion reason");
        }
        if (!counted && reason == ExclusionReason.COUNTED) {
            throw new IllegalArgumentException("an excluded component must say why");
        }
    }

    /** Where a quantity was observed. */
    public enum Source {
        /** An internal warehouse the company operates. */
        INTERNAL_WAREHOUSE,
        /** Stock the platform reports for a store and fulfillment mode. */
        PLATFORM_VISIBLE,
        /** An attested inbound consignment inside its eligible window. */
        ELIGIBLE_INBOUND
    }

    /** Why a component was or was not counted. */
    public enum ExclusionReason {
        /** It counted. */
        COUNTED,
        /** The platform view mirrors internal stock already counted. */
        MIRRORS_INTERNAL_STOCK,
        /** Nobody has declared whether this is the same physical stock. */
        OWNERSHIP_NOT_DECLARED,
        /** The observation is older than its freshness bound. */
        STALE_OBSERVATION,
        /** The source reported the mode but no quantity. */
        QUANTITY_NOT_REPORTED,
        /** Units are reserved against orders already placed. */
        RESERVED,
        /** Units are held in quality control or written off. */
        NOT_SELLABLE,
        /** Two attributable sources disagree about the quantity. */
        CONFLICTING_SOURCES,
        /** The inbound window falls outside the coverage horizon. */
        OUTSIDE_HORIZON,
        /** The attested business status does not permit risk reduction. */
        INELIGIBLE_STATUS
    }

    /** A component that contributed. */
    public static SupplyComponent counted(Source source, int units, UUID provenanceId,
                                          java.time.Instant observedAt) {
        return new SupplyComponent(source, units, true, ExclusionReason.COUNTED, provenanceId, observedAt);
    }

    /** A component that was observed and deliberately not counted. */
    public static SupplyComponent excluded(Source source, int units, ExclusionReason reason,
                                           UUID provenanceId, java.time.Instant observedAt) {
        return new SupplyComponent(source, units, false, reason, provenanceId, observedAt);
    }

    /**
     * Whether this exclusion means the company total cannot be called complete.
     *
     * <p>Reserved and non-sellable units are known and correctly excluded, so
     * they do not undermine the answer. Undeclared ownership, staleness and
     * conflict do: in each case there are units nobody can currently classify,
     * and a total that ignores them is a guess wearing a number.
     */
    public boolean underminesCompleteness() {
        return !counted && switch (reason) {
            case OWNERSHIP_NOT_DECLARED, STALE_OBSERVATION, QUANTITY_NOT_REPORTED,
                 CONFLICTING_SOURCES -> true;
            default -> false;
        };
    }
}
