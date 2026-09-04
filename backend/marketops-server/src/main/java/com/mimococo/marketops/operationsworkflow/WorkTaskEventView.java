package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One thing that happened to a task, in the order it happened.
 *
 * <p>The distinctions this record refuses to blur are the point of it. Opening a
 * page is not an acknowledgement; an acknowledgement is not an action; an action
 * is not an outcome; and a task that changes hands is the same task, not a new
 * one. A journal that collapsed any of those would let a service level be
 * reported as met by somebody who had only looked at it.
 *
 * @param id the event
 * @param taskId the task it happened to
 * @param sequenceNo its position, starting at one
 * @param eventKind what happened
 * @param lineageKey the lineage a reopen or escalation continues
 * @param actionKind the structured action, when this is one
 * @param actionEvidence what the action rests on, when this is one
 * @param evidenceReference where that evidence lives
 * @param outcomeKind which outcome reading this is, when it is one
 * @param outcomeReference the observation it names
 * @param fromAssigneeUserId who held the task before, on a reassignment
 * @param toAssigneeUserId who holds it now, on an assignment
 * @param actorUserId the person, where a person did it
 * @param actorRoleCode the role they acted in
 * @param reason the words somebody wrote at the time
 * @param occurredAt when it happened
 */
public record WorkTaskEventView(
        UUID id,
        UUID taskId,
        int sequenceNo,
        String eventKind,
        String lineageKey,
        String actionKind,
        String actionEvidence,
        String evidenceReference,
        String outcomeKind,
        String outcomeReference,
        UUID fromAssigneeUserId,
        UUID toAssigneeUserId,
        UUID actorUserId,
        String actorRoleCode,
        String reason,
        Instant occurredAt) {

    public WorkTaskEventView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(eventKind, "eventKind");
        Objects.requireNonNull(lineageKey, "lineageKey");
    }

    /** Whether this event is a structured action somebody performed. */
    public boolean action() {
        return "ACTION_RECORDED".equals(eventKind);
    }

    /**
     * Whether this event could satisfy an action stage.
     *
     * <p>Deliberately not "did somebody engage with it". A view and an
     * acknowledgement are both engagement and neither is an action, which is the
     * whole reason the three are separate kinds.
     */
    public boolean satisfiesActionStage() {
        return action() && actionKind != null && evidenceReference != null
                && actorUserId != null;
    }

    /** Whether this event is a later observation of what the action achieved. */
    public boolean outcome() {
        return "OUTCOME_OBSERVED".equals(eventKind);
    }

    /** Whether the task changed hands here. */
    public boolean handover() {
        return "REASSIGNED".equals(eventKind);
    }
}
