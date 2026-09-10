package com.mimococo.marketops.marketplaceintegration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One description command with its attempts and readbacks, for a person to read.
 *
 * <p>Carries digests rather than texts: the full Russian text is reviewed on
 * the action, and the command timeline answers what was called and what the
 * marketplace answered.
 */
public record ListingDescriptionCommandView(
        UUID id,
        UUID actionId,
        UUID recommendationId,
        UUID storeId,
        UUID platformListingId,
        String platformCode,
        String state,
        String priorTextDigest,
        String targetTextDigest,
        boolean priorTextCaptured,
        boolean kizMarkedDeclared,
        String equivalenceRule,
        String affectedSetDigest,
        int attemptNo,
        int retryBudgetRemaining,
        String failureCode,
        Instant approvalExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant terminalAt,
        List<Attempt> attempts,
        List<Readback> readbacks,
        List<ExecutionReceipt> executionReceipts) {

    public record Attempt(UUID id, int attemptNo, String purpose, String outcomeClass,
                          String nativeStatus, String errorCode, Instant startedAt, Instant completedAt) {
    }

    public record Readback(UUID id, String matchState, String observedTextDigest,
                           Boolean observedKizMarked, Instant observedAt) {
    }

    public record ExecutionReceipt(UUID id, String executionState, UUID readbackId, UUID mutationAttemptId,
                                   UUID nativeStatusAttemptId, List<String> gaps, Instant recordedAt,
                                   UUID taskEventId, Instant taskRecordedAt) {
        public ExecutionReceipt { gaps=List.copyOf(gaps); }
    }

    public ListingDescriptionCommandView {
        executionReceipts=List.copyOf(executionReceipts == null ? List.of() : executionReceipts);
        attempts = List.copyOf(attempts == null ? List.of() : attempts);
        readbacks = List.copyOf(readbacks == null ? List.of() : readbacks);
    }
}
