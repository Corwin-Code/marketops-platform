package com.mimococo.marketops.shared.internal.errors;

import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem boundary of the operating console.
 *
 * <p>Every refusal the console can produce becomes a stable code, a fixed
 * message and the request's correlation identifier. The mapping from code to
 * status is exhaustive by construction: an unmapped code falls through to
 * {@code 422}, which is truthful for a business refusal and never leaks the
 * unhandled case as a server error.
 *
 * <p>Nothing about the refused request is echoed. The codes were designed to be
 * safe to return, and the diagnostic detail stays in the log under the same
 * correlation identifier the caller received.
 */
@RestControllerAdvice(annotations = com.mimococo.marketops.shared.ConsoleApi.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConsoleProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(ConsoleProblemAdvice.class);

    private static final Map<ErrorCode, HttpStatus> STATUS = Map.ofEntries(
            Map.entry(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(ErrorCode.DUPLICATE_IDENTITY, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.VERSION_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.INVALID_STATE_TRANSITION, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.EFFECTIVE_RANGE_OVERLAP, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.CROSS_ORGANIZATION_REJECTED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.INVALID_CURRENCY, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.CURRENCY_MISMATCH, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.SECRET_MATERIAL_SUSPECTED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.AUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.MULTI_FACTOR_REQUIRED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.STEP_UP_REQUIRED, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.USER_NOT_PROVISIONED, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.USER_INACTIVE, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.ACTION_NOT_PERMITTED, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.RESOURCE_SCOPE_DENIED, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.APPROVAL_EVIDENCE_SCOPE_BLOCKED, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.IDENTITY_PROVIDER_NOT_ACCEPTED, HttpStatus.UNAUTHORIZED),
            Map.entry(ErrorCode.IMPORT_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE),
            Map.entry(ErrorCode.EXPORT_QUEUE_FULL, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(ErrorCode.EXPORT_UNAVAILABLE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.EXPORT_INTEGRITY_FAILED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.IMPORT_DUPLICATE_CONTENT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PRODUCTION_WRITE_DISABLED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.WRITE_GATE_CLOSED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.COMMAND_STATE_INVALID, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.RECOMMENDATION_STALE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.APPROVAL_REQUIRED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.COMPENSATION_UNSAFE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.READBACK_REQUIRED, HttpStatus.CONFLICT));

    /**
     * The status a business refusal that is not in the table above is answered
     * with.
     *
     * <p>Unprocessable content is the honest answer: the request was understood
     * and rejected on its merits. Falling back to a server error would blame the
     * server for a decision the product made deliberately.
     */
    private static final HttpStatus BUSINESS_REFUSAL = HttpStatus.UNPROCESSABLE_ENTITY;

    /** Render a business refusal raised anywhere under the console surface. */
    @ExceptionHandler(OperationRejectedException.class)
    ProblemDetail handleRefusal(OperationRejectedException exception) {
        ErrorCode code = exception.errorCode();
        HttpStatus status = STATUS.getOrDefault(code, BUSINESS_REFUSAL);
        log.atInfo()
                .addKeyValue("event", "console_request_refused")
                .addKeyValue("errorCode", code.name())
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Console request refused");

        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(code.name());
        detail.setDetail(code.safeMessage());
        detail.setProperty("correlationId", CorrelationId.current());
        return detail;
    }

    /** Parsing failures stay on the console surface and never echo the rejected body. */
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class})
    ProblemDetail invalidRequest() {
        return handleRefusal(OperationRejectedException.of(ErrorCode.VALIDATION_FAILED));
    }

    /** A relational race is translated without exposing SQL, values or connection details. */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ProblemDetail relationalRefusal(org.springframework.dao.DataAccessException failure) {
        String state = "";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                state = java.util.Objects.toString(sql.getSQLState(), ""); break;
            }
        }
        ErrorCode code = switch (state) {
            case "23505", "23P01", "MO061", "MO063", "MO065" -> ErrorCode.VERSION_CONFLICT;
            case "23503", "23514", "22007", "22008", "22003", "22P02", "MO036", "MO039" -> ErrorCode.VALIDATION_FAILED;
            case "MO060", "MO064" -> ErrorCode.RESOURCE_SCOPE_DENIED;
            case "MO062" -> ErrorCode.IMPORT_VALIDATION_FAILED;
            case "MO080" -> ErrorCode.EXPORT_QUEUE_FULL;
            case "MO084" -> ErrorCode.EXPORT_UNAVAILABLE;
            default -> ErrorCode.INTERNAL_ERROR;
        };
        return handleRefusal(OperationRejectedException.of(code));
    }
}
