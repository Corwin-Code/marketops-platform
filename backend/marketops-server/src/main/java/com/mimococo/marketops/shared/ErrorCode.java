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
    OPERATOR_ATTRIBUTION_MISSING("The request carries no valid operator attribution."),

    /** Two monetary amounts in different currencies were combined. */
    CURRENCY_MISMATCH("Amounts in different currencies cannot be combined."),

    /** The request carried no accepted identity. */
    AUTHENTICATION_REQUIRED("Authentication is required."),

    /** The presented identity did not prove a second authentication factor. */
    MULTI_FACTOR_REQUIRED("Multi-factor authentication is required."),

    /** The action requires a more recent authentication than the one presented. */
    STEP_UP_REQUIRED("This action requires a recent re-authentication."),

    /** The external subject has no MarketOps profile. */
    USER_NOT_PROVISIONED("This identity has no profile in MarketOps."),

    /** The MarketOps profile is suspended, disabled or superseded. */
    USER_INACTIVE("This profile is not active."),

    /** No live role grants the requested action. */
    ACTION_NOT_PERMITTED("This action is not permitted for this profile."),

    /** No live grant covers the requested resource. */
    RESOURCE_SCOPE_DENIED("This resource is outside the profile's scope."),

    /** The token issuer is not a registered, active identity provider. */
    IDENTITY_PROVIDER_NOT_ACCEPTED("The identity provider is not accepted here."),

    /** The listing variant has no confirmed mapping to an internal variant. */
    MAPPING_UNRESOLVED("The listing has no confirmed internal mapping."),

    /** The listing variant has an unresolved mapping conflict. */
    MAPPING_CONFLICT_OPEN("The listing has an unresolved mapping conflict."),

    /** No active schema profile is registered for this dataset. */
    IMPORT_SCHEMA_PROFILE_MISSING("No file contract is registered for this dataset."),

    /** The same file content is already present in a live batch. */
    IMPORT_DUPLICATE_CONTENT("This file has already been submitted."),

    /** The submitted file did not satisfy its registered contract. */
    IMPORT_VALIDATION_FAILED("The file did not satisfy its registered contract."),

    /** The submitted file is larger than the intake boundary accepts. */
    IMPORT_TOO_LARGE("The file is larger than this intake accepts."),

    /** The bounded asynchronous export queue has no capacity for another job. */
    EXPORT_QUEUE_FULL("The export queue is full. Try again later."),

    /** The export is unfinished, failed or expired and cannot be downloaded. */
    EXPORT_UNAVAILABLE("This export is not available for download."),

    /** The export bytes no longer match the immutable snapshot manifest. */
    EXPORT_INTEGRITY_FAILED("The export failed its integrity check."),

    /** A required canonical metric input could not be resolved. */
    METRIC_INPUT_UNAVAILABLE("A required input for this metric is unavailable."),

    /** No eligible model provider is available. */
    AI_PROVIDER_UNAVAILABLE("No eligible model provider is available."),

    /** The model output failed deterministic validation. */
    AI_OUTPUT_REJECTED("The model output was rejected by validation."),

    /** A projection carried a field the allowlist does not contain. */
    AI_PROJECTION_FIELD_NOT_ALLOWED("The projection carried a field that is not allowed."),

    /** No commercial policy is in force for this scope and instant. */
    POLICY_NOT_CONFIGURED("No commercial policy is in force for this scope."),

    /** Deterministic guardrails refused the proposed action. */
    GUARDRAIL_BLOCKED("The proposed action was refused by commercial guardrails."),

    /** The recommendation expired or no longer matches current entity versions. */
    RECOMMENDATION_STALE("The recommendation is no longer current."),

    /** The action requires an approval or a matching policy authorization. */
    APPROVAL_REQUIRED("This action requires an approval."),

    /** The requested command transition is not in the reviewed transition set. */
    COMMAND_STATE_INVALID("The command cannot move to the requested state."),

    /** One or more write-gate conditions are closed. */
    WRITE_GATE_CLOSED("Platform writes are not permitted for this command."),

    /** Success was claimed without a readback that observed the intended value. */
    READBACK_REQUIRED("Success requires a matching readback."),

    /** The current platform value no longer matches what this command wrote. */
    COMPENSATION_UNSAFE("Restoring would overwrite a later change."),

    /** The bounded authorization has no remaining uses or is out of scope. */
    POLICY_AUTHORIZATION_UNUSABLE("The policy authorization cannot be used here."),

    /** The capability is unverified, unavailable or switched off for this subject. */
    CAPABILITY_NOT_USABLE("The capability is not usable for this subject."),

    /** Stored bytes did not read back with the digest they were written under. */
    OBJECT_STORAGE_VERIFICATION_FAILED("Stored content failed read-back verification."),

    /** A fact was offered without the source evidence it must be derived from. */
    RAW_EVIDENCE_MISSING("The fact carries no source evidence.");

    private final String safeMessage;

    ErrorCode(String safeMessage) {
        this.safeMessage = safeMessage;
    }

    /** Message suitable for returning to an unauthenticated caller. */
    public String safeMessage() {
        return safeMessage;
    }
}
