package com.mimococo.marketops.adminobservability.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.mimococo.marketops.adminobservability.audit.AuditActorType;
import com.mimococo.marketops.adminobservability.audit.AuditSourceDomain;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditDenial;
import com.mimococo.marketops.adminobservability.audit.MetadataAuditRecorder;
import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.OperationRejectedException;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.HandlerMapping;

class AdminMetadataProblemAdviceTest {

    private MetadataAuditRecorder auditRecorder;
    private AdminMetadataProblemAdvice advice;

    @BeforeEach
    void setUp() {
        auditRecorder = mock(MetadataAuditRecorder.class);
        advice = new AdminMetadataProblemAdvice(auditRecorder);
    }

    @Test
    void rejectionPreservesSafeConflictContextAndOperatorAttribution() {
        UUID conflict = UUID.randomUUID();
        MockHttpServletRequest request = mutation("POST");
        request.setAttribute(OperatorAttribution.REQUEST_ATTRIBUTE, "operator-1");

        ProblemDetail detail = advice.handleRejection(
                OperationRejectedException.duplicate(
                        "organizationaccount", "organization", "ORG-1", conflict),
                request);

        assertThat(detail.getStatus()).isEqualTo(409);
        assertThat(detail.getTitle()).isEqualTo("DUPLICATE_IDENTITY");
        assertThat(detail.getProperties()).containsEntry(
                "conflictingResourceId", conflict.toString());
        ArgumentCaptor<MetadataAuditDenial> denial =
                ArgumentCaptor.forClass(MetadataAuditDenial.class);
        verify(auditRecorder).recordDenial(denial.capture());
        assertThat(denial.getValue().sourceDomain())
                .isEqualTo(AuditSourceDomain.ORGANIZATION_ACCOUNT);
        assertThat(denial.getValue().actorType()).isEqualTo(AuditActorType.OPERATOR);
        assertThat(denial.getValue().actorId()).isEqualTo("operator-1");
    }

    @Test
    void writeGateAndValidationHandlersCoverMutationAndReadBoundaries() {
        MockHttpServletRequest gateRequest = mutation("PUT");
        gateRequest.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/admin/metadata/organizations/{id}");
        ProblemDetail gate = advice.handleRejection(
                OperationRejectedException.of(ErrorCode.MAINTENANCE_WRITE_DISABLED),
                gateRequest);
        assertThat(gate.getStatus()).isEqualTo(403);
        verify(auditRecorder).recordDenial(any());

        MockHttpServletRequest unattributedGateRequest = mutation("POST");
        advice.handleRejection(
                OperationRejectedException.of(ErrorCode.MAINTENANCE_WRITE_DISABLED),
                unattributedGateRequest);

        reset(auditRecorder);
        advice.handleInvalidArgument(mock(MethodArgumentNotValidException.class),
                mutation("POST"));
        advice.handleUnreadableBody(mock(HttpMessageNotReadableException.class),
                mutation("PATCH"));
        advice.handleInvalidBinding(mock(BindException.class), mutation("DELETE"));
        verify(auditRecorder, org.mockito.Mockito.times(3)).recordDenial(any());

        reset(auditRecorder);
        advice.handleInvalidArgument(mock(MethodArgumentNotValidException.class),
                mutation("GET"));
        advice.handleUnreadableBody(mock(HttpMessageNotReadableException.class),
                mutation("HEAD"));
        advice.handleInvalidBinding(mock(BindException.class), mutation("OPTIONS"));
        MockHttpServletRequest outsideBoundary = new MockHttpServletRequest(
                "POST", "/api/v1/public/metadata");
        advice.handleInvalidBinding(mock(BindException.class), outsideBoundary);
        advice.handleRejection(OperationRejectedException.of(ErrorCode.UNKNOWN_SCOPE),
                mutation("GET"));
        verify(auditRecorder, never()).recordDenial(any());
    }

    @Test
    void sqlStatesMapToStableSanitizedCodes() {
        assertSqlState("23505", ErrorCode.DUPLICATE_IDENTITY);
        assertSqlState("23P01", ErrorCode.EFFECTIVE_RANGE_OVERLAP);
        assertSqlState("23503", ErrorCode.VALIDATION_FAILED);
        assertSqlState("23514", ErrorCode.VALIDATION_FAILED);
        assertSqlState("99999", ErrorCode.INTERNAL_ERROR);

        reset(auditRecorder);
        ProblemDetail noState = advice.handleDataAccess(
                new DataIntegrityViolationException(
                        "safe-test", new SQLException("safe-test", (String) null)),
                mutation("POST"));
        assertThat(noState.getTitle()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        verify(auditRecorder, never()).recordDenial(any());

        reset(auditRecorder);
        ProblemDetail cyclicCause = advice.handleDataAccess(
                new DataIntegrityViolationException("safe-test", new CyclicCause()),
                mutation("POST"));
        assertThat(cyclicCause.getTitle()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
        verify(auditRecorder, never()).recordDenial(any());

        reset(auditRecorder);
        ProblemDetail readConflict = advice.handleDataAccess(
                failure("23505"), mutation("GET"));
        assertThat(readConflict.getTitle()).isEqualTo(ErrorCode.DUPLICATE_IDENTITY.name());
        verify(auditRecorder, never()).recordDenial(any());
    }

    private void assertSqlState(String sqlState, ErrorCode expected) {
        reset(auditRecorder);
        ProblemDetail detail = advice.handleDataAccess(failure(sqlState), mutation("POST"));
        assertThat(detail.getTitle()).isEqualTo(expected.name());
        if (expected == ErrorCode.INTERNAL_ERROR) {
            verify(auditRecorder, never()).recordDenial(any());
        } else {
            verify(auditRecorder).recordDenial(any());
        }
    }

    private static DataIntegrityViolationException failure(String sqlState) {
        return new DataIntegrityViolationException(
                "safe-test", new SQLException("safe-test", sqlState));
    }

    private static MockHttpServletRequest mutation(String method) {
        return new MockHttpServletRequest(method, "/api/v1/admin/metadata/test");
    }

    private static final class CyclicCause extends RuntimeException {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
