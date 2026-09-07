package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Reads the workflow's one coverage-aware response clock without reimplementing it. */
public interface AdvertisingTaskSloQuery {
    Optional<Status> statusForCase(UUID caseId);
    Optional<Status> statusForCase(UUID caseId, Instant asOf);

    record Status(String coverageState,Instant acknowledgementDueAt,Instant actionDueAt,Instant escalationDueAt,
                  Instant nextStaffedResponseAt,Instant acknowledgedAt,Instant firstAttributableActionAt,
                  long wallClockExposureAgeSeconds,boolean acknowledgementBreached,boolean actionBreached,boolean actionPaused) { }
}
