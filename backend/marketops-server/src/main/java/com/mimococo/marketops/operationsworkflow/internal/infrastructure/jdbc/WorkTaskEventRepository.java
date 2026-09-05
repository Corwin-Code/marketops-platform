package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.operationsworkflow.WorkTaskEventView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The append-only journal of what happened to a task.
 *
 * <p>Append-only because there is no grant that would allow anything else: the
 * application role holds {@code SELECT} and {@code INSERT} on
 * {@code ops.work_task_event} and nothing more. A journal whose entries could be
 * edited is not a journal, and the schema rather than this class is what makes
 * that true.
 *
 * <p>The sequence number is allocated inside the insert, so two appends racing
 * for the same task collide on the unique constraint rather than producing two
 * events that both claim to be third.
 */
@Repository
public class WorkTaskEventRepository {

    private final JdbcClient jdbc;

    WorkTaskEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One event to append.
     *
     * @param id the event's own identity
     * @param taskId the task it happened to
     * @param organizationId the organization that owns the task
     * @param eventKind what happened
     * @param lineageKey the lineage a reopen or escalation continues
     * @param actionKind the structured action, for an action event only
     * @param actionEvidenceJson what it rests on, for an action event only
     * @param evidenceReference where that evidence lives
     * @param outcomeKind the outcome reading, for an outcome event only
     * @param outcomeReference the observation it names
     * @param fromAssigneeUserId who held it before, for a reassignment only
     * @param toAssigneeUserId who holds it now, for an assignment
     * @param actorUserId the person who did it, where a person did
     * @param actorRoleCode the role they acted in
     * @param reason the words written at the time
     * @param occurredAt when it happened
     * @param correlationId the run or request that caused it
     */
    public record Event(UUID id, UUID taskId, UUID organizationId, String eventKind,
                        String lineageKey, String actionKind, String actionEvidenceJson,
                        String evidenceReference, String outcomeKind, String outcomeReference,
                        UUID fromAssigneeUserId, UUID toAssigneeUserId, UUID actorUserId,
                        String actorRoleCode, String reason, Instant occurredAt,
                        String correlationId) {
    }

    /** Append one event, taking the next sequence number for its task. */
    @org.springframework.transaction.annotation.Transactional
    public void append(Event event) {
        jdbc.sql("SELECT id FROM ops.work_task WHERE id=:id FOR UPDATE")
                .param("id",event.taskId()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO ops.work_task_event (
                    id, task_id, organization_id, sequence_no, event_kind, lineage_key,
                    action_kind, action_evidence, evidence_reference, outcome_kind,
                    outcome_reference, from_assignee_user_id, to_assignee_user_id,
                    actor_user_id, actor_role_code, reason, occurred_at, correlation_id)
                VALUES (:id, :taskId, :organizationId,
                    (SELECT coalesce(max(sequence_no), 0) + 1
                       FROM ops.work_task_event WHERE task_id = :taskId),
                    :eventKind, :lineageKey, :actionKind,
                    CAST(:actionEvidence AS jsonb), :evidenceReference, :outcomeKind,
                    :outcomeReference, :fromAssignee, :toAssignee, :actorUserId,
                    :actorRoleCode, :reason, :occurredAt, :correlationId)
                """)
                .param("id", event.id())
                .param("taskId", event.taskId())
                .param("organizationId", event.organizationId())
                .param("eventKind", event.eventKind())
                .param("lineageKey", event.lineageKey())
                .param("actionKind", event.actionKind())
                .param("actionEvidence", event.actionEvidenceJson())
                .param("evidenceReference", event.evidenceReference())
                .param("outcomeKind", event.outcomeKind())
                .param("outcomeReference", event.outcomeReference())
                .param("fromAssignee", event.fromAssigneeUserId())
                .param("toAssignee", event.toAssigneeUserId())
                .param("actorUserId", event.actorUserId())
                .param("actorRoleCode", event.actorRoleCode())
                .param("reason", event.reason())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("correlationId", event.correlationId())
                .update();
    }

    /** One task's whole history, oldest first. */
    public List<WorkTaskEventView> journal(UUID taskId) {
        return jdbc.sql("""
                SELECT id, task_id, sequence_no, event_kind, lineage_key, action_kind,
                       action_evidence, evidence_reference, outcome_kind, outcome_reference,
                       from_assignee_user_id, to_assignee_user_id, actor_user_id,
                       actor_role_code, reason, occurred_at
                  FROM ops.work_task_event
                 WHERE task_id = :taskId
                 ORDER BY sequence_no
                """)
                .param("taskId", taskId)
                .query(WorkTaskEventRepository::map)
                .list();
    }

    /** Every task event in one lineage, across reopens and escalations. */
    public List<WorkTaskEventView> lineage(UUID organizationId, String lineageKey) {
        return jdbc.sql("""
                SELECT id, task_id, sequence_no, event_kind, lineage_key, action_kind,
                       action_evidence, evidence_reference, outcome_kind, outcome_reference,
                       from_assignee_user_id, to_assignee_user_id, actor_user_id,
                       actor_role_code, reason, occurred_at
                  FROM ops.work_task_event
                 WHERE organization_id = :organizationId AND lineage_key = :lineageKey
                 ORDER BY occurred_at, sequence_no
                """)
                .param("organizationId", organizationId)
                .param("lineageKey", lineageKey)
                .query(WorkTaskEventRepository::map)
                .list();
    }

    private static WorkTaskEventView map(ResultSet rs, int index) throws SQLException {
        return new WorkTaskEventView(
                rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class),
                rs.getInt("sequence_no"),
                rs.getString("event_kind"),
                rs.getString("lineage_key"),
                rs.getString("action_kind"),
                rs.getString("action_evidence"),
                rs.getString("evidence_reference"),
                rs.getString("outcome_kind"),
                rs.getString("outcome_reference"),
                rs.getObject("from_assignee_user_id", UUID.class),
                rs.getObject("to_assignee_user_id", UUID.class),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_role_code"),
                rs.getString("reason"),
                rs.getTimestamp("occurred_at").toInstant());
    }
}
