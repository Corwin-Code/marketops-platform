package com.mimococo.marketops.marketplaceintegration.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.identityaccess.AccessMetadataDirectory;
import com.mimococo.marketops.identityaccess.ServiceAccountEvaluation;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformEndpoint;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ReadWriteClass;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.EndpointRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionJobRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionJobRepository.JobRow;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository;
import com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc.IngestionRunRepository.RunState;
import com.mimococo.marketops.organizationaccount.MarketplaceAccountRef;
import com.mimococo.marketops.organizationaccount.OrganizationDirectory;
import com.mimococo.marketops.organizationaccount.StoreRef;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Maintenance of acquisition jobs and their manual runs.
 *
 * <p>A job binds one marketplace account, one service account and one READ
 * endpoint. Creating it grants nothing: every call is still authorised by the
 * database at its own instant against the service account's scope grant, the
 * account's READ credential and the endpoint's verified registry state.
 *
 * <p>Runs started here are MANUAL and are executed in the request that asks for
 * it, so an operator can take one acquisition from queue to resting state while
 * the scheduler stays off. The run is claimed and fenced exactly as a scheduled
 * worker would claim it; the operator only chooses when.
 */
@Service
public class IngestionJobService {

    static final String ENTITY_TYPE = "ingestion-job";
    static final String RUN_ENTITY_TYPE = "ingestion-run";

    private static final Set<String> DATASET_KINDS = Set.of(
            "LISTING", "LISTING_HEALTH", "LISTING_CONTENT", "PRICE", "STOCK", "TRAFFIC", "SALES",
            "RETURNS", "FINANCE", "ADVERTISING", "UNKNOWN");

    /** Job status moves a maintenance operator may make; RETIRED is final. */
    private static final Map<String, Set<String>> STATUS_MOVES = Map.of(
            "ACTIVE", Set.of("PAUSED", "RETIRED"),
            "PAUSED", Set.of("ACTIVE", "RETIRED"));

    /** SQLSTATE the enqueue primitive raises when the job already has a live run. */
    private static final String LIVE_RUN_EXISTS = "MO040";

    private final IngestionJobRepository jobs;
    private final IngestionRunRepository runs;
    private final EndpointRepository endpoints;
    private final AcquisitionRunner runner;
    private final OrganizationDirectory organizationDirectory;
    private final AccessMetadataDirectory accessMetadata;
    private final MetadataAuditRecorder auditRecorder;
    private final TransactionTemplate transactions;
    private final IdGenerator idGenerator;
    private final Clock clock;

