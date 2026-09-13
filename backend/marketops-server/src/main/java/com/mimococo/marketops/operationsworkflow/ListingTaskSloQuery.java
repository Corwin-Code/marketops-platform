package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reads the original Task responsibility without granting access or restarting its age. */
public interface ListingTaskSloQuery {
    Optional<Status> statusForRecommendation(UUID recommendationId);
    Optional<Status> statusForRecommendation(UUID recommendationId, Instant asOf);

    record Status(UUID taskId, UUID calibrationPackageId, Integer calibrationVersion, String basisDigest,
                  String clockState, Instant firstRaisedAt, Instant acknowledgementDueAt,
                  Instant actionDueAt, Instant outcomeMaturityDueAt, Instant nextCoveredAt,
                  Instant acknowledgedAt, Instant firstAttributableActionAt,
                  Boolean acknowledgementBreached, Boolean actionBreached, long wallClockAgeSeconds) { }
}
