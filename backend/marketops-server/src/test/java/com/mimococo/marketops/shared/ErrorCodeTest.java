package com.mimococo.marketops.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that every returnable message stays free of internal detail.
 *
 * <p>The messages are returned to an unauthenticated caller, so the assertion is
 * about what they must not contain rather than about their wording.
 */
class ErrorCodeTest {

    private static final List<String> DISCLOSING_TERMS = List.of(
            "sql", "jdbc", "postgres", "schema", "table", "column", "password",
            "user", "role", "flyway", "exception", "stack", "/", "\\", "127.0.0.1");

    @Test
    @DisplayName("every code carries a message that discloses nothing")
    void messagesDoNotDiscloseInternals() {
        for (ErrorCode code : ErrorCode.values()) {
            String message = code.safeMessage().toLowerCase(java.util.Locale.ROOT);
            assertThat(message).isNotBlank();
            for (String term : DISCLOSING_TERMS) {
                assertThat(message)
                        .as("message of %s must not mention %s", code, term)
                        .doesNotContain(term);
            }
        }
    }

    @Test
    @DisplayName("the code set is the published contract")
    void codeSetIsStable() {
        assertThat(ErrorCode.values())
                .extracting(Enum::name)
                .containsExactly(
                        "VALIDATION_FAILED", "RESOURCE_NOT_FOUND", "INTERNAL_ERROR",
                        "DUPLICATE_IDENTITY", "VERSION_CONFLICT", "INVALID_STATE_TRANSITION",
                        "EFFECTIVE_RANGE_OVERLAP", "CROSS_ORGANIZATION_REJECTED",
                        "REFERENCED_ENTITY_ACTIVE", "INVALID_TIMEZONE", "INVALID_CURRENCY",
                        "INVALID_COUNTRY", "SECRET_REFERENCE_INVALID",
                        "SECRET_MATERIAL_SUSPECTED", "SERVICE_ACCOUNT_INACTIVE",
                        "UNKNOWN_SCOPE", "CAPABILITY_VERIFICATION_NOT_SUPPORTED",
                        "PRODUCTION_WRITE_DISABLED", "MAINTENANCE_WRITE_DISABLED",
                        "OPERATOR_ATTRIBUTION_MISSING", "CURRENCY_MISMATCH",
                        "AUTHENTICATION_REQUIRED", "MULTI_FACTOR_REQUIRED",
                        "STEP_UP_REQUIRED", "USER_NOT_PROVISIONED", "USER_INACTIVE",
                        "ACTION_NOT_PERMITTED", "RESOURCE_SCOPE_DENIED",
                        "IDENTITY_PROVIDER_NOT_ACCEPTED", "MAPPING_UNRESOLVED",
                        "MAPPING_CONFLICT_OPEN", "IMPORT_SCHEMA_PROFILE_MISSING",
                        "IMPORT_DUPLICATE_CONTENT", "IMPORT_VALIDATION_FAILED",
                        "IMPORT_TOO_LARGE", "EXPORT_QUEUE_FULL", "EXPORT_UNAVAILABLE",
                        "EXPORT_INTEGRITY_FAILED", "METRIC_INPUT_UNAVAILABLE",
                        "AI_PROVIDER_UNAVAILABLE", "AI_OUTPUT_REJECTED",
                        "AI_PROJECTION_FIELD_NOT_ALLOWED", "POLICY_NOT_CONFIGURED",
                        "GUARDRAIL_BLOCKED", "RECOMMENDATION_STALE", "APPROVAL_REQUIRED",
                        "COMMAND_STATE_INVALID", "WRITE_GATE_CLOSED", "READBACK_REQUIRED",
                        "COMPENSATION_UNSAFE", "POLICY_AUTHORIZATION_UNUSABLE",
                        "CAPABILITY_NOT_USABLE", "OBJECT_STORAGE_VERIFICATION_FAILED",
                        "RAW_EVIDENCE_MISSING");
    }
}
