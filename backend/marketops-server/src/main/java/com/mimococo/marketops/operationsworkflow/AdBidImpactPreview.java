package com.mimococo.marketops.operationsworkflow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What changing this bid would do, and whether it is currently allowed.
 *
 * <p>The advertising counterpart of {@link ImpactPreview}, and a separate type
 * rather than a widened one. A bid change has no break-even price and no
 * contribution margin; a price change reaches one listing variant and a bid
 * change reaches every variant the advertising object promotes. Sharing a
 * record would mean half its fields were always null and the reader would have
 * to know which half.
 *
 * <p>The number that matters most here is {@code affectedVariantCount}. A price
 * change is about the thing an operator is looking at. A bid change is about
 * everything one advertising object touches, and an operator who does not see
 * that is approving a wider action than they think.
 *
 * @param recommendationId the proposal being previewed
 * @param projection what the advertising module knows about it
 * @param gateReasons why the write gate would refuse right now, possibly empty
 * @param unresolvedReasons why the decision could not be resolved, possibly empty
 * @param verdict the deterministic guardrail decision
 */
public record AdBidImpactPreview(
        UUID recommendationId,
        AdvertisingBidProjection projection,
        List<String> gateReasons,
        List<String> unresolvedReasons,
        GuardrailVerdict verdict,
        tools.jackson.databind.JsonNode evidence) {

    public AdBidImpactPreview(UUID recommendationId,AdvertisingBidProjection projection,List<String> gateReasons,
            List<String> unresolvedReasons,GuardrailVerdict verdict) {
        this(recommendationId,projection,gateReasons,unresolvedReasons,verdict,null);
    }

    public AdBidImpactPreview {
        Objects.requireNonNull(recommendationId, "recommendationId");
        Objects.requireNonNull(verdict, "verdict");
        gateReasons = List.copyOf(gateReasons == null ? List.of() : gateReasons);
        unresolvedReasons =
                List.copyOf(unresolvedReasons == null ? List.of() : unresolvedReasons);
    }

    /**
     * Whether anything at all currently stands in the way.
     *
     * <p>Deliberately not the same as {@code verdict().passed()}. A proposal can
     * pass the guardrail and still be refused by the gate, and an operator told
     * only about the guardrail would be surprised twice.
     */
    public boolean clear() {
        return verdict.passed() && gateReasons.isEmpty() && unresolvedReasons.isEmpty();
    }

    /** How many product variants a single approval here would reach. */
    public int affectedVariantCount() {
        return projection == null ? 0 : projection.affectedVariantCount();
    }
}
