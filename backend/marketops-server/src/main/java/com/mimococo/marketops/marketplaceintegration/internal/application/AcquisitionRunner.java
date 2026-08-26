package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.internal.config.AcquisitionProperties;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives one acquisition run from claim to a resting state.
 *
 * <p>The claim, each page and the finish are separate transactions. Holding one
 * transaction across outbound calls would keep a database connection open for
 * the length of a marketplace's latency and would make a partial run
 * indistinguishable from no run at all; separating them is what makes a long
 * acquisition restartable rather than all-or-nothing.
 *
 * <p>A run that reaches its per-run call ceiling rests rather than continuing.
 * One job must not monopolise a worker, and a lease short enough to survive a
 * crash has to be renewed rather than stretched.
 *
 * <p>A replay claims a run so its progress is visible and fenced, and makes no
 * call. The database refuses one for a replay regardless, so the guarantee does
 * not depend on this class remembering it.
 */
@Service
public class AcquisitionRunner {

    private static final Logger log = LoggerFactory.getLogger(AcquisitionRunner.class);

    private final IngestionRunRepository runs;
    private final AcquisitionPageWorker pageWorker;
    private final AcquisitionProperties properties;
    private final IdGenerator idGenerator;

    AcquisitionRunner(IngestionRunRepository runs,
                      AcquisitionPageWorker pageWorker,
                      AcquisitionProperties properties,
                      IdGenerator idGenerator) {
        this.runs = runs;
        this.pageWorker = pageWorker;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    /** Queue a run for a job, or refuse because one is already live. */
    @Transactional
    public UUID enqueue(UUID jobId, String runKind, Instant windowFrom, Instant windowTo) {
        return runs.enqueue(idGenerator.newId(), jobId, runKind, windowFrom, windowTo);
    }

    /** Claim and execute one run. */
    public RunOutcome execute(UUID runId, String workerName) {
        long fence;
        try {
            fence = runs.claim(runId, workerName, leaseSeconds());
        } catch (RuntimeException notClaimable) {
            return new RunOutcome(runId, 0, "NOT_CLAIMABLE");
        }

        Optional<IngestionRunRepository.RunState> claimed = runs.findRun(runId);
        if (claimed.isEmpty()) {
            return new RunOutcome(runId, 0, "NOT_FOUND");
        }
        Optional<IngestionRunRepository.JobExecutionContext> context =
                runs.findJobContext(claimed.get().jobId());
        if (context.isEmpty()) {
            // A job with no active read grant, or one that has been paused, is
            // not executable. Failing it terminally puts it in front of a
            // person rather than retrying a configuration problem forever.
            runs.transition(runId, fence, workerName, "FAILED_TERMINAL", null,
                    "JOB_NOT_EXECUTABLE");
            return new RunOutcome(runId, 0, "JOB_NOT_EXECUTABLE");
        }

        runs.transition(runId, fence, workerName, "RUNNING", leaseSeconds(), null);

        if ("REPLAY".equals(claimed.get().runKind())) {
            runs.transition(runId, fence, workerName, "SUCCEEDED", null, null);
            return new RunOutcome(runId, 0, "REPLAY_NO_ACQUISITION");
        }
        return acquirePages(runId, fence, workerName, context.get());
    }

    private RunOutcome acquirePages(UUID runId,
                                    long fence,
                                    String workerName,
                                    IngestionRunRepository.JobExecutionContext context) {
        int pagesStored = 0;
        for (int page = 0; page < properties.getMaximumCallsPerRun(); page++) {
            AcquisitionPageWorker.PageOutcome outcome;
            try {
                outcome = pageWorker.acquireOnePage(runId, fence, workerName, context);
            } catch (RuntimeException failure) {
                log.atWarn()
                        .addKeyValue("event", "acquisition_page_failed")
                        .addKeyValue("jobCode", context.jobCode())
                        .addKeyValue("exceptionClass", failure.getClass().getName())
                        .addKeyValue("correlationId", CorrelationId.current())
                        .log("An acquisition page could not be completed");
                return rest(runId, fence, workerName, pagesStored, "PAGE_FAILED");
            }

            pagesStored++;
            switch (outcome.kind()) {
                case UNKNOWN_RESULT -> {
                    runs.transition(runId, fence, workerName, "BLOCKED", null, null);
                    return new RunOutcome(runId, pagesStored, "UNKNOWN_RESULT");
                }
                case SOURCE_EXHAUSTED -> {
                    runs.transition(runId, fence, workerName, "SUCCEEDED", null, null);
                    return new RunOutcome(runId, pagesStored, "SUCCEEDED");
                }
                case PAGE_STORED -> runs.renewLease(runId, fence, workerName, leaseSeconds());
            }
        }
        return rest(runId, fence, workerName, pagesStored, "CALL_CEILING_REACHED");
    }

    /**
     * Put a run down, retrying it if its budget allows and failing it if not.
     *
     * <p>The budget is counted in claims rather than in page failures, because a
     * run that keeps being taken over is failing just as surely as one that
     * keeps throwing, and an unbounded retry turns a permanent problem into
     * permanent traffic against a marketplace.
     */
    private RunOutcome rest(UUID runId, long fence, String workerName,
                            int pagesStored, String reason) {
        boolean budgetLeft = runs.findRun(runId)
                .map(state -> state.attemptNo() <= properties.getRetryBudget())
                .orElse(false);
        if (budgetLeft) {
            runs.transition(runId, fence, workerName, "RETRY_WAIT", null, null);
            return new RunOutcome(runId, pagesStored, reason);
        }
        runs.transition(runId, fence, workerName, "FAILED_TERMINAL", null, reason);
        return new RunOutcome(runId, pagesStored, "FAILED_TERMINAL");
    }

    private int leaseSeconds() {
        return Math.toIntExact(properties.getLeaseDuration().toSeconds());
    }

    /**
     * What one run produced.
     *
     * @param runId the run
     * @param pagesStored how many pages reached custody
     * @param reason why the run stopped where it did
     */
    public record RunOutcome(UUID runId, int pagesStored, String reason) {
    }
}
