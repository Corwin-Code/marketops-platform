package com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc;

import com.mimococo.marketops.shared.IdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Append-only relational trace spans for the availability operating loop. */
@Repository
public class AvailabilityTraceRepository {

    private final JdbcClient jdbc;
    private final IdGenerator ids;

    public AvailabilityTraceRepository(JdbcClient jdbc, IdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    public void record(UUID organizationId, UUID productVariantId, String pathKind,
                       String stageCode, String status, String correlationId,
                       String parentCorrelationId, String subjectReference,
                       String detailJson, Instant at) {
        jdbc.sql("""
                        INSERT INTO ops.availability_trace_event
                            (id, organization_id, product_variant_id, path_kind, stage_code,
                             status, correlation_id, parent_correlation_id, subject_reference,
                             detail, occurred_at)
                        VALUES (:id, :organizationId, :productVariantId, :pathKind, :stageCode,
                                :status, :correlationId, :parentCorrelationId,
                                :subjectReference, CAST(:detail AS jsonb), :at)
                        """)
                .param("id", ids.newId()).param("organizationId", organizationId)
                .param("productVariantId", productVariantId).param("pathKind", pathKind)
                .param("stageCode", stageCode).param("status", status)
                .param("correlationId", correlationId)
                .param("parentCorrelationId", parentCorrelationId)
                .param("subjectReference", subjectReference)
                .param("detail", detailJson == null ? "{}" : detailJson)
                .param("at", Timestamp.from(at)).update();
    }
}
