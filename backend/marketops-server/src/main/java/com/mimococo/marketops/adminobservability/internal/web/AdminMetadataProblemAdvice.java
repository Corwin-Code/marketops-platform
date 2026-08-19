package com.mimococo.marketops.adminobservability.internal.web;

import com.mimococo.marketops.adminobservability.audit.AuditActorType;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditDenial;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.SQLException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Problem boundary of the metadata maintenance surface.
 *
 * <p>Every refusal becomes a stable error code with a sanitized message and the
 * request's correlation identifier, and every refused mutation attempt on the
 * maintenance surface is journaled as a DENIED audit event. When the request
 * carries validated operator attribution the denial names the operator;
 * otherwise it is truthfully attributed to the boundary itself as a system
 * observer — no operator identity is ever fabricated, and rejected input is
 * never echoed into the response, the log or the journal.
 *
 * <p>Constraint violations that reach the database concurrently with a
 * competing change are translated here from their SQL state into the same
 * stable codes the services use for their pre-checks, so an operator sees one
 * vocabulary regardless of which layer refused first.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminMetadataProblemAdvice {

    private static final Logger log = LoggerFactory.getLogger(AdminMetadataProblemAdvice.class);

    private static final String ADMIN_METADATA_PREFIX = "/api/v1/admin/metadata";

    private static final Map<ErrorCode, HttpStatus> STATUS = Map.ofEntries(
            Map.entry(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(ErrorCode.DUPLICATE_IDENTITY, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.VERSION_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.INVALID_STATE_TRANSITION, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.EFFECTIVE_RANGE_OVERLAP, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.CROSS_ORGANIZATION_REJECTED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.REFERENCED_ENTITY_ACTIVE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.INVALID_TIMEZONE, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.INVALID_CURRENCY, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.INVALID_COUNTRY, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.SECRET_REFERENCE_INVALID, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.SECRET_MATERIAL_SUSPECTED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.SERVICE_ACCOUNT_INACTIVE, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.UNKNOWN_SCOPE, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.CAPABILITY_VERIFICATION_NOT_SUPPORTED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.PRODUCTION_WRITE_DISABLED, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.MAINTENANCE_WRITE_DISABLED, HttpStatus.FORBIDDEN),
            Map.entry(ErrorCode.OPERATOR_ATTRIBUTION_MISSING, HttpStatus.BAD_REQUEST));

    private final MetadataAuditRecorder auditRecorder;

    AdminMetadataProblemAdvice(MetadataAuditRecorder auditRecorder) {
        this.auditRecorder = auditRecorder;
    }

    /** Refusals raised by guards, services and state machines. */
    @ExceptionHandler(OperationRejectedException.class)
    ProblemDetail handleRejection(OperationRejectedException rejection,
                                  HttpServletRequest request) {
        boolean journaled = journalDenial(request, rejection.errorCode(),
                rejection.auditDomain(), rejection.entityType(), rejection.entityCode());
        if (rejection.errorCode() == ErrorCode.MAINTENANCE_WRITE_DISABLED) {
            logWriteGateDenial(request);
        } else if (!journaled) {
            logRefusal(rejection.errorCode(), rejection.getClass().getName());
        }
        ProblemDetail detail = problem(rejection.errorCode());
        if (rejection.conflictingResourceId() != null) {
            detail.setProperty("conflictingResourceId",
                    rejection.conflictingResourceId().toString());
        }
        return detail;
    }

    /** Bean-validation failures, journaled when they refuse a maintenance mutation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidArgument(MethodArgumentNotValidException invalid,
                                        HttpServletRequest request) {
        if (!journalDenial(request, ErrorCode.VALIDATION_FAILED, null, null, null)) {
            logRefusal(ErrorCode.VALIDATION_FAILED, invalid.getClass().getName());
        }
        return problem(ErrorCode.VALIDATION_FAILED);
    }

    /** Unreadable or unknown-field request bodies; body content is never echoed. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException unreadable,
                                       HttpServletRequest request) {
        if (!journalDenial(request, ErrorCode.VALIDATION_FAILED, null, null, null)) {
            logRefusal(ErrorCode.VALIDATION_FAILED, unreadable.getClass().getName());
        }
        return problem(ErrorCode.VALIDATION_FAILED);
    }

    /** Conversion and binding refusals that occur after the maintenance guard. */
    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class,
            HandlerMethodValidationException.class,
            BindException.class
    })
    ProblemDetail handleInvalidBinding(Exception invalid, HttpServletRequest request) {
        if (!journalDenial(request, ErrorCode.VALIDATION_FAILED, null, null, null)) {
            logRefusal(ErrorCode.VALIDATION_FAILED, invalid.getClass().getName());
        }
        return problem(ErrorCode.VALIDATION_FAILED);
    }

    /**
     * Relational refusals that won a race against the service pre-checks.
     *
     * <p>The SQL state decides the code: unique violation is a duplicate,
     * exclusion violation is an interval overlap, and referential or check
     * violations are invalid input. Ownership services emit the more specific
     * cross-organization refusal before persistence. Anything else is an
     * internal failure and is logged sanitized.
     */
    @ExceptionHandler(DataAccessException.class)
    ProblemDetail handleDataAccess(DataAccessException failure, HttpServletRequest request) {
        ErrorCode code = translate(failure);
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.atError()
                    .addKeyValue("event", "maintenance_persistence_failure")
                    .addKeyValue("errorCode", code.name())
                    .addKeyValue("correlationId", CorrelationId.current())
                    .addKeyValue("exceptionClass", failure.getClass().getName())
                    .log("Metadata persistence failed");
            return problem(code);
        }
        if (!journalDenial(request, code, null, null, null)) {
            logRefusal(code, failure.getClass().getName());
        }
        return problem(code);
    }

    private static ErrorCode translate(DataAccessException failure) {
        String sqlState = sqlState(failure);
        if (sqlState == null) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (sqlState) {
            case "23505" -> ErrorCode.DUPLICATE_IDENTITY;
            case "23P01" -> ErrorCode.EFFECTIVE_RANGE_OVERLAP;
            case "23503" -> ErrorCode.VALIDATION_FAILED;
            case "23514" -> ErrorCode.VALIDATION_FAILED;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }

    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            if (current.getCause() == current) {
                return null;
            }
        }
        return null;
    }

    private boolean journalDenial(HttpServletRequest request,
                                  ErrorCode code,
                                  String auditDomain,
                                  String entityType,
                                  String entityCode) {
        if (!isMaintenanceMutation(request)) {
            return false;
        }
        Object operator = request.getAttribute(OperatorAttribution.REQUEST_ATTRIBUTE);
        auditRecorder.recordDenial(new MetadataAuditDenial(
                sourceDomain(auditDomain),
                operator == null ? AuditActorType.SYSTEM : AuditActorType.OPERATOR,
                operator == null ? OperatorAttribution.BOUNDARY_RECORDER : operator.toString(),
                code.name(),
                entityType,
                null,
                entityCode,
                null));
        return true;
    }

    private static void logWriteGateDenial(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object operator = request.getAttribute(OperatorAttribution.REQUEST_ATTRIBUTE);
        log.atWarn()
                .addKeyValue("event", "maintenance_write_disabled_denied")
                .addKeyValue("pathTemplate",
                        pattern == null ? "unmatched" : pattern.toString())
                .addKeyValue("actorId", operator == null
                        ? OperatorAttribution.BOUNDARY_RECORDER : operator.toString())
                .addKeyValue("correlationId", CorrelationId.current())
                .log("Maintenance write refused by the environment gate");
    }

    private static boolean isMaintenanceMutation(HttpServletRequest request) {
        String method = request.getMethod();
        return request.getRequestURI().startsWith(ADMIN_METADATA_PREFIX)
                && !"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method);
    }

    private static AuditSourceDomain sourceDomain(String auditDomain) {
        if (auditDomain == null) {
            return AuditSourceDomain.ADMIN_OBSERVABILITY;
        }
        return AuditSourceDomain.fromDbValue(auditDomain);
    }

    private static void logRefusal(ErrorCode code, String exceptionClass) {
        log.atWarn()
                .addKeyValue("event", "maintenance_request_denied")
                .addKeyValue("errorCode", code.name())
                .addKeyValue("correlationId", CorrelationId.current())
                .addKeyValue("exceptionClass", exceptionClass)
                .log("Maintenance request denied");
    }

    private static ProblemDetail problem(ErrorCode code) {
        ProblemDetail detail = ProblemDetail.forStatus(STATUS.get(code));
        detail.setTitle(code.name());
        detail.setDetail(code.safeMessage());
        detail.setInstance(URI.create(ADMIN_METADATA_PREFIX));
        detail.setProperty("correlationId", CorrelationId.current());
        return detail;
    }
}
