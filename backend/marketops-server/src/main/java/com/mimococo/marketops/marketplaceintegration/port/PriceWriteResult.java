package com.mimococo.marketops.marketplaceintegration.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * What a platform answered, classified but not interpreted.
 *
 * <p>The classification is about the call, never about the command. An
 * {@code ACCEPTED} answer says the platform took the request, which is not the
 * same as the platform holding the intended value — only a readback says that,
 * and only the command's own transition rules may draw the conclusion.
 *
 * <p>{@code UNKNOWN_STATE} is the honest answer to a timeout. The call may have
 * reached the marketplace and may have changed the price. Collapsing it into a
 * failure would authorize a retry that writes twice.
 *
 * @param outcome how the answer classifies
 * @param nativeStatus the platform's own words, or {@code null}
 * @param nativeTaskKey the platform's handle for asynchronous work, or {@code null}
 * @param observedPrice the price a readback observed, or {@code null}
 * @param observedCurrency currency a readback observed, or {@code null}
 * @param body the answer's bytes, kept as evidence
 * @param completedAt when the answer arrived
 * @param errorCode why the call did not succeed, or {@code null}
 */
public record PriceWriteResult(
        Outcome outcome,
        String nativeStatus,
        String nativeTaskKey,
        BigDecimal observedPrice,
        String observedCurrency,
        byte[] body,
        Instant completedAt,
        String errorCode) {

    public PriceWriteResult {
        Objects.requireNonNull(outcome, "outcome");
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** How one answer classifies. */
    public enum Outcome {

        /** The platform took the request, or reported the value. */
        ACCEPTED,

        /** The platform refused permanently. */
        REJECTED,

        /** A retriable transport or rate-limit condition occurred. */
        RETRIABLE_ERROR,

        /** The call timed out; whether it took effect is unknown. */
        TIMEOUT,

        /** The answer cannot be classified. */
        UNKNOWN_STATE
    }

    /** Whether the platform reported the asynchronous work as finished. */
    public boolean taskFinished() {
        return outcome == Outcome.ACCEPTED && nativeTaskKey == null;
    }
}
