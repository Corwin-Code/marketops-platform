package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.UUID;

/** Freeze the attributable evaluation plan before human selection/approval and external action. */
public interface AdvertisingOutcomePlanning {
    UUID prepare(UUID organizationId, UUID candidateId, Instant at);
    UUID prepareManual(UUID organizationId, UUID proposalId, Instant at);
    UUID observeManual(UUID organizationId, UUID packetId, Instant at);
}
