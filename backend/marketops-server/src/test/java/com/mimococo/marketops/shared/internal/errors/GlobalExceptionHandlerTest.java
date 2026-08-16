package com.mimococo.marketops.shared.internal.errors;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Establishes that a failure response carries a code and nothing else.
 *
 * <p>Each case asserts the absence of the detail the exception itself carried,
 * because the value of this handler is precisely what it withholds.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void clearLoggingContext() {
        MDC.remove(CorrelationId.LOG_CONTEXT_KEY);
    }

    @Test
    @DisplayName("a rejected request reports a validation code without the field detail")
    void validationFailureIsReducedToACode() throws NoSuchMethodException {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("accepts", String.class);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "payload");
        binding.reject("secretRuleName", "column ops_internal_value must be positive");
        MethodArgumentNotValidException exception =
                new MethodArgumentNotValidException(new MethodParameter(method, 0), binding);

        ProblemDetail detail = handler.handleValidationFailure(exception);

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(detail.getTitle()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(detail.getDetail()).isEqualTo(ErrorCode.VALIDATION_FAILED.safeMessage());
        assertThat(detail.getDetail()).doesNotContain("ops_internal_value");
    }

    @Test
    @DisplayName("an unmapped path reports a not-found code without naming known paths")
    void unknownResourceIsReducedToACode() {
        NoResourceFoundException exception =
                new NoResourceFoundException(HttpMethod.GET, "", "/api/v1/internal/secret");

        ProblemDetail detail = handler.handleUnknownResource(exception);

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(detail.getTitle()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.name());
        assertThat(detail.getDetail()).doesNotContain("internal");
    }

    @Test
    @DisplayName("an unexpected failure never returns the exception message")
    void unexpectedFailureIsReducedToACode() {
        Exception exception = new IllegalStateException(
                "FATAL: password authentication failed for user marketops_app at 10.0.0.7:5432");

        ProblemDetail detail = handler.handleUnexpectedFailure(exception);

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(detail.getTitle()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        assertThat(detail.getDetail()).isEqualTo(ErrorCode.INTERNAL_ERROR.safeMessage());
        assertThat(detail.getDetail()).doesNotContain("password", "marketops_app", "10.0.0.7");
    }

    @Test
    @DisplayName("the response quotes the identifier the request established")
    void correlationIdentifierIsCarried() {
        MDC.put(CorrelationId.LOG_CONTEXT_KEY, "established-value");

        ProblemDetail detail = handler.handleUnexpectedFailure(new IllegalStateException("failed"));

        assertThat(detail.getProperties()).containsEntry("correlationId", "established-value");
    }

    @Test
    @DisplayName("a failure outside a request still quotes an identifier")
    void correlationIdentifierIsAlwaysPresent() {
        ProblemDetail detail = handler.handleUnexpectedFailure(new IllegalStateException("failed"));

        assertThat(detail.getProperties()).containsKey("correlationId");
        assertThat((String) detail.getProperties().get("correlationId")).isNotBlank();
    }

    @Test
    @DisplayName("public-boundary logs discard exception messages and throwable proxies")
    void logsContainOnlySanitizedFailureCategories() throws NoSuchMethodException {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("accepts", String.class);
            BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "payload");
            binding.reject("secretRule", "SELECT password FROM ops WHERE role = marketops_app");
            handler.handleValidationFailure(
                    new MethodArgumentNotValidException(new MethodParameter(method, 0), binding));
            handler.handleUnknownResource(
                    new NoResourceFoundException(HttpMethod.GET, "", "/private/10.0.0.7:5432"));
            handler.handleUnexpectedFailure(new IllegalStateException(
                    "FATAL: password authentication failed for marketops_app at 10.0.0.7:5432"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(3).allSatisfy(event -> {
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).isEqualTo("Request processing failed");
        });
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN, Level.INFO, Level.ERROR);
        assertThat(rendered(appender.list))
                .contains(
                        "event=\"request_validation_failed\"",
                        "errorCode=\"VALIDATION_FAILED\"",
                        "event=\"request_resource_not_found\"",
                        "errorCode=\"RESOURCE_NOT_FOUND\"",
                        "event=\"request_unhandled_failure\"",
                        "errorCode=\"INTERNAL_ERROR\"",
                        "correlationId=",
                        "exceptionClass=")
                .doesNotContain("password", "marketops_app", "10.0.0.7", "5432", "SELECT",
                        "secretRule", "/private/");
    }

    private static String rendered(List<ILoggingEvent> events) {
        return events.stream()
                .map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs())
                .reduce("", (left, right) -> left + "\n" + right);
    }

    @SuppressWarnings("unused")
    private void accepts(String value) {
        // Target of the MethodParameter the validation exception needs. Referencing
        // the argument keeps the fixture honest if this helper is ever invoked.
        java.util.Objects.requireNonNull(value, "validation fixture value");
    }
}
