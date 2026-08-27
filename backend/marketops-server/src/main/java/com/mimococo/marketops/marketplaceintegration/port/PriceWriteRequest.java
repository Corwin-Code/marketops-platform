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
        String nativeTaskKey) {

    public PriceWriteRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(targetPrice, "targetPrice");
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
