package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.ScheduledAcquisition.RunRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** What the collection scheduler reads about runs and evidence before it starts a run. */
@Repository
public class ScheduledAcquisitionRepository {

    private static final String RUN_COLUMNS = """
            SELECT id, job_id, run_kind, state, window_from, window_to, failure_code,
                   created_at, updated_at, next_attempt_at
              FROM ops.ingestion_run
            """;

    private final JdbcClient jdbc;

    ScheduledAcquisitionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The job's runs created at or after {@code since}, newest first. */
    public List<RunRecord> runsSince(UUID jobId, Instant since) {
        return jdbc.sql(RUN_COLUMNS + """
                         WHERE job_id = :jobId AND created_at >= :since
                         ORDER BY created_at DESC, id DESC
                        """)
                .param("jobId", jobId)
                .param("since", Timestamp.from(since))
                .query(ScheduledAcquisitionRepository::mapRun)
                .list();
    }

    /** The job's one run that has not come to rest. */
    public Optional<RunRecord> liveRun(UUID jobId) {
        return jdbc.sql(RUN_COLUMNS + """
                         WHERE job_id = :jobId
                           AND state IN ('QUEUED', 'LEASED', 'RUNNING', 'RETRY_WAIT', 'BLOCKED')
                        """)
                .param("jobId", jobId)
                .query(ScheduledAcquisitionRepository::mapRun)
                .optional();
    }

    /** One run. */
    public Optional<RunRecord> run(UUID runId) {
        return jdbc.sql(RUN_COLUMNS + " WHERE id = :runId")
                .param("runId", runId)
                .query(ScheduledAcquisitionRepository::mapRun)
                .optional();
    }

    /**
     * Until when approved real-account evidence covers the job's endpoint under the capability's
     * current registry configuration: the same conditions platform.capability_evidence_current
     * applies to every call.
     */
    public Optional<Instant> evidenceValidUntil(UUID jobId) {
        Timestamp validUntil = jdbc.sql("""
                        SELECT max(evidence.valid_until)
                          FROM platform.ingestion_job AS job
                          JOIN platform.platform_endpoint AS endpoint ON endpoint.id = job.endpoint_id
                          JOIN platform.registry_verification_case AS evidence
                            ON evidence.marketplace_account_id = job.marketplace_account_id
                           AND evidence.capability_id = endpoint.capability_id
                         WHERE job.id = :jobId
                           AND evidence.state = 'APPROVED' AND evidence.evidence_class = 'REAL_ACCOUNT'
                           AND evidence.tested_at <= statement_timestamp()
                           AND evidence.valid_until > statement_timestamp()
                           AND job.endpoint_id = ANY (evidence.endpoint_ids)
                           AND evidence.configuration_snapshot
                               = platform.registry_configuration_snapshot(endpoint.capability_id)
                        """)
                .param("jobId", jobId)
                .query(Timestamp.class)
                .optional()
                .orElse(null);
        return Optional.ofNullable(validUntil).map(Timestamp::toInstant);
    }

    private static RunRecord mapRun(ResultSet rows, int rowNumber) throws SQLException {
        return new RunRecord(
                rows.getObject("id", UUID.class),
                rows.getObject("job_id", UUID.class),
                rows.getString("run_kind"),
                rows.getString("state"),
                instant(rows.getTimestamp("window_from")),
                instant(rows.getTimestamp("window_to")),
                rows.getString("failure_code"),
                instant(rows.getTimestamp("created_at")),
                instant(rows.getTimestamp("updated_at")),
                instant(rows.getTimestamp("next_attempt_at")));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
