package com.mimococo.marketops.productlisting.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A proposal that one platform listing variant is one internal variant.
 *
 * <p>A proposal never takes effect on its own. Confirming it writes an
 * effective-dated mapping and records who decided and why; rejecting it keeps
 * the row so the same proposal is not made again as if it were new.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param platformListingVariantId the platform side
 * @param productVariantId the internal side
 * @param matchMethod how the proposal was produced
 * @param confidence the method's confidence, between zero and one
 * @param evidenceNote what the method compared, or {@code null}
 * @param state where the proposal stands
 * @param decidedByUserId who decided, or {@code null} while proposed
 * @param decidedAt when they decided, or {@code null} while proposed
 * @param decisionReason why, or {@code null} while proposed
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record MappingCandidate(
        UUID id,
        UUID organizationId,
        UUID platformListingVariantId,
        UUID productVariantId,
        MatchMethod matchMethod,
        BigDecimal confidence,
        String evidenceNote,
        CandidateState state,
        UUID decidedByUserId,
        Instant decidedAt,
        String decisionReason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
