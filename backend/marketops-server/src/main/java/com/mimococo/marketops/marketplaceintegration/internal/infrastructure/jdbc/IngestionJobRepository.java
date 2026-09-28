package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
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
 * Relational access to {@code platform.ingestion_job}: published reads, and the
 * maintenance writes that create a job and change its status.
 */
@Repository
public class IngestionJobRepository {

    private final JdbcClient jdbc;

    IngestionJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Load one job. */
    public Optional<IngestionJobView> findById(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, organization_id, platform_code, marketplace_account_id,
                               store_id, dataset_kind, job_code, status
                          FROM platform.ingestion_job WHERE id = :jobId
                        """)
                .param("jobId", jobId)
                .query(IngestionJobRepository::map)
                .optional();
    }

    /** List an organization's jobs. */
    public List<IngestionJobView> listByOrganization(UUID organizationId) {
        return jdbc.sql("""
                        SELECT id, organization_id, platform_code, marketplace_account_id,
                               store_id, dataset_kind, job_code, status
                          FROM platform.ingestion_job
                         WHERE organization_id = :organizationId
                         ORDER BY job_code
                        """)
                .param("organizationId", organizationId)
                .query(IngestionJobRepository::map)
                .list();
    }

    /** Load one job with the acquisition identities maintenance works with. */
    public Optional<JobRow> findRow(UUID jobId) {
        return jdbc.sql(ROW_SELECT + " WHERE id = :jobId")
                .param("jobId", jobId)
                .query(IngestionJobRepository::mapRow)
                .optional();
    }

    /** List one marketplace account's jobs with their acquisition identities. */
    public List<JobRow> listRowsByAccount(UUID marketplaceAccountId) {
        return jdbc.sql(ROW_SELECT + " WHERE marketplace_account_id = :accountId ORDER BY job_code")
                .param("accountId", marketplaceAccountId)
                .query(IngestionJobRepository::mapRow)
                .list();
    }

    /** The job an organization already knows by this code, if any. */
    public Optional<UUID> findIdByCode(UUID organizationId, String jobCode) {
        return jdbc.sql("""
                        SELECT id FROM platform.ingestion_job
                         WHERE organization_id = :organizationId AND job_code = :jobCode
                        """)
                .param("organizationId", organizationId)
                .param("jobCode", jobCode)
                .query(UUID.class)
                .optional();
    }

    /** Insert a new job. */
    public void insert(JobRow job) {
        jdbc.sql("""
                        INSERT INTO platform.ingestion_job (
                            id, organization_id, marketplace_account_id, platform_code,
                            service_account_id, endpoint_id, store_id, dataset_kind,
                            job_code, display_name, status, created_at, updated_at, version)
                        VALUES (
                            :id, :organizationId, :marketplaceAccountId, :platformCode,
                            :serviceAccountId, :endpointId, :storeId, :datasetKind,
                            :jobCode, :displayName, :status, :createdAt, :updatedAt, :version)
                        """)
                .param("id", job.id())
                .param("organizationId", job.organizationId())
                .param("marketplaceAccountId", job.marketplaceAccountId())
                .param("platformCode", job.platformCode())
                .param("serviceAccountId", job.serviceAccountId())
                .param("endpointId", job.endpointId())
                .param("storeId", job.storeId())
                .param("datasetKind", job.datasetKind())
                .param("jobCode", job.jobCode())
                .param("displayName", job.displayName())
                .param("status", job.status())
                .param("createdAt", Timestamp.from(job.createdAt()))
                .param("updatedAt", Timestamp.from(job.updatedAt()))
                .param("version", job.version())
                .update();
    }

    /** Move a job to another status; false when the version no longer matches. */
    public boolean updateStatus(UUID jobId, String status, long expectedVersion, Instant at) {
        return jdbc.sql("""
                        UPDATE platform.ingestion_job
                           SET status = :status, updated_at = :at, version = version + 1
                         WHERE id = :jobId AND version = :expectedVersion
                        """)
                .param("jobId", jobId)
                .param("status", status)
                .param("at", Timestamp.from(at))
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    private static final String ROW_SELECT = """
            SELECT id, organization_id, marketplace_account_id, platform_code,
                   service_account_id, endpoint_id, store_id, dataset_kind,
                   job_code, display_name, status, created_at, updated_at, version
              FROM platform.ingestion_job
            """;

    private static JobRow mapRow(ResultSet rows, int rowNumber) throws SQLException {
        return new JobRow(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("marketplace_account_id", UUID.class),
                rows.getString("platform_code"),
                rows.getObject("service_account_id", UUID.class),
                rows.getObject("endpoint_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getString("dataset_kind"),
                rows.getString("job_code"),
                rows.getString("display_name"),
                rows.getString("status"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getTimestamp("updated_at").toInstant(),
                rows.getLong("version"));
    }

    /**
     * A job as the maintenance surface sees it: the published view plus the
     * service account and endpoint the acquisition authority uses.
     *
     * @param id identifier
     * @param organizationId owning organization
     * @param marketplaceAccountId account the job reads for
     * @param platformCode marketplace the job reads
     * @param serviceAccountId subject the call authority is granted to
     * @param endpointId the one endpoint the job reads
     * @param storeId store the job's facts belong to, or {@code null}
     * @param datasetKind what the job acquires
     * @param jobCode business code, unique in the organization
     * @param displayName human name
     * @param status ACTIVE, PAUSED or RETIRED
     * @param createdAt creation instant
     * @param updatedAt last change instant
     * @param version optimistic concurrency version
     */
    public record JobRow(
            UUID id,
            UUID organizationId,
            UUID marketplaceAccountId,
            String platformCode,
            UUID serviceAccountId,
            UUID endpointId,
            UUID storeId,
            String datasetKind,
            String jobCode,
            String displayName,
            String status,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    private static IngestionJobView map(ResultSet rows, int rowNumber) throws SQLException {
        return new IngestionJobView(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getString("platform_code"),
                rows.getObject("marketplace_account_id", UUID.class),
                rows.getObject("store_id", UUID.class),
                rows.getString("dataset_kind"),
                rows.getString("job_code"),
                rows.getString("status"));
    }
}
