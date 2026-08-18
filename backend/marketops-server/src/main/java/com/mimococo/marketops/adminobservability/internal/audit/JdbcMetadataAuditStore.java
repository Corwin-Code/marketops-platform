package com.mimococo.marketops.adminobservability.internal.audit;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditActorType;
import com.mimococo.marketops.adminobservability.audit.AuditEventFilter;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditDenial;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditEntry;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditQueries;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Journal persistence over the append-only audit table.
 *
 * <p>Inserts never supply {@code occurred_at}: the database clock stamps every
 * event, so recorded time cannot be forged by a caller. The application role
 * holds INSERT and SELECT only, which makes the append-only property a database
 * fact rather than a convention this class promises.
 *
 * <p>Every journaled event is mirrored as one structured log record and one
 * bounded-tag counter increment. The journal stays the single authority; the
 * log and the counters carry references only.
 */
@Component
class JdbcMetadataAuditStore implements MetadataAuditRecorder, MetadataAuditQueries {

    private static final Logger log = LoggerFactory.getLogger(JdbcMetadataAuditStore.class);

    private static final int MAX_PAGE = 200;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final IdGenerator idGenerator;
    private final MeterRegistry meterRegistry;

    JdbcMetadataAuditStore(JdbcClient jdbc,
                           ObjectMapper objectMapper,
                           IdGenerator idGenerator,
                           MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
        this.meterRegistry = meterRegistry;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordChange(MetadataAuditChange change) {
        try {
            insert(
                    AuditActorType.OPERATOR,
                    change.actorId(),
                    change.sourceDomain(),
                    change.action(),
                    change.entityType(),
                    change.entityId(),
                    change.entityCode(),
                    changeSummaryJson(change),
                    null,
                    change.reason(),
                    change.evidenceRef());
        } catch (DataAccessException failure) {
            observeWriteFailure(change.sourceDomain(), change.entityType(),
                    change.action(), failure);
            throw failure;
        }
        log.atInfo()
                .addKeyValue("event", "metadata_change_recorded")
                .addKeyValue("sourceDomain", change.sourceDomain().dbValue())
                .addKeyValue("entityType", change.entityType())
                .addKeyValue("entityId", String.valueOf(change.entityId()))
                .addKeyValue("action", change.action().name())
                .addKeyValue("actorId", change.actorId())
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Metadata change recorded");
        meterRegistry.counter("marketops.metadata.changes",
                "domain", change.sourceDomain().dbValue(),
                "action", change.action().name()).increment();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDenial(MetadataAuditDenial denial) {
        try {
            insert(
                    denial.actorType(),
                    denial.actorId(),
                    denial.sourceDomain(),
                    AuditAction.DENIED,
                    denial.entityType(),
                    denial.entityId(),
                    denial.entityCode(),
                    null,
                    denial.denialCode(),
                    denial.reason(),
                    null);
        } catch (DataAccessException failure) {
            observeWriteFailure(denial.sourceDomain(), denial.entityType(),
                    AuditAction.DENIED, failure);
            throw failure;
        }
        log.atWarn()
                .addKeyValue("event", "metadata_change_denied")
                .addKeyValue("sourceDomain", denial.sourceDomain().dbValue())
                .addKeyValue("entityType", String.valueOf(denial.entityType()))
                .addKeyValue("entityId", String.valueOf(denial.entityId()))
                .addKeyValue("action", AuditAction.DENIED.name())
                .addKeyValue("actorId", denial.actorId())
                .addKeyValue("denialCode", denial.denialCode())
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Metadata change denied");
        meterRegistry.counter("marketops.metadata.denials",
                "code", denial.denialCode()).increment();
    }

    private void observeWriteFailure(AuditSourceDomain sourceDomain,
                                     String entityType,
                                     AuditAction action,
                                     DataAccessException failure) {
        log.atError()
                .addKeyValue("event", "audit_write_failed")
                .addKeyValue("sourceDomain", sourceDomain.dbValue())
                .addKeyValue("entityType", String.valueOf(entityType))
                .addKeyValue("action", action.name())
                .addKeyValue("correlationId", CorrelationId.current())
                .addKeyValue("exceptionClass", failure.getClass().getName())
                .log("Audit journal write failed");
        meterRegistry.counter("marketops.audit.write.failures").increment();
    }

    private void insert(AuditActorType actorType,
                        String actorId,
                        AuditSourceDomain sourceDomain,
                        AuditAction action,
                        String entityType,
                        UUID entityId,
                        String entityCode,
                        String changeSummary,
                        String denialCode,
                        String reason,
                        String evidenceRef) {
        jdbc.sql("""
                        INSERT INTO ops.metadata_audit_event (
                            id, actor_type, actor_id, source_domain, action,
                            entity_type, entity_id, entity_code, change_summary,
                            denial_code, reason, correlation_id, evidence_ref)
                        VALUES (:id, :actorType, :actorId, :sourceDomain, :action,
                            :entityType, :entityId, :entityCode, CAST(:changeSummary AS jsonb),
                            :denialCode, :reason, :correlationId, :evidenceRef)
                        """)
                .param("id", idGenerator.newId())
                .param("actorType", actorType.name())
                .param("actorId", actorId)
                .param("sourceDomain", sourceDomain.dbValue())
                .param("action", action.name())
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("entityCode", entityCode)
                .param("changeSummary", changeSummary)
                .param("denialCode", denialCode)
                .param("reason", reason)
                .param("correlationId", CorrelationId.current())
                .param("evidenceRef", evidenceRef)
                .update();
    }

    private String changeSummaryJson(MetadataAuditChange change) {
        if (change.changes() == null || change.changes().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(change.changes());
        } catch (JacksonException unrepresentable) {
            // Field changes are validated text; a document that cannot be
            // rendered indicates a programming defect, and failing here rolls
            // the mutation back rather than journaling a partial truth.
            throw new IllegalStateException("audit change summary is unrepresentable");
        }
    }

    @Override
    public List<MetadataAuditEntry> find(AuditEventFilter filter) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, occurred_at, actor_type, actor_id, source_domain, action,
                       entity_type, entity_id, entity_code, CAST(change_summary AS text) AS change_summary,
                       denial_code, reason, correlation_id, evidence_ref
                FROM ops.metadata_audit_event
                WHERE 1 = 1
                """);
        Map<String, Object> params = new HashMap<>();
        appendEquals(sql, params, "actor_id", "actorId", filter.actorId());
        appendEquals(sql, params, "source_domain", "sourceDomain",
                filter.sourceDomain() == null ? null : filter.sourceDomain().dbValue());
        appendEquals(sql, params, "action", "action",
                filter.action() == null ? null : filter.action().name());
        appendEquals(sql, params, "entity_type", "entityType", filter.entityType());
        appendEquals(sql, params, "entity_id", "entityId", filter.entityId());
        if (filter.occurredFrom() != null) {
            sql.append(" AND occurred_at >= :occurredFrom");
            params.put("occurredFrom", Timestamp.from(filter.occurredFrom()));
        }
        if (filter.occurredTo() != null) {
            sql.append(" AND occurred_at < :occurredTo");
            params.put("occurredTo", Timestamp.from(filter.occurredTo()));
        }
        if (filter.beforeOccurredAt() != null && filter.beforeId() != null) {
            sql.append(" AND (occurred_at, id) < (:beforeOccurredAt, :beforeId)");
            params.put("beforeOccurredAt", Timestamp.from(filter.beforeOccurredAt()));
            params.put("beforeId", filter.beforeId());
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT :pageLimit");
        params.put("pageLimit", Math.clamp(filter.limit(), 1, MAX_PAGE));

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString());
        for (Map.Entry<String, Object> parameter : params.entrySet()) {
            statement = statement.param(parameter.getKey(), parameter.getValue());
        }
        return statement.query(JdbcMetadataAuditStore::mapEntry).list();
    }

    private static void appendEquals(StringBuilder sql,
                                     Map<String, Object> params,
                                     String column,
                                     String name,
                                     Object value) {
        if (value != null) {
            sql.append(" AND ").append(column).append(" = :").append(name);
            params.put(name, value);
        }
    }

    private static MetadataAuditEntry mapEntry(ResultSet row, int rowNumber) throws SQLException {
        return new MetadataAuditEntry(
                row.getObject("id", UUID.class),
                instant(row.getTimestamp("occurred_at")),
                AuditActorType.valueOf(row.getString("actor_type")),
                row.getString("actor_id"),
                AuditSourceDomain.fromDbValue(row.getString("source_domain")),
                AuditAction.valueOf(row.getString("action")),
                row.getString("entity_type"),
                row.getObject("entity_id", UUID.class),
                row.getString("entity_code"),
                row.getString("change_summary"),
                row.getString("denial_code"),
                row.getString("reason"),
                row.getString("correlation_id"),
                row.getString("evidence_ref"));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
