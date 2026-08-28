package com.mimococo.marketops.marketplaceintegration.port;

import com.mimococo.marketops.shared.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One operation to perform against a platform on behalf of a command.
 *
 * <p>The native keys are the marketplace's own identifiers, carried verbatim.
 * Nothing here translates them, because a marketplace's identifier space is its
 * fact and a normalized version of it would be this product's invention.
 *
 * @param operation what to do: apply, enquire, read back or restore
 * @param capabilityId the write capability being used
 * @param credentialId the credential the call authenticates with
 * @param nativeListingKey the marketplace's own listing identifier
 * @param nativeVariantKey the marketplace's own variant identifier
 * @param targetPrice the price this operation is about
 * @param idempotencyKey identity a platform retry must not duplicate
 * @param nativeTaskKey the platform's handle for asynchronous work, or {@code null}
 */
public record PriceWriteRequest(
        Operation operation,
        UUID capabilityId,
        UUID credentialId,
        String nativeListingKey,
        String nativeVariantKey,
        Money targetPrice,
        String idempotencyKey,
        String nativeTaskKey,
        String expectedVersionToken,
        UUID attemptId) {

    public PriceWriteRequest(Operation operation, UUID capabilityId, UUID credentialId,
                             String nativeListingKey, String nativeVariantKey, Money targetPrice,
                             String idempotencyKey, String nativeTaskKey, String expectedVersionToken) {
        this(operation,capabilityId,credentialId,nativeListingKey,nativeVariantKey,targetPrice,
                idempotencyKey,nativeTaskKey,expectedVersionToken,null);
    }

    public PriceWriteRequest(Operation operation, UUID capabilityId, UUID credentialId,
                             String nativeListingKey, String nativeVariantKey, Money targetPrice,
                             String idempotencyKey, String nativeTaskKey) {
        this(operation, capabilityId, credentialId, nativeListingKey, nativeVariantKey,
                targetPrice, idempotencyKey, nativeTaskKey, null);
    }

    public PriceWriteRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(targetPrice, "targetPrice");
    }

    /** Non-secret request intent, bound to the durable attempt before dispatch. */
    public String digest() {
        return com.mimococo.marketops.shared.Digest.ofComponents(java.util.Arrays.asList(
                operation.name(), capabilityId.toString(),
                credentialId == null ? null : credentialId.toString(), nativeListingKey,
                nativeVariantKey, targetPrice.amount().toPlainString(), targetPrice.currencyCode(),
                idempotencyKey, nativeTaskKey, expectedVersionToken, attemptId == null ? null : attemptId.toString()));
    }

    /** RESTORE is a different logical write; it must not replay the APPLY acknowledgement. */
    public static String operationIdempotencyKey(Operation operation, String commandKey) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(commandKey, "commandKey");
        return operation == Operation.RESTORE
                ? com.mimococo.marketops.shared.Digest.ofComponents(java.util.List.of(commandKey, "RESTORE"))
                : commandKey;
    }

    /** What one call is for. */
    public enum Operation {

        /** Set the price. */
        APPLY,

        /** Ask what became of asynchronous work. */
        STATUS_ENQUIRY,

        /** Observe what the platform now holds. */
        READBACK,

        /** Put the previous price back. */
        RESTORE
    }
}
