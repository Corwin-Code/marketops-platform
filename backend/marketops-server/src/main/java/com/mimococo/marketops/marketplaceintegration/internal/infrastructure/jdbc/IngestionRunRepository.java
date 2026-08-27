package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The run lifecycle as the worker sees it.
 *
 * <p>Every transition is a function call rather than an update, because the
 * application role holds no write privilege on the run. That is not a style
 * choice: it is what makes the lease, the fence and the reviewed transition set
 * true for any client that connects as this role, including one that is not
 * this application.
 */
@Repository
public class IngestionRunRepository {

    private final JdbcClient jdbc;

    IngestionRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Create a run for a job, or refuse because one is already live. */
    public UUID enqueue(UUID runId, UUID jobId, String runKind,
                        Instant windowFrom, Instant windowTo) {
        return enqueue(runId, jobId, runKind, windowFrom, windowTo, 4);
    }

    public UUID enqueue(UUID runId, UUID jobId, String runKind,
                        Instant windowFrom, Instant windowTo, int maxClaims) {
        return jdbc.sql("""
                        SELECT ops.enqueue_ingestion_run(
                            :runId, :jobId, :runKind, :windowFrom, :windowTo, :maxClaims)
                        """)
                .param("runId", runId)
                .param("jobId", jobId)
                .param("runKind", runKind)
                .param("windowFrom", windowFrom == null ? null : Timestamp.from(windowFrom))
                .param("windowTo", windowTo == null ? null : Timestamp.from(windowTo))
                .param("maxClaims", maxClaims)
                .query(UUID.class)
                .single();
    }

    /** Claim a run and return the fence token the worker must carry. */
    public long claim(UUID runId, String leaseOwner, int leaseSeconds) {
        return jdbc.sql("SELECT ops.claim_ingestion_run(:runId, :leaseOwner, :leaseSeconds)")
                .param("runId", runId)
                .param("leaseOwner", leaseOwner)
                .param("leaseSeconds", leaseSeconds)
                .query(Long.class)
                .optional().orElse(0L);
    }

    /** Move a claimed run between its working states. */
    public String transition(UUID runId, long fenceToken, String leaseOwner,
                             String toState, Integer leaseSeconds, String failureCode) {
        return transition(runId, fenceToken, leaseOwner, toState, leaseSeconds, failureCode, 120);
    }

    public String transition(UUID runId, long fenceToken, String leaseOwner,
                             String toState, Integer leaseSeconds, String failureCode, int retryDelaySeconds) {
        return jdbc.sql("""
                        SELECT ops.transition_ingestion_run(
                            :runId, :fenceToken, :leaseOwner, :toState,
                            :leaseSeconds, :failureCode, :retryDelaySeconds)
                        """)
                .param("runId", runId)
                .param("fenceToken", fenceToken)
                .param("leaseOwner", leaseOwner)
                .param("toState", toState)
                .param("leaseSeconds", leaseSeconds)
                .param("failureCode", failureCode)
                .param("retryDelaySeconds", retryDelaySeconds)
                .query(String.class)
                .single();
    }

    /** Extend a live lease without changing state. */
    public Instant renewLease(UUID runId, long fenceToken, String leaseOwner, int leaseSeconds) {
        Timestamp renewed = jdbc.sql("""
                        SELECT ops.renew_ingestion_run_lease(
                            :runId, :fenceToken, :leaseOwner, :leaseSeconds)
                        """)
                .param("runId", runId)
                .param("fenceToken", fenceToken)
                .param("leaseOwner", leaseOwner)
                .param("leaseSeconds", leaseSeconds)
                .query(Timestamp.class)
                .single();
        return renewed.toInstant();
    }

    /** Advance the acquisition cursor against committed evidence. */
    public long acknowledgeCheckpoint(UUID runId, long fenceToken, String leaseOwner,
                                      UUID observationId, long expectedVersion,
                                      String position) {
        return jdbc.sql("""
                        SELECT ops.acknowledge_checkpoint(
                            :runId, :fenceToken, :leaseOwner, :observationId,
                            :expectedVersion, :position)
                        """)
                .param("runId", runId)
                .param("fenceToken", fenceToken)
                .param("leaseOwner", leaseOwner)
                .param("observationId", observationId)
                .param("expectedVersion", expectedVersion)
                .param("position", position)
                .query(Long.class)
                .single();
    }

    /** The current checkpoint version of one job. */
    public long checkpointVersion(UUID jobId) {
        return jdbc.sql("""
                        SELECT checkpoint_version FROM ops.ingestion_checkpoint
                         WHERE job_id = :jobId
                        """)
                .param("jobId", jobId)
                .query(Long.class)
                .single();
    }

    /** Load one run. */
    public Optional<RunState> findRun(UUID runId) {
        return jdbc.sql("""
                        SELECT id, job_id, state, fence_token, lease_owner,
                               lease_expires_at, attempt_no, last_call_seq, run_kind,
                               window_from, window_to, failure_code
                          FROM ops.ingestion_run WHERE id = :runId
                        """)
                .param("runId", runId)
                .query(IngestionRunRepository::mapRun)
                .optional();
    }

