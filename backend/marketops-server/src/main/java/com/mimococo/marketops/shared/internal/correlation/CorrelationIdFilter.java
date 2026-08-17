package com.mimococo.marketops.shared.internal.correlation;

import com.mimococo.marketops.shared.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the correlation identifier for the duration of a request.
 *
 * <p>The filter runs first so every downstream record carries the identifier. The
 * logging context is cleared in a finally block: request threads are pooled, and a
 * leaked value would label an unrelated request with the previous one's identity.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CorrelationId.Result result =
                CorrelationId.validateOrGenerate(request.getHeader(CorrelationId.HEADER_NAME));

        MDC.put(CorrelationId.LOG_CONTEXT_KEY, result.value());
        response.setHeader(CorrelationId.HEADER_NAME, result.value());
        try {
            if (!result.acceptedInbound()) {
                // The rejected value itself is never recorded: it is untrusted input that
                // would land in the log it was rejected for being able to corrupt.
                log.atDebug()
                        .addKeyValue("event", "correlation_identifier_replaced")
                        .addKeyValue("correlationId", result.value())
                        .addKeyValue("rejectionCategory", result.rejectionReason().name())
                        .log("Inbound correlation identifier replaced");
            }
            chain.doFilter(request, response);
            log.atInfo()
                    .addKeyValue("event", "request_completed")
                    .addKeyValue("correlationId", result.value())
                    .addKeyValue("status", response.getStatus())
                    .log("Request completed");
        } finally {
            MDC.remove(CorrelationId.LOG_CONTEXT_KEY);
        }
    }
}
