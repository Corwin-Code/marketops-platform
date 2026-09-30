package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The declared payload shapes, the progress cursor over stored evidence, and the
 * record of fields a source sent that no declaration names.
 *
 * <p>Only verified, active declarations are visible. A platform whose payload
 * nobody has recorded therefore has no mapping at all, and normalization stops
 * with an explicit reason instead of guessing at a structure.
 */
@Repository
public class NormalizationDeclarationRepository {

    private final JdbcClient jdbc;

    NormalizationDeclarationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The live declaration for one platform and dataset, when one is verified. */
    public Optional<MappingDeclaration> liveMapping(String platformCode, String datasetKind) {
        return jdbc.sql("""
                        SELECT id, dataset_kind, record_pointer, child_pointer, mapping_version
                          FROM staging.normalization_mapping
                         WHERE platform_code = :platformCode
                           AND dataset_kind = :datasetKind
                           AND source_dataset_kind IS NULL
                           AND status = 'ACTIVE'
                           AND verification_state = 'VERIFIED'
                        """)
                .param("platformCode", platformCode)
                .param("datasetKind", datasetKind)
                .query((rows, rowNumber) -> mappingDeclaration(rows))
                .optional();
    }

    /**
     * The live companion declarations that read the evidence of another dataset's jobs beside
     * that dataset's own declaration, each writing facts of its own dataset.
     */
    public List<MappingDeclaration> companionMappings(String platformCode, String sourceDatasetKind) {
        return jdbc.sql("""
                        SELECT id, dataset_kind, record_pointer, child_pointer, mapping_version
                          FROM staging.normalization_mapping
                         WHERE platform_code = :platformCode
                           AND source_dataset_kind = :sourceDatasetKind
                           AND status = 'ACTIVE'
                           AND verification_state = 'VERIFIED'
                         ORDER BY dataset_kind
                        """)
                .param("platformCode", platformCode)
                .param("sourceDatasetKind", sourceDatasetKind)
                .query((rows, rowNumber) -> mappingDeclaration(rows))
                .list();
    }

    private static MappingDeclaration mappingDeclaration(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new MappingDeclaration(
                rows.getObject("id", UUID.class),
                rows.getString("dataset_kind"),
                rows.getString("record_pointer"),
                rows.getString("child_pointer"),
                rows.getInt("mapping_version"));
    }

