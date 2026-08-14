package com.mimococo.marketops.shared.internal.errors;

import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * identifier. The exception, its message, and any SQL or configuration text it
 * may contain stay in the server log, so a caller learns how to report the
 * problem without learning how the system is built.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Report a rejected request body or parameter without repeating its content. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidationFailure(MethodArgumentNotValidException exception) {
        log.debug("Request validation failed", exception);
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED);
    }

    /** Report an unmapped path without disclosing which paths exist. */
    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleUnknownResource(NoResourceFoundException exception) {
        log.debug("No handler for request", exception);
        return problem(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** Report any other failure as a fixed message and log the cause in full. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpectedFailure(Exception exception) {
        log.error("Unhandled failure while processing a request", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR);
    }

    private ProblemDetail problem(HttpStatus status, ErrorCode code) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(code.name());
        detail.setDetail(code.safeMessage());
        detail.setProperty("correlationId", CorrelationId.current());
        return detail;
    }
}
