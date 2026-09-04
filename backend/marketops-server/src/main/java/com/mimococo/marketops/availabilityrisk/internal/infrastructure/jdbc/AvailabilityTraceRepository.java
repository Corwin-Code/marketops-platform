package com.mimococo.marketops.availabilityrisk.internal.infrastructure.jdbc;

import com.mimococo.marketops.shared.IdGenerator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

    /** One span, as {@link #record(List)} writes it. */
    public record Span(UUID organizationId, UUID productVariantId, String pathKind,
                       String stageCode, String status, String correlationId,
                       String parentCorrelationId, String subjectReference,
                       String detailJson, Instant at) {
    }

    /**
     * Append several spans in one statement.
     *
     * <p>Only for spans that already share a transaction. Two spans written a
     * statement apart inside one transaction become visible together at commit
     * either way, so writing them together changes when the database is asked
     * and not when anybody can see the answer. A span whose whole purpose is to
     * survive a crash before commit must still be written on its own.
     */
    public void record(List<Span> spans) {
        if (spans.isEmpty()) {
            return;
        }
        StringBuilder values = new StringBuilder();
        for (int index = 0; index < spans.size(); index++) {
            values.append(index == 0 ? "    (" : ",\n    (")
                    .append(":id").append(index)
                    .append(", :organizationId").append(index)
                    .append(", :productVariantId").append(index)
                    .append(", :pathKind").append(index)
                    .append(", :stageCode").append(index)
                    .append(", :status").append(index)
                    .append(", :correlationId").append(index)
                    .append(", :parentCorrelationId").append(index)
                    .append(", :subjectReference").append(index)
                    .append(", CAST(:detail").append(index).append(" AS jsonb)")
                    .append(", :at").append(index).append(')');
        }
        var spec = jdbc.sql("""
                INSERT INTO ops.availability_trace_event
                    (id, organization_id, product_variant_id, path_kind, stage_code,
                     status, correlation_id, parent_correlation_id, subject_reference,
                     detail, occurred_at)
                VALUES
                """ + values.append('\n'));
        for (int index = 0; index < spans.size(); index++) {
            Span span = spans.get(index);
            spec = spec.param("id" + index, ids.newId())
                    .param("organizationId" + index, span.organizationId())
                    .param("productVariantId" + index, span.productVariantId())
                    .param("pathKind" + index, span.pathKind())
                    .param("stageCode" + index, span.stageCode())
                    .param("status" + index, span.status())
                    .param("correlationId" + index, span.correlationId())
                    .param("parentCorrelationId" + index, span.parentCorrelationId())
                    .param("subjectReference" + index, span.subjectReference())
                    .param("detail" + index,
                            span.detailJson() == null ? "{}" : span.detailJson())
                    .param("at" + index, Timestamp.from(span.at()));
        }
        spec.update();
    }
}
