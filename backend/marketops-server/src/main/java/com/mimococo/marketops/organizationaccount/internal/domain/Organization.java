package com.mimococo.marketops.organizationaccount.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Top-level operating entity.
 *
 * @param id identifier
 * @param code globally unique business code, immutable after creation
 * @param displayName operator-facing name
 * @param defaultTimezone IANA zone, or {@code null} when not recorded
 * @param defaultCurrencyCode ISO 4217 code, or {@code null} when not recorded
 * @param status lifecycle status
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record Organization(
        UUID id,
        String code,
        String displayName,
        String defaultTimezone,
        String defaultCurrencyCode,
        EntityStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
