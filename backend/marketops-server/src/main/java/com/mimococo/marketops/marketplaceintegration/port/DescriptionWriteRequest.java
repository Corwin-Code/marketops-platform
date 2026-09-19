package com.mimococo.marketops.marketplaceintegration.port;

import com.mimococo.marketops.shared.Digest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One call about one listing's description, fully specified before it is made.
 *
 * <p>The text is the exact target Russian full text on an apply, the exact
 * captured prior text on a restore and absent otherwise. The attribute key names
 * the one attribute the write may touch, and comes from the verified registry
 * rather than from the caller.
 */
public record DescriptionWriteRequest(
        Operation operation,
        UUID capabilityId,
        UUID credentialId,
        String nativeListingKey,
        String nativeVariantKey,
        String descriptionText,
        String descriptionAttributeKey,
        boolean kizMarkedDeclared,
        String idempotencyKey,
        String nativeTaskKey,
        String expectedVersionToken,
        UUID attemptId) {

    public enum Operation {
        APPLY,
        STATUS_ENQUIRY,
        READBACK,
        RESTORE
    }

    public DescriptionWriteRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(nativeListingKey, "nativeListingKey");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(attemptId, "attemptId");
        if (operation == Operation.APPLY || operation == Operation.RESTORE) {
            Objects.requireNonNull(descriptionText, "descriptionText");
        }
    }

    /** The identity of exactly this call, bound into the attempt before it is made. */
    public String digest() {
        return Digest.ofComponents(Arrays.asList(
                operation.name(),
                String.valueOf(capabilityId),
                String.valueOf(credentialId),
                nativeListingKey,
                nativeVariantKey,
                descriptionText == null ? null : Digest.ofText(descriptionText),
                descriptionAttributeKey,
                Boolean.toString(kizMarkedDeclared),
                idempotencyKey,
                nativeTaskKey,
                expectedVersionToken,
                String.valueOf(attemptId)));
    }

    public static String operationIdempotencyKey(Operation operation, String commandKey) {
        return operation == Operation.RESTORE
                ? Digest.ofComponents(List.of(commandKey, "RESTORE"))
                : commandKey;
    }
}
