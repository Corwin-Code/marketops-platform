package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.Availability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilitySubjectStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code platform.capability_subject_status}. */
@Repository
public class SubjectStatusRepository {

    private final JdbcClient jdbc;

    SubjectStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new subject-status row. */
    public void insert(CapabilitySubjectStatus status) {
        jdbc.sql("""
                        INSERT INTO platform.capability_subject_status (
                            id, organization_id, platform_code, capability_id,
                            marketplace_account_id, store_id, availability,
                            last_verified_at, evidence_ref, verified_source_title,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :platformCode, :capabilityId,
                            :marketplaceAccountId, :storeId, :availability,
                            :lastVerifiedAt, :evidenceRef, :verifiedSourceTitle,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", status.id())
                .param("organizationId", status.organizationId())
                .param("platformCode", status.platformCode())
                .param("capabilityId", status.capabilityId())
                .param("marketplaceAccountId", status.marketplaceAccountId())
                .param("storeId", status.storeId())
                .param("availability", status.availability().name())
                .param("lastVerifiedAt", status.lastVerifiedAt() == null
                        ? null : Timestamp.from(status.lastVerifiedAt()))
                .param("evidenceRef", status.evidenceRef())
                .param("verifiedSourceTitle", status.verifiedSourceTitle())
                .param("createdAt", Timestamp.from(status.createdAt()))
                .param("updatedAt", Timestamp.from(status.updatedAt()))
                .param("version", status.version())
                .update();
    }

    /** Load one subject-status row. */
    public Optional<CapabilitySubjectStatus> findById(UUID id) {
        return jdbc.sql("SELECT * FROM platform.capability_subject_status WHERE id = :id")
                .param("id", id)
                .query(SubjectStatusRepository::map)
                .optional();
    }

    /** Load the row of one capability and one account subject, if any. */
    public Optional<CapabilitySubjectStatus> findByCapabilityAndAccount(
            UUID capabilityId, UUID marketplaceAccountId) {
        return jdbc.sql("""
                        SELECT * FROM platform.capability_subject_status
                        WHERE capability_id = :capabilityId
                          AND marketplace_account_id = :marketplaceAccountId
                        """)
                .param("capabilityId", capabilityId)
                .param("marketplaceAccountId", marketplaceAccountId)
                .query(SubjectStatusRepository::map)
                .optional();
    }

    /** Load the row of one capability and one store subject, if any. */
    public Optional<CapabilitySubjectStatus> findByCapabilityAndStore(
            UUID capabilityId, UUID storeId) {
        return jdbc.sql("""
                        SELECT * FROM platform.capability_subject_status
                        WHERE capability_id = :capabilityId AND store_id = :storeId
                        """)
                .param("capabilityId", capabilityId)
                .param("storeId", storeId)
                .query(SubjectStatusRepository::map)
                .optional();
    }

    /** List a capability's subject-status rows. */
    public List<CapabilitySubjectStatus> listByCapability(UUID capabilityId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM platform.capability_subject_status
                        WHERE capability_id = :capabilityId
                        ORDER BY created_at, id
                        LIMIT :pageLimit
                        """)
                .param("capabilityId", capabilityId)
                .param("pageLimit", limit)
                .query(SubjectStatusRepository::map)
                .list();
    }

    private static CapabilitySubjectStatus map(ResultSet row, int rowNumber)
            throws SQLException {
        Timestamp lastVerifiedAt = row.getTimestamp("last_verified_at");
        return new CapabilitySubjectStatus(
                row.getObject("id", UUID.class),
                row.getObject("organization_id", UUID.class),
                row.getString("platform_code"),
                row.getObject("capability_id", UUID.class),
                row.getObject("marketplace_account_id", UUID.class),
                row.getObject("store_id", UUID.class),
                Availability.valueOf(row.getString("availability")),
                lastVerifiedAt == null ? null : lastVerifiedAt.toInstant(),
                row.getString("evidence_ref"),
                row.getString("verified_source_title"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
