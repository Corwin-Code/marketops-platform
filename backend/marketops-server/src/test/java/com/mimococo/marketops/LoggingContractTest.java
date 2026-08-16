package com.mimococo.marketops;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MarkerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises the actual encoders selected by the local and CI profiles. */
class LoggingContractTest {

    @Test
    @DisplayName("the CI encoder emits parseable ECS JSON with the safe application schema")
    void ciEncoderProducesTheStructuredContract() throws IOException {
        StandardEnvironment environment = environment("application-ci.yaml");
        LoggerContext context = loggerContext(environment);
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat(environment.getRequiredProperty("logging.structured.format.console"));
        encoder.start();
        try {
            String rendered = new String(encoder.encode(event(context)), StandardCharsets.UTF_8);
            JsonNode record = new ObjectMapper().readTree(rendered);

            assertThat(record.path("@timestamp").stringValue()).isNotBlank();
            assertThat(record.path("log").path("level").stringValue()).isEqualTo("ERROR");
            assertThat(record.path("message").stringValue()).isEqualTo("Request processing failed");
            assertThat(record.path("application").stringValue()).isEqualTo("marketops-server");
            assertThat(record.path("environment").stringValue()).isEqualTo("ci");
            assertThat(record.path("buildVersion").stringValue()).isEqualTo("0.1.0-SNAPSHOT");
            assertThat(record.path("correlationId").stringValue()).isEqualTo("correlation-123");
            assertThat(record.path("event").stringValue()).isEqualTo("request_unhandled_failure");
            assertThat(record.path("errorCode").stringValue()).isEqualTo("INTERNAL_ERROR");
            assertThat(record.path("exceptionClass").stringValue())
                    .isEqualTo("java.lang.IllegalStateException");
            assertThat(record.has("tags")).isFalse();
            assertThat(record.has("error")).isFalse();
            assertThat(rendered).doesNotContain("excluded-marker", "private-message", "stack_trace");
        } finally {
            encoder.stop();
            context.stop();
        }
    }

    @Test
    @DisplayName("the local pattern renders one readable line with safe key-values")
    void localPatternProducesReadableSafeOutput() throws IOException {
        StandardEnvironment environment = environment("application-local.yaml");
        LoggerContext context = loggerContext(environment);
        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(environment.getRequiredProperty("logging.pattern.console"));
        layout.start();
        try {
            String rendered = layout.doLayout(event(context));

            assertThat(rendered.lines()).hasSize(1);
            assertThat(rendered)
                    .contains(
                            "ERROR",
                            "application=marketops-server",
                            "environment=local",
                            "buildVersion=0.1.0-SNAPSHOT",
                            "correlationId=correlation-123",
                            "Request processing failed",
                            "event=\"request_unhandled_failure\"",
                            "errorCode=\"INTERNAL_ERROR\"",
                            "exceptionClass=\"java.lang.IllegalStateException\"")
                    .doesNotContain("excluded-marker", "private-message");
        } finally {
            layout.stop();
            context.stop();
        }
    }

    private static LoggerContext loggerContext(Environment environment) {
        LoggerContext context = new LoggerContext();
        context.putObject(Environment.class.getName(), environment);
        context.start();
        return context;
    }

    private static LoggingEvent event(LoggerContext context) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.mimococo.marketops.LoggingContractTest");
        event.setLevel(Level.ERROR);
        event.setMessage("Request processing failed");
        event.setInstant(Instant.parse("2026-08-17T00:00:00Z"));
        event.setThreadName("logging-contract-test");
        event.setMDCPropertyMap(Map.of("correlationId", "correlation-123"));
        event.setKeyValuePairs(List.of(
                new KeyValuePair("event", "request_unhandled_failure"),
                new KeyValuePair("errorCode", "INTERNAL_ERROR"),
                new KeyValuePair("exceptionClass", "java.lang.IllegalStateException")));
        event.addMarker(MarkerFactory.getMarker("excluded-marker"));
        return event;
    }

    private static StandardEnvironment environment(String profile) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(load("application.yaml"));
        environment.getPropertySources().addFirst(load(profile));
        return environment;
    }

    private static PropertySource<?> load(String resource) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(resource, new ClassPathResource(resource));
        assertThat(sources).as(resource).hasSize(1);
        return sources.get(0);
    }
}
