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
                .containsExactly("VALIDATION_FAILED", "RESOURCE_NOT_FOUND", "INTERNAL_ERROR");
    }
}
