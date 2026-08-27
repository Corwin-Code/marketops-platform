package com.mimococo.marketops.marketplaceintegration.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.mimococo.marketops.marketplaceintegration.internal.domain.EndpointCallSpec;
import com.mimococo.marketops.marketplaceintegration.port.AcquisitionResult;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

class AcquisitionPageWorkerTest {
    private final AcquisitionPageWorker worker = new AcquisitionPageWorker(null, null, null,
            null, null, JsonMapper.builder().build(), null, mock(PlatformTransactionManager.class));

    @ParameterizedTest
    @MethodSource("paginationResponses")
    void onlyAnExplicitTerminalValueCanEndAPagedSource(String body, String expected) {
        var outcome = worker.continuationToken(response(body), spec("CURSOR", "/next"));
        assertThat(outcome.kind().name()).isEqualTo(expected);
        if (!expected.equals("NEXT")) assertThat(outcome.token()).isNull();
    }

    static Stream<Arguments> paginationResponses() {
        return Stream.of(Arguments.of("{\"next\":null}", "END"),
                Arguments.of("{\"next\":\"page-2\"}", "NEXT"),
                Arguments.of("{}", "SCHEMA_DRIFT"), Arguments.of("{\"next\":false}", "SCHEMA_DRIFT"),
                Arguments.of("{\"next\":{}}", "SCHEMA_DRIFT"), Arguments.of("{\"next\":\"\"}", "SCHEMA_DRIFT"),
                Arguments.of("not json", "UNREADABLE"), Arguments.of("null", "UNREADABLE"),
                Arguments.of("", "UNREADABLE"));
    }

    @Test
    void anUndeclaredPaginationContractCannotBecomeSinglePageSuccess() {
        assertThat(worker.continuationToken(response("{}"), spec("UNKNOWN", null)).kind().name()).isEqualTo("CONFIG_INVALID");
        assertThat(worker.continuationToken(response("{}"), spec("CURSOR", null)).kind().name()).isEqualTo("CONFIG_INVALID");
        assertThat(worker.continuationToken(response("{}"), spec("NONE", null)).kind().name()).isEqualTo("END");
        var failure = new AcquisitionResult("{}".getBytes(StandardCharsets.UTF_8), "HTTP 400",
                AcquisitionResult.AcquisitionOutcome.BUSINESS_FAILURE_BYTES, null);
        assertThat(worker.continuationToken(failure, spec("NONE", null)).kind().name()).isEqualTo("UNKNOWN_RESULT");
    }

    private static AcquisitionResult response(String body) {
        return new AcquisitionResult(body.getBytes(StandardCharsets.UTF_8), "HTTP 200",
                AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES, null);
    }

    @ParameterizedTest
    @MethodSource("typedPaginationResponses")
    void numericAndStringContinuationTokensRemainExactAndBounded(String model, String body,
            String expectedKind, String expectedToken) {
        var outcome=worker.continuationToken(response(body),spec(model,"/next"));
        assertThat(outcome.kind().name()).isEqualTo(expectedKind);
        assertThat(outcome.token()).isEqualTo(expectedToken);
    }

    static Stream<Arguments> typedPaginationResponses() {
        return Stream.of(
                Arguments.of("OFFSET","{\"next\":0}","NEXT","0"),
                Arguments.of("PAGE","{\"next\":1}","NEXT","1"),
                Arguments.of("OFFSET","{\"next\":9223372036854775807}","NEXT","9223372036854775807"),
                Arguments.of("OFFSET","{\"next\":-1}","SCHEMA_DRIFT",null),
                Arguments.of("PAGE","{\"next\":0}","SCHEMA_DRIFT",null),
                Arguments.of("PAGE","{\"next\":1.5}","SCHEMA_DRIFT",null),
                Arguments.of("OFFSET","{\"next\":9223372036854775808}","SCHEMA_DRIFT",null),
                Arguments.of("PAGE","{\"next\":\"1\"}","SCHEMA_DRIFT",null),
                Arguments.of("DATE_WINDOW","{\"next\":\"window-2\"}","NEXT","window-2"),
                Arguments.of("CURSOR","{\"next\":\" \"}","SCHEMA_DRIFT",null),
                Arguments.of("CURSOR","{\"next\":\"a\\u0001b\"}","SCHEMA_DRIFT",null),
                Arguments.of("CURSOR","{\"next\":\""+"x".repeat(2048)+"\"}","NEXT","x".repeat(2048)),
                Arguments.of("CURSOR","{\"next\":\""+"x".repeat(2049)+"\"}","SCHEMA_DRIFT",null),
                Arguments.of("NONE","[]","END",null),
                Arguments.of("NONE","true","UNREADABLE",null),
                Arguments.of("NONE","123","UNREADABLE",null),
                Arguments.of("CURSOR","{\"next\":null,\"next\":\"other\"}","UNREADABLE",null));
    }

    @ParameterizedTest
    @MethodSource("incompleteAndFailureResponses")
    void partialOrUnexpectedResponsesNeverAdvanceTheCursor(boolean complete, boolean retryable,
            String failureCode, AcquisitionResult.AcquisitionOutcome classification, String expected) {
        var result=new AcquisitionResult("{\"next\":null}".getBytes(StandardCharsets.UTF_8),"synthetic-status",
                classification,null,complete,failureCode,retryable,null,null);
        assertThat(worker.continuationToken(result,spec("CURSOR","/next")).kind().name()).isEqualTo(expected);
    }

    static Stream<Arguments> incompleteAndFailureResponses() {
        return Stream.of(
                Arguments.of(true,false,"UNEXPECTED_CONTENT_TYPE",AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES,"SCHEMA_DRIFT"),
                Arguments.of(false,true,"UNEXPECTED_CONTENT_TYPE",AcquisitionResult.AcquisitionOutcome.BUSINESS_FAILURE_BYTES,"RETRY_LATER"),
                Arguments.of(false,false,"BODY_INCOMPLETE",AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES,"UNKNOWN_RESULT"),
                Arguments.of(false,true,"BODY_INCOMPLETE",AcquisitionResult.AcquisitionOutcome.SUCCESS_BYTES,"RETRY_LATER"),
                Arguments.of(true,true,null,AcquisitionResult.AcquisitionOutcome.BUSINESS_FAILURE_BYTES,"RETRY_LATER"));
    }

    @Test
    void relativeContinuationPointersAreNotExecutableConfiguration() {
        assertThat(worker.continuationToken(response("{}"),spec("CURSOR","next")).kind().name()).isEqualTo("CONFIG_INVALID");
    }

    private static EndpointCallSpec spec(String pagination, String pointer) {
        return new EndpointCallSpec(UUID.randomUUID(), "FIXTURE", "page", "https://fixture.invalid", "GET",
                "/page", null, null, "application/json", pointer, pagination, 10, 1000, 4096);
    }
}
