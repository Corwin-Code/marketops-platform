package com.mimococo.marketops.adminobservability.internal.web;

import com.mimococo.marketops.adminobservability.audit.OperatorAttribution;
import com.mimococo.marketops.shared.ErrorCode;
import com.mimococo.marketops.shared.MaintenanceWriteGate;
import com.mimococo.marketops.shared.MetadataFieldPolicy;
import com.mimococo.marketops.shared.OperationRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Boundary of every metadata maintenance mutation.
 *
 * <p>The actual socket peer must be loopback for every method. Mutations then
 * also require the environment write switch and valid operator attribution.
 * Forwarding headers are deliberately irrelevant to this boundary.
 *
 * <p>A refusal thrown here reaches the shared maintenance problem boundary,
 * which returns the stable error and journals a truthful system-observed
 * denial. The rejected attribution value itself is never stored or logged.
 * Attribution is a recording obligation, not authentication; the peer check is
 * enforced even in serving profiles that bind the application to all interfaces.
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
        if (!isLoopbackPeer(request.getRemoteAddr())) {
            throw OperationRejectedException.of(ErrorCode.MAINTENANCE_LOOPBACK_REQUIRED);
        }
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

    private static boolean isLoopbackPeer(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        if (!remoteAddress.contains(":")) {
            return isIpv4LoopbackLiteral(remoteAddress);
        }
        if (!remoteAddress.matches("[0-9A-Fa-f:.]+")) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (UnknownHostException invalidPeerAddress) {
            return false;
        }
    }

    /** Parse dotted IPv4 locally so a hostname-shaped value can never trigger DNS. */
    private static boolean isIpv4LoopbackLiteral(String remoteAddress) {
        String[] octets = remoteAddress.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        int first = -1;
        for (int index = 0; index < octets.length; index++) {
            String octet = octets[index];
            if (!octet.matches("[0-9]{1,3}")) {
                return false;
            }
            int value = Integer.parseInt(octet);
            if (value > 255) {
                return false;
            }
            if (index == 0) {
                first = value;
            }
        }
        return first == 127;
    }

    private static boolean isQuery(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        return HttpMethod.GET.equals(method)
                || HttpMethod.HEAD.equals(method)
                || HttpMethod.OPTIONS.equals(method);
    }
}
