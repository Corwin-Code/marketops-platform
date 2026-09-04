package com.mimococo.marketops.operationsworkflow.internal.application;

import com.mimococo.marketops.adminobservability.audit.AuditAction;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.FieldChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditChange;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.operationsworkflow.WorkTaskEventView;
import com.mimococo.marketops.operationsworkflow.WorkTaskView;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskEventRepository;
import com.mimococo.marketops.operationsworkflow.internal.infrastructure.jdbc.WorkTaskRepository;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.IdGenerator;
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
    private final WorkTaskEventRepository journal;
    private final IdGenerator ids;

    WorkTaskService(WorkTaskRepository tasks, MetadataAuditRecorder auditRecorder,
                    Clock clock, BusinessAuthorization authorization,
                    WorkTaskEventRepository journal, IdGenerator ids) {
        this.tasks = tasks;
        this.auditRecorder = auditRecorder;
        this.clock = clock;
        this.authorization = authorization;
        this.journal = journal;
        this.ids = ids;
    }

    /**
     * Give a task an owner, or hand it to somebody else.
     *
     * <p>Both are recorded, and they are recorded as different things. A task
     * that changes hands keeps the instant it was raised, so its age is the age
     * of the work rather than the age of the current holder's involvement — and
     * a reassignment that reset the clock would let a queue look healthy by
     * being passed around.
     */
    @Transactional
    public void assign(AuthenticatedActor actor, UUID taskId, UUID assigneeUserId,
                       long expectedVersion) {
        authorization.requireOwned(actor, ActionScopeCode.TASK_ASSIGN,
                new OwnedResource(OwnedResource.Kind.WORK_TASK, taskId));
        String operator = actor.userId().toString();
        WorkTaskView task = require(taskId);
        UUID previousAssignee = task.assigneeUserId();
        if (!tasks.assign(taskId, assigneeUserId, clock.instant(), expectedVersion)) {
            throw OperationRejectedException.of(ErrorCode.VERSION_CONFLICT);
        }
        boolean handover = previousAssignee != null && !previousAssignee.equals(assigneeUserId);
        journal.append(new WorkTaskEventRepository.Event(
                ids.newId(), taskId, task.organizationId(),
                handover ? "REASSIGNED" : "ASSIGNED", lineageOf(task),
                null, null, null, null, null,
                handover ? previousAssignee : null, assigneeUserId,
                actor.userId(), null,
                handover ? "the task changed hands and kept its age"
                        : "the task was given an owner",
                clock.instant(), "task-assign:" + taskId));
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

    /**
     * Somebody opened the task, which is not the same as taking it on.
     *
     * <p>Recorded because a service level has to be able to tell an unopened
     * task from one somebody has read and left, and because the recording must
     * not be capable of being presented as engagement: the schema refuses a view
     * that carries an acknowledgement, an action or an outcome.
     */
    @Transactional
    public void recordView(AuthenticatedActor actor, UUID taskId) {
        authorization.requireOwned(actor, ActionScopeCode.TASK_ASSIGN,
                new OwnedResource(OwnedResource.Kind.WORK_TASK, taskId));
        WorkTaskView task = require(taskId);
        journal.append(new WorkTaskEventRepository.Event(
                ids.newId(), taskId, task.organizationId(), "VIEWED", lineageOf(task),
                null, null, null, null, null, null, null, actor.userId(), null,
                "the task was opened", clock.instant(), "task-view:" + taskId));
    }

    /**
     * Somebody has taken the work on, which is not the same as doing it.
     *
     * <p>An acknowledgement names a person and nothing else. The schema refuses
     * one that carries an action, so this cannot become a route by which reading
     * a page satisfies an action stage.
     */
    @Transactional
    public void acknowledge(AuthenticatedActor actor, UUID taskId) {
        authorization.requireOwned(actor, ActionScopeCode.TASK_ASSIGN,
                new OwnedResource(OwnedResource.Kind.WORK_TASK, taskId));
        WorkTaskView task = require(taskId);
        journal.append(new WorkTaskEventRepository.Event(
                ids.newId(), taskId, task.organizationId(), "ACKNOWLEDGED", lineageOf(task),
                null, null, null, null, null, null, null, actor.userId(), null,
                "the task was acknowledged", clock.instant(), "task-acknowledge:" + taskId));
    }

    /**
     * A structured action somebody performed, with the evidence for it.
     *
     * <p>The schema requires all three — the action, its evidence and the person
     * — so a caller cannot record an action it cannot support. What this method
     * adds is that it also cannot record an outcome: an action is a thing done,
     * and what it achieved is observed later, against evidence this moment does
     * not have.
     */
    @Transactional
    public void recordAction(AuthenticatedActor actor, UUID taskId, String actionKind,
                             String evidenceReference, String reason) {
        authorization.requireOwned(actor, ActionScopeCode.TASK_ASSIGN,
                new OwnedResource(OwnedResource.Kind.WORK_TASK, taskId));
        WorkTaskView task = require(taskId);
        String reference = MetadataFieldPolicy.requireText("evidenceReference",
                evidenceReference);
        journal.append(new WorkTaskEventRepository.Event(
                ids.newId(), taskId, task.organizationId(), "ACTION_RECORDED", lineageOf(task),
                MetadataFieldPolicy.requireText("actionKind", actionKind),
                "{\"reference\":\"" + reference.replace("\"", "'") + "\"}",
                reference, null, null, null, null, actor.userId(), null,
                MetadataFieldPolicy.requireText("reason", reason), clock.instant(),
                "task-action:" + taskId));
    }

    /**
     * What the action turned out to have achieved.
     *
     * <p>A separate event from the action, made later, naming the observation it
     * read. Nothing about an action may claim this, and nothing here may carry an
     * action, because "we did it" and "it worked" are different claims and only
     * one of them can be made at the time of doing.
     */
    @Transactional
    public void recordOutcome(UUID taskId, String outcomeKind, String outcomeReference,
                              String reason, String correlationId) {
        WorkTaskView task = require(taskId);
        journal.append(new WorkTaskEventRepository.Event(
                ids.newId(), taskId, task.organizationId(), "OUTCOME_OBSERVED", lineageOf(task),
                null, null, null,
                MetadataFieldPolicy.requireText("outcomeKind", outcomeKind),
                MetadataFieldPolicy.requireText("outcomeReference", outcomeReference),
                null, null, null, null,
                MetadataFieldPolicy.requireText("reason", reason), clock.instant(),
                correlationId));
    }

    /**
     * The work came back, or went up.
     *
     * <p>Both continue the lineage they belong to rather than starting one. A
     * reopen that began a new lineage would present a recurring problem as a
     * series of unrelated first occurrences, which is exactly how a systemic
     * fault stays invisible.
     */
    @Transactional
    public void reopen(AuthenticatedActor actor, UUID taskId, boolean escalated, String reason) {
        authorization.requireOwned(actor, ActionScopeCode.TASK_ASSIGN,
                new OwnedResource(OwnedResource.Kind.WORK_TASK, taskId));
        WorkTaskView task = require(taskId);
        journal.append(new WorkTaskEventRepository.Event(
                ids.newId(), taskId, task.organizationId(),
                escalated ? "ESCALATED" : "REOPENED", lineageOf(task),
                null, null, null, null, null, null, null, actor.userId(), null,
                MetadataFieldPolicy.requireText("reason", reason), clock.instant(),
                "task-reopen:" + taskId));
    }

    /** One task's whole history, oldest first. */
    public List<WorkTaskEventView> journal(UUID taskId) {
        return journal.journal(taskId);
    }

    /** Every event in one lineage, across every reopen and escalation. */
    public List<WorkTaskEventView> lineage(UUID organizationId, String lineageKey) {
        return journal.lineage(organizationId, lineageKey);
    }

    /**
     * The lineage a task belongs to.
     *
     * <p>The recommendation, because that is what the work is about. A task
     * reopened for the same proposal is the same work continuing; a task raised
     * from a different proposal is different work whatever it is called.
     */
    private static String lineageOf(WorkTaskView task) {
        return "recommendation:" + task.recommendationId();
    }

    private WorkTaskView require(UUID taskId) {
        return tasks.find(taskId)
                .orElseThrow(() -> OperationRejectedException.forEntity(
                        ErrorCode.RESOURCE_NOT_FOUND, AuditSourceDomain.OPERATIONS_WORKFLOW.dbValue(),
                        ENTITY_TYPE, taskId, null));
    }
}
