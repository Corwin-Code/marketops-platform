package com.mimococo.marketops.availabilityrisk.internal.application;

import com.mimococo.marketops.availabilityrisk.AvailabilityLane;
import com.mimococo.marketops.availabilityrisk.internal.domain.AvailabilityPolicySet;
import com.mimococo.marketops.availabilityrisk.internal.domain.ChildRisk;
import com.mimococo.marketops.availabilityrisk.internal.domain.DemandWindowEvidence;
import com.mimococo.marketops.availabilityrisk.internal.domain.PriorityPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One variant's complete calculated risk, before anything is written down.
 *
 * <p>Keeping the calculation and the write apart is what makes the Contract's
 * equivalence obligation testable. A targeted recalculation and an hourly sweep
 * can each produce one of these for the same instant and be compared for
 * equality directly, rather than compared by reading back rows that a partially
 * applied write might have left in a state neither run intended.
 *
 * @param organizationId owning organization
 * @param productVariantId the internal variant this card is about
 * @param asOf the instant the evidence was read at
 * @param policies exactly the policy versions used
 * @param children every calculated child, channel and company
 */
public record VariantRisk(
        UUID organizationId,
        UUID productVariantId,
        Instant asOf,
        AvailabilityPolicySet policies,
        List<ScoredChild> children) {

    public VariantRisk {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(productVariantId, "productVariantId");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(policies, "policies");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }

    /**
     * One child with its rank and the evidence behind it.
     *
     * @param risk the calculated child
     * @param subject the exact channel, or {@code null} for the company child
     * @param ranking the score and its visible factors
     * @param windows the demand windows this child was judged on
     */
    public record ScoredChild(ChildRisk risk,
                              AvailabilityEvidenceGatherer.ChannelSubject subject,
                              PriorityPolicy.Ranking ranking,
                              List<DemandWindowEvidence> windows) {
    }

    /**
     * The lane the card shows: the most severe of its children.
     *
     * <p>A card with no children at all is unresolved rather than healthy.
     * Nothing was observed, and nothing observed is not a clean bill of health.
     */
    public AvailabilityLane parentLane() {
        return children.stream()
                .map(child -> child.risk().lane())
                .reduce(AvailabilityLane::mostSevere)
                .orElse(AvailabilityLane.UNRESOLVED);
    }

    /**
     * The child that produced the card's lane.
     *
     * <p>Ties break on rank, so the disclosed child is the one an operator
     * would open first rather than whichever the database returned first.
     */
    public ScoredChild triggeringChild() {
        AvailabilityLane lane = parentLane();
        return children.stream()
                .filter(child -> child.risk().lane() == lane)
                .max((left, right) -> left.ranking().score().compareTo(right.ranking().score()))
                .orElse(null);
    }

    /** The card's rank: the highest of its children's. */
    public BigDecimal rankScore() {
        return children.stream()
                .map(child -> child.ranking().score())
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }
}
