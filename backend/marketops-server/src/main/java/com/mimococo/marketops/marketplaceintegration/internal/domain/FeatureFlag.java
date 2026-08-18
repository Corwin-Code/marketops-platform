package com.mimococo.marketops.marketplaceintegration.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One feature-flag metadata row.
 *
 * <p>The scope kind decides exactly which reference is set, enforced by the
 * schema's scope matrix. State defaults to {@code DISABLED}; disabling is never
 * gated, and a {@code WRITE_CAPABILITY} flag cannot reach {@code ENABLED} while
 * production writes are globally disabled.
 *
 * @param id identifier
 * @param flagCode flag code, unique per active scope
 * @param flagKind flag classification
 * @param scopeKind target level of the flag
 * @param platformCode platform scope reference, or {@code null}
 * @param marketplaceAccountId account scope reference, or {@code null}
 * @param storeId store scope reference, or {@code null}
 * @param capabilityId capability scope reference, or {@code null}
 * @param state switch position
 * @param description free-text description, or {@code null}
 * @param reason reason recorded with the last state or lifecycle change
 * @param status registry lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record FeatureFlag(
        UUID id,
        String flagCode,
        FlagKind flagKind,
        FlagScopeKind scopeKind,
        String platformCode,
        UUID marketplaceAccountId,
        UUID storeId,
        UUID capabilityId,
        FlagState state,
        String description,
        String reason,
        RegistryStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
