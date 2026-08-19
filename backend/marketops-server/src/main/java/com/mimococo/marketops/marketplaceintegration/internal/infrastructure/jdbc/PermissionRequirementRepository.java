package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.PermissionRequirement;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RequirementKind;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code platform.platform_permission_requirement}. */
@Repository
public class PermissionRequirementRepository {

    private final JdbcClient jdbc;

    PermissionRequirementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new requirement row. */
    public void insert(PermissionRequirement requirement) {
        jdbc.sql("""
                        INSERT INTO platform.platform_permission_requirement (
                            id, platform_code, capability_id, endpoint_id, requirement_kind,
                            external_code, description, verification_state, last_verified_at,
                            evidence_ref, verified_source_title, status,
                            created_at, updated_at, version)
                        VALUES (:id, :platformCode, :capabilityId, :endpointId, :requirementKind,
                            :externalCode, :description, :verificationState, :lastVerifiedAt,
                            :evidenceRef, :verifiedSourceTitle, :status,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", requirement.id())
                .param("platformCode", requirement.platformCode())
                .param("capabilityId", requirement.capabilityId())
                .param("endpointId", requirement.endpointId())
                .param("requirementKind", requirement.requirementKind().name())
                .param("externalCode", requirement.externalCode())
                .param("description", requirement.description())
                .param("verificationState", requirement.verificationState().name())
                .param("lastVerifiedAt", requirement.lastVerifiedAt() == null
                        ? null : Timestamp.from(requirement.lastVerifiedAt()))
                .param("evidenceRef", requirement.evidenceRef())
                .param("verifiedSourceTitle", requirement.verifiedSourceTitle())
                .param("status", requirement.status().name())
                .param("createdAt", Timestamp.from(requirement.createdAt()))
                .param("updatedAt", Timestamp.from(requirement.updatedAt()))
                .param("version", requirement.version())
                .update();
    }

    /** Load one requirement row. */
    public Optional<PermissionRequirement> findById(UUID id) {
        return jdbc.sql("""
                        SELECT * FROM platform.platform_permission_requirement WHERE id = :id
                        """)
                .param("id", id)
                .query(PermissionRequirementRepository::map)
                .optional();
    }

    /** Load an identical requirement for the same target, if any. */
    public Optional<PermissionRequirement> findDuplicate(
            String platformCode, RequirementKind kind, String externalCode,
            UUID capabilityId, UUID endpointId) {
        return jdbc.sql("""
                        SELECT * FROM platform.platform_permission_requirement
                        WHERE platform_code = :platformCode
                          AND requirement_kind = :requirementKind
                          AND external_code = :externalCode
                          AND capability_id IS NOT DISTINCT FROM CAST(:capabilityId AS uuid)
                          AND endpoint_id IS NOT DISTINCT FROM CAST(:endpointId AS uuid)
                        """)
                .param("platformCode", platformCode)
                .param("requirementKind", kind.name())
                .param("externalCode", externalCode)
                .param("capabilityId", capabilityId)
                .param("endpointId", endpointId)
                .query(PermissionRequirementRepository::map)
                .optional();
    }

    /** List the requirements recorded for one capability. */
    public List<PermissionRequirement> listByCapability(UUID capabilityId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM platform.platform_permission_requirement
                        WHERE capability_id = :capabilityId
                        ORDER BY requirement_kind, external_code
                        LIMIT :pageLimit
                        """)
                .param("capabilityId", capabilityId)
                .param("pageLimit", limit)
                .query(PermissionRequirementRepository::map)
                .list();
    }

    /** List the requirements recorded for one endpoint. */
    public List<PermissionRequirement> listByEndpoint(UUID endpointId, int limit) {
        return jdbc.sql("""
                        SELECT * FROM platform.platform_permission_requirement
                        WHERE endpoint_id = :endpointId
                        ORDER BY requirement_kind, external_code
                        LIMIT :pageLimit
                        """)
                .param("endpointId", endpointId)
                .param("pageLimit", limit)
                .query(PermissionRequirementRepository::map)
                .list();
    }

    private static PermissionRequirement map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp lastVerifiedAt = row.getTimestamp("last_verified_at");
        return new PermissionRequirement(
                row.getObject("id", UUID.class),
                row.getString("platform_code"),
                row.getObject("capability_id", UUID.class),
                row.getObject("endpoint_id", UUID.class),
                RequirementKind.valueOf(row.getString("requirement_kind")),
                row.getString("external_code"),
                row.getString("description"),
                VerificationState.valueOf(row.getString("verification_state")),
                lastVerifiedAt == null ? null : lastVerifiedAt.toInstant(),
                row.getString("evidence_ref"),
                row.getString("verified_source_title"),
                RegistryStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
