package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Starting and finishing acquisition runs of registered jobs on a schedule.
 *
 * <p>A scheduled run is an ordinary SCHEDULED run: it is claimed, fenced and authorised call by
 * call exactly like a manual one, and it can only read what its job is registered and verified to
 * read. The caller decides when; nothing here decides whether a call is allowed.
 */
public interface ScheduledAcquisition {

    /** The job's runs created at or after {@code since}, newest first. */
    List<RunRecord> runsSince(UUID jobId, Instant since);

    /** The job's run that has not come to rest; a job has at most one. */
    Optional<RunRecord> liveRun(UUID jobId);

    /**
     * Until when the approved real-account evidence for the job's endpoint stays current; empty
     * when none is. Every call is refused once it lapses, so a run started after that ends BLOCKED.
     */
    Optional<Instant> evidenceValidUntil(UUID jobId);

    /**
     * Until when the active read credential of the job's marketplace account is in force; empty when
     * it has none. The marketplace may withdraw a key earlier; a refused call then shows in
     * {@link #lastAnswerStatus}.
     */
    Optional<Instant> credentialExpiresAt(UUID jobId);

    /**
     * The native status of the newest answer a run stored (Ozon: "HTTP 403" for a key that was
     * deactivated), or empty when it stored none. It says why a run came to rest blocked.
     */
    Optional<String> lastAnswerStatus(UUID runId);

    /**
     * Queue one SCHEDULED run.
     *
     * @param windowFrom start of the run's window, or {@code null} for a snapshot
     * @param windowTo end of the run's window, or {@code null} for a snapshot
     * @param actorId who is recorded in the audit
     * @return the queued run
     * @throws com.mimococo.marketops.shared.OperationRejectedException while the job is not
     *         active or already has a live run
     */
    RunRecord enqueueScheduled(UUID jobId, Instant windowFrom, Instant windowTo, String actorId);

    /** Claim and execute one queued or waiting run now; answers where it came to rest. */
    Execution execute(UUID runId, String actorId);

    /**
     * One run.
     *
     * @param runKind MANUAL, SCHEDULED, BACKFILL or REPLAY
     * @param state QUEUED, LEASED, RUNNING, RETRY_WAIT, BLOCKED, SUCCEEDED or FAILED_TERMINAL
     * @param windowFrom start of the run's window, or {@code null}
     * @param windowTo end of the run's window, or {@code null}
     * @param failureCode why it failed or blocked, or {@code null}
     * @param updatedAt when it last changed; for a run at rest, when it came to rest
     * @param nextAttemptAt when a waiting run may be claimed again, or {@code null}
     */
    record RunRecord(UUID runId, UUID jobId, String runKind, String state, Instant windowFrom,
                     Instant windowTo, String failureCode, Instant createdAt, Instant updatedAt,
                     Instant nextAttemptAt) {

        public boolean succeeded() {
            return "SUCCEEDED".equals(state);
        }

        public boolean failed() {
            return "FAILED_TERMINAL".equals(state);
        }

        /** Whether it reads exactly the window [from, to). */
        public boolean covers(Instant from, Instant to) {
            return from != null && to != null && from.equals(windowFrom) && to.equals(windowTo);
        }
    }

    /**
     * What executing one run left behind.
     *
     * @param pagesStored how many pages reached custody
     * @param reason why the run stopped where it did
     */
    record Execution(RunRecord run, int pagesStored, String reason) {
    }
}
