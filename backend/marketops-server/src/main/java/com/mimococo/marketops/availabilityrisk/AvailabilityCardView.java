package com.mimococo.marketops.availabilityrisk;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One grouped Internal Variant card.
 *
 * <p>{@code triggeringChildId} is sent rather than left to the reader to infer
 * from the lanes. Two children can share the parent's lane, and the card has to
 * say which one produced it.
 *
 * @param id the card
 * @param productVariantId the internal variant
 * @param skuCode its business identifier
 * @param displayName its name
 * @param lane the most severe eligible child lane
 * @param triggeringChildId the child that produced the lane, or {@code null} when healthy
 * @param rankScore its queue position
 * @param policyVersionDigest exactly which policy versions produced it
 * @param asOf the instant the evidence was read at
 * @param calculatedAt when the answer was produced
 * @param children the independently governed child risks
 */
public record AvailabilityCardView(
        UUID id,
        UUID productVariantId,
        String skuCode,
        String displayName,
        String lane,
        UUID triggeringChildId,
        BigDecimal rankScore,
        String policyVersionDigest,
        Instant asOf,
        Instant calculatedAt,
        List<AvailabilityChildView> children) {

    public AvailabilityCardView {
        children = List.copyOf(children);
    }
}
