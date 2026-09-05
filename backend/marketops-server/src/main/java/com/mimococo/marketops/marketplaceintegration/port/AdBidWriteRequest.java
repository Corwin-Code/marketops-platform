package com.mimococo.marketops.marketplaceintegration.port;

import com.mimococo.marketops.shared.Digest;
import com.mimococo.marketops.shared.Money;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One call an adapter is being asked to make on behalf of one bid command.
 *
 * <p>The target is a {@link Money} plus a unit code, and both travel because a
 * bid of twenty-five means two different things depending on whether the
 * marketplace counts roubles or kopecks. Sending the number without the unit is
 * the kind of error that is silent in both directions and expensive in one.
 *
 * <p>The credential is an identifier, never a secret. Resolution happens inside
 * the adapter, at the moment of use, and nothing that leaves the adapter carries
 * the resolved value.
 *
 * <p>{@link #digest()} is what the attempt row records before the call is made
 * and what the completion re-checks afterwards. A response that cannot be tied
 * back to the exact request that produced it is not evidence about that request.
 */
public record AdBidWriteRequest(
        Operation operation,
        UUID capabilityId,
        UUID credentialId,
        String nativeCampaignKey,
        String nativeObjectKey,
        Money targetBid,
        String bidUnitCode,
        String idempotencyKey,
        String nativeTaskKey,
        String expectedVersionToken,
        UUID attemptId) {

    /** What the adapter is being asked to do. */
    public enum Operation {

        /** Set the bid to the approved target. Happens at most once per command. */
        APPLY,

        /** Ask the platform whether asynchronous work it accepted has finished. */
        STATUS_ENQUIRY,

        /** Observe the current bid, which is the only route to success. */
        READBACK,

        /** Restore the captured prior bid. Happens at most once per command. */
        RESTORE
    }

    public AdBidWriteRequest {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(capabilityId, "capabilityId");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(nativeObjectKey, "nativeObjectKey");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(attemptId, "attemptId");
        if (operation != Operation.STATUS_ENQUIRY) {
            Objects.requireNonNull(targetBid, "targetBid");
            Objects.requireNonNull(bidUnitCode, "bidUnitCode");
        }
    }

    /**
     * A stable fingerprint of exactly this call.
     *
     * <p>The component list admits nulls, because several components are
     * legitimately absent and {@link Digest} distinguishes an absent component
     * from an empty one. An immutable list would reject them instead.
     */
    public String digest() {
        return Digest.ofComponents(Arrays.asList(
                operation.name(),
                String.valueOf(capabilityId),
                String.valueOf(credentialId),
                nativeCampaignKey,
                nativeObjectKey,
                targetBid == null ? null : targetBid.amount().toPlainString(),
                targetBid == null ? null : targetBid.currencyCode(),
                bidUnitCode,
                idempotencyKey,
                nativeTaskKey,
                expectedVersionToken,
                String.valueOf(attemptId)));
    }

    /**
     * The provider identity for one operation of one command.
     *
     * <p>A restore uses a different key from the apply it undoes, so a provider
     * that de-duplicates on the key cannot answer a restore with the
     * acknowledgement it already gave the apply.
     */
    public static String operationIdempotencyKey(Operation operation, String commandKey) {
        return operation == Operation.RESTORE
                ? Digest.ofComponents(List.of(commandKey, "RESTORE"))
                : commandKey;
    }
}
