package com.mimococo.marketops.shared.internal.errors;

import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Converts an unhandled failure into a problem detail that reveals nothing.
 *
 * <p>The response carries a stable code, a fixed message, and the correlation
 * identifier. Logs record only a fixed event, error code, correlation identifier
 * and exception class. Exception messages and stack traces are deliberately
 * discarded because they can carry SQL, credentials and connection details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Report a rejected request body or parameter without repeating its content. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationFailure(MethodArgumentNotValidException exception) {
        logSafely(log.atWarn(), "request_validation_failed", ErrorCode.VALIDATION_FAILED, exception);
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED);
    }

    /** Report an unmapped path without disclosing which paths exist. */
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleUnknownResource(NoResourceFoundException exception) {
        logSafely(log.atInfo(), "request_resource_not_found", ErrorCode.RESOURCE_NOT_FOUND, exception);
        return problem(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** Report any other failure as a fixed message and a sanitized log event. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedFailure(Exception exception) {
        logSafely(log.atError(), "request_unhandled_failure", ErrorCode.INTERNAL_ERROR, exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    }

    private void logSafely(LoggingEventBuilder builder,
                           String event,
                           ErrorCode code,
                           Exception exception) {
        builder
                .addKeyValue("event", event)
                .addKeyValue("errorCode", code.name())
                .addKeyValue("correlationId", CorrelationId.current())
                .addKeyValue("exceptionClass", exception.getClass().getName())
                .log("Request processing failed");
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(code.name());
        detail.setDetail(code.safeMessage());
        detail.setProperty("correlationId", CorrelationId.current());
        return detail;
    }
}
