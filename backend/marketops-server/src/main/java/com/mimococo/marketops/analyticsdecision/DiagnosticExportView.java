package com.mimococo.marketops.analyticsdecision;

import java.time.Instant;
import java.util.UUID;

/** A bounded job status; lease tokens, custody locators and requester identity stay internal. */
public record DiagnosticExportView(UUID id, UUID storeId, MetricWindow window, String state,
        Instant createdAt, Instant snapshotAt, Instant expiresAt, int rowCount, long byteLength,
        int completedParts, String failureCode) {
}
