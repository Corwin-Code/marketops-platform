package com.mimococo.marketops.operatingfacts.internal.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Relational access to {@code core.fact_provenance}. */
@Repository
public class ProvenanceRepository {

    private final JdbcClient jdbc;

    ProvenanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Load one provenance record. */
    public Optional<ProvenanceRow> find(UUID id) {
        return jdbc.sql("""
                        SELECT id, organization_id, source_kind, raw_observation_id,
                               import_batch_id, source_time, ingestion_time,
                               recorded_by_user_id, evidence_note
                          FROM core.fact_provenance WHERE id = :id
                        """)
                .param("id", id)
                .query(ProvenanceRepository::map)
                .optional();
    }

    private static ProvenanceRow map(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp sourceTime = rows.getTimestamp("source_time");
        return new ProvenanceRow(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getString("source_kind"),
                rows.getObject("raw_observation_id", UUID.class),
                rows.getObject("import_batch_id", UUID.class),
                sourceTime == null ? null : sourceTime.toInstant(),
                rows.getTimestamp("ingestion_time").toInstant(),
                rows.getObject("recorded_by_user_id", UUID.class),
                rows.getString("evidence_note"));
    }

    /**
     * One provenance record as stored.
     *
     * @param id identifier
     * @param organizationId owning organization
     * @param sourceKind whether the fact came from a marketplace, a file or a person
     * @param rawObservationId the acquisition answer it came from, or {@code null}
     * @param importBatchId the submitted file it came from, or {@code null}
     * @param sourceTime when the source considered it true, or {@code null}
     * @param ingestionTime when this system learned it
     * @param recordedByUserId who entered it by hand, or {@code null}
     * @param evidenceNote what the recorder said about it, or {@code null}
     */
    public record ProvenanceRow(
            UUID id, UUID organizationId, String sourceKind, UUID rawObservationId,
            UUID importBatchId, Instant sourceTime, Instant ingestionTime,
            UUID recordedByUserId, String evidenceNote) {
    }
}
