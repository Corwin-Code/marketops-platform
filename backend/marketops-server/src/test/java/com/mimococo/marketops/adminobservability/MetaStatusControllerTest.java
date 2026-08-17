package com.mimococo.marketops.adminobservability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mimococo.marketops.adminobservability.internal.MetaStatusAssembler;
import com.mimococo.marketops.adminobservability.internal.MetaStatusResponse;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.internal.correlation.CorrelationIdFilter;
import com.mimococo.marketops.shared.internal.errors.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Exercises the metadata resource with the correlation filter and the failure
 * handler in place, which is the arrangement a caller actually meets.
 *
 * <p>The assembler is replaced by a stub so the cases describe the resource's
 * own behaviour: what it publishes, what it withholds, and what it does when the
 * layer beneath it fails.
 */
class MetaStatusControllerTest {

    private static final String PATH = "/api/v1/meta/status";

    private MetaStatusAssembler assembler;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        assembler = mock(MetaStatusAssembler.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MetaStatusController(assembler))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @AfterEach
    void clearLoggingContext() {
        MDC.remove(CorrelationId.LOG_CONTEXT_KEY);
    }

    @Test
    @DisplayName("the resource publishes exactly the agreed field set")
    void fieldSetIsAnAllowlist() throws Exception {
        when(assembler.assemble()).thenReturn(sample());

        MvcResult result = mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

        Object decoded = JsonPath.parse(result.getResponse().getContentAsString()).read("$");
        assertThat(decoded).isInstanceOf(Map.class);
        Map<?, ?> payload = (Map<?, ?>) decoded;
        List<String> fieldNames = payload.keySet().stream().map(String::valueOf).toList();
        assertThat(fieldNames).containsExactlyInAnyOrder(
                "product", "application", "environment", "buildVersion", "gitCommit",
                "serverTimeUtc", "database", "migration", "correlationId");
    }

    @Test
    @DisplayName("the payload never carries a connection detail")
    void payloadCarriesNoConnectionDetail() throws Exception {
        when(assembler.assemble()).thenReturn(sample());

        String body = mockMvc.perform(get(PATH)).andReturn().getResponse().getContentAsString();

        assertThat(body.toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("jdbc:")
                .doesNotContain("password")
                .doesNotContain("search_path")
                .doesNotContain("marketops_app")
                .doesNotContain("marketops_migration");
    }

    @Test
    @DisplayName("the assembled values reach the response")
    void assembledValuesAreReturned() throws Exception {
        when(assembler.assemble()).thenReturn(sample());

        mockMvc.perform(get(PATH))
                .andExpect(jsonPath("$.product").value("MarketOps Russia"))
                .andExpect(jsonPath("$.application").value("marketops-server"))
                .andExpect(jsonPath("$.database.status").value("UP"))
                .andExpect(jsonPath("$.migration.currentVersion").value("1"));
    }

    @Test
    @DisplayName("a well-formed inbound identifier is echoed and reaches the handler")
    void inboundIdentifierIsEstablishedForTheRequest() throws Exception {
        when(assembler.assemble())
                .thenAnswer(invocation -> sampleWithCorrelationId(CorrelationId.current()));

        mockMvc.perform(get(PATH).header(CorrelationId.HEADER_NAME, "req-0001"))
                .andExpect(header().string(CorrelationId.HEADER_NAME, "req-0001"))
                .andExpect(jsonPath("$.correlationId").value("req-0001"));
    }

    @Test
    @DisplayName("a hostile inbound identifier is neither echoed nor published")
    void hostileIdentifierIsReplaced() throws Exception {
        when(assembler.assemble())
                .thenAnswer(invocation -> sampleWithCorrelationId(CorrelationId.current()));
        String hostile = "abc\u0009def";

        MvcResult result = mockMvc.perform(get(PATH).header(CorrelationId.HEADER_NAME, hostile))
                .andExpect(status().isOk())
                .andReturn();

        String echoed = result.getResponse().getHeader(CorrelationId.HEADER_NAME);
        assertThat(echoed).isNotNull().isNotEqualTo(hostile);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(hostile);
    }

    @Test
    @DisplayName("the logging context is empty once the response is written")
    void loggingContextIsClearedAfterTheRequest() throws Exception {
        when(assembler.assemble()).thenReturn(sample());

        mockMvc.perform(get(PATH).header(CorrelationId.HEADER_NAME, "req-0002"));

        assertThat(MDC.get(CorrelationId.LOG_CONTEXT_KEY)).isNull();
    }

    @Test
    @DisplayName("the logging context is cleared even when the request fails")
    void loggingContextIsClearedAfterAFailure() throws Exception {
        when(assembler.assemble()).thenThrow(new IllegalStateException("assembly failed"));

        mockMvc.perform(get(PATH).header(CorrelationId.HEADER_NAME, "req-0003"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value(ErrorCode.INTERNAL_ERROR.name()));

        assertThat(MDC.get(CorrelationId.LOG_CONTEXT_KEY)).isNull();
    }

    @Test
    @DisplayName("a failure below the resource is reported without its message")
    void failureIsReportedWithoutDetail() throws Exception {
        when(assembler.assemble())
                .thenThrow(new IllegalStateException("password authentication failed for marketops_app"));

        String body = mockMvc.perform(get(PATH))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password").doesNotContain("marketops_app");
        assertThat(body).contains(ErrorCode.INTERNAL_ERROR.safeMessage());
    }

    private static MetaStatusResponse sample() {
        return sampleWithCorrelationId("00000000-0000-4000-8000-000000000000");
    }

    private static MetaStatusResponse sampleWithCorrelationId(String correlationId) {
        return new MetaStatusResponse(
                "MarketOps Russia",
                "marketops-server",
                "test",
                "0.1.0-SNAPSHOT",
                "3ecc72ae509664ff0550f80ece98d4f50dbb0bc0",
                "2026-08-14T10:15:30Z",
                new MetaStatusResponse.DatabaseStatus("UP"),
                new MetaStatusResponse.MigrationStatus("1"),
                correlationId);
    }
}
