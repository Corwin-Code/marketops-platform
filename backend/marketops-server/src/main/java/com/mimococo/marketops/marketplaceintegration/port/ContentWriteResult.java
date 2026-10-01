package com.mimococo.marketops.marketplaceintegration.port;

import java.time.Instant;

/**
 * What the marketplace answered to one content call, classified but not concluded.
 *
 * @param outcome the classification
 * @param dispatched whether the request left this process; a refusal before any call has not
 * @param httpStatus the HTTP status, or {@code null} when no complete answer arrived
 * @param nativeTaskKey the platform task an accepted apply opened, or {@code null}
 * @param taskStatus the platform's own word for the task's state, or {@code null}
 * @param observedTitle the title a readback found, or {@code null}
 * @param observedDescription the description a readback found, or {@code null}
 * @param errorCode a stable code for a refusal, a failure or an unreadable answer, or {@code null}
 * @param detail what the platform said about a failure, bounded, or {@code null}
 * @param retryAfterSeconds how long the platform asked to wait, or {@code null}
 * @param body the answer as received, for custody; empty when nothing arrived
 * @param answeredAt when the answer was classified
 */
public record ContentWriteResult(
        Outcome outcome,
        boolean dispatched,
        Integer httpStatus,
        String nativeTaskKey,
        String taskStatus,
        String observedTitle,
        String observedDescription,
        String errorCode,
        String detail,
        Integer retryAfterSeconds,
        byte[] body,
        Instant answeredAt) {

    public ContentWriteResult {
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    /** How one answer is classified. */
    public enum Outcome {
        /** The apply was taken and a platform task opened for it. */
        ACCEPTED,
        /** The marketplace refused the request, or it was refused before any call; nothing changed. */
        REJECTED,
        /** Nothing changed and the same call may be made again later. */
        RETRIABLE_ERROR,
        /** The call may or may not have changed the card; only a readback can tell. */
        UNKNOWN_STATE,
        /** The platform task is still being processed. */
        TASK_PENDING,
        /** The platform task finished, or found nothing to change. */
        TASK_SUCCEEDED,
        /** The platform task finished with errors. */
        TASK_FAILED,
        /** A readback found the listing's title and description. */
        OBSERVED,
        /** A readback answered, but not with a title and a description where they were recorded. */
        UNREADABLE
    }
}
