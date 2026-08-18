package com.mimococo.marketops.identityaccess.internal.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Declared allowed network source of a service account.
 *
 * <p>This declarative metadata is fail-closed: zero active declarations means
 * no source is declared and therefore nothing is allowed. Withdrawal is
 * terminal for the row; re-declaring the same source creates a new row, so the
 * declaration history stays complete.
 *
 * @param id identifier
 * @param serviceAccountId owning service account
 * @param cidr declared source in CIDR notation
 * @param note operator note, or {@code null}
 * @param status {@code ACTIVE} or {@code WITHDRAWN}
 * @param reason withdrawal reason, or {@code null} while active
 * @param createdAt creation time
 * @param updatedAt last change time
 * @param version optimistic-lock version
 */
public record AllowedSource(
        UUID id,
        UUID serviceAccountId,
        String cidr,
        String note,
        AllowedSourceStatus status,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
