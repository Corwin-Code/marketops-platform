package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One content command, the change it carries out and every call it made (W2).
 *
 * @param state PENDING, AWAITING_TASK, AWAITING_READBACK, UNKNOWN_REQUIRES_READBACK,
 *        READBACK_MISMATCH, SUCCEEDED, FAILED_BEFORE_WRITE, FAILED, CANCELLED or CLOSED
 * @param gateReasons why the command last found the gate closed, empty once past it
 * @param outcomeCode a stable code for how the command ended or why it waits, or {@code null}
 * @param outcomeDetail what the platform said, bounded, or {@code null}
 * @param events every call and move, in order
 */
public record ContentCommandView(
        UUID id,
        UUID changeId,
        UUID storeId,
        UUID platformListingVariantId,
        String nativeListingKey,
        String offerKey,
        String state,
        List<String> gateReasons,
        String outcomeCode,
        String outcomeDetail,
        String nativeTaskKey,
        Instant nextActionAt,
        Instant createdAt,
        Instant updatedAt,
        Instant terminalAt,
        String priorTitle,
        String priorDescription,
        Instant priorObservedAt,
        String targetTitle,
        String targetDescription,
        boolean titleChanged,
        boolean descriptionChanged,
        UUID sourceInvocationId,
        String reason,
        UUID approvedByUserId,
        Instant approvedAt,
        Instant approvalExpiresAt,
        List<Event> events) {

    public ContentCommandView {
        gateReasons = List.copyOf(gateReasons);
        events = List.copyOf(events);
    }

    /** Whether the command has finished. */
    public boolean terminal() {
        return terminalAt != null;
    }

    /**
     * One recorded call or move.
     *
     * @param kind CREATED, GATE_CLOSED, PRE_READ, APPLY_STARTED, APPLY, STATUS, READBACK, STATE or
     *        RESOLUTION
     * @param titleMatch / descriptionMatch MATCHES_TARGET, MATCHES_PRIOR or DIFFERENT for a read
     */
    public record Event(int sequence, String kind, String stateAfter, Integer httpStatus, String outcome,
                        String nativeTaskKey, String taskStatus, String observedTitle,
                        String observedDescription, String titleMatch, String descriptionMatch, String detail,
                        UUID actorUserId, Instant recordedAt) {
    }
}
