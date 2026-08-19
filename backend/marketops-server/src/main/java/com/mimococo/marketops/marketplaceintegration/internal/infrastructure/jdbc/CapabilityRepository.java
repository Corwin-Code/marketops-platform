package com.mimococo.marketops.marketplaceintegration.internal.infrastructure.jdbc;

import com.mimococo.marketops.marketplaceintegration.internal.domain.CapabilityAppliesTo;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ContractTestStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.PlatformCapability;
import com.mimococo.marketops.marketplaceintegration.internal.domain.ReadWriteClass;
import com.mimococo.marketops.marketplaceintegration.internal.domain.RegistryStatus;
import com.mimococo.marketops.marketplaceintegration.internal.domain.TriState;
import com.mimococo.marketops.marketplaceintegration.internal.domain.VerificationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code platform.platform_capability}. */
@Repository
public class CapabilityRepository {

    private final JdbcClient jdbc;

    CapabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert a new capability row. */
    public void insert(PlatformCapability capability) {
        jdbc.sql("""
                        INSERT INTO platform.platform_capability (
                            id, platform_code, capability_code, display_name, description,
                            applies_to, read_write_class, subscription_required,
                            verification_state, last_verified_at, evidence_ref,
                            verified_source_title, owner_label, contract_test_status,
                            deprecated_at, replacement_capability_id, status,
                            created_at, updated_at, version)
                        VALUES (:id, :platformCode, :capabilityCode, :displayName, :description,
                            :appliesTo, :readWriteClass, :subscriptionRequired,
                            :verificationState, :lastVerifiedAt, :evidenceRef,
                            :verifiedSourceTitle, :ownerLabel, :contractTestStatus,
                            :deprecatedAt, :replacementCapabilityId, :status,
                            :createdAt, :updatedAt, :version)
                        """)
                .param("id", capability.id())
                .param("platformCode", capability.platformCode())
                .param("capabilityCode", capability.capabilityCode())
                .param("displayName", capability.displayName())
                .param("description", capability.description())
                .param("appliesTo", capability.appliesTo().name())
                .param("readWriteClass", capability.readWriteClass().name())
                .param("subscriptionRequired", capability.subscriptionRequired().name())
                .param("verificationState", capability.verificationState().name())
                .param("lastVerifiedAt", capability.lastVerifiedAt() == null
                        ? null : Timestamp.from(capability.lastVerifiedAt()))
                .param("evidenceRef", capability.evidenceRef())
                .param("verifiedSourceTitle", capability.verifiedSourceTitle())
                .param("ownerLabel", capability.ownerLabel())
                .param("contractTestStatus", capability.contractTestStatus().name())
                .param("deprecatedAt", capability.deprecatedAt() == null
                        ? null : Timestamp.from(capability.deprecatedAt()))
                .param("replacementCapabilityId", capability.replacementCapabilityId())
                .param("status", capability.status().name())
                .param("createdAt", Timestamp.from(capability.createdAt()))
                .param("updatedAt", Timestamp.from(capability.updatedAt()))
                .param("version", capability.version())
                .update();
    }

    /** Apply a versioned update; false means the expected version was stale. */
    public boolean update(PlatformCapability capability, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE platform.platform_capability
                        SET display_name = :displayName, description = :description,
                            applies_to = :appliesTo, read_write_class = :readWriteClass,
                            subscription_required = :subscriptionRequired,
                            verification_state = :verificationState,
                            last_verified_at = :lastVerifiedAt,
                            evidence_ref = :evidenceRef,
                            verified_source_title = :verifiedSourceTitle,
                            owner_label = :ownerLabel,
                            contract_test_status = :contractTestStatus,
                            deprecated_at = :deprecatedAt,
                            replacement_capability_id = :replacementCapabilityId,
                            status = :status, updated_at = :updatedAt, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                        """)
                .param("displayName", capability.displayName())
                .param("description", capability.description())
                .param("appliesTo", capability.appliesTo().name())
                .param("readWriteClass", capability.readWriteClass().name())
                .param("subscriptionRequired", capability.subscriptionRequired().name())
                .param("verificationState", capability.verificationState().name())
                .param("lastVerifiedAt", capability.lastVerifiedAt() == null
                        ? null : Timestamp.from(capability.lastVerifiedAt()))
                .param("evidenceRef", capability.evidenceRef())
                .param("verifiedSourceTitle", capability.verifiedSourceTitle())
                .param("ownerLabel", capability.ownerLabel())
                .param("contractTestStatus", capability.contractTestStatus().name())
                .param("deprecatedAt", capability.deprecatedAt() == null
                        ? null : Timestamp.from(capability.deprecatedAt()))
                .param("replacementCapabilityId", capability.replacementCapabilityId())
                .param("status", capability.status().name())
                .param("updatedAt", Timestamp.from(capability.updatedAt()))
                .param("newVersion", expectedVersion + 1)
                .param("id", capability.id())
                .param("expectedVersion", expectedVersion)
                .update();
        return updated == 1;
    }

    /** Load one capability. */
    public Optional<PlatformCapability> findById(UUID id) {
        return jdbc.sql("SELECT * FROM platform.platform_capability WHERE id = :id")
                .param("id", id)
                .query(CapabilityRepository::map)
                .optional();
    }

    /** Load one capability by platform and registry code. */
    public Optional<PlatformCapability> findByCode(String platformCode, String capabilityCode) {
        return jdbc.sql("""
                        SELECT * FROM platform.platform_capability
                        WHERE platform_code = :platformCode
                          AND capability_code = :capabilityCode
                        """)
                .param("platformCode", platformCode)
                .param("capabilityCode", capabilityCode)
                .query(CapabilityRepository::map)
                .optional();
    }

    /** List a platform's capabilities by code with a keyset cursor. */
    public List<PlatformCapability> list(String platformCode, String afterCode, int limit) {
        return jdbc.sql("""
                        SELECT * FROM platform.platform_capability
                        WHERE platform_code = :platformCode
                          AND (CAST(:afterCode AS text) IS NULL OR capability_code > :afterCode)
                        ORDER BY capability_code
                        LIMIT :pageLimit
                        """)
                .param("platformCode", platformCode)
                .param("afterCode", afterCode)
                .param("pageLimit", limit)
                .query(CapabilityRepository::map)
                .list();
    }

    private static PlatformCapability map(ResultSet row, int rowNumber) throws SQLException {
        Timestamp lastVerifiedAt = row.getTimestamp("last_verified_at");
        Timestamp deprecatedAt = row.getTimestamp("deprecated_at");
        return new PlatformCapability(
                row.getObject("id", UUID.class),
                row.getString("platform_code"),
                row.getString("capability_code"),
                row.getString("display_name"),
                row.getString("description"),
                CapabilityAppliesTo.valueOf(row.getString("applies_to")),
                ReadWriteClass.valueOf(row.getString("read_write_class")),
                TriState.valueOf(row.getString("subscription_required")),
                VerificationState.valueOf(row.getString("verification_state")),
                lastVerifiedAt == null ? null : lastVerifiedAt.toInstant(),
                row.getString("evidence_ref"),
                row.getString("verified_source_title"),
                row.getString("owner_label"),
                ContractTestStatus.valueOf(row.getString("contract_test_status")),
                deprecatedAt == null ? null : deprecatedAt.toInstant(),
                row.getObject("replacement_capability_id", UUID.class),
                RegistryStatus.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version"));
    }
}
