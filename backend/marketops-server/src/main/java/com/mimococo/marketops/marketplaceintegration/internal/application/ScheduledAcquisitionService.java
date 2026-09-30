package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.marketplaceintegration.ScheduledAcquisition;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.ScheduledAcquisitionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheduled runs go through the same service, checks and audit as manual ones; this only adds what
 * a scheduler has to read first.
 */
@Service
class ScheduledAcquisitionService implements ScheduledAcquisition {

    private final IngestionJobService jobs;
    private final ScheduledAcquisitionRepository runs;

    ScheduledAcquisitionService(IngestionJobService jobs, ScheduledAcquisitionRepository runs) {
        this.jobs = jobs;
        this.runs = runs;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RunRecord> runsSince(UUID jobId, Instant since) {
        return runs.runsSince(jobId, since);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RunRecord> liveRun(UUID jobId) {
        return runs.liveRun(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> evidenceValidUntil(UUID jobId) {
        return runs.evidenceValidUntil(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Instant> credentialExpiresAt(UUID jobId) {
        return runs.credentialExpiresAt(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> lastAnswerStatus(UUID runId) {
        return runs.lastAnswerStatus(runId);
    }

    @Override
    public RunRecord enqueueScheduled(UUID jobId, Instant windowFrom, Instant windowTo, String actorId) {
        UUID runId = jobs.enqueueScheduledRun(actorId, jobId, windowFrom, windowTo).id();
        return runs.run(runId).orElseThrow();
    }

    @Override
    public Execution execute(UUID runId, String actorId) {
        IngestionJobService.ExecutionResult result = jobs.executeRun(actorId, runId);
        return new Execution(runs.run(runId).orElseThrow(), result.pagesStored(), result.reason());
    }
}
