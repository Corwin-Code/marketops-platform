package com.mimococo.marketops.marketplaceintegration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One command and everything that happened to it.
 *
 * <p>The attempts and readbacks travel with it because the question an operator
 * asks about a price change is never just what state it is in. A command sitting
 * in {@code UNKNOWN_REQUIRES_READBACK} is only actionable alongside what was
 * actually called and what the platform actually answered.
 *
 * @param id the command
 * @param recommendationId the proposal it executes
 * @param storeId store the listing sits on
 * @param platformListingVariantId the listing variant it changes
 * @param platformCode marketplace it targets
 * @param idempotencyKey identity a platform retry must not duplicate
 * @param currencyCode currency of both prices
 * @param priorPrice the price held before
 * @param targetPrice the price intended
 * @param state where it stands
 * @param attemptNo how many attempts have been made
 * @param retryBudgetRemaining how many retriable failures may still be absorbed
 * @param failureCode why it failed, or {@code null}
 * @param leaseOwner the worker holding it, or {@code null}
 * @param leaseExpiresAt when that claim lapses, or {@code null}
 * @param nextAttemptAt when it may next be tried, or {@code null}
 * @param createdAt when it was created
 * @param terminalAt when it finished, or {@code null}
 * @param attempts every call made on its behalf
 * @param readbacks every observation of what the platform holds
 */
public record PriceCommandView(
        UUID id,
        UUID recommendationId,
        UUID storeId,
        UUID platformListingVariantId,
        String platformCode,
        String idempotencyKey,
        String currencyCode,
        BigDecimal priorPrice,
        BigDecimal targetPrice,
        PriceCommandState state,
        int attemptNo,
        int retryBudgetRemaining,
        String failureCode,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant terminalAt,
        List<Attempt> attempts,
        List<Readback> readbacks) {

    public PriceCommandView {
        attempts = List.copyOf(Objects.requireNonNull(attempts, "attempts"));
        readbacks = List.copyOf(Objects.requireNonNull(readbacks, "readbacks"));
    }

    /**
     * One call made against a platform.
     *
     * @param id the attempt
     * @param attemptNo its position in the sequence
     * @param purpose what the call was for
     * @param startedAt when it began
     * @param completedAt when it finished, or {@code null} while in flight
     * @param outcomeClass how the answer was classified
     * @param nativeStatus the platform's own words, or {@code null}
     * @param nativeTaskKey the platform's handle for asynchronous work, or {@code null}
     * @param rawObservationId the stored evidence, or {@code null}
     * @param errorCode why it did not succeed, or {@code null}
     */
    public record Attempt(UUID id, int attemptNo, String purpose, Instant startedAt,
                          Instant completedAt, String outcomeClass, String nativeStatus,
                          String nativeTaskKey, UUID rawObservationId, String errorCode) {
    }

    /**
     * What a later read of the platform observed.
     *
     * @param id the readback
     * @param observedAt when the platform reported it
     * @param observedPrice the price observed, or {@code null} when unreadable
     * @param currencyCode currency observed, or {@code null}
     * @param matchState how it compares to the intended value
     * @param rawObservationId the stored evidence, or {@code null}
     */
    public record Readback(UUID id, Instant observedAt, BigDecimal observedPrice,
                           String currencyCode, String matchState, UUID rawObservationId) {
    }
}
