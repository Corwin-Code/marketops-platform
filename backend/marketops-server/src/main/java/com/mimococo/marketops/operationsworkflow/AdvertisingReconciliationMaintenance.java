package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.UUID;

/** Reconciles durable workflow authority; counts describe changes in this cycle. */
public interface AdvertisingReconciliationMaintenance {
    Counts reconcile(UUID organizationId, Instant asOf);

    record Counts(int expiredExceptions, int expiredApprovals, int expiredRecommendations,
                  int escalatedTasks) { }
}