    /** Runs that a worker may claim right now, oldest first. */
    public List<RunState> claimableRuns(int limit) {
        return jdbc.sql("""
                        SELECT id, job_id, state, fence_token, lease_owner,
                               lease_expires_at, attempt_no, last_call_seq, run_kind,
                               window_from, window_to, failure_code
                          FROM ops.ingestion_run
                         WHERE state = 'QUEUED' OR (state='RETRY_WAIT' AND next_attempt_at <= clock_timestamp())
                            OR (state IN ('LEASED', 'RUNNING')
                                AND lease_expires_at <= clock_timestamp())
                         ORDER BY updated_at
                         LIMIT :pageLimit
                        """)
                .param("pageLimit", limit)
                .query(IngestionRunRepository::mapRun)
                .list();
    }

    /**
     * The job's execution context: what it acquires, and with which identity.
     *
     * <p>The scope grant is resolved here rather than chosen by the worker,
     * because the grant primitive validates the exact grant it is handed and a
     * worker that picked the wrong one would be refused with a message that
     * looks like a permission failure rather than a wiring mistake.
     */
    public Optional<JobExecutionContext> findJobContext(UUID jobId) {
        return jdbc.sql("""
                        SELECT job.id AS job_id, job.organization_id, job.platform_code,
                               job.marketplace_account_id, job.endpoint_id,
                               job.dataset_kind, job.job_code,
                               scope_grant.id AS scope_grant_id
                          FROM platform.ingestion_job AS job
                          JOIN iam.service_account_scope_grant AS scope_grant
                            ON scope_grant.service_account_id = job.service_account_id
                           AND scope_grant.permission_code = 'READ'
                           AND scope_grant.status = 'ACTIVE'
                           AND scope_grant.marketplace_account_ref_id
                                   = job.marketplace_account_id
                           AND scope_grant.effective_from <= clock_timestamp()
                           AND (scope_grant.effective_to IS NULL
                                OR scope_grant.effective_to > clock_timestamp())
                         WHERE job.id = :jobId
                           AND job.status = 'ACTIVE'
                        """)
                .param("jobId", jobId)
                .query((rows, rowNumber) -> new JobExecutionContext(
                        rows.getObject("job_id", UUID.class),
                        rows.getObject("organization_id", UUID.class),
                        rows.getString("platform_code"),
                        rows.getObject("marketplace_account_id", UUID.class),
                        rows.getObject("endpoint_id", UUID.class),
                        rows.getString("dataset_kind"),
                        rows.getString("job_code"),
                        rows.getObject("scope_grant_id", UUID.class)))
                .optional();
    }

    private static RunState mapRun(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp leaseExpires = rows.getTimestamp("lease_expires_at");
        Timestamp windowFrom = rows.getTimestamp("window_from");
        Timestamp windowTo = rows.getTimestamp("window_to");
        return new RunState(
                rows.getObject("id", UUID.class),
                rows.getObject("job_id", UUID.class),
                rows.getString("state"),
                rows.getLong("fence_token"),
                rows.getString("lease_owner"),
                leaseExpires == null ? null : leaseExpires.toInstant(),
                rows.getInt("attempt_no"),
                rows.getInt("last_call_seq"),
                rows.getString("run_kind"),
                windowFrom == null ? null : windowFrom.toInstant(),
                windowTo == null ? null : windowTo.toInstant(),
                rows.getString("failure_code"));
    }

    /**
     * One run as stored.
     *
     * @param id identifier
     * @param jobId the job this run executes
     * @param state where the run stands
     * @param fenceToken token an authoritative write must carry
     * @param leaseOwner current holder, or {@code null}
     * @param leaseExpiresAt when the lease lapses, or {@code null}
     * @param attemptNo how many times the run has been claimed
     * @param lastCallSeq how many calls the run has made
     * @param runKind why the run exists
     * @param windowFrom start of a bounded window, or {@code null}
     * @param windowTo end of a bounded window, or {@code null}
     * @param failureCode why it failed terminally, or {@code null}
     */
    public record RunState(
            UUID id,
            UUID jobId,
            String state,
            long fenceToken,
            String leaseOwner,
            Instant leaseExpiresAt,
            int attemptNo,
            int lastCallSeq,
            String runKind,
            Instant windowFrom,
            Instant windowTo,
            String failureCode) {
    }

    /**
     * What a job needs in order to run.
     *
     * @param jobId the job
     * @param organizationId owning organization
     * @param platformCode marketplace the job reads
     * @param marketplaceAccountId account the job reads for
     * @param endpointId endpoint the job reads
     * @param datasetKind what the job acquires
     * @param jobCode business code, used as the custody namespace
     * @param scopeGrantId the grant the call authority is issued against
     */
    public record JobExecutionContext(
            UUID jobId,
            UUID organizationId,
            String platformCode,
            UUID marketplaceAccountId,
            UUID endpointId,
            String datasetKind,
            String jobCode,
            UUID scopeGrantId) {
    }
}
