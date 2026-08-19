package com.mimococo.marketops.shared;

/**
 * Stable error codes safe to return to a caller.
 *
 * <p>Each constant carries a short message that reveals nothing about the
 * database, the filesystem, or the configuration. Diagnostic detail stays in the
 * server log, correlated by the identifier returned alongside the code.
 *
 * <p>This enum is the single registry of refusal semantics for the metadata
 * maintenance boundary: a rejected input never echoes the input, a conflict
 * names at most the identifier of the resource it collided with, and a refusal
 * of secret-like material names neither the value nor its shape.
 */
public enum ErrorCode {

    /** The request body or parameters failed validation. */
    VALIDATION_FAILED("The request could not be processed as submitted."),

    /** The requested resource does not exist. */
    RESOURCE_NOT_FOUND("The requested resource does not exist."),

    /** An unexpected condition prevented the request from completing. */
    INTERNAL_ERROR("The request could not be completed."),

    /** A business code, native key or reference collides with a live resource. */
    DUPLICATE_IDENTITY("A resource with the same identity already exists."),

    /** The supplied expected version no longer matches the stored resource. */
    VERSION_CONFLICT("The resource changed since it was read."),

    /** The requested lifecycle transition is not allowed from the current state. */
    INVALID_STATE_TRANSITION("The requested state transition is not allowed."),

    /** The validity interval overlaps an active interval of the same scope. */
    EFFECTIVE_RANGE_OVERLAP("The validity interval overlaps an existing one."),

    /** The referenced resources belong to different organizations or accounts. */
    CROSS_ORGANIZATION_REJECTED("The referenced resources belong to different owners."),

    /** The resource still has live children or live references and cannot retire. */
    REFERENCED_ENTITY_ACTIVE("The resource is still referenced by active resources."),

    /** The timezone identifier is not a known IANA zone. */
    INVALID_TIMEZONE("The timezone identifier is not recognized."),

    /** The currency code is not a known ISO 4217 code. */
    INVALID_CURRENCY("The currency code is not recognized."),

    /** The country code is not a known ISO 3166-1 alpha-2 code. */
    INVALID_COUNTRY("The country code is not recognized."),

    /** The secret reference is not a well-formed opaque reference name. */
    SECRET_REFERENCE_INVALID("The secret reference is not a well-formed reference."),

    /** A writable text value appears to carry secret material. */
    SECRET_MATERIAL_SUSPECTED("The submitted text was refused by the secret-material guard."),

    /** The service account is expired, disabled, revoked or unknown. */
    SERVICE_ACCOUNT_INACTIVE("The service account is not active."),

    /** The authorization scope type or resource cannot be resolved. */
    UNKNOWN_SCOPE("The authorization scope could not be resolved."),

    /** No verification path to VERIFIED exists for this capability metadata. */
    CAPABILITY_VERIFICATION_NOT_SUPPORTED(
            "Capability verification is not available for this metadata."),

    /** Production writes are globally disabled and cannot be represented as enabled. */
    PRODUCTION_WRITE_DISABLED("Production writes are disabled."),

    /** This environment does not accept metadata maintenance writes. */
    MAINTENANCE_WRITE_DISABLED("Metadata maintenance writes are disabled here."),

    /** The mutation carries no valid operator attribution. */
    OPERATOR_ATTRIBUTION_MISSING("The request carries no valid operator attribution.");

    private final String safeMessage;

    ErrorCode(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    /** Message suitable for returning to an unauthenticated caller. */
    public String safeMessage() {
        return safeMessage;
    }
}
