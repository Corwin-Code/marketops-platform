package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Registration and verification of declared payload shapes. */
@Repository
public class NormalizationRegistrationRepository {

    private final JdbcClient jdbc;

    NormalizationRegistrationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Register a declaration in its unverified, inactive state. */
    public void insertMapping(UUID id, String platformCode, String datasetKind,
                              int mappingVersion, String recordPointer, String ownerLabel,
                              Instant now) {
        jdbc.sql("""
                        INSERT INTO staging.normalization_mapping (
                            id, platform_code, dataset_kind, mapping_version, record_pointer,
                            verification_state, owner_label, status, created_at, updated_at,
                            version)
                        VALUES (:id, :platformCode, :datasetKind, :mappingVersion,
                            :recordPointer, 'UNVERIFIED', :ownerLabel, 'RETIRED',
                            :now, :now, 0)
                        """)
                .param("id", id)
                .param("platformCode", platformCode)
                .param("datasetKind", datasetKind)
                .param("mappingVersion", mappingVersion)
                .param("recordPointer", recordPointer)
                .param("ownerLabel", ownerLabel)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Declare where one canonical field lives inside a record. */
    public void insertField(UUID mappingId, String datasetKind, String fieldName,
                            String sourcePointer) {
        jdbc.sql("""
                        INSERT INTO staging.normalization_field (
                            mapping_id, dataset_kind, field_name, source_pointer)
                        VALUES (:mappingId, :datasetKind, :fieldName, :sourcePointer)
                        """)
                .param("mappingId", mappingId)
                .param("datasetKind", datasetKind)
                .param("fieldName", fieldName)
                .param("sourcePointer", sourcePointer)
                .update();
    }

    /** Record verified evidence and activate the declaration. */
    public boolean verifyAndActivate(UUID mappingId, Instant verifiedAt, String evidenceRef,
                                     String verifiedSourceTitle, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE staging.normalization_mapping
                        SET verification_state = 'VERIFIED', last_verified_at = :verifiedAt,
                            evidence_ref = :evidenceRef,
                            verified_source_title = :verifiedSourceTitle,
                            status = 'ACTIVE', updated_at = :verifiedAt, version = :newVersion
                        WHERE id = :mappingId AND version = :expectedVersion
                        """)
                .param("verifiedAt", Timestamp.from(verifiedAt))
                .param("evidenceRef", evidenceRef)
                .param("verifiedSourceTitle", verifiedSourceTitle)
                .param("newVersion", expectedVersion + 1)
                .param("mappingId", mappingId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Stop using a declaration. The direction to closed is never gated. */
    public boolean retire(UUID mappingId, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE staging.normalization_mapping
                        SET status = 'RETIRED', updated_at = :at, version = :newVersion
                        WHERE id = :mappingId AND version = :expectedVersion
                        """)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("mappingId", mappingId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Every registered declaration, ordered by platform and dataset. */
    public List<MappingRow> list() {
        return jdbc.sql("""
                        SELECT id, platform_code, dataset_kind, mapping_version,
                               record_pointer, verification_state, status, owner_label, version
                          FROM staging.normalization_mapping
                         ORDER BY platform_code, dataset_kind, mapping_version
                        """)
                .query(NormalizationRegistrationRepository::map)
                .list();
    }

    private static MappingRow map(ResultSet rows, int rowNumber) throws SQLException {
        return new MappingRow(
                rows.getObject("id", UUID.class),
                rows.getString("platform_code"),
                rows.getString("dataset_kind"),
                rows.getInt("mapping_version"),
                rows.getString("record_pointer"),
                rows.getString("verification_state"),
                rows.getString("status"),
                rows.getString("owner_label"),
                rows.getLong("version"));
    }

    /**
     * One registered declaration.
     *
     * @param id identifier
     * @param platformCode marketplace whose payload it describes
     * @param datasetKind dataset it describes
     * @param mappingVersion which recorded version this is
     * @param recordPointer where the repeated records live
     * @param verificationState how well the shape is known
     * @param status whether normalization uses it
     * @param ownerLabel responsible owner
     * @param version optimistic-lock version
     */
    public record MappingRow(
            UUID id, String platformCode, String datasetKind, int mappingVersion,
            String recordPointer, String verificationState, String status, String ownerLabel,
            long version) {
    }
}
