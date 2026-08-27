package com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc;

import com.mimococo.marketops.operationsworkflow.WorkTaskView;
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
 * The work people own, and what happened to it.
 *
 * <p>A task is never deleted. Cancelling one is a recorded closure with a
 * reason, so a queue that empties can be explained: work that was done and work
 * that was abandoned look different, and only one of them is a good sign.
 */
@Repository
public class WorkTaskRepository {

    private final JdbcClient jdbc;

    WorkTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Raise a task from a proposal. */
    public void insert(UUID id, UUID organizationId, UUID recommendationId, String title,
                       Instant dueAt, Instant now) {
        jdbc.sql("""
                        INSERT INTO ops.work_task (
                            id, organization_id, recommendation_id, title, state, due_at,
                            created_at, updated_at, version)
                        VALUES (:id, :organizationId, :recommendationId, :title, 'OPEN',
                            :dueAt, :now, :now, 0)
                        """)
                .param("id", id)
                .param("organizationId", organizationId)
                .param("recommendationId", recommendationId)
                .param("title", title)
                .param("dueAt", dueAt == null ? null : Timestamp.from(dueAt))
                .param("now", Timestamp.from(now))
                .update();
    }

    /** Give a task an owner. */
    public boolean assign(UUID id, UUID assigneeUserId, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.work_task
                        SET state = 'ASSIGNED', assignee_user_id = :assigneeUserId,
                            updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                          AND state IN ('OPEN', 'ASSIGNED')
                        """)
                .param("assigneeUserId", assigneeUserId)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Record that somebody has started. */
    public boolean start(UUID id, Instant at, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.work_task
                        SET state = 'IN_PROGRESS', updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion AND state = 'ASSIGNED'
                        """)
                .param("at", Timestamp.from(at))
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** Close a task, whether it was done or abandoned. */
    public boolean close(UUID id, String state, String closureReason, Instant at,
                         long expectedVersion) {
        return jdbc.sql("""
                        UPDATE ops.work_task
                        SET state = :state, closed_at = :at, closure_reason = :closureReason,
                            updated_at = :at, version = :newVersion
                        WHERE id = :id AND version = :expectedVersion
                          AND state NOT IN ('DONE', 'CANCELLED')
                        """)
                .param("state", state)
                .param("at", Timestamp.from(at))
                .param("closureReason", closureReason)
                .param("newVersion", expectedVersion + 1)
                .param("id", id)
                .param("expectedVersion", expectedVersion)
                .update() == 1;
    }

    /** One task. */
    public Optional<WorkTaskView> find(UUID id) {
        return jdbc.sql(SELECT_TASK + " WHERE id = :id")
                .param("id", id)
                .query(WorkTaskRepository::map)
                .optional();
    }

    /** The open work of one organization, soonest due first. */
    public List<WorkTaskView> openTasks(UUID organizationId, UUID assigneeUserId, int limit) {
        return jdbc.sql(SELECT_TASK + """
                         WHERE organization_id = :organizationId
                           AND state NOT IN ('DONE', 'CANCELLED')
                           AND (CAST(:assigneeUserId AS uuid) IS NULL
                                OR assignee_user_id = :assigneeUserId)
                         ORDER BY due_at NULLS LAST, created_at
                         LIMIT :limit
                        """)
                .param("organizationId", organizationId)
                .param("assigneeUserId", assigneeUserId)
                .param("limit", limit)
                .query(WorkTaskRepository::map)
                .list();
    }

    /** Every task raised from one proposal. */
    public List<WorkTaskView> forRecommendation(UUID recommendationId) {
        return jdbc.sql(SELECT_TASK + """
                         WHERE recommendation_id = :recommendationId
                         ORDER BY created_at
                        """)
                .param("recommendationId", recommendationId)
                .query(WorkTaskRepository::map)
                .list();
    }

    private static final String SELECT_TASK = """
            SELECT id, organization_id, recommendation_id, title, state, assignee_user_id,
                   due_at, closed_at, closure_reason, created_at, version
              FROM ops.work_task
            """;

    private static WorkTaskView map(ResultSet rows, int rowNumber) throws SQLException {
        Timestamp dueAt = rows.getTimestamp("due_at");
        Timestamp closedAt = rows.getTimestamp("closed_at");
        return new WorkTaskView(
                rows.getObject("id", UUID.class),
                rows.getObject("organization_id", UUID.class),
                rows.getObject("recommendation_id", UUID.class),
                rows.getString("title"),
                rows.getString("state"),
                rows.getObject("assignee_user_id", UUID.class),
                dueAt == null ? null : dueAt.toInstant(),
                closedAt == null ? null : closedAt.toInstant(),
                rows.getString("closure_reason"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getLong("version"));
    }
}
