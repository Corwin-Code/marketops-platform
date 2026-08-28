package com.mimococo.marketops.operationsworkflow;

import java.time.Instant;
import java.util.UUID;

/**
 * One piece of work a person owns.
 *
 * <p>A task always names the recommendation it came from. Work that arrives
 * without the case behind it gets done without judgement, and the point of the
 * diagnostic loop is that a person can see why they are being asked.
 *
 * @param id the task
 * @param organizationId owning organization
 * @param recommendationId the proposal that produced it
 * @param title what to do
 * @param state where it stands
 * @param assigneeUserId who owns it, or {@code null}
 * @param dueAt when it is due, or {@code null}
 * @param closedAt when it finished, or {@code null}
 * @param closureReason why it finished, or {@code null}
 * @param createdAt when it was raised
 * @param version optimistic-lock version
 */
public record WorkTaskView(
        UUID id,
        UUID organizationId,
        UUID recommendationId,
        String title,
        String state,
        UUID assigneeUserId,
        Instant dueAt,
        Instant closedAt,
        String closureReason,
        Instant createdAt,
        long version) {

    /** Whether the task is finished. */
    public boolean closed() {
        return closedAt != null;
    }
}
