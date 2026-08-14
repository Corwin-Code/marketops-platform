package com.mimococo.marketops.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

/**
 * Establishes what an inbound correlation identifier may contain.
 *
 * <p>The value reaches a response header and every log record of the request, so
 * the cases below are the ones that would let a caller split a header or write a
 * line of its own choosing into the log.
 */
class CorrelationIdTest {

    private static final Pattern GENERATED =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @AfterEach
    void clearLoggingContext() {
        MDC.remove(CorrelationId.LOG_CONTEXT_KEY);
    }

    @Test
    @DisplayName("a well-formed inbound value is used unchanged")
    void wellFormedValueIsAccepted() {
        CorrelationId.Result result = CorrelationId.validateOrGenerate("req-2026.08.14:0001");

        assertThat(result.acceptedInbound()).isTrue();
        assertThat(result.rejectionReason()).isNull();
        assertThat(result.value()).isEqualTo("req-2026.08.14:0001");
    }

    @Test
    @DisplayName("a value of exactly the maximum length is accepted")
    void maximumLengthIsInclusive() {
        String value = "a".repeat(CorrelationId.MAX_LENGTH);

        CorrelationId.Result result = CorrelationId.validateOrGenerate(value);

        assertThat(result.acceptedInbound()).isTrue();
        assertThat(result.value()).isEqualTo(value);
    }

    @Test
    @DisplayName("one character beyond the maximum length is replaced")
    void oneCharacterTooLongIsRejected() {
        CorrelationId.Result result =
                CorrelationId.validateOrGenerate("a".repeat(CorrelationId.MAX_LENGTH + 1));

        assertThat(result.acceptedInbound()).isFalse();
        assertThat(result.rejectionReason()).isEqualTo(CorrelationId.RejectionReason.TOO_LONG);
        assertThat(result.value()).matches(GENERATED);
    }

    @Test
    @DisplayName("a missing value produces a generated identifier")
    void missingValueIsReplaced() {
        for (String inbound : new String[] {null, ""}) {
            CorrelationId.Result result = CorrelationId.validateOrGenerate(inbound);

            assertThat(result.rejectionReason()).isEqualTo(CorrelationId.RejectionReason.MISSING);
            assertThat(result.value()).matches(GENERATED);
        }
    }

    @ParameterizedTest(name = "rejects [{0}]")
    @ValueSource(strings = {
            "value\r\nX-Injected: 1",
            "value\nSet-Cookie: session=1",
            "value with space",
            "value;drop",
            "value/slash",
            "value\u0000null",
            "identifier-\u0429",
            "<script>",
            "%0d%0aX-Injected:%201",
            "\u0009tab",
            "trailing "
    })
    @DisplayName("a value outside the accepted alphabet is replaced")
    void hostileValueIsReplaced(String inbound) {
        CorrelationId.Result result = CorrelationId.validateOrGenerate(inbound);

        assertThat(result.acceptedInbound()).isFalse();
        assertThat(result.rejectionReason())
                .isEqualTo(CorrelationId.RejectionReason.ILLEGAL_CHARACTER);
        assertThat(result.value()).matches(GENERATED).isNotEqualTo(inbound);
    }

    @Test
    @DisplayName("the rejected value never appears in the outcome")
    void rejectedValueIsNotCarried() {
        String hostile = "bad value\r\nInjected: yes";

        CorrelationId.Result result = CorrelationId.validateOrGenerate(hostile);

        assertThat(result.value()).doesNotContain("Injected").doesNotContain("bad value");
    }

    @Test
    @DisplayName("generated identifiers do not repeat")
    void generatedValuesAreDistinct() {
        Set<String> generated = new HashSet<>();
        for (int index = 0; index < 500; index++) {
            generated.add(CorrelationId.generate());
        }

        assertThat(generated).hasSize(500);
        assertThat(generated).allMatch(value -> GENERATED.matcher(value).matches());
    }

    @Test
    @DisplayName("a generated identifier is itself acceptable as an inbound value")
    void generatedValueSurvivesValidation() {
        CorrelationId.Result result = CorrelationId.validateOrGenerate(CorrelationId.generate());

        assertThat(result.acceptedInbound()).isTrue();
    }

    @Test
    @DisplayName("the current identifier is the one the request established")
    void currentReturnsTheEstablishedValue() {
        MDC.put(CorrelationId.LOG_CONTEXT_KEY, "established-value");

        assertThat(CorrelationId.current()).isEqualTo("established-value");
    }

    @Test
    @DisplayName("a caller outside a request still receives an identifier")
    void currentGeneratesWhenNoneEstablished() {
        assertThat(CorrelationId.current()).matches(GENERATED);
    }
}
