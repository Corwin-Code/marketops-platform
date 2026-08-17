package com.mimococo.marketops.shared;

/**
 * Stable error codes safe to return to a caller.
 *
 * <p>Each constant carries a short message that reveals nothing about the
 * database, the filesystem, or the configuration. Diagnostic detail stays in the
 * server log, correlated by the identifier returned alongside the code.
 */
public enum ErrorCode {

    /** The request body or parameters failed validation. */
    VALIDATION_FAILED("The request could not be processed as submitted."),

    /** The requested resource does not exist. */
    RESOURCE_NOT_FOUND("The requested resource does not exist."),

    /** An unexpected condition prevented the request from completing. */
    INTERNAL_ERROR("The request could not be completed.");

    private final String safeMessage;

    ErrorCode(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    /** Message suitable for returning to an unauthenticated caller. */
    public String safeMessage() {
        return safeMessage;
    }
}
