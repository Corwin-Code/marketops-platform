package com.mimococo.marketops.adminobservability.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MaintenanceWriteGate;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Boundary of every metadata maintenance mutation.
 *
 * <p>Two rules run before any handler: the environment must accept maintenance
 * writes, and the mutation must carry valid operator attribution. Queries pass
 * freely — the maintenance query surface is not gated.
 *
 * <p>A refusal thrown here reaches the shared maintenance problem boundary,
 * which returns the stable error and journals a truthful system-observed
 * denial. The rejected attribution value itself is never stored or logged.
 * Attribution is a recording obligation, not authentication; the surface stays
 * safe because the server binds to loopback and the write switch fails closed.
 */
@Component
class AdminMetadataGuard implements HandlerInterceptor {

    private final MaintenanceWriteGate writeGate;

    AdminMetadataGuard(MaintenanceWriteGate writeGate) {
        this.writeGate = writeGate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (isQuery(request)) {
            return true;
        }
        if (!writeGate.writeEnabled()) {
            throw OperationRejectedException.of(ErrorCode.MAINTENANCE_WRITE_DISABLED);
        }
        String operator = request.getHeader(OperatorAttribution.HEADER_NAME);
        if (operator == null || !MetadataFieldPolicy.OPERATOR.matcher(operator).matches()) {
            throw OperationRejectedException.of(ErrorCode.OPERATOR_ATTRIBUTION_MISSING);
        }
        request.setAttribute(OperatorAttribution.REQUEST_ATTRIBUTE, operator);
        return true;
    }

    private static boolean isQuery(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.OPTIONS.equals(method);
    }
}
