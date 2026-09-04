package com.mimococo.marketops.advertisingefficiency.internal.infrastructure.jdbc;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Durable evidence that a pass through the loop happened, and what it did.
 *
 * <p>Without this, a suppressed duplicate and a dropped trigger look identical
 * afterwards: in both cases nothing was written and nothing is wrong. Recording
 * the suppression makes the difference an observable fact rather than an
 * inference from absence, which is what turns "the queue looks quiet" into a
 * question somebody can answer.
 *
 * <p>Append-only, and the detail is bounded structured JSON rather than a
 * message, so an operator query can filter on it and a log line cannot smuggle
 * an exception message into durable storage.
 */
@Repository
public class AdvertisingTraceRepository {

    private final JdbcClient jdbc;

    AdvertisingTraceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Record one stage of one pass. */
    public void record(UUID id, UUID organizationId, UUID adNativeObjectId, String pathKind,
            String stageCode, String status, String correlationId, String parentCorrelationId,
            String subjectReference, String detailJson, Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO ops.ad_trace_event (
                    id, organization_id, ad_native_object_id, path_kind, stage_code, status,
                    correlation_id, parent_correlation_id, subject_reference, detail, occurred_at)
                VALUES (:id, :organizationId, :adNativeObjectId, :pathKind, :stageCode, :status,
                    :correlationId, :parentCorrelationId, :subjectReference,
                    CAST(:detail AS jsonb), :occurredAt)
                """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("adNativeObjectId", adNativeObjectId)
                .param("pathKind", pathKind)
                .param("stageCode", stageCode)
                .param("status", status)
                .param("correlationId", correlationId)
                .param("parentCorrelationId", parentCorrelationId)
                .param("subjectReference", subjectReference)
                .param("detail", detailJson == null ? "{}" : detailJson)
                .param("occurredAt", ts(occurredAt))
                .update();
    }

    /** Record one internal-latency observation, with source latency kept separate. */
    public void recordSlo(UUID id, UUID organizationId, UUID adNativeObjectId, UUID caseId,
            String lane, String pathKind, Instant sourceEventTime, Instant sourceUpdatedAt,
            Instant ingestedAt, Instant factAcceptedAt, Instant calculatedAt,
            Instant caseUpdatedAt, long internalLatencyMillis, Long sourceLatencyMillis,
            boolean breached, String correlationId) {
        jdbc.sql("""
                INSERT INTO ops.ad_slo_observation (
                    id, organization_id, ad_native_object_id, case_id, lane, path_kind,
                    source_event_time, source_updated_at, ingested_at, fact_accepted_at,
                    calculated_at, case_updated_at, internal_latency_ms, source_latency_ms,
                    breached, correlation_id)
                VALUES (:id, :organizationId, :adNativeObjectId, :caseId, :lane, :pathKind,
                    :sourceEventTime, :sourceUpdatedAt, :ingestedAt, :factAcceptedAt,
                    :calculatedAt, :caseUpdatedAt, :internalLatencyMillis, :sourceLatencyMillis,
                    :breached, :correlationId)
                """)
                .param("id", id).param("organizationId", organizationId)
                .param("adNativeObjectId", adNativeObjectId).param("caseId", caseId)
                .param("lane", lane).param("pathKind", pathKind)
                .param("sourceEventTime", ts(sourceEventTime))
                .param("sourceUpdatedAt", ts(sourceUpdatedAt))
                .param("ingestedAt", ts(ingestedAt)).param("factAcceptedAt", ts(factAcceptedAt))
                .param("calculatedAt", ts(calculatedAt)).param("caseUpdatedAt", ts(caseUpdatedAt))
                .param("internalLatencyMillis", internalLatencyMillis)
                .param("sourceLatencyMillis", sourceLatencyMillis)
                .param("breached", breached).param("correlationId", correlationId)
                .update();
    }

    /**
     * Bind an instant the driver can type.
     *
     * <p>PostgreSQL's driver cannot infer a SQL type for {@link java.time.Instant},
     * and a bare {@code null} is worse: it has no type at all. Wrapping both in a
     * typed parameter value is what the rest of this codebase does, and doing it
     * anywhere else would produce a runtime failure that only shows up on the
     * path that happens to pass a null.
     */
    private static org.springframework.jdbc.core.SqlParameterValue ts(java.time.Instant instant) {
        return new org.springframework.jdbc.core.SqlParameterValue(
                java.sql.Types.TIMESTAMP,
                instant == null ? null : java.sql.Timestamp.from(instant));
    }
}
