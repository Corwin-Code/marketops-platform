package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reads the original Task responsibility without granting access or restarting its age. */
public interface ListingTaskSloQuery {
    Optional<Status> statusForRecommendation(UUID recommendationId);
    Optional<Status> statusForRecommendation(UUID recommendationId, Instant asOf);
    Optional<Status> statusForTask(UUID taskId, Instant asOf);
    java.util.List<DiagnosticStatus> diagnosticsForListing(UUID listingId);

    record DiagnosticStatus(String causeCode, Status status) { }

    record Status(UUID taskId, UUID calibrationPackageId, Integer calibrationVersion, String basisDigest,
                  String clockState, Instant firstRaisedAt, Instant acknowledgementDueAt,
                  Instant originalActionDueAt, Instant actionDueAt, Instant outcomeMaturityDueAt, Instant nextCoveredAt,
                  Instant acknowledgedAt, Instant firstAttributableActionAt,
                  Boolean acknowledgementBreached, Boolean actionBreached, long wallClockAgeSeconds,
                  long dependencyHoldElapsedSeconds, ListingTaskDeferralIntake.View deferral,
                  ListingTaskDependencyHold.View dependencyHold) { }
}
