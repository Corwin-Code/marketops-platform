package com.mimococo.marketops.marketplaceintegration.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.marketplaceintegration.internal.application.IngestionJobService;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionJobRepository.JobRow;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository.RunState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Maintenance commands and queries for acquisition jobs and their manual runs.
 *
 * <p>Loopback-only like every metadata maintenance route: mutations need the
 * environment write switch and operator attribution. Queuing or executing a run
 * never widens what a call may do; the database authorises each call.
 */
@RestController
@RequestMapping("/api/v1/admin/metadata")
class IngestionJobAdminController {

    private final IngestionJobService jobService;

    IngestionJobAdminController(IngestionJobService jobService) {
        this.jobService = jobService;
    }

    /** Register an acquisition job. */
    @PostMapping(value = "/ingestion-jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    JobView create(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                   @Valid @RequestBody CreateJobRequest request) {
        return JobView.of(jobService.create(operator, request.marketplaceAccountId(),
                request.serviceAccountId(), request.endpointId(), request.storeId(),
                request.datasetKind(), request.jobCode(), request.displayName()));
    }

    /** Pause, resume or retire a job. */
    @PostMapping(value = "/ingestion-jobs/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    JobView changeStatus(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                         @PathVariable UUID id,
                         @Valid @RequestBody StatusChangeRequest request) {
        return JobView.of(jobService.changeStatus(operator, id, request.target(),
                request.reason(), request.expectedVersion()));
    }

    /** Queue one MANUAL run for a job. */
    @PostMapping(value = "/ingestion-jobs/{id}/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    RunView enqueue(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                    @PathVariable UUID id) {
        return RunView.of(jobService.enqueueManualRun(operator, id));
    }

    /** Claim and execute a queued run now; answers once the run rests. */
    @PostMapping(value = "/ingestion-runs/{runId}/execution",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ExecutionView execute(@RequestAttribute(OperatorAttribution.REQUEST_ATTRIBUTE) String operator,
                          @PathVariable UUID runId) {
        IngestionJobService.ExecutionResult result = jobService.executeRun(operator, runId);
        return new ExecutionView(RunView.of(result.run()), result.pagesStored(), result.reason());
    }

    /** Load one job. */
    @GetMapping(value = "/ingestion-jobs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    JobView get(@PathVariable UUID id) {
        return JobView.of(jobService.job(id));
    }

    /** List one account's jobs. */
    @GetMapping(value = "/ingestion-jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    List<JobView> list(@RequestParam UUID marketplaceAccountId) {
        return jobService.jobsForAccount(marketplaceAccountId).stream().map(JobView::of).toList();
    }

    /** Load one run. */
    @GetMapping(value = "/ingestion-runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    RunView run(@PathVariable UUID runId) {
        return RunView.of(jobService.run(runId));
    }

    record CreateJobRequest(
            @NotNull UUID marketplaceAccountId,
            @NotNull UUID serviceAccountId,
            @NotNull UUID endpointId,
            UUID storeId,
            String datasetKind,
            @NotBlank String jobCode,
            @NotBlank String displayName) {
    }

    record StatusChangeRequest(
            @NotBlank String target,
            @NotBlank String reason,
            @NotNull Long expectedVersion) {
    }

    record JobView(UUID id, UUID organizationId, UUID marketplaceAccountId, String platformCode,
                   UUID serviceAccountId, UUID endpointId, UUID storeId, String datasetKind,
                   String jobCode, String displayName, String status, Instant createdAt,
                   Instant updatedAt, long version) {
        static JobView of(JobRow job) {
            return new JobView(job.id(), job.organizationId(), job.marketplaceAccountId(),
                    job.platformCode(), job.serviceAccountId(), job.endpointId(), job.storeId(),
                    job.datasetKind(), job.jobCode(), job.displayName(), job.status(),
                    job.createdAt(), job.updatedAt(), job.version());
        }
    }

    /** A run without its lease holder or fence: those identify a worker, not an outcome. */
    record RunView(UUID id, UUID jobId, String state, String runKind, int attemptNo,
                   int lastCallSeq, String failureCode) {
        static RunView of(RunState run) {
            return new RunView(run.id(), run.jobId(), run.state(), run.runKind(),
                    run.attemptNo(), run.lastCallSeq(), run.failureCode());
        }
    }

    record ExecutionView(RunView run, int pagesStored, String reason) {
    }
}
