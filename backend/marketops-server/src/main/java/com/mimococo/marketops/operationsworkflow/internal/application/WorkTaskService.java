package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.operationsworkflow.WorkTaskView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.time.Clock;
import com.mimococo.marketops.identityaccess.AuthenticatedActor;
import com.mimococo.marketops.identityaccess.BusinessAuthorization;
import com.mimococo.marketops.identityaccess.ActionScopeCode;
import com.mimococo.marketops.identityaccess.OwnedResource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work people own.
 *
 * <p>Closing a task always names a reason, whether it was done or abandoned. A
 * queue that empties should be explainable, and work that was quietly cancelled
 * looks exactly like work that was completed unless the record says otherwise.
 */
@Service
public class WorkTaskService {

    static final String ENTITY_TYPE = "work-task";

    private final WorkTaskRepository tasks;
    private final MetadataAuditRecorder auditRecorder;
    private final Clock clock;
    private final BusinessAuthorization authorization;

    WorkTaskService(WorkTaskRepository tasks, MetadataAuditRecorder auditRecorder,
                    Clock clock, BusinessAuthorization authorization) {
        this.tasks = tasks;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
        this.authorization = authorization;
    }

    /** Give a task an owner. */
    @Transactional
    public void assign(AuthenticatedActor actor, UUID taskId, UUID assigneeUserId,
                       long expectedVersion) {
        authorization.requireOwned(actor, ActionScopeCode.TASK_ASSIGN,
                new OwnedResource(OwnedResource.Kind.WORK_TASK, taskId));
        String operator = actor.userId().toString();
        WorkTaskView task = require(taskId);
        if (!tasks.assign(taskId, assigneeUserId, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, operator, AuditAction.UPDATE,
                ENTITY_TYPE, taskId, null,
                Map.of(
                        "state", new FieldChange(task.state(), "ASSIGNED"),
                        "assigneeUserId", new FieldChange(
                                task.assigneeUserId() == null
                                        ? null : task.assigneeUserId().toString(),
                                assigneeUserId.toString())),
                null, null));
    }

    /** Record that somebody has started. */
    @Transactional
    public void start(AuthenticatedActor actor, UUID taskId, long expectedVersion) {
        authorization.requireOwned(actor, ActionScopeCode.TASK_ASSIGN,
                new OwnedResource(OwnedResource.Kind.WORK_TASK, taskId));
        String operator = actor.userId().toString();
        WorkTaskView task = require(taskId);
        if (!tasks.start(taskId, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.INVALID_STATE_TRANSITION);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, taskId, null,
                Map.of("state", new FieldChange(task.state(), "IN_PROGRESS")),
                null, null));
    }

    /** Finish a task, whether it was done or abandoned. */
    @Transactional
    public void close(AuthenticatedActor actor, UUID taskId, boolean done, String closureReason,
                      long expectedVersion) {
        authorization.requireOwned(actor, ActionScopeCode.TASK_ASSIGN,
                new OwnedResource(OwnedResource.Kind.WORK_TASK, taskId));
        String operator = actor.userId().toString();
        WorkTaskView task = require(taskId);
        String reason = MetadataFieldPolicy.requireText("closureReason", closureReason);
        String state = done ? "DONE" : "CANCELLED";
        if (!tasks.close(taskId, state, reason, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        auditRecorder.recordChange(new MetadataAuditChange(
                AuditSourceDomain.OPERATIONS_WORKFLOW, operator, AuditAction.STATUS_CHANGE,
                ENTITY_TYPE, taskId, null,
                Map.of("state", new FieldChange(task.state(), state)),
                reason, null));
    }

    /** The open work of one organization, soonest due first. */
    @Transactional(readOnly = true)
    public List<WorkTaskView> openTasks(UUID organizationId, UUID assigneeUserId, int limit) {
        return tasks.openTasks(organizationId, assigneeUserId, limit);
    }

    /** Every task raised from one proposal. */
    @Transactional(readOnly = true)
    public List<WorkTaskView> forRecommendation(UUID recommendationId) {
        return tasks.forRecommendation(recommendationId);
    }

    /** One task. */
    @Transactional(readOnly = true)
    public Optional<WorkTaskView> find(UUID taskId) {
        return tasks.find(taskId);
    }

    private WorkTaskView require(UUID taskId) {
        return tasks.find(taskId)
                .orElseThrow(() -> OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND, AuditSourceDomain.OPERATIONS_WORKFLOW.dbValue(),
                        ENTITY_TYPE, taskId, null));
    }
}
