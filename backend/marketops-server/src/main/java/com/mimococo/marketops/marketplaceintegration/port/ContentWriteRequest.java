package com.mimococo.marketops.marketplaceintegration.port;

import java.util.Objects;
import java.util.UUID;

/**
 * One call a content command makes.
 *
 * @param operation which recorded operation to perform
 * @param capabilityId the verified content capability
 * @param credentialId the content-write credential, or {@code null} when none resolves (refused)
 * @param offerKey the seller article the marketplace addresses the listing by
 * @param titleText the title to write; only an apply places it
 * @param descriptionText the description to write; only an apply places it
 * @param nativeTaskKey the platform task an enquiry asks about, or {@code null}
 * @param commandId the command the call belongs to, for the record
 */
public record ContentWriteRequest(
        Operation operation,
        UUID capabilityId,
        UUID credentialId,
        String offerKey,
        String titleText,
        String descriptionText,
        String nativeTaskKey,
        UUID commandId) {

    public ContentWriteRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(offerKey, "offerKey");
        Objects.requireNonNull(commandId, "commandId");
    }

    /** The recorded operations of a content capability. */
    public enum Operation {
        /** Write the title and the description together. */
        APPLY,
        /** Ask what became of the platform task the apply opened. */
        STATUS_ENQUIRY,
        /** Read the listing's title and description as the marketplace holds them. */
        READBACK
    }

    /** Whether this call could change the card. */
    public boolean mutating() {
        return operation == Operation.APPLY;
    }
}
