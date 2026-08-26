package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.IngestionJobView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code platform.ingestion_job} for published reads. */
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
