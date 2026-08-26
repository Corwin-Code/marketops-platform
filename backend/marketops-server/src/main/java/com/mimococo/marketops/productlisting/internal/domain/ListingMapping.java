package com.mimococo.marketops.productlisting.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A confirmed relationship over a half-open interval.
 *
 * <p>At any instant a platform listing variant resolves to at most one internal
 * variant. That is what keeps cost, and therefore profit, unambiguous at the
 * moment a price decision needs it, and it is enforced relationally rather than
 * by convention.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param platformListingVariantId the platform side
 * @param productVariantId the internal side
 * @param sourceCandidateId proposal this came from, or {@code null}
 * @param effectiveFrom start of the interval
 * @param effectiveTo end of the interval, or {@code null} while open
 * @param status whether the interval is open, closed or withdrawn
 * @param confirmedByUserId who confirmed it
 * @param reason why
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record ListingMapping(
        UUID id,
        UUID organizationId,
        UUID platformListingVariantId,
        UUID productVariantId,
        UUID sourceCandidateId,
        Instant effectiveFrom,
        Instant effectiveTo,
        MappingStatus status,
        UUID confirmedByUserId,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
