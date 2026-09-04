package com.mimococo.marketops.marketplaceintegration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One advertising bid command, as the console and the workflow read it.
 *
 * <p>The attempt and readback history travels with it because an operator
 * resolving an unknown state needs to see what was actually tried and what was
 * actually observed, not a summary somebody wrote.
 */
public record AdBidCommandView(
        UUID id,
        UUID recommendationId,
        UUID storeId,
        UUID adNativeObjectId,
        String platformCode,
        String direction,
        String candidateBasis,
        String materialityRoute,
        String state,
        String currencyCode,
        String bidUnitCode,
        BigDecimal priorBidAmount,
        BigDecimal targetBidAmount,
        String affectedSetDigest,
        int attemptNo,
        int retryBudgetRemaining,
        String failureCode,
        Instant approvalExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant terminalAt,
        List<Attempt> attempts,
        List<Readback> readbacks) {

    /** One call made on behalf of this command. */
    public record Attempt(
            UUID id, int attemptNo, String purpose, String outcomeClass, String nativeStatus,
            String errorCode, Instant startedAt, Instant completedAt) {
    }

    /** One observation of what the platform holds. */
    public record Readback(
            UUID id, String matchState, BigDecimal observedBid, String currencyCode,
            Instant observedAt) {
    }

    public AdBidCommandView {
        attempts = List.copyOf(attempts == null ? List.of() : attempts);
        readbacks = List.copyOf(readbacks == null ? List.of() : readbacks);
    }
}