    /** Where each canonical field of one mapping comes from, keyed by canonical field. */
    public Map<String, FieldSource> fieldSources(UUID mappingId) {
        Map<String, FieldSource> sources = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT field_name, source_kind, source_pointer, constant_value,
                               value_map::text AS value_map, element_pointer
                          FROM staging.normalization_field
                         WHERE mapping_id = :mappingId ORDER BY field_name
                        """)
                .param("mappingId", mappingId)
                .query((rows, rowNumber) -> sources.put(rows.getString("field_name"), new FieldSource(
                        rows.getString("source_kind"), rows.getString("source_pointer"),
                        rows.getString("constant_value"), rows.getString("value_map"),
                        rows.getString("element_pointer"))))
                .list();
        return Map.copyOf(sources);
    }

    /** The canonical fields of one dataset that a record must carry. */
    public List<String> requiredFields(String datasetKind) {
        return jdbc.sql("""
                        SELECT field_name FROM staging.canonical_field
                         WHERE dataset_kind = :datasetKind AND requirement = 'REQUIRED'
                         ORDER BY ordinal
                        """)
                .param("datasetKind", datasetKind)
                .query(String.class)
                .list();
    }

    /** The value kind each canonical field of one dataset is converted to. */
    public Map<String, String> valueKinds(String datasetKind) {
        Map<String, String> kinds = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT field_name, value_kind FROM staging.canonical_field
                         WHERE dataset_kind = :datasetKind ORDER BY ordinal
                        """)
                .param("datasetKind", datasetKind)
                .query((rows, rowNumber) -> kinds.put(
                        rows.getString("field_name"), rows.getString("value_kind")))
                .list();
        return Map.copyOf(kinds);
    }

    /** The canonical fields of one dataset that hold a list of values rather than one value. */
    public java.util.Set<String> repeatedFields(String datasetKind) {
        return java.util.Set.copyOf(jdbc.sql("""
                        SELECT field_name FROM staging.canonical_field
                         WHERE dataset_kind = :datasetKind AND repeated
                        """)
                .param("datasetKind", datasetKind)
                .query(String.class)
                .list());
    }

    /** How far normalization has read one job's evidence. */
    public Optional<ProgressCursor> progress(UUID jobId) {
        return jdbc.sql("""
                        SELECT last_ingestion_time, last_observation_id, processed_count, version
                          FROM staging.normalization_checkpoint WHERE job_id = :jobId
                        """)
                .param("jobId", jobId)
                .query((rows, rowNumber) -> {
                    Timestamp lastTime = rows.getTimestamp("last_ingestion_time");
                    return new ProgressCursor(
                            lastTime == null ? null : lastTime.toInstant(),
                            rows.getObject("last_observation_id", UUID.class),
                            rows.getLong("processed_count"),
                            rows.getLong("version"));
                })
                .optional();
    }

    /**
     * Move the progress cursor forward.
     *
     * <p>The compare-and-set on the version is what stops two normalizers from
     * both advancing past the same evidence; a losing writer affects no row and
     * simply reads the new position on its next pass.
     */
    public boolean advanceProgress(UUID jobId, Instant lastIngestionTime,
                                   UUID lastObservationId, long processedDelta,
                                   Instant updatedAt, long expectedVersion) {
        return jdbc.sql("""
                        INSERT INTO staging.normalization_checkpoint (
                            job_id, last_ingestion_time, last_observation_id,
                            processed_count, updated_at, version)
                        VALUES (:jobId, :lastIngestionTime, :lastObservationId,
                            :processedDelta, :updatedAt, 1)
                        ON CONFLICT (job_id) DO UPDATE
                        SET last_ingestion_time = EXCLUDED.last_ingestion_time,
                            last_observation_id = EXCLUDED.last_observation_id,
                            processed_count = staging.normalization_checkpoint.processed_count
                                              + EXCLUDED.processed_count,
                            updated_at = EXCLUDED.updated_at,
                            version = staging.normalization_checkpoint.version + 1
                        WHERE staging.normalization_checkpoint.version = :expectedVersion
                        """)
                .param("jobId", jobId)
                .param("lastIngestionTime", Timestamp.from(lastIngestionTime))
                .param("lastObservationId", lastObservationId)
                .param("processedDelta", processedDelta)
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /**
     * Record a field the source sent that no declaration names.
     *
     * <p>Repeated occurrences update one row rather than appending, so a
     * platform that added one field produces one queue item instead of one per
     * record. The first observation is kept so somebody can open the exact bytes
     * that first showed the change.
     */
    public void recordDrift(UUID id, UUID jobId, UUID mappingId, String unmappedPointer,
                            UUID firstObservationId, Instant seenAt) {
        jdbc.sql("""
                        INSERT INTO staging.schema_drift_observation (
                            id, job_id, mapping_id, unmapped_pointer, first_observation_id,
                            first_seen_at, last_seen_at, occurrence_count, state)
                        VALUES (:id, :jobId, :mappingId, :unmappedPointer, :firstObservationId,
                            :seenAt, :seenAt, 1, 'OPEN')
                        ON CONFLICT (job_id, mapping_id, unmapped_pointer)
                            WHERE state = 'OPEN'
                        DO UPDATE
                        SET last_seen_at = EXCLUDED.last_seen_at,
                            occurrence_count =
                                staging.schema_drift_observation.occurrence_count + 1,
                            version = staging.schema_drift_observation.version + 1
                        """)
                .param("id", id)
                .param("jobId", jobId)
                .param("mappingId", mappingId)
                .param("unmappedPointer", unmappedPointer)
                .param("firstObservationId", firstObservationId)
                .param("seenAt", Timestamp.from(seenAt))
                .update();
    }

    /** Acknowledge one drift finding. */
    public boolean acknowledgeDrift(UUID id, UUID userId, Instant at, String note,
                                    long expectedVersion) {
        return jdbc.sql("""
                        UPDATE staging.schema_drift_observation
                        SET state = 'ACKNOWLEDGED', acknowledged_by_user_id = :userId,
                            acknowledged_at = :at, acknowledgement_note = :note,
                            version = :newVersion
                        WHERE id = :id AND version = :expectedVersion AND state = 'OPEN'
                        """)
                .param("userId", userId)
                .param("at", Timestamp.from(at))
                .param("note", note)
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** The open drift queue, most recently seen first. */
    public List<DriftFinding> openDrift(int limit) {
        return jdbc.sql("""
                        SELECT drift.id, drift.job_id, drift.unmapped_pointer,
                               drift.first_observation_id, drift.first_seen_at,
                               drift.last_seen_at, drift.occurrence_count, drift.version,
                               mapping.platform_code, mapping.dataset_kind
                          FROM staging.schema_drift_observation AS drift
                          JOIN staging.normalization_mapping AS mapping
                            ON mapping.id = drift.mapping_id
                         WHERE drift.state = 'OPEN'
                         ORDER BY drift.last_seen_at DESC, drift.id
                         LIMIT :pageLimit
                        """)
                .param("pageLimit", limit)
                .query((rows, rowNumber) -> new DriftFinding(
                        rows.getObject("id", UUID.class),
                        rows.getObject("job_id", UUID.class),
                        rows.getString("platform_code"),
                        rows.getString("dataset_kind"),
                        rows.getString("unmapped_pointer"),
                        rows.getObject("first_observation_id", UUID.class),
                        rows.getTimestamp("first_seen_at").toInstant(),
                        rows.getTimestamp("last_seen_at").toInstant(),
                        rows.getLong("occurrence_count"),
                        rows.getLong("version")))
                .list();
    }

    /**
     * One declared payload shape.
     *
     * @param id identifier
     * @param datasetKind the dataset whose facts it writes
     * @param recordPointer where the repeated records live inside the payload
     * @param childPointer where each record's own array of child records lives, when
     *        the children rather than the records are the facts; otherwise {@code null}
     * @param mappingVersion which recorded version this is
     */
    public record MappingDeclaration(UUID id, String datasetKind, String recordPointer, String childPointer,
                                     int mappingVersion) {
    }

    /**
     * Where one canonical field's value comes from.
     *
     * @param kind {@code POINTER} (the record, or the child record when the mapping
     *        has children), {@code PARENT_POINTER} (the record above a child),
     *        {@code OBSERVATION_TIME}, {@code WINDOW_START} / {@code WINDOW_END} (the run's
     *        window), {@code CONSTANT}, {@code EACH_POINTER} (every element of an array in the
     *        record) or {@code ARRAY_LENGTH} (how many elements an array holds)
     * @param pointer the JSON pointer for the pointer kinds, otherwise {@code null}
     * @param constant the text of a constant, otherwise {@code null}
     * @param valueMapJson a JSON object translating native words, or {@code null}
     * @param elementPointer for {@code EACH_POINTER}, the value inside each element, otherwise {@code null}
     */
    public record FieldSource(String kind, String pointer, String constant, String valueMapJson,
                              String elementPointer) {
    }

    /**
     * How far normalization has read one job's evidence.
     *
     * @param lastIngestionTime ingestion time of the last processed observation
     * @param lastObservationId identifier of the last processed observation
     * @param processedCount how many observations have been processed
     * @param version optimistic-lock version
     */
    public record ProgressCursor(
            Instant lastIngestionTime, UUID lastObservationId, long processedCount,
            long version) {
    }

    /**
     * One field a source sent that no declaration names.
     *
     * @param id identifier
     * @param jobId job whose evidence showed it
     * @param platformCode marketplace the payload came from
     * @param datasetKind dataset the payload belongs to
     * @param unmappedPointer where the unnamed value sits inside a record
     * @param firstObservationId evidence that first showed it
     * @param firstSeenAt when it was first seen
     * @param lastSeenAt when it was last seen
     * @param occurrenceCount how many records carried it
     * @param version optimistic-lock version
     */
    public record DriftFinding(
            UUID id, UUID jobId, String platformCode, String datasetKind,
            String unmappedPointer, UUID firstObservationId, Instant firstSeenAt,
            Instant lastSeenAt, long occurrenceCount, long version) {
    }
}
