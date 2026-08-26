package com.mimococo.marketops.identityaccess.internal.web;

import tools.jackson.databind.ObjectMapper;
import com.mimococo.marketops.shared.CorrelationId;
import com.mimococo.marketops.shared.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Renders an identity refusal in the same shape as every other refusal.
 *
 * <p>Two statuses are distinguished on purpose. A missing or unusable token is
 * {@code 401}: re-authenticating can fix it. A token that resolved to a profile
 * this deployment will not act for is {@code 403}: re-authenticating cannot fix
 * it, and answering {@code 401} would send an operator into a login loop that
 * hides the real problem.
 *
 * <p>The body carries the stable code, its fixed message and the correlation
 * identifier, and nothing else. No claim, subject, issuer or provider detail is
 * echoed to a caller who has just failed to authenticate.
 */
@Component
public class IdentityProblemEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    IdentityProblemEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (exception instanceof IdentityRefusedException refused) {
            write(response, statusFor(refused.errorCode()), refused.errorCode());
            return;
        }
        write(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(response, HttpStatus.FORBIDDEN, ErrorCode.ACTION_NOT_PERMITTED);
    }

    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case IDENTITY_PROVIDER_NOT_ACCEPTED, MULTI_FACTOR_REQUIRED, AUTHENTICATION_REQUIRED ->
                    HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.FORBIDDEN;
        };
    }

    private void write(HttpServletResponse response, HttpStatus status, ErrorCode code)
            throws IOException {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setTitle(code.name());
        detail.setDetail(code.safeMessage());
        detail.setProperty("correlationId", CorrelationId.current());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), detail);
    }
}
