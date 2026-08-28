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
 * Append-only access to Raw logical units and the observations of them.
 *
 * <p>The application role holds SELECT and INSERT and nothing else on all three
 * evidence tables, so immutability is a privilege rather than a convention: a
 * stored observation cannot be rewritten by this application or by any client
 * connecting as this role.
 */
@Repository
public class RawEvidenceRepository {

    private final JdbcClient jdbc;
    private final tools.jackson.databind.ObjectMapper mapper;

    RawEvidenceRepository(JdbcClient jdbc, tools.jackson.databind.ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Record the logical unit a source page represents, or leave the existing
     * one alone.
     *
     * <p>Re-acquiring the same page is idempotent at this level. The
     * observations below still record both calls, so "we read this twice" stays
     * visible while "this is one page" stays true.
     */
    public UUID recordLogicalUnit(UUID id, UUID jobId, UUID marketplaceAccountId,
                                  String unitKind, String sourceUnitKey, Instant sourceTime) {
        jdbc.sql("""
                        INSERT INTO raw.raw_logical_unit (
                            id, job_id, marketplace_account_id, unit_kind,
                            source_unit_key, source_time)
                        VALUES (:id, :jobId, :accountId, :unitKind, :sourceUnitKey, :sourceTime)
                        ON CONFLICT (job_id, unit_kind, source_unit_key) DO NOTHING
                        """)
                .param("id", id)
                .param("jobId", jobId)
                .param("accountId", marketplaceAccountId)
                .param("unitKind", unitKind)
                .param("sourceUnitKey", sourceUnitKey)
                .param("sourceTime", sourceTime == null ? null : Timestamp.from(sourceTime))
                .update();
        return jdbc.sql("""
                        SELECT id FROM raw.raw_logical_unit
                         WHERE job_id = :jobId AND unit_kind = :unitKind
                           AND source_unit_key = :sourceUnitKey
                        """)
                .param("jobId", jobId)
                .param("unitKind", unitKind)
                .param("sourceUnitKey", sourceUnitKey)
                .query(UUID.class)
                .single();
    }

    /** Record one acquisition answer exactly as it arrived. */
    public void recordObservation(UUID id, UUID runId, UUID logicalUnitId, UUID contentId,
                                  int callSeq, String nativeStatus, String outcomeClass) {
        recordObservation(id, runId, logicalUnitId, contentId, callSeq, nativeStatus, outcomeClass, true, null, null);
    }

    public void recordObservation(UUID id, UUID runId, UUID logicalUnitId, UUID contentId,
                                  int callSeq, String nativeStatus, String outcomeClass,
                                  boolean complete, String failure, UUID decision) {
        recordObservation(id,runId,logicalUnitId,contentId,callSeq,nativeStatus,outcomeClass,complete,failure,decision,java.util.Map.of(),"UNASSESSED");
    }

    public void recordObservation(UUID id, UUID runId, UUID logicalUnitId, UUID contentId,
                                  int callSeq, String nativeStatus, String outcomeClass,
                                  boolean complete, String failure, UUID decision, java.util.Map<String,String> headers, String paginationOutcome) {
        jdbc.sql("""
                        INSERT INTO raw.raw_acquisition_observation (
                            id, run_id, logical_unit_id, content_id, call_seq,
                            native_status, outcome_class, response_complete, transport_failure_code, authority_decision_id,response_headers,pagination_outcome)
                        VALUES (:id, :runId, :unitId, :contentId, :callSeq,
                            :nativeStatus, :outcomeClass, :complete, :failure, :decision,CAST(:headers AS jsonb),:pagination)
                        """)
                .param("id", id)
                .param("runId", runId)
                .param("unitId", logicalUnitId)
                .param("contentId", contentId)
                .param("callSeq", callSeq)
                .param("nativeStatus", nativeStatus)
                .param("outcomeClass", outcomeClass)
                .param("complete", complete).param("failure", failure).param("decision", decision)
                .param("headers",mapper.writeValueAsString(headers))
                .param("pagination",paginationOutcome)
                .update();
    }

    /**
     * Observations of one job after a cursor, oldest first.
     *
     * <p>The cursor is a time and an identifier together, because two
     * observations can share an ingestion instant and a time-only cursor would
     * either skip one or replay it forever.
     */
    public List<StoredObservation> observationsAfter(UUID jobId,
                                                     Instant afterIngestionTime,
                                                     UUID afterObservationId,
                                                     int limit) {
        return jdbc.sql("""
                        SELECT observation.id, observation.run_id, observation.call_seq,
                               observation.native_status, observation.outcome_class,
                               observation.ingestion_time, unit.id AS unit_id,
                               unit.unit_kind, unit.source_unit_key, unit.source_time,
                               content.id AS content_id, content.hash_value,
                               content.byte_length, content.object_ref
                          FROM raw.raw_acquisition_observation AS observation
                          JOIN raw.raw_logical_unit AS unit
                            ON unit.id = observation.logical_unit_id
                          JOIN raw.raw_content AS content
                            ON content.id = observation.content_id
                         WHERE unit.job_id = :jobId
                           AND (CAST(:afterTime AS timestamptz) IS NULL
                                OR (observation.ingestion_time, observation.id)
                                       > (CAST(:afterTime AS timestamptz),
                                          CAST(:afterId AS uuid)))
                         ORDER BY observation.ingestion_time, observation.id
                         LIMIT :pageLimit
                        """)
                .param("jobId", jobId)
                .param("afterTime",
                        afterIngestionTime == null ? null : Timestamp.from(afterIngestionTime))
                .param("afterId", afterObservationId)
                .param("pageLimit", limit)
                .query(RawEvidenceRepository::mapObservation)
                .list();
    }

    /** Load one observation with its unit and content. */
    public Optional<StoredObservation> findObservation(UUID observationId) {
        return jdbc.sql("""
                        SELECT observation.id, observation.run_id, observation.call_seq,
                               observation.native_status, observation.outcome_class,
                               observation.ingestion_time, unit.id AS unit_id,
                               unit.unit_kind, unit.source_unit_key, unit.source_time,
                               content.id AS content_id, content.hash_value,
                               content.byte_length, content.object_ref
                          FROM raw.raw_acquisition_observation AS observation
                          JOIN raw.raw_logical_unit AS unit
                            ON unit.id = observation.logical_unit_id
                          JOIN raw.raw_content AS content
                            ON content.id = observation.content_id
                         WHERE observation.id = :observationId
                        """)
                .param("observationId", observationId)
                .query(RawEvidenceRepository::mapObservation)
                .optional();
    }

    /** Every custody record referenced by at least one observation. */
    public List<String> referencedObjectRefs(int limit, int offset) {
        return jdbc.sql("""
                        SELECT DISTINCT content.object_ref
                          FROM raw.raw_content AS content
                         ORDER BY content.object_ref
                         LIMIT :pageLimit OFFSET :pageOffset
                        """)
                .param("pageLimit", limit)
                .param("pageOffset", offset)
                .query(String.class)
                .list();
    }

    private static StoredObservation mapObservation(ResultSet rows, int rowNumber)
            throws SQLException {
        Timestamp sourceTime = rows.getTimestamp("source_time");
        return new StoredObservation(
                rows.getObject("id", UUID.class),
                rows.getObject("run_id", UUID.class),
                rows.getObject("unit_id", UUID.class),
                rows.getString("unit_kind"),
                rows.getString("source_unit_key"),
                sourceTime == null ? null : sourceTime.toInstant(),
                rows.getInt("call_seq"),
                rows.getString("native_status"),
                rows.getString("outcome_class"),
                rows.getTimestamp("ingestion_time").toInstant(),
                rows.getObject("content_id", UUID.class),
                rows.getString("hash_value"),
                rows.getLong("byte_length"),
                rows.getString("object_ref"));
    }

    /**
     * One stored acquisition answer together with the unit and content it names.
     *
     * @param id observation identifier
     * @param runId run that produced it
     * @param logicalUnitId the source page it observed
     * @param unitKind kind of page
     * @param sourceUnitKey the source's own key for the page
     * @param sourceTime when the source considered it true, or {@code null}
     * @param callSeq position of the call inside the run
     * @param nativeStatus the transport's own status words
     * @param outcomeClass how the answer was classified
     * @param ingestionTime when this system learned it
     * @param contentId custody record of the bytes
     * @param sha256 digest of the bytes
     * @param byteLength length of the bytes
     * @param objectRef opaque custody locator
     */
    public record StoredObservation(
            UUID id,
            UUID runId,
            UUID logicalUnitId,
            String unitKind,
            String sourceUnitKey,
            Instant sourceTime,
            int callSeq,
            String nativeStatus,
            String outcomeClass,
            Instant ingestionTime,
            UUID contentId,
            String sha256,
            long byteLength,
            String objectRef) {
    }
}