    IngestionJobService(IngestionJobRepository jobs,
                        IngestionRunRepository runs,
                        EndpointRepository endpoints,
                        AcquisitionRunner runner,
                        OrganizationDirectory organizationDirectory,
                        AccessMetadataDirectory accessMetadata,
                        MetadataAuditRecorder auditRecorder,
                        PlatformTransactionManager transactionManager,
                        IdGenerator idGenerator,
                        Clock clock) {
        this.jobs = jobs;
        this.runs = runs;
        this.endpoints = endpoints;
        this.runner = runner;
        this.organizationDirectory = organizationDirectory;
        this.accessMetadata = accessMetadata;
        this.auditRecorder = auditRecorder;
        this.transactions = new TransactionTemplate(transactionManager);
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /** Register a job; it starts ACTIVE and has no run until one is queued. */
    @Transactional
    public JobRow create(String operator,
                         UUID marketplaceAccountId,
                         UUID serviceAccountId,
                         UUID endpointId,
                         UUID storeId,
                         String datasetKind,
                         String jobCode,
                         String displayName) {
        MarketplaceAccountRef account = organizationDirectory
                .marketplaceAccount(Objects.requireNonNullElse(marketplaceAccountId, new UUID(0, 0)))
                .orElseThrow(() -> notFound(null));
        if (!"ACTIVE".equals(account.status())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        PlatformEndpoint endpoint = endpoints
                .findById(Objects.requireNonNullElse(endpointId, new UUID(0, 0)))
                .orElseThrow(() -> notFound(null));
        if (!endpoint.platformCode().equals(account.platformCode())
                || endpoint.readWriteClass() != ReadWriteClass.READ) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (serviceAccountId == null
                || accessMetadata.evaluate(serviceAccountId) != ServiceAccountEvaluation.ACTIVE) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        if (storeId != null) {
            StoreRef store = organizationDirectory.store(storeId).orElseThrow(() -> notFound(null));
            if (!store.marketplaceAccountId().equals(account.id())) {
                throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
            }
        }
        String validKind = datasetKind == null ? "UNKNOWN" : datasetKind;
        if (!DATASET_KINDS.contains(validKind)) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        String validCode = MetadataFieldPolicy.requireCode(jobCode);
        String validName = MetadataFieldPolicy.requireText("displayName", displayName);
        jobs.findIdByCode(account.organizationId(), validCode).ifPresent(existing -> {
            throw OperationRejectedException.duplicate(
                    AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                    ENTITY_TYPE, validCode, existing);
        });

        Instant now = clock.instant();
        JobRow job = new JobRow(idGenerator.newId(), account.organizationId(), account.id(),
                account.platformCode(), serviceAccountId, endpoint.id(), storeId, validKind,
                validCode, validName, "ACTIVE", now, now, 0L);
        jobs.insert(job);
        Map<String, FieldChange> changes = new HashMap<>(Map.of(
                "marketplaceAccountId", new FieldChange(null, account.id().toString()),
                "serviceAccountId", new FieldChange(null, serviceAccountId.toString()),
                "endpointId", new FieldChange(null, endpoint.id().toString()),
                "datasetKind", new FieldChange(null, validKind),
                "status", new FieldChange(null, "ACTIVE")));
        if (storeId != null) {
            changes.put("storeId", new FieldChange(null, storeId.toString()));
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                ENTITY_TYPE, job.id(), validCode, changes, null, null));
        return job;
    }

    /** Pause, resume or retire a job. */
    @Transactional
    public JobRow changeStatus(String operator, UUID jobId, String target, String reason,
                               long expectedVersion) {
        JobRow current = require(jobId);
        String validReason = MetadataFieldPolicy.requireText("reason", reason);
        if (target == null || !STATUS_MOVES.getOrDefault(current.status(), Set.of()).contains(target)) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if (!jobs.updateStatus(jobId, target, expectedVersion, clock.instant())) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, jobId, current.jobCode(),
                Map.of("status", new FieldChange(current.status(), target)), validReason, null));
        return require(jobId);
    }

    /** Queue one MANUAL run; refused while the job is not ACTIVE or already has a live run. */
    @Transactional
    public RunState enqueueManualRun(String operator, UUID jobId, Instant windowFrom,
                                     Instant windowTo) {
        JobRow job = require(jobId);
        if (!"ACTIVE".equals(job.status())) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        if ((windowFrom == null) != (windowTo == null)
                || (windowFrom != null && !windowFrom.isBefore(windowTo))) {
            throw OperationRejectedException.of(ErrorCode.VALIDATION_FAILED);
        }
        UUID runId;
        try {
            runId = runner.enqueue(jobId, "MANUAL", windowFrom, windowTo);
        } catch (DataAccessException refused) {
            if (LIVE_RUN_EXISTS.equals(sqlState(refused))) {
                throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
            }
            throw refused;
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.CREATE,
                RUN_ENTITY_TYPE, runId, job.jobCode(),
                windowFrom == null
                        ? Map.of("runKind", new FieldChange(null, "MANUAL"),
                                "jobId", new FieldChange(null, jobId.toString()))
                        : Map.of("runKind", new FieldChange(null, "MANUAL"),
                                "jobId", new FieldChange(null, jobId.toString()),
                                "window", new FieldChange(null, windowFrom + "/" + windowTo)),
                null, null));
        return requireRun(runId);
    }

    /**
     * Claim and execute one queued run now, in this request.
     *
     * <p>Not transactional: the runner claims, calls and finishes in separate
     * transactions, exactly as the scheduler would. The audit record of who
     * asked, and where the run came to rest, is written in its own transaction
     * afterwards, because the recorder requires one.
     */
    public ExecutionResult executeRun(String operator, UUID runId) {
        RunState before = requireRun(runId);
        AcquisitionRunner.RunOutcome outcome = runner.execute(runId, WorkerIdentity.current());
        RunState after = requireRun(runId);
        transactions.executeWithoutResult(status -> auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.MARKETPLACE_INTEGRATION, operator, AuditAction.STATUS_CHANGE,
                RUN_ENTITY_TYPE, runId, null,
                Map.of("state", new FieldChange(before.state(), after.state())),
                outcome.reason(), null)));
        return new ExecutionResult(after, outcome.pagesStored(), outcome.reason());
    }

    /** Load one job. */
    @Transactional(readOnly = true)
    public JobRow job(UUID jobId) {
        return require(jobId);
    }

    /** List one account's jobs. */
    @Transactional(readOnly = true)
    public List<JobRow> jobsForAccount(UUID marketplaceAccountId) {
        return jobs.listRowsByAccount(marketplaceAccountId);
    }

    /** Load one run. */
    @Transactional(readOnly = true)
    public RunState run(UUID runId) {
        return requireRun(runId);
    }

    /**
     * The job's run that has not come to rest. A job has at most one, and it
     * blocks the next run until it finishes.
     */
    @Transactional(readOnly = true)
    public Optional<RunState> liveRun(UUID jobId) {
        require(jobId);
        return runs.findLiveRun(jobId);
    }

    private JobRow require(UUID jobId) {
        return jobs.findRow(Objects.requireNonNullElse(jobId, new UUID(0, 0)))
                .orElseThrow(() -> notFound(jobId));
    }

    private RunState requireRun(UUID runId) {
        return runs.findRun(Objects.requireNonNullElse(runId, new UUID(0, 0)))
                .orElseThrow(() -> OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(),
                        RUN_ENTITY_TYPE, runId, null));
    }

    private static OperationRejectedException notFound(UUID id) {
        return OperationRejectedException.forEntity(ErrorCode.RESOURCE_NOT_FOUND,
                AuditSourceDomain.MARKETPLACE_INTEGRATION.dbValue(), ENTITY_TYPE, id, null);
    }

    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (current.getCause() == current) {
                return null;
            }
        }
        return null;
    }

    /**
     * What executing one run left behind.
     *
     * @param run the run in its resting state
     * @param pagesStored how many pages reached custody
     * @param reason why the run stopped where it did
     */
    public record ExecutionResult(RunState run, int pagesStored, String reason) {
    }
}
