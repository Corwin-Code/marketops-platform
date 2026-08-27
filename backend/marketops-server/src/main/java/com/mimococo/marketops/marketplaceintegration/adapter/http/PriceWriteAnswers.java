package com.mimococo.marketops.marketplaceintegration.adapter.http;

import com.mimococo.marketops.marketplaceintegration.internal.domain.WriteOperationSpec;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteRequest;
import com.mimococo.marketops.marketplaceintegration.port.PriceWriteResult;
import com.mimococo.marketops.shared.CorrelationId;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * What a marketplace's answer means.
 *
 * <p>Separated from the transport that fetched it because this is the
 * consequential decision and it is a pure one: given a status, a body and the
 * recorded specification, the outcome is fixed. Keeping it here means it can be
 * exercised for every shape of answer a platform can give without a network,
 * and it keeps the adapter's remaining job — build a request, send it, hand the
 * answer here — small enough to read in one sitting.
 *
 * <p>The judgement that matters most is whether an unanswered call could have
 * changed a real price. For a write it could, so the outcome is unknown and the
 * command is never retried; for a read it could not, so the outcome is
 * retriable. Getting that backwards means either a price changed twice or a
 * readback nobody ever repeated.
 */
final class PriceWriteAnswers {

    private static final Logger log = LoggerFactory.getLogger(PriceWriteAnswers.class);

    private final Clock clock;

    PriceWriteAnswers(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Classify an answer that was not a success.
     *
     * <p>The distinction that matters is whether the call could have taken
     * effect. A rate-limit refusal is the one status where HTTP genuinely says
     * the request was not processed, so it is safely retriable for anything. A
     * timeout or a server failure says nothing of the kind: for a read that is
     * merely inconvenient and worth retrying, but for a write the request may
     * have reached the marketplace and changed a real price, so the honest
     * answer is that the outcome is unknown.
     *
     * <p>This is HTTP semantics rather than a marketplace fact, which is why it
     * can be decided here. What each platform does with a request it refuses is
     * recorded elsewhere; what an unanswered write means is universal.
     */
    PriceWriteResult inconclusive(PriceWriteRequest request, int status,
                                          String nativeStatus, byte[] body) {
        boolean mutating = request.operation() == PriceWriteRequest.Operation.APPLY
                || request.operation() == PriceWriteRequest.Operation.RESTORE;
        if (status == 429) {
            return new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR, nativeStatus,
                    null, null, null, body, clock.instant(), "platform_rate_limited");
        }
        if (status == 408 || status >= 500) {
            if (mutating) {
                return new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE,
                        nativeStatus, null, null, null, body, clock.instant(),
                        "platform_did_not_answer_a_write");
            }
            return new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR, nativeStatus,
                    null, null, null, body, clock.instant(), "platform_unavailable");
        }
        return new PriceWriteResult(PriceWriteResult.Outcome.REJECTED, nativeStatus, null, null,
                null, body, clock.instant(), "platform_rejected");
    }

    /**
     * What an apply answered.
     *
     * <p>An asynchronous platform answers with a handle, and the absence of that
     * handle where one is expected is an unknown state rather than a success:
     * the platform accepted something, but nothing here can later ask what
     * became of it.
     */
    PriceWriteResult applied(WriteOperationSpec spec, JsonNode document,
                                     String nativeStatus, byte[] body) {
        if (!spec.asynchronous()) {
            return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, nativeStatus, null,
                    null, null, body, clock.instant(), null);
        }
        String taskKey = text(document, spec.taskKeyPointer());
        if (taskKey == null) {
            return unknown("task_key_not_at_recorded_pointer");
        }
        return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, nativeStatus, taskKey,
                null, null, body, clock.instant(), null);
    }

    /**
     * What a status enquiry answered.
     *
     * <p>The platform's own words for finished and rejected are recorded values,
     * compared literally. Anything else means the work is still running, which
     * is a retriable condition rather than an outcome.
     */
    PriceWriteResult enquired(WriteOperationSpec spec, JsonNode document,
                                      String nativeStatus, byte[] body,
                                      PriceWriteRequest request) {
        String taskStatus = text(document, spec.taskStatusPointer());
        if (taskStatus == null) {
            return unknown("task_status_not_at_recorded_pointer");
        }
        if (taskStatus.equals(spec.taskSuccessValue())) {
            return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, taskStatus, null,
                    null, null, body, clock.instant(), null);
        }
        if (taskStatus.equals(spec.taskFailureValue())) {
            return new PriceWriteResult(PriceWriteResult.Outcome.REJECTED, taskStatus, null,
                    null, null, body, clock.instant(), "platform_task_rejected");
        }
        return new PriceWriteResult(PriceWriteResult.Outcome.RETRIABLE_ERROR, taskStatus,
                request.nativeTaskKey(), null, null, body, clock.instant(),
                "platform_task_in_progress");
    }

    /**
     * What a readback observed.
     *
     * <p>A response the recorded pointer does not reach carries no price. That is
     * reported as an unreadable observation rather than as a mismatch, because
     * "the platform holds something else" and "this product could not tell what
     * the platform holds" lead an operator to different actions.
     */
    PriceWriteResult observed(WriteOperationSpec spec, JsonNode document,
                                      String nativeStatus, byte[] body) {
        String price = text(document, spec.observedPricePointer());
        if (price == null) {
            return unknown("observed_price_not_at_recorded_pointer");
        }
        BigDecimal observed;
        try {
            observed = new BigDecimal(price);
        } catch (NumberFormatException notANumber) {
            return unknown("observed_price_not_a_number");
        }
        String currency = spec.observedCurrencyPointer() == null
                ? null : text(document, spec.observedCurrencyPointer());
        return new PriceWriteResult(PriceWriteResult.Outcome.ACCEPTED, nativeStatus, null,
                observed, currency, body, clock.instant(), null);
    }

    /**
     * Read one recorded pointer as text.
     *
     * <p>Numbers are accepted as well as strings, because a marketplace that
     * reports a price as a JSON number is not wrong, and refusing it would make
     * the pointer mechanism useless for exactly the field it exists for.
     */
    static String text(JsonNode document, String pointer) {
        if (pointer == null) {
            return null;
        }
        JsonNode node = document.at(pointer);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            String value = node.asString().trim();
            return value.isEmpty() ? null : value;
        }
        return node.isNumber() || node.isBoolean() ? node.asString() : null;
    }

    /**
     * Report an outcome nothing here can classify.
     *
     * <p>No body and no platform words travel outward: an answer this product
     * cannot read is not one it should quote.
     */
    PriceWriteResult unknown(String errorCode) {
        log.atWarn()
                .addKeyValue("event", "price_write_outcome_unknown")
                .addKeyValue("errorCode", errorCode)
                .addKeyValue("correlationId", CorrelationId.current())
                .log("A price write left an outcome that cannot be classified");
        return new PriceWriteResult(PriceWriteResult.Outcome.UNKNOWN_STATE, null, null, null,
                null, new byte[0], clock.instant(), errorCode);
    }
}
