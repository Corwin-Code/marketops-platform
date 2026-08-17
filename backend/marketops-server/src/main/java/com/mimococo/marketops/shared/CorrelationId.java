package com.mimococo.marketops.shared;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation identifier that ties a request to every record it produces.
 *
 * <p>An inbound value is accepted only when it is short and drawn from a narrow
 * ASCII set. Anything else is replaced by a generated identifier, because a
 * caller-supplied string reaches both the logging context and a response header:
 * a control character there would split a header or forge a log line.
 *
 * <p>A rejected value is never echoed, logged, or returned. Only the reason it
 * was rejected is recorded, which is enough to diagnose a misbehaving client
 * without repeating hostile input.
 */
public final class CorrelationId {

    /** Longest accepted inbound value. */
    public static final int MAX_LENGTH = 64;

    /** Header carrying the identifier in both directions. */
    public static final String HEADER_NAME = "X-Correlation-ID";

    /**
     * Key under which the identifier appears in the logging context.
     *
     * <p>The constant is part of this module's published surface because other
     * modules read the current identifier when they build a response. Keeping it
     * beside the validation rules means a reader who wants to know what may
     * appear under this key finds the answer in the same class.
     */
    public static final String LOG_CONTEXT_KEY = "correlationId";

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private CorrelationId() {
    }

    /** Reason an inbound identifier was not used. */
    public enum RejectionReason {
        /** No identifier was supplied. */
        MISSING,
        /** The identifier exceeded {@link #MAX_LENGTH}. */
        TOO_LONG,
        /** The identifier contained a character outside the accepted set. */
        ILLEGAL_CHARACTER
    }

    /**
     * Outcome of validating an inbound identifier.
     *
     * @param value identifier to use for the request
     * @param rejectionReason why the inbound value was not used, or {@code null}
     */
    public record Result(String value, RejectionReason rejectionReason) {

        /** Whether the returned value came from the caller. */
        public boolean acceptedInbound() {
            return rejectionReason == null;
        }
    }

    /**
     * Return the identifier to use for a request.
     *
     * @param inbound raw header value, possibly {@code null}
     * @return the accepted inbound value, or a generated one with the reason the
     *         inbound value was rejected
     */
    public static Result validateOrGenerate(String inbound) {
        if (inbound == null || inbound.isEmpty()) {
            return new Result(generate(), RejectionReason.MISSING);
        }
        if (inbound.length() > MAX_LENGTH) {
            return new Result(generate(), RejectionReason.TOO_LONG);
        }
        if (!ACCEPTED.matcher(inbound).matches()) {
            return new Result(generate(), RejectionReason.ILLEGAL_CHARACTER);
        }
        return new Result(inbound, null);
    }

    /** Generate a new identifier. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Return the identifier of the request being handled on this thread.
     *
     * <p>A generated value is returned when no filter established one, so a caller
     * always receives an identifier it can quote when reporting a problem.
     */
    public static String current() {
        String established = org.slf4j.MDC.get(LOG_CONTEXT_KEY);
        return established != null ? established : generate();
    }
}
