package com.mimococo.marketops.shared.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mimococo.marketops.shared.CorrelationId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;

/** Verifies the complete browser boundary independently of application data. */
class CorsContractTest {

    private static final String PATH = "/api/v1/meta/status";
    private static final String DEVELOPMENT_ORIGIN = "http://127.0.0.1:5173";
    private static final String PREVIEW_ORIGIN = "http://127.0.0.1:4173";

    @Test
    @DisplayName("the base profile emits no CORS response headers")
    void emptyOriginListDisablesCors() throws Exception {
        client(List.of()).perform(get(PATH).header(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("both loopback console origins may read metadata")
    void localConsoleOriginsAreAllowed() throws Exception {
        MockMvc client = client(List.of(DEVELOPMENT_ORIGIN, PREVIEW_ORIGIN));
        client.perform(get(PATH).header(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEVELOPMENT_ORIGIN))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, CorrelationId.HEADER_NAME))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        client.perform(get(PATH).header(HttpHeaders.ORIGIN, PREVIEW_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PREVIEW_ORIGIN));
    }

    @Test
    @DisplayName("another origin is rejected")
    void unknownOriginIsRejected() throws Exception {
        client(List.of(DEVELOPMENT_ORIGIN, PREVIEW_ORIGIN))
                .perform(get(PATH).header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("an unknown configured origin prevents application startup")
    void unknownConfiguredOriginFailsBindingValidation() {
        new ApplicationContextRunner()
                .withUserConfiguration(WebConfig.class)
                .withPropertyValues(
                        "marketops.web.cors.allowed-origins[0]=http://localhost:5173")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("preflight permits only GET and the two request headers")
    void preflightContractIsFinite() throws Exception {
        MockMvc client = client(List.of(DEVELOPMENT_ORIGIN, PREVIEW_ORIGIN));
        client.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "Accept," + CorrelationId.HEADER_NAME))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DEVELOPMENT_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,OPTIONS"))
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Accept, X-Correlation-ID"));

        client.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
        client.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
                .andExpect(status().isForbidden());
    }

    @Test
    void diagnosticExportPreflightPermitsItsIdempotencyKeyWithoutAmbientCredentials() throws Exception {
        MockMvc client = client(List.of(DEVELOPMENT_ORIGIN));
        String export = "/api/v1/console/diagnosis/stores/00000000-0000-0000-0000-000000000001/exports";
        client.perform(options(export).header(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization,Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Idempotency-Key"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        client.perform(options(export).header(HttpHeaders.ORIGIN, "https://untrusted.example.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
        client.perform(options(export).header(HttpHeaders.ORIGIN, DEVELOPMENT_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Object-Ref"))
                .andExpect(status().isForbidden());
    }

    private static MockMvc client(List<String> allowedOrigins) {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(allowedOrigins);
        WebConfig configuration = new WebConfig();
        CorsFilter filter = configuration.corsFilter(configuration.corsConfigurationSource(properties));
        return MockMvcBuilders.standaloneSetup(new MetadataResource()).addFilters(filter).build();
    }

    @RestController
    private static final class MetadataResource {
        @GetMapping(PATH)
        String status() {
            return "ok";
        }
    }
}
