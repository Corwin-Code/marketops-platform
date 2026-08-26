package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

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
 * Relational access to registered schema profiles, import batches and their
 * row-level outcomes.
 *
 * <p>Rows are append-only. The rejection report and the preview are the same
 * object, so a report that could be edited after the fact would not be a report;
 * the privilege set is what makes that true rather than the code that writes it.
 */
@Repository
public class ImportRepository {

    private final JdbcClient jdbc;

    ImportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Register a file contract for one dataset. */
    public void insertProfile(UUID id, UUID organizationId, String datasetKind,
                              String profileCode, int profileVersion, String displayName,
                              String columnContract, String ownerLabel, Instant now) {
        jdbc.sql("""
                        INSERT INTO staging.import_schema_profile (
                            id, organization_id, dataset_kind, profile_code, profile_version,
                            display_name, column_contract, owner_label, status,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :datasetKind, :profileCode,
                            :profileVersion, :displayName, CAST(:columnContract AS jsonb),
                            :ownerLabel, 'ACTIVE', :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("datasetKind", datasetKind)
                .param("profileCode", profileCode)
                .param("profileVersion", profileVersion)
                .param("displayName", displayName)
                .param("columnContract", columnContract)
                .param("ownerLabel", ownerLabel)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** The live contract for one dataset, when one is registered. */
    public Optional<SchemaProfile> liveProfile(UUID organizationId, String datasetKind) {
        return jdbc.sql("""
                        SELECT id, dataset_kind, profile_code, profile_version,
                               CAST(column_contract AS text) AS column_contract
                          FROM staging.import_schema_profile
                         WHERE organization_id = :organizationId
                           AND dataset_kind = :datasetKind
                           AND status = 'ACTIVE'
                        """)
                .param("organizationId", organizationId)
                .param("datasetKind", datasetKind)
                .query((rows, rowNumber) -> new SchemaProfile(
                        rows.getObject("id", UUID.class),
                        rows.getString("dataset_kind"),
                        rows.getString("profile_code"),
                        rows.getInt("profile_version"),
                        rows.getString("column_contract")))
                .optional();
    }

    /** Record a submitted file. */
    public void insertBatch(UUID id, UUID organizationId, String datasetKind, UUID profileId,
                            UUID contentId, String fileName, String mediaType,
                            UUID submittedByUserId, Instant now) {
        jdbc.sql("""
                        INSERT INTO staging.import_batch (
                            id, organization_id, dataset_kind, schema_profile_id, content_id,
                            declared_file_name, declared_media_type, state,
                            submitted_by_user_id, submitted_at, created_at, updated_at, version)
                        VALUES (:id, :organizationId, :datasetKind, :profileId, :contentId,
                            :fileName, :mediaType, 'RECEIVED', :submittedByUserId,
                            :now, :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("datasetKind", datasetKind)
                .param("profileId", profileId)
                .param("contentId", contentId)
                .param("fileName", fileName)
                .param("mediaType", mediaType)
                .param("submittedByUserId", submittedByUserId)
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Record what validation concluded about a batch. */
    public boolean recordValidation(UUID batchId, String state, String rejectionCode,
                                    int totalRows, int acceptedRows, int rejectedRows,
                                    Instant now, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE staging.import_batch
                        SET state = :state, rejection_code = :rejectionCode,
                            total_row_count = :totalRows, accepted_row_count = :acceptedRows,
                            rejected_row_count = :rejectedRows,
                            updated_at = :now, version = :newVersion
                        WHERE id = :batchId AND version = :expectedVersion
                        """)
                .param("state", state)
                .param("rejectionCode", rejectionCode)
                .param("totalRows", totalRows)
                .param("acceptedRows", acceptedRows)
                .param("rejectedRows", rejectedRows)
                .param("now", Timestamp.from(now))
                .param("newVersion", expectedVersion + 1)
                .param("batchId", batchId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Record an approval and the instant the batch's facts take effect from. */
    public boolean recordApproval(UUID batchId, UUID approvedByUserId, Instant approvedAt,
                                  Instant effectiveFrom, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE staging.import_batch
                        SET state = 'APPROVED', approved_by_user_id = :approvedByUserId,
                            approved_at = :approvedAt, effective_from = :effectiveFrom,
                            updated_at = :approvedAt, version = :newVersion
                        WHERE id = :batchId AND version = :expectedVersion
                          AND state = 'VALIDATED'
                        """)
                .param("approvedByUserId", approvedByUserId)
                .param("approvedAt", Timestamp.from(approvedAt))
                .param("effectiveFrom", Timestamp.from(effectiveFrom))
                .param("newVersion", expectedVersion + 1)
                .param("batchId", batchId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Record that an approved batch's rows have been written. */
    public boolean recordApplied(UUID batchId, Instant appliedAt, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE staging.import_batch
                        SET state = 'APPLIED', applied_at = :appliedAt,
                            updated_at = :appliedAt, version = :newVersion
                        WHERE id = :batchId AND version = :expectedVersion
                          AND state = 'APPROVED'
                        """)
                .param("appliedAt", Timestamp.from(appliedAt))
                .param("newVersion", expectedVersion + 1)
                .param("batchId", batchId)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Append one parsed row and what validation decided about it. */
    public void insertRow(UUID id, UUID batchId, int rowNumber, String parsedValues,
                          String validationState, String rejectionCode,
                          String rejectionDetail, String targetKey) {
        jdbc.sql("""
                        INSERT INTO staging.import_row (
                            id, batch_id, row_number, parsed_values, validation_state,
                            rejection_code, rejection_detail, target_key)
                        VALUES (:id, :batchId, :rowNumber, CAST(:parsedValues AS jsonb),
                            :validationState, :rejectionCode, :rejectionDetail, :targetKey)
                        ON CONFLICT (batch_id, row_number) DO NOTHING
                        """)
                .param("id", id)
                .param("batchId", batchId)
                .param("rowNumber", rowNumber)
                .param("parsedValues", parsedValues)
                .param("validationState", validationState)
                .param("rejectionCode", rejectionCode)
                .param("rejectionDetail", rejectionDetail)
                .param("targetKey", targetKey)
                .update();
    }

    /** Load one batch. */
    public Optional<ImportBatch> findBatch(UUID batchId) {
        return jdbc.sql("""
                        SELECT id, organization_id, dataset_kind, schema_profile_id,
                               content_id, declared_file_name, declared_media_type, state,
                               rejection_code, total_row_count, accepted_row_count,
                               rejected_row_count, effective_from, submitted_by_user_id,
                               submitted_at, approved_by_user_id, approved_at, applied_at,
                               version
                          FROM staging.import_batch WHERE id = :batchId
                        """)
                .param("batchId", batchId)
                .query(ImportRepository::mapBatch)
                .optional();
    }

    /** Whether a live batch already carries this exact content. */
    public Optional<UUID> liveBatchWithContent(UUID organizationId, String datasetKind,
                                               UUID contentId) {
        return jdbc.sql("""
                        SELECT id FROM staging.import_batch
                         WHERE organization_id = :organizationId
                           AND dataset_kind = :datasetKind
                           AND content_id = :contentId
                           AND state <> 'REJECTED'
                        """)
                .param("organizationId", organizationId)
                .param("datasetKind", datasetKind)
                .param("contentId", contentId)
                .query(UUID.class)
                .optional();
    }

    /** An organization's batches for one dataset, newest first. */
    public List<ImportBatch> listBatches(UUID organizationId, String datasetKind, int limit) {
        return jdbc.sql("""
                        SELECT id, organization_id, dataset_kind, schema_profile_id,
                               content_id, declared_file_name, declared_media_type, state,
                               rejection_code, total_row_count, accepted_row_count,
                               rejected_row_count, effective_from, submitted_by_user_id,
                               submitted_at, approved_by_user_id, approved_at, applied_at,
                               version
                          FROM staging.import_batch
                         WHERE organization_id = :organizationId
                           AND (CAST(:datasetKind AS text) IS NULL
                                OR dataset_kind = CAST(:datasetKind AS text))
                         ORDER BY submitted_at DESC, id
                         LIMIT :pageLimit
                        """)
                .param("organizationId", organizationId)
                .param("datasetKind", datasetKind)
                .param("pageLimit", limit)
                .query(ImportRepository::mapBatch)
                .list();
    }

    /** One batch's rows, in file order. */
    public List<ImportRow> listRows(UUID batchId, String validationState, int limit) {
        return jdbc.sql("""
                        SELECT id, row_number, CAST(parsed_values AS text) AS parsed_values,
                               validation_state, rejection_code, rejection_detail, target_key
                          FROM staging.import_row
                         WHERE batch_id = :batchId
                           AND (CAST(:validationState AS text) IS NULL
                                OR validation_state = CAST(:validationState AS text))
                         ORDER BY row_number
                         LIMIT :pageLimit
                        """)
                .param("batchId", batchId)
                .param("validationState", validationState)
                .param("pageLimit", limit)
                .query((rows, rowNumber) -> new ImportRow(
                        rows.getObject("id", UUID.class),
                        rows.getInt("row_number"),
                        rows.getString("parsed_values"),
                        rows.getString("validation_state"),
                        rows.getString("rejection_code"),
                        rows.getString("rejection_detail"),
                        rows.getString("target_key")))
                .list();
    }

    private static ImportBatch mapBatch(ResultSet rows, int rowNumber) throws SQLException {
        return new ImportBatch(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getString("dataset_kind"),
                rows.getObject("schema_profile_id", UUID.class),
                rows.getObject("content_id", UUID.class),
                rows.getString("declared_file_name"),
                rows.getString("declared_media_type"),
                rows.getString("state"),
                rows.getString("rejection_code"),
                integerOrNull(rows, "total_row_count"),
                integerOrNull(rows, "accepted_row_count"),
                integerOrNull(rows, "rejected_row_count"),
                instantOrNull(rows, "effective_from"),
                rows.getObject("submitted_by_user_id", UUID.class),
                rows.getTimestamp("submitted_at").toInstant(),
                rows.getObject("approved_by_user_id", UUID.class),
                instantOrNull(rows, "approved_at"),
                instantOrNull(rows, "applied_at"),
                rows.getLong("version"));
    }

    private static Instant instantOrNull(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Integer integerOrNull(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    /**
     * One registered file contract.
     *
     * @param id identifier
     * @param datasetKind which dataset it describes
     * @param profileCode business code
     * @param profileVersion which recorded version this is
     * @param columnContract the column declarations, as JSON text
     */
    public record SchemaProfile(
            UUID id, String datasetKind, String profileCode, int profileVersion,
            String columnContract) {
    }

    /**
     * One submitted file and everything decided about it.
     *
     * @param id identifier
     * @param organizationId owning organization
     * @param datasetKind which dataset the file carries
     * @param schemaProfileId the contract it was validated against
     * @param contentId the exact bytes in Raw custody
     * @param declaredFileName the name the submitter gave
     * @param declaredMediaType the type the submitter declared
     * @param state where the batch stands
     * @param rejectionCode why it was rejected, or {@code null}
     * @param totalRowCount rows the file carried, or {@code null} before validation
     * @param acceptedRowCount rows that passed, or {@code null} before validation
     * @param rejectedRowCount rows that failed, or {@code null} before validation
     * @param effectiveFrom when its facts take effect, or {@code null} before approval
     * @param submittedByUserId who submitted it
     * @param submittedAt when
     * @param approvedByUserId who approved it, or {@code null}
     * @param approvedAt when, or {@code null}
     * @param appliedAt when its rows were written, or {@code null}
     * @param version optimistic-lock version
     */
    public record ImportBatch(
            UUID id, UUID organizationId, String datasetKind, UUID schemaProfileId,
            UUID contentId, String declaredFileName, String declaredMediaType, String state,
            String rejectionCode, Integer totalRowCount, Integer acceptedRowCount,
            Integer rejectedRowCount, Instant effectiveFrom, UUID submittedByUserId,
            Instant submittedAt, UUID approvedByUserId, Instant approvedAt, Instant appliedAt,
            long version) {
    }

    /**
     * One parsed row and what validation decided about it.
     *
     * @param id identifier
     * @param rowNumber position in the file, counting the header as row one
     * @param parsedValues the mapped internal fields, as JSON text
     * @param validationState whether the row was accepted
     * @param rejectionCode why it was rejected, or {@code null}
     * @param rejectionDetail which field failed, or {@code null}
     * @param targetKey the internal entity the row addresses, or {@code null}
     */
    public record ImportRow(
            UUID id, int rowNumber, String parsedValues, String validationState,
            String rejectionCode, String rejectionDetail, String targetKey) {
    }
}
