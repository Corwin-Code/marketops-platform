package com.mimococo.marketops.productlisting.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Work a person must resolve before a listing variant can carry precise cost,
 * precise profit or any platform write.
 *
 * @param id identifier
 * @param organizationId owning organization
 * @param platformListingVariantId the blocked listing variant
 * @param conflictKind why it is blocked
 * @param detail what was compared, in operator-readable terms
 * @param state whether it still blocks
 * @param detectedAt when it was first detected
 * @param resolvedByUserId who resolved it, or {@code null} while open
 * @param resolvedAt when, or {@code null} while open
 * @param resolutionReason why, or {@code null} while open
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record MappingConflict(
        UUID id,
        UUID organizationId,
        UUID platformListingVariantId,
        ConflictKind conflictKind,
        String detail,
        ConflictState state,
        Instant detectedAt,
        UUID resolvedByUserId,
        Instant resolvedAt,
        String resolutionReason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
